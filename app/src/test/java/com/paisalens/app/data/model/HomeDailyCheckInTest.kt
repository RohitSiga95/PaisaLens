package com.paisalens.app.data.model

import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeDailyCheckInTest {
    private val today = LocalDate.of(2026, 11, 23)
    private val zone = ZoneId.of("Asia/Kolkata")
    private val now = today.atTime(12, 0).atZone(zone)
    private val morning = today.atStartOfDay(zone).toInstant().toEpochMilli()

    @Test
    fun `first visit uses local midnight and totals only the bills and spending shown in its details`() {
        val confirmed = transaction(1, morning + 1, 250_00)
        val review = transaction(2, morning + 2, 99_00).copy(reviewStatus = ReviewStatus.NEEDS_REVIEW)
        val previousDay = transaction(3, morning - 1, 999_00)
        val pending = listOf(
            timelineItem("today", 0, HomeTimelineSource.CREDIT_CARD_BILL),
            timelineItem("last-day", 6, HomeTimelineSource.BILL),
            timelineItem("too-late", 7, HomeTimelineSource.BILL),
            timelineItem("overdue", -1, HomeTimelineSource.BILL),
            timelineItem("expected-income", 1, HomeTimelineSource.EXPECTED_INCOME).copy(isIncoming = true),
            timelineItem("estimated-expense", 2, HomeTimelineSource.RECURRING_PAYMENT),
        )
        val snapshot = buildHomeDailyCheckIn(
            previousHomeVisitAt = null,
            now = now,
            transactions = listOf(confirmed, review, previousDay),
            effectiveExpenses = listOf(confirmed, review, previousDay),
            timeline = timeline(pending),
            paymentReviewCount = 2,
        )
        assertFalse(snapshot.hasPreviousVisit)
        assertEquals(listOf(confirmed), snapshot.spending)
        assertEquals(250_00L, snapshot.spendingMinor)
        assertEquals(listOf("today", "last-day"), snapshot.dueBills.map(HomeTimelineItem::stableId))
        assertEquals(2_000_00L, snapshot.dueMinor)
        assertEquals(3, snapshot.reviewCount)
    }

    @Test
    fun `return visit spans midnight and omits transfers and future records`() {
        val priorVisit = morning - 60 * 60_000L
        val beforeVisit = transaction(1, priorVisit - 1, 100)
        val afterVisit = transaction(2, priorVisit + 1, 200)
        val transfer = transaction(3, morning + 1, 300).copy(type = TransactionType.TRANSFER)
        val future = transaction(4, now.toInstant().toEpochMilli() + 1, 400)
        val expenses = listOf(beforeVisit, afterVisit, transfer, future)
        val snapshot = buildHomeDailyCheckIn(priorVisit, now, expenses, expenses, timeline(emptyList()), 0)
        assertTrue(snapshot.hasPreviousVisit)
        assertEquals(listOf(afterVisit), snapshot.spending)
        assertEquals(200L, snapshot.spendingMinor)
    }

    private fun transaction(id: Long, occurredAt: Long, amount: Long) = TransactionRecord(
        id = id,
        sourceMessageId = "sample-$id",
        amountMinor = amount,
        merchant = "Sample shop",
        accountHint = null,
        category = ExpenseCategory.OTHER,
        type = TransactionType.EXPENSE,
        occurredAt = occurredAt,
        source = TransactionSource.CARD,
        sender = "",
    )

    private fun timelineItem(id: String, offset: Long, source: HomeTimelineSource) = HomeTimelineItem(
        stableId = id, title = id, amountMinor = 100_000L, date = today.plusDays(offset), source = source,
    )

    private fun timeline(items: List<HomeTimelineItem>) = HomeMoneyTimeline(today, today.plusDays(13), items, 0, 0)
}
