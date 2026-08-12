package com.paisalens.app.data.importer

import com.paisalens.app.data.model.ExchangeRate
import com.paisalens.app.data.model.ExpenseCategory
import com.paisalens.app.data.model.ReviewStatus
import com.paisalens.app.data.model.StatementImportPreview
import com.paisalens.app.data.model.StatementImportRow
import com.paisalens.app.data.model.TransactionRecord
import com.paisalens.app.data.model.TransactionSource
import com.paisalens.app.data.model.TransactionType
import com.paisalens.app.data.model.normalizedCurrency
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.security.MessageDigest
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.util.Locale
import java.util.zip.ZipInputStream
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.math.abs
import kotlin.math.roundToLong
import org.w3c.dom.Element

object StatementImporter {
    fun preview(
        input: InputStream,
        fileName: String,
        accountId: Long?,
        accountName: String?,
        baseCurrency: String = "INR",
        exchangeRates: List<ExchangeRate> = emptyList(),
    ): StatementImportPreview {
        val bytes = input.readLimitedBytes(MAX_FILE_BYTES)
        val tableRead = if (fileName.lowercase(Locale.ROOT).endsWith(".xlsx")) {
            readXlsx(bytes)
        } else {
            readDelimited(bytes.toString(Charsets.UTF_8))
        }
        val table = tableRead.rows
        if (table.size < 2) {
            return StatementImportPreview(
                emptyList(),
                tableRead.truncatedRows,
                (listOf("No transaction rows were found") + tableRead.warnings()).distinct(),
            )
        }
        val headerIndex = (0 until minOf(table.size, StatementTableLimits.MAX_HEADER_SCAN_ROWS)).firstOrNull { candidate ->
            val candidateHeaders = table[candidate].map(::normalizeHeader)
            candidateHeaders.any { it in DATE_HEADERS } &&
                candidateHeaders.any { it in DESCRIPTION_HEADERS } &&
                candidateHeaders.any { it in DEBIT_HEADERS || it in CREDIT_HEADERS || it in AMOUNT_HEADERS }
        }
        if (headerIndex == null) {
            return StatementImportPreview(
                emptyList(),
                (table.size - 1).coerceAtLeast(0) + tableRead.truncatedRows,
                (listOf("Could not identify required columns: date, description/narration, and amount or debit/credit") +
                    tableRead.warnings()).distinct(),
            )
        }
        val headers = table[headerIndex].map(::normalizeHeader)
        val dateIndex = headers.indexOfFirst { it in DATE_HEADERS }
        val descriptionIndex = headers.indexOfFirst { it in DESCRIPTION_HEADERS }
        val debitIndex = headers.indexOfFirst { it in DEBIT_HEADERS }
        val creditIndex = headers.indexOfFirst { it in CREDIT_HEADERS }
        val amountIndex = headers.indexOfFirst { it in AMOUNT_HEADERS }
        val typeIndex = headers.indexOfFirst { it in TYPE_HEADERS }
        val currencyIndex = headers.indexOfFirst { it in CURRENCY_HEADERS }
        val noteIndex = headers.indexOfFirst { it in NOTE_HEADERS }
        val missing = buildList {
            if (dateIndex < 0) add("date")
            if (descriptionIndex < 0) add("description/narration")
            if (debitIndex < 0 && creditIndex < 0 && amountIndex < 0) add("amount or debit/credit")
        }
        if (missing.isNotEmpty()) {
            return StatementImportPreview(
                emptyList(),
                (table.size - headerIndex - 1).coerceAtLeast(0) + tableRead.truncatedRows,
                (listOf("Could not identify required column${if (missing.size == 1) "" else "s"}: ${missing.joinToString()}") +
                    tableRead.warnings()).distinct(),
            )
        }

        val base = baseCurrency.normalizedCurrency()
        val ratesByQuote = exchangeRates.filter { it.baseCurrency == base }.associateBy { it.quoteCurrency }
        var skipped = tableRead.truncatedRows
        val warnings = linkedSetOf<String>().apply { addAll(tableRead.warnings()) }
        val rows = mutableListOf<StatementImportRow>()
        for (tableIndex in headerIndex + 1 until table.size) {
            val columns = table[tableIndex]
            fun cell(position: Int): String = if (position in columns.indices) columns[position].trim() else ""
            val rowNumber = tableIndex + 1
            val date = parseDate(cell(dateIndex))
            val merchant = cell(descriptionIndex).replace(Regex("\\s+"), " ").take(96)
            val debit = parseAmount(cell(debitIndex))
            val credit = parseAmount(cell(creditIndex))
            val signedAmount = parseAmount(cell(amountIndex))
            val typeText = cell(typeIndex).lowercase(Locale.ROOT)
            val type = when {
                credit != null && credit != 0.0 -> TransactionType.INCOME
                debit != null && debit != 0.0 -> TransactionType.EXPENSE
                typeText.contains("credit") || typeText == "cr" || typeText.contains("deposit") -> TransactionType.INCOME
                signedAmount != null && signedAmount > 0 && typeText.contains("income") -> TransactionType.INCOME
                else -> TransactionType.EXPENSE
            }
            val rawAmount = when (type) {
                TransactionType.INCOME -> credit ?: signedAmount
                else -> debit ?: signedAmount
            }
            if (date == null || merchant.isBlank() || rawAmount == null || rawAmount == 0.0) {
                skipped += 1
                continue
            }
            val currency = cell(currencyIndex).ifBlank { base }.normalizedCurrency()
            val originalMinor = (abs(rawAmount) * 100).roundToLong()
            val rate = if (currency == base) 1.0 else ratesByQuote[currency]?.rate
            if (rate == null || !rate.isFinite() || rate <= 0) {
                skipped += 1
                warnings += "Skipped $currency rows because no cached $currency/$base rate is available; refresh travel rates first."
                continue
            }
            val amountMinor = (originalMinor * rate).roundToLong()
            val occurredAt = date.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
            val rowHash = sha256("$date|$merchant|$originalMinor|$currency|$type|${accountId ?: 0}")
            val needsCategory = type == TransactionType.EXPENSE
            val transaction = TransactionRecord(
                sourceMessageId = "statement-$rowHash",
                amountMinor = amountMinor,
                merchant = merchant,
                accountHint = null,
                category = if (type == TransactionType.INCOME) ExpenseCategory.INCOME else ExpenseCategory.OTHER,
                type = type,
                occurredAt = occurredAt,
                source = TransactionSource.STATEMENT,
                sender = "Imported from ${fileName.take(64)}",
                note = cell(noteIndex).take(160).takeIf(String::isNotBlank),
                accountId = accountId,
                accountName = accountName,
                reviewStatus = if (needsCategory) ReviewStatus.NEEDS_REVIEW else ReviewStatus.CONFIRMED,
                reviewReason = if (needsCategory) "Imported statement: confirm category" else null,
                originalAmountMinor = originalMinor.takeIf { currency != base },
                originalCurrency = currency.takeIf { currency != base },
                exchangeRate = rate.takeIf { currency != base },
            )
            rows += StatementImportRow(rowNumber, transaction)
        }
        val invalidRows = skipped - tableRead.truncatedRows
        if (invalidRows > 0) {
            warnings += "$invalidRows row${if (invalidRows == 1) " was" else "s were"} skipped because required values were missing or invalid."
        }
        return StatementImportPreview(rows, skipped, warnings.toList())
    }

