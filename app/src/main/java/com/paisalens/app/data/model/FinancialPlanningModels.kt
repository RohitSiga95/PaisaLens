package com.paisalens.app.data.model

import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import kotlin.math.roundToLong

data class AccountBalanceSnapshot(
    val id: Long = 0,
    val accountId: Long,
    val balanceMinor: Long?,
    val availableCreditMinor: Long?,
    val creditLimitMinor: Long?,
    val recordedAt: Long,
    val sender: String? = null,
)

/** Machine-readable source stored for balances the user copies from a UPI app. */
const val USER_ENTERED_UPI_BALANCE_SOURCE = "USER_ENTERED_UPI"

private const val USER_ENTERED_UPI_BALANCE_SOURCE_SEPARATOR = '|'
private const val USER_ENTERED_UPI_LABEL_PREFIX = "User entered after "
private const val USER_ENTERED_UPI_LABEL_SUFFIX = " check"

/** ₹1 trillion, stored in minor units. This also keeps later arithmetic far from Long overflow. */
const val MAX_USER_ENTERED_BALANCE_MINOR = 100_000_000_000_000L

private const val MAX_USER_ENTERED_BALANCE_FUTURE_SKEW_MILLIS = 5L * 60L * 1000L

data class AccountBalanceWriteResult(
    val snapshotRecorded: Boolean,
    val currentBalanceUpdated: Boolean,
)

fun encodeUserEnteredUpiBalanceSource(sourceLabel: String?): String {
    val cleanLabel = sourceLabel?.trim()?.replace(Regex("\\s+"), " ").orEmpty()
    val appName = cleanLabel
        .takeIf {
            it.startsWith(USER_ENTERED_UPI_LABEL_PREFIX) &&
                it.endsWith(USER_ENTERED_UPI_LABEL_SUFFIX)
        }
        ?.removePrefix(USER_ENTERED_UPI_LABEL_PREFIX)
        ?.removeSuffix(USER_ENTERED_UPI_LABEL_SUFFIX)
        .sanitizeUpiAppName()
    return appName?.let { "$USER_ENTERED_UPI_BALANCE_SOURCE$USER_ENTERED_UPI_BALANCE_SOURCE_SEPARATOR$it" }
        ?: USER_ENTERED_UPI_BALANCE_SOURCE
}

fun balanceSourceDisplayName(source: String?): String? = when {
    source == USER_ENTERED_UPI_BALANCE_SOURCE -> "User entered after UPI check"
    source?.startsWith("$USER_ENTERED_UPI_BALANCE_SOURCE$USER_ENTERED_UPI_BALANCE_SOURCE_SEPARATOR") == true -> {
        val appName = source.substringAfter(USER_ENTERED_UPI_BALANCE_SOURCE_SEPARATOR).sanitizeUpiAppName()
        appName?.let { "User entered after $it check" } ?: "User entered after UPI check"
    }
    else -> source
}

private fun String?.sanitizeUpiAppName(): String? = this
    ?.replace(Regex("[^\\p{L}\\p{N} .&'()+_-]"), "")
    ?.trim()
    ?.replace(Regex("\\s+"), " ")
    ?.take(30)
    ?.trim()
    ?.takeIf(String::isNotBlank)

fun validateUserEnteredUpiBalance(
    accountId: Long,
    accountType: AccountType,
    balanceMinor: Long,
    recordedAt: Long,
    now: Long = System.currentTimeMillis(),
) {
    require(accountId > 0) { "Select a valid bank account" }
    require(accountType == AccountType.BANK_ACCOUNT) {
        "UPI balance entry is available only for bank accounts"
    }
    require(balanceMinor in -MAX_USER_ENTERED_BALANCE_MINOR..MAX_USER_ENTERED_BALANCE_MINOR) {
        "Enter a bank balance between -₹1 trillion and ₹1 trillion"
    }
    require(recordedAt > 0 && recordedAt <= now + MAX_USER_ENTERED_BALANCE_FUTURE_SKEW_MILLIS) {
        "Balance timestamp is invalid"
    }
}

data class DailyBalancePoint(
    val accountId: Long,
    val date: LocalDate,
    val balanceMinor: Long?,
    val availableCreditMinor: Long?,
    val creditLimitMinor: Long?,
    val recordedAt: Long,
)

