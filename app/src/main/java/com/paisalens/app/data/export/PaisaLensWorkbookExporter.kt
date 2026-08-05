package com.paisalens.app.data.export

import com.paisalens.app.data.model.CategoryBudget
import com.paisalens.app.data.model.ExpenseCategory
import com.paisalens.app.data.model.TransactionRecord
import com.paisalens.app.data.model.TransactionType
import java.io.OutputStream
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.TreeMap
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Creates a standards-based XLSX workbook without a network or third-party runtime dependency.
 * The workbook contains native Excel charts and formula-backed analysis tables.
 */
object PaisaLensWorkbookExporter {
    private const val DASHBOARD_SHEET = "Dashboard"
    private const val TRANSACTIONS_SHEET = "Transactions"
    private const val CATEGORIES_SHEET = "Categories"
    private const val BUDGETS_SHEET = "Budgets"
    private const val CATEGORY_START_ROW = 6
    private const val DASHBOARD_DATA_START_ROW = 12

    private val monthFormatter = DateTimeFormatter.ofPattern("MMM yyyy", Locale.ENGLISH)
    private val exportableCategories = ExpenseCategory.entries.filterNot { it == ExpenseCategory.INCOME }

    fun write(
        transactions: List<TransactionRecord>,
        budgets: List<CategoryBudget>,
        outputStream: OutputStream,
        generatedAt: Long = System.currentTimeMillis(),
        zoneId: ZoneId = ZoneId.systemDefault(),
    ) {
        val sortedTransactions = transactions.sortedWith(
            compareByDescending<TransactionRecord> { it.occurredAt }.thenByDescending { it.id },
        )
        val categorySummaries = categorySummaries(sortedTransactions)
        val monthSummaries = monthSummaries(sortedTransactions, generatedAt, zoneId)
        val chartCategories = chartCategories(categorySummaries)
        val dashboard = dashboardSheet(
            transactions = sortedTransactions,
            categorySummaries = categorySummaries,
            chartCategories = chartCategories,
            monthSummaries = monthSummaries,
            generatedAt = generatedAt,
            zoneId = zoneId,
        )

        ZipOutputStream(outputStream.buffered()).use { zip ->
            zip.writeEntry("[Content_Types].xml", contentTypesXml())
            zip.writeEntry("_rels/.rels", rootRelationshipsXml())
            zip.writeEntry("docProps/app.xml", appPropertiesXml())
            zip.writeEntry("docProps/core.xml", corePropertiesXml(generatedAt))
            zip.writeEntry("xl/workbook.xml", workbookXml())
            zip.writeEntry("xl/_rels/workbook.xml.rels", workbookRelationshipsXml())
            zip.writeEntry("xl/styles.xml", stylesXml())
            zip.writeEntry("xl/worksheets/sheet1.xml", dashboard.xml)
            zip.writeEntry("xl/worksheets/_rels/sheet1.xml.rels", dashboardRelationshipsXml())
            zip.writeEntry(
                "xl/worksheets/sheet2.xml",
                transactionsSheet(sortedTransactions, zoneId),
            )
            zip.writeEntry(
                "xl/worksheets/sheet3.xml",
                categoriesSheet(categorySummaries, sortedTransactions.size),
            )
            zip.writeEntry(
                "xl/worksheets/sheet4.xml",
                budgetsSheet(
                    budgets = budgets,
                    transactions = sortedTransactions,
                    transactionCount = sortedTransactions.size,
                    currentMonth = YearMonth.from(Instant.ofEpochMilli(generatedAt).atZone(zoneId)),
                    zoneId = zoneId,
                ),
            )
            zip.writeEntry("xl/drawings/drawing1.xml", drawingXml(dashboard.chartStartRow))
            zip.writeEntry("xl/drawings/_rels/drawing1.xml.rels", drawingRelationshipsXml())
            zip.writeEntry(
                "xl/charts/chart1.xml",
                doughnutChartXml(chartCategories, dashboard.categoryEndRow),
            )
            zip.writeEntry(
                "xl/charts/chart2.xml",
                monthlyTrendChartXml(monthSummaries, dashboard.monthEndRow),
            )
        }
    }

    private fun categorySummaries(transactions: List<TransactionRecord>): List<CategorySummary> {
        val expenses = transactions.filter { it.type == TransactionType.EXPENSE }
        return exportableCategories.map { category ->
            val matches = expenses.filter { it.category == category }
            CategorySummary(
                category = category,
                spendMinor = matches.sumOf { it.amountMinor },
                transactionCount = matches.size,
            )
        }
    }

    private fun monthSummaries(
        transactions: List<TransactionRecord>,
        generatedAt: Long,
        zoneId: ZoneId,
    ): List<MonthSummary> {
        val grouped = transactions.groupBy {
            YearMonth.from(Instant.ofEpochMilli(it.occurredAt).atZone(zoneId))
        }
        val currentMonth = YearMonth.from(Instant.ofEpochMilli(generatedAt).atZone(zoneId))
        val months = if (grouped.isEmpty()) {
            listOf(currentMonth)
        } else {
            grouped.keys.sorted().takeLast(12)
        }
        return months.map { month ->
            val records = grouped[month].orEmpty()
            val grossSpend = records.filter { it.type == TransactionType.EXPENSE }.sumOf { it.amountMinor }
            val refunds = records.filter { it.type == TransactionType.REFUND }.sumOf { it.amountMinor }
            MonthSummary(
                month = month,
                label = month.format(monthFormatter),
                spendMinor = grossSpend - refunds,
                incomeMinor = records.filter { it.type == TransactionType.INCOME }.sumOf { it.amountMinor },
            )
        }
    }

    private fun chartCategories(categorySummaries: List<CategorySummary>): List<ChartCategory> {
        val populated = categorySummaries
            .filter { it.spendMinor > 0 }
            .sortedByDescending { it.spendMinor }
        if (populated.size <= 6) {
            return populated.map {
                ChartCategory(
                    label = it.category.label,
                    spendMinor = it.spendMinor,
                    sourceCategory = it.category,
                )
            }.ifEmpty { listOf(ChartCategory("No expenses", 0, null)) }
        }
        val top = populated.take(5).map {
            ChartCategory(it.category.label, it.spendMinor, it.category)
        }
        return top + ChartCategory(
            label = "Other categories",
            spendMinor = populated.drop(5).sumOf { it.spendMinor },
            sourceCategory = null,
        )
    }

