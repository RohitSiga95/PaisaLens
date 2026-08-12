package com.paisalens.app.notification

import com.paisalens.app.data.model.BillReminder
import com.paisalens.app.data.model.NotificationDigestConfiguration
import com.paisalens.app.data.model.NotificationDigestFrequency
import com.paisalens.app.data.model.PaymentCommitment
import com.paisalens.app.data.model.ExpenseSplit
import com.paisalens.app.data.model.ReviewStatus
import com.paisalens.app.data.model.SavingsContribution
import com.paisalens.app.data.model.SavingsGoal
import com.paisalens.app.data.model.TransactionRecord
import com.paisalens.app.data.model.TransactionLink
import com.paisalens.app.data.model.TransactionType
import com.paisalens.app.data.model.DueStatus
import com.paisalens.app.data.model.buildDueItems
import com.paisalens.app.data.model.buildEffectiveExpenseTransactions
import com.paisalens.app.data.model.calculateSavingsGoalProgress
import com.paisalens.app.data.model.buildPaymentCommitmentDueItems
import java.text.NumberFormat
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale

data class PrivateDigestSummary(
    val expenseCount: Int,
    val expenseTotalMinor: Long,
    val needsReviewCount: Int,
    val dueSoonCount: Int,
    val goalsNeedingAttentionCount: Int,
)

data class PrivateDigestText(
    val title: String,
    val text: String,
    val publicTitle: String = "PaisaLens",
    val publicText: String = "Your private money digest is ready",
)

/** Builds a compact summary exclusively from records already held in app-private storage. */
fun buildPrivateDigestSummary(
    transactions: List<TransactionRecord>,
    bills: List<BillReminder>,
    savingsGoals: List<SavingsGoal>,
    savingsContributions: List<SavingsContribution>,
    paymentCommitments: List<PaymentCommitment>,
    transactionLinks: List<TransactionLink> = emptyList(),
    expenseSplits: List<ExpenseSplit> = emptyList(),
    configuration: NotificationDigestConfiguration,
    nowMillis: Long = System.currentTimeMillis(),
    zoneId: ZoneId = ZoneId.systemDefault(),
): PrivateDigestSummary {
    val lookbackDays = when (configuration.frequency) {
        NotificationDigestFrequency.DAILY -> 1L
        NotificationDigestFrequency.WEEKLY -> 7L
    }
    val startMillis = nowMillis - lookbackDays * 24L * 60L * 60L * 1_000L
    val expenses = buildEffectiveExpenseTransactions(transactions, transactionLinks, expenseSplits)
        .filter { it.occurredAt in startMillis..nowMillis }
    val today = Instant.ofEpochMilli(nowMillis).atZone(zoneId).toLocalDate()
    val billCount = buildDueItems(
        manualBills = bills,
        recurringPayments = emptyList(),
        loans = emptyList(),
        today = today,
        zoneId = zoneId,
        horizonDays = 8,
    ).count { it.status in setOf(DueStatus.OVERDUE, DueStatus.DUE_TODAY, DueStatus.DUE_SOON) }
    val commitmentCount = buildPaymentCommitmentDueItems(
        commitments = paymentCommitments,
        today = today,
        horizonDays = 8,
        includeRepeatingOccurrences = true,
    ).size
    val goalsNeedingAttention = savingsGoals.count { goal ->
        if (!goal.isActive) return@count false
        val progress = calculateSavingsGoalProgress(goal, savingsContributions, today)
        val targetDate = goal.targetDateEpochDay?.let(LocalDate::ofEpochDay)
        !progress.isComplete && targetDate != null && !targetDate.isAfter(today.plusDays(30))
    }
    return PrivateDigestSummary(
        expenseCount = expenses.size,
        expenseTotalMinor = expenses.sumOf(TransactionRecord::amountMinor),
        needsReviewCount = transactions.count { it.reviewStatus == ReviewStatus.NEEDS_REVIEW },
        dueSoonCount = billCount + commitmentCount,
        goalsNeedingAttentionCount = goalsNeedingAttention,
    )
}

fun buildPrivateDigestText(
    summary: PrivateDigestSummary,
    configuration: NotificationDigestConfiguration,
): PrivateDigestText {
    val cadence = when (configuration.frequency) {
        NotificationDigestFrequency.DAILY -> "Daily"
        NotificationDigestFrequency.WEEKLY -> "Weekly"
    }
    val parts = buildList {
        if (summary.expenseCount > 0) {
            add(
                if (configuration.showAmounts) {
                    "${summary.expenseCount} expenses · ${formatRupees(summary.expenseTotalMinor)} spent"
                } else {
                    "${summary.expenseCount} expenses recorded"
                },
            )
        }
        if (summary.needsReviewCount > 0) add("${summary.needsReviewCount} need review")
        if (summary.dueSoonCount > 0) add("${summary.dueSoonCount} payments due soon")
        if (summary.goalsNeedingAttentionCount > 0) {
            add("${summary.goalsNeedingAttentionCount} goals need attention")
        }
    }
    return PrivateDigestText(
        title = "$cadence money digest",
        text = parts.joinToString(" • ").ifBlank { "Nothing new needs your attention" },
    )
}

private fun formatRupees(amountMinor: Long): String {
    val formatter = NumberFormat.getCurrencyInstance(Locale.forLanguageTag("en-IN")).apply {
        minimumFractionDigits = 0
        maximumFractionDigits = if (amountMinor % 100L == 0L) 0 else 2
    }
    return formatter.format(amountMinor / 100.0)
}