fun buildDailyBalanceHistory(
    snapshots: List<AccountBalanceSnapshot>,
    zoneId: ZoneId,
    accountId: Long? = null,
): List<DailyBalancePoint> = snapshots
    .asSequence()
    .filter { accountId == null || it.accountId == accountId }
    .groupBy { snapshot ->
        snapshot.accountId to Instant.ofEpochMilli(snapshot.recordedAt).atZone(zoneId).toLocalDate()
    }
    .map { (key, matches) ->
        val latest = matches.maxWith(compareBy<AccountBalanceSnapshot> { it.recordedAt }.thenBy { it.id })
        fun latestValue(selector: (AccountBalanceSnapshot) -> Long?): Long? = matches
            .filter { selector(it) != null }
            .maxWithOrNull(compareBy<AccountBalanceSnapshot> { it.recordedAt }.thenBy { it.id })
            ?.let(selector)
        DailyBalancePoint(
            accountId = key.first,
            date = key.second,
            balanceMinor = latestValue(AccountBalanceSnapshot::balanceMinor),
            availableCreditMinor = latestValue(AccountBalanceSnapshot::availableCreditMinor),
            creditLimitMinor = latestValue(AccountBalanceSnapshot::creditLimitMinor),
            recordedAt = latest.recordedAt,
        )
    }
    .sortedWith(compareBy<DailyBalancePoint> { it.date }.thenBy { it.accountId })

enum class CreditUtilizationBand {
    UNKNOWN,
    HEALTHY,
    MODERATE,
    HIGH,
    CRITICAL,
}

data class CreditUtilization(
    val accountId: Long,
    val creditLimitMinor: Long?,
    val availableCreditMinor: Long?,
    val usedMinor: Long?,
    /** 10,000 basis points represents 100%. */
    val utilizationBasisPoints: Int?,
    val band: CreditUtilizationBand,
)

fun calculateCreditUtilization(
    accountId: Long,
    availableCreditMinor: Long?,
    creditLimitMinor: Long?,
): CreditUtilization {
    if (creditLimitMinor == null || creditLimitMinor <= 0 || availableCreditMinor == null) {
        return CreditUtilization(
            accountId = accountId,
            creditLimitMinor = creditLimitMinor,
            availableCreditMinor = availableCreditMinor,
            usedMinor = null,
            utilizationBasisPoints = null,
            band = CreditUtilizationBand.UNKNOWN,
        )
    }
    val available = availableCreditMinor.coerceIn(0, creditLimitMinor)
    val used = creditLimitMinor - available
    val basisPoints = ((used.toDouble() / creditLimitMinor) * 10_000).roundToLong().toInt()
    val band = when (basisPoints) {
        in 0..<3_000 -> CreditUtilizationBand.HEALTHY
        in 3_000..<5_000 -> CreditUtilizationBand.MODERATE
        in 5_000..<7_500 -> CreditUtilizationBand.HIGH
        else -> CreditUtilizationBand.CRITICAL
    }
    return CreditUtilization(accountId, creditLimitMinor, available, used, basisPoints, band)
}

fun buildCreditUtilizations(
    accounts: List<AccountProfile>,
    snapshots: List<AccountBalanceSnapshot>,
): List<CreditUtilization> = accounts
    .filter { it.type == AccountType.CREDIT_CARD }
    .map { account ->
        val latest = snapshots.filter { it.accountId == account.id }.maxByOrNull { it.recordedAt }
        calculateCreditUtilization(
            accountId = account.id,
            availableCreditMinor = latest?.availableCreditMinor ?: account.availableCreditMinor,
            creditLimitMinor = account.creditLimitMinor ?: latest?.creditLimitMinor,
        )
    }
    .sortedWith(
        compareByDescending<CreditUtilization> { it.utilizationBasisPoints ?: -1 }
            .thenBy { it.accountId },
    )

data class BillReminder(
    val id: Long = 0,
    val title: String,
    val amountMinor: Long,
    val dueDateEpochDay: Long,
    /** Zero means one-time; positive values repeat by this many calendar months. */
    val recurrenceMonths: Int = 0,
    val accountId: Long? = null,
    val notes: String? = null,
    val isActive: Boolean = true,
    val lastPaidEpochDay: Long? = null,
)