    private fun dashboardSheet(
        transactions: List<TransactionRecord>,
        categorySummaries: List<CategorySummary>,
        chartCategories: List<ChartCategory>,
        monthSummaries: List<MonthSummary>,
        generatedAt: Long,
        zoneId: ZoneId,
    ): DashboardSheet {
        val rows = SheetRows()
        val grossSpend = transactions.filter { it.type == TransactionType.EXPENSE }.sumOf { it.amountMinor }
        val refunds = transactions.filter { it.type == TransactionType.REFUND }.sumOf { it.amountMinor }
        val income = transactions.filter { it.type == TransactionType.INCOME }.sumOf { it.amountMinor }
        val netSpend = grossSpend - refunds

        rows.text(1, 1, "PaisaLens Spending Dashboard", Styles.TITLE)
        rows.text(
            3,
            1,
            "Offline expense analysis · Exported ${formatExportDate(generatedAt, zoneId)}",
            Styles.SUBTITLE,
        )
        rows.text(5, 1, "NET SPEND", Styles.KPI_LABEL_ROSE)
        rows.number(6, 1, minorToMajor(netSpend), Styles.KPI_MONEY_ROSE)
        rows.text(5, 3, "INCOME", Styles.KPI_LABEL_GREEN)
        rows.number(6, 3, minorToMajor(income), Styles.KPI_MONEY_GREEN)
        rows.text(5, 5, "REFUNDS", Styles.KPI_LABEL_AMBER)
        rows.number(6, 5, minorToMajor(refunds), Styles.KPI_MONEY_AMBER)
        rows.text(5, 7, "TRANSACTIONS", Styles.KPI_LABEL_INDIGO)
        rows.number(6, 7, transactions.size.toDouble(), Styles.KPI_COUNT_INDIGO)

        rows.text(10, 1, "Top spending categories", Styles.SECTION)
        rows.text(11, 1, "Category", Styles.HEADER)
        rows.text(11, 2, "Spend (INR)", Styles.HEADER)
        rows.text(11, 3, "Share", Styles.HEADER)

        val totalCategorySpend = categorySummaries.sumOf { it.spendMinor }
        chartCategories.forEachIndexed { index, item ->
            val row = DASHBOARD_DATA_START_ROW + index
            val bodyStyle = if (index % 2 == 0) Styles.BODY else Styles.BODY_BAND
            val moneyStyle = if (index % 2 == 0) Styles.FORMULA_MONEY else Styles.FORMULA_MONEY_BAND
            val percentStyle = if (index % 2 == 0) Styles.FORMULA_PERCENT else Styles.FORMULA_PERCENT_BAND
            val sourceRow = item.sourceCategory?.let { category ->
                CATEGORY_START_ROW + exportableCategories.indexOf(category)
            }
            if (sourceRow != null) {
                rows.formulaText(
                    row,
                    1,
                    "'$CATEGORIES_SHEET'!A$sourceRow",
                    item.label,
                    bodyStyle,
                )
                rows.formulaNumber(
                    row,
                    2,
                    "'$CATEGORIES_SHEET'!B$sourceRow",
                    minorToMajor(item.spendMinor),
                    moneyStyle,
                )
            } else {
                rows.text(row, 1, item.label, bodyStyle)
                val firstCategoryRow = CATEGORY_START_ROW
                val lastCategoryRow = CATEGORY_START_ROW + exportableCategories.lastIndex
                val previousValues = if (index == 0) "0" else "SUM(B$DASHBOARD_DATA_START_ROW:B${row - 1})"
                rows.formulaNumber(
                    row,
                    2,
                    "SUM('$CATEGORIES_SHEET'!B$firstCategoryRow:B$lastCategoryRow)-$previousValues",
                    minorToMajor(item.spendMinor),
                    moneyStyle,
                )
            }
            rows.formulaNumber(
                row,
                3,
                "IFERROR(B$row/SUM(B\$$DASHBOARD_DATA_START_ROW:B\$${DASHBOARD_DATA_START_ROW + chartCategories.lastIndex}),0)",
                if (totalCategorySpend == 0L) 0.0 else item.spendMinor.toDouble() / totalCategorySpend,
                percentStyle,
            )
        }

        rows.text(10, 5, "Monthly cash view", Styles.SECTION)
        rows.text(11, 5, "Month", Styles.HEADER)
        rows.text(11, 6, "Net spend", Styles.HEADER)
        rows.text(11, 7, "Income", Styles.HEADER)
        val transactionEndRow = maxOf(2, transactions.size + 1)
        monthSummaries.forEachIndexed { index, month ->
            val row = DASHBOARD_DATA_START_ROW + index
            val bodyStyle = if (index % 2 == 0) Styles.BODY else Styles.BODY_BAND
            val moneyStyle = if (index % 2 == 0) Styles.FORMULA_MONEY else Styles.FORMULA_MONEY_BAND
            rows.text(row, 5, month.label, bodyStyle)
            rows.formulaNumber(
                row,
                6,
                "-SUMIFS('$TRANSACTIONS_SHEET'!\$F\$2:\$F\$$transactionEndRow," +
                    "'$TRANSACTIONS_SHEET'!\$B\$2:\$B\$$transactionEndRow,E$row," +
                    "'$TRANSACTIONS_SHEET'!\$C\$2:\$C\$$transactionEndRow,\"Expense\")-" +
                    "SUMIFS('$TRANSACTIONS_SHEET'!\$F\$2:\$F\$$transactionEndRow," +
                    "'$TRANSACTIONS_SHEET'!\$B\$2:\$B\$$transactionEndRow,E$row," +
                    "'$TRANSACTIONS_SHEET'!\$C\$2:\$C\$$transactionEndRow,\"Refund\")",
                minorToMajor(month.spendMinor),
                moneyStyle,
            )
            rows.formulaNumber(
                row,
                7,
                "SUMIFS('$TRANSACTIONS_SHEET'!\$F\$2:\$F\$$transactionEndRow," +
                    "'$TRANSACTIONS_SHEET'!\$B\$2:\$B\$$transactionEndRow,E$row," +
                    "'$TRANSACTIONS_SHEET'!\$C\$2:\$C\$$transactionEndRow,\"Income\")",
                minorToMajor(month.incomeMinor),
                moneyStyle,
            )
        }

        rows.height(1, 30.0)
        rows.height(2, 30.0)
        rows.height(3, 22.0)
        rows.height(5, 21.0)
        rows.height(6, 28.0)
        rows.height(7, 28.0)
        rows.height(10, 24.0)
        rows.height(11, 24.0)

        val dataEndRow = maxOf(
            DASHBOARD_DATA_START_ROW + chartCategories.lastIndex,
            DASHBOARD_DATA_START_ROW + monthSummaries.lastIndex,
        )
        val chartStartRow = maxOf(21, dataEndRow + 3)
        val xml = worksheetXml(
            rows = rows,
            lastCell = "H${chartStartRow + 17}",
            columns = listOf(
                ColumnWidth(1, 1, 22.0),
                ColumnWidth(2, 2, 16.0),
                ColumnWidth(3, 3, 12.0),
                ColumnWidth(4, 4, 3.0),
                ColumnWidth(5, 5, 16.0),
                ColumnWidth(6, 7, 16.0),
                ColumnWidth(8, 8, 4.0),
            ),
            merges = listOf(
                "A1:H2",
                "A3:H3",
                "A5:B5",
                "A6:B7",
                "C5:D5",
                "C6:D7",
                "E5:F5",
                "E6:F7",
                "G5:H5",
                "G6:H7",
                "A10:C10",
                "E10:G10",
            ),
            drawing = true,
            pageOrientation = "landscape",
        )
        return DashboardSheet(
            xml = xml,
            chartStartRow = chartStartRow,
            categoryEndRow = DASHBOARD_DATA_START_ROW + chartCategories.lastIndex,
            monthEndRow = DASHBOARD_DATA_START_ROW + monthSummaries.lastIndex,
        )
    }

