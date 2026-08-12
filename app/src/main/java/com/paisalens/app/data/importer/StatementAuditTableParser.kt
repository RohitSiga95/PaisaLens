package com.paisalens.app.data.importer

import com.paisalens.app.data.model.StatementAmountDirection
import com.paisalens.app.data.model.StatementAuditParseResult
import com.paisalens.app.data.model.StatementAuditRow
import com.paisalens.app.data.model.StatementImportRow
import com.paisalens.app.data.model.TransactionType
import com.paisalens.app.data.model.normalizedCurrency
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.util.Locale

/** Converts already-extracted CSV/XLSX cells into deterministic, reviewable audit rows. */
object StatementAuditTableParser {
    fun parse(
        table: List<List<String>>,
        zoneId: ZoneId = ZoneId.systemDefault(),
        defaultCurrency: String = "INR",
    ): StatementAuditParseResult {
        if (table.isEmpty()) return StatementAuditParseResult(emptyList(), 0, listOf("The statement table is empty."))
        val boundedRowCount = minOf(table.size, StatementTableLimits.MAX_ROWS)
        var clippedColumns = false
        var clippedCells = false
        fun boundedRow(index: Int): List<String> = table[index]
            .also { if (it.size > StatementTableLimits.MAX_COLUMNS) clippedColumns = true }
            .take(StatementTableLimits.MAX_COLUMNS)
            .map { value ->
                if (value.length > StatementTableLimits.MAX_CELL_CHARACTERS) clippedCells = true
                value.take(StatementTableLimits.MAX_CELL_CHARACTERS)
            }
        val headerIndex = (0 until minOf(boundedRowCount, StatementTableLimits.MAX_HEADER_SCAN_ROWS)).firstOrNull { candidate ->
            val headers = boundedRow(candidate).map(::normalizeHeader)
            headers.any { it in DATE_HEADERS } &&
                headers.any { it in DESCRIPTION_HEADERS } &&
                headers.any { it in DEBIT_HEADERS || it in CREDIT_HEADERS || it in AMOUNT_HEADERS }
        } ?: return StatementAuditParseResult(
            rows = emptyList(),
            skippedRows = (table.size - 1).coerceAtLeast(0),
            warnings = buildList {
                add("Could not identify date, description, and amount columns. Use the structured manual fallback.")
                if (table.size > StatementTableLimits.MAX_ROWS) add(StatementTableLimits.ROW_LIMIT_WARNING)
                if (clippedColumns) add(StatementTableLimits.COLUMN_LIMIT_WARNING)
                if (clippedCells) add(StatementTableLimits.CELL_LIMIT_WARNING)
            },
        )

        val headers = boundedRow(headerIndex).map(::normalizeHeader)
        val dateIndex = headers.indexOfFirst { it in DATE_HEADERS }
        val descriptionIndex = headers.indexOfFirst { it in DESCRIPTION_HEADERS }
        val debitIndex = headers.indexOfFirst { it in DEBIT_HEADERS }
        val creditIndex = headers.indexOfFirst { it in CREDIT_HEADERS }
        val amountIndex = headers.indexOfFirst { it in AMOUNT_HEADERS }
        val typeIndex = headers.indexOfFirst { it in TYPE_HEADERS }
        val currencyIndex = headers.indexOfFirst { it in CURRENCY_HEADERS }
        val rows = mutableListOf<StatementAuditRow>()
        val warnings = linkedSetOf<String>()
        val truncatedRows = (table.size - boundedRowCount).coerceAtLeast(0)
        var skipped = 0

        for (tableIndex in headerIndex + 1 until boundedRowCount) {
            val columns = boundedRow(tableIndex)
            val rowNumber = tableIndex + 1
            fun cell(index: Int): String = columns.getOrNull(index)?.trim().orEmpty()
            val date = parseDate(cell(dateIndex))
            val description = cell(descriptionIndex).replace(Regex("\\s+"), " ").take(160)
            val debit = parseAmount(cell(debitIndex))
            val credit = parseAmount(cell(creditIndex))
            val signed = parseAmount(cell(amountIndex))
            if (debit != null && debit.absoluteMinor > 0 && credit != null && credit.absoluteMinor > 0) {
                skipped += 1
                warnings += "Row $rowNumber has both debit and credit values and needs manual review."
                continue
            }
            val typeText = cell(typeIndex).lowercase(Locale.ROOT)
            val amount = debit?.takeIf { it.absoluteMinor > 0 }
                ?: credit?.takeIf { it.absoluteMinor > 0 }
                ?: signed?.takeIf { it.absoluteMinor > 0 }
            val direction = when {
                debit != null && debit.absoluteMinor > 0 -> StatementAmountDirection.DEBIT
                credit != null && credit.absoluteMinor > 0 -> StatementAmountDirection.CREDIT
                amount?.creditMarker == true -> StatementAmountDirection.CREDIT
                amount?.debitMarker == true -> StatementAmountDirection.DEBIT
                typeText.indicatesCredit() -> StatementAmountDirection.CREDIT
                typeText.indicatesDebit() -> StatementAmountDirection.DEBIT
                amount?.negative == true -> StatementAmountDirection.CREDIT
                else -> StatementAmountDirection.DEBIT
            }
            if (date == null || description.isBlank() || amount == null || amount.absoluteMinor <= 0) {
                skipped += 1
                continue
            }
            rows += StatementAuditRow(
                rowNumber = rowNumber,
                occurredAt = date.atStartOfDay(zoneId).toInstant().toEpochMilli(),
                description = description,
                amountMinor = amount.absoluteMinor,
                direction = direction,
                currency = cell(currencyIndex).ifBlank { defaultCurrency }.normalizedCurrency(),
                sourceReference = "table-row-$rowNumber",
            )
        }
        if (truncatedRows > 0) warnings += StatementTableLimits.ROW_LIMIT_WARNING
        if (clippedColumns) warnings += StatementTableLimits.COLUMN_LIMIT_WARNING
        if (clippedCells) warnings += StatementTableLimits.CELL_LIMIT_WARNING
        if (skipped > 0) {
            warnings += "$skipped table row${if (skipped == 1) " was" else "s were"} skipped because required values were missing, invalid, or ambiguous."
        }
        return StatementAuditParseResult(rows, skipped + truncatedRows, warnings.toList())
    }