enum class DueItemSource {
    MANUAL_BILL,
    RECURRING_PAYMENT,
    PAYMENT_COMMITMENT,
    LOAN_EMI,
}

enum class DueStatus {
    OVERDUE,
    DUE_TODAY,
    DUE_SOON,
    UPCOMING,
    LATER,
}

data class DueItem(
    val stableId: String,
    val source: DueItemSource,
    val title: String,
    val amountMinor: Long,
    val dueDate: LocalDate,
    val accountId: Long? = null,
    val accountName: String? = null,
    val notes: String? = null,
    val status: DueStatus,
)

fun classifyDueDate(dueDate: LocalDate, today: LocalDate): DueStatus {
    val days = ChronoUnit.DAYS.between(today, dueDate)
    return when {
        days < 0 -> DueStatus.OVERDUE
        days == 0L -> DueStatus.DUE_TODAY
        days <= 7 -> DueStatus.DUE_SOON
        days <= 30 -> DueStatus.UPCOMING
        else -> DueStatus.LATER
    }
}

private fun anchoredMonthOccurrence(anchor: LocalDate, monthsAfterAnchor: Long): LocalDate {
    val targetMonth = YearMonth.from(anchor).plusMonths(monthsAfterAnchor)
    return if (anchor.dayOfMonth == YearMonth.from(anchor).lengthOfMonth()) {
        targetMonth.atEndOfMonth()
    } else {
        targetMonth.atDay(anchor.dayOfMonth.coerceAtMost(targetMonth.lengthOfMonth()))
    }
}

fun buildDueItems(
    manualBills: List<BillReminder>,
    recurringPayments: List<RecurringPayment>,
    loans: List<LoanAccount>,
    today: LocalDate,
    zoneId: ZoneId,
    horizonDays: Int = 60,
    includeRepeatingOccurrences: Boolean = false,
): List<DueItem> {
    require(horizonDays >= 0) { "horizonDays must be non-negative" }
    val endDateExclusive = today.plusDays(horizonDays.toLong())
    val dueItems = mutableListOf<DueItem>()

    manualBills.filter { it.isActive && it.amountMinor > 0 }.forEach { bill ->
        val anchorDate = LocalDate.ofEpochDay(bill.dueDateEpochDay)
        var occurrence = 0L
        var dueDate = anchorDate
        val lastPaid = bill.lastPaidEpochDay?.let(LocalDate::ofEpochDay)
        if (lastPaid != null && !dueDate.isAfter(lastPaid)) {
            if (bill.recurrenceMonths <= 0) return@forEach
            while (!dueDate.isAfter(lastPaid)) {
                occurrence += 1
                dueDate = anchoredMonthOccurrence(
                    anchorDate,
                    occurrence * bill.recurrenceMonths.toLong(),
                )
            }
        }
        while (dueDate.isBefore(endDateExclusive)) {
            dueItems += DueItem(
                stableId = "bill:${bill.id}:${dueDate.toEpochDay()}",
                source = DueItemSource.MANUAL_BILL,
                title = bill.title,
                amountMinor = bill.amountMinor,
                dueDate = dueDate,
                accountId = bill.accountId,
                notes = bill.notes,
                status = classifyDueDate(dueDate, today),
            )
            if (!includeRepeatingOccurrences || bill.recurrenceMonths <= 0) break
            occurrence += 1
            dueDate = anchoredMonthOccurrence(
                anchorDate,
                occurrence * bill.recurrenceMonths.toLong(),
            )
        }
    }

    recurringPayments.filter { it.typicalAmountMinor > 0 }.forEach { payment ->
        var dueDate = Instant.ofEpochMilli(payment.nextDueAt).atZone(zoneId).toLocalDate()
        while (dueDate.isBefore(endDateExclusive)) {
            dueItems += DueItem(
                stableId = "recurring:${normalizedMerchantKey(payment.merchant)}:${dueDate.toEpochDay()}",
                source = DueItemSource.RECURRING_PAYMENT,
                title = payment.merchant,
                amountMinor = payment.typicalAmountMinor,
                dueDate = dueDate,
                accountName = payment.accountName,
                status = classifyDueDate(dueDate, today),
            )
            if (!includeRepeatingOccurrences) break
            dueDate = dueDate.plusDays(payment.intervalDays.toLong().coerceAtLeast(1))
        }
    }

    loans.filter { it.remainingInstallments > 0 && it.emiMinor > 0 }.forEach { loan ->
        val anchorDate = LocalDate.ofEpochDay(loan.startDateEpochDay)
        var installmentIndex = loan.paidInstallments.toLong()
        var dueDate = anchoredMonthOccurrence(anchorDate, installmentIndex)
        var remaining = loan.remainingInstallments
        while (dueDate.isBefore(endDateExclusive) && remaining > 0) {
            dueItems += DueItem(
                stableId = "loan:${loan.id}:${dueDate.toEpochDay()}",
                source = DueItemSource.LOAN_EMI,
                title = loan.name,
                amountMinor = loan.emiMinor,
                dueDate = dueDate,
                accountId = loan.accountId,
                accountName = loan.lender,
                notes = loan.notes,
                status = classifyDueDate(dueDate, today),
            )
            if (!includeRepeatingOccurrences) break
            remaining -= 1
            installmentIndex += 1
            dueDate = anchoredMonthOccurrence(anchorDate, installmentIndex)
        }
    }

    return dueItems.sortedWith(compareBy<DueItem> { it.dueDate }.thenBy { it.title.lowercase() })
}

