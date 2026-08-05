package com.paisalens.app.ui

import android.Manifest
import android.content.ContentResolver
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.OpenableColumns
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.paisalens.app.PaisaLensApplication
import com.paisalens.app.data.model.AccountProfile
import com.paisalens.app.data.model.AccountType
import com.paisalens.app.data.model.CategorySelection
import com.paisalens.app.data.model.CustomCategory
import com.paisalens.app.data.model.ExpenseCategory
import com.paisalens.app.data.model.LoanAccount
import com.paisalens.app.data.model.ReceiptOcrDraft
import com.paisalens.app.data.model.StatementImportPreview
import com.paisalens.app.data.model.TransactionRecord
import com.paisalens.app.data.model.TransactionType
import com.paisalens.app.data.model.sanitizeTags
import com.paisalens.app.data.model.normalizedCurrency
import com.paisalens.app.data.export.PaisaLensWorkbookExporter
import com.paisalens.app.data.ocr.ReceiptOcrProcessor
import com.paisalens.app.data.parser.ReceiptTextParser
import com.paisalens.app.sms.SmsInboxScanner
import com.paisalens.app.widget.PaisaLensWidgetProvider
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class PaisaLensViewModel(
    private val app: PaisaLensApplication,
) : ViewModel() {
    val transactions = app.repository.transactions
    val budgets = app.repository.budgets
    val categorizedMerchantKeys = app.repository.categorizedMerchantKeys
    val accounts = app.repository.accounts
    val customCategories = app.repository.customCategories
    val recurringPayments = app.repository.recurringPayments
    val loans = app.repository.loans
    val exchangeRates = app.repository.exchangeRates
    val merchantAliases = app.repository.merchantAliases
    val insights = app.repository.insights

    private val receiptOcrProcessor = ReceiptOcrProcessor()
    private val receiptTextParser = ReceiptTextParser(app.parser::categorize)

    private val _isScanning = MutableStateFlow(false)
    val isScanning = _isScanning.asStateFlow()

    private val _onboardingComplete = MutableStateFlow(app.preferences.onboardingComplete)
    val onboardingComplete = _onboardingComplete.asStateFlow()

    private val _darkMode = MutableStateFlow(app.preferences.darkMode)
    val darkMode = _darkMode.asStateFlow()

    private val _lastScanAt = MutableStateFlow(app.preferences.lastScanAt)
    val lastScanAt = _lastScanAt.asStateFlow()

    private val _appLockEnabled = MutableStateFlow(app.preferences.appLockEnabled)
    val appLockEnabled = _appLockEnabled.asStateFlow()

    private val _widgetAmountsVisible = MutableStateFlow(app.preferences.widgetAmountsVisible)
    val widgetAmountsVisible = _widgetAmountsVisible.asStateFlow()

    private val _travelModeEnabled = MutableStateFlow(app.preferences.travelModeEnabled)
    val travelModeEnabled = _travelModeEnabled.asStateFlow()

    private val _baseCurrency = MutableStateFlow(app.preferences.baseCurrency)
    val baseCurrency = _baseCurrency.asStateFlow()

    private val _statementPreview = MutableStateFlow<StatementImportPreview?>(null)
    val statementPreview = _statementPreview.asStateFlow()

    private val _isImporting = MutableStateFlow(false)
    val isImporting = _isImporting.asStateFlow()

    private val _isRefreshingRate = MutableStateFlow(false)
    val isRefreshingRate = _isRefreshingRate.asStateFlow()

    private val _receiptDraft = MutableStateFlow<ReceiptOcrDraft?>(null)
    val receiptDraft = _receiptDraft.asStateFlow()

    private val _isReceiptOcrRunning = MutableStateFlow(false)
    val isReceiptOcrRunning = _isReceiptOcrRunning.asStateFlow()

    private val _events = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val events = _events.asSharedFlow()

    init {
        viewModelScope.launch { app.repository.load() }
    }

    fun completeOnboarding() {
        app.preferences.onboardingComplete = true
        _onboardingComplete.value = true
    }

    fun setDarkMode(enabled: Boolean) {
        app.preferences.darkMode = enabled
        _darkMode.value = enabled
    }

    fun setAppLock(enabled: Boolean) {
        app.preferences.appLockEnabled = enabled
        _appLockEnabled.value = enabled
        if (enabled) {
            app.preferences.widgetAmountsVisible = false
            _widgetAmountsVisible.value = false
        }
        PaisaLensWidgetProvider.updateAll(app)
    }

    fun setWidgetAmountsVisible(enabled: Boolean) {
        app.preferences.widgetAmountsVisible = enabled
        _widgetAmountsVisible.value = enabled
        PaisaLensWidgetProvider.updateAll(app)
    }

    fun setTravelMode(enabled: Boolean) {
        app.preferences.travelModeEnabled = enabled
        _travelModeEnabled.value = enabled
    }

    fun setBaseCurrency(currency: String) {
        val clean = currency.normalizedCurrency()
        app.preferences.baseCurrency = clean
        _baseCurrency.value = clean
    }

    fun scanSms(context: Context) {
        if (
            ContextCompat.checkSelfPermission(context, Manifest.permission.READ_SMS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            _events.tryEmit("Enable SMS access to scan transaction alerts")
            return
        }
        if (_isScanning.value) return

        viewModelScope.launch {
            _isScanning.value = true
            runCatching {
                val scan = SmsInboxScanner(context, app.parser, app.availabilityParser).scan()
                app.repository.ingestSms(scan.transactions, scan.availabilityUpdates)
            }.onSuccess { result ->
                val now = System.currentTimeMillis()
                app.preferences.lastScanAt = now
                _lastScanAt.value = now
                _events.emit(
                    when {
                        result.insertedTransactions > 0 -> {
                            "${result.insertedTransactions} new transaction" +
                                (if (result.insertedTransactions == 1) "" else "s") + " added" +
                                (if (result.updatedAccounts > 0) " · balances updated" else "")
                        }
                        result.updatedAccounts > 0 -> "Account balances updated"
                        else -> "You're up to date"
                    },
                )
            }.onFailure {
                _events.emit("Could not scan SMS. Check permission and try again")
            }
            _isScanning.value = false
        }
    }

    fun recognizeReceipt(uri: Uri, sourceLabel: String, deleteAfterProcessing: Boolean = false) {
        if (_isReceiptOcrRunning.value) return
        viewModelScope.launch {
            _isReceiptOcrRunning.value = true
            runCatching { receiptOcrProcessor.recognize(app, uri) }
                .onSuccess { text ->
                    if (text.isBlank()) {
                        _events.emit("No readable text found. Try a clearer bill image")
                    } else {
                        _receiptDraft.value = receiptTextParser.parse(text, sourceLabel)
                        _events.emit("Bill details extracted locally. Review before saving")
                    }
                }
                .onFailure { _events.emit("Could not read that bill. Try a clearer image") }
            if (deleteAfterProcessing) runCatching { app.contentResolver.delete(uri, null, null) }
            _isReceiptOcrRunning.value = false
        }
    }

    fun clearReceiptDraft() {
        _receiptDraft.value = null
    }

    fun addManual(
        amountMinor: Long,
        merchant: String,
        category: CategorySelection,
        type: TransactionType,
        note: String?,
        accountId: Long?,
        tags: String,
        originalAmountMinor: Long? = null,
        originalCurrency: String? = null,
        exchangeRate: Double? = null,
    ) {
        viewModelScope.launch {
            app.repository.addManual(
                amountMinor = amountMinor,
                merchant = merchant,
                category = category,
                type = type,
                note = note,
                accountId = accountId,
                tags = sanitizeTags(tags),
                originalAmountMinor = originalAmountMinor,
                originalCurrency = originalCurrency,
                exchangeRate = exchangeRate,
            )
            _events.emit("Transaction added")
        }
    }

    fun updateCategory(
        transaction: TransactionRecord,
        category: CategorySelection,
    ) {
        viewModelScope.launch {
            val updated = app.repository.updateCategory(transaction, category)
            _events.emit(
                if (transaction.type == TransactionType.EXPENSE && updated > 1) {
                    "$updated ${transaction.merchant} expenses updated"
                } else {
                    "Category updated"
                },
            )
        }
    }

    fun updateMerchantCategory(merchant: String, category: ExpenseCategory) {
        updateMerchantCategory(merchant, CategorySelection(category))
    }

    fun updateMerchantCategory(merchant: String, category: CategorySelection) {
        viewModelScope.launch {
            val updated = app.repository.updateMerchantCategory(merchant, category)
            _events.emit(
                "$updated matching expense" + (if (updated == 1) "" else "s") + " updated",
            )
        }
    }

    fun updateNote(id: Long, note: String) {
        viewModelScope.launch {
            app.repository.updateNote(id, note)
            _events.emit(if (note.isBlank()) "Note removed" else "Note saved")
        }
    }

    fun updateTags(id: Long, tags: String) {
        viewModelScope.launch {
            app.repository.updateTags(id, sanitizeTags(tags))
            _events.emit("Tags saved")
        }
    }

    fun confirmTransaction(id: Long) {
        viewModelScope.launch {
            app.repository.confirmTransaction(id)
            _events.emit("Transaction confirmed")
        }
    }

    fun updateTransactionAccount(id: Long, accountId: Long?) {
        viewModelScope.launch {
            app.repository.updateTransactionAccount(id, accountId)
            _events.emit("Account updated")
        }
    }

    fun updateTransactionType(id: Long, type: TransactionType) {
        viewModelScope.launch {
            app.repository.updateTransactionType(id, type)
            _events.emit(if (type == TransactionType.TRANSFER) "Marked as transfer" else "Transaction type updated")
        }
    }

    fun addAccount(name: String, type: AccountType, accountHint: String?) {
        viewModelScope.launch {
            runCatching { app.repository.addAccount(name, type, accountHint) }
                .onSuccess { _events.emit("Account added") }
                .onFailure { _events.emit("Could not add account") }
        }
    }

    fun updateAccount(account: AccountProfile) {
        viewModelScope.launch {
            app.repository.updateAccount(account)
            _events.emit("Account saved")
        }
    }

    fun deleteAccount(id: Long) {
        viewModelScope.launch {
            app.repository.deleteAccount(id)
            _events.emit("Account removed; transactions were kept")
        }
    }

    fun addCustomCategory(name: String, colorHex: String) {
        addCustomCategory(name, colorHex) { }
    }

    fun addCustomCategory(
        name: String,
        colorHex: String,
        onAdded: (CustomCategory) -> Unit,
    ) {
        viewModelScope.launch {
            runCatching { app.repository.addCustomCategory(name, colorHex) }
                .onSuccess { category ->
                    onAdded(category)
                    _events.emit("${category.name} category added")
                }
                .onFailure { _events.emit("That category name already exists") }
        }
    }

    fun updateCustomCategory(category: CustomCategory) {
        viewModelScope.launch {
            runCatching { app.repository.updateCustomCategory(category) }
                .onSuccess { _events.emit("Custom category saved") }
                .onFailure { _events.emit("Could not save category") }
        }
    }

    fun deleteCustomCategory(id: Long) {
        viewModelScope.launch {
            app.repository.deleteCustomCategory(id)
            _events.emit("Custom category removed")
        }
    }

    fun renameMerchant(aliasName: String, canonicalName: String) {
        viewModelScope.launch {
            runCatching { app.repository.renameMerchant(aliasName, canonicalName) }
                .onSuccess { count -> _events.emit("$count transaction${if (count == 1) "" else "s"} renamed") }
                .onFailure { _events.emit(it.message ?: "Could not rename merchant") }
        }
    }

    fun deleteMerchantAlias(aliasKey: String) {
        viewModelScope.launch {
            app.repository.deleteMerchantAlias(aliasKey)
            _events.emit("Merchant cleanup rule removed")
        }
    }

    fun previewStatement(contentResolver: ContentResolver, uri: Uri, accountId: Long?) {
        if (_isImporting.value) return
        viewModelScope.launch {
            _isImporting.value = true
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val fileName = contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                        ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
                        ?: "statement.csv"
                    val input = contentResolver.openInputStream(uri) ?: error("Could not open the selected statement")
                    input.use {
                        app.repository.previewStatement(it, fileName, accountId, _baseCurrency.value)
                    }
                }
            }
            _isImporting.value = false
            result.onSuccess { preview ->
                _statementPreview.value = preview
                if (preview.rows.isEmpty()) _events.emit(preview.warnings.firstOrNull() ?: "No transactions found")
            }.onFailure { _events.emit(it.message ?: "Could not read statement") }
        }
    }

    fun confirmStatementImport() {
        val preview = _statementPreview.value ?: return
        viewModelScope.launch {
            _isImporting.value = true
            runCatching { app.repository.importStatement(preview) }
                .onSuccess { result ->
                    _statementPreview.value = null
                    _events.emit("${result.imported} imported · ${result.duplicates} duplicate${if (result.duplicates == 1) "" else "s"} skipped")
                }
                .onFailure { _events.emit(it.message ?: "Could not import statement") }
            _isImporting.value = false
        }
    }

    fun cancelStatementImport() {
        _statementPreview.value = null
    }

    fun saveLoan(loan: LoanAccount) {
        viewModelScope.launch {
            runCatching { app.repository.saveLoan(loan) }
                .onSuccess { _events.emit("Loan tracker saved") }
                .onFailure { _events.emit(it.message ?: "Could not save loan") }
        }
    }

    fun deleteLoan(id: Long) {
        viewModelScope.launch {
            app.repository.deleteLoan(id)
            _events.emit("Loan tracker removed")
        }
    }

    fun refreshExchangeRate(foreignCurrency: String) {
        if (_isRefreshingRate.value) return
        viewModelScope.launch {
            _isRefreshingRate.value = true
            runCatching { app.repository.refreshExchangeRate(foreignCurrency, _baseCurrency.value) }
                .onSuccess { rate -> _events.emit("${rate.quoteCurrency}/${rate.baseCurrency} reference rate updated") }
                .onFailure { _events.emit("Could not refresh rate. Check the connection and try again") }
            _isRefreshingRate.value = false
        }
    }

    fun deleteTransaction(id: Long) {
        viewModelScope.launch {
            app.repository.deleteTransaction(id)
            _events.emit("Transaction removed")
        }
    }

    fun setBudget(category: ExpenseCategory, limitMinor: Long) {
        viewModelScope.launch {
            app.repository.setBudget(category, limitMinor)
            _events.emit(if (limitMinor > 0) "Budget saved" else "Budget removed")
        }
    }

    fun exportWorkbook(contentResolver: ContentResolver, uri: Uri) {
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val output = contentResolver.openOutputStream(uri, "w")
                        ?: error("Could not open the selected file")
                    output.use {
                        PaisaLensWorkbookExporter.write(
                            transactions = transactions.value,
                            budgets = budgets.value,
                            outputStream = it,
                        )
                    }
                }
            }
            _events.emit(
                if (result.isSuccess) {
                    "Excel report exported"
                } else {
                    "Could not export Excel report"
                },
            )
        }
    }

    fun exportBackup(contentResolver: ContentResolver, uri: Uri, passphrase: CharArray) {
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                try {
                    runCatching {
                        val output = contentResolver.openOutputStream(uri, "w")
                            ?: error("Could not open the selected file")
                        output.use { app.repository.writeBackup(it, passphrase) }
                    }
                } finally {
                    passphrase.fill('\u0000')
                }
            }
            _events.emit(if (result.isSuccess) "Encrypted backup created" else result.exceptionOrNull()?.message ?: "Could not create backup")
        }
    }

    fun restoreBackup(contentResolver: ContentResolver, uri: Uri, passphrase: CharArray) {
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                try {
                    runCatching {
                        val input = contentResolver.openInputStream(uri)
                            ?: error("Could not open the selected file")
                        input.use { app.repository.restoreBackup(it, passphrase) }
                    }
                } finally {
                    passphrase.fill('\u0000')
                }
            }
            _events.emit(
                result.fold(
                    onSuccess = { count -> "$count transactions restored" },
                    onFailure = { it.message ?: "Could not restore backup" },
                ),
            )
        }
    }

    fun clearAll() {
        viewModelScope.launch {
            app.repository.clearAll()
            app.preferences.lastScanAt = 0L
            _lastScanAt.value = 0L
            app.preferences.appLockEnabled = false
            app.preferences.widgetAmountsVisible = false
            app.preferences.travelModeEnabled = false
            app.preferences.baseCurrency = "INR"
            _appLockEnabled.value = false
            _widgetAmountsVisible.value = false
            _travelModeEnabled.value = false
            _baseCurrency.value = "INR"
            PaisaLensWidgetProvider.updateAll(app)
            _events.emit("All local financial data erased")
        }
    }

    class Factory(
        private val app: PaisaLensApplication,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return PaisaLensViewModel(app) as T
        }
    }
}