    private data class BoundedTable(
        val rows: List<List<String>>,
        val truncatedRows: Int = 0,
        val truncatedColumns: Boolean = false,
        val truncatedCells: Boolean = false,
        val truncatedSharedStrings: Boolean = false,
    ) {
        fun warnings(): List<String> = buildList {
            if (truncatedRows > 0) add(StatementTableLimits.ROW_LIMIT_WARNING)
            if (truncatedColumns) add(StatementTableLimits.COLUMN_LIMIT_WARNING)
            if (truncatedCells) add(StatementTableLimits.CELL_LIMIT_WARNING)
            if (truncatedSharedStrings) add("The XLSX shared-text table exceeded its safety limit; affected rows were skipped.")
        }
    }

    private fun readDelimited(text: String): BoundedTable {
        val sampleLines = text.lineSequence().take(StatementTableLimits.MAX_HEADER_SCAN_ROWS).toList()
        val delimiter = listOf(',', '\t', ';').maxBy { candidate ->
            sampleLines.maxOfOrNull { line -> line.count { it == candidate } } ?: 0
        }
        val records = mutableListOf<List<String>>()
        var row = mutableListOf<String>()
        val cell = StringBuilder()
        var quoted = false
        var cellWasTruncated = false
        var truncatedRows = 0
        var truncatedColumns = false
        var truncatedCells = false

        fun finishCell() {
            if (row.size < StatementTableLimits.MAX_COLUMNS) {
                row += cell.toString()
            } else {
                truncatedColumns = true
            }
            if (cellWasTruncated) truncatedCells = true
            cell.clear()
            cellWasTruncated = false
        }

        fun finishRow() {
            finishCell()
            if (row.any { it.isNotBlank() }) {
                if (records.size < StatementTableLimits.MAX_ROWS) {
                    records += row
                } else {
                    truncatedRows += 1
                }
            }
            row = mutableListOf()
        }

        fun append(character: Char) {
            if (cell.length < StatementTableLimits.MAX_CELL_CHARACTERS) {
                cell.append(character)
            } else {
                cellWasTruncated = true
            }
        }

        var index = 0
        while (index < text.length) {
            val char = text[index]
            when {
                char == '"' && quoted && index + 1 < text.length && text[index + 1] == '"' -> {
                    append('"')
                    index += 1
                }
                char == '"' -> quoted = !quoted
                char == delimiter && !quoted -> {
                    finishCell()
                }
                (char == '\n' || char == '\r') && !quoted -> {
                    if (char == '\r' && index + 1 < text.length && text[index + 1] == '\n') index += 1
                    finishRow()
                }
                else -> append(char)
            }
            index += 1
        }
        if (cell.isNotEmpty() || row.isNotEmpty()) {
            finishRow()
        }
        return BoundedTable(records, truncatedRows, truncatedColumns, truncatedCells)
    }

