package com.paisalens.app.data.export

import com.paisalens.app.data.model.CategoryBudget
import com.paisalens.app.data.model.ContributionFrequency
import com.paisalens.app.data.model.ExpenseCategory
import com.paisalens.app.data.model.ExpenseSplit
import com.paisalens.app.data.model.PaymentCommitment
import com.paisalens.app.data.model.PaymentCommitmentKind
import com.paisalens.app.data.model.PaymentCommitmentSource
import com.paisalens.app.data.model.PaymentFrequency
import com.paisalens.app.data.model.SavingsContribution
import com.paisalens.app.data.model.SavingsGoal
import com.paisalens.app.data.model.SavingsGoalKind
import com.paisalens.app.data.model.TransactionRecord
import com.paisalens.app.data.model.TransactionLink
import com.paisalens.app.data.model.TransactionLinkType
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
        assertTrue(entries.getValue("xl/workbook.xml").contains("name=\"Shared Expenses\""))
        assertTrue(entries.getValue("xl/workbook.xml").contains("name=\"Savings Goals\""))
        assertTrue(entries.getValue("xl/workbook.xml").contains("name=\"Goal Contributions\""))
        assertTrue(entries.getValue("xl/workbook.xml").contains("name=\"Subscriptions and AutoPay\""))
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
        assertEquals(8, Regex("<sheet name=").findAll(entries.getValue("xl/workbook.xml")).count())
    }

    @Test
    fun exportsSharedFinanceGoalsContributionsAndAutopayDetails() {
        val entries = unzip(sampleWorkbook())
        val sharedExpensesXml = entries.getValue("xl/worksheets/sheet5.xml")
        val savingsGoalsXml = entries.getValue("xl/worksheets/sheet6.xml")
        val contributionsXml = entries.getValue("xl/worksheets/sheet7.xml")
        val commitmentsXml = entries.getValue("xl/worksheets/sheet8.xml")

        assertTrue(sharedExpensesXml.contains("Shared Expenses &amp; Reimbursements"))
        assertTrue(sharedExpensesXml.contains("Mira"))
        assertTrue(sharedExpensesXml.contains("Team lunch"))
        assertTrue(sharedExpensesXml.contains("<f>MAX(F10-G10,0)</f><v>250</v>"))
        assertTrue(sharedExpensesXml.contains("<pane ySplit=\"9\""))
        assertTrue(sharedExpensesXml.contains("<autoFilter ref=\"A9:L10\"/>"))

        assertTrue(savingsGoalsXml.contains("Emergency reserve"))
        assertTrue(savingsGoalsXml.contains("Sinking fund"))
        assertTrue(savingsGoalsXml.contains("<f>IFERROR(MIN(E10/D10,1),1)</f><v>0.125</v>"))
        assertTrue(savingsGoalsXml.contains("<autoFilter ref=\"A9:N10\"/>"))

        assertTrue(contributionsXml.contains("Initial transfer"))
        assertTrue(Regex("<c r=\"D6\"[^>]*><v>500</v></c>").containsMatchIn(contributionsXml))
        assertTrue(contributionsXml.contains("<pane ySplit=\"5\""))

        assertTrue(commitmentsXml.contains("StreamFlix"))
        assertTrue(commitmentsXml.contains("Subscription"))
        assertTrue(commitmentsXml.contains("On-device suggestion"))
        assertTrue(commitmentsXml.contains("<f>MAX(H10-G10,0)</f><v>100</v>"))
        assertTrue(commitmentsXml.contains("<autoFilter ref=\"A9:O10\"/>"))
        assertTrue(entries.getValue("xl/styles.xml").contains("formatCode=\"dd mmm yyyy\""))
    }

    @Test
    fun usesLinkedRefundsForAnalysisWhileKeepingRecordedRows() {
        val expense = transaction(
            id = 21,
            date = "2026-08-03T10:00:00+05:30[Asia/Kolkata]",
            amountMinor = 12_345,
            merchant = "Travel Desk",
            category = ExpenseCategory.TRAVEL,
        )
        val refund = transaction(
            id = 22,
            date = "2026-08-04T10:00:00+05:30[Asia/Kolkata]",
            amountMinor = 2_345,
            merchant = "Travel Desk",
            category = ExpenseCategory.TRAVEL,
            type = TransactionType.REFUND,
        )
        val bytes = ByteArrayOutputStream().use { output ->
            PaisaLensWorkbookExporter.write(
                transactions = listOf(expense, refund),
                budgets = emptyList(),
                outputStream = output,
                generatedAt = ZonedDateTime.parse("2026-08-05T12:00:00+05:30[Asia/Kolkata]")
                    .toInstant()
                    .toEpochMilli(),
                zoneId = ZoneId.of("Asia/Kolkata"),
                transactionLinks = listOf(
                    TransactionLink(
                        id = 1,
                        sourceTransactionId = expense.id,
                        targetTransactionId = refund.id,
                        type = TransactionLinkType.REFUND,
                    ),
                ),
            )
            output.toByteArray()
        }

        val transactionsXml = unzip(bytes).getValue("xl/worksheets/sheet2.xml")
        assertTrue(transactionsXml.contains("Expense reduced by a linked refund"))
        assertTrue(transactionsXml.contains("Applied against a linked expense"))
        assertTrue(Regex("<c r=\"O2\"[^>]*><v>0</v></c>").containsMatchIn(transactionsXml))
        assertTrue(Regex("<c r=\"O3\"[^>]*><v>-100</v></c>").containsMatchIn(transactionsXml))
    }

    @Test
    fun keepsDashboardAndBudgetCachesAlignedWithEffectiveReimbursementSpend() {
        val expense = transaction(
            id = 21,
            date = "2026-08-03T10:00:00+05:30[Asia/Kolkata]",
            amountMinor = 12_345,
            merchant = "Travel Desk",
            category = ExpenseCategory.TRAVEL,
        )
        val linkedReimbursement = transaction(
            id = 22,
            date = "2026-08-04T10:00:00+05:30[Asia/Kolkata]",
            amountMinor = 2_345,
            merchant = "Mira reimbursement",
            category = ExpenseCategory.INCOME,
            type = TransactionType.INCOME,
        )
        val regularIncome = transaction(
            id = 23,
            date = "2026-08-05T10:00:00+05:30[Asia/Kolkata]",
            amountMinor = 7_500,
            merchant = "Interest credit",
            category = ExpenseCategory.INCOME,
            type = TransactionType.INCOME,
        )
        val bytes = ByteArrayOutputStream().use { output ->
            PaisaLensWorkbookExporter.write(
                transactions = listOf(expense, linkedReimbursement, regularIncome),
                budgets = listOf(CategoryBudget(ExpenseCategory.TRAVEL, 20_000)),
                outputStream = output,
                generatedAt = ZonedDateTime.parse("2026-08-05T12:00:00+05:30[Asia/Kolkata]")
                    .toInstant()
                    .toEpochMilli(),
                zoneId = ZoneId.of("Asia/Kolkata"),
                transactionLinks = listOf(
                    TransactionLink(
                        id = 1,
                        sourceTransactionId = expense.id,
                        targetTransactionId = linkedReimbursement.id,
                        type = TransactionLinkType.REIMBURSEMENT,
                    ),
                ),
                expenseSplits = listOf(
                    ExpenseSplit(
                        id = 1,
                        transactionId = expense.id,
                        participantName = "Mira",
                        shareMinor = 5_000,
                        reimbursedMinor = 3_000,
                        linkedIncomingTransactionId = linkedReimbursement.id,
                    ),
                ),
            )
            output.toByteArray()
        }
        val entries = unzip(bytes)
        val dashboardXml = entries.getValue("xl/worksheets/sheet1.xml")
        val transactionsXml = entries.getValue("xl/worksheets/sheet2.xml")
        val budgetsXml = entries.getValue("xl/worksheets/sheet4.xml")

        val netSpendCell = cellXml(dashboardXml, "F12")
        assertTrue(netSpendCell.contains("\$O\$2:\$O\$4"))
        assertTrue(!netSpendCell.contains("\$F\$2:\$F\$4"))
        assertTrue(netSpendCell.contains("MAX(0,"))
        assertTrue(netSpendCell.contains("<v>93.45</v>"))

        val incomeCell = cellXml(dashboardXml, "G12")
        assertTrue(incomeCell.contains("\$O\$2:\$O\$4"))
        assertTrue(!incomeCell.contains("\$F\$2:\$F\$4"))
        assertTrue(incomeCell.contains("<v>75</v>"))

        assertTrue(cellXml(transactionsXml, "O2").contains("<v>75</v>"))
        assertTrue(cellXml(transactionsXml, "O3").contains("<v>0</v>"))
        assertTrue(cellXml(transactionsXml, "O4").contains("<v>-93.45</v>"))

        val travelBudgetRow = CATEGORY_START_ROW_FOR_TESTS +
            ExpenseCategory.entries.filterNot { it == ExpenseCategory.INCOME }.indexOf(ExpenseCategory.TRAVEL)
        val budgetSpendCell = cellXml(budgetsXml, "C$travelBudgetRow")
        assertTrue(budgetSpendCell.contains("\$O\$2:\$O\$4"))
        assertTrue(budgetSpendCell.contains("<v>93.45</v>"))
    }

    @Test
    fun countsEveryRepeatingCommitmentOccurrenceInThirtyDayKpi() {
        val weekly = PaymentCommitment(
            id = 701,
            name = "Weekly pass",
            merchantKey = "weekly-pass",
            frequency = PaymentFrequency.WEEKLY,
            amountMinor = 1_000,
            nextDueEpochDay = java.time.LocalDate.parse("2026-08-05").toEpochDay(),
        )
        val entries = unzip(
            ByteArrayOutputStream().use { output ->
                PaisaLensWorkbookExporter.write(
                    transactions = emptyList(),
                    budgets = emptyList(),
                    outputStream = output,
                    generatedAt = ZonedDateTime.parse("2026-08-05T12:00:00+05:30[Asia/Kolkata]")
                        .toInstant()
                        .toEpochMilli(),
                    zoneId = ZoneId.of("Asia/Kolkata"),
                    paymentCommitments = listOf(weekly),
                )
                output.toByteArray()
            },
        )

        val commitmentsXml = entries.getValue("xl/worksheets/sheet8.xml")
        assertTrue(commitmentsXml.contains("DUE NEXT 30 DAYS"))
        // 5 Aug, 12 Aug, 19 Aug, 26 Aug, and 2 Sep fall in the inclusive 30-day view.
        assertTrue(cellXml(commitmentsXml, "G6").contains("<v>50</v>"))
    }

    @Test
    fun savingsGoalHeadlineTotalsIncludeOnlyActiveGoalsButRowsKeepArchivedGoals() {
        val activeGoal = SavingsGoal(
            id = 801,
            name = "Active reserve",
            targetMinor = 100_000,
            startingSavedMinor = 20_000,
        )
        val archivedGoal = SavingsGoal(
            id = 802,
            name = "Archived holiday",
            targetMinor = 900_000,
            startingSavedMinor = 800_000,
            isActive = false,
        )
        val entries = unzip(
            ByteArrayOutputStream().use { output ->
                PaisaLensWorkbookExporter.write(
                    transactions = emptyList(),
                    budgets = emptyList(),
                    outputStream = output,
                    generatedAt = ZonedDateTime.parse("2026-08-05T12:00:00+05:30[Asia/Kolkata]")
                        .toInstant()
                        .toEpochMilli(),
                    zoneId = ZoneId.of("Asia/Kolkata"),
                    savingsGoals = listOf(activeGoal, archivedGoal),
                )
                output.toByteArray()
            },
        )

        val goalsXml = entries.getValue("xl/worksheets/sheet6.xml")
        assertTrue(cellXml(goalsXml, "A6").contains("<v>1000</v>"))
        assertTrue(cellXml(goalsXml, "D6").contains("<v>200</v>"))
        assertTrue(cellXml(goalsXml, "G6").contains("<v>800</v>"))
        assertTrue(cellXml(goalsXml, "J6").contains("<v>1</v>"))
        assertTrue(goalsXml.contains("Active reserve"))
        assertTrue(goalsXml.contains("Archived holiday"))
        assertTrue(goalsXml.contains(">Paused<"))
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
        val expenseSplits = listOf(
            ExpenseSplit(
                id = 301,
                transactionId = 2,
                participantName = "Mira",
                shareMinor = 40_000,
                reimbursedMinor = 15_000,
                note = "Team lunch",
            ),
        )
        val savingsGoals = listOf(
            SavingsGoal(
                id = 401,
                name = "Emergency reserve",
                targetMinor = 600_000,
                startingSavedMinor = 25_000,
                targetDateEpochDay = java.time.LocalDate.parse("2027-02-28").toEpochDay(),
                kind = SavingsGoalKind.SINKING_FUND,
                contributionFrequency = ContributionFrequency.MONTHLY,
                notes = "Build six months of buffer",
            ),
        )
        val savingsContributions = listOf(
            SavingsContribution(
                id = 501,
                goalId = 401,
                amountMinor = 50_000,
                contributedAt = ZonedDateTime.parse("2026-08-04T18:00:00+05:30[Asia/Kolkata]")
                    .toInstant()
                    .toEpochMilli(),
                note = "Initial transfer",
            ),
        )
        val paymentCommitments = listOf(
            PaymentCommitment(
                id = 601,
                name = "StreamFlix",
                merchantKey = "streamflix",
                kind = PaymentCommitmentKind.SUBSCRIPTION,
                frequency = PaymentFrequency.MONTHLY,
                amountMinor = 49_900,
                maxMandateMinor = 59_900,
                nextDueEpochDay = java.time.LocalDate.parse("2026-08-12").toEpochDay(),
                source = PaymentCommitmentSource.ON_DEVICE_SUGGESTION,
                categoryLabel = "Entertainment",
            ),
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
                expenseSplits = expenseSplits,
                savingsGoals = savingsGoals,
                savingsContributions = savingsContributions,
                paymentCommitments = paymentCommitments,
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

    private fun cellXml(sheetXml: String, reference: String): String = requireNotNull(
        Regex("<c r=\"${Regex.escape(reference)}\"[^>]*>.*?</c>").find(sheetXml)?.value,
    ) { "Cell $reference was not found" }

    private companion object {
        const val CATEGORY_START_ROW_FOR_TESTS = 6
        val EXPECTED_ENTRIES = setOf(
            "[Content_Types].xml",
            "_rels/.rels",
            "xl/workbook.xml",
            "xl/styles.xml",
            "xl/worksheets/sheet1.xml",
            "xl/worksheets/sheet2.xml",
            "xl/worksheets/sheet3.xml",
            "xl/worksheets/sheet4.xml",
            "xl/worksheets/sheet5.xml",
            "xl/worksheets/sheet6.xml",
            "xl/worksheets/sheet7.xml",
            "xl/worksheets/sheet8.xml",
            "xl/drawings/drawing1.xml",
            "xl/charts/chart1.xml",
            "xl/charts/chart2.xml",
        )
    }
}
