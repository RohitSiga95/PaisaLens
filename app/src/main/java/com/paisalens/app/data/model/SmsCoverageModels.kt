package com.paisalens.app.data.model

import java.security.MessageDigest
import java.util.Locale

enum class SmsCoverageReason {
    MISSING_AMOUNT,
    MISSING_DIRECTION,
    UNSUPPORTED_FORMAT,
}

enum class SmsCoverageStatus {
    NEEDS_REVIEW,
    RULE_APPLIED,
    RESOLVED,
    DISMISSED,
}

data class SmsCoverageCandidate(
    val sourceMessageId: String,
    val sender: String,
    val body: String,
    val receivedAt: Long,
    val reason: SmsCoverageReason,
)

data class SmsCoverageMessage(
    val id: Long = 0,
    val sourceMessageId: String,
    val sender: String,
    val body: String,
    val receivedAt: Long,
    val reason: SmsCoverageReason,
    val status: SmsCoverageStatus = SmsCoverageStatus.NEEDS_REVIEW,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = createdAt,
)

internal data class EncryptedSmsCoverageMessage(
    val id: Long,
    val sourceMessageId: String,
    val sender: String,
    val bodyCipher: ByteArray,
    val receivedAt: Long,
    val reason: SmsCoverageReason,
    val status: SmsCoverageStatus,
    val createdAt: Long,
    val updatedAt: Long,
)

/**
 * A deliberately literal SMS parser rule. User supplied regular expressions are not
 * accepted: exact sender matching plus required phrases is predictable, fast, and avoids
 * pathological expressions being evaluated against private SMS content.
 */
data class SmsCoverageRule(
    val id: Long = 0,
    val name: String,
    val senderKey: String,
    val requiredPhrases: List<String>,
    val merchantName: String,
    val category: ExpenseCategory = ExpenseCategory.OTHER,
    val type: TransactionType = TransactionType.EXPENSE,
    val source: TransactionSource = TransactionSource.BANK,
    val enabled: Boolean = true,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = createdAt,
)

data class SmsTransactionIngestResult(
    val inserted: Int,
    val duplicatesMerged: Int,
    val ignoredSourceMessages: Int,
)

data class TransactionSmsSource(
    val sourceMessageId: String,
    val transactionId: Long,
    val receivedAt: Long,
)

fun SmsCoverageRule.normalized(): SmsCoverageRule {
    val cleanName = name.trim().replace(Regex("\\s+"), " ").take(48)
    val cleanSender = normalizedSmsSender(senderKey)
    val cleanPhrases = requiredPhrases
        .asSequence()
        .map(::normalizedSmsText)
        .filter { it.length >= 2 }
        .distinct()
        .take(6)
        .toList()
    val cleanMerchant = merchantName.trim().replace(Regex("\\s+"), " ").take(64)
    require(cleanName.isNotBlank()) { "Rule name cannot be empty" }
    require(cleanSender.isNotBlank()) { "SMS sender cannot be empty" }
    require(cleanPhrases.isNotEmpty()) { "Add at least one phrase found in the SMS" }
    require(cleanMerchant.isNotBlank()) { "Merchant name cannot be empty" }
    return copy(
        name = cleanName,
        senderKey = cleanSender,
        requiredPhrases = cleanPhrases,
        merchantName = cleanMerchant,
        updatedAt = updatedAt.coerceAtLeast(createdAt),
    )
}

fun SmsCoverageRule.matches(sender: String, body: String): Boolean {
    if (!enabled || normalizedSmsSender(sender) != normalizedSmsSender(senderKey)) return false
    val normalizedBody = normalizedSmsText(body)
    return requiredPhrases.isNotEmpty() && requiredPhrases.all { phrase ->
        normalizedBody.contains(normalizedSmsText(phrase))
    }
}

