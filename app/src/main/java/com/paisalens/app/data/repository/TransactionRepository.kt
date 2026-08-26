package com.paisalens.app.data.repository

import android.content.Context
import com.paisalens.app.data.backup.PaisaLensBackupCodec
import com.paisalens.app.data.importer.StatementImporter
import com.paisalens.app.data.local.PaisaLensDatabase
import com.paisalens.app.data.model.AccountAvailabilityUpdate
import com.paisalens.app.data.model.AccountBalanceSnapshot
import com.paisalens.app.data.model.AccountBalanceWriteResult
import com.paisalens.app.data.model.AccountMergeResult
import com.paisalens.app.data.model.AccountProfile
import com.paisalens.app.data.model.AccountType
import com.paisalens.app.data.model.AdvancedBudgetPlan
import com.paisalens.app.data.model.AuditBatchSummary
import com.paisalens.app.data.model.AuditEvent
import com.paisalens.app.data.model.AuditUndoResult
import com.paisalens.app.data.model.BackupVerificationMetadata
import com.paisalens.app.data.model.BillReminder
import com.paisalens.app.data.model.CategoryBudget
import com.paisalens.app.data.model.CategorySelection
import com.paisalens.app.data.model.DataHealthSummary
import com.paisalens.app.data.model.CustomCategory
import com.paisalens.app.data.model.CreditCardBill
import com.paisalens.app.data.model.CreditCardBillPaymentResult
import com.paisalens.app.data.model.ExpenseCategory
import com.paisalens.app.data.model.ExchangeRate
import com.paisalens.app.data.model.ExpenseSplit
import com.paisalens.app.data.model.ExpenseSplitSummary
import com.paisalens.app.data.model.LoanAccount
import com.paisalens.app.data.model.MerchantAliasRule
import com.paisalens.app.data.model.MonthlyReconciliation
import com.paisalens.app.data.model.NetWorthItem
import com.paisalens.app.data.model.ParsedTransaction
import com.paisalens.app.data.model.ParsedCreditCardBill
import com.paisalens.app.data.model.PaymentCommitment
import com.paisalens.app.data.model.PaymentReminderOccurrence
import com.paisalens.app.data.model.PaymentReminderRule
import com.paisalens.app.data.model.PaymentReminderSettings
import com.paisalens.app.data.model.RecurringPayment
import com.paisalens.app.data.model.ReconciliationMetrics
import com.paisalens.app.data.model.ReviewStatus
import com.paisalens.app.data.model.SpendingInsight
import com.paisalens.app.data.model.SmartCategoryRule
import com.paisalens.app.data.model.SavingsContribution
import com.paisalens.app.data.model.SavingsGoal
import com.paisalens.app.data.model.SmsCoverageCandidate
import com.paisalens.app.data.model.SmsCoverageMessage
import com.paisalens.app.data.model.SmsCoverageRule
import com.paisalens.app.data.model.SmsCoverageStatus
import com.paisalens.app.data.model.StatementImportPreview
import com.paisalens.app.data.model.StatementImportResult
import com.paisalens.app.data.model.TransactionLink
import com.paisalens.app.data.model.TransactionLinkSuggestion
import com.paisalens.app.data.model.TransactionLinkValidation
import com.paisalens.app.data.model.TransactionRecord
import com.paisalens.app.data.model.TransactionSource
import com.paisalens.app.data.model.TransactionType
import com.paisalens.app.data.model.detectRecurringPayments
import com.paisalens.app.data.model.buildExpenseSplitSummary
import com.paisalens.app.data.model.buildOnDeviceInsights
import com.paisalens.app.data.model.buildAuditBatchSummaries
import com.paisalens.app.data.model.buildDataHealthSummary
import com.paisalens.app.data.model.buildEffectiveExpenseTransactions
import com.paisalens.app.data.model.calculateReconciliationMetrics
import com.paisalens.app.data.model.calculateEffectiveSpendMinor
import com.paisalens.app.data.model.findReconciliationsInvalidatedByLedger
import com.paisalens.app.data.model.suggestTransactionLinks
import com.paisalens.app.data.model.suggestPaymentCommitments
import com.paisalens.app.data.model.matches
import com.paisalens.app.data.model.buildPaymentReminderTargets
import com.paisalens.app.data.model.resolvePaymentReminderOccurrences
import com.paisalens.app.data.model.creditCardBillGroupKey
import com.paisalens.app.data.model.creditCardReminderTargetKey
import com.paisalens.app.data.model.currentCreditCardBills
import com.paisalens.app.data.model.manualBillReminderTargetKey
import com.paisalens.app.data.model.validateTransactionLink as validateLinkCandidate
import com.paisalens.app.data.network.FrankfurterRateService
import com.paisalens.app.security.SensitiveDataCipher
import com.paisalens.app.notification.PaymentReminderScheduler
import com.paisalens.app.notification.PaymentReminderNotifier
import com.paisalens.app.widget.PaisaLensWidgetProvider
import java.io.InputStream
import java.io.OutputStream
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
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

    private val _smsCoverageMessages = MutableStateFlow<List<SmsCoverageMessage>>(emptyList())
    val smsCoverageMessages: StateFlow<List<SmsCoverageMessage>> = _smsCoverageMessages.asStateFlow()

    private val _smsCoverageRules = MutableStateFlow<List<SmsCoverageRule>>(emptyList())
    val smsCoverageRules: StateFlow<List<SmsCoverageRule>> = _smsCoverageRules.asStateFlow()

    private val _effectiveExpenseTransactions = MutableStateFlow<List<TransactionRecord>>(emptyList())
    val effectiveExpenseTransactions: StateFlow<List<TransactionRecord>> = _effectiveExpenseTransactions.asStateFlow()

    private val _effectiveSpendMinor = MutableStateFlow(0L)
    val effectiveSpendMinor: StateFlow<Long> = _effectiveSpendMinor.asStateFlow()

    private val _budgets = MutableStateFlow<List<CategoryBudget>>(emptyList())
    val budgets: StateFlow<List<CategoryBudget>> = _budgets.asStateFlow()

    private val _advancedBudgets = MutableStateFlow<List<AdvancedBudgetPlan>>(emptyList())
    val advancedBudgets: StateFlow<List<AdvancedBudgetPlan>> = _advancedBudgets.asStateFlow()

    private val _accounts = MutableStateFlow<List<AccountProfile>>(emptyList())
    val accounts: StateFlow<List<AccountProfile>> = _accounts.asStateFlow()

    private val _balanceHistory = MutableStateFlow<List<AccountBalanceSnapshot>>(emptyList())
    val balanceHistory: StateFlow<List<AccountBalanceSnapshot>> = _balanceHistory.asStateFlow()

    private val _bills = MutableStateFlow<List<BillReminder>>(emptyList())
    val bills: StateFlow<List<BillReminder>> = _bills.asStateFlow()

    private val _creditCardBills = MutableStateFlow<List<CreditCardBill>>(emptyList())
    val creditCardBills: StateFlow<List<CreditCardBill>> = _creditCardBills.asStateFlow()

    private val _netWorthItems = MutableStateFlow<List<NetWorthItem>>(emptyList())
    val netWorthItems: StateFlow<List<NetWorthItem>> = _netWorthItems.asStateFlow()

    private val _smartCategoryRules = MutableStateFlow<List<SmartCategoryRule>>(emptyList())
    val smartCategoryRules: StateFlow<List<SmartCategoryRule>> = _smartCategoryRules.asStateFlow()

    private val _reconciliations = MutableStateFlow<List<MonthlyReconciliation>>(emptyList())
    val reconciliations: StateFlow<List<MonthlyReconciliation>> = _reconciliations.asStateFlow()

    private val _transactionLinks = MutableStateFlow<List<TransactionLink>>(emptyList())
    val transactionLinks: StateFlow<List<TransactionLink>> = _transactionLinks.asStateFlow()

    private val _transactionLinkSuggestions = MutableStateFlow<List<TransactionLinkSuggestion>>(emptyList())
    val transactionLinkSuggestions: StateFlow<List<TransactionLinkSuggestion>> =
        _transactionLinkSuggestions.asStateFlow()

    private val _auditEvents = MutableStateFlow<List<AuditEvent>>(emptyList())
    val auditEvents: StateFlow<List<AuditEvent>> = _auditEvents.asStateFlow()

    private val _auditBatches = MutableStateFlow<List<AuditBatchSummary>>(emptyList())
    val auditBatches: StateFlow<List<AuditBatchSummary>> = _auditBatches.asStateFlow()

    private val _dataHealth = MutableStateFlow(DataHealthSummary(score = 100, findings = emptyList()))
    val dataHealth: StateFlow<DataHealthSummary> = _dataHealth.asStateFlow()

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

    private val _expenseSplits = MutableStateFlow<List<ExpenseSplit>>(emptyList())
    val expenseSplits: StateFlow<List<ExpenseSplit>> = _expenseSplits.asStateFlow()

    private val _savingsGoals = MutableStateFlow<List<SavingsGoal>>(emptyList())
    val savingsGoals: StateFlow<List<SavingsGoal>> = _savingsGoals.asStateFlow()

    private val _savingsContributions = MutableStateFlow<List<SavingsContribution>>(emptyList())
    val savingsContributions: StateFlow<List<SavingsContribution>> = _savingsContributions.asStateFlow()

    private val _paymentCommitments = MutableStateFlow<List<PaymentCommitment>>(emptyList())
    val paymentCommitments: StateFlow<List<PaymentCommitment>> = _paymentCommitments.asStateFlow()

    private val _paymentCommitmentSuggestions = MutableStateFlow<List<PaymentCommitment>>(emptyList())
    val paymentCommitmentSuggestions: StateFlow<List<PaymentCommitment>> =
        _paymentCommitmentSuggestions.asStateFlow()

    private val _paymentReminderRules = MutableStateFlow<List<PaymentReminderRule>>(emptyList())
    val paymentReminderRules: StateFlow<List<PaymentReminderRule>> = _paymentReminderRules.asStateFlow()

    private val _paymentReminderSettings = MutableStateFlow(PaymentReminderSettings())
    val paymentReminderSettings: StateFlow<PaymentReminderSettings> = _paymentReminderSettings.asStateFlow()

    private val _paymentReminderOccurrences = MutableStateFlow<List<PaymentReminderOccurrence>>(emptyList())
    val paymentReminderOccurrences: StateFlow<List<PaymentReminderOccurrence>> =
        _paymentReminderOccurrences.asStateFlow()

    suspend fun load() = withContext(Dispatchers.IO) { refresh() }

    suspend fun ingestSms(
        items: List<ParsedTransaction>,
        availabilityUpdates: List<AccountAvailabilityUpdate>,
        coverageCandidates: List<SmsCoverageCandidate> = emptyList(),
        creditCardBills: List<ParsedCreditCardBill> = emptyList(),
    ): SmsIngestResult = withContext(Dispatchers.IO) {
        val reminderSchedulingFenced = creditCardBills.isNotEmpty()
        if (reminderSchedulingFenced) PaymentReminderScheduler.cancel(context)
        try {
            val cardBillsBefore = if (creditCardBills.isEmpty()) {
                emptyMap()
            } else {
                currentCreditCardBills(database.getCreditCardBills())
                    .associateBy(CreditCardBill::creditCardBillGroupKey)
            }
            val encrypted = items.map { it to cipher.encrypt(it.rawMessage) }
            val transactionResult = database.insertAll(encrypted)
            val encryptedCoverage = coverageCandidates.map { it to cipher.encrypt(it.body) }
            val coverageCount = database.insertSmsCoverage(encryptedCoverage)
            val availabilityCount = database.applyAccountAvailability(availabilityUpdates)
            val creditCardBillCount = database.upsertCreditCardBills(creditCardBills)
            if (creditCardBillCount > 0) {
                val cardBillsAfter = currentCreditCardBills(database.getCreditCardBills())
                    .associateBy(CreditCardBill::creditCardBillGroupKey)
                val changedTargets = (cardBillsBefore.keys + cardBillsAfter.keys).asSequence()
                    .filter { cardBillsBefore[it] != cardBillsAfter[it] }
                    .map(::creditCardReminderTargetKey)
                    .toSet()
                database.getPaymentReminderRules()
                    .filter { it.targetKey in changedTargets }
                    .forEach { PaymentReminderNotifier.cancelForRule(context, it.id) }
            }
            refresh()
            SmsIngestResult(
                insertedTransactions = transactionResult.inserted,
                updatedAccounts = availabilityCount,
                mergedDuplicateTransactions = transactionResult.duplicatesMerged,
                coverageMessagesAdded = coverageCount,
                updatedCreditCardBills = creditCardBillCount,
            )
        } catch (error: Throwable) {
            if (reminderSchedulingFenced) PaymentReminderScheduler.sync(context)
            throw error
        }
    }

    suspend fun insertParsed(items: List<ParsedTransaction>): Int =
        ingestSms(items, emptyList()).insertedTransactions

    suspend fun updateSmsCoverageStatus(id: Long, status: SmsCoverageStatus) = withContext(Dispatchers.IO) {
        if (status == SmsCoverageStatus.NEEDS_REVIEW) {
            database.updateSmsCoverageStatus(id, status)
        } else {
            // Handled raw candidates have no ledger value. Remove them instead of retaining
            // decrypted message text in memory or encrypted copies indefinitely on disk.
            database.deleteSmsCoverageMessage(id)
        }
        refresh()
    }

    suspend fun ignoreSmsCoverageMessage(id: Long) =
        updateSmsCoverageStatus(id, SmsCoverageStatus.DISMISSED)

    suspend fun resolveSmsCoverageMessage(id: Long) =
        updateSmsCoverageStatus(id, SmsCoverageStatus.RESOLVED)

    suspend fun reopenSmsCoverageMessage(id: Long) =
        updateSmsCoverageStatus(id, SmsCoverageStatus.NEEDS_REVIEW)

    suspend fun deleteSmsCoverageMessage(id: Long) = withContext(Dispatchers.IO) {
        database.deleteSmsCoverageMessage(id)
        refresh()
    }

    suspend fun smsCoverageRulesForParsing(): List<SmsCoverageRule> = withContext(Dispatchers.IO) {
        database.getSmsCoverageRules().filter(SmsCoverageRule::enabled)
    }

    suspend fun saveSmsCoverageRule(rule: SmsCoverageRule): Long = withContext(Dispatchers.IO) {
        val id = database.upsertSmsCoverageRule(rule)
        if (rule.enabled) {
            val matchingIds = _smsCoverageMessages.value
                .asSequence()
                .filter { it.status == SmsCoverageStatus.NEEDS_REVIEW && rule.matches(it.sender, it.body) }
                .map(SmsCoverageMessage::id)
                .toList()
            database.deleteSmsCoverageMessages(matchingIds)
        }
        refresh()
        id
    }

    suspend fun deleteSmsCoverageRule(id: Long) = withContext(Dispatchers.IO) {
        database.deleteSmsCoverageRule(id)
        refresh()
    }

    suspend fun updateAccountAvailability(update: AccountAvailabilityUpdate): Int = withContext(Dispatchers.IO) {
        val count = database.applyAccountAvailability(listOf(update))
        refresh()
        count
    }

    suspend fun saveAdvancedBudget(plan: AdvancedBudgetPlan): Long = withContext(Dispatchers.IO) {
        val id = database.upsertAdvancedBudgetPlan(plan)
        refresh()
        id
    }

    suspend fun deleteAdvancedBudget(id: Long) = withContext(Dispatchers.IO) {
        database.deleteAdvancedBudgetPlan(id)
        refresh()
    }

    suspend fun markCreditCardBillPaid(
        id: Long,
        confirmed: Boolean,
        paidAt: Long = System.currentTimeMillis(),
    ): CreditCardBillPaymentResult = withContext(Dispatchers.IO) {
        val reminderRuleIds = database.getCreditCardBills().firstOrNull { it.id == id }?.let { bill ->
            val targetKey = creditCardReminderTargetKey(bill.creditCardBillGroupKey)
            database.getPaymentReminderRules().filter { it.targetKey == targetKey }.map { it.id }
        }.orEmpty()
        val fenced = confirmed && reminderRuleIds.isNotEmpty()
        if (fenced) PaymentReminderScheduler.cancel(context)
        try {
            val result = database.markCreditCardBillPaid(id, confirmed, paidAt)
            if (result == CreditCardBillPaymentResult.MARKED_PAID) {
                reminderRuleIds.forEach { PaymentReminderNotifier.cancelForRule(context, it) }
                refresh()
            } else if (fenced) {
                PaymentReminderScheduler.sync(context)
            }
            result
        } catch (error: Throwable) {
            if (fenced) PaymentReminderScheduler.sync(context)
            throw error
        }
    }

    suspend fun assignCreditCardBillToAccount(id: Long, accountId: Long): Boolean = withContext(Dispatchers.IO) {
        PaymentReminderScheduler.cancel(context)
        try {
            val assigned = database.assignCreditCardBillToAccount(id, accountId)
            if (assigned) refresh() else PaymentReminderScheduler.sync(context)
            assigned
        } catch (error: Throwable) {
            PaymentReminderScheduler.sync(context)
            throw error
        }
    }

    suspend fun recordUserEnteredUpiBalance(
        accountId: Long,
        balanceMinor: Long,
        recordedAt: Long = System.currentTimeMillis(),
        sourceLabel: String? = null,
    ): AccountBalanceWriteResult = withContext(Dispatchers.IO) {
        val result = database.recordUserEnteredUpiBalance(accountId, balanceMinor, recordedAt, sourceLabel)
        refresh()
        result
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

    suspend fun saveExpenseSplit(split: ExpenseSplit): Long = withContext(Dispatchers.IO) {
        val id = database.upsertExpenseSplit(split)
        refresh()
        id
    }

    suspend fun deleteExpenseSplit(id: Long) = withContext(Dispatchers.IO) {
        database.deleteExpenseSplit(id)
        refresh()
    }

    suspend fun replaceExpenseSplits(
        transactionId: Long,
        splits: List<ExpenseSplit>,
        deletedIds: Set<Long> = emptySet(),
    ): List<Long> = withContext(Dispatchers.IO) {
        val ids = database.replaceExpenseSplits(transactionId, splits, deletedIds)
        refresh()
        ids
    }

    fun expenseSplitSummary(transactionId: Long): ExpenseSplitSummary? =
        _transactions.value.firstOrNull { it.id == transactionId }?.let { transaction ->
            buildExpenseSplitSummary(transaction, _expenseSplits.value)
        }

    suspend fun saveSavingsGoal(goal: SavingsGoal): Long = withContext(Dispatchers.IO) {
        val id = database.upsertSavingsGoal(goal)
        refresh()
        id
    }

    suspend fun deleteSavingsGoal(id: Long) = withContext(Dispatchers.IO) {
        database.deleteSavingsGoal(id)
        refresh()
    }

    suspend fun saveSavingsContribution(contribution: SavingsContribution): Long = withContext(Dispatchers.IO) {
        val id = database.upsertSavingsContribution(contribution)
        refresh()
        id
    }

    suspend fun deleteSavingsContribution(id: Long) = withContext(Dispatchers.IO) {
        database.deleteSavingsContribution(id)
        refresh()
    }

    suspend fun savePaymentCommitment(commitment: PaymentCommitment): Long = withContext(Dispatchers.IO) {
        val id = database.upsertPaymentCommitment(commitment)
        refresh()
        id
    }

    suspend fun deletePaymentCommitment(id: Long) = withContext(Dispatchers.IO) {
        database.deletePaymentCommitment(id)
        refresh()
    }

    suspend fun addAccount(name: String, type: AccountType, accountHint: String?) = withContext(Dispatchers.IO) {
        database.addAccount(name, type, accountHint)
        refresh()
    }

    suspend fun updateAccount(account: AccountProfile) = withContext(Dispatchers.IO) {
        val affectedTargets = database.getCreditCardBills()
            .filter { it.accountId == account.id }
            .map { creditCardReminderTargetKey(it.creditCardBillGroupKey) }
            .toSet()
        val affectedRuleIds = database.getPaymentReminderRules()
            .filter { it.targetKey in affectedTargets }
            .map(PaymentReminderRule::id)
        PaymentReminderScheduler.cancel(context)
        try {
            database.updateAccountProfile(account)
            affectedRuleIds.forEach { PaymentReminderNotifier.cancelForRule(context, it) }
            refresh()
        } catch (error: Throwable) {
            PaymentReminderScheduler.sync(context)
            throw error
        }
    }

    suspend fun mergeAccounts(
        accountIds: Collection<Long>,
        mergedName: String,
    ): AccountMergeResult = withContext(Dispatchers.IO) {
        PaymentReminderScheduler.cancel(context)
        try {
            val result = database.mergeAccounts(accountIds, mergedName)
            if (result is AccountMergeResult.Success) refresh() else PaymentReminderScheduler.sync(context)
            result
        } catch (error: Throwable) {
            PaymentReminderScheduler.sync(context)
            throw error
        }
    }

    suspend fun deleteAccount(id: Long) = withContext(Dispatchers.IO) {
        PaymentReminderScheduler.cancel(context)
        try {
            database.deleteAccount(id)
            refresh()
        } catch (error: Throwable) {
            PaymentReminderScheduler.sync(context)
            throw error
        }
    }

    suspend fun saveBill(bill: BillReminder) = withContext(Dispatchers.IO) {
        val reminderRuleIds = if (bill.id > 0L) {
            database.getPaymentReminderRules()
                .filter { it.targetKey == manualBillReminderTargetKey(bill.id) }
                .map(PaymentReminderRule::id)
        } else {
            emptyList()
        }
        PaymentReminderScheduler.cancel(context)
        try {
            database.upsertBill(bill)
            reminderRuleIds.forEach { PaymentReminderNotifier.cancelForRule(context, it) }
            refresh()
        } catch (error: Throwable) {
            PaymentReminderScheduler.sync(context)
            throw error
        }
    }

    suspend fun markBillPaid(id: Long, paidOn: LocalDate = LocalDate.now()) = withContext(Dispatchers.IO) {
        val bill = database.getBills().firstOrNull { it.id == id } ?: return@withContext
        val reminderRuleIds = database.getPaymentReminderRules()
            .filter { it.targetKey == manualBillReminderTargetKey(id) }
            .map { it.id }
        val next = if (bill.recurrenceMonths > 0) {
            val anchor = LocalDate.ofEpochDay(bill.dueDateEpochDay)
            var occurrence = 0L
            var currentDue = anchor
            bill.lastPaidEpochDay?.let(LocalDate::ofEpochDay)?.let { lastPaid ->
                while (!currentDue.isAfter(lastPaid)) {
                    occurrence += 1
                    currentDue = anchoredBillOccurrence(anchor, occurrence * bill.recurrenceMonths)
                }
            }
            bill.copy(
                lastPaidEpochDay = maxOf(paidOn, currentDue).toEpochDay(),
                isActive = true,
            )
        } else {
            bill.copy(lastPaidEpochDay = paidOn.toEpochDay(), isActive = false)
        }
        PaymentReminderScheduler.cancel(context)
        try {
            database.upsertBill(next)
            reminderRuleIds.forEach { PaymentReminderNotifier.cancelForRule(context, it) }
            refresh()
        } catch (error: Throwable) {
            PaymentReminderScheduler.sync(context)
            throw error
        }
    }

    suspend fun deleteBill(id: Long) = withContext(Dispatchers.IO) {
        val reminderRuleIds = if (id > 0L) {
            database.getPaymentReminderRules()
                .filter { it.targetKey == manualBillReminderTargetKey(id) }
                .map { it.id }
        } else {
            emptyList()
        }
        PaymentReminderScheduler.cancel(context)
        try {
            database.deleteBill(id)
            reminderRuleIds.forEach { PaymentReminderNotifier.cancelForRule(context, it) }
            refresh()
        } catch (error: Throwable) {
            PaymentReminderScheduler.sync(context)
            throw error
        }
    }

    suspend fun savePaymentReminderRule(rule: PaymentReminderRule): Long = withContext(Dispatchers.IO) {
        PaymentReminderScheduler.cancel(context)
        try {
            val id = database.upsertPaymentReminderRule(rule)
            PaymentReminderNotifier.cancelForRule(context, id)
            refresh()
            id
        } catch (error: Throwable) {
            PaymentReminderScheduler.sync(context)
            throw error
        }
    }

    suspend fun deletePaymentReminderRule(id: Long): Boolean = withContext(Dispatchers.IO) {
        PaymentReminderScheduler.cancel(context)
        try {
            val deleted = database.deletePaymentReminderRule(id)
            if (deleted) {
                PaymentReminderNotifier.cancelForRule(context, id)
                refresh()
            } else {
                PaymentReminderScheduler.sync(context)
            }
            deleted
        } catch (error: Throwable) {
            PaymentReminderScheduler.sync(context)
            throw error
        }
    }

    suspend fun setPaymentReminderSettings(settings: PaymentReminderSettings) = withContext(Dispatchers.IO) {
        PaymentReminderScheduler.cancel(context)
        try {
            database.setPaymentReminderSettings(settings)
            if (!settings.enabled) PaymentReminderNotifier.cancelAll(context)
            refresh()
        } catch (error: Throwable) {
            PaymentReminderScheduler.sync(context)
            throw error
        }
    }

    suspend fun snoozePaymentReminder(
        ruleId: Long,
        occurrenceEpochDay: Long,
        minutes: Int,
        expectedGeneration: Long? = null,
    ): Boolean = withContext(Dispatchers.IO) {
        PaymentReminderScheduler.cancel(context)
        try {
            val generation = expectedGeneration ?: database.getPaymentReminderRule(ruleId)?.generation
            if (generation == null) {
                PaymentReminderScheduler.sync(context)
                return@withContext false
            }
            val changed = database.snoozePaymentReminder(
                ruleId,
                occurrenceEpochDay,
                minutes,
                expectedGeneration = generation,
            )
            if (changed) {
                PaymentReminderNotifier.cancelForRule(context, ruleId)
                refresh()
            } else {
                PaymentReminderScheduler.sync(context)
            }
            changed
        } catch (error: Throwable) {
            PaymentReminderScheduler.sync(context)
            throw error
        }
    }

    suspend fun skipNextPaymentReminder(
        ruleId: Long,
        occurrenceEpochDay: Long,
        expectedGeneration: Long? = null,
    ): Boolean =
        withContext(Dispatchers.IO) {
            PaymentReminderScheduler.cancel(context)
            try {
                val generation = expectedGeneration ?: database.getPaymentReminderRule(ruleId)?.generation
                if (generation == null) {
                    PaymentReminderScheduler.sync(context)
                    return@withContext false
                }
                val changed = database.skipNextPaymentReminder(ruleId, occurrenceEpochDay, generation)
                if (changed) {
                    PaymentReminderNotifier.cancelForRule(context, ruleId)
                    refresh()
                } else {
                    PaymentReminderScheduler.sync(context)
                }
                changed
            } catch (error: Throwable) {
                PaymentReminderScheduler.sync(context)
                throw error
            }
        }

    suspend fun clearPaymentReminderSnooze(
        ruleId: Long,
        expectedGeneration: Long? = null,
    ): Boolean = withContext(Dispatchers.IO) {
        PaymentReminderScheduler.cancel(context)
        try {
            val generation = expectedGeneration ?: database.getPaymentReminderRule(ruleId)?.generation
            if (generation == null) {
                PaymentReminderScheduler.sync(context)
                return@withContext false
            }
            val changed = database.clearPaymentReminderSnooze(ruleId, generation)
            if (changed) refresh() else PaymentReminderScheduler.sync(context)
            changed
        } catch (error: Throwable) {
            PaymentReminderScheduler.sync(context)
            throw error
        }
    }

    suspend fun saveNetWorthItem(item: NetWorthItem) = withContext(Dispatchers.IO) {
        database.upsertNetWorthItem(item)
        refresh()
    }

    suspend fun deleteNetWorthItem(id: Long) = withContext(Dispatchers.IO) {
        database.deleteNetWorthItem(id)
        refresh()
    }

    suspend fun saveSmartCategoryRule(rule: SmartCategoryRule, applyToHistory: Boolean) =
        withContext(Dispatchers.IO) {
            database.upsertSmartCategoryRule(rule, applyToHistory)
            refresh()
        }

    suspend fun deleteSmartCategoryRule(id: Long) = withContext(Dispatchers.IO) {
        database.deleteSmartCategoryRule(id)
        refresh()
    }

    suspend fun saveReconciliation(reconciliation: MonthlyReconciliation): Long = withContext(Dispatchers.IO) {
        val id = database.upsertMonthlyReconciliation(reconciliation)
        refresh()
        id
    }

    suspend fun deleteReconciliation(id: Long) = withContext(Dispatchers.IO) {
        database.deleteMonthlyReconciliation(id)
        refresh()
    }

    suspend fun createTransactionLink(link: TransactionLink): Long = withContext(Dispatchers.IO) {
        val id = database.createTransactionLink(link)
        refresh()
        id
    }

    fun validateTransactionLink(link: TransactionLink): TransactionLinkValidation = validateLinkCandidate(
        candidate = link,
        existingLinks = _transactionLinks.value,
        transactionIds = _transactions.value.mapTo(mutableSetOf(), TransactionRecord::id),
        transactionsById = _transactions.value.associateBy(TransactionRecord::id),
    )

    fun reconciliationMetrics(
        reconciliation: MonthlyReconciliation,
        zoneId: ZoneId = ZoneId.systemDefault(),
    ): ReconciliationMetrics = calculateReconciliationMetrics(
        reconciliation = reconciliation,
        transactions = _transactions.value,
        zoneId = zoneId,
        accountType = _accounts.value.firstOrNull { it.id == reconciliation.accountId }?.type
            ?: AccountType.BANK_ACCOUNT,
        transactionLinks = _transactionLinks.value,
    )

    suspend fun deleteTransactionLink(id: Long) = withContext(Dispatchers.IO) {
        database.deleteTransactionLink(id)
        refresh()
    }

    suspend fun undoAuditBatch(batchId: String): AuditUndoResult = withContext(Dispatchers.IO) {
        val result = database.undoAuditBatch(batchId)
        refresh()
        result
    }

    suspend fun addCustomCategory(name: String, colorHex: String): CustomCategory = withContext(Dispatchers.IO) {
        val cleanName = name.trim().take(32)
        val id = database.addCustomCategory(cleanName, colorHex)
        refresh()
        database.getCustomCategories().firstOrNull { it.id == id }
            ?: CustomCategory(id = id, name = cleanName, colorHex = colorHex)
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
            PaisaLensBackupCodec.write(
                database.snapshot(),
                passphrase,
                output,
            )
        } finally {
            passphrase.fill('\u0000')
        }
    }

    suspend fun restoreBackup(input: InputStream, passphrase: CharArray): Int = withContext(Dispatchers.IO) {
        try {
            val snapshot = PaisaLensBackupCodec.read(passphrase, input)
            // Fence any receiver that claimed an alarm for the old dataset before mutating the DB.
            PaymentReminderScheduler.cancel(context)
            PaymentReminderNotifier.cancelAll(context)
            try {
                database.restore(snapshot)
            } catch (error: Throwable) {
                // Database.restore is transactional. Replan the intact old dataset on rollback.
                PaymentReminderScheduler.sync(context)
                throw error
            }
            refresh()
            snapshot.transactions.size
        } finally {
            passphrase.fill('\u0000')
        }
    }

    suspend fun verifyBackup(
        input: InputStream,
        passphrase: CharArray,
    ): BackupVerificationMetadata = withContext(Dispatchers.IO) {
        try {
            PaisaLensBackupCodec.verify(passphrase, input)
        } finally {
            passphrase.fill('\u0000')
        }
    }

    suspend fun clearAll() = withContext(Dispatchers.IO) {
        PaymentReminderScheduler.cancel(context)
        PaymentReminderNotifier.cancelAll(context)
        try {
            database.clearAll()
            // Also remove a card posted by a worker that finished immediately before the fence.
            PaymentReminderNotifier.cancelAll(context)
            refresh()
        } catch (error: Throwable) {
            PaymentReminderScheduler.sync(context)
            throw error
        }
    }

    private fun refresh() {
        val currentTransactions = database.getTransactions()
        val currentAccounts = database.getAccounts()
        val currentLinks = database.getTransactionLinks()
        _smsCoverageRules.value = database.getSmsCoverageRules()
        database.deleteHandledSmsCoverageMessages()
        _smsCoverageMessages.value = database.getEncryptedSmsCoverageMessages().mapNotNull { stored ->
            runCatching {
                SmsCoverageMessage(
                    id = stored.id,
                    sourceMessageId = stored.sourceMessageId,
                    sender = stored.sender,
                    body = cipher.decrypt(stored.bodyCipher),
                    receivedAt = stored.receivedAt,
                    reason = stored.reason,
                    status = stored.status,
                    createdAt = stored.createdAt,
                    updatedAt = stored.updatedAt,
                )
            }.getOrNull()
        }
        var currentReconciliations = database.getMonthlyReconciliations()
        val invalidatedReconciliationIds = findReconciliationsInvalidatedByLedger(
            reconciliations = currentReconciliations,
            transactions = currentTransactions,
            accountTypesById = currentAccounts.associate { it.id to it.type },
            transactionLinks = currentLinks,
        ).mapTo(linkedSetOf(), MonthlyReconciliation::id)
        if (database.markReconciliationsReviewRequired(invalidatedReconciliationIds) > 0) {
            currentReconciliations = database.getMonthlyReconciliations()
        }
        _transactions.value = currentTransactions
        _budgets.value = database.getBudgets()
        _advancedBudgets.value = database.getAdvancedBudgetPlans()
        _accounts.value = currentAccounts
        _balanceHistory.value = database.getBalanceHistory()
        _bills.value = database.getBills()
        _creditCardBills.value = database.getCreditCardBills()
        _netWorthItems.value = database.getNetWorthItems()
        _smartCategoryRules.value = database.getSmartCategoryRules()
        _reconciliations.value = currentReconciliations
        _transactionLinks.value = currentLinks
        _expenseSplits.value = database.getExpenseSplits()
        _effectiveExpenseTransactions.value = buildEffectiveExpenseTransactions(
            currentTransactions,
            _transactionLinks.value,
            _expenseSplits.value,
        )
        _effectiveSpendMinor.value = calculateEffectiveSpendMinor(
            currentTransactions,
            _transactionLinks.value,
            _expenseSplits.value,
        )
        _transactionLinkSuggestions.value = suggestTransactionLinks(
            currentTransactions,
            _transactionLinks.value,
        )
        _auditEvents.value = database.getAuditEvents()
        _auditBatches.value = buildAuditBatchSummaries(_auditEvents.value)
        _dataHealth.value = buildDataHealthSummary(
            transactions = currentTransactions,
            accounts = _accounts.value,
            reconciliations = _reconciliations.value,
            transactionLinks = _transactionLinks.value,
            expenseSplits = _expenseSplits.value,
        )
        _customCategories.value = database.getCustomCategories()
        _recurringPayments.value = detectRecurringPayments(_effectiveExpenseTransactions.value)
        _loans.value = database.getLoans()
        _exchangeRates.value = database.getExchangeRates()
        _merchantAliases.value = database.getMerchantAliases()
        _insights.value = buildOnDeviceInsights(
            currentTransactions,
            _recurringPayments.value,
            transactionLinks = _transactionLinks.value,
            expenseSplits = _expenseSplits.value,
        )
        _categorizedMerchantKeys.value = database.getCategorizedMerchantKeys()
        _savingsGoals.value = database.getSavingsGoals()
        _savingsContributions.value = database.getSavingsContributions()
        _paymentCommitments.value = database.getPaymentCommitments()
        _paymentCommitmentSuggestions.value = suggestPaymentCommitments(
            recurringPayments = _recurringPayments.value,
            existingCommitments = _paymentCommitments.value,
            accounts = _accounts.value,
        )
        _paymentReminderRules.value = database.getPaymentReminderRules()
        _paymentReminderSettings.value = database.getPaymentReminderSettings()
        _paymentReminderOccurrences.value = resolvePaymentReminderOccurrences(
            rules = _paymentReminderRules.value,
            targets = buildPaymentReminderTargets(
                manualBills = _bills.value,
                creditCardBills = _creditCardBills.value,
                today = LocalDate.now(),
                accountNamesById = _accounts.value.associate { it.id to it.name },
            ),
            manualBills = _bills.value,
            nowMillis = System.currentTimeMillis(),
        )
        PaymentReminderScheduler.sync(context)
        PaisaLensWidgetProvider.updateAll(context)
    }
}

private fun anchoredBillOccurrence(anchor: LocalDate, monthsAfterAnchor: Long): LocalDate {
    val targetMonth = YearMonth.from(anchor).plusMonths(monthsAfterAnchor)
    return if (anchor.dayOfMonth == YearMonth.from(anchor).lengthOfMonth()) {
        targetMonth.atEndOfMonth()
    } else {
        targetMonth.atDay(anchor.dayOfMonth.coerceAtMost(targetMonth.lengthOfMonth()))
    }
}

data class SmsIngestResult(
    val insertedTransactions: Int,
    val updatedAccounts: Int,
    val mergedDuplicateTransactions: Int = 0,
    val coverageMessagesAdded: Int = 0,
    val updatedCreditCardBills: Int = 0,
)
