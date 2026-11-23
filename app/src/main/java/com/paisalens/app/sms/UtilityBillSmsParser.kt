package com.paisalens.app.sms

import com.paisalens.app.data.model.UtilityBillKind
import com.paisalens.app.data.model.UtilitySmsBill
import com.paisalens.app.data.model.smsCoverageFingerprint
import java.time.Instant
import java.time.LocalDate
import java.time.Month
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.abs

/** Extracts explicit INR dues from bill notices. Unclear amounts or dates stay unparsed. */
object UtilityBillSmsParser {
    fun parse(sender: String, body: String, timestamp: Long): UtilitySmsBill? {
        if (timestamp <= 0 || body.length > 4_096) return null
        val senderCode = sender.trim().uppercase(Locale.ROOT)
            .replace(TRANSPORT_PREFIX, "").replace(SENDER_SUFFIX, "")
        if (!SENDER_CODE.matches(senderCode)) return null
        val text = body.lowercase(Locale.ROOT).replace(LINK, " ").replace(SPACE, " ").trim()
        if (EXCLUDED.containsMatchIn(text) || !BILL_CONTEXT.containsMatchIn(text)) return null
        val provider = providersBySender[senderCode]
        val kind = when {
            ELECTRICITY.containsMatchIn(text) -> UtilityBillKind.ELECTRICITY
            BROADBAND.containsMatchIn(text) -> UtilityBillKind.BROADBAND
            provider?.kind != null -> provider.kind
            POSTPAID.containsMatchIn(text) -> UtilityBillKind.MOBILE_POSTPAID
            else -> null
        } ?: return null
        if (provider?.kind != null && kind != provider.kind) return null
        val receivedDay = Instant.ofEpochMilli(timestamp).atZone(indiaZone).toLocalDate()
        val dueDate = explicitDueDate(text, receivedDay)?.takeIf { it.toEpochDay() in 1..365241 }
            ?: return null
        val amount = amountDue(text) ?: return null
        val providerKey = provider?.key ?: senderCode.lowercase(Locale.ROOT)
        val account = findIdentifier(ACCOUNT, text) ?: findIdentifier(SERVICE_NUMBER, text)
        val invoice = findIdentifier(INVOICE, text)
        return UtilitySmsBill(
            kind = kind,
            providerKey = providerKey,
            providerName = provider?.label ?: senderCode,
            accountKey = account?.let { smsCoverageFingerprint(providerKey, "account|${kind.name}|$it") },
            accountLastFour = account?.takeLast(4)?.takeIf { it.all(Char::isDigit) },
            referenceKey = invoice?.let { smsCoverageFingerprint(providerKey, "invoice|${kind.name}|$it") },
            billingPeriod = billingPeriod(text),
            amountMinor = amount,
            dueDateEpochDay = dueDate.toEpochDay(),
            detectedAt = timestamp,
            noticeKeys = setOf(smsCoverageFingerprint(providerKey, "${kind.name}|$dueDate|$text")),
        )
    }

    private fun explicitDueDate(text: String, receivedDay: LocalDate): LocalDate? {
        for (pattern in DUE_DATES) {
            val dates = pattern.findAll(text).map { parseDate(it.groupValues[1], receivedDay) }.toList()
            if (dates.isNotEmpty()) return dates.distinct().singleOrNull()
        }
        return null
    }

    private fun parseDate(value: String, receivedDay: LocalDate): LocalDate? {
        if (value == "today") return receivedDay
        if (value == "tomorrow") return receivedDay.plusDays(1)
        val parts = value.replace(ORDINAL, "$1").split(DATE_SEPARATOR).filter(String::isNotBlank)
        if (parts.size !in 2..3) return null
        if (parts.first().length == 4 && parts.size == 3) {
            return validDate(parts[0].toIntOrNull(), parts[1].toIntOrNull(), parts[2].toIntOrNull())
                ?.takeIf { abs(it.toEpochDay() - receivedDay.toEpochDay()) <= MAX_DATE_DISTANCE_DAYS }
        }
        val day = parts[0].toIntOrNull() ?: return null
        val month = monthNumber(parts[1]) ?: return null
        if (parts.size == 3) {
            return validDate(yearNumber(parts[2]), month, day)
                ?.takeIf { abs(it.toEpochDay() - receivedDay.toEpochDay()) <= MAX_DATE_DISTANCE_DAYS }
        }
        return (receivedDay.year - 1..receivedDay.year + 1)
            .mapNotNull { validDate(it, month, day) }
            .minByOrNull { abs(it.toEpochDay() - receivedDay.toEpochDay()) }
            ?.takeIf { abs(it.toEpochDay() - receivedDay.toEpochDay()) <= 120 }
    }

    private fun validDate(year: Int?, month: Int?, day: Int?): LocalDate? {
        if (year == null || month == null || day == null) return null
        return runCatching { LocalDate.of(year, month, day) }.getOrNull()
    }

