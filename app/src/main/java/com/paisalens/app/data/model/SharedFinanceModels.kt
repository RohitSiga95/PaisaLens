package com.paisalens.app.data.model

import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import kotlin.math.ceil
import kotlin.math.roundToInt

enum class ExpenseSplitStatus {
    OPEN,
    PARTIALLY_REIMBURSED,
    REIMBURSED,
}

data class ExpenseSplit(
    val id: Long = 0,
    val transactionId: Long,
    val participantName: String,
    val shareMinor: Long,
    val reimbursedMinor: Long = 0,
    val linkedIncomingTransactionId: Long? = null,
    val note: String? = null,
    val status: ExpenseSplitStatus = ExpenseSplitStatus.OPEN,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val entryMode: ExpenseSplitEntryMode = ExpenseSplitEntryMode.AMOUNT,
    /** Original percentage in basis points (10,000 = 100%) when [entryMode] is percentage. */
    val shareBasisPoints: Int? = null,
)

enum class ExpenseSplitValidationIssue {
    TRANSACTION_NOT_FOUND,
    NOT_AN_EXPENSE,
    PARTICIPANT_NAME_EMPTY,
    SHARE_NOT_POSITIVE,
    REIMBURSEMENT_NEGATIVE,
    REIMBURSEMENT_EXCEEDS_SHARE,
    ALLOCATION_EXCEEDS_EXPENSE,
    LINKED_TRANSACTION_NOT_FOUND,
    LINKED_TRANSACTION_NOT_INCOMING,
    LINKED_TRANSACTION_REUSED,
    LINKED_TRANSACTION_EXCEEDS_REIMBURSEMENT,
    INVALID_PERCENTAGE_CONFIGURATION,
}

data class ExpenseSplitValidation(
    val isValid: Boolean,
    val issue: ExpenseSplitValidationIssue? = null,
)

data class ExpenseSplitSummary(
    val transactionId: Long,
    val totalExpenseMinor: Long,
    val allocatedMinor: Long,
    val reimbursedMinor: Long,
    val outstandingMinor: Long,
    val unallocatedMinor: Long,
    val participantCount: Int,
    val settledParticipantCount: Int,
)

/** How participant shares are configured. Exact minor-unit shares are retained for ledger calculations. */
enum class ExpenseSplitEntryMode {
    AMOUNT,
    PERCENTAGE,
}

data class ExpenseSplitCategoryStat(
    val category: ExpenseCategory,
    val customCategoryId: Long? = null,
    val categoryLabel: String,
    val transactionCount: Int,
    val allocatedMinor: Long,
    val reimbursedMinor: Long,
    val outstandingMinor: Long,
)

/** Converts basis-point percentages (10,000 = 100%) into deterministic exact shares. */
fun expenseSplitSharesFromPercentages(
    totalMinor: Long,
    percentagesBasisPoints: List<Int>,
): List<Long> {
    require(totalMinor >= 0) { "Expense total cannot be negative." }
    require(percentagesBasisPoints.all { it in 1..10_000 }) { "Each percentage must be above 0% and at most 100%." }
    val totalBasisPoints = percentagesBasisPoints.sum()
    require(totalBasisPoints <= 10_000) { "Participant percentages cannot exceed 100%." }
    var remainingMinor = totalMinor
    return percentagesBasisPoints.mapIndexed { index, basisPoints ->
        val share = if (index == percentagesBasisPoints.lastIndex && totalBasisPoints == 10_000) {
            remainingMinor
        } else {
            ((totalMinor * basisPoints.toLong() + 5_000L) / 10_000L).coerceAtMost(remainingMinor)
        }
        remainingMinor -= share
        share
    }
}

fun expenseSplitShareBasisPoints(shareMinor: Long, totalMinor: Long): Int = when {
    shareMinor <= 0 || totalMinor <= 0 -> 0
    else -> ((shareMinor * 10_000L + totalMinor / 2L) / totalMinor)
        .coerceIn(0L, 10_000L)
        .toInt()
}

/**
 * Converts exact shares back to a stable percentage configuration without allowing independent
 * rounding to push the total above 100%. Largest remainders receive the spare basis points.
 */