    private data class ParsedAmount(
        val absoluteMinor: Long,
        val negative: Boolean,
        val creditMarker: Boolean,
        val debitMarker: Boolean,
    )

    private fun parseAmount(value: String): ParsedAmount? {
        if (value.isBlank()) return null
        val normalized = value.trim().lowercase(Locale.ROOT)
        val negative = (normalized.contains('(') && normalized.contains(')')) || normalized.startsWith('-')
        val creditMarker = Regex("(?:^|\\s)(?:cr|credit)(?:\\s|$)").containsMatchIn(normalized)
        val debitMarker = Regex("(?:^|\\s)(?:dr|debit)(?:\\s|$)").containsMatchIn(normalized)
        val decimal = normalized
            .replace(",", "")
            .replace(Regex("[^0-9.]"), "")
            .takeIf { it.count { character -> character == '.' } <= 1 }
            ?.toBigDecimalOrNull()
            ?: return null
        val minor = decimal.abs().multiply(BigDecimal(100)).setScale(0, RoundingMode.HALF_UP).longValueExactOrNull()
            ?: return null
        return ParsedAmount(minor, negative, creditMarker, debitMarker)
    }

    private fun BigDecimal.longValueExactOrNull(): Long? = runCatching { longValueExact() }.getOrNull()

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

    private fun normalizeHeader(value: String): String = value
        .replace("\uFEFF", "")
        .trim()
        .lowercase(Locale.ROOT)
        .replace(Regex("[^a-z0-9]+"), " ")
        .trim()

    private fun String.indicatesCredit(): Boolean = contains("credit") || this == "cr" ||
        contains("refund") || contains("reversal") || contains("payment")

    private fun String.indicatesDebit(): Boolean = contains("debit") || this == "dr" || contains("purchase")

    private val DATE_HEADERS = setOf("date", "transaction date", "txn date", "value date", "posting date")
    private val DESCRIPTION_HEADERS = setOf("description", "narration", "merchant", "particulars", "details", "transaction details", "remarks")
    private val DEBIT_HEADERS = setOf("debit", "debit amount", "withdrawal", "withdrawal amount", "dr amount")
    private val CREDIT_HEADERS = setOf("credit", "credit amount", "deposit", "deposit amount", "cr amount")
    private val AMOUNT_HEADERS = setOf("amount", "transaction amount", "txn amount")
    private val TYPE_HEADERS = setOf("type", "transaction type", "dr cr", "debit credit")
    private val CURRENCY_HEADERS = setOf("currency", "currency code", "ccy")
    private val DATE_FORMATS = listOf(
        DateTimeFormatter.ISO_LOCAL_DATE,
        DateTimeFormatter.ISO_LOCAL_DATE_TIME,
        DateTimeFormatter.ofPattern("d/M/uuuu"),
        DateTimeFormatter.ofPattern("d-M-uuuu"),
        DateTimeFormatter.ofPattern("d MMM uuuu", Locale.ENGLISH),
        DateTimeFormatter.ofPattern("dd-MMM-uuuu", Locale.ENGLISH),
        DateTimeFormatter.ofPattern("M/d/uuuu"),
    )
}

internal object StatementTableLimits {
    const val MAX_ROWS = 10_000
    const val MAX_COLUMNS = 64
    const val MAX_CELL_CHARACTERS = 2_048
    const val MAX_HEADER_SCAN_ROWS = 25
    const val MAX_SHARED_STRINGS = 50_000
    const val MAX_CELLS_SCANNED_PER_ROW = MAX_COLUMNS * 4

    const val ROW_LIMIT_WARNING = "Only the first 10,000 table rows were processed for safety."
    const val COLUMN_LIMIT_WARNING = "Columns after the first 64 were ignored for safety."
    const val CELL_LIMIT_WARNING = "Very long cell text was shortened for safety."
}

/** Adapter for the rows already produced by [StatementImporter]. */
object StatementAuditRows {
    fun fromImported(
        rows: List<StatementImportRow>,
        currency: String = "INR",
    ): List<StatementAuditRow> = rows.map { imported ->
        val transaction = imported.transaction
        StatementAuditRow(
            rowNumber = imported.rowNumber,
            occurredAt = transaction.occurredAt,
            description = transaction.merchant,
            amountMinor = transaction.amountMinor,
            direction = when (transaction.type) {
                TransactionType.INCOME, TransactionType.REFUND -> StatementAmountDirection.CREDIT
                TransactionType.EXPENSE, TransactionType.TRANSFER -> StatementAmountDirection.DEBIT
            },
            currency = currency.normalizedCurrency(),
            accountId = transaction.accountId,
            accountHint = transaction.accountHint,
            sourceReference = transaction.sourceMessageId,
        )
    }
}
