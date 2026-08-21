package com.paisalens.app.data.model

import com.paisalens.app.sms.BankSmsSupport
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit
import kotlin.math.roundToInt

enum class HomeBalanceFreshness {
    FRESH,
    AGING,
    STALE,
    PARTIAL,
    UNAVAILABLE,
}

enum class HomePulseConfidence {
    COMPLETE,
    PARTIAL,
    UNAVAILABLE,
}

data class HomeFinancialPulse(
    val safeToSpendMinor: Long?,
    val availableCashMinor: Long?,
    val upcomingObligationsMinor: Long,
    val goalReserveMinor: Long,
    val safetyBufferMinor: Long,
    val monthlySpendMinor: Long,
    val throughDate: LocalDate,
    val balanceFreshness: HomeBalanceFreshness,
    val confidence: HomePulseConfidence,
    val availableBalanceCount: Int,
    val totalBalanceCount: Int,
    val oldestBalanceAt: Long?,
    val latestBalanceAt: Long?,
    val assumptions: List<String>,
)

enum class HomeTimelineSource {
    EXPECTED_INCOME,
    BILL,
    RECURRING_PAYMENT,
    PAYMENT_COMMITMENT,
    LOAN_EMI,
    CREDIT_CARD_BILL,
}

data class HomeTimelineItem(
    val stableId: String,
    val title: String,
    val amountMinor: Long,
    val date: LocalDate,
    val source: HomeTimelineSource,
    val accountName: String? = null,
    val isIncoming: Boolean = false,
    val isEstimate: Boolean = false,
)

data class HomeMoneyTimeline(
    val startDate: LocalDate,
    val endDateInclusive: LocalDate,
    val items: List<HomeTimelineItem>,
    val incomingMinor: Long,
    val outgoingMinor: Long,
)

data class HomeBudgetPace(
    val planCount: Int,
    val availableMinor: Long,
    val spentMinor: Long,
    val remainingMinor: Long,
    val plannedToDateMinor: Long,
    val actualVsPlannedMinor: Long,
    val spentBasisPoints: Int,
    val periodElapsedBasisPoints: Int,
    val health: BudgetHealth,
    val usesAdvancedPlans: Boolean,
)

data class HomeCardHealthItem(
    val stableId: String,
    val accountId: Long?,
    val name: String,
    val accountHint: String?,
    val totalDueMinor: Long?,
    val minimumDueMinor: Long?,
    val dueDate: LocalDate?,
    val availableCreditMinor: Long?,
    val creditLimitMinor: Long?,
    val utilizationBasisPoints: Int?,
    val utilizationBand: CreditUtilizationBand,
)

data class HomeCardHealth(
    val cards: List<HomeCardHealthItem>,
    val totalDueMinor: Long,
    val totalAvailableCreditMinor: Long?,
    val highestUtilizationBasisPoints: Int?,
    val highUtilizationCount: Int,
    val nextDueDate: LocalDate?,
)

data class HomeDashboardSnapshot(
    val pulse: HomeFinancialPulse,
    val timeline: HomeMoneyTimeline,
    val budgetPace: HomeBudgetPace?,
    val cardHealth: HomeCardHealth,
)

/**
 * Builds the Home snapshot only from data already held on-device. Forecasts are deliberately
 * labelled estimates and never include predicted income in the safe-to-spend amount.
 */