    private fun transactionsSheet(
        transactions: List<TransactionRecord>,
        zoneId: ZoneId,
    ): String {
        val rows = SheetRows()
        val headers = listOf(
            "Date & time",
            "Month",
            "Type",
            "Merchant",
            "Category",
            "Amount (INR)",
            "Note",
            "Source",
            "Account",
        )
        headers.forEachIndexed { index, header -> rows.text(1, index + 1, header, Styles.HEADER) }
        rows.height(1, 26.0)

        transactions.forEachIndexed { index, transaction ->
            val row = index + 2
            val isBand = index % 2 == 1
            val bodyStyle = if (isBand) Styles.BODY_BAND else Styles.BODY
            val dateStyle = if (isBand) Styles.DATE_BAND else Styles.DATE
            val moneyStyle = if (isBand) Styles.MONEY_BAND else Styles.MONEY
            val noteStyle = if (isBand) Styles.NOTE_BAND else Styles.NOTE
            val dateTime = Instant.ofEpochMilli(transaction.occurredAt).atZone(zoneId)
            rows.number(row, 1, excelDateSerial(transaction.occurredAt, zoneId), dateStyle)
            rows.text(row, 2, YearMonth.from(dateTime).format(monthFormatter), bodyStyle)
            rows.text(row, 3, transaction.type.exportLabel, bodyStyle)
            rows.text(row, 4, transaction.merchant, bodyStyle)
            rows.text(row, 5, transaction.category.label, bodyStyle)
            rows.number(row, 6, signedAmount(transaction), moneyStyle)
            rows.text(row, 7, transaction.note.orEmpty(), noteStyle)
            rows.text(row, 8, transaction.source.name.lowercase().replaceFirstChar(Char::titlecase), bodyStyle)
            rows.text(row, 9, transaction.accountHint.orEmpty(), bodyStyle)
            rows.height(row, if (transaction.note.isNullOrBlank()) 21.0 else 34.0)
        }

        return worksheetXml(
            rows = rows,
            lastCell = "I${maxOf(1, transactions.size + 1)}",
            columns = listOf(
                ColumnWidth(1, 1, 21.0),
                ColumnWidth(2, 3, 14.0),
                ColumnWidth(4, 4, 25.0),
                ColumnWidth(5, 5, 20.0),
                ColumnWidth(6, 6, 16.0),
                ColumnWidth(7, 7, 38.0),
                ColumnWidth(8, 9, 16.0),
            ),
            freezeRows = 1,
            autoFilter = "A1:I${maxOf(1, transactions.size + 1)}",
            pageOrientation = "landscape",
        )
    }

    private fun categoriesSheet(
        summaries: List<CategorySummary>,
        transactionCount: Int,
    ): String {
        val rows = SheetRows()
        rows.text(1, 1, "Spending by Category", Styles.TITLE)
        rows.text(3, 1, "Formula-backed analysis linked to the Transactions sheet", Styles.SUBTITLE)
        listOf("Category", "Spend (INR)", "Transactions", "Average", "Share").forEachIndexed { index, header ->
            rows.text(5, index + 1, header, Styles.HEADER)
        }
        val transactionEndRow = maxOf(2, transactionCount + 1)
        val totalSpend = summaries.sumOf { it.spendMinor }
        summaries.forEachIndexed { index, summary ->
            val row = CATEGORY_START_ROW + index
            val isBand = index % 2 == 1
            val bodyStyle = if (isBand) Styles.BODY_BAND else Styles.BODY
            val moneyStyle = if (isBand) Styles.FORMULA_MONEY_BAND else Styles.FORMULA_MONEY
            val numberStyle = if (isBand) Styles.NUMBER_BAND else Styles.NUMBER
            val percentStyle = if (isBand) Styles.FORMULA_PERCENT_BAND else Styles.FORMULA_PERCENT
            rows.text(row, 1, summary.category.label, bodyStyle)
            rows.formulaNumber(
                row,
                2,
                "-SUMIFS('$TRANSACTIONS_SHEET'!\$F\$2:\$F\$$transactionEndRow," +
                    "'$TRANSACTIONS_SHEET'!\$E\$2:\$E\$$transactionEndRow,A$row," +
                    "'$TRANSACTIONS_SHEET'!\$C\$2:\$C\$$transactionEndRow,\"Expense\")",
                minorToMajor(summary.spendMinor),
                moneyStyle,
            )
            rows.formulaNumber(
                row,
                3,
                "COUNTIFS('$TRANSACTIONS_SHEET'!\$E\$2:\$E\$$transactionEndRow,A$row," +
                    "'$TRANSACTIONS_SHEET'!\$C\$2:\$C\$$transactionEndRow,\"Expense\")",
                summary.transactionCount.toDouble(),
                numberStyle,
            )
            rows.formulaNumber(
                row,
                4,
                "IFERROR(B$row/C$row,0)",
                if (summary.transactionCount == 0) 0.0 else minorToMajor(summary.spendMinor) / summary.transactionCount,
                moneyStyle,
            )
            rows.formulaNumber(
                row,
                5,
                "IFERROR(B$row/SUM(\$B\$$CATEGORY_START_ROW:\$B\$${CATEGORY_START_ROW + summaries.lastIndex}),0)",
                if (totalSpend == 0L) 0.0 else summary.spendMinor.toDouble() / totalSpend,
                percentStyle,
            )
        }
        val totalRow = CATEGORY_START_ROW + summaries.size
        rows.text(totalRow, 1, "Total", Styles.TOTAL_LABEL)
        rows.formulaNumber(
            totalRow,
            2,
            "SUM(B$CATEGORY_START_ROW:B${totalRow - 1})",
            minorToMajor(totalSpend),
            Styles.TOTAL_MONEY,
        )
        rows.formulaNumber(
            totalRow,
            3,
            "SUM(C$CATEGORY_START_ROW:C${totalRow - 1})",
            summaries.sumOf { it.transactionCount }.toDouble(),
            Styles.NUMBER,
        )
        rows.formulaNumber(
            totalRow,
            4,
            "IFERROR(B$totalRow/C$totalRow,0)",
            if (summaries.sumOf { it.transactionCount } == 0) 0.0 else
                minorToMajor(totalSpend) / summaries.sumOf { it.transactionCount },
            Styles.TOTAL_MONEY,
        )
        rows.formulaNumber(
            totalRow,
            5,
            "SUM(E$CATEGORY_START_ROW:E${totalRow - 1})",
            if (totalSpend == 0L) 0.0 else 1.0,
            Styles.FORMULA_PERCENT,
        )
        rows.height(1, 30.0)
        rows.height(2, 30.0)
        rows.height(3, 22.0)
        rows.height(5, 26.0)
        return worksheetXml(
            rows = rows,
            lastCell = "E$totalRow",
            columns = listOf(
                ColumnWidth(1, 1, 24.0),
                ColumnWidth(2, 2, 18.0),
                ColumnWidth(3, 3, 15.0),
                ColumnWidth(4, 4, 18.0),
                ColumnWidth(5, 5, 13.0),
            ),
            merges = listOf("A1:E2", "A3:E3"),
            freezeRows = 5,
            autoFilter = "A5:E${totalRow - 1}",
        )
    }

