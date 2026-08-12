package com.paisalens.app.data.model

import org.junit.Assert.assertEquals
import org.junit.Test

class SmartCategoryRuleEvaluationTest {
    @Test
    fun previewOnlyIncludesRowsWherePendingRuleWins() {
        val highPriority = rule(id = 1, pattern = "amazon", priority = 20, category = ExpenseCategory.SHOPPING)
        val pending = rule(id = 2, pattern = "amazon", priority = 10, category = ExpenseCategory.FOOD)
        val transactions = listOf(expense(1, "Amazon"), expense(2, "Cafe"))

        val preview = previewSmartCategoryRuleApplication(
            rule = pending,
            transactions = transactions,
            savedRules = listOf(highPriority, pending),
            exactMerchantKeys = emptySet(),
        )

        assertEquals(0, preview.matchedCount)
    }

    @Test
    fun previewTreatsEditedRuleAsNewestTieAndHonorsExactMappings() {
        val existing = rule(id = 1, pattern = "amazon", priority = 10, category = ExpenseCategory.SHOPPING)
        val edited = rule(id = 2, pattern = "amazon", priority = 10, category = ExpenseCategory.FOOD)
        val transactions = listOf(expense(1, "Amazon"), expense(2, "Amazon Fresh"))

        val preview = previewSmartCategoryRuleApplication(
            rule = edited,
            transactions = transactions,
            savedRules = listOf(existing, edited),
            exactMerchantKeys = setOf(normalizedMerchantKey("Amazon")),
        )

        assertEquals(listOf(2L), preview.matchedTransactionIds)
        assertEquals(1, preview.matchedCount)
        assertEquals(10_000L, preview.totalAmountMinor)
    }

    @Test
    fun previewIsEmptyWhenPendingRuleWillBeSavedDisabled() {
        val disabled = rule(id = 3, pattern = "cafe", priority = 10, category = ExpenseCategory.FOOD)
            .copy(enabled = false)

        val preview = previewSmartCategoryRuleApplication(
            rule = disabled,
            transactions = listOf(expense(1, "Cafe")),
            savedRules = emptyList(),
            exactMerchantKeys = emptySet(),
        )

        assertEquals(0, preview.matchedCount)
    }

    private fun rule(
        id: Long,
        pattern: String,
        priority: Int,
        category: ExpenseCategory,
    ) = SmartCategoryRule(
        id = id,
        name = pattern,
        merchantPattern = pattern,
        matchType = SmartRuleMatchType.CONTAINS,
        category = category,
        priority = priority,
        updatedAt = id,
    )

    private fun expense(id: Long, merchant: String) = TransactionRecord(
        id = id,
        sourceMessageId = "test-$id",
        amountMinor = 10_000,
        merchant = merchant,
        accountHint = null,
        category = ExpenseCategory.OTHER,
        type = TransactionType.EXPENSE,
        occurredAt = id,
        source = TransactionSource.MANUAL,
        sender = "Test",
    )
}