fun buildHomeDashboardSnapshot(
    transactions: List<TransactionRecord>,
    effectiveExpenseTransactions: List<TransactionRecord>,
    transactionLinks: List<TransactionLink> = emptyList(),
    accounts: List<AccountProfile>,
    legacyBudgets: List<CategoryBudget>,
    advancedBudgets: List<AdvancedBudgetPlan>,
    manualBills: List<BillReminder>,
    recurringPayments: List<RecurringPayment>,
    loans: List<LoanAccount>,
    creditCardBills: List<CreditCardBill>,
    paymentCommitments: List<PaymentCommitment>,
    savingsGoals: List<SavingsGoal>,
    savingsContributions: List<SavingsContribution>,
    now: ZonedDateTime,
    horizonDays: Int = 14,
    safetyBufferBasisPoints: Int = 500,
): HomeDashboardSnapshot {
    require(horizonDays in 1..90) { "Home timeline must cover between 1 and 90 days" }
    require(safetyBufferBasisPoints in 0..10_000) { "Safety buffer must be between 0% and 100%" }

    val today = now.toLocalDate()
    val zoneId = now.zone
    val appliedExpenseOffsets = transactionIdsAppliedAsExpenseOffsets(transactions, transactionLinks)
    val budgetTransactions = effectiveExpenseTransactions + transactions.filter {
        it.type == TransactionType.REFUND &&
            it.reviewStatus == ReviewStatus.CONFIRMED &&
            it.id !in appliedExpenseOffsets
    }
    val timeline = buildHomeMoneyTimeline(
        transactions = transactions,
        accounts = accounts,
        manualBills = manualBills,
        recurringPayments = recurringPayments,
        loans = loans,
        creditCardBills = creditCardBills,
        paymentCommitments = paymentCommitments,
        today = today,
        zoneId = zoneId,
        horizonDays = horizonDays,
    )
    val pulse = buildHomeFinancialPulse(
        effectiveExpenseTransactions = budgetTransactions,
        accounts = accounts,
        timeline = timeline,
        savingsGoals = savingsGoals,
        savingsContributions = savingsContributions,
        today = today,
        nowMillis = now.toInstant().toEpochMilli(),
        zoneId = zoneId,
        safetyBufferBasisPoints = safetyBufferBasisPoints,
    )
    return HomeDashboardSnapshot(
        pulse = pulse,
        timeline = timeline,
        budgetPace = buildHomeBudgetPace(
            advancedBudgets = advancedBudgets,
            legacyBudgets = legacyBudgets,
            effectiveExpenseTransactions = budgetTransactions,
            today = today,
            zoneId = zoneId,
        ),
        cardHealth = buildHomeCardHealth(accounts, creditCardBills),
    )
}

fun buildHomeMoneyTimeline(
    transactions: List<TransactionRecord>,
    accounts: List<AccountProfile>,
    manualBills: List<BillReminder>,
    recurringPayments: List<RecurringPayment>,
    loans: List<LoanAccount>,
    creditCardBills: List<CreditCardBill>,
    paymentCommitments: List<PaymentCommitment>,
    today: LocalDate,
    zoneId: ZoneId,
    horizonDays: Int = 14,
): HomeMoneyTimeline {
    require(horizonDays in 1..90) { "Home timeline must cover between 1 and 90 days" }
    val endExclusive = today.plusDays(horizonDays.toLong())
    val accountNames = accounts.associate { it.id to it.name }
    val accountIdsByName = accounts.associate { normalizedMerchantKey(it.name) to it.id }
    val commitmentItems = buildPaymentCommitmentDueItems(
        commitments = deduplicatedPaymentCommitments(paymentCommitments),
        today = today,
        horizonDays = horizonDays,
        includeRepeatingOccurrences = true,
        accountNamesById = accountNames,
    ).map { due -> due.toHomeTimelineItem(HomeTimelineSource.PAYMENT_COMMITMENT) }
    val reviewedCommitmentKeys = paymentCommitments
        .asSequence()
        .filter { it.status == PaymentCommitmentStatus.ACTIVE && it.amountMinor > 0 }
        .mapTo(hashSetOf(), ::paymentCommitmentIdentityKey)
    val unreviewedRecurringPayments = recurringPayments.filterNot { recurring ->
        recurringPaymentIdentityKey(recurring, accountIdsByName) in reviewedCommitmentKeys
    }
    val scheduledItems = buildDueItems(
        manualBills = manualBills,
        recurringPayments = unreviewedRecurringPayments,
        loans = loans,
        today = today,
        zoneId = zoneId,
        horizonDays = horizonDays,
        includeRepeatingOccurrences = true,
    ).map { due ->
        val source = when (due.source) {
            DueItemSource.MANUAL_BILL -> HomeTimelineSource.BILL
            DueItemSource.RECURRING_PAYMENT -> HomeTimelineSource.RECURRING_PAYMENT
            DueItemSource.LOAN_EMI -> HomeTimelineSource.LOAN_EMI
            DueItemSource.PAYMENT_COMMITMENT -> HomeTimelineSource.PAYMENT_COMMITMENT
        }
        due.toHomeTimelineItem(source)
    }
    val cardItems = currentCreditCardBills(creditCardBills)
        .asSequence()
        .filter { it.status == CreditCardBillStatus.DUE }
        .map { bill ->
            HomeTimelineItem(
                stableId = "card-bill:${bill.id}:${bill.dueDateEpochDay}",
                title = "${bill.institutionName} card bill",
                amountMinor = bill.totalDueMinor,
                date = LocalDate.ofEpochDay(bill.dueDateEpochDay),
                source = HomeTimelineSource.CREDIT_CARD_BILL,
                accountName = bill.accountId?.let(accountNames::get),
            )
        }
        .filter { it.date.isBefore(endExclusive) }
        .toList()
    val incomeItems = inferExpectedIncomeItems(transactions, today, endExclusive, zoneId)
    val items = (commitmentItems + scheduledItems + cardItems + incomeItems)
        .filter { it.date.isBefore(endExclusive) }
        .sortedWith(compareBy<HomeTimelineItem> { it.date }.thenByDescending { it.isIncoming }.thenBy { it.title })
    return HomeMoneyTimeline(
        startDate = today,
        endDateInclusive = endExclusive.minusDays(1),
        items = items,
        incomingMinor = items.filter(HomeTimelineItem::isIncoming).sumMoney(HomeTimelineItem::amountMinor),
        outgoingMinor = items.filterNot(HomeTimelineItem::isIncoming).sumMoney(HomeTimelineItem::amountMinor),
    )
}