/** Builds due occurrences for user-reviewed subscriptions and UPI AutoPay planning records. */
fun buildPaymentCommitmentDueItems(
    commitments: List<PaymentCommitment>,
    today: LocalDate,
    horizonDays: Int = 60,
    includeRepeatingOccurrences: Boolean = false,
    accountNamesById: Map<Long, String> = emptyMap(),
): List<DueItem> {
    require(horizonDays >= 0) { "horizonDays must be non-negative" }
    val endDateExclusive = today.plusDays(horizonDays.toLong())
    return buildList {
        commitments
            .filter { it.status == PaymentCommitmentStatus.ACTIVE && it.amountMinor > 0 }
            .forEach { commitment ->
                var dueDate = currentPaymentDueDate(commitment, today)
                while (dueDate.isBefore(endDateExclusive)) {
                    add(
                        DueItem(
                            stableId = "commitment:${commitment.id}:${dueDate.toEpochDay()}",
                            source = DueItemSource.PAYMENT_COMMITMENT,
                            title = commitment.name,
                            amountMinor = commitment.amountMinor,
                            dueDate = dueDate,
                            accountId = commitment.accountId,
                            accountName = commitment.accountId?.let(accountNamesById::get),
                            notes = commitment.notes,
                            status = classifyDueDate(dueDate, today),
                        ),
                    )
                    if (!includeRepeatingOccurrences) break
                    dueDate = calculateNextPaymentDue(commitment, dueDate)
                }
            }
    }.sortedWith(compareBy<DueItem> { it.dueDate }.thenBy { it.title.lowercase() })
}

data class CashFlowBaseline(
    val lookbackDays: Int,
    val averageDailyIncomeMinor: Long,
    val averageDailyFlexibleExpenseMinor: Long,
)

data class CashFlowPoint(
    val date: LocalDate,
    val expectedIncomeMinor: Long,
    val expectedFlexibleExpenseMinor: Long,
    val scheduledExpenseMinor: Long,
    val projectedBalanceMinor: Long,
)

data class CashFlowForecast(
    val openingBalanceMinor: Long,
    val baseline: CashFlowBaseline,
    val points: List<CashFlowPoint>,
    val lowestBalanceMinor: Long,
    val endingBalanceMinor: Long,
)

