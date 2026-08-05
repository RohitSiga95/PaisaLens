package com.paisalens.app.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MerchantGroupingTest {
    @Test
    fun normalizesMerchantFormattingForMatching() {
        assertEquals(
            normalizedMerchantKey("  ACME-Foods! "),
            normalizedMerchantKey("acme foods"),
        )
    }

    @Test
    fun groupsOnlyUncategorizedExpensesByMerchant() {
        val transactions = listOf(
            transaction(merchant = "Cafe Aroma", amountMinor = 45000),
            transaction(merchant = "cafe-aroma", amountMinor = 30000),
            transaction(
                merchant = "Cafe Aroma",
                amountMinor = 10000,
                category = ExpenseCategory.FOOD,
            ),
            transaction(
                merchant = "Cafe Aroma",
                amountMinor = 5000,
                type = TransactionType.REFUND,
            ),
        )

        val groups = findUncategorizedMerchantGroups(transactions, emptySet())

        assertEquals(1, groups.size)
        assertEquals(2, groups.single().transactionCount)
        assertEquals(75_000L, groups.single().totalMinor)
    }

    @Test
    fun hidesMerchantsWhoseCategoryWasAlreadyConfirmed() {
        val merchantKey = normalizedMerchantKey("Cafe Aroma")

        val groups = findUncategorizedMerchantGroups(
            transactions = listOf(transaction(merchant = "Cafe Aroma", amountMinor = 45000)),
            categorizedMerchantKeys = setOf(merchantKey),
        )

        assertTrue(groups.isEmpty())
    }

    private fun transaction(
        merchant: String,
        amountMinor: Long,
        category: ExpenseCategory = ExpenseCategory.OTHER,
        type: TransactionType = TransactionType.EXPENSE,
    ) = TransactionRecord(
        sourceMessageId = "test-$merchant-$amountMinor-$type",
        amountMinor = amountMinor,
        merchant = merchant,
        accountHint = null,
        category = category,
        type = type,
        occurredAt = 0,
        source = TransactionSource.MANUAL,
        sender = "Test",
    )
}