    private fun budgetsSheet(
        budgets: List<CategoryBudget>,
        transactions: List<TransactionRecord>,
        transactionCount: Int,
        currentMonth: YearMonth,
        zoneId: ZoneId,
    ): String {
        val rows = SheetRows()
        rows.text(1, 1, "Budget Performance", Styles.TITLE)
        rows.text(
            3,
            1,
            "Current month: ${currentMonth.format(monthFormatter)} · Positive remaining amounts are on track",
            Styles.SUBTITLE,
        )
        listOf("Category", "Monthly budget", "Current spend", "Remaining", "Used", "Status").forEachIndexed { index, header ->
            rows.text(5, index + 1, header, Styles.HEADER)
        }
        val budgetMap = budgets.associateBy { it.category }
        val currentMonthExpenses = transactions.filter {
            it.type == TransactionType.EXPENSE &&
                YearMonth.from(Instant.ofEpochMilli(it.occurredAt).atZone(zoneId)) == currentMonth
        }
        val transactionEndRow = maxOf(2, transactionCount + 1)
        val monthLabel = currentMonth.format(monthFormatter)
        exportableCategories.forEachIndexed { index, category ->
            val row = CATEGORY_START_ROW + index
            val budgetMinor = budgetMap[category]?.limitMinor ?: 0L
            val spendMinor = currentMonthExpenses.filter { it.category == category }.sumOf { it.amountMinor }
            val remainingMinor = budgetMinor - spendMinor
            val utilization = if (budgetMinor == 0L) 0.0 else spendMinor.toDouble() / budgetMinor
            val status = when {
                budgetMinor == 0L -> "Not set"
                remainingMinor < 0 -> "Over budget"
                else -> "On track"
            }
            val isBand = index % 2 == 1
            val bodyStyle = if (isBand) Styles.BODY_BAND else Styles.BODY
            val moneyStyle = if (isBand) Styles.FORMULA_MONEY_BAND else Styles.FORMULA_MONEY
            val percentStyle = if (isBand) Styles.FORMULA_PERCENT_BAND else Styles.FORMULA_PERCENT
            rows.text(row, 1, category.label, bodyStyle)
            rows.number(row, 2, minorToMajor(budgetMinor), moneyStyle)
            rows.formulaNumber(
                row,
                3,
                "-SUMIFS('$TRANSACTIONS_SHEET'!\$F\$2:\$F\$$transactionEndRow," +
                    "'$TRANSACTIONS_SHEET'!\$B\$2:\$B\$$transactionEndRow,\"$monthLabel\"," +
                    "'$TRANSACTIONS_SHEET'!\$E\$2:\$E\$$transactionEndRow,A$row," +
                    "'$TRANSACTIONS_SHEET'!\$C\$2:\$C\$$transactionEndRow,\"Expense\")",
                minorToMajor(spendMinor),
                moneyStyle,
            )
            rows.formulaNumber(
                row,
                4,
                "B$row-C$row",
                minorToMajor(remainingMinor),
                moneyStyle,
            )
            rows.formulaNumber(
                row,
                5,
                "IFERROR(C$row/B$row,0)",
                utilization,
                percentStyle,
            )
            rows.formulaText(
                row,
                6,
                "IF(B$row=0,\"Not set\",IF(D$row<0,\"Over budget\",\"On track\"))",
                status,
                when (status) {
                    "On track" -> Styles.STATUS_GOOD
                    "Over budget" -> Styles.STATUS_BAD
                    else -> Styles.STATUS_NEUTRAL
                },
            )
        }
        val lastRow = CATEGORY_START_ROW + exportableCategories.lastIndex
        rows.height(1, 30.0)
        rows.height(2, 30.0)
        rows.height(3, 22.0)
        rows.height(5, 26.0)
        return worksheetXml(
            rows = rows,
            lastCell = "F$lastRow",
            columns = listOf(
                ColumnWidth(1, 1, 24.0),
                ColumnWidth(2, 4, 18.0),
                ColumnWidth(5, 5, 12.0),
                ColumnWidth(6, 6, 16.0),
            ),
            merges = listOf("A1:F2", "A3:F3"),
            freezeRows = 5,
            autoFilter = "A5:F$lastRow",
        )
    }

    private fun worksheetXml(
        rows: SheetRows,
        lastCell: String,
        columns: List<ColumnWidth>,
        merges: List<String> = emptyList(),
        freezeRows: Int? = null,
        autoFilter: String? = null,
        drawing: Boolean = false,
        pageOrientation: String = "portrait",
    ): String = buildString {
        append(XML_DECLARATION)
        append(
            "<worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\" " +
                "xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\">",
        )
        append("<dimension ref=\"A1:$lastCell\"/>")
        append("<sheetViews><sheetView showGridLines=\"0\" workbookViewId=\"0\">")
        if (freezeRows != null) {
            append(
                "<pane ySplit=\"$freezeRows\" topLeftCell=\"A${freezeRows + 1}\" " +
                    "activePane=\"bottomLeft\" state=\"frozen\"/>",
            )
            append("<selection pane=\"bottomLeft\" activeCell=\"A${freezeRows + 1}\" sqref=\"A${freezeRows + 1}\"/>")
        }
        append("</sheetView></sheetViews>")
        append("<sheetFormatPr defaultRowHeight=\"18\"/>")
        append("<cols>")
        columns.forEach { column ->
            append(
                "<col min=\"${column.min}\" max=\"${column.max}\" width=\"${column.width}\" " +
                    "customWidth=\"1\"/>",
            )
        }
        append("</cols>")
        append("<sheetData>${rows.xml()}</sheetData>")
        if (merges.isNotEmpty()) {
            append("<mergeCells count=\"${merges.size}\">")
            merges.forEach { append("<mergeCell ref=\"$it\"/>") }
            append("</mergeCells>")
        }
        autoFilter?.let { append("<autoFilter ref=\"$it\"/>") }
        append(
            "<pageMargins left=\"0.3\" right=\"0.3\" top=\"0.5\" bottom=\"0.5\" " +
                "header=\"0.2\" footer=\"0.2\"/>",
        )
        append("<pageSetup orientation=\"$pageOrientation\" fitToWidth=\"1\" fitToHeight=\"0\"/>")
        if (drawing) append("<drawing r:id=\"rId1\"/>")
        append("</worksheet>")
    }