fun buildCashFlowForecast(
    openingBalanceMinor: Long,
    transactions: List<TransactionRecord>,
    dueItems: List<DueItem>,
    asOf: LocalDate,
    zoneId: ZoneId,
    horizonDays: Int = 30,
    lookbackDays: Int = 90,
    transactionLinks: List<TransactionLink> = emptyList(),
): CashFlowForecast {
    require(horizonDays > 0) { "horizonDays must be positive" }
    require(lookbackDays > 0) { "lookbackDays must be positive" }
    val start = asOf.minusDays(lookbackDays.toLong())
    val scheduledMerchantKeys = dueItems.map { normalizedMerchantKey(it.title) }.filter(String::isNotBlank).toSet()
    val history = cashFlowRelevantTransactions(transactions, transactionLinks).filter { transaction ->
        if (transaction.reviewStatus != ReviewStatus.CONFIRMED) return@filter false
        val date = Instant.ofEpochMilli(transaction.occurredAt).atZone(zoneId).toLocalDate()
        !date.isBefore(start) && date.isBefore(asOf)
    }
    val incomeTotal = history.filter { it.type == TransactionType.INCOME || it.type == TransactionType.REFUND }
        .sumOf { it.amountMinor }
    val flexibleExpenseTotal = history.filter {
        it.type == TransactionType.EXPENSE && normalizedMerchantKey(it.merchant) !in scheduledMerchantKeys
    }.sumOf { it.amountMinor }
    val baseline = CashFlowBaseline(
        lookbackDays = lookbackDays,
        averageDailyIncomeMinor = incomeTotal / lookbackDays,
        averageDailyFlexibleExpenseMinor = flexibleExpenseTotal / lookbackDays,
    )
    var balance = openingBalanceMinor
    val points = (0 until horizonDays).map { offset ->
        val date = asOf.plusDays(offset.toLong())
        val scheduled = dueItems.filter { due ->
            due.dueDate == date || (offset == 0 && due.dueDate.isBefore(asOf))
        }.sumOf { it.amountMinor }
        balance += baseline.averageDailyIncomeMinor
        balance -= baseline.averageDailyFlexibleExpenseMinor + scheduled
        CashFlowPoint(
            date = date,
            expectedIncomeMinor = baseline.averageDailyIncomeMinor,
            expectedFlexibleExpenseMinor = baseline.averageDailyFlexibleExpenseMinor,
            scheduledExpenseMinor = scheduled,
            projectedBalanceMinor = balance,
        )
    }
    return CashFlowForecast(
        openingBalanceMinor = openingBalanceMinor,
        baseline = baseline,
        points = points,
        lowestBalanceMinor = points.minOf { it.projectedBalanceMinor },
        endingBalanceMinor = points.last().projectedBalanceMinor,
    )
}

enum class NetWorthKind {
    ASSET,
    LIABILITY,
}

data class NetWorthItem(
    val id: Long = 0,
    val name: String,
    val kind: NetWorthKind,
    val valueMinor: Long,
    val category: String,
    val updatedAt: Long,
)

data class NetWorthSummary(
    val assetsMinor: Long,
    val liabilitiesMinor: Long,
    val netWorthMinor: Long,
    val items: List<NetWorthItem>,
)

fun remainingLoanPrincipalMinor(loan: LoanAccount): Long {
    if (loan.principalMinor <= 0 || loan.remainingInstallments <= 0) return 0
    var balance = loan.principalMinor
    repeat(loan.paidInstallments.coerceIn(0, loan.tenureMonths)) {
        val interest = (balance * (loan.annualRateBasisPoints / 10_000.0 / 12.0)).roundToLong()
        balance = (balance + interest - loan.emiMinor).coerceAtLeast(0)
    }
    return balance
}

