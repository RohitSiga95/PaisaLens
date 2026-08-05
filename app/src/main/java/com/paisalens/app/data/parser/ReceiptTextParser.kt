package com.paisalens.app.data.parser

import com.paisalens.app.data.model.ExpenseCategory
import com.paisalens.app.data.model.ReceiptOcrDraft
import java.math.BigDecimal
import java.math.RoundingMode

class ReceiptTextParser(
    private val categoryResolver: (String) -> ExpenseCategory = TransactionSmsParser()::categorize,
) {
    fun parse(text: String, sourceLabel: String): ReceiptOcrDraft {
        val cleanLines = text.lineSequence()
            .map { it.replace(Regex("\\s+"), " ").trim() }
            .filter(String::isNotBlank)
            .toList()
        val amountMinor = preferredTotal(cleanLines) ?: largestAmount(cleanLines)
        val merchant = cleanLines.firstOrNull(::isLikelyMerchant).orEmpty().take(48)
        val noteText = cleanLines
            .take(8)
            .joinToString(" · ")
            .take(130)

        return ReceiptOcrDraft(
            amountMinor = amountMinor,
            merchant = merchant,
            category = categoryResolver(text),
            note = "Scanned bill${noteText.takeIf(String::isNotBlank)?.let { ": $it" }.orEmpty()}".take(160),
            sourceLabel = sourceLabel,
        )
    }

    private fun preferredTotal(lines: List<String>): Long? = lines
        .asSequence()
        .filter { totalKeywords.any { keyword -> it.contains(keyword, ignoreCase = true) } }
        .filterNot { it.contains("subtotal", ignoreCase = true) || it.contains("tax total", ignoreCase = true) }
        .mapNotNull(::lastAmountMinor)
        .maxOrNull()

    private fun largestAmount(lines: List<String>): Long? = lines
        .asSequence()
        .flatMap { line -> amountPattern.findAll(line).mapNotNull { amountMinor(it.groupValues[1]) } }
        .maxOrNull()

    private fun lastAmountMinor(line: String): Long? = amountPattern.findAll(line)
        .mapNotNull { amountMinor(it.groupValues[1]) }
        .lastOrNull()

    private fun amountMinor(value: String): Long? = value
        .replace(",", "")
        .toBigDecimalOrNull()
        ?.multiply(BigDecimal(100))
        ?.setScale(0, RoundingMode.HALF_UP)
        ?.longValueExact()
        ?.takeIf { it in 1L..1_000_000_000L }

    private fun isLikelyMerchant(line: String): Boolean {
        val lower = line.lowercase()
        if (line.length !in 2..60 || line.count(Char::isLetter) < 2) return false
        if (ignoredMerchantLines.any(lower::contains)) return false
        if (amountPattern.containsMatchIn(line)) return false
        return true
    }

    private companion object {
        val amountPattern = Regex(
            "(?i)(?:₹|INR|Rs\\.?|Rupees?)?\\s*([0-9][0-9,]*(?:\\.[0-9]{1,2})?)",
        )
        val totalKeywords = listOf("grand total", "amount paid", "net amount", "total due", "total")
        val ignoredMerchantLines = listOf(
            "tax invoice", "invoice", "receipt", "gstin", "phone", "mobile", "thank you", "welcome",
        )
    }
}