fun expenseSplitBasisPointsFromShares(
    sharesMinor: List<Long>,
    totalMinor: Long,
): List<Int> {
    require(totalMinor > 0) { "Expense total must be positive." }
    require(sharesMinor.all { it > 0 }) { "Every participant share must be positive." }
    val allocatedMinor = sharesMinor.sum()
    require(allocatedMinor <= totalMinor) { "Participant shares cannot exceed the expense." }
    if (sharesMinor.isEmpty()) return emptyList()

    val scaled = sharesMinor.map { it * 10_000L }
    val result = scaled.map { (it / totalMinor).toInt() }.toMutableList()
    val roundedTotal = ((allocatedMinor * 10_000L + totalMinor / 2L) / totalMinor)
        .coerceIn(sharesMinor.size.toLong(), 10_000L)
        .toInt()
    var spare = roundedTotal - result.sum()
    val remainderOrder = scaled.indices.sortedWith(
        compareByDescending<Int> { scaled[it] % totalMinor }.thenBy { it },
    )
    var cursor = 0
    while (spare > 0) {
        result[remainderOrder[cursor % remainderOrder.size]] += 1
        cursor += 1
        spare -= 1
    }
    return result
}

fun buildExpenseSplitCategoryStats(
    transactions: List<TransactionRecord>,
    splits: List<ExpenseSplit>,
): List<ExpenseSplitCategoryStat> {
    val transactionsById = transactions.associateBy(TransactionRecord::id)
    return splits
        .groupBy { split ->
            val transaction = transactionsById[split.transactionId]
            Triple(
                transaction?.category ?: ExpenseCategory.OTHER,
                transaction?.customCategoryId,
                transaction?.categoryLabel() ?: ExpenseCategory.OTHER.label,
            )
        }
        .map { (key, rows) ->
            val allocated = rows.sumOf(ExpenseSplit::shareMinor)
            val reimbursed = rows.sumOf(ExpenseSplit::reimbursedMinor)
            ExpenseSplitCategoryStat(
                category = key.first,
                customCategoryId = key.second,
                categoryLabel = key.third,
                transactionCount = rows.map(ExpenseSplit::transactionId).distinct().size,
                allocatedMinor = allocated,
                reimbursedMinor = reimbursed,
                outstandingMinor = (allocated - reimbursed).coerceAtLeast(0),
            )
        }
        .sortedWith(compareByDescending<ExpenseSplitCategoryStat> { it.outstandingMinor }.thenBy { it.categoryLabel })
}

fun expenseSplitStatus(shareMinor: Long, reimbursedMinor: Long): ExpenseSplitStatus = when {
    shareMinor > 0 && reimbursedMinor >= shareMinor -> ExpenseSplitStatus.REIMBURSED
    reimbursedMinor > 0 -> ExpenseSplitStatus.PARTIALLY_REIMBURSED
    else -> ExpenseSplitStatus.OPEN
}

