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

    private fun expense(
        merchant: String,
        amountMinor: Long,
        occurredAt: Long,
        reviewStatus: ReviewStatus = ReviewStatus.CONFIRMED,
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
    )
}
