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
)

fun paymentCommitmentIdentityKey(commitment: PaymentCommitment): Triple<String, Long?, PaymentCommitmentKind> =
    Triple(
        normalizedMerchantKey(commitment.merchantKey.ifBlank { commitment.name }),
        commitment.accountId,
        commitment.kind,
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
    fun accountKey(accountId: Long?, accountName: String?): String = accountId?.let { "id:$it" }
        ?: accountName?.let(::normalizedMerchantKey)?.takeIf(String::isNotBlank)?.let { "name:$it" }
        ?: "unscoped"
    val seenKeys = existingCommitments.mapTo(mutableSetOf()) { commitment ->
        Triple(
            normalizedMerchantKey(commitment.merchantKey.ifBlank { commitment.name }),
            accountKey(commitment.accountId, null),
            commitment.kind,
        )
    }
    return recurringPayments.mapNotNull { recurring ->
        val merchantKey = normalizedMerchantKey(recurring.merchant)
        val accountId = accounts.firstOrNull { account ->
            recurring.accountName != null && account.name.equals(recurring.accountName, ignoreCase = true)
        }?.id
        val suggestionKey = Triple(
            merchantKey,
            accountKey(accountId, recurring.accountName),
            PaymentCommitmentKind.SUBSCRIPTION,
        )
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
            source = PaymentCommitmentSource.ON_DEVICE_SUGGESTION,
            categoryLabel = recurring.categoryLabel,
        )
    }.sortedBy(PaymentCommitment::nextDueEpochDay)
}