    private fun monthNumber(text: String): Int? = text.toIntOrNull() ?: monthNames[text]

    private fun yearNumber(text: String): Int? = when (text.length) {
        2 -> text.toIntOrNull()?.plus(2000)
        4 -> text.toIntOrNull()
        else -> null
    }

    private fun billingPeriod(text: String): String? =
        PERIOD.findAll(text).mapNotNull { match ->
            val month = monthNumber(match.groupValues[1]) ?: return@mapNotNull null
            val year = yearNumber(match.groupValues[2]) ?: return@mapNotNull null
            runCatching { YearMonth.of(year, month).toString() }.getOrNull()
        }.distinct().singleOrNull()

    private fun amountDue(text: String): Long? {
        for (pattern in AMOUNTS) {
            val amounts = pattern.findAll(text).map { match ->
                match.groupValues[1].replace(",", "").toBigDecimalOrNull()?.movePointRight(2)
                    ?.let { runCatching(it::longValueExact).getOrNull() }
            }.toList()
            if (amounts.isNotEmpty()) return amounts.distinct().singleOrNull()?.takeIf { it > 0L }
        }
        return null
    }

    private fun findIdentifier(pattern: Regex, text: String): String? = pattern.findAll(text)
        .map { it.groupValues[1] }
        .filter { value -> value.count(Char::isDigit) >= 3 && value.takeLast(4).any(Char::isDigit) }
        // Mask representations vary. Prefixing a masked value keeps it distinct from a full ID.
        .map { value -> if (value.startsWith('x') || value.startsWith('*')) "masked:${value.trimStart('x', '*')}" else value }
        .distinct()
        .singleOrNull()

    private data class Provider(
        val key: String,
        val label: String,
        val kind: UtilityBillKind?,
        val senderCodes: Set<String>,
    )

    private fun electricity(key: String, label: String, vararg code: String) =
        Provider(key, label, UtilityBillKind.ELECTRICITY, code.toSet())

    private fun broadband(key: String, label: String, vararg code: String) =
        Provider(key, label, UtilityBillKind.BROADBAND, code.toSet())

    private val providersBySender = listOf(
        electricity("bescom", "BESCOM", "BESCOM", "BESCBL"),
        electricity("msedcl", "MSEDCL", "MSEDCL", "MAHAVI", "MAHADIS", "MSCBIL"),
        electricity("tangedco", "TANGEDCO", "TNEB", "TNEBIL", "TANGED", "TNPDCL"),
        electricity("bses-rajdhani", "BSES Rajdhani", "BRPLDL", "BSESBR", "BRPL"),
        electricity("bses-yamuna", "BSES Yamuna", "BYPLDL", "BSESBY", "BYPL"),
        electricity("bses", "BSES", "BSESDL", "BSESBYB", "BSES"),
        electricity("tata-power", "Tata Power", "TATAPW", "TATAPD", "TPDDL"),
        electricity("adani-electricity", "Adani Electricity", "ADANIE", "ADANIP", "AEMLBK"),
        electricity("kseb", "KSEB", "KSEBLT", "KSEBBL", "KSEB"),
        electricity("uppcl", "UPPCL", "UPPCLT", "UPPCLB", "UPPCL"),
        electricity("wbsedcl", "WBSEDCL", "WBSED", "WBSEDCL", "WBSEDC"),
        electricity("cesc", "CESC", "CESCLT", "CESCBL"),
        broadband("act", "ACT Fibernet", "ACTNET", "ACTFBR", "ACTFIB", "ACTBB"),
        broadband("hathway", "Hathway", "HATHWY", "HATHWA"),
        broadband("excitel", "Excitel", "EXCTEL", "EXCITL", "EXCITEL"),
        broadband("tikona", "Tikona", "TIKONA"),
        Provider("airtel", "Airtel", null, setOf("AIRTEL", "AIRBIL", "AIRPAY", "AIRTBL", "AIRXST")),
        Provider("jio", "Jio", null, setOf("JIOINF", "JIO", "JIOTEL", "JIONET", "JIOFBR", "JIOBIL")),
        Provider("vi", "Vi", null, setOf("VICARE", "VI", "VODFNE", "VODAFO", "VODAFN", "IDEACL")),
        Provider("bsnl", "BSNL", null, setOf("BSNL", "BSNLIN", "BSNLTL", "BSNLFT")),
    ).flatMap { provider -> provider.senderCodes.map { code -> code to provider } }.toMap()