fun buildHomeBudgetPace(
    advancedBudgets: List<AdvancedBudgetPlan>,
    legacyBudgets: List<CategoryBudget>,
    effectiveExpenseTransactions: List<TransactionRecord>,
    today: LocalDate,
    zoneId: ZoneId,
): HomeBudgetPace? {
    val advancedResults = advancedBudgets.mapNotNull { plan ->
        runCatching { evaluateBudgetPlan(plan, effectiveExpenseTransactions, today, zoneId) }.getOrNull()
    }.filterNot { it.health == BudgetHealth.ENDED }
    if (advancedResults.isNotEmpty()) {
        val available = advancedResults.sumMoney(BudgetPeriodResult::availableMinor)
        val actual = advancedResults.sumMoney(BudgetPeriodResult::actualMinor)
        val planned = advancedResults.sumMoney(BudgetPeriodResult::plannedToDateMinor)
        val weightedElapsed = advancedResults.weightedElapsedBasisPoints(today)
        return HomeBudgetPace(
            planCount = advancedResults.size,
            availableMinor = available,
            spentMinor = actual,
            remainingMinor = saturatingSubtract(available, actual),
            plannedToDateMinor = planned,
            actualVsPlannedMinor = saturatingSubtract(actual, planned),
            spentBasisPoints = moneyBasisPoints(actual, available),
            periodElapsedBasisPoints = weightedElapsed,
            health = advancedResults.maxOf { it.health.homeSeverity() }.toBudgetHealth(),
            usesAdvancedPlans = true,
        )
    }

    val activeLegacyBudgets = legacyBudgets.filter { it.limitMinor > 0 }
    val available = activeLegacyBudgets.sumMoney(CategoryBudget::limitMinor)
    if (available <= 0) return null
    val budgetedCategories = activeLegacyBudgets.mapTo(hashSetOf(), CategoryBudget::category)
    val month = YearMonth.from(today)
    val actual = effectiveExpenseTransactions.asSequence()
        .filter { it.category in budgetedCategories }
        .filter { YearMonth.from(Instant.ofEpochMilli(it.occurredAt).atZone(zoneId)) == month }
        .fold(0L) { total, transaction ->
            if (transaction.type == TransactionType.REFUND) {
                saturatingSubtract(total, transaction.amountMinor.coerceAtLeast(0))
            } else {
                saturatingAdd(total, transaction.amountMinor.coerceAtLeast(0))
            }
        }
        .coerceAtLeast(0)
    val elapsed = ((today.dayOfMonth.toDouble() / month.lengthOfMonth()) * 10_000).roundToInt().coerceIn(0, 10_000)
    val planned = (available.toDouble() * elapsed / 10_000.0).toLong()
    val spentBasisPoints = moneyBasisPoints(actual, available)
    return HomeBudgetPace(
        planCount = activeLegacyBudgets.size,
        availableMinor = available,
        spentMinor = actual,
        remainingMinor = saturatingSubtract(available, actual),
        plannedToDateMinor = planned,
        actualVsPlannedMinor = saturatingSubtract(actual, planned),
        spentBasisPoints = spentBasisPoints,
        periodElapsedBasisPoints = elapsed,
        health = when {
            actual > available -> BudgetHealth.EXCEEDED
            spentBasisPoints >= 8_000 -> BudgetHealth.WARNING
            else -> BudgetHealth.ON_TRACK
        },
        usesAdvancedPlans = false,
    )
}

