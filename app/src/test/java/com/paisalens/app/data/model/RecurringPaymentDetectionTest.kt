package com.paisalens.app.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RecurringPaymentDetectionTest {
    private val day = 24L * 60L * 60L * 1000L

    @Test
    fun detectsConsistentMonthlyMerchantPayments() {
        val now = 100L * day
        val recurring = detectRecurringPayments(
            transactions = listOf(
                expense("Netflix", 49900, 40L * day),
                expense("Netflix", 49900, 70L * day),
            ),
            now = now,
        )

        assertEquals(1, recurring.size)
        assertEquals(30, recurring.single().intervalDays)
        assertEquals(49900L, recurring.single().typicalAmountMinor)
        assertEquals(100L * day, recurring.single().nextDueAt)
    }

    @Test
    fun excludesUnconfirmedAndHighlyVariablePayments() {
        val now = 100L * day
        val recurring = detectRecurringPayments(
            transactions = listOf(
                expense("Store", 10000, 40L * day),
                expense("Store", 90000, 70L * day),
                expense("Unknown", 50000, 40L * day, ReviewStatus.NEEDS_REVIEW),
                expense("Unknown", 50000, 70L * day, ReviewStatus.NEEDS_REVIEW),
            ),
            now = now,
        )

        assertTrue(recurring.isEmpty())
    }

    @Test
    fun keepsSameMerchantCadencesSeparateAcrossMergedPhysicalMembers() {
        val recurring = detectRecurringPayments(
            transactions = listOf(
                expense("Stream Co", 49_900, 10L * day, physicalAccountId = 101),
                expense("Stream Co", 49_900, 40L * day, physicalAccountId = 101),
                expense("Stream Co", 79_900, 15L * day, physicalAccountId = 102),
                expense("Stream Co", 79_900, 45L * day, physicalAccountId = 102),
            ),
            now = 70L * day,
        )

        assertEquals(2, recurring.size)
        assertEquals(setOf(49_900L, 79_900L), recurring.mapTo(mutableSetOf(), RecurringPayment::typicalAmountMinor))
        assertEquals(setOf(101L, 102L), recurring.mapNotNullTo(mutableSetOf(), RecurringPayment::physicalAccountId))
        assertTrue(recurring.all { it.accountId == 1L && it.occurrences == 2 })
    }

    private fun expense(
        merchant: String,
        amountMinor: Long,
        occurredAt: Long,
        reviewStatus: ReviewStatus = ReviewStatus.CONFIRMED,
        physicalAccountId: Long? = null,
    ) = TransactionRecord(
        sourceMessageId = "$merchant-$occurredAt",
        amountMinor = amountMinor,
        merchant = merchant,
        accountHint = "1234",
        category = ExpenseCategory.ENTERTAINMENT,
        type = TransactionType.EXPENSE,
        occurredAt = occurredAt,
        source = TransactionSource.CARD,
        sender = "Test",
        accountId = 1,
        accountName = "Card •1234",
        reviewStatus = reviewStatus,
        physicalAccountId = physicalAccountId,
    )
}