    private val indiaZone = ZoneId.of("Asia/Kolkata")
    private val monthNames = Month.entries.flatMap { month ->
        listOf(TextStyle.SHORT, TextStyle.FULL).map { style ->
            month.getDisplayName(style, Locale.ENGLISH).lowercase(Locale.ROOT) to month.value
        }
    }.toMap() + ("sept" to 9)
    private const val MAX_DATE_DISTANCE_DAYS = 366
    private val TRANSPORT_PREFIX = Regex("^[A-Z]{2}-")
    private val SENDER_SUFFIX = Regex("-[GSPT]$")
    private val SENDER_CODE = Regex("(?=[A-Z0-9]{2,16}$)(?=.*[A-Z])[A-Z0-9]+")
    private val LINK = Regex("https?://\\S+")
    private val SPACE = Regex("\\s+")
    private val EXCLUDED = Regex(
        "\\b(?:otp|verification code|one[- ]time password|credit[ -]card|prepaid|recharge|payment request|" +
            "payment (?:was )?(?:received|successful|failed)|paid successfully|thank you for (?:your )?payment|refund)\\b",
    )
    private val BILL_CONTEXT = Regex("\\b(?:bills?|invoice|amount due|outstanding|payable|payment due)\\b")
    private val ELECTRICITY = Regex("\\b(?:electricity|power bill|electric bill|energy charges)\\b")
    private val BROADBAND = Regex("\\b(?:broadband|xstream|jio(?:air)?fiber|airfiber|fibre|fibernet|fiber|ftth|wi[ -]?fi|fixedline)\\b")
    private val POSTPAID = Regex("\\b(?:post[ -]?paid|mobile bill|mobile number)\\b")
    private const val DATE = "(today|tomorrow|\\d{4}-\\d{1,2}-\\d{1,2}|" +
        "\\d{1,2}(?:st|nd|rd|th)?[ ./-]+[a-z]{3,9}(?:[ ,./-]+(?:\\d{4}|\\d{2}))?|" +
        "\\d{1,2}[/-]\\d{1,2}(?:[/-](?:\\d{4}|\\d{2}))?|\\d{1,2}\\.\\d{1,2}\\.(?:\\d{4}|\\d{2}))(?![a-z0-9]|[./-]\\d)"
    private val DUE_DATES = listOf(
        Regex("\\b(?:due (?:date|dt\\.?)|last date (?:for|of) payment)\\s*(?:is|on|:|=|-)?\\s*$DATE"),
        Regex("\\b(?:due|payable)\\s*(?:on|by|:)\\s*$DATE"),
        Regex("\\b(?:pay|payment)\\b[^!?;]{0,70}?\\b(?:by|before)\\s+$DATE"),
        Regex("\\b(?:due|payable)\\s+(?=today|tomorrow|\\d{1,4}[/-]|\\d{1,2}(?:st|nd|rd|th)?[ -]+[a-z])$DATE"),
    )
    private val ORDINAL = Regex("(\\d{1,2})(?:st|nd|rd|th)\\b")
    private val DATE_SEPARATOR = Regex("[ ,./-]+")
    private const val AMOUNT = "((?:[0-9]{1,3}(?:,[0-9]{2,3})*,[0-9]{3}|[0-9]+)(?:\\.[0-9]{1,2})?)" +
        "(?![0-9]|[.,][0-9])(?:/-)?"
    private const val CURRENCY = "(?:₹|inr|rs\\.?)\\s*"
    private const val VALUE = "\\s*[:=]?\\s*(?:is\\s+|of\\s+)?(?:$CURRENCY)?$AMOUNT"
    private val AMOUNTS = listOf(
        Regex("\\b(?:total (?:amount )?(?:due|payable)|net (?:amount )?payable)\\b$VALUE"),
        Regex("\\b(?:amount (?:due|payable)|due amount|payable amount|outstanding (?:amount|balance))\\b$VALUE"),
        Regex("\\b(?:bill (?:amount|amt\\.?)|payment amount)\\b$VALUE"),
        Regex("\\b(?:bill of|pay)\\s+$CURRENCY$AMOUNT"),
        Regex("$CURRENCY$AMOUNT\\s+(?:is\\s+)?(?:due|payable)\\b"),
    )
    private const val IDENTIFIER = "\\s*(?:(?:id|no\\.?|number|ending(?: in| with)?)\\s*)?" +
        "[:#=.-]?\\s*(?=[a-z0-9*]*\\d)([a-z0-9*]{4,32})\\b"
    private val ACCOUNT = Regex(
        "\\b(?:consumer|customer|service|relationship|connection|subscriber|account|a/c|acc|ca|rr|k)\\b$IDENTIFIER",
    )
    private val SERVICE_NUMBER = Regex(
        "\\b(?:mobile|landline|broadband|fiber|fibre|postpaid)\\b$IDENTIFIER",
    )
    private val INVOICE = Regex(
        "\\b(?:bill|invoice)\\s+(?:id|no\\.?|number|reference)\\s*[:#=.-]?\\s*([a-z0-9-]{3,32})\\b",
    )
    private val PERIOD = Regex(
        "\\b(?:billing (?:period|month)|bill for|invoice for)\\s*[:=-]?\\s*([a-z]{3,9}|\\d{1,2})[ ,/-]+(\\d{4}|\\d{2})\\b",
    )
}
