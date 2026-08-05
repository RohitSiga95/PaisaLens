package com.paisalens.app.data.repository

import android.content.Context
import com.paisalens.app.data.backup.PaisaLensBackupCodec
import com.paisalens.app.data.importer.StatementImporter
import com.paisalens.app.data.local.PaisaLensDatabase
import com.paisalens.app.data.model.AccountProfile
import com.paisalens.app.data.model.AccountType
import com.paisalens.app.data.model.CategoryBudget
import com.paisalens.app.data.model.CategorySelection
import com.paisalens.app.data.model.CustomCategory
import com.paisalens.app.data.model.ExpenseCategory
import com.paisalens.app.data.model.ExchangeRate
import com.paisalens.app.data.model.LoanAccount
import com.paisalens.app.data.model.MerchantAliasRule
import com.paisalens.app.data.model.ParsedTransaction
import com.paisalens.app.data.model.RecurringPayment
import com.paisalens.app.data.model.ReviewStatus
import com.paisalens.app.data.model.SpendingInsight
import com.paisalens.app.data.model.StatementImportPreview
import com.paisalens.app.data.model.StatementImportResult
import com.paisalens.app.data.model.TransactionRecord
import com.paisalens.app.data.model.TransactionSource
import com.paisalens.app.data.model.TransactionType
import com.paisalens.app.data.model.detectRecurringPayments
import com.paisalens.app.data.model.buildOnDeviceInsights
import com.paisalens.app.data.network.FrankfurterRateService
import com.paisalens.app.security.SensitiveDataCipher
import com.paisalens.app.widget.PaisaLensWidgetProvider
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

