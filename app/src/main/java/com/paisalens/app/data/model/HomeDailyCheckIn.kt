package com.paisalens.app.data.model

import java.time.Instant
import java.time.ZonedDateTime

data class HomeDailyCheckIn(
    val hasPreviousVisit: Boolean,
    val spending: List<TransactionRecord>,
    val dueBills: List<HomeTimelineItem>,
    val transactionReviewCount: Int,
    val paymentReviewCount: Int,
) {
    val spendingMinor: Long get() = spending.sumOf(TransactionRecord::amountMinor)
    val dueMinor: Long get() = dueBills.sumOf(HomeTimelineItem::amountMinor)
    val reviewCount: Int get() = transactionReviewCount + paymentReviewCount
}

fun buildHomeDailyCheckIn(
    previousHomeVisitAt: Long?,
    now: ZonedDateTime,
    transactions: List<TransactionRecord>,
    effectiveExpenses: List<TransactionRecord>,
    timeline: HomeMoneyTimeline,
    paymentReviewCount: Int,
): HomeDailyCheckIn {
    val today = now.toLocalDate()
    val end = now.toInstant().toEpochMilli()
    val previousVisit = previousHomeVisitAt?.takeIf { it in 1 until end }
    val start = previousVisit ?: today.atStartOfDay(now.zone).toInstant().toEpochMilli()
    return HomeDailyCheckIn(
        hasPreviousVisit = previousVisit != null,
        spending = effectiveExpenses.filter {
            it.occurredAt in start..end && it.type == TransactionType.EXPENSE &&
                it.reviewStatus == ReviewStatus.CONFIRMED && it.amountMinor > 0
        }.sortedByDescending(TransactionRecord::occurredAt),
        dueBills = timeline.items.filter {
            it.date >= today && it.date < today.plusDays(7) && !it.isIncoming &&
                it.source != HomeTimelineSource.RECURRING_PAYMENT && it.amountMinor > 0
        },
        transactionReviewCount = transactions.count { it.reviewStatus == ReviewStatus.NEEDS_REVIEW },
        paymentReviewCount = paymentReviewCount,
    )
}
