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
        val table = if (fileName.lowercase(Locale.ROOT).endsWith(".xlsx")) {
            readXlsx(bytes)
        } else {
            readDelimited(bytes.toString(Charsets.UTF_8))
        }
        if (table.size < 2) return StatementImportPreview(emptyList(), 0, listOf("No transaction rows were found"))
        val headers = table.first().map(::normalizeHeader)
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
                table.size - 1,
                listOf("Could not identify required column${if (missing.size == 1) "" else "s"}: ${missing.joinToString()}"),
            )
        }

        val base = baseCurrency.normalizedCurrency()
        val ratesByQuote = exchangeRates.filter { it.baseCurrency == base }.associateBy { it.quoteCurrency }
        var skipped = 0
        val warnings = linkedSetOf<String>()
        val rows = mutableListOf<StatementImportRow>()
        table.drop(1).forEachIndexed { index, columns ->
            fun cell(position: Int): String = if (position in columns.indices) columns[position].trim() else ""
            val rowNumber = index + 2
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
                return@forEachIndexed
            }
            val currency = cell(currencyIndex).ifBlank { base }.normalizedCurrency()
            val originalMinor = (abs(rawAmount) * 100).roundToLong()
            val rate = if (currency == base) 1.0 else ratesByQuote[currency]?.rate
            if (rate == null || !rate.isFinite() || rate <= 0) {
                skipped += 1
                warnings += "Skipped $currency rows because no cached $currency/$base rate is available; refresh travel rates first."
                return@forEachIndexed
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
        if (skipped > 0) warnings += "$skipped row${if (skipped == 1) " was" else "s were"} skipped because required values were missing or invalid."
        return StatementImportPreview(rows, skipped, warnings.toList())
    }

    private fun readDelimited(text: String): List<List<String>> {
        val firstLine = text.lineSequence().firstOrNull().orEmpty()
        val delimiter = listOf(',', '\t', ';').maxBy { firstLine.count { char -> char == it } }
        val records = mutableListOf<List<String>>()
        var row = mutableListOf<String>()
        val cell = StringBuilder()
        var quoted = false
        var index = 0
        while (index < text.length) {
            val char = text[index]
            when {
                char == '"' && quoted && index + 1 < text.length && text[index + 1] == '"' -> {
                    cell.append('"')
                    index += 1
                }
                char == '"' -> quoted = !quoted
                char == delimiter && !quoted -> {
                    row += cell.toString()
                    cell.clear()
                }
                (char == '\n' || char == '\r') && !quoted -> {
                    if (char == '\r' && index + 1 < text.length && text[index + 1] == '\n') index += 1
                    row += cell.toString()
                    cell.clear()
                    if (row.any { it.isNotBlank() }) records += row
                    row = mutableListOf()
                }
                else -> cell.append(char)
            }
            index += 1
        }
        if (cell.isNotEmpty() || row.isNotEmpty()) {
            row += cell.toString()
            if (row.any { it.isNotBlank() }) records += row
        }
        return records
    }

    private fun readXlsx(bytes: ByteArray): List<List<String>> {
        val entries = mutableMapOf<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                if (!entry.isDirectory && entry.name in setOf("xl/sharedStrings.xml", "xl/worksheets/sheet1.xml")) {
                    entries[entry.name] = zip.readLimitedBytes(MAX_FILE_BYTES)
                }
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }
        val sheet = entries["xl/worksheets/sheet1.xml"] ?: return emptyList()
        val shared = entries["xl/sharedStrings.xml"]?.let { xml ->
            parseXml(xml).getElementsByTagName("si").let { nodes ->
                List(nodes.length) { index ->
                    val item = nodes.item(index) as Element
                    item.getElementsByTagName("t").let { textNodes ->
                        (0 until textNodes.length).joinToString("") { textNodes.item(it).textContent }
                    }
                }
            }
        }.orEmpty()
        val rows = parseXml(sheet).getElementsByTagName("row")
        return List(rows.length) { rowIndex ->
            val cells = (rows.item(rowIndex) as Element).getElementsByTagName("c")
            val values = sortedMapOf<Int, String>()
            for (cellIndex in 0 until cells.length) {
                val cell = cells.item(cellIndex) as Element
                val column = cell.getAttribute("r").takeWhile(Char::isLetter).fold(0) { value, letter ->
                    value * 26 + (letter.uppercaseChar() - 'A' + 1)
                } - 1
                val raw = cell.getElementsByTagName("v").item(0)?.textContent
                    ?: cell.getElementsByTagName("t").item(0)?.textContent.orEmpty()
                values[column] = if (cell.getAttribute("t") == "s") shared.getOrNull(raw.toIntOrNull() ?: -1).orEmpty() else raw
            }
            List((values.keys.maxOrNull() ?: -1) + 1) { values[it].orEmpty() }
        }
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
}