fun buildHomeCardHealth(
    accounts: List<AccountProfile>,
    creditCardBills: List<CreditCardBill>,
): HomeCardHealth {
    val currentBills = currentCreditCardBills(creditCardBills)
    val billsByAccountId = currentBills.mapNotNull { bill -> bill.accountId?.let { it to bill } }.toMap()
    val billsByIdentity = currentBills.mapNotNull { bill ->
        cardIdentityKey(bill.institutionName, bill.accountHint)?.let { it to bill }
    }.toMap()
    val matchedBillIds = mutableSetOf<Long>()
    val accountCards = accounts
        .filter { it.type == AccountType.CREDIT_CARD }
        .groupBy(::homeDashboardAccountKey)
        .values
        .map { matches ->
            val account = matches.maxWithOrNull(
                compareBy<AccountProfile> {
                    when {
                        it.availableCreditMinor != null -> 2
                        it.creditLimitMinor != null -> 1
                        else -> 0
                    }
                }.thenBy { it.availabilityFetchedAt ?: Long.MIN_VALUE },
            ) ?: matches.first()
            val bill = matches.mapNotNull { billsByAccountId[it.id] }
                .maxWithOrNull(compareBy<CreditCardBill> { it.dueDateEpochDay }.thenBy { it.detectedAt })
                ?: cardIdentityKey(account.institution, account.accountHint)?.let(billsByIdentity::get)
            bill?.let { matchedBillIds += it.id }
            val partialMergedCredit = account.mergedMemberCount > 1 &&
                account.availableCreditMinor != null && account.availabilityFetchedAt == null
            val completeAvailableCredit = if (partialMergedCredit) {
                null
            } else {
                account.availableCreditMinor
                    ?: matches.filter { it.availableCreditMinor != null }
                        .maxByOrNull { it.availabilityFetchedAt ?: Long.MIN_VALUE }
                        ?.availableCreditMinor
            }
            val utilization = calculateCreditUtilization(
                account.id,
                completeAvailableCredit,
                account.creditLimitMinor
                    ?: matches.filter { it.creditLimitMinor != null }
                        .maxByOrNull { it.availabilityFetchedAt ?: Long.MIN_VALUE }
                        ?.creditLimitMinor,
            )
            HomeCardHealthItem(
                stableId = "account:${account.id}",
                accountId = account.id,
                name = account.name,
                accountHint = account.accountHint,
                totalDueMinor = bill?.takeIf { it.status == CreditCardBillStatus.DUE }?.totalDueMinor,
                minimumDueMinor = bill?.takeIf { it.status == CreditCardBillStatus.DUE }?.minimumDueMinor,
                dueDate = bill?.takeIf { it.status == CreditCardBillStatus.DUE }?.dueDateEpochDay?.let(LocalDate::ofEpochDay),
                availableCreditMinor = utilization.availableCreditMinor,
                creditLimitMinor = utilization.creditLimitMinor,
                utilizationBasisPoints = utilization.utilizationBasisPoints,
                utilizationBand = utilization.band,
            )
        }
    val billOnlyCards = currentBills
        .filter { it.status == CreditCardBillStatus.DUE && it.id !in matchedBillIds }
        .map { bill ->
            HomeCardHealthItem(
                stableId = "bill:${bill.id}",
                accountId = bill.accountId,
                name = "${bill.institutionName} card",
                accountHint = bill.accountHint,
                totalDueMinor = bill.totalDueMinor,
                minimumDueMinor = bill.minimumDueMinor,
                dueDate = LocalDate.ofEpochDay(bill.dueDateEpochDay),
                availableCreditMinor = null,
                creditLimitMinor = null,
                utilizationBasisPoints = null,
                utilizationBand = CreditUtilizationBand.UNKNOWN,
            )
        }
    val cards = (accountCards + billOnlyCards).sortedWith(
        compareBy<HomeCardHealthItem> { it.dueDate ?: LocalDate.MAX }
            .thenByDescending { it.utilizationBasisPoints ?: -1 },
    )
    return HomeCardHealth(
        cards = cards,
        totalDueMinor = cards.mapNotNull(HomeCardHealthItem::totalDueMinor).sumMoney { it },
        totalAvailableCreditMinor = cards.mapNotNull(HomeCardHealthItem::availableCreditMinor)
            .takeIf(List<Long>::isNotEmpty)
            ?.sumMoney { it },
        highestUtilizationBasisPoints = cards.mapNotNull(HomeCardHealthItem::utilizationBasisPoints).maxOrNull(),
        highUtilizationCount = cards.count { it.utilizationBand in setOf(CreditUtilizationBand.HIGH, CreditUtilizationBand.CRITICAL) },
        nextDueDate = cards.mapNotNull(HomeCardHealthItem::dueDate).minOrNull(),
    )
}