fun buildNetWorthSummary(
    accounts: List<AccountProfile>,
    loans: List<LoanAccount>,
    manualItems: List<NetWorthItem>,
    creditLimitsByAccountId: Map<Long, Long> = emptyMap(),
): NetWorthSummary {
    val consolidatedAccounts = accounts
        .groupBy { account ->
            val hint = account.accountHint?.filter(Char::isDigit)?.takeLast(4).orEmpty()
            if (hint.isNotBlank()) "${account.type}:$hint" else "${account.type}:id:${account.id}"
        }
        .values
        .map { matches ->
            val latest = matches.maxBy { it.availabilityFetchedAt ?: Long.MIN_VALUE }
            fun latestWithValue(selector: (AccountProfile) -> Long?): Long? = matches
                .filter { selector(it) != null }
                .maxByOrNull { it.availabilityFetchedAt ?: Long.MIN_VALUE }
                ?.let(selector)
            latest.copy(
                balanceMinor = latestWithValue(AccountProfile::balanceMinor),
                availableCreditMinor = latestWithValue(AccountProfile::availableCreditMinor),
                creditLimitMinor = latestWithValue(AccountProfile::creditLimitMinor)
                    ?: matches.mapNotNull { creditLimitsByAccountId[it.id] }.firstOrNull(),
            )
        }

    val accountItems = consolidatedAccounts.mapNotNull { account ->
        when (account.type) {
            AccountType.CREDIT_CARD -> {
                val limit = account.creditLimitMinor ?: creditLimitsByAccountId[account.id] ?: return@mapNotNull null
                val available = account.availableCreditMinor ?: return@mapNotNull null
                val used = (limit - available.coerceIn(0, limit)).coerceAtLeast(0)
                NetWorthItem(
                    id = account.id,
                    name = account.name,
                    kind = NetWorthKind.LIABILITY,
                    valueMinor = used,
                    category = "Credit card",
                    updatedAt = account.availabilityFetchedAt ?: 0,
                )
            }
            else -> account.balanceMinor?.let { balance ->
                NetWorthItem(
                    id = account.id,
                    name = account.name,
                    kind = if (balance >= 0) NetWorthKind.ASSET else NetWorthKind.LIABILITY,
                    valueMinor = kotlin.math.abs(balance),
                    category = account.type.label,
                    updatedAt = account.availabilityFetchedAt ?: 0,
                )
            }
        }
    }
    val loanItems = loans.mapNotNull { loan ->
        remainingLoanPrincipalMinor(loan).takeIf { it > 0 }?.let { remaining ->
            NetWorthItem(
                id = -loan.id - 1,
                name = loan.name,
                kind = NetWorthKind.LIABILITY,
                valueMinor = remaining,
                category = "Loan",
                updatedAt = 0,
            )
        }
    }
    val items = (accountItems + loanItems + manualItems)
        .filter { it.valueMinor >= 0 }
        .sortedWith(compareBy<NetWorthItem> { it.kind }.thenByDescending { it.valueMinor })
    val assets = items.filter { it.kind == NetWorthKind.ASSET }.sumOf { it.valueMinor }
    val liabilities = items.filter { it.kind == NetWorthKind.LIABILITY }.sumOf { it.valueMinor }
    return NetWorthSummary(assets, liabilities, assets - liabilities, items)
}

enum class SmartRuleMatchType {
    EXACT,
    CONTAINS,
    STARTS_WITH,
    REGEX,
}

data class SmartCategoryRule(
    val id: Long = 0,
    val name: String,
    val merchantPattern: String,
    val matchType: SmartRuleMatchType,
    val minAmountMinor: Long? = null,
    val maxAmountMinor: Long? = null,
    val accountId: Long? = null,
    val category: ExpenseCategory,
    val customCategoryId: Long? = null,
    val enabled: Boolean = true,
    val priority: Int = 0,
    val updatedAt: Long,
) {
    val selection: CategorySelection
        get() = CategorySelection(builtIn = category, customCategoryId = customCategoryId)

    fun matches(transaction: TransactionRecord): Boolean {
        if (!enabled || transaction.type != TransactionType.EXPENSE) return false
        if (accountId != null && transaction.accountId != accountId) return false
        if (minAmountMinor != null && transaction.amountMinor < minAmountMinor) return false
        if (maxAmountMinor != null && transaction.amountMinor > maxAmountMinor) return false
        val rawMerchant = transaction.merchant.trim()
        val rawPattern = merchantPattern.trim()
        val merchant = rawMerchant.replace(Regex("\\s+"), " ")
        val pattern = rawPattern.replace(Regex("\\s+"), " ")
        if (pattern.isBlank()) return false
        return when (matchType) {
            SmartRuleMatchType.EXACT -> merchant.equals(pattern, ignoreCase = true)
            SmartRuleMatchType.CONTAINS -> merchant.contains(pattern, ignoreCase = true)
            SmartRuleMatchType.STARTS_WITH -> merchant.startsWith(pattern, ignoreCase = true)
            SmartRuleMatchType.REGEX -> runCatching {
                Regex(rawPattern, RegexOption.IGNORE_CASE).containsMatchIn(rawMerchant)
            }
                .getOrDefault(false)
        }
    }
}