fun smsCoverageReasonOrNull(sender: String, body: String): SmsCoverageReason? {
    val normalizedBody = normalizedSmsText(body)
    if (normalizedBody.isBlank() || isAuthenticationSms(normalizedBody)) return null

    val senderLooksFinancial = normalizedSmsSender(sender).let { key ->
        FINANCIAL_SENDER_MARKERS.any(key::contains)
    }
    val matchedSignals = FINANCIAL_BODY_MARKERS.count(normalizedBody::contains)
    if (!senderLooksFinancial && matchedSignals < 2) return null

    val hasAmount = MONEY_PATTERN.containsMatchIn(body)
    val hasDirection = DIRECTION_MARKERS.any(normalizedBody::contains)
    return when {
        !hasAmount -> SmsCoverageReason.MISSING_AMOUNT
        !hasDirection -> SmsCoverageReason.MISSING_DIRECTION
        else -> SmsCoverageReason.UNSUPPORTED_FORMAT
    }
}

fun smsDuplicateFingerprint(transaction: ParsedTransaction): String = smsDuplicateFingerprint(
    sender = transaction.sender,
    body = transaction.rawMessage,
    amountMinor = transaction.amountMinor,
    type = transaction.type,
    accountHint = transaction.accountHint,
    merchant = transaction.merchant,
)

fun smsCoverageFingerprint(sender: String, body: String): String {
    val canonical = normalizedSmsSender(sender) + "\u001F" + normalizedSmsText(body)
    return MessageDigest.getInstance("SHA-256")
        .digest(canonical.toByteArray(Charsets.UTF_8))
        .joinToString("") { byte -> "%02x".format(byte) }
}

fun smsDuplicateFingerprint(
    sender: String,
    body: String,
    amountMinor: Long,
    type: TransactionType,
    accountHint: String?,
    merchant: String,
): String {
    val canonical = listOf(
        normalizedSmsSender(sender),
        amountMinor.toString(),
        type.name,
        accountHint?.filter(Char::isDigit)?.takeLast(4).orEmpty(),
        normalizedMerchantKey(merchant),
        normalizedSmsBodyForDuplicateCheck(body),
    ).joinToString("\u001F")
    return MessageDigest.getInstance("SHA-256")
        .digest(canonical.toByteArray(Charsets.UTF_8))
        .joinToString("") { byte -> "%02x".format(byte) }
}

fun areLikelyDuplicateSms(
    first: ParsedTransaction,
    second: ParsedTransaction,
    windowMillis: Long = SMS_DUPLICATE_WINDOW_MILLIS,
): Boolean {
    if (first.sourceMessageId == second.sourceMessageId) return false
    if (smsDuplicateFingerprint(first) != smsDuplicateFingerprint(second)) return false
    if (kotlin.math.abs(first.occurredAt - second.occurredAt) > windowMillis) return false
    if (
        hasStableSmsTransactionReference(first.rawMessage) &&
        hasStableSmsTransactionReference(second.rawMessage)
    ) {
        return true
    }
    if (first.occurredAt != second.occurredAt) return false
    val provenances = setOf(
        smsIngestProvenance(first.sourceMessageId),
        smsIngestProvenance(second.sourceMessageId),
    )
    return provenances in setOf(
        setOf(SmsIngestProvenance.LIVE_RECEIVER, SmsIngestProvenance.INBOX),
        setOf(SmsIngestProvenance.LIVE_RECEIVER, SmsIngestProvenance.RESTORED_INBOX),
        setOf(SmsIngestProvenance.INBOX, SmsIngestProvenance.RESTORED_INBOX),
    )
}

/**
 * A stable bank reference supports a windowed match from any ingest path. Without one, the
 * receiver and inbox representations must have the same network-sent timestamp as well.
 */
fun hasStableSmsTransactionReference(body: String): Boolean =
    EXPLICIT_STABLE_TRANSACTION_REFERENCE_PATTERN.containsMatchIn(body) ||
        BARE_STABLE_TRANSACTION_REFERENCE_PATTERN.containsMatchIn(body)

internal enum class SmsIngestProvenance {
    LIVE_RECEIVER,
    INBOX,
    RESTORED_INBOX,
    OTHER,
}

/** Identifies only durable IDs generated by the two SMS ingestion paths. */
internal fun smsIngestProvenance(sourceMessageId: String): SmsIngestProvenance = when {
    INBOX_SMS_SOURCE_ID_PATTERN.matches(sourceMessageId) -> SmsIngestProvenance.INBOX
    RESTORED_INBOX_SMS_SOURCE_ID_PATTERN.matches(sourceMessageId) -> SmsIngestProvenance.RESTORED_INBOX
    sourceMessageId.startsWith("received-") || LEGACY_LIVE_SMS_SOURCE_ID_PATTERN.matches(sourceMessageId) ->
        SmsIngestProvenance.LIVE_RECEIVER
    else -> SmsIngestProvenance.OTHER
}