fun validateExpenseSplits(
    transaction: TransactionRecord?,
    splits: List<ExpenseSplit>,
    transactionsById: Map<Long, TransactionRecord> = emptyMap(),
): ExpenseSplitValidation {
    if (transaction == null) return ExpenseSplitValidation(false, ExpenseSplitValidationIssue.TRANSACTION_NOT_FOUND)
    if (transaction.type != TransactionType.EXPENSE) {
        return ExpenseSplitValidation(false, ExpenseSplitValidationIssue.NOT_AN_EXPENSE)
    }
    splits.forEach { split ->
        if (split.transactionId != transaction.id) {
            return ExpenseSplitValidation(false, ExpenseSplitValidationIssue.TRANSACTION_NOT_FOUND)
        }
        if (split.participantName.isBlank()) {
            return ExpenseSplitValidation(false, ExpenseSplitValidationIssue.PARTICIPANT_NAME_EMPTY)
        }
        if (split.shareMinor <= 0) {
            return ExpenseSplitValidation(false, ExpenseSplitValidationIssue.SHARE_NOT_POSITIVE)
        }
        if (split.reimbursedMinor < 0) {
            return ExpenseSplitValidation(false, ExpenseSplitValidationIssue.REIMBURSEMENT_NEGATIVE)
        }
        if (split.reimbursedMinor > split.shareMinor) {
            return ExpenseSplitValidation(false, ExpenseSplitValidationIssue.REIMBURSEMENT_EXCEEDS_SHARE)
        }
        if (
            split.entryMode == ExpenseSplitEntryMode.PERCENTAGE &&
            split.shareBasisPoints !in 1..10_000
        ) {
            return ExpenseSplitValidation(false, ExpenseSplitValidationIssue.INVALID_PERCENTAGE_CONFIGURATION)
        }
    }
    if (
        splits.any { it.entryMode == ExpenseSplitEntryMode.PERCENTAGE } &&
        splits.filter { it.entryMode == ExpenseSplitEntryMode.PERCENTAGE }
            .sumOf { it.shareBasisPoints ?: 0 } > 10_000
    ) {
        return ExpenseSplitValidation(false, ExpenseSplitValidationIssue.INVALID_PERCENTAGE_CONFIGURATION)
    }
    if (splits.sumOf(ExpenseSplit::shareMinor) > transaction.amountMinor) {
        return ExpenseSplitValidation(false, ExpenseSplitValidationIssue.ALLOCATION_EXCEEDS_EXPENSE)
    }
    val linkedIds = splits.mapNotNull(ExpenseSplit::linkedIncomingTransactionId)
    if (linkedIds.size != linkedIds.distinct().size) {
        return ExpenseSplitValidation(false, ExpenseSplitValidationIssue.LINKED_TRANSACTION_REUSED)
    }
    splits.forEach { split ->
        split.linkedIncomingTransactionId?.let { linkedId ->
            val linked = transactionsById[linkedId]
                ?: return ExpenseSplitValidation(false, ExpenseSplitValidationIssue.LINKED_TRANSACTION_NOT_FOUND)
            if (linked.type != TransactionType.INCOME && linked.type != TransactionType.REFUND) {
                return ExpenseSplitValidation(false, ExpenseSplitValidationIssue.LINKED_TRANSACTION_NOT_INCOMING)
            }
            // A split can combine cash/manual reimbursement with a linked incoming credit,
            // but analytics must never offset more than the total recorded reimbursement.
            if (linked.amountMinor > split.reimbursedMinor) {
                return ExpenseSplitValidation(false, ExpenseSplitValidationIssue.LINKED_TRANSACTION_EXCEEDS_REIMBURSEMENT)
            }
        }
    }
    return ExpenseSplitValidation(true)
}

fun buildExpenseSplitSummary(
    transaction: TransactionRecord,
    splits: List<ExpenseSplit>,
): ExpenseSplitSummary {
    val relevant = splits.filter { it.transactionId == transaction.id }
    val allocated = relevant.sumOf(ExpenseSplit::shareMinor)
    val reimbursed = relevant.sumOf(ExpenseSplit::reimbursedMinor)
    return ExpenseSplitSummary(
        transactionId = transaction.id,
        totalExpenseMinor = transaction.amountMinor,
        allocatedMinor = allocated,
        reimbursedMinor = reimbursed,
        outstandingMinor = (allocated - reimbursed).coerceAtLeast(0),
        unallocatedMinor = (transaction.amountMinor - allocated).coerceAtLeast(0),
        participantCount = relevant.size,
        settledParticipantCount = relevant.count { it.shareMinor > 0 && it.reimbursedMinor >= it.shareMinor },
    )
}

enum class SavingsGoalKind {
    SAVINGS_GOAL,
    SINKING_FUND,
}

enum class ContributionFrequency {
    WEEKLY,
    MONTHLY,
    ONE_TIME,
}

data class SavingsGoal(
    val id: Long = 0,
    val name: String,
    val targetMinor: Long,
    val startingSavedMinor: Long = 0,
    val targetDateEpochDay: Long? = null,
    val linkedAccountId: Long? = null,
    val kind: SavingsGoalKind = SavingsGoalKind.SAVINGS_GOAL,
    val contributionFrequency: ContributionFrequency = ContributionFrequency.MONTHLY,
    val notes: String? = null,
    val colorHex: String = "#21D19F",
    val isActive: Boolean = true,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
)

data class SavingsContribution(
    val id: Long = 0,
    val goalId: Long,
    val amountMinor: Long,
    val contributedAt: Long = System.currentTimeMillis(),
    val note: String? = null,
    val linkedTransactionId: Long? = null,
)