private fun buildHomeFinancialPulse(
    effectiveExpenseTransactions: List<TransactionRecord>,
    accounts: List<AccountProfile>,
    timeline: HomeMoneyTimeline,
    savingsGoals: List<SavingsGoal>,
    savingsContributions: List<SavingsContribution>,
    today: LocalDate,
    nowMillis: Long,
    zoneId: ZoneId,
    safetyBufferBasisPoints: Int,
): HomeFinancialPulse {
    val liquidAccounts = accounts
        .filter { it.type in setOf(AccountType.BANK_ACCOUNT, AccountType.WALLET, AccountType.CASH) }
        .groupBy(::homeDashboardAccountKey)
        .values
        .map { matches ->
            matches.maxWithOrNull(
                compareBy<AccountProfile> { if (it.balanceMinor != null) 1 else 0 }
                    .thenBy { it.availabilityFetchedAt ?: Long.MIN_VALUE }
                    .thenBy { it.id },
            )
                ?: matches.first()
        }
    val availableAccounts = liquidAccounts.filter { it.balanceMinor != null }
    val availableCash = availableAccounts.takeIf(List<AccountProfile>::isNotEmpty)
        ?.mapNotNull(AccountProfile::balanceMinor)
        ?.sumMoney { it }
    val hasIncompleteMergedLiquidAccount = liquidAccounts.any {
        it.mergedMemberCount > 1 && it.availabilityFetchedAt == null
    }
    val expectedIncomeDate = timeline.items.firstOrNull { it.isIncoming }?.date
    val throughDate = expectedIncomeDate?.minusDays(1)?.coerceAtLeast(today) ?: timeline.endDateInclusive
    val obligations = timeline.items
        .filter { !it.isIncoming && !it.date.isAfter(throughDate) }
        .sumMoney(HomeTimelineItem::amountMinor)
    val reserveDays = ChronoUnit.DAYS.between(today, throughDate).plus(1).coerceAtLeast(1)
    val goalReserve = savingsGoals.asSequence()
        .filter(SavingsGoal::isActive)
        .mapNotNull { goal -> calculateSavingsGoalProgress(goal, savingsContributions, today).requiredMonthlyMinor }
        .filter { it > 0 }
        .map { monthly -> (monthly.toDouble() * reserveDays / 30.0).toLong() }
        .toList()
        .sumMoney { it }
    val safetyBuffer = availableCash?.coerceAtLeast(0)?.let {
        (it.toDouble() * safetyBufferBasisPoints / 10_000.0).toLong()
    } ?: 0L
    val safeToSpend = availableCash?.takeUnless { hasIncompleteMergedLiquidAccount }?.let { cash ->
        saturatingSubtract(saturatingSubtract(saturatingSubtract(cash, obligations), goalReserve), safetyBuffer)
    }
    val timestamps = availableAccounts.mapNotNull(AccountProfile::availabilityFetchedAt)
    val missingCount = liquidAccounts.size - availableAccounts.size
    val unknownFreshnessCount = missingCount + availableAccounts.count { it.availabilityFetchedAt == null }
    val freshness = balanceFreshness(timestamps, unknownFreshnessCount, nowMillis, zoneId)
    val currentMonth = YearMonth.from(today)
    val monthlySpend = effectiveExpenseTransactions.asSequence()
        .filter { YearMonth.from(Instant.ofEpochMilli(it.occurredAt).atZone(zoneId)) == currentMonth }
        .fold(0L) { total, transaction ->
            if (transaction.type == TransactionType.REFUND) {
                saturatingSubtract(total, transaction.amountMinor.coerceAtLeast(0))
            } else {
                saturatingAdd(total, transaction.amountMinor.coerceAtLeast(0))
            }
        }
        .coerceAtLeast(0)
    val assumptions = buildList {
        if (availableAccounts.isEmpty()) {
            add("Safe to spend needs at least one saved bank, wallet, or cash balance.")
        } else {
            add("Uses ${availableAccounts.size} of ${liquidAccounts.size} spendable account balances.")
            if (hasIncompleteMergedLiquidAccount) {
                add("Safe to spend is withheld until every merged account balance is available.")
            }
            add("Subtracts scheduled payments through $throughDate; predicted income is not added.")
            if (goalReserve > 0) add("Sets aside a proportional reserve for active savings goals.")
            if (safetyBufferBasisPoints > 0) add("Keeps a ${safetyBufferBasisPoints / 100.0}% safety buffer.")
            add("Unrecorded cash, pending debits, and unscheduled spending are not included.")
        }
    }
    return HomeFinancialPulse(
        safeToSpendMinor = safeToSpend,
        availableCashMinor = availableCash,
        upcomingObligationsMinor = obligations,
        goalReserveMinor = goalReserve,
        safetyBufferMinor = safetyBuffer,
        monthlySpendMinor = monthlySpend,
        throughDate = throughDate,
        balanceFreshness = freshness,
        confidence = when {
            availableAccounts.isEmpty() -> HomePulseConfidence.UNAVAILABLE
            missingCount > 0 || freshness !in setOf(HomeBalanceFreshness.FRESH, HomeBalanceFreshness.AGING) -> HomePulseConfidence.PARTIAL
            else -> HomePulseConfidence.COMPLETE
        },
        availableBalanceCount = availableAccounts.size,
        totalBalanceCount = liquidAccounts.size,
        oldestBalanceAt = timestamps.minOrNull(),
        latestBalanceAt = timestamps.maxOrNull(),
        assumptions = assumptions,
    )
}

