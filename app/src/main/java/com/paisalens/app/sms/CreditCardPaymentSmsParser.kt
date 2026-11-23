package com.paisalens.app.sms

import com.paisalens.app.data.model.CreditCardPayment
import java.security.MessageDigest
import java.util.Locale

/** Recognises payment acknowledgements with an issuer, card suffix and explicit INR payment amount. */
object CreditCardPaymentSmsParser {
    fun parse(sender: String, body: String, timestamp: Long): CreditCardPayment? {
        if (timestamp <= 0) return null
        val issuer = BankSmsSupport.bankKey(sender) ?: return null
        val normalized = body.trim().lowercase(Locale.ROOT).replace(SPACE, " ")
        if (NON_PAYMENTS.containsMatchIn(normalized)) return null
        val cards = CARD.findAll(normalized).toList()
        if (cards.any { CARD_USED_FOR_PAYMENT.containsMatchIn(normalized.substring(0, it.range.first)) }) return null
        val card = cards.map { it.groupValues[1] }.distinct().singleOrNull() ?: return null
        val amount = PAYMENT_AMOUNTS.firstNotNullOfOrNull { it.find(normalized)?.groupValues?.get(1) }
            ?.replace(",", "")
            ?.toBigDecimalOrNull()
            ?.movePointRight(2)
            ?.let { runCatching(it::longValueExact).getOrNull() }
            ?.takeIf { it > 0 }
            ?: return null
        val reference = REFERENCE.find(normalized)?.groupValues?.get(1)?.takeIf { it.any(Char::isDigit) }
        val bodyHash = if (reference == null) digest("$issuer|$card|$normalized") else null
        val key = if (reference == null) digest("sms|$bodyHash|$timestamp")
            else digest("ref|$issuer|$card|$reference")
        return CreditCardPayment(
            key = key,
            institutionName = BankSmsSupport.institutionName(issuer),
            cardLastFour = card,
            amountMinor = amount,
            receivedAt = timestamp,
            unreferencedMessageHash = bodyHash,
        )
    }

    private fun digest(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

    private val SPACE = Regex("\\s+")
    private val CARD = Regex(
        "\\b(?:credit[ -]+card|sbi card)(?: account)?(?:\\s*(?:no\\.?|number|ending(?: in| with)?))?" +
            "\\s*[:#.-]?\\s*[x*#•-]*([0-9]{4})\\b",
    )
    private val NON_PAYMENTS = Regex(
        "\\b(?:otp|verification code|scheduled|pending|failed|declined|refund(?:ed)?|spent|purchase|" +
            "reversed|reversal|cashback|reward points|payment request|not (?:been )?received)\\b",
    )
    private val CARD_USED_FOR_PAYMENT = Regex(
        "\\b(?:using|with|via|from|by) (?:(?!(?:for|to|towards?|on)\\b)[a-z]+ ){0,5}$",
    )
    private const val MONEY = "(?:₹|inr|rs\\.?)\\s*" +
        "((?:[0-9]{1,3}(?:,[0-9]{2,3})*,[0-9]{3}|[0-9]+)(?:\\.[0-9]{1,2})?)(?![0-9]|[.,][0-9])(?:/-)?"
    private val PAYMENT_AMOUNTS = listOf(
        Regex("\\b(?:we (?:have )?)?received (?:your |a )?payment of $MONEY"),
        Regex("\\bpayment of $MONEY\\s+(?:(?:has been|was|is)\\s+)?(?:received|credited|posted|successful)\\b"),
        Regex("\\bpayment of $MONEY (?:towards?|to|for|on) [^!?;]{1,90}\\b(?:has been|was) (?:received|credited|posted)\\b"),
        Regex("\\bpayment (?:received|successful)\\s*[:=-]?\\s*(?:(?:of|for)\\s+)?$MONEY"),
        Regex("\\bthank(?: you|s) for (?:your |the )?payment of $MONEY"),
        Regex("\\b(?:received|credited) $MONEY\\s+(?:toward(?:s)?|as (?:a )?payment)\\b"),
    )
    private val REFERENCE = Regex(
        "\\b(?:payment ref(?:erence)?|transaction ref(?:erence)?|utr|rrn|ref(?:erence)?)" +
            "\\s*(?:no\\.?|number|id)?\\s*[:#=-]?\\s*([a-z0-9]{6,40})\\b",
    )
}