data class SavingsGoalProgress(
    val goalId: Long,
    val currentSavedMinor: Long,
    val remainingMinor: Long,
    /** 10,000 basis points represents 100%; overfunding is capped at 100%. */
    val progressBasisPoints: Int,
    val monthsRemaining: Int?,
    val requiredMonthlyMinor: Long?,
    val isComplete: Boolean,
)

fun calculateSavingsGoalProgress(
    goal: SavingsGoal,
    contributions: List<SavingsContribution>,
    asOf: LocalDate = LocalDate.now(),
): SavingsGoalProgress {
    val contributionTotal = contributions.asSequence()
        .filter { it.goalId == goal.id && it.amountMinor > 0 }
        .sumOf(SavingsContribution::amountMinor)
    val current = goal.startingSavedMinor.coerceAtLeast(0) + contributionTotal
    val target = goal.targetMinor.coerceAtLeast(0)
    val remaining = (target - current).coerceAtLeast(0)
    val progress = when {
        target <= 0 -> 10_000
        else -> ((current.toDouble() / target) * 10_000).roundToInt().coerceIn(0, 10_000)
    }
    val months = goal.targetDateEpochDay?.let { epochDay ->
        val targetDate = LocalDate.ofEpochDay(epochDay)
        if (targetDate.isBefore(asOf)) 0 else {
            (ChronoUnit.MONTHS.between(YearMonth.from(asOf), YearMonth.from(targetDate)) + 1)
                .coerceAtMost(Int.MAX_VALUE.toLong())
                .toInt()
        }
    }
    val requiredMonthly = when {
        goal.targetDateEpochDay == null -> null
        remaining == 0L -> 0L
        months == null || months <= 0 -> remaining
        else -> ceil(remaining.toDouble() / months).toLong()
    }
    return SavingsGoalProgress(
        goalId = goal.id,
        currentSavedMinor = current,
        remainingMinor = remaining,
        progressBasisPoints = progress,
        monthsRemaining = months,
        requiredMonthlyMinor = requiredMonthly,
        isComplete = remaining == 0L,
    )
}

enum class PaymentCommitmentKind {
    SUBSCRIPTION,
    UPI_AUTOPAY,
}

enum class PaymentFrequency {
    WEEKLY,
    MONTHLY,
    QUARTERLY,
    YEARLY,
    CUSTOM,
}

enum class PaymentCommitmentStatus {
    ACTIVE,
    PAUSED,
    CANCELLED,
    EXPIRED,
}

enum class PaymentCommitmentSource {
    MANUAL,
    ON_DEVICE_SUGGESTION,
}

data class PaymentCommitment(
    val id: Long = 0,
    val name: String,
    val merchantKey: String = "",
    val kind: PaymentCommitmentKind = PaymentCommitmentKind.SUBSCRIPTION,
    val frequency: PaymentFrequency = PaymentFrequency.MONTHLY,
    val customIntervalDays: Int? = null,
    val amountMinor: Long,
    val maxMandateMinor: Long? = null,
    val nextDueEpochDay: Long,
    val accountId: Long? = null,
    val upiHandle: String? = null,
    val status: PaymentCommitmentStatus = PaymentCommitmentStatus.ACTIVE,
    val source: PaymentCommitmentSource = PaymentCommitmentSource.MANUAL,
    val categoryLabel: String? = null,
    val notes: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    /** Raw storage identity retained when [accountId] is projected to a merged root. */
    val physicalAccountId: Long? = null,
)

fun PaymentCommitment.accountIdentityId(): Long? = physicalAccountId ?: accountId

fun paymentCommitmentIdentityKey(commitment: PaymentCommitment): Triple<String, Long?, PaymentCommitmentKind> =
    Triple(
        normalizedMerchantKey(commitment.merchantKey.ifBlank { commitment.name }),
        commitment.accountIdentityId(),
        commitment.kind,
    )

fun recurringPaymentIdentityKey(
    recurring: RecurringPayment,
    accountIdsByNormalizedName: Map<String, Long> = emptyMap(),
): Triple<String, Long?, PaymentCommitmentKind> = Triple(
    normalizedMerchantKey(recurring.merchant),
    recurring.accountIdentityId()
        ?: recurring.accountName?.let(::normalizedMerchantKey)?.let(accountIdsByNormalizedName::get),
    PaymentCommitmentKind.SUBSCRIPTION,
)

