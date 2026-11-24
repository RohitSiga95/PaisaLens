package com.paisalens.app.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CategoryBreakdownModelsTest {
    @Test
    fun `custom categories remain separate from built in other and from each other`() {
        val rows = listOf(
            expense(1, 1_000, ExpenseCategory.OTHER),
            expense(2, 2_000, ExpenseCategory.OTHER, customId = 41, customName = "Pets"),
            expense(3, 3_000, ExpenseCategory.OTHER, customId = 42, customName = "Gifts"),
            expense(4, 500, ExpenseCategory.OTHER, customId = 41, customName = "Pets"),
        )

        val totals = buildSpendingCategoryTotals(rows)

        assertEquals(listOf("Gifts", "Pets", "Other"), totals.map { it.key.label })
        assertEquals(listOf(3_000L, 2_500L, 1_000L), totals.map { it.amountMinor })
        assertTrue(totals.first { it.key.label == "Pets" }.key.matches(rows.last()))
        assertTrue(totals.first { it.key.label == "Other" }.key.matches(rows.first()))
    }

    @Test
    fun `custom category id is stable across stale row labels`() {
        val totals = buildSpendingCategoryTotals(
            listOf(
                expense(1, 600, ExpenseCategory.OTHER, customId = 7, customName = "Old name"),
                expense(2, 400, ExpenseCategory.OTHER, customId = 7, customName = "New name"),
            ),
        )

        assertEquals(1, totals.size)
        assertEquals(1_000L, totals.single().amountMinor)
        assertEquals(7L, totals.single().key.customCategoryId)
    }

    @Test
    fun `new custom categories are visible and current labels win after sync`() {
        val custom = listOf(CustomCategory(id = 7, name = "Meals"), CustomCategory(id = 8, name = "Pet care"))
        val totals = buildSpendingCategoryTotals(
            listOf(
                expense(1, 600, ExpenseCategory.OTHER, customId = 7, customName = "Old name"),
                expense(2, 400, ExpenseCategory.FOOD, customId = 7, customName = "Older name"),
            ),
            custom,
        )
        assertEquals(listOf("Meals", "Pet care"), totals.map { it.key.label })
        assertEquals(listOf(1_000L, 0L), totals.map { it.amountMinor })
    }

    private fun expense(
        id: Long,
        amountMinor: Long,
        category: ExpenseCategory,
        customId: Long? = null,
        customName: String? = null,
    ) = TransactionRecord(
        id = id,
        sourceMessageId = "test-$id",
        amountMinor = amountMinor,
        merchant = "Merchant $id",
        accountHint = null,
        category = category,
        type = TransactionType.EXPENSE,
        occurredAt = id,
        source = TransactionSource.MANUAL,
        sender = "TEST",
        reviewStatus = ReviewStatus.CONFIRMED,
        customCategoryId = customId,
        customCategoryName = customName,
    )
}
