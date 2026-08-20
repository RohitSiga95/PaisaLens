package com.paisalens.app.data.model

import java.util.Locale

enum class TransactionType {
    EXPENSE,
    INCOME,
    REFUND,
    TRANSFER,
}

enum class TransactionSource {
    BANK,
    CARD,
    UPI,
    WALLET,
    MANUAL,
    STATEMENT,
}

enum class ReviewStatus {
    CONFIRMED,
    NEEDS_REVIEW,
}

enum class AccountType(val label: String) {
    BANK_ACCOUNT("Bank account"),
    CREDIT_CARD("Credit card"),
    WALLET("Wallet"),
    CASH("Cash"),
    OTHER("Other"),
}

enum class ExpenseCategory(val label: String) {
    FOOD("Food & dining"),
    GROCERIES("Groceries"),
    SHOPPING("Shopping"),
    TRANSPORT("Transport"),
    BILLS("Bills & utilities"),
    ENTERTAINMENT("Entertainment"),
    HEALTH("Health"),
    EDUCATION("Education"),
    TRAVEL("Travel"),
    CASH("Cash withdrawal"),
    TRANSFER("Transfers"),
    INCOME("Income"),
    OTHER("Other"),
}

data class TransactionRecord(
    val id: Long = 0,
    val sourceMessageId: String,
    val amountMinor: Long,
    val merchant: String,
    val accountHint: String?,
    val category: ExpenseCategory,
    val type: TransactionType,
    val occurredAt: Long,
    val source: TransactionSource,
    val sender: String,
    val note: String? = null,
    val accountId: Long? = null,
    val accountName: String? = null,
    val customCategoryId: Long? = null,
    val customCategoryName: String? = null,
    val tags: List<String> = emptyList(),
    val reviewStatus: ReviewStatus = ReviewStatus.CONFIRMED,
    val reviewReason: String? = null,
    val originalAmountMinor: Long? = null,
    val originalCurrency: String? = null,
    val exchangeRate: Double? = null,
    /** Canonical bank/card tag derived locally from the linked account or SMS sender. */
    val institutionName: String? = null,
    /** Number of distinct SMS alerts conservatively merged into this ledger entry. */
    val duplicateCount: Int = 1,
    /** Raw storage identity retained when [accountId] is projected to a merged root. */
    val physicalAccountId: Long? = null,
)

fun TransactionRecord.accountIdentityId(): Long? = physicalAccountId ?: accountId

data class ParsedTransaction(
    val sourceMessageId: String,
    val amountMinor: Long,
    val merchant: String,
    val accountHint: String?,
    val category: ExpenseCategory,
    val type: TransactionType,
    val occurredAt: Long,
    val source: TransactionSource,
    val sender: String,
    val rawMessage: String,
    val reviewStatus: ReviewStatus = ReviewStatus.CONFIRMED,
    val reviewReason: String? = null,
)

data class AccountProfile(
    val id: Long = 0,
    val name: String,
    val type: AccountType,
    val accountHint: String? = null,
    val institution: String? = null,
    val balanceMinor: Long? = null,
    val availableCreditMinor: Long? = null,
    val creditLimitMinor: Long? = null,
    val availabilityFetchedAt: Long? = null,
    val availabilitySender: String? = null,
    val identityKey: String? = null,
    /**
     * Internal physical-account identity retained after an explicit logical merge.
     * Normal account lists expose only canonical profiles; backups retain this link so
     * institution-specific SMS aliases survive a restore.
     */
    val mergedIntoAccountId: Long? = null,
    /** Number of retained physical identities represented by this user-facing profile. */
    val mergedMemberCount: Int = 1,
)

data class AccountAvailabilityUpdate(
    val bankKey: String,
    val institutionName: String,
    val accountType: AccountType,
    val accountHint: String?,
    val balanceMinor: Long? = null,
    val availableCreditMinor: Long? = null,
    val creditLimitMinor: Long? = null,
    val fetchedAt: Long,
    val sender: String,
)

data class ReceiptOcrDraft(
    val amountMinor: Long?,
    val merchant: String,
    val category: ExpenseCategory,
    val note: String,
    val sourceLabel: String,
)

data class CustomCategory(
    val id: Long = 0,
    val name: String,
    val colorHex: String = "#7784FF",
)

data class CategorySelection(
    val builtIn: ExpenseCategory = ExpenseCategory.OTHER,
    val customCategoryId: Long? = null,
    val customCategoryName: String? = null,
) {
    val label: String
        get() = customCategoryName ?: builtIn.label
}

data class MerchantCategoryRule(
    val merchantKey: String,
    val merchantName: String,
    val category: ExpenseCategory,
    val customCategoryId: Long? = null,
)

data class RecurringPayment(
    val merchant: String,
    val accountName: String?,
    val typicalAmountMinor: Long,
    val intervalDays: Int,
    val lastPaidAt: Long,
    val nextDueAt: Long,
    val occurrences: Int,
    val categoryLabel: String,
    /** User-visible logical account; merged groups expose their canonical root here. */
    val accountId: Long? = null,
    /** Physical member whose transaction series produced this recurrence. */
    val physicalAccountId: Long? = null,
)

fun RecurringPayment.accountIdentityId(): Long? = physicalAccountId ?: accountId