/** Keeps the most recently updated row for each merchant/account/kind identity. */
fun deduplicatedPaymentCommitments(commitments: List<PaymentCommitment>): List<PaymentCommitment> =
    commitments
        .sortedWith(compareByDescending<PaymentCommitment> { it.updatedAt }.thenByDescending { it.id })
        .distinctBy(::paymentCommitmentIdentityKey)
        .sortedBy(PaymentCommitment::id)

fun calculateNextPaymentDue(
    commitment: PaymentCommitment,
    afterDate: LocalDate,
): LocalDate {
    val anchor = LocalDate.ofEpochDay(commitment.nextDueEpochDay)
    var due = anchor
    var occurrence = 0L
    while (!due.isAfter(afterDate)) {
        occurrence += 1
        due = when (commitment.frequency) {
            PaymentFrequency.WEEKLY -> anchor.plusWeeks(occurrence)
            PaymentFrequency.MONTHLY -> anchoredPaymentMonth(anchor, occurrence)
            PaymentFrequency.QUARTERLY -> anchoredPaymentMonth(anchor, occurrence * 3)
            PaymentFrequency.YEARLY -> anchoredPaymentMonth(anchor, occurrence * 12)
            PaymentFrequency.CUSTOM -> anchor.plusDays(
                occurrence * (commitment.customIntervalDays?.coerceAtLeast(1)?.toLong() ?: 1),
            )
        }
    }
    return due
}

/** Resolves the next actionable occurrence while preserving the stored recurrence anchor. */
fun currentPaymentDueDate(
    commitment: PaymentCommitment,
    asOf: LocalDate = LocalDate.now(),
): LocalDate {
    val stored = LocalDate.ofEpochDay(commitment.nextDueEpochDay)
    return if (!stored.isBefore(asOf)) stored else calculateNextPaymentDue(commitment, asOf.minusDays(1))
}

private fun anchoredPaymentMonth(anchor: LocalDate, monthsAfterAnchor: Long): LocalDate {
    val targetMonth = YearMonth.from(anchor).plusMonths(monthsAfterAnchor)
    return if (anchor.dayOfMonth == YearMonth.from(anchor).lengthOfMonth()) {
        targetMonth.atEndOfMonth()
    } else {
        targetMonth.atDay(anchor.dayOfMonth.coerceAtMost(targetMonth.lengthOfMonth()))
    }
}

fun suggestPaymentCommitments(
    recurringPayments: List<RecurringPayment>,
    existingCommitments: List<PaymentCommitment>,
    zoneId: ZoneId = ZoneId.systemDefault(),
    accounts: List<AccountProfile> = emptyList(),
): List<PaymentCommitment> {
    val accountIdsByName = accounts.associate { normalizedMerchantKey(it.name) to it.id }
    val seenKeys = existingCommitments.mapTo(mutableSetOf(), ::paymentCommitmentIdentityKey)
    return recurringPayments.mapNotNull { recurring ->
        val merchantKey = normalizedMerchantKey(recurring.merchant)
        val accountId = recurring.accountId
            ?: recurring.accountName?.let(::normalizedMerchantKey)?.let(accountIdsByName::get)
        val suggestionKey = recurringPaymentIdentityKey(recurring, accountIdsByName)
        if (merchantKey.isBlank() || !seenKeys.add(suggestionKey)) return@mapNotNull null
        val frequency = when (recurring.intervalDays) {
            in 5..9 -> PaymentFrequency.WEEKLY
            in 25..40 -> PaymentFrequency.MONTHLY
            else -> PaymentFrequency.CUSTOM
        }
        PaymentCommitment(
            name = recurring.merchant,
            merchantKey = merchantKey,
            frequency = frequency,
            customIntervalDays = recurring.intervalDays.takeIf { frequency == PaymentFrequency.CUSTOM },
            amountMinor = recurring.typicalAmountMinor,
            nextDueEpochDay = Instant.ofEpochMilli(recurring.nextDueAt).atZone(zoneId).toLocalDate().toEpochDay(),
            accountId = accountId,
            physicalAccountId = recurring.physicalAccountId,
            source = PaymentCommitmentSource.ON_DEVICE_SUGGESTION,
            categoryLabel = recurring.categoryLabel,
        )
    }.sortedBy(PaymentCommitment::nextDueEpochDay)
}