class TransactionRepository(
    private val context: Context,
    private val database: PaisaLensDatabase,
    private val cipher: SensitiveDataCipher,
    private val rateService: FrankfurterRateService = FrankfurterRateService(),
) {
    private val _transactions = MutableStateFlow<List<TransactionRecord>>(emptyList())
    val transactions: StateFlow<List<TransactionRecord>> = _transactions.asStateFlow()

    private val _budgets = MutableStateFlow<List<CategoryBudget>>(emptyList())
    val budgets: StateFlow<List<CategoryBudget>> = _budgets.asStateFlow()

    private val _accounts = MutableStateFlow<List<AccountProfile>>(emptyList())
    val accounts: StateFlow<List<AccountProfile>> = _accounts.asStateFlow()

    private val _customCategories = MutableStateFlow<List<CustomCategory>>(emptyList())
    val customCategories: StateFlow<List<CustomCategory>> = _customCategories.asStateFlow()

    private val _recurringPayments = MutableStateFlow<List<RecurringPayment>>(emptyList())
    val recurringPayments: StateFlow<List<RecurringPayment>> = _recurringPayments.asStateFlow()

    private val _loans = MutableStateFlow<List<LoanAccount>>(emptyList())
    val loans: StateFlow<List<LoanAccount>> = _loans.asStateFlow()

    private val _exchangeRates = MutableStateFlow<List<ExchangeRate>>(emptyList())
    val exchangeRates: StateFlow<List<ExchangeRate>> = _exchangeRates.asStateFlow()

    private val _merchantAliases = MutableStateFlow<List<MerchantAliasRule>>(emptyList())
    val merchantAliases: StateFlow<List<MerchantAliasRule>> = _merchantAliases.asStateFlow()

    private val _insights = MutableStateFlow<List<SpendingInsight>>(emptyList())
    val insights: StateFlow<List<SpendingInsight>> = _insights.asStateFlow()

    private val _categorizedMerchantKeys = MutableStateFlow<Set<String>>(emptySet())
    val categorizedMerchantKeys: StateFlow<Set<String>> = _categorizedMerchantKeys.asStateFlow()

    suspend fun load() = withContext(Dispatchers.IO) { refresh() }

    suspend fun insertParsed(items: List<ParsedTransaction>): Int = withContext(Dispatchers.IO) {
        val encrypted = items.map { it to cipher.encrypt(it.rawMessage) }
        val count = database.insertAll(encrypted)
        refresh()
        count
    }

    suspend fun addManual(
        amountMinor: Long,
        merchant: String,
        category: CategorySelection,
        type: TransactionType,
        note: String?,
        accountId: Long?,
        tags: List<String>,
        originalAmountMinor: Long? = null,
        originalCurrency: String? = null,
        exchangeRate: Double? = null,
        occurredAt: Long = System.currentTimeMillis(),
    ) = withContext(Dispatchers.IO) {
        val account = database.getAccounts().firstOrNull { it.id == accountId }
        val effectiveCategory = when (type) {
            TransactionType.INCOME -> CategorySelection(ExpenseCategory.INCOME)
            TransactionType.TRANSFER -> CategorySelection(ExpenseCategory.TRANSFER)
            else -> category
        }
        database.insertManual(
            TransactionRecord(
                sourceMessageId = "manual-${UUID.randomUUID()}",
                amountMinor = amountMinor,
                merchant = merchant.trim().ifBlank { "Manual transaction" },
                accountHint = account?.accountHint,
                category = effectiveCategory.builtIn,
                type = type,
                occurredAt = occurredAt,
                source = TransactionSource.MANUAL,
                sender = "Added manually",
                note = note,
                accountId = accountId,
                accountName = account?.name,
                customCategoryId = effectiveCategory.customCategoryId,
                customCategoryName = effectiveCategory.customCategoryName,
                tags = tags,
                originalAmountMinor = originalAmountMinor,
                originalCurrency = originalCurrency,
                exchangeRate = exchangeRate,
            ),
        )
        if (type == TransactionType.EXPENSE) {
            database.updateMerchantCategory(merchant, effectiveCategory)
        }
        refresh()
    }

    suspend fun updateCategory(
        transaction: TransactionRecord,
        category: CategorySelection,
    ): Int = withContext(Dispatchers.IO) {
        val updated = if (transaction.type == TransactionType.EXPENSE) {
            database.updateMerchantCategory(transaction.merchant, category)
        } else {
            database.updateCategory(transaction.id, category)
        }
        refresh()
        updated
    }

    suspend fun updateMerchantCategory(
        merchant: String,
        category: CategorySelection,
    ): Int = withContext(Dispatchers.IO) {
        val updated = database.updateMerchantCategory(merchant, category)
        refresh()
        updated
    }

    suspend fun updateNote(id: Long, note: String) = withContext(Dispatchers.IO) {
        database.updateNote(id, note)
        refresh()
    }

    suspend fun updateTags(id: Long, tags: List<String>) = withContext(Dispatchers.IO) {
        database.updateTags(id, tags)
        refresh()
    }

    suspend fun confirmTransaction(id: Long) = withContext(Dispatchers.IO) {
        database.updateReviewStatus(id, ReviewStatus.CONFIRMED)
        refresh()
    }

    suspend fun updateTransactionAccount(id: Long, accountId: Long?) = withContext(Dispatchers.IO) {
        database.updateAccount(id, accountId)
        refresh()
    }

    suspend fun updateTransactionType(id: Long, type: TransactionType) = withContext(Dispatchers.IO) {
        database.updateTransactionType(id, type)
        refresh()
    }

    suspend fun deleteTransaction(id: Long) = withContext(Dispatchers.IO) {
        database.deleteTransaction(id)
        refresh()
    }

    suspend fun addAccount(name: String, type: AccountType, accountHint: String?) = withContext(Dispatchers.IO) {
        database.addAccount(name, type, accountHint)
        refresh()
    }

    suspend fun updateAccount(account: AccountProfile) = withContext(Dispatchers.IO) {
        database.updateAccountProfile(account)
        refresh()
    }

    suspend fun deleteAccount(id: Long) = withContext(Dispatchers.IO) {
        database.deleteAccount(id)
        refresh()
    }

    suspend fun addCustomCategory(name: String, colorHex: String) = withContext(Dispatchers.IO) {
        database.addCustomCategory(name, colorHex)
        refresh()
    }

    suspend fun updateCustomCategory(category: CustomCategory) = withContext(Dispatchers.IO) {
        database.updateCustomCategory(category)
        refresh()
    }

    suspend fun deleteCustomCategory(id: Long) = withContext(Dispatchers.IO) {
        database.deleteCustomCategory(id)
        refresh()
    }

    suspend fun renameMerchant(aliasName: String, canonicalName: String): Int = withContext(Dispatchers.IO) {
        val count = database.renameMerchant(aliasName, canonicalName)
        refresh()
        count
    }

    suspend fun deleteMerchantAlias(aliasKey: String) = withContext(Dispatchers.IO) {
        database.deleteMerchantAlias(aliasKey)
        refresh()
    }

    suspend fun previewStatement(
        input: InputStream,
        fileName: String,
        accountId: Long?,
        baseCurrency: String,
    ): StatementImportPreview = withContext(Dispatchers.IO) {
        val account = database.getAccounts().firstOrNull { it.id == accountId }
        StatementImporter.preview(
            input = input,
            fileName = fileName,
            accountId = accountId,
            accountName = account?.name,
            baseCurrency = baseCurrency,
            exchangeRates = database.getExchangeRates(),
        )
    }

    suspend fun importStatement(preview: StatementImportPreview): StatementImportResult = withContext(Dispatchers.IO) {
        val result = database.insertImported(preview.rows.map { it.transaction })
        refresh()
        result
    }

    suspend fun saveLoan(loan: LoanAccount) = withContext(Dispatchers.IO) {
        database.upsertLoan(loan)
        refresh()
    }

    suspend fun deleteLoan(id: Long) = withContext(Dispatchers.IO) {
        database.deleteLoan(id)
        refresh()
    }

    suspend fun refreshExchangeRate(fromCurrency: String, baseCurrency: String): ExchangeRate = withContext(Dispatchers.IO) {
        val rate = rateService.latestRate(fromCurrency, baseCurrency)
        database.upsertExchangeRate(rate)
        refresh()
        rate
    }

    suspend fun setBudget(category: ExpenseCategory, limitMinor: Long) = withContext(Dispatchers.IO) {
        database.upsertBudget(category, limitMinor)
        refresh()
    }

    suspend fun writeBackup(output: OutputStream, passphrase: CharArray) = withContext(Dispatchers.IO) {
        try {
            PaisaLensBackupCodec.write(database.snapshot(), passphrase, output)
        } finally {
            passphrase.fill('\u0000')
        }
    }

    suspend fun restoreBackup(input: InputStream, passphrase: CharArray): Int = withContext(Dispatchers.IO) {
        try {
            val snapshot = PaisaLensBackupCodec.read(passphrase, input)
            database.restore(snapshot)
            refresh()
            snapshot.transactions.size
        } finally {
            passphrase.fill('\u0000')
        }
    }

    suspend fun clearAll() = withContext(Dispatchers.IO) {
        database.clearAll()
        refresh()
    }

    private fun refresh() {
        val currentTransactions = database.getTransactions()
        _transactions.value = currentTransactions
        _budgets.value = database.getBudgets()
        _accounts.value = database.getAccounts()
        _customCategories.value = database.getCustomCategories()
        _recurringPayments.value = detectRecurringPayments(currentTransactions)
        _loans.value = database.getLoans()
        _exchangeRates.value = database.getExchangeRates()
        _merchantAliases.value = database.getMerchantAliases()
        _insights.value = buildOnDeviceInsights(currentTransactions, _recurringPayments.value)
        _categorizedMerchantKeys.value = database.getCategorizedMerchantKeys()
        PaisaLensWidgetProvider.updateAll(context)
    }
}
