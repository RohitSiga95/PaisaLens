package com.paisalens.app.sms

import com.paisalens.app.data.model.ParsedCreditCardBill
import java.math.BigDecimal
import java.math.RoundingMode
import java.security.MessageDigest
import java.time.Instant
import java.time.LocalDate
import java.time.MonthDay
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeFormatterBuilder
import java.time.format.DateTimeParseException
import java.util.Locale

/** Parses statement-level credit-card dues without sending SMS contents off-device. */
class CreditCardBillSmsParser(
    private val zoneId: ZoneId = ZoneId.systemDefault(),
) {
    fun parse(
        sender: String,
        body: String,
        timestamp: Long,
        messageId: String? = null,
    ): ParsedCreditCardBill? {
        val normalized = body.lowercase(Locale.ROOT).replace(Regex("\\s+"), " ")
        if (!looksLikeUnpaidCardBill(normalized)) return null

        val totalDueMinor = extractAmount(body, TOTAL_DUE_PATTERNS) ?: return null
        if (totalDueMinor < 0) return null
        val dueDate = extractDueDate(body, timestamp) ?: return null
        val accountHint = extractAccountHint(body)
        val bankKey = BankSmsSupport.bankKey(sender) ?: BankSmsSupport.bankKey(body)
        val institutionName = bankKey?.let(BankSmsSupport::institutionName)
            ?: cleanSenderName(sender).ifBlank { "Credit card" }
        val senderIdentity = sender.lowercase(Locale.ROOT)
            .replace(Regex("[^a-z0-9]+"), "")
            .take(32)
            .ifBlank { "card" }
        // If the alert omits last-four digits, never collapse every card at the bank into one
        // identity. An exact normalized-body hash safely coalesces repeated copies of this alert;
        // the database upgrades it to an account identity when one unambiguous card is known.
        val identitySuffix = accountHint ?: "unidentified:${stableBodyIdentity(senderIdentity, normalized)}"
        val cardIdentityKey = "card:${bankKey ?: senderIdentity}:$identitySuffix"

        return ParsedCreditCardBill(
            sourceMessageId = messageId ?: stableId(sender, body, timestamp),
            cardIdentityKey = cardIdentityKey,
            accountHint = accountHint,
            institutionName = institutionName,
            totalDueMinor = totalDueMinor,
            minimumDueMinor = extractAmount(body, MINIMUM_DUE_PATTERNS),
            dueDateEpochDay = dueDate.toEpochDay(),
            detectedAt = timestamp,
            sender = sender.ifBlank { "Credit-card statement" }.take(64),
            rawMessage = body,
        )
    }

    private fun looksLikeUnpaidCardBill(text: String): Boolean {
        if (PAID_PHRASES.any(text::contains)) return false
        val hasDueAmount = listOf(
            "total amount due",
            "total amt due",
            "total due",
            "current amount due",
            "current due",
            "statement balance",
            "bill amount",
        ).any(text::contains)
        val hasDueDate = listOf("due date", "due on", "due by", "pay by", "payable by").any(text::contains)
        return hasDueAmount && hasDueDate
    }

    private fun extractAmount(body: String, patterns: List<Regex>): Long? {
        patterns.forEach { pattern ->
            val amount = pattern.find(body)?.groupValues?.getOrNull(1)
                ?.replace(",", "")
                ?.toBigDecimalOrNull()
                ?: return@forEach
            return runCatching {
                amount.multiply(BigDecimal(100)).setScale(0, RoundingMode.HALF_UP).longValueExact()
            }.getOrNull()
        }
        return null
    }

    private fun extractDueDate(body: String, timestamp: Long): LocalDate? {
        val rawDate = DUE_DATE_PATTERNS.firstNotNullOfOrNull { pattern ->
            pattern.find(body)?.groupValues?.getOrNull(1)
        }
            ?.trim(' ', '.', ',')
            ?.replace(ORDINAL_DAY_SUFFIX, "$1")
            ?: return null
        DATE_FORMATTERS.forEach { formatter ->
            try {
                return LocalDate.parse(rawDate, formatter)
            } catch (_: DateTimeParseException) {
                // Try the next supported bank date layout.
            }
        }
        MONTH_DAY_FORMATTERS.forEach { formatter ->
            try {
                val monthDay = MonthDay.parse(rawDate, formatter)
                val receivedDate = Instant.ofEpochMilli(timestamp).atZone(zoneId).toLocalDate()
                var candidate = monthDay.atYear(receivedDate.year)
                // A yearless date shortly before the SMS can be an overdue reminder. Only infer
                // the next year when the same-year date is implausibly far in the past, which
                // preserves genuine calendar rollovers such as a January due date sent in December.
                if (candidate.isBefore(receivedDate.minusDays(YEARLESS_DATE_ROLLOVER_DAYS))) {
                    candidate = candidate.plusYears(1)
                }
                return candidate
            } catch (_: DateTimeParseException) {
                // Try the next supported layout.
            }
        }
        return null
    }

    private fun extractAccountHint(body: String): String? = ACCOUNT_PATTERNS.firstNotNullOfOrNull { pattern ->
        pattern.find(body)?.groupValues?.getOrNull(1)?.filter(Char::isDigit)?.takeLast(4)
    }?.takeIf { it.length == 4 }

    private fun cleanSenderName(sender: String): String = sender
        .replace(Regex("(?i)^(?:AD|AX|BZ|JD|JM|VK|VM|TM|CP|BP|HP|QP)-"), "")
        .replace(Regex("[^A-Za-z0-9 ]"), " ")
        .replace(Regex("\\s+"), " ")
        .trim()
        .take(48)

    private fun stableId(sender: String, body: String, timestamp: Long): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest("bill|$sender|$body|$timestamp".toByteArray())
        return "bill-" + digest.take(12).joinToString("") { "%02x".format(it) }
    }

    private fun stableBodyIdentity(senderIdentity: String, normalizedBody: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest("$senderIdentity|$normalizedBody".toByteArray())
            .take(12)
            .joinToString("") { "%02x".format(it) }

    private companion object {
        private const val YEARLESS_DATE_ROLLOVER_DAYS = 180L
        private const val AMOUNT = "([0-9][0-9,]*(?:\\.[0-9]{1,2})?)"
        private const val CURRENCY = "(?:₹|INR|Rs\\.?)\\s*"

        val TOTAL_DUE_PATTERNS = listOf(
            Regex("(?i)\\b(?:total\\s+(?:amount|amt)?\\s*due|total\\s+due|current\\s+(?:amount\\s+)?due|statement\\s+balance|bill\\s+amount)\\b\\s*(?:is|of|:|-)?\\s*$CURRENCY$AMOUNT"),
            Regex("(?i)\\b$CURRENCY$AMOUNT\\s*(?:is\\s+)?(?:the\\s+)?(?:total\\s+(?:amount\\s+)?due|statement\\s+balance)\\b"),
        )
        val MINIMUM_DUE_PATTERNS = listOf(
            Regex("(?i)\\bminimum\\s+(?:amount\\s+)?due\\b\\s*(?:is|of|:|-)?\\s*$CURRENCY$AMOUNT"),
            Regex("(?i)\\bmin\\.?\\s+(?:amt\\.?\\s+)?due\\b\\s*(?:is|of|:|-)?\\s*$CURRENCY$AMOUNT"),
        )
        val DUE_DATE_PATTERNS = listOf(
            Regex("(?i)\\b(?:payment\\s+)?due\\s+date\\s*(?:is|on|:|-)?\\s*([0-9]{1,2}(?:st|nd|rd|th)?(?:[-/ ](?:[0-9]{1,2}|[A-Za-z]{3,9}))(?:[-/ ,]+[0-9]{2,4})?)"),
            Regex("(?i)\\b(?:payment\\s+)?due\\s+(?:on|by)\\s*([0-9]{1,2}(?:st|nd|rd|th)?(?:[-/ ](?:[0-9]{1,2}|[A-Za-z]{3,9}))(?:[-/ ,]+[0-9]{2,4})?)"),
            Regex("(?i)\\b(?:pay|payable)\\s+by\\s*([0-9]{1,2}(?:st|nd|rd|th)?(?:[-/ ](?:[0-9]{1,2}|[A-Za-z]{3,9}))(?:[-/ ,]+[0-9]{2,4})?)"),
        )
        val ACCOUNT_PATTERNS = listOf(
            Regex("(?i)\\b(?:credit\\s+)?card(?:\\s*(?:no\\.?|number|ending(?:\\s+in)?|ending))?\\s*[:.-]?\\s*[xX*#-]*(\\d{4})\\b"),
            Regex("(?i)\\b(?:ending|last)\\s+(?:in\\s+)?[xX*#-]*(\\d{4})\\b"),
        )
        val PAID_PHRASES = listOf(
            "payment received",
            "payment successful",
            "amount paid",
            "no amount due",
            "zero amount due",
        )
        val ORDINAL_DAY_SUFFIX = Regex("(?i)\\b([0-9]{1,2})(?:st|nd|rd|th)\\b")

        private fun formatter(pattern: String): DateTimeFormatter = DateTimeFormatterBuilder()
            .parseCaseInsensitive()
            .appendPattern(pattern)
            .toFormatter(Locale.ENGLISH)

        val DATE_FORMATTERS = listOf(
            "d-MMM-uuuu", "d-MMM-uu", "d/MM/uuuu", "d/MM/uu", "d-MM-uuuu", "d-MM-uu",
            "d MMM uuuu", "d MMM uu",
        ).map(::formatter)
        val MONTH_DAY_FORMATTERS = listOf("d MMM", "d-MMM", "d/MM", "d-MM").map(::formatter)
    }
}
