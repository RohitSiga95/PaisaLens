package com.paisalens.app.data.importer

import com.paisalens.app.data.model.ExchangeRate
import com.paisalens.app.data.model.ReviewStatus
import com.paisalens.app.data.model.TransactionSource
import com.paisalens.app.data.model.TransactionType
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
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

    @Test
    fun findsSemicolonHeaderAfterStatementPreamble() {
        val csv = """
            Credit card statement;August 2026
            Generated locally;Do not reply
            Transaction Date;Description;Debit Amount;Credit Amount
            01/08/2026;Corner Cafe;1250.50;
        """.trimIndent()

        val preview = StatementImporter.preview(
            ByteArrayInputStream(csv.toByteArray()),
            "card.csv",
            9,
            "Credit card 4242",
        )

        assertEquals(1, preview.rows.size)
        assertEquals(4, preview.rows.single().rowNumber)
        assertEquals(125_050L, preview.rows.single().transaction.amountMinor)
    }

    @Test
    fun ignoresOutOfRangeXlsxColumnsWithoutAllocatingSparseRows() {
        val sheetXml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
              <sheetData>
                <row r="1">
                  <c r="A1" t="inlineStr"><is><t>Date</t></is></c>
                  <c r="B1" t="inlineStr"><is><t>Description</t></is></c>
                  <c r="C1" t="inlineStr"><is><t>Debit</t></is></c>
                  <c r="ZZZZZZ1" t="inlineStr"><is><t>Ignored</t></is></c>
                </row>
                <row r="2">
                  <c r="A2" t="inlineStr"><is><t>01/08/2026</t></is></c>
                  <c r="B2" t="inlineStr"><is><t>Book Store</t></is></c>
                  <c r="C2"><v>1250.50</v></c>
                </row>
              </sheetData>
            </worksheet>
        """.trimIndent()
        val workbook = ByteArrayOutputStream().also { output ->
            ZipOutputStream(output).use { zip ->
                zip.putNextEntry(ZipEntry("xl/worksheets/sheet1.xml"))
                zip.write(sheetXml.toByteArray())
                zip.closeEntry()
            }
        }.toByteArray()

        val preview = StatementImporter.preview(ByteArrayInputStream(workbook), "card.xlsx", 9, "Credit card 4242")

        assertEquals(1, preview.rows.size)
        assertEquals(125_050L, preview.rows.single().transaction.amountMinor)
        assertTrue(preview.warnings.contains(StatementTableLimits.COLUMN_LIMIT_WARNING))
    }
}