private fun inferExpectedIncomeItems(
    transactions: List<TransactionRecord>,
    today: LocalDate,
    endExclusive: LocalDate,
    zoneId: ZoneId,
): List<HomeTimelineItem> = transactions.asSequence()
    .filter { it.type == TransactionType.INCOME && it.reviewStatus == ReviewStatus.CONFIRMED }
    .filter { Instant.ofEpochMilli(it.occurredAt).atZone(zoneId).toLocalDate().isBefore(today) }
    .groupBy { normalizedMerchantKey(it.merchant) to it.accountIdentityId() }
    .mapNotNull { (identity, matches) ->
        if (identity.first.isBlank() || matches.size < 2) return@mapNotNull null
        val ordered = matches.sortedBy(TransactionRecord::occurredAt)
        val dates = ordered.map { Instant.ofEpochMilli(it.occurredAt).atZone(zoneId).toLocalDate() }
        val intervals = dates.zipWithNext { first, second -> ChronoUnit.DAYS.between(first, second).toInt() }
            .filter { it > 0 }
            .sorted()
        if (intervals.isEmpty()) return@mapNotNull null
        val medianInterval = intervals[intervals.size / 2]
        val normalizedInterval = when (medianInterval) {
            in 6..8 -> 7
            in 25..40 -> medianInterval
            else -> return@mapNotNull null
        }
        val daysSinceLatest = ChronoUnit.DAYS.between(dates.last(), today)
        val recencyToleranceDays = if (normalizedInterval == 7) 3 else 10
        if (daysSinceLatest > normalizedInterval + recencyToleranceDays) return@mapNotNull null
        var nextDate = dates.last().plusDays(normalizedInterval.toLong())
        while (nextDate.isBefore(today)) nextDate = nextDate.plusDays(normalizedInterval.toLong())
        if (!nextDate.isBefore(endExclusive)) return@mapNotNull null
        val amounts = ordered.map(TransactionRecord::amountMinor).sorted()
        val latest = ordered.last()
        HomeTimelineItem(
            stableId = "expected-income:${identity.first}:${identity.second ?: 0}:$nextDate",
            title = latest.merchant,
            amountMinor = amounts[amounts.size / 2],
            date = nextDate,
            source = HomeTimelineSource.EXPECTED_INCOME,
            accountName = latest.accountName,
            isIncoming = true,
            isEstimate = true,
        )
    }
    .sortedBy(HomeTimelineItem::date)

private fun DueItem.toHomeTimelineItem(source: HomeTimelineSource): HomeTimelineItem = HomeTimelineItem(
    stableId = stableId,
    title = title,
    amountMinor = amountMinor,
    date = dueDate,
    source = source,
    accountName = accountName,
)