data class SmartRulePreview(
    val rule: SmartCategoryRule,
    val matchedTransactionIds: List<Long>,
    val matchedCount: Int,
    val totalAmountMinor: Long,
)

fun previewSmartCategoryRule(
    rule: SmartCategoryRule,
    transactions: List<TransactionRecord>,
): SmartRulePreview {
    val matches = transactions.filter(rule::matches)
    return SmartRulePreview(
        rule = rule,
        matchedTransactionIds = matches.map { it.id },
        matchedCount = matches.size,
        totalAmountMinor = matches.sumOf { it.amountMinor },
    )
}

fun findMatchingSmartCategoryRule(
    transaction: TransactionRecord,
    rules: List<SmartCategoryRule>,
): SmartCategoryRule? = rules
    .asSequence()
    .filter { it.matches(transaction) }
    .sortedWith(compareByDescending<SmartCategoryRule> { it.priority }.thenByDescending { it.updatedAt }.thenBy { it.id })
    .firstOrNull()

data class WhatIfScenario(
    val name: String,
    val extraMonthlyIncomeMinor: Long = 0,
    /** 10,000 basis points represents a 100% reduction. */
    val flexibleExpenseReductionBasisPoints: Int = 0,
    val oneTimeExpenseMinor: Long = 0,
    val oneTimeExpenseMonth: Int = 1,
)

data class WhatIfMonthPoint(
    val monthNumber: Int,
    val baselineBalanceMinor: Long,
    val scenarioBalanceMinor: Long,
    val improvementMinor: Long,
)

data class WhatIfSimulation(
    val scenario: WhatIfScenario,
    val points: List<WhatIfMonthPoint>,
    val baselineEndingMinor: Long,
    val scenarioEndingMinor: Long,
    val improvementMinor: Long,
)

private fun scaledByBasisPoints(value: Long, basisPoints: Int): Long {
    val whole = value / 10_000
    val remainder = value % 10_000
    return whole * basisPoints + remainder * basisPoints / 10_000
}

fun simulateWhatIfMonthly(
    openingBalanceMinor: Long,
    monthlyIncomeMinor: Long,
    monthlyFixedExpenseMinor: Long,
    monthlyFlexibleExpenseMinor: Long,
    scenario: WhatIfScenario,
    months: Int = 12,
): WhatIfSimulation {
    require(months > 0) { "months must be positive" }
    require(scenario.flexibleExpenseReductionBasisPoints in 0..10_000) {
        "flexibleExpenseReductionBasisPoints must be between 0 and 10,000"
    }
    require(scenario.oneTimeExpenseMonth in 1..months) { "oneTimeExpenseMonth must be within the simulation" }
    val flexibleSaving = scaledByBasisPoints(
        monthlyFlexibleExpenseMinor,
        scenario.flexibleExpenseReductionBasisPoints,
    )
    var baselineBalance = openingBalanceMinor
    var scenarioBalance = openingBalanceMinor
    val points = (1..months).map { month ->
        baselineBalance += monthlyIncomeMinor - monthlyFixedExpenseMinor - monthlyFlexibleExpenseMinor
        scenarioBalance += monthlyIncomeMinor + scenario.extraMonthlyIncomeMinor - monthlyFixedExpenseMinor -
            (monthlyFlexibleExpenseMinor - flexibleSaving)
        if (month == scenario.oneTimeExpenseMonth) scenarioBalance -= scenario.oneTimeExpenseMinor
        WhatIfMonthPoint(
            monthNumber = month,
            baselineBalanceMinor = baselineBalance,
            scenarioBalanceMinor = scenarioBalance,
            improvementMinor = scenarioBalance - baselineBalance,
        )
    }
    return WhatIfSimulation(
        scenario = scenario,
        points = points,
        baselineEndingMinor = baselineBalance,
        scenarioEndingMinor = scenarioBalance,
        improvementMinor = scenarioBalance - baselineBalance,
    )
}