data class PaisaLensBackupSnapshot(
    val createdAt: Long,
    val transactions: List<TransactionRecord>,
    val budgets: List<CategoryBudget>,
    val accounts: List<AccountProfile>,
    val customCategories: List<CustomCategory>,
    val merchantRules: List<MerchantCategoryRule>,
    val merchantAliases: List<MerchantAliasRule> = emptyList(),
    val loans: List<LoanAccount> = emptyList(),
    val balanceHistory: List<AccountBalanceSnapshot> = emptyList(),
    val bills: List<BillReminder> = emptyList(),
    val netWorthItems: List<NetWorthItem> = emptyList(),
    val smartCategoryRules: List<SmartCategoryRule> = emptyList(),
    val reconciliations: List<MonthlyReconciliation> = emptyList(),
    val transactionLinks: List<TransactionLink> = emptyList(),
    val auditEvents: List<AuditEvent> = emptyList(),
    val expenseSplits: List<ExpenseSplit> = emptyList(),
    val savingsGoals: List<SavingsGoal> = emptyList(),
    val savingsContributions: List<SavingsContribution> = emptyList(),
    val paymentCommitments: List<PaymentCommitment> = emptyList(),
    val transactionSmsSources: List<TransactionSmsSource> = emptyList(),
    /** Runtime-only: portable backups intentionally exclude unresolved SMS text. */
    val smsCoverageMessages: List<SmsCoverageMessage> = emptyList(),
    val smsCoverageRules: List<SmsCoverageRule> = emptyList(),
    val advancedBudgets: List<AdvancedBudgetPlan> = emptyList(),
    val creditCardBills: List<CreditCardBill> = emptyList(),
)

data class CategoryBudget(
    val category: ExpenseCategory,
    val limitMinor: Long,
)

data class MerchantTransactionGroup(
    val merchant: String,
    val merchantKey: String,
    val transactionCount: Int,
    val totalMinor: Long,
)

fun normalizedMerchantKey(merchant: String): String = merchant
    .trim()
    .lowercase(Locale.ROOT)
    .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
    .trim()

fun TransactionRecord.categoryLabel(): String = customCategoryName ?: category.label

fun sanitizeTags(value: String): List<String> = value
    .split(',')
    .map { it.trim().replace(Regex("\\s+"), " ").take(24) }
    .filter(String::isNotBlank)
    .distinctBy { it.lowercase(Locale.ROOT) }
    .take(6)

fun detectRecurringPayments(
    transactions: List<TransactionRecord>,
    now: Long = System.currentTimeMillis(),
): List<RecurringPayment> {
    val dayMillis = 24L * 60L * 60L * 1000L
    return transactions
        .asSequence()
        .filter { it.type == TransactionType.EXPENSE && it.reviewStatus == ReviewStatus.CONFIRMED }
        .groupBy { normalizedMerchantKey(it.merchant) to it.accountIdentityId() }
        .values
        .mapNotNull { matches ->
            if (matches.size < 2) return@mapNotNull null
            val ordered = matches.sortedBy { it.occurredAt }
            val gaps = ordered.zipWithNext { first, second ->
                ((second.occurredAt - first.occurredAt) / dayMillis).toInt()
            }.filter { it > 0 }
            if (gaps.isEmpty()) return@mapNotNull null
            val medianGap = gaps.sorted()[gaps.size / 2]
            val interval = when (medianGap) {
                in 5..9 -> 7
                in 25..40 -> 30
                else -> return@mapNotNull null
            }
            val amounts = ordered.map { it.amountMinor }.sorted()
            val typicalAmount = amounts[amounts.size / 2]
            val permittedVariation = maxOf(10_000L, typicalAmount / 4)
            if ((amounts.last() - amounts.first()) > permittedVariation) return@mapNotNull null
            val last = ordered.last()
            val nextDue = last.occurredAt + interval * dayMillis
            if (nextDue < now - 7 * dayMillis) return@mapNotNull null
            RecurringPayment(
                merchant = last.merchant,
                accountName = last.accountName,
                typicalAmountMinor = typicalAmount,
                intervalDays = interval,
                lastPaidAt = last.occurredAt,
                nextDueAt = nextDue,
                occurrences = ordered.size,
                categoryLabel = last.categoryLabel(),
                accountId = last.accountId,
                physicalAccountId = last.accountIdentityId(),
            )
        }
        .sortedBy { it.nextDueAt }
}

fun findUncategorizedMerchantGroups(
    transactions: List<TransactionRecord>,
    categorizedMerchantKeys: Set<String>,
): List<MerchantTransactionGroup> = transactions
    .asSequence()
    .filter { it.type == TransactionType.EXPENSE && it.category == ExpenseCategory.OTHER }
    .groupBy { normalizedMerchantKey(it.merchant) }
    .filterKeys { it.isNotBlank() && it !in categorizedMerchantKeys }
    .map { (merchantKey, matchingTransactions) ->
        MerchantTransactionGroup(
            merchant = matchingTransactions.first().merchant,
            merchantKey = merchantKey,
            transactionCount = matchingTransactions.size,
            totalMinor = matchingTransactions.sumOf { it.amountMinor },
        )
    }
    .sortedWith(
        compareByDescending<MerchantTransactionGroup> { it.transactionCount }
            .thenByDescending { it.totalMinor },
    )