internal fun homeDashboardAccountKey(account: AccountProfile): String {
    val institutionKey = homeCanonicalInstitutionKey(account.institution)
    val lastFour = account.accountHint
        ?.filter(Char::isDigit)
        ?.takeLast(4)
        ?.takeIf { it.length == 4 }
    return if (institutionKey != null && lastFour != null) {
        "${account.type.name}:institution:$institutionKey:last4:$lastFour"
    } else {
        "${account.type.name}:account:${account.id}"
    }
}

private fun cardIdentityKey(institution: String?, accountHint: String?): String? {
    val institutionKey = homeCanonicalInstitutionKey(institution) ?: return null
    val lastFour = accountHint?.filter(Char::isDigit)?.takeLast(4)?.takeIf { it.length == 4 } ?: return null
    return "$institutionKey:$lastFour"
}

private fun homeCanonicalInstitutionKey(institution: String?): String? {
    val value = institution?.trim()?.takeIf(String::isNotBlank) ?: return null
    return BankSmsSupport.bankKey(value)
        ?: normalizedMerchantKey(value).takeIf(String::isNotBlank)
}

private fun balanceFreshness(
    timestamps: List<Long>,
    missingCount: Int,
    nowMillis: Long,
    zoneId: ZoneId,
): HomeBalanceFreshness {
    if (timestamps.isEmpty()) return HomeBalanceFreshness.UNAVAILABLE
    val today = Instant.ofEpochMilli(nowMillis).atZone(zoneId).toLocalDate()
    val oldestDate = Instant.ofEpochMilli(timestamps.min()).atZone(zoneId).toLocalDate()
    val ageDays = ChronoUnit.DAYS.between(oldestDate, today).coerceAtLeast(0)
    return when {
        ageDays > 7 -> HomeBalanceFreshness.STALE
        missingCount > 0 -> HomeBalanceFreshness.PARTIAL
        ageDays <= 1 -> HomeBalanceFreshness.FRESH
        else -> HomeBalanceFreshness.AGING
    }
}

private fun List<BudgetPeriodResult>.weightedElapsedBasisPoints(today: LocalDate): Int {
    val weighted = map { result ->
        val totalDays = ChronoUnit.DAYS.between(result.range.start, result.range.endInclusive).plus(1).coerceAtLeast(1)
        val elapsedDays = ChronoUnit.DAYS.between(result.range.start, minOf(today, result.range.endInclusive))
            .plus(1)
            .coerceIn(0, totalDays)
        val basisPoints = ((elapsedDays.toDouble() / totalDays) * 10_000).roundToInt()
        basisPoints to result.availableMinor.coerceAtLeast(0)
    }
    val totalWeight = weighted.sumMoney { it.second }
    return if (totalWeight > 0) {
        (weighted.sumOf { (basisPoints, weight) -> basisPoints.toDouble() * weight } / totalWeight)
            .roundToInt()
            .coerceIn(0, 10_000)
    } else {
        weighted.map { it.first }.average().roundToInt().coerceIn(0, 10_000)
    }
}

private fun BudgetHealth.homeSeverity(): Int = when (this) {
    BudgetHealth.NOT_STARTED -> 0
    BudgetHealth.ON_TRACK -> 1
    BudgetHealth.WARNING -> 2
    BudgetHealth.EXCEEDED -> 3
    BudgetHealth.ENDED -> 0
}

private fun Int.toBudgetHealth(): BudgetHealth = when (this) {
    3 -> BudgetHealth.EXCEEDED
    2 -> BudgetHealth.WARNING
    1 -> BudgetHealth.ON_TRACK
    else -> BudgetHealth.NOT_STARTED
}

private fun moneyBasisPoints(value: Long, total: Long): Int = when {
    total <= 0 -> 0
    else -> ((value.toDouble() / total) * 10_000).roundToInt().coerceAtLeast(0)
}

private inline fun <T> Iterable<T>.sumMoney(selector: (T) -> Long): Long =
    fold(0L) { total, item -> saturatingAdd(total, selector(item)) }

private fun saturatingAdd(left: Long, right: Long): Long = try {
    Math.addExact(left, right)
} catch (_: ArithmeticException) {
    if (right >= 0) Long.MAX_VALUE else Long.MIN_VALUE
}

private fun saturatingSubtract(left: Long, right: Long): Long = try {
    Math.subtractExact(left, right)
} catch (_: ArithmeticException) {
    if (right >= 0) Long.MIN_VALUE else Long.MAX_VALUE
}