    private fun contentTypesXml(): String = XML_DECLARATION +
        """
        <Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
          <Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
          <Default Extension="xml" ContentType="application/xml"/>
          <Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/>
          <Override PartName="/xl/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml"/>
          <Override PartName="/xl/worksheets/sheet1.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/>
          <Override PartName="/xl/worksheets/sheet2.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/>
          <Override PartName="/xl/worksheets/sheet3.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/>
          <Override PartName="/xl/worksheets/sheet4.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/>
          <Override PartName="/xl/drawings/drawing1.xml" ContentType="application/vnd.openxmlformats-officedocument.drawing+xml"/>
          <Override PartName="/xl/charts/chart1.xml" ContentType="application/vnd.openxmlformats-officedocument.drawingml.chart+xml"/>
          <Override PartName="/xl/charts/chart2.xml" ContentType="application/vnd.openxmlformats-officedocument.drawingml.chart+xml"/>
          <Override PartName="/docProps/core.xml" ContentType="application/vnd.openxmlformats-package.core-properties+xml"/>
          <Override PartName="/docProps/app.xml" ContentType="application/vnd.openxmlformats-officedocument.extended-properties+xml"/>
        </Types>
        """.trimIndent()

    private fun rootRelationshipsXml(): String = XML_DECLARATION +
        """
        <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
          <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/>
          <Relationship Id="rId2" Type="http://schemas.openxmlformats.org/package/2006/relationships/metadata/core-properties" Target="docProps/core.xml"/>
          <Relationship Id="rId3" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/extended-properties" Target="docProps/app.xml"/>
        </Relationships>
        """.trimIndent()

    private fun workbookXml(): String = XML_DECLARATION +
        """
        <workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">
          <fileVersion appName="xl" lastEdited="7" lowestEdited="7" rupBuild="27328"/>
          <workbookPr date1904="0"/>
          <bookViews><workbookView xWindow="0" yWindow="0" windowWidth="24000" windowHeight="12000"/></bookViews>
          <sheets>
            <sheet name="$DASHBOARD_SHEET" sheetId="1" r:id="rId1"/>
            <sheet name="$TRANSACTIONS_SHEET" sheetId="2" r:id="rId2"/>
            <sheet name="$CATEGORIES_SHEET" sheetId="3" r:id="rId3"/>
            <sheet name="$BUDGETS_SHEET" sheetId="4" r:id="rId4"/>
          </sheets>
          <calcPr calcId="191029" fullCalcOnLoad="1" forceFullCalc="1"/>
        </workbook>
        """.trimIndent()

    private fun workbookRelationshipsXml(): String = XML_DECLARATION +
        """
        <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
          <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet1.xml"/>
          <Relationship Id="rId2" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet2.xml"/>
          <Relationship Id="rId3" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet3.xml"/>
          <Relationship Id="rId4" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet4.xml"/>
          <Relationship Id="rId5" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles" Target="styles.xml"/>
        </Relationships>
        """.trimIndent()

    private fun dashboardRelationshipsXml(): String = XML_DECLARATION +
        """
        <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
          <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/drawing" Target="../drawings/drawing1.xml"/>
        </Relationships>
        """.trimIndent()

    private fun drawingRelationshipsXml(): String = XML_DECLARATION +
        """
        <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
          <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/chart" Target="../charts/chart1.xml"/>
          <Relationship Id="rId2" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/chart" Target="../charts/chart2.xml"/>
        </Relationships>
        """.trimIndent()

    private fun drawingXml(chartStartRow: Int): String {
        val start = chartStartRow - 1
        val end = start + 16
        return XML_DECLARATION +
            """
            <xdr:wsDr xmlns:xdr="http://schemas.openxmlformats.org/drawingml/2006/spreadsheetDrawing" xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main">
              ${chartAnchorXml(2, "Category spend chart", "rId1", 0, start, 4, end)}
              ${chartAnchorXml(3, "Monthly cash trend chart", "rId2", 4, start, 8, end)}
            </xdr:wsDr>
            """.trimIndent()
    }

    private fun chartAnchorXml(
        id: Int,
        name: String,
        relationshipId: String,
        fromColumn: Int,
        fromRow: Int,
        toColumn: Int,
        toRow: Int,
    ): String =
        """
        <xdr:twoCellAnchor>
          <xdr:from><xdr:col>$fromColumn</xdr:col><xdr:colOff>0</xdr:colOff><xdr:row>$fromRow</xdr:row><xdr:rowOff>0</xdr:rowOff></xdr:from>
          <xdr:to><xdr:col>$toColumn</xdr:col><xdr:colOff>0</xdr:colOff><xdr:row>$toRow</xdr:row><xdr:rowOff>0</xdr:rowOff></xdr:to>
          <xdr:graphicFrame macro="">
            <xdr:nvGraphicFramePr><xdr:cNvPr id="$id" name="$name"/><xdr:cNvGraphicFramePr/></xdr:nvGraphicFramePr>
            <xdr:xfrm/>
            <a:graphic><a:graphicData uri="http://schemas.openxmlformats.org/drawingml/2006/chart"><c:chart xmlns:c="http://schemas.openxmlformats.org/drawingml/2006/chart" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships" r:id="$relationshipId"/></a:graphicData></a:graphic>
          </xdr:graphicFrame>
          <xdr:clientData/>
        </xdr:twoCellAnchor>
        """.trimIndent()

    private fun doughnutChartXml(
        categories: List<ChartCategory>,
        endRow: Int,
    ): String {
        val categoryRange = "'$DASHBOARD_SHEET'!\$A\$$DASHBOARD_DATA_START_ROW:\$A\$$endRow"
        val valueRange = "'$DASHBOARD_SHEET'!\$B\$$DASHBOARD_DATA_START_ROW:\$B\$$endRow"
        val categoryCache = stringCache(categories.map { it.label })
        val valueCache = numberCache(categories.map { minorToMajor(it.spendMinor) }, "₹#,##0")
        val colors = listOf("4F46E5", "14B8A6", "F59E0B", "F43F5E", "8B5CF6", "0EA5E9")
        val points = categories.indices.joinToString("") { index ->
            "<c:dPt><c:idx val=\"$index\"/><c:spPr><a:solidFill><a:srgbClr val=\"${colors[index % colors.size]}\"/></a:solidFill></c:spPr></c:dPt>"
        }
        return XML_DECLARATION +
            """
            <c:chartSpace xmlns:c="http://schemas.openxmlformats.org/drawingml/2006/chart" xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main">
              <c:roundedCorners val="1"/>
              <c:chart>
                ${chartTitleXml("Where your money went")}
                <c:plotArea><c:layout/>
                  <c:doughnutChart><c:varyColors val="1"/>
                    <c:ser><c:idx val="0"/><c:order val="0"/><c:tx><c:v>Spend</c:v></c:tx>
                      $points
                      <c:cat><c:strRef><c:f>$categoryRange</c:f>$categoryCache</c:strRef></c:cat>
                      <c:val><c:numRef><c:f>$valueRange</c:f>$valueCache</c:numRef></c:val>
                      <c:dLbls><c:showLegendKey val="0"/><c:showVal val="0"/><c:showCatName val="0"/><c:showPercent val="1"/><c:showLeaderLines val="1"/></c:dLbls>
                    </c:ser>
                    <c:holeSize val="58"/>
                  </c:doughnutChart>
                </c:plotArea>
                <c:legend><c:legendPos val="b"/><c:layout/></c:legend>
                <c:plotVisOnly val="1"/><c:dispBlanksAs val="zero"/><c:showDLblsOverMax val="0"/>
              </c:chart>
            </c:chartSpace>
            """.trimIndent()
    }

