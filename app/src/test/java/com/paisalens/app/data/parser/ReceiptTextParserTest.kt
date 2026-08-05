package com.paisalens.app.data.parser

import com.paisalens.app.data.model.ExpenseCategory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReceiptTextParserTest {
    private val parser = ReceiptTextParser()

    @Test
    fun extractsMerchantGrandTotalAndCategory() {
        val draft = parser.parse(
            text = """
                Fresh Mart
                GSTIN 29ABCDE1234F1Z5
                Groceries
                Subtotal Rs 1,100.00
                Tax Rs 55.00
                Grand Total ₹1,155.00
            """.trimIndent(),
            sourceLabel = "camera",
        )

        assertEquals("Fresh Mart", draft.merchant)
        assertEquals(115_500L, draft.amountMinor)
        assertEquals(ExpenseCategory.GROCERIES, draft.category)
        assertTrue(draft.note.startsWith("Scanned bill"))
    }

    @Test
    fun fallsBackToLargestReasonableAmount() {
        val draft = parser.parse(
            text = """
                Corner Cafe
                Coffee 180.00
                Sandwich 240.00
                ₹420.00
            """.trimIndent(),
            sourceLabel = "uploaded bill",
        )

        assertEquals("Corner Cafe", draft.merchant)
        assertEquals(42_000L, draft.amountMinor)
        assertEquals(ExpenseCategory.FOOD, draft.category)
    }
}
