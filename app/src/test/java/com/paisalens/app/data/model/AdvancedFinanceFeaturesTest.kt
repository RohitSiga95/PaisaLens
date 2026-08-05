package com.paisalens.app.data.model

import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.YearMonth
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AdvancedFinanceFeaturesTest {
    private val utc: ZoneId = ZoneOffset.UTC

    @Test
    fun calculatesStandardReducingBalanceEmi() {
        val emi = calculateEmiMinor(
            principalMinor = 10_000_000,
            annualRateBasisPoints = 1_200,
            tenureMonths = 12,
        )

        assertTrue(emi in 888_000..889_000)
    }

    @Test
    fun analyticsExcludeReviewInboxAndBuildSixMonthTrend() {
        val today = LocalDate.of(2026, 8, 5)
        val analytics = buildSpendingAnalytics(
            transactions = listOf(
                expense(1, "Grocer", 50_000, LocalDate.of(2026, 8, 2)),
                expense(2, "Uncertain", 90_000, LocalDate.of(2026, 8, 3), ReviewStatus.NEEDS_REVIEW),
                expense(3, "Grocer", 40_000, LocalDate.of(2026, 7, 2)),
            ),
            today = today,
            zoneId = utc,
        )

        assertEquals(50_000L, analytics.currentMonthMinor)
        assertEquals(40_000L, analytics.previousMonthMinor)
        assertEquals(6, analytics.monthlyTrend.size)
        assertEquals("Grocer", analytics.topMerchants.single().label)
    }

    @Test
    fun detectsPossibleDuplicateEntirelyOnDevice() {
        val first = expense(1, "Coffee Shop", 35_000, LocalDate.of(2026, 8, 2)).copy(occurredAt = 1_000_000)
        val second = expense(2, "Coffee Shop", 35_000, LocalDate.of(2026, 8, 2)).copy(occurredAt = 1_300_000)

        val insights = buildOnDeviceInsights(listOf(first, second), emptyList(), LocalDate.of(2026, 8, 5), utc)

        assertTrue(insights.any { it.kind == InsightKind.DUPLICATE && it.transactionId == 2L })
    }

    @Test
    fun calendarGroupsConfirmedExpensesByDay() {
        val rows = listOf(
            expense(1, "Cafe", 10_000, LocalDate.of(2026, 8, 4)),
            expense(2, "Cab", 20_000, LocalDate.of(2026, 8, 4)),
        )

        val calendar = buildCalendarSpend(rows, YearMonth.of(2026, 8), utc)

        assertEquals(30_000L, calendar.getValue(LocalDate.of(2026, 8, 4)).amountMinor)
        assertEquals(2, calendar.getValue(LocalDate.of(2026, 8, 4)).transactions.size)
    }

    private fun expense(
        id: Long,
        merchant: String,
        amountMinor: Long,
        date: LocalDate,
        status: ReviewStatus = ReviewStatus.CONFIRMED,
    ) = TransactionRecord(
        id = id,
        sourceMessageId = "test-$id",
        amountMinor = amountMinor,
        merchant = merchant,
        accountHint = null,
        category = ExpenseCategory.FOOD,
        type = TransactionType.EXPENSE,
        occurredAt = date.atStartOfDay(utc).toInstant().toEpochMilli(),
        source = TransactionSource.MANUAL,
        sender = "Test",
        reviewStatus = status,
    )
}
