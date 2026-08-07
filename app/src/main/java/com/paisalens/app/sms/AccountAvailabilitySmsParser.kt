package com.paisalens.app.sms

import com.paisalens.app.data.model.AccountAvailabilityUpdate
import com.paisalens.app.data.model.AccountType
import java.math.BigDecimal
import java.math.RoundingMode

class AccountAvailabilitySmsParser {
    fun parse(sender: String, body: String, timestamp: Long): AccountAvailabilityUpdate? {
        val senderBankKey = BankSmsSupport.bankKey(sender)
        val bankKey = senderBankKey ?: BankSmsSupport.bankKey(body) ?: senderBankFallback(sender)
        val availableCreditMinor = findAmount(body, availableCreditPatterns)
        val creditLimitMinor = findAmount(body, creditLimitPatterns)
        val senderBalancePatterns = if (senderBankKey == "hdfc") {
            balancePatterns + hdfcDailyBalancePatterns
        } else {
            balancePatterns
        }
        val balanceMinor = if (availableCreditMinor == null) {
            findAmount(body, senderBalancePatterns)
        } else {
            null
        }
        if (balanceMinor == null && availableCreditMinor == null && creditLimitMinor == null) return null

        val type = if (availableCreditMinor != null || creditLimitMinor != null) {
            AccountType.CREDIT_CARD
        } else {
            AccountType.BANK_ACCOUNT
        }
        return AccountAvailabilityUpdate(
            bankKey = bankKey,
            institutionName = BankSmsSupport.institutionName(bankKey),
            accountType = type,
            accountHint = accountHintPattern.find(body)?.groupValues?.getOrNull(1)
                ?.filter(Char::isDigit)
                ?.takeLast(4)
                ?.takeIf(String::isNotBlank),
            balanceMinor = balanceMinor,
            availableCreditMinor = availableCreditMinor,
            creditLimitMinor = creditLimitMinor,
            fetchedAt = timestamp,
            sender = sender.ifBlank { "Balance alert" },
        )
    }

    private fun findAmount(body: String, patterns: List<Regex>): Long? {
        patterns.forEach { pattern ->
            val raw = pattern.find(body)?.groupValues?.getOrNull(1) ?: return@forEach
            val value = raw.replace(",", "").toBigDecimalOrNull() ?: return@forEach
            return value.multiply(BigDecimal(100))
                .setScale(0, RoundingMode.HALF_UP)
                .longValueExact()
        }
        return null
    }

    private fun senderBankFallback(sender: String): String = sender
        .replace(Regex("(?i)^(?:AD|AX|BZ|JD|JM|VK|VM|TM|CP|BP|HP|QP)-"), "")
        .replace(Regex("[^A-Za-z0-9]"), "")
        .lowercase()
        .ifBlank { "bank" }

    private companion object {
        private const val CURRENCY = "(?:₹|INR|Rs\\.?|Rupees?)"
        private const val AMOUNT = "([0-9][0-9,]*(?:\\.[0-9]{1,2})?)"

        val availableCreditPatterns = listOf(
            Regex("(?i)(?:available|avail\\.?|avl)\\s+(?:credit|purchase)(?:\\s+(?:limit|balance))?[^0-9₹]{0,24}$CURRENCY\\s*$AMOUNT"),
            Regex("(?i)(?:available|avail\\.?|avl)\\s+(?:credit|purchase)(?:\\s+(?:limit|balance))?[^0-9]{0,24}$AMOUNT\\s*(?:INR|Rupees?)"),
            Regex("(?i)(?:credit|purchase)\\s+(?:limit|balance)\\s+(?:available|avail\\.?|avl)[^0-9₹]{0,24}$CURRENCY\\s*$AMOUNT"),
        )

        val creditLimitPatterns = listOf(
            Regex("(?i)(?:total|overall|sanctioned)\\s+(?:card\\s+)?credit\\s+limit[^0-9₹]{0,24}$CURRENCY\\s*$AMOUNT"),
            Regex("(?i)(?:total|overall|sanctioned)\\s+(?:card\\s+)?credit\\s+limit[^0-9]{0,24}$AMOUNT\\s*(?:INR|Rupees?)"),
            Regex("(?i)your\\s+(?:card\\s+)?credit\\s+limit(?:\\s+(?:is|of))?[^0-9₹]{0,24}$CURRENCY\\s*$AMOUNT"),
        )

        val balancePatterns = listOf(
            Regex("(?i)(?:(?:available|avail\\.?|avl|current|closing|ledger)\\s+(?:a/c\\s+|account\\s+)?bal(?:ance)?|bal(?:ance)?\\s+(?:available|is))[^0-9₹]{0,24}$CURRENCY\\s*$AMOUNT"),
            Regex("(?i)(?:(?:available|avail\\.?|avl|current|closing|ledger)\\s+(?:a/c\\s+|account\\s+)?bal(?:ance)?|bal(?:ance)?\\s+(?:available|is))[^0-9]{0,24}$AMOUNT\\s*(?:INR|Rupees?)"),
        )

        // VM-HDFCBK-S daily alerts can place the masked account and an as-of timestamp
        // between "Available Balance" and the amount, so keep the relaxed gap HDFC-only.
        val hdfcDailyBalancePatterns = listOf(
            Regex("(?is)(?:available|avail\\.?|avl)\\s+bal(?:ance)?\\.?\\b.{0,140}?$CURRENCY\\s*$AMOUNT"),
            Regex("(?is)bal(?:ance)?\\s+(?:in|for|of)\\s+(?:your\\s+)?(?:a/c|acct|account)\\b.{0,140}?$CURRENCY\\s*$AMOUNT"),
        )

        val accountHintPattern = Regex(
            "(?i)(?:a/c|acct|account|card)(?:\\s*(?:no\\.?|number|ending|xx|x{2,}))?\\s*[:.-]?\\s*([xX*0-9-]{3,24})",
        )
    }
}
