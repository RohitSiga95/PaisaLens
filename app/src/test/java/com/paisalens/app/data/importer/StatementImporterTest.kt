package com.paisalens.app.data.importer

import com.paisalens.app.data.model.ExchangeRate
import com.paisalens.app.data.model.ReviewStatus
import com.paisalens.app.data.model.TransactionSource
import com.paisalens.app.data.model.TransactionType
import java.io.ByteArrayInputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StatementImporterTest {
    @Test
    fun mapsCommonCsvDebitAndCreditColumns() {
        val csv = """
            Date,Narration,Debit,Credit
            01/08/2026,Corner Cafe,"1,250.50",
            02/08/2026,Salary,,50000
        """.trimIndent()

        val preview = StatementImporter.preview(ByteArrayInputStream(csv.toByteArray()), "bank.csv", 4, "Salary account")

        assertEquals(2, preview.rows.size)
        assertEquals(125_050L, preview.rows[0].transaction.amountMinor)
        assertEquals(TransactionType.EXPENSE, preview.rows[0].transaction.type)
        assertEquals(ReviewStatus.NEEDS_REVIEW, preview.rows[0].transaction.reviewStatus)
        assertEquals(TransactionType.INCOME, preview.rows[1].transaction.type)
        assertEquals(TransactionSource.STATEMENT, preview.rows[1].transaction.source)
    }

    @Test
    fun convertsForeignStatementAmountUsingCachedReferenceRate() {
        val csv = "Date,Description,Amount,Currency\n2026-08-01,Hotel,100,USD"
        val preview = StatementImporter.preview(
            ByteArrayInputStream(csv.toByteArray()),
            "travel.csv",
            null,
            null,
            baseCurrency = "INR",
            exchangeRates = listOf(ExchangeRate("INR", "USD", 84.5, "2026-08-01", 1)),
        )

        val transaction = preview.rows.single().transaction
        assertEquals(845_000L, transaction.amountMinor)
        assertEquals(10_000L, transaction.originalAmountMinor)
        assertEquals("USD", transaction.originalCurrency)
        assertTrue(transaction.sourceMessageId.startsWith("statement-"))
    }
}