fun normalizedSmsSender(sender: String): String = sender
    .trim()
    .uppercase(Locale.ROOT)
    .replace(Regex("^[A-Z]{2}-"), "")
    .replace(Regex("[^A-Z0-9]"), "")

internal fun normalizedSmsText(value: String): String = value
    .lowercase(Locale.ROOT)
    .replace(Regex("\\s+"), " ")
    .trim()

/**
 * Keeps transaction references intact. Different UTR/RRN/reference values are evidence of
 * different payments, even when amount, merchant, account and delivery time are otherwise equal.
 * Only presentation differences that cannot change financial identity are normalized.
 */
internal fun normalizedSmsBodyForDuplicateCheck(body: String): String = normalizedSmsText(body)

internal fun isAuthenticationSms(body: String): Boolean =
    AUTHENTICATION_PATTERN.containsMatchIn(normalizedSmsText(body))

const val SMS_DUPLICATE_WINDOW_MILLIS: Long = 10L * 60L * 1000L

private val MONEY_PATTERN = Regex(
    "(?i)(?:₹|INR|Rs\\.?|Rupees?)\\s*[0-9][0-9,]*(?:\\.[0-9]{1,2})?",
)
private const val STABLE_TRANSACTION_REFERENCE_TOKEN =
    "(?=[a-z0-9-]*[0-9])[a-z0-9-]{6,}\\b"
private val EXPLICIT_STABLE_TRANSACTION_REFERENCE_PATTERN = Regex(
    "(?i)\\b(?:" +
        "(?:upi\\s+)?ref(?:erence)?(?:\\s+(?:id|no\\.?|number))?|" +
        "(?:rrn|utr)(?:\\s+(?:id|no\\.?|number))?|" +
        "(?:txn|transaction)\\s+(?:id|no\\.?|number)" +
        ")\\s*(?:[:#=.-]\\s*)?(?:is\\s+)?$STABLE_TRANSACTION_REFERENCE_TOKEN",
)
private val BARE_STABLE_TRANSACTION_REFERENCE_PATTERN = Regex(
    "(?i)\\b(?:txn|transaction)\\s*(?:" +
        "[:#=.-]\\s*$STABLE_TRANSACTION_REFERENCE_TOKEN|" +
        "[0-9][0-9-]{5,}\\b" +
        ")",
)
// The fingerprint/timestamp suffix is a defense-in-depth namespace for a provider row-ID
// collision. It stays an inbox provenance while preserving the existing mapping under raw ID.
private val INBOX_SMS_SOURCE_ID_PATTERN = Regex(
    "^sms-[0-9]+(?:-[0-9a-f]{64}-[0-9]+)?$",
)
private val RESTORED_INBOX_SMS_SOURCE_ID_PATTERN = Regex(
    "^restored-[0-9]+-sms-[0-9]+(?:-[0-9a-f]{64}-[0-9]+)?$",
)
private val LEGACY_LIVE_SMS_SOURCE_ID_PATTERN = Regex("^[0-9a-f]{24}$")
private val AUTHENTICATION_PATTERN = Regex(
    "\\b(?:otp|one time password|verification code|login code)\\b|do not share",
    RegexOption.IGNORE_CASE,
)
private val DIRECTION_MARKERS = listOf(
    "debited", "debit of", "spent", "paid", "purchase", "withdrawn", "charged",
    "credited", "credit of", "received", "refund", "reversal", "transferred",
)
private val FINANCIAL_BODY_MARKERS = listOf(
    "₹", "inr", "rs ", "rupee", "account", "a/c", "card", "balance", "payment",
    "debited", "credited", "spent", "due", "upi", "transaction", "txn", "utr", "rrn",
)
private val FINANCIAL_SENDER_MARKERS = listOf(
    "BANK", "BNK", "HDFC", "IDFC", "ICICI", "SBI", "SIB", "AXIS", "KOTAK", "YESBK",
    "CARDS", "CARD", "UPI", "PAYTM", "PHONEPE",
)