    private fun readXlsx(bytes: ByteArray): BoundedTable {
        val entries = mutableMapOf<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                if (!entry.isDirectory && entry.name in setOf("xl/sharedStrings.xml", "xl/worksheets/sheet1.xml")) {
                    entries[entry.name] = zip.readLimitedBytes(MAX_XML_ENTRY_BYTES)
                }
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }
        val sheet = entries["xl/worksheets/sheet1.xml"] ?: return BoundedTable(emptyList())
        var truncatedCells = false
        var truncatedSharedStrings = false
        val shared = entries["xl/sharedStrings.xml"]?.let { xml ->
            parseXml(xml).getElementsByTagName("si").let { nodes ->
                if (nodes.length > StatementTableLimits.MAX_SHARED_STRINGS) truncatedSharedStrings = true
                List(minOf(nodes.length, StatementTableLimits.MAX_SHARED_STRINGS)) { index ->
                    val item = nodes.item(index) as Element
                    item.getElementsByTagName("t").let { textNodes ->
                        val value = buildString {
                            for (textIndex in 0 until textNodes.length) {
                                val remaining = StatementTableLimits.MAX_CELL_CHARACTERS - length
                                if (remaining <= 0) {
                                    truncatedCells = true
                                    break
                                }
                                val text = textNodes.item(textIndex).textContent.orEmpty()
                                append(text.take(remaining))
                                if (text.length > remaining) truncatedCells = true
                            }
                        }
                        value
                    }
                }
            }
        }.orEmpty()
        val rowNodes = parseXml(sheet).getElementsByTagName("row")
        val rowCount = minOf(rowNodes.length, StatementTableLimits.MAX_ROWS)
        var truncatedColumns = false
        val parsedRows = List(rowCount) { rowIndex ->
            val cells = (rowNodes.item(rowIndex) as Element).getElementsByTagName("c")
            val values = sortedMapOf<Int, String>()
            if (cells.length > StatementTableLimits.MAX_CELLS_SCANNED_PER_ROW) truncatedColumns = true
            for (cellIndex in 0 until minOf(cells.length, StatementTableLimits.MAX_CELLS_SCANNED_PER_ROW)) {
                val cell = cells.item(cellIndex) as Element
                val reference = cell.getAttribute("r")
                val column = if (reference.isBlank()) {
                    cellIndex.takeIf { it < StatementTableLimits.MAX_COLUMNS }
                } else {
                    parseColumnIndex(reference)
                }
                if (column == null || column !in 0 until StatementTableLimits.MAX_COLUMNS) {
                    truncatedColumns = true
                    continue
                }
                val raw = cell.getElementsByTagName("v").item(0)?.textContent
                    ?: cell.getElementsByTagName("t").item(0)?.textContent.orEmpty()
                val value = if (cell.getAttribute("t") == "s") {
                    val sharedIndex = raw.toIntOrNull() ?: -1
                    shared.getOrNull(sharedIndex).orEmpty().also {
                        if (sharedIndex >= shared.size) truncatedSharedStrings = true
                    }
                } else {
                    raw
                }
                if (value.length > StatementTableLimits.MAX_CELL_CHARACTERS) truncatedCells = true
                values[column] = value.take(StatementTableLimits.MAX_CELL_CHARACTERS)
            }
            List((values.keys.maxOrNull() ?: -1) + 1) { values[it].orEmpty() }
        }
        return BoundedTable(
            rows = parsedRows,
            truncatedRows = (rowNodes.length - rowCount).coerceAtLeast(0),
            truncatedColumns = truncatedColumns,
            truncatedCells = truncatedCells,
            truncatedSharedStrings = truncatedSharedStrings,
        )
    }

    private fun parseColumnIndex(reference: String): Int? {
        val letters = reference.takeWhile(Char::isLetter)
        if (letters.isBlank()) return null
        var oneBased = 0
        for (letter in letters) {
            val digit = letter.uppercaseChar() - 'A' + 1
            if (digit !in 1..26 || oneBased > StatementTableLimits.MAX_COLUMNS) return null
            oneBased = oneBased * 26 + digit
            if (oneBased > StatementTableLimits.MAX_COLUMNS) return null
        }
        return oneBased - 1
    }

    private fun parseXml(bytes: ByteArray) = DocumentBuilderFactory.newInstance().apply {
        setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        setFeature("http://xml.org/sax/features/external-general-entities", false)
        setFeature("http://xml.org/sax/features/external-parameter-entities", false)
        runCatching { setAttribute("http://javax.xml.XMLConstants/property/accessExternalDTD", "") }
        runCatching { setAttribute("http://javax.xml.XMLConstants/property/accessExternalSchema", "") }
        isXIncludeAware = false
        isExpandEntityReferences = false
    }.newDocumentBuilder().parse(ByteArrayInputStream(bytes))

    private fun normalizeHeader(value: String): String = value
        .replace("\uFEFF", "")
        .trim()
        .lowercase(Locale.ROOT)
        .replace(Regex("[^a-z0-9]+"), " ")
        .trim()

    private fun parseAmount(value: String): Double? {
        if (value.isBlank()) return null
        val negative = value.contains('(') && value.contains(')') || value.trim().startsWith('-')
        val clean = value.replace(Regex("[^0-9.]"), "")
        return clean.toDoubleOrNull()?.let { if (negative) -it else it }
    }

    private fun parseDate(value: String): LocalDate? {
        if (value.isBlank()) return null
        value.toDoubleOrNull()?.let { serial ->
            if (serial in 1.0..100_000.0) return LocalDate.of(1899, 12, 30).plusDays(serial.toLong())
        }
        DATE_FORMATS.forEach { formatter ->
            try {
                return LocalDate.parse(value.trim(), formatter)
            } catch (_: DateTimeParseException) {
                try {
                    return LocalDateTime.parse(value.trim(), formatter).toLocalDate()
                } catch (_: DateTimeParseException) {
                    Unit
                }
            }
        }
        return null
    }

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray())
        .joinToString("") { "%02x".format(it) }

    private fun InputStream.readLimitedBytes(limit: Int): ByteArray {
        val output = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(8_192)
        var total = 0
        while (true) {
            val read = read(buffer)
            if (read < 0) break
            total += read
            require(total <= limit) { "Statement file is too large" }
            output.write(buffer, 0, read)
        }
        return output.toByteArray()
    }

    private val DATE_HEADERS = setOf("date", "transaction date", "txn date", "value date", "posting date")
    private val DESCRIPTION_HEADERS = setOf("description", "narration", "merchant", "particulars", "details", "transaction details", "remarks")
    private val DEBIT_HEADERS = setOf("debit", "debit amount", "withdrawal", "withdrawal amount", "dr amount")
    private val CREDIT_HEADERS = setOf("credit", "credit amount", "deposit", "deposit amount", "cr amount")
    private val AMOUNT_HEADERS = setOf("amount", "transaction amount", "txn amount")
    private val TYPE_HEADERS = setOf("type", "transaction type", "dr cr", "debit credit")
    private val CURRENCY_HEADERS = setOf("currency", "currency code", "ccy")
    private val NOTE_HEADERS = setOf("note", "notes", "memo")
    private val DATE_FORMATS = listOf(
        DateTimeFormatter.ISO_LOCAL_DATE,
        DateTimeFormatter.ISO_LOCAL_DATE_TIME,
        DateTimeFormatter.ofPattern("d/M/uuuu"),
        DateTimeFormatter.ofPattern("d-M-uuuu"),
        DateTimeFormatter.ofPattern("d MMM uuuu", Locale.ENGLISH),
        DateTimeFormatter.ofPattern("dd-MMM-uuuu", Locale.ENGLISH),
        DateTimeFormatter.ofPattern("M/d/uuuu"),
    )
    private const val MAX_FILE_BYTES = 20 * 1024 * 1024
    private const val MAX_XML_ENTRY_BYTES = 8 * 1024 * 1024
}