    private fun monthlyTrendChartXml(
        months: List<MonthSummary>,
        endRow: Int,
    ): String {
        val categoryRange = "'$DASHBOARD_SHEET'!\$E\$$DASHBOARD_DATA_START_ROW:\$E\$$endRow"
        val spendRange = "'$DASHBOARD_SHEET'!\$F\$$DASHBOARD_DATA_START_ROW:\$F\$$endRow"
        val incomeRange = "'$DASHBOARD_SHEET'!\$G\$$DASHBOARD_DATA_START_ROW:\$G\$$endRow"
        val categoryCache = stringCache(months.map { it.label })
        val spendCache = numberCache(months.map { minorToMajor(it.spendMinor) }, "₹#,##0")
        val incomeCache = numberCache(months.map { minorToMajor(it.incomeMinor) }, "₹#,##0")
        return XML_DECLARATION +
            """
            <c:chartSpace xmlns:c="http://schemas.openxmlformats.org/drawingml/2006/chart" xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main">
              <c:roundedCorners val="1"/>
              <c:chart>
                ${chartTitleXml("Monthly spend vs income")}
                <c:plotArea><c:layout/>
                  <c:lineChart><c:grouping val="standard"/><c:varyColors val="0"/>
                    ${lineSeriesXml(0, "Net spend", categoryRange, categoryCache, spendRange, spendCache, "F43F5E")}
                    ${lineSeriesXml(1, "Income", categoryRange, categoryCache, incomeRange, incomeCache, "14B8A6")}
                    <c:marker val="1"/><c:smooth val="0"/><c:axId val="78123601"/><c:axId val="78123602"/>
                  </c:lineChart>
                  <c:catAx><c:axId val="78123601"/><c:scaling><c:orientation val="minMax"/></c:scaling><c:delete val="0"/><c:axPos val="b"/><c:tickLblPos val="nextTo"/><c:crossAx val="78123602"/><c:crosses val="autoZero"/><c:auto val="1"/><c:lblAlgn val="ctr"/><c:lblOffset val="100"/></c:catAx>
                  <c:valAx><c:axId val="78123602"/><c:scaling><c:orientation val="minMax"/></c:scaling><c:delete val="0"/><c:axPos val="l"/><c:numFmt formatCode="₹#,##0" sourceLinked="0"/><c:majorGridlines/><c:tickLblPos val="nextTo"/><c:crossAx val="78123601"/><c:crosses val="autoZero"/><c:crossBetween val="between"/></c:valAx>
                </c:plotArea>
                <c:legend><c:legendPos val="b"/><c:layout/></c:legend>
                <c:plotVisOnly val="1"/><c:dispBlanksAs val="gap"/><c:showDLblsOverMax val="0"/>
              </c:chart>
            </c:chartSpace>
            """.trimIndent()
    }

    private fun lineSeriesXml(
        index: Int,
        name: String,
        categoryRange: String,
        categoryCache: String,
        valueRange: String,
        valueCache: String,
        color: String,
    ): String =
        """
        <c:ser><c:idx val="$index"/><c:order val="$index"/><c:tx><c:v>$name</c:v></c:tx>
          <c:spPr><a:ln w="28575"><a:solidFill><a:srgbClr val="$color"/></a:solidFill><a:round/></a:ln></c:spPr>
          <c:marker><c:symbol val="circle"/><c:size val="6"/><c:spPr><a:solidFill><a:srgbClr val="$color"/></a:solidFill><a:ln><a:solidFill><a:srgbClr val="FFFFFF"/></a:solidFill></a:ln></c:spPr></c:marker>
          <c:cat><c:strRef><c:f>$categoryRange</c:f>$categoryCache</c:strRef></c:cat>
          <c:val><c:numRef><c:f>$valueRange</c:f>$valueCache</c:numRef></c:val>
          <c:smooth val="0"/>
        </c:ser>
        """.trimIndent()

    private fun chartTitleXml(title: String): String =
        """
        <c:title><c:tx><c:rich><a:bodyPr/><a:lstStyle/><a:p><a:r><a:rPr lang="en-US" sz="1400" b="1"/><a:t>${escapeXml(title)}</a:t></a:r><a:endParaRPr lang="en-US"/></a:p></c:rich></c:tx><c:layout/><c:overlay val="0"/></c:title>
        """.trimIndent()

    private fun stringCache(values: List<String>): String = buildString {
        append("<c:strCache><c:ptCount val=\"${values.size}\"/>")
        values.forEachIndexed { index, value ->
            append("<c:pt idx=\"$index\"><c:v>${escapeXml(value)}</c:v></c:pt>")
        }
        append("</c:strCache>")
    }

    private fun numberCache(values: List<Double>, formatCode: String): String = buildString {
        append("<c:numCache><c:formatCode>${escapeXml(formatCode)}</c:formatCode><c:ptCount val=\"${values.size}\"/>")
        values.forEachIndexed { index, value ->
            append("<c:pt idx=\"$index\"><c:v>${numberXml(value)}</c:v></c:pt>")
        }
        append("</c:numCache>")
    }

    private fun appPropertiesXml(): String = XML_DECLARATION +
        """
        <Properties xmlns="http://schemas.openxmlformats.org/officeDocument/2006/extended-properties" xmlns:vt="http://schemas.openxmlformats.org/officeDocument/2006/docPropsVTypes">
          <Application>PaisaLens</Application>
          <HeadingPairs><vt:vector size="2" baseType="variant"><vt:variant><vt:lpstr>Worksheets</vt:lpstr></vt:variant><vt:variant><vt:i4>4</vt:i4></vt:variant></vt:vector></HeadingPairs>
          <TitlesOfParts><vt:vector size="4" baseType="lpstr"><vt:lpstr>$DASHBOARD_SHEET</vt:lpstr><vt:lpstr>$TRANSACTIONS_SHEET</vt:lpstr><vt:lpstr>$CATEGORIES_SHEET</vt:lpstr><vt:lpstr>$BUDGETS_SHEET</vt:lpstr></vt:vector></TitlesOfParts>
          <Company/><LinksUpToDate>false</LinksUpToDate><SharedDoc>false</SharedDoc><HyperlinksChanged>false</HyperlinksChanged><AppVersion>16.0300</AppVersion>
        </Properties>
        """.trimIndent()

