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
import com.paisalens.app.data.model.AppThemeConfiguration
import com.paisalens.app.data.model.BillReminder
import com.paisalens.app.data.model.BackupVerificationMetadata
import com.paisalens.app.data.model.AuditUndoResult
import com.paisalens.app.data.model.CategorySelection
import com.paisalens.app.data.model.CustomCategory
import com.paisalens.app.data.model.ExpenseCategory
import com.paisalens.app.data.model.ExpenseSplit
import com.paisalens.app.data.model.HomeLayoutConfiguration
import com.paisalens.app.data.model.LoanAccount
import com.paisalens.app.data.model.MonthlyReconciliation
import com.paisalens.app.data.model.NetWorthItem
import com.paisalens.app.data.model.NotificationDigestConfiguration
import com.paisalens.app.data.model.PaymentCommitment
import com.paisalens.app.data.model.ReceiptOcrDraft
import com.paisalens.app.data.model.SavingsContribution
import com.paisalens.app.data.model.SavingsGoal
import com.paisalens.app.data.model.StatementImportPreview
import com.paisalens.app.data.model.SmartCategoryRule
import com.paisalens.app.data.model.StatementAuditMetadata
import com.paisalens.app.data.model.StatementAuditReport
import com.paisalens.app.data.model.StatementAuditRow
import com.paisalens.app.data.model.TransactionRecord
import com.paisalens.app.data.model.TransactionLink
import com.paisalens.app.data.model.TransactionLinkSuggestion
import com.paisalens.app.data.model.TransactionType
import com.paisalens.app.data.model.sanitizeTags
import com.paisalens.app.data.model.normalizedCurrency
import com.paisalens.app.data.export.PaisaLensWorkbookExporter
import com.paisalens.app.data.importer.CreditCardStatementAuditor
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
    val effectiveExpenseTransactions = app.repository.effectiveExpenseTransactions
    val budgets = app.repository.budgets
    val categorizedMerchantKeys = app.repository.categorizedMerchantKeys
    val accounts = app.repository.accounts
    val balanceHistory = app.repository.balanceHistory
    val bills = app.repository.bills
    val netWorthItems = app.repository.netWorthItems
    val smartCategoryRules = app.repository.smartCategoryRules
    val reconciliations = app.repository.reconciliations
    val transactionLinks = app.repository.transactionLinks
    val transactionLinkSuggestions = app.repository.transactionLinkSuggestions
    val auditEvents = app.repository.auditEvents
    val auditBatches = app.repository.auditBatches
    val dataHealth = app.repository.dataHealth
    val customCategories = app.repository.customCategories
    val recurringPayments = app.repository.recurringPayments
    val loans = app.repository.loans
    val exchangeRates = app.repository.exchangeRates
    val merchantAliases = app.repository.merchantAliases
    val insights = app.repository.insights
    val expenseSplits = app.repository.expenseSplits
    val savingsGoals = app.repository.savingsGoals
    val savingsContributions = app.repository.savingsContributions
    val paymentCommitments = app.repository.paymentCommitments
    val paymentCommitmentSuggestions = app.repository.paymentCommitmentSuggestions
    val themeConfiguration = app.preferences.themeConfiguration
    val homeLayout = app.preferences.homeLayout
    val notificationDigest = app.preferences.notificationDigest

    private val receiptOcrProcessor = ReceiptOcrProcessor()
    private val receiptTextParser = ReceiptTextParser(app.parser::categorize)

    private val _isScanning = MutableStateFlow(false)
    val isScanning = _isScanning.asStateFlow()

    private val _onboardingComplete = MutableStateFlow(app.preferences.onboardingComplete)
    val onboardingComplete = _onboardingComplete.asStateFlow()

    private val _lastScanAt = MutableStateFlow(app.preferences.lastScanAt)
    val lastScanAt = _lastScanAt.asStateFlow()

    private val _lastBackupCreatedAt = MutableStateFlow(app.preferences.lastBackupCreatedAt)
    val lastBackupCreatedAt = _lastBackupCreatedAt.asStateFlow()

    private val _lastBackupVerifiedAt = MutableStateFlow(app.preferences.lastBackupVerifiedAt)
    val lastBackupVerifiedAt = _lastBackupVerifiedAt.asStateFlow()

    private val _backupVerification = MutableStateFlow<BackupVerificationMetadata?>(null)
    val backupVerification = _backupVerification.asStateFlow()

    private val _backupVerificationError = MutableStateFlow<String?>(null)
    val backupVerificationError = _backupVerificationError.asStateFlow()

    private val _isVerifyingBackup = MutableStateFlow(false)
    val isVerifyingBackup = _isVerifyingBackup.asStateFlow()

    private val _lastUndoResult = MutableStateFlow<AuditUndoResult?>(null)
    val lastUndoResult = _lastUndoResult.asStateFlow()

    private val _undoInProgressBatchId = MutableStateFlow<String?>(null)
    val undoInProgressBatchId = _undoInProgressBatchId.asStateFlow()

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

    private val _statementAuditReport = MutableStateFlow<StatementAuditReport?>(null)
    val statementAuditReport = _statementAuditReport.asStateFlow()

    private val _statementAuditError = MutableStateFlow<String?>(null)
    val statementAuditError = _statementAuditError.asStateFlow()

    private val _isAuditingStatement = MutableStateFlow(false)
    val isAuditingStatement = _isAuditingStatement.asStateFlow()

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

    fun setThemeConfiguration(configuration: AppThemeConfiguration) {
        app.preferences.setThemeConfiguration(configuration)
        PaisaLensWidgetProvider.scheduleUpdateAll(app, delayMillis = 250L)
    }

    fun setHomeLayout(configuration: HomeLayoutConfiguration) {
        app.preferences.setHomeLayout(configuration)
    }

    fun setNotificationDigest(configuration: NotificationDigestConfiguration) {
        app.preferences.setNotificationDigest(configuration)
    }

    fun setNotificationDigestEnabled(enabled: Boolean) {
        app.preferences.setNotificationDigestEnabled(enabled)
    }

    fun saveExpenseSplit(split: ExpenseSplit) {
        viewModelScope.launch {
            runCatching { app.repository.saveExpenseSplit(split) }
                .onSuccess { _events.emit("Shared expense saved") }
                .onFailure { _events.emit(it.message ?: "Could not save that split") }
        }
    }

    fun deleteExpenseSplit(id: Long) {
        viewModelScope.launch {
            app.repository.deleteExpenseSplit(id)
            _events.emit("Participant removed")
        }
    }

    fun replaceExpenseSplits(
        transactionId: Long,
        splits: List<ExpenseSplit>,
        deletedIds: Set<Long>,
        onComplete: (Boolean) -> Unit = {},
    ) {
        viewModelScope.launch {
            runCatching { app.repository.replaceExpenseSplits(transactionId, splits, deletedIds) }
                .onSuccess {
                    _events.emit("Shared expense saved")
                    onComplete(true)
                }
                .onFailure {
                    _events.emit(it.message ?: "Could not save that split")
                    onComplete(false)
                }
        }
    }

    fun saveSavingsGoal(
        goal: SavingsGoal,
        onComplete: (Boolean) -> Unit = {},
    ) {
        viewModelScope.launch {
            runCatching { app.repository.saveSavingsGoal(goal) }
                .onSuccess {
                    _events.emit("Savings goal saved")
                    onComplete(true)
                }
                .onFailure {
                    _events.emit(it.message ?: "Could not save that goal")
                    onComplete(false)
                }
        }
    }

    fun deleteSavingsGoal(id: Long) {
        viewModelScope.launch {
            app.repository.deleteSavingsGoal(id)
            _events.emit("Savings goal removed")
        }
    }

    fun saveSavingsContribution(
        contribution: SavingsContribution,
        onComplete: (Boolean) -> Unit = {},
    ) {
        viewModelScope.launch {
            runCatching { app.repository.saveSavingsContribution(contribution) }
                .onSuccess {
                    _events.emit("Contribution recorded")
                    onComplete(true)
                }
                .onFailure {
                    _events.emit(it.message ?: "Could not record contribution")
                    onComplete(false)
                }
        }
    }

    fun deleteSavingsContribution(id: Long) {
        viewModelScope.launch {
            app.repository.deleteSavingsContribution(id)
            _events.emit("Contribution removed")
        }
    }

    fun savePaymentCommitment(
        commitment: PaymentCommitment,
        onComplete: (Boolean) -> Unit = {},
    ) {
        viewModelScope.launch {
            runCatching { app.repository.savePaymentCommitment(commitment) }
                .onSuccess {
                    _events.emit("Payment commitment saved")
                    onComplete(true)
                }
                .onFailure {
                    _events.emit(it.message ?: "Could not save that commitment")
                    onComplete(false)
                }
        }
    }

    fun deletePaymentCommitment(id: Long) {
        viewModelScope.launch {
            app.repository.deletePaymentCommitment(id)
            _events.emit("Payment commitment removed")
        }
    }

    fun setAppLock(enabled: Boolean) {
        app.preferences.appLockEnabled = enabled
        _appLockEnabled.value = enabled
        if (enabled) {
            app.preferences.widgetAmountsVisible = false
            _widgetAmountsVisible.value = false
        }
        PaisaLensWidgetProvider.scheduleUpdateAll(app)
    }

    fun setWidgetAmountsVisible(enabled: Boolean) {
        app.preferences.widgetAmountsVisible = enabled
        _widgetAmountsVisible.value = enabled
        PaisaLensWidgetProvider.scheduleUpdateAll(app)
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
            runCatching { app.repository.updateTransactionType(id, type) }
                .onSuccess {
                    _events.emit(if (type == TransactionType.TRANSFER) "Marked as transfer" else "Transaction type updated")
                }
                .onFailure { _events.emit(it.message ?: "Could not change that transaction type") }
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

    fun saveBill(bill: BillReminder) {
        viewModelScope.launch {
            runCatching { app.repository.saveBill(bill) }
                .onSuccess { _events.emit("Bill reminder saved") }
                .onFailure { _events.emit(it.message ?: "Could not save bill reminder") }
        }
    }

    fun markBillPaid(id: Long) {
        viewModelScope.launch {
            app.repository.markBillPaid(id)
            _events.emit("Bill marked paid")
        }
    }

    fun deleteBill(id: Long) {
        viewModelScope.launch {
            app.repository.deleteBill(id)
            _events.emit("Bill reminder removed")
        }
    }

    fun saveNetWorthItem(item: NetWorthItem) {
        viewModelScope.launch {
            runCatching { app.repository.saveNetWorthItem(item) }
                .onSuccess { _events.emit("Net-worth item saved") }
                .onFailure { _events.emit(it.message ?: "Could not save net-worth item") }
        }
    }

    fun deleteNetWorthItem(id: Long) {
        viewModelScope.launch {
            app.repository.deleteNetWorthItem(id)
            _events.emit("Net-worth item removed")
        }
    }

    fun saveSmartCategoryRule(rule: SmartCategoryRule, applyToHistory: Boolean) {
        viewModelScope.launch {
            runCatching { app.repository.saveSmartCategoryRule(rule, applyToHistory) }
                .onSuccess {
                    _events.emit(
                        if (applyToHistory) "Rule saved and matching expenses updated" else "Smart category rule saved",
                    )
                }
                .onFailure { _events.emit(it.message ?: "Could not save category rule") }
        }
    }

    fun deleteSmartCategoryRule(id: Long) {
        viewModelScope.launch {
            app.repository.deleteSmartCategoryRule(id)
            _events.emit("Smart category rule removed")
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

    fun auditCardStatement(
        contentResolver: ContentResolver,
        uri: Uri,
        metadata: StatementAuditMetadata,
    ) {
        if (_isAuditingStatement.value) return
        viewModelScope.launch {
            _isAuditingStatement.value = true
            _statementAuditError.value = null
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val resolvedMetadata = resolveStatementAuditAccount(metadata)
                    val fileName = contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                        ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
                        ?: "card-statement.csv"
                    val support = CreditCardStatementAuditor.sourceSupport(fileName)
                    require(support.directParsingSupported) { support.message }
                    val input = contentResolver.openInputStream(uri)
                        ?: error("Could not open the selected statement")
                    val preview = input.use {
                        app.repository.previewStatement(
                            input = it,
                            fileName = fileName,
                            accountId = resolvedMetadata.accountId,
                            baseCurrency = resolvedMetadata.currency,
                        )
                    }
                    CreditCardStatementAuditor.auditImported(
                        metadata = resolvedMetadata.copy(sourceFileName = fileName),
                        importedRows = preview.rows,
                        existingTransactions = transactions.value,
                    ).let { report ->
                        report.copy(warnings = (report.warnings + preview.warnings).distinct())
                    }
                }
            }
            _isAuditingStatement.value = false
            result.onSuccess { report ->
                _statementAuditReport.value = report
                _events.emit("Statement audited locally · ${report.matchedCount} SMS matches")
            }.onFailure {
                _statementAuditError.value = it.message ?: "Could not audit statement"
                _events.emit(_statementAuditError.value!!)
            }
        }
    }

    fun auditCardStatementRows(metadata: StatementAuditMetadata, rows: List<StatementAuditRow>) {
        if (_isAuditingStatement.value) return
        viewModelScope.launch {
            _isAuditingStatement.value = true
            _statementAuditError.value = null
            val result = withContext(Dispatchers.Default) {
                runCatching {
                    CreditCardStatementAuditor.audit(resolveStatementAuditAccount(metadata), rows, transactions.value)
                }
            }
            result.onSuccess { report ->
                _statementAuditReport.value = report
                _events.emit("Statement audited locally · ${report.matchedCount} SMS matches")
            }.onFailure {
                _statementAuditError.value = it.message ?: "Could not audit statement"
                _events.emit(_statementAuditError.value!!)
            }
            _isAuditingStatement.value = false
        }
    }

    fun clearStatementAudit() {
        _statementAuditReport.value = null
        _statementAuditError.value = null
    }

    private fun resolveStatementAuditAccount(metadata: StatementAuditMetadata): StatementAuditMetadata {
        if (metadata.accountId != null) return metadata
        val lastFour = metadata.cardLast4?.filter(Char::isDigit)?.takeLast(4).orEmpty()
        if (lastFour.isBlank()) return metadata
        val account = accounts.value.firstOrNull {
            it.type == AccountType.CREDIT_CARD &&
                it.accountHint?.filter(Char::isDigit)?.takeLast(4) == lastFour
        } ?: return metadata
        return metadata.copy(
            accountId = account.id,
            accountName = metadata.accountName ?: account.name,
        )
    }

    fun saveReconciliation(reconciliation: MonthlyReconciliation) {
        viewModelScope.launch {
            runCatching { app.repository.saveReconciliation(reconciliation) }
                .onSuccess { _events.emit("Monthly reconciliation saved") }
                .onFailure { _events.emit(it.message ?: "Could not save reconciliation") }
        }
    }

    fun deleteReconciliation(id: Long) {
        viewModelScope.launch {
            runCatching { app.repository.deleteReconciliation(id) }
                .onSuccess { _events.emit("Reconciliation removed · undo is available in Data Health") }
                .onFailure { _events.emit(it.message ?: "Could not remove reconciliation") }
        }
    }

    fun acceptTransactionLink(suggestion: TransactionLinkSuggestion) {
        viewModelScope.launch {
            runCatching {
                app.repository.createTransactionLink(
                    TransactionLink(
                        sourceTransactionId = suggestion.sourceTransactionId,
                        targetTransactionId = suggestion.targetTransactionId,
                        type = suggestion.type,
                        note = suggestion.reason,
                    ),
                )
            }.onSuccess { _events.emit("Transactions linked · undo is available in Data Health") }
                .onFailure { _events.emit(it.message ?: "Could not link transactions") }
        }
    }

    fun deleteTransactionLink(id: Long) {
        viewModelScope.launch {
            runCatching { app.repository.deleteTransactionLink(id) }
                .onSuccess { _events.emit("Transactions unlinked · undo is available in Data Health") }
                .onFailure { _events.emit(it.message ?: "Could not unlink transactions") }
        }
    }

    fun undoAuditBatch(batchId: String) {
        if (_undoInProgressBatchId.value != null) return
        viewModelScope.launch {
            _undoInProgressBatchId.value = batchId
            runCatching { app.repository.undoAuditBatch(batchId) }
                .onSuccess { result ->
                    _lastUndoResult.value = result
                    _events.emit(
                        "Change undone · ${result.insertedEntities + result.updatedEntities + result.deletedEntities} record" +
                            if (result.insertedEntities + result.updatedEntities + result.deletedEntities == 1) " restored" else "s restored",
                    )
                }
                .onFailure { _events.emit(it.message ?: "Could not undo that change") }
            _undoInProgressBatchId.value = null
        }
    }

    fun dismissUndoResult() {
        _lastUndoResult.value = null
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
            runCatching { app.repository.deleteTransaction(id) }
                .onSuccess { _events.emit("Transaction removed") }
                .onFailure { _events.emit(it.message ?: "Could not remove that transaction") }
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
                            transactionLinks = transactionLinks.value,
                            expenseSplits = expenseSplits.value,
                            savingsGoals = savingsGoals.value,
                            savingsContributions = savingsContributions.value,
                            paymentCommitments = paymentCommitments.value,
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
            result.onSuccess {
                val now = System.currentTimeMillis()
                app.preferences.lastBackupCreatedAt = now
                _lastBackupCreatedAt.value = now
            }
            _events.emit(if (result.isSuccess) "Encrypted backup created" else result.exceptionOrNull()?.message ?: "Could not create backup")
        }
    }

    fun verifyBackup(contentResolver: ContentResolver, uri: Uri, passphrase: CharArray) {
        if (_isVerifyingBackup.value) {
            passphrase.fill('\u0000')
            return
        }
        viewModelScope.launch {
            _isVerifyingBackup.value = true
            _backupVerification.value = null
            _backupVerificationError.value = null
            val result = withContext(Dispatchers.IO) {
                try {
                    runCatching {
                        val input = contentResolver.openInputStream(uri)
                            ?: error("Could not open the selected file")
                        input.use { app.repository.verifyBackup(it, passphrase) }
                    }
                } finally {
                    passphrase.fill('\u0000')
                }
            }
            result.onSuccess { metadata ->
                val now = System.currentTimeMillis()
                app.preferences.lastBackupVerifiedAt = now
                _lastBackupVerifiedAt.value = now
                _backupVerification.value = metadata
            }
            result.onFailure { error ->
                _backupVerification.value = null
                _backupVerificationError.value = error.message ?: "Could not verify backup"
            }
            _events.emit(
                result.fold(
                    onSuccess = { metadata -> "Backup verified · ${metadata.totalRecordCount} protected records" },
                    onFailure = { it.message ?: "Could not verify backup" },
                ),
            )
            _isVerifyingBackup.value = false
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
            app.preferences.lastBackupCreatedAt = 0L
            app.preferences.lastBackupVerifiedAt = 0L
            _lastBackupCreatedAt.value = 0L
            _lastBackupVerifiedAt.value = 0L
            _backupVerification.value = null
            _backupVerificationError.value = null
            _isVerifyingBackup.value = false
            _lastUndoResult.value = null
            _undoInProgressBatchId.value = null
            app.preferences.appLockEnabled = false
            app.preferences.widgetAmountsVisible = false
            app.preferences.travelModeEnabled = false
            app.preferences.baseCurrency = "INR"
            app.preferences.setNotificationDigestEnabled(false)
            _appLockEnabled.value = false
            _widgetAmountsVisible.value = false
            _travelModeEnabled.value = false
            _baseCurrency.value = "INR"
            PaisaLensWidgetProvider.scheduleUpdateAll(app)
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
