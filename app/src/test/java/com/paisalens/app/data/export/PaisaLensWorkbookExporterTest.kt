package com.paisalens.app.data.export

import com.paisalens.app.data.model.CategoryBudget
import com.paisalens.app.data.model.ExpenseCategory
import com.paisalens.app.data.model.TransactionRecord
import com.paisalens.app.data.model.TransactionSource
import com.paisalens.app.data.model.TransactionType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.zip.ZipInputStream

class PaisaLensWorkbookExporterTest {
    @Test
    fun createsFormattedWorkbookWithChartsAndAnalysisSheets() {
        val bytes = sampleWorkbook()
        val entries = unzip(bytes)

        assertTrue(entries.keys.containsAll(EXPECTED_ENTRIES))
        assertTrue(entries.getValue("xl/workbook.xml").contains("name=\"Dashboard\""))
        assertTrue(entries.getValue("xl/workbook.xml").contains("name=\"Transactions\""))
        assertTrue(entries.getValue("xl/worksheets/sheet1.xml").contains("<drawing r:id=\"rId1\"/>"))
        assertTrue(entries.getValue("xl/worksheets/sheet3.xml").contains("SUMIFS"))
        assertTrue(entries.getValue("xl/worksheets/sheet4.xml").contains("Budget Performance"))
        assertTrue(entries.getValue("xl/charts/chart1.xml").contains("<c:doughnutChart>"))
        assertTrue(entries.getValue("xl/charts/chart2.xml").contains("<c:lineChart>"))
    }

    @Test
    fun preservesTypedValuesAndEscapesUserNotes() {
        val entries = unzip(sampleWorkbook())
        val transactionsXml = entries.getValue("xl/worksheets/sheet2.xml")

        assertTrue(transactionsXml.contains("Lunch &amp; team &lt;meeting&gt;"))
        assertTrue(transactionsXml.contains("<v>-850</v>"))
        assertTrue(transactionsXml.contains("<v>7500</v>"))
        assertTrue(entries.getValue("xl/styles.xml").contains("₹#,##0.00"))
    }

    @Test
    fun writesWorkbookThatCanBeUsedForVisualVerification() {
        val outputDirectory = File("build/test-exports").apply { mkdirs() }
        val output = File(outputDirectory, "PaisaLens-sample.xlsx")
        output.writeBytes(sampleWorkbook())

        assertTrue(output.isFile)
        assertTrue(output.length() > 10_000L)
    }

    @Test
    fun createsUsableWorkbookWhenLedgerIsEmpty() {
        val bytes = ByteArrayOutputStream().use { output ->
            PaisaLensWorkbookExporter.write(
                transactions = emptyList(),
                budgets = emptyList(),
                outputStream = output,
                generatedAt = ZonedDateTime.parse("2026-08-05T12:00:00+05:30[Asia/Kolkata]")
                    .toInstant()
                    .toEpochMilli(),
                zoneId = ZoneId.of("Asia/Kolkata"),
            )
            output.toByteArray()
        }
        val entries = unzip(bytes)

        assertTrue(entries.getValue("xl/worksheets/sheet1.xml").contains("No expenses"))
        assertTrue(entries.getValue("xl/worksheets/sheet2.xml").contains("Date &amp; time"))
        assertEquals(4, Regex("<sheet name=").findAll(entries.getValue("xl/workbook.xml")).count())
    }

    private fun sampleWorkbook(): ByteArray {
        val zone = ZoneId.of("Asia/Kolkata")
        val transactions = listOf(
            transaction(
                id = 1,
                date = "2026-05-08T10:30:00+05:30[Asia/Kolkata]",
                amountMinor = 350_000,
                merchant = "Urban Homes",
                category = ExpenseCategory.BILLS,
                note = "May apartment rent",
            ),
            transaction(
                id = 2,
                date = "2026-06-12T13:15:00+05:30[Asia/Kolkata]",
                amountMinor = 85_000,
                merchant = "Cafe Aroma",
                category = ExpenseCategory.FOOD,
                note = "Lunch & team <meeting>",
            ),
            transaction(
                id = 3,
                date = "2026-06-18T18:00:00+05:30[Asia/Kolkata]",
                amountMinor = 125_000,
                merchant = "Fresh Basket",
                category = ExpenseCategory.GROCERIES,
            ),
            transaction(
                id = 4,
                date = "2026-07-02T09:00:00+05:30[Asia/Kolkata]",
                amountMinor = 42_000,
                merchant = "Metro Rail",
                category = ExpenseCategory.TRANSPORT,
            ),
            transaction(
                id = 5,
                date = "2026-07-31T12:00:00+05:30[Asia/Kolkata]",
                amountMinor = 750_000,
                merchant = "Acme Payroll",
                category = ExpenseCategory.INCOME,
                type = TransactionType.INCOME,
            ),
            transaction(
                id = 6,
                date = "2026-08-03T20:20:00+05:30[Asia/Kolkata]",
                amountMinor = 68_000,
                merchant = "Cinema House",
                category = ExpenseCategory.ENTERTAINMENT,
            ),
        )
        val budgets = listOf(
            CategoryBudget(ExpenseCategory.FOOD, 200_000),
            CategoryBudget(ExpenseCategory.GROCERIES, 300_000),
            CategoryBudget(ExpenseCategory.ENTERTAINMENT, 100_000),
        )
        return ByteArrayOutputStream().use { output ->
            PaisaLensWorkbookExporter.write(
                transactions = transactions,
                budgets = budgets,
                outputStream = output,
                generatedAt = ZonedDateTime.parse("2026-08-05T12:00:00+05:30[Asia/Kolkata]")
                    .toInstant()
                    .toEpochMilli(),
                zoneId = zone,
            )
            output.toByteArray()
        }
    }

    private fun transaction(
        id: Long,
        date: String,
        amountMinor: Long,
        merchant: String,
        category: ExpenseCategory,
        type: TransactionType = TransactionType.EXPENSE,
        note: String? = null,
    ) = TransactionRecord(
        id = id,
        sourceMessageId = "export-$id",
        amountMinor = amountMinor,
        merchant = merchant,
        accountHint = "1234",
        category = category,
        type = type,
        occurredAt = ZonedDateTime.parse(date).toInstant().toEpochMilli(),
        source = TransactionSource.BANK,
        sender = "BANK",
        note = note,
    )

    private fun unzip(bytes: ByteArray): Map<String, String> {
        val entries = linkedMapOf<String, String>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                entries[entry.name] = zip.readBytes().toString(Charsets.UTF_8)
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }
        return entries
    }

    private companion object {
        val EXPECTED_ENTRIES = setOf(
            "[Content_Types].xml",
            "_rels/.rels",
            "xl/workbook.xml",
            "xl/styles.xml",
            "xl/worksheets/sheet1.xml",
            "xl/worksheets/sheet2.xml",
            "xl/worksheets/sheet3.xml",
            "xl/worksheets/sheet4.xml",
            "xl/drawings/drawing1.xml",
            "xl/charts/chart1.xml",
            "xl/charts/chart2.xml",
        )
    }
}