    private fun corePropertiesXml(generatedAt: Long): String {
        val timestamp = Instant.ofEpochMilli(generatedAt).toString()
        return XML_DECLARATION +
            """
            <cp:coreProperties xmlns:cp="http://schemas.openxmlformats.org/package/2006/metadata/core-properties" xmlns:dc="http://purl.org/dc/elements/1.1/" xmlns:dcterms="http://purl.org/dc/terms/" xmlns:dcmitype="http://purl.org/dc/dcmitype/" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance">
              <dc:title>PaisaLens Spending Analysis</dc:title><dc:subject>Offline expense report</dc:subject><dc:creator>PaisaLens</dc:creator><cp:lastModifiedBy>PaisaLens</cp:lastModifiedBy><dc:description>Private on-device spending export with charts and categorized transaction detail.</dc:description><dcterms:created xsi:type="dcterms:W3CDTF">$timestamp</dcterms:created><dcterms:modified xsi:type="dcterms:W3CDTF">$timestamp</dcterms:modified>
            </cp:coreProperties>
            """.trimIndent()
    }

    private fun stylesXml(): String = XML_DECLARATION +
        """
        <styleSheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
          <numFmts count="3"><numFmt numFmtId="164" formatCode="₹#,##0.00;[Red]-₹#,##0.00"/><numFmt numFmtId="165" formatCode="dd mmm yyyy hh:mm"/><numFmt numFmtId="166" formatCode="0.0%"/></numFmts>
          <fonts count="10">
            <font><sz val="11"/><color rgb="FF1F2937"/><name val="Aptos"/><family val="2"/></font>
            <font><b/><sz val="20"/><color rgb="FFFFFFFF"/><name val="Aptos Display"/><family val="2"/></font>
            <font><b/><sz val="11"/><color rgb="FFFFFFFF"/><name val="Aptos"/><family val="2"/></font>
            <font><b/><sz val="12"/><color rgb="FF1F2937"/><name val="Aptos"/><family val="2"/></font>
            <font><sz val="10"/><color rgb="FF64748B"/><name val="Aptos"/><family val="2"/></font>
            <font><b/><sz val="20"/><color rgb="FF172033"/><name val="Aptos Display"/><family val="2"/></font>
            <font><sz val="10"/><color rgb="FFE2E8F0"/><name val="Aptos"/><family val="2"/></font>
            <font><b/><sz val="10"/><color rgb="FF047857"/><name val="Aptos"/><family val="2"/></font>
            <font><b/><sz val="10"/><color rgb="FFBE123C"/><name val="Aptos"/><family val="2"/></font>
            <font><b/><sz val="10"/><color rgb="FF64748B"/><name val="Aptos"/><family val="2"/></font>
          </fonts>
          <fills count="10">
            <fill><patternFill patternType="none"/></fill><fill><patternFill patternType="gray125"/></fill>
            <fill><patternFill patternType="solid"><fgColor rgb="FF1F2A44"/><bgColor indexed="64"/></patternFill></fill>
            <fill><patternFill patternType="solid"><fgColor rgb="FF4F46E5"/><bgColor indexed="64"/></patternFill></fill>
            <fill><patternFill patternType="solid"><fgColor rgb="FFEEF2FF"/><bgColor indexed="64"/></patternFill></fill>
            <fill><patternFill patternType="solid"><fgColor rgb="FFECFDF5"/><bgColor indexed="64"/></patternFill></fill>
            <fill><patternFill patternType="solid"><fgColor rgb="FFFFF1F2"/><bgColor indexed="64"/></patternFill></fill>
            <fill><patternFill patternType="solid"><fgColor rgb="FFFFFBEB"/><bgColor indexed="64"/></patternFill></fill>
            <fill><patternFill patternType="solid"><fgColor rgb="FFF8FAFC"/><bgColor indexed="64"/></patternFill></fill>
            <fill><patternFill patternType="solid"><fgColor rgb="FFF1F5F9"/><bgColor indexed="64"/></patternFill></fill>
          </fills>
          <borders count="2"><border><left/><right/><top/><bottom/><diagonal/></border><border><left/><right/><top/><bottom style="thin"><color rgb="FFE2E8F0"/></bottom><diagonal/></border></borders>
          <cellStyleXfs count="1"><xf numFmtId="0" fontId="0" fillId="0" borderId="0"/></cellStyleXfs>
          <cellXfs count="32">
            ${xf(0, 0, 0, 0)}
            ${xf(0, 1, 2, 0, "center", "center")}
            ${xf(0, 6, 2, 0, "center", "center")}
            ${xf(0, 3, 0, 0, "left", "center")}
            ${xf(0, 2, 3, 1, "center", "center", wrap = true)}
            ${xf(0, 0, 0, 1, "left", "center")}
            ${xf(0, 0, 8, 1, "left", "center")}
            ${xf(165, 0, 0, 1, "left", "center")}
            ${xf(165, 0, 8, 1, "left", "center")}
            ${xf(164, 0, 0, 1, "right", "center")}
            ${xf(164, 0, 8, 1, "right", "center")}
            ${xf(0, 4, 0, 1, "left", "center", wrap = true)}
            ${xf(0, 4, 8, 1, "left", "center", wrap = true)}
            ${xf(0, 3, 6, 0, "center", "center")}
            ${xf(164, 5, 6, 0, "center", "center")}
            ${xf(0, 3, 5, 0, "center", "center")}
            ${xf(164, 5, 5, 0, "center", "center")}
            ${xf(0, 3, 7, 0, "center", "center")}
            ${xf(164, 5, 7, 0, "center", "center")}
            ${xf(0, 3, 4, 0, "center", "center")}
            ${xf(0, 5, 4, 0, "center", "center")}
            ${xf(164, 0, 0, 1, "right", "center")}
            ${xf(164, 0, 8, 1, "right", "center")}
            ${xf(166, 0, 0, 1, "right", "center")}
            ${xf(166, 0, 8, 1, "right", "center")}
            ${xf(0, 3, 9, 1, "left", "center")}
            ${xf(164, 3, 9, 1, "right", "center")}
            ${xf(0, 7, 5, 1, "center", "center")}
            ${xf(0, 8, 6, 1, "center", "center")}
            ${xf(0, 9, 9, 1, "center", "center")}
            ${xf(0, 0, 0, 1, "right", "center")}
            ${xf(0, 0, 8, 1, "right", "center")}
          </cellXfs>
          <cellStyles count="1"><cellStyle name="Normal" xfId="0" builtinId="0"/></cellStyles><dxfs count="0"/><tableStyles count="0" defaultTableStyle="TableStyleMedium2" defaultPivotStyle="PivotStyleLight16"/>
        </styleSheet>
        """.trimIndent()

