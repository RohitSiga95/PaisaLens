package com.paisalens.app.notification

import com.paisalens.app.data.model.BillReminder
import com.paisalens.app.data.model.ExpenseCategory
import com.paisalens.app.data.model.NotificationDigestConfiguration
import com.paisalens.app.data.model.NotificationDigestFrequency
import com.paisalens.app.data.model.PaymentCommitment
import com.paisalens.app.data.model.PaymentCommitmentStatus
import com.paisalens.app.data.model.ReviewStatus
import com.paisalens.app.data.model.SavingsContribution
import com.paisalens.app.data.model.SavingsGoal
import com.paisalens.app.data.model.TransactionRecord
import com.paisalens.app.data.model.TransactionSource
import com.paisalens.app.data.model.TransactionType
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PrivateDigestModelsTest {
    private val zone = ZoneId.of("Asia/Kolkata")
    private val now = ZonedDateTime.of(2026, 8, 12, 20, 0, 0, 0, zone)

    @Test
    fun `daily alarm advances to tomorrow when today's hour has passed`() {
        val result = nextDigestTriggerAt(
            NotificationDigestConfiguration(hour = 19),
            now,
        )

        assertEquals(now.toLocalDate().plusDays(1), result.toLocalDate())
        assertEquals(19, result.hour)
    }

    @Test
    fun `weekly alarm chooses requested weekday and always lies in the future`() {
        val result = nextDigestTriggerAt(
            NotificationDigestConfiguration(
                frequency = NotificationDigestFrequency.WEEKLY,
                hour = 9,
                weekday = DayOfWeek.MONDAY,
            ),
            now,
        )

        assertEquals(DayOfWeek.MONDAY, result.dayOfWeek)
        assertEquals(9, result.hour)
        assertTrue(result.isAfter(now))
    }

    @Test
    fun `summary includes only local recent records and useful attention counts`() {
        val today = now.toLocalDate()
        val nowMillis = now.toInstant().toEpochMilli()
        val recentExpense = transaction(
            id = 1,
            occurredAt = nowMillis - 60_000,
            amountMinor = 125_00,
        )
        val oldExpense = transaction(
            id = 2,
            occurredAt = nowMillis - 2 * 24 * 60 * 60 * 1_000L,
            amountMinor = 500_00,
        )
        val reviewItem = transaction(
            id = 3,
            occurredAt = nowMillis - 30 * 24 * 60 * 60 * 1_000L,
            amountMinor = 50_00,
            reviewStatus = ReviewStatus.NEEDS_REVIEW,
        )
        val goal = SavingsGoal(
            id = 4,
            name = "Emergency fund",
            targetMinor = 10_000_00,
            targetDateEpochDay = today.plusDays(15).toEpochDay(),
        )
        val summary = buildPrivateDigestSummary(
            transactions = listOf(recentExpense, oldExpense, reviewItem),
            bills = listOf(
                BillReminder(title = "Bill", amountMinor = 100_00, dueDateEpochDay = today.plusDays(2).toEpochDay()),
            ),
            savingsGoals = listOf(goal),
            savingsContributions = listOf(SavingsContribution(goalId = goal.id, amountMinor = 100_00)),
            paymentCommitments = listOf(
                PaymentCommitment(
                    name = "Subscription",
                    amountMinor = 50_00,
                    nextDueEpochDay = today.plusDays(4).toEpochDay(),
                ),
                PaymentCommitment(
                    name = "Paused",
                    amountMinor = 50_00,
                    nextDueEpochDay = today.plusDays(4).toEpochDay(),
                    status = PaymentCommitmentStatus.PAUSED,
                ),
            ),
            configuration = NotificationDigestConfiguration(),
            nowMillis = nowMillis,
            zoneId = zone,
        )

        assertEquals(1, summary.expenseCount)
        assertEquals(125_00, summary.expenseTotalMinor)
        assertEquals(1, summary.needsReviewCount)
        assertEquals(2, summary.dueSoonCount)
        assertEquals(1, summary.goalsNeedingAttentionCount)
    }

    @Test
    fun `digest conceals amounts by default and public text is always generic`() {
        val summary = PrivateDigestSummary(3, 987_65, 1, 2, 1)
        val privateByDefault = buildPrivateDigestText(summary, NotificationDigestConfiguration())
        val optedIn = buildPrivateDigestText(
            summary,
            NotificationDigestConfiguration(showAmounts = true),
        )

        assertFalse(privateByDefault.text.contains("987"))
        assertFalse(privateByDefault.text.contains("₹"))
        assertFalse(privateByDefault.publicText.contains("Private merchant"))
        assertFalse(privateByDefault.publicText.contains("1234"))
        assertFalse(privateByDefault.publicText.contains("987"))
        assertFalse(privateByDefault.publicText.contains("₹"))
        assertTrue(optedIn.text.contains("987"))
        assertEquals("PaisaLens", privateByDefault.publicTitle)
        assertEquals("Your private money digest is ready", privateByDefault.publicText)
    }

    private fun transaction(
        id: Long,
        occurredAt: Long,
        amountMinor: Long,
        reviewStatus: ReviewStatus = ReviewStatus.CONFIRMED,
    ) = TransactionRecord(
        id = id,
        sourceMessageId = "test-$id",
        amountMinor = amountMinor,
        merchant = "Private merchant",
        accountHint = "1234",
        category = ExpenseCategory.OTHER,
        type = TransactionType.EXPENSE,
        occurredAt = occurredAt,
        source = TransactionSource.BANK,
        sender = "BANK",
        reviewStatus = reviewStatus,
    )
}