    private fun xf(
        numFmtId: Int,
        fontId: Int,
        fillId: Int,
        borderId: Int,
        horizontal: String? = null,
        vertical: String? = null,
        wrap: Boolean = false,
    ): String {
        val alignment = if (horizontal != null || vertical != null || wrap) {
            "<alignment" +
                (horizontal?.let { " horizontal=\"$it\"" } ?: "") +
                (vertical?.let { " vertical=\"$it\"" } ?: "") +
                (if (wrap) " wrapText=\"1\"" else "") +
                "/>"
        } else {
            ""
        }
        return "<xf numFmtId=\"$numFmtId\" fontId=\"$fontId\" fillId=\"$fillId\" " +
            "borderId=\"$borderId\" xfId=\"0\" applyFont=\"1\" applyFill=\"1\" applyBorder=\"1\" " +
            "applyNumberFormat=\"1\"${if (alignment.isNotEmpty()) " applyAlignment=\"1\"" else ""}>" +
            alignment + "</xf>"
    }

    private fun excelDateSerial(timestamp: Long, zoneId: ZoneId): Double {
        val dateTime = Instant.ofEpochMilli(timestamp).atZone(zoneId).toLocalDateTime()
        val days = java.time.temporal.ChronoUnit.DAYS.between(LocalDate.of(1899, 12, 30), dateTime.toLocalDate())
        val seconds = dateTime.toLocalTime().toSecondOfDay() + dateTime.nano / 1_000_000_000.0
        return days + seconds / 86_400.0
    }

    private fun signedAmount(transaction: TransactionRecord): Double = when (transaction.type) {
        TransactionType.INCOME, TransactionType.REFUND -> minorToMajor(transaction.amountMinor)
        TransactionType.EXPENSE, TransactionType.TRANSFER -> -minorToMajor(transaction.amountMinor)
    }

    private fun minorToMajor(amountMinor: Long): Double = amountMinor / 100.0

    private fun numberXml(value: Double): String =
        BigDecimal.valueOf(value).stripTrailingZeros().toPlainString()

    private fun formatExportDate(timestamp: Long, zoneId: ZoneId): String =
        DateTimeFormatter.ofPattern("d MMM yyyy, h:mm a", Locale.ENGLISH)
            .format(Instant.ofEpochMilli(timestamp).atZone(zoneId))

    private val TransactionType.exportLabel: String
        get() = name.lowercase().replaceFirstChar(Char::titlecase)

    private fun escapeXml(value: String): String = value
        .map { character ->
            if (character.code < 0x20 && character != '\t' && character != '\n' && character != '\r') ' '
            else character
        }
        .joinToString("")
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&apos;")

    private fun ZipOutputStream.writeEntry(path: String, content: String) {
        putNextEntry(ZipEntry(path))
        write(content.toByteArray(Charsets.UTF_8))
        closeEntry()
    }

    private data class CategorySummary(
        val category: ExpenseCategory,
        val spendMinor: Long,
        val transactionCount: Int,
    )

    private data class MonthSummary(
        val month: YearMonth,
        val label: String,
        val spendMinor: Long,
        val incomeMinor: Long,
    )

    private data class ChartCategory(
        val label: String,
        val spendMinor: Long,
        val sourceCategory: ExpenseCategory?,
    )

    private data class ColumnWidth(
        val min: Int,
        val max: Int,
        val width: Double,
    )

    private data class DashboardSheet(
        val xml: String,
        val chartStartRow: Int,
        val categoryEndRow: Int,
        val monthEndRow: Int,
    )

    private class SheetRows {
        private val cells = TreeMap<Int, TreeMap<Int, String>>()
        private val heights = mutableMapOf<Int, Double>()

        fun text(row: Int, column: Int, value: String, style: Int) {
            val reference = cellReference(row, column)
            val preserved = if (value.startsWith(" ") || value.endsWith(" ")) " xml:space=\"preserve\"" else ""
            cell(row, column, "<c r=\"$reference\" s=\"$style\" t=\"inlineStr\"><is><t$preserved>${escapeXml(value)}</t></is></c>")
        }

        fun number(row: Int, column: Int, value: Double, style: Int) {
            val reference = cellReference(row, column)
            cell(row, column, "<c r=\"$reference\" s=\"$style\"><v>${numberXml(value)}</v></c>")
        }

        fun formulaNumber(
            row: Int,
            column: Int,
            formula: String,
            cachedValue: Double,
            style: Int,
        ) {
            val reference = cellReference(row, column)
            cell(
                row,
                column,
                "<c r=\"$reference\" s=\"$style\"><f>${escapeXml(formula)}</f><v>${numberXml(cachedValue)}</v></c>",
            )
        }

        fun formulaText(
            row: Int,
            column: Int,
            formula: String,
            cachedValue: String,
            style: Int,
        ) {
            val reference = cellReference(row, column)
            cell(
                row,
                column,
                "<c r=\"$reference\" s=\"$style\" t=\"str\"><f>${escapeXml(formula)}</f><v>${escapeXml(cachedValue)}</v></c>",
            )
        }

        fun height(row: Int, height: Double) {
            heights[row] = height
        }

        fun xml(): String = buildString {
            cells.forEach { (rowNumber, rowCells) ->
                val height = heights[rowNumber]
                append("<row r=\"$rowNumber\"")
                if (height != null) append(" ht=\"$height\" customHeight=\"1\"")
                append(">")
                rowCells.values.forEach(::append)
                append("</row>")
            }
        }

        private fun cell(row: Int, column: Int, xml: String) {
            cells.getOrPut(row) { TreeMap() }[column] = xml
        }
    }

    private object Styles {
        const val GENERAL = 0
        const val TITLE = 1
        const val SUBTITLE = 2
        const val SECTION = 3
        const val HEADER = 4
        const val BODY = 5
        const val BODY_BAND = 6
        const val DATE = 7
        const val DATE_BAND = 8
        const val MONEY = 9
        const val MONEY_BAND = 10
        const val NOTE = 11
        const val NOTE_BAND = 12
        const val KPI_LABEL_ROSE = 13
        const val KPI_MONEY_ROSE = 14
        const val KPI_LABEL_GREEN = 15
        const val KPI_MONEY_GREEN = 16
        const val KPI_LABEL_AMBER = 17
        const val KPI_MONEY_AMBER = 18
        const val KPI_LABEL_INDIGO = 19
        const val KPI_COUNT_INDIGO = 20
        const val FORMULA_MONEY = 21
        const val FORMULA_MONEY_BAND = 22
        const val FORMULA_PERCENT = 23
        const val FORMULA_PERCENT_BAND = 24
        const val TOTAL_LABEL = 25
        const val TOTAL_MONEY = 26
        const val STATUS_GOOD = 27
        const val STATUS_BAD = 28
        const val STATUS_NEUTRAL = 29
        const val NUMBER = 30
        const val NUMBER_BAND = 31
    }

    private fun cellReference(row: Int, column: Int): String {
        var index = column
        val letters = StringBuilder()
        while (index > 0) {
            index -= 1
            letters.append(('A'.code + index % 26).toChar())
            index /= 26
        }
        return letters.reverse().toString() + row
    }

    private const val XML_DECLARATION = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
}
