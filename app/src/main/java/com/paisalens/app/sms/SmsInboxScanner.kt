package com.paisalens.app.sms

import android.content.Context
import android.provider.Telephony
import com.paisalens.app.data.model.AccountAvailabilityUpdate
import com.paisalens.app.data.model.ParsedTransaction
import com.paisalens.app.data.model.ParsedCreditCardBill
import com.paisalens.app.data.model.SmsCoverageCandidate
import com.paisalens.app.data.model.SmsCoverageRule
import com.paisalens.app.data.model.smsCoverageReasonOrNull
import com.paisalens.app.data.parser.TransactionSmsParser

class SmsInboxScanner(
    private val context: Context,
    private val parser: TransactionSmsParser,
    private val availabilityParser: AccountAvailabilitySmsParser,
    private val creditCardBillParser: CreditCardBillSmsParser = CreditCardBillSmsParser(),
) {
    fun scan(
        maxMessages: Int = 10_000,
        coverageRules: List<SmsCoverageRule> = emptyList(),
    ): SmsScanBatch {
        val parsed = mutableListOf<ParsedTransaction>()
        val availability = mutableListOf<AccountAvailabilityUpdate>()
        val coverageCandidates = mutableListOf<SmsCoverageCandidate>()
        val creditCardBills = mutableListOf<ParsedCreditCardBill>()
        val projection = arrayOf(
            Telephony.Sms._ID,
            Telephony.Sms.ADDRESS,
            Telephony.Sms.BODY,
            Telephony.Sms.DATE,
            Telephony.Sms.DATE_SENT,
        )

        context.contentResolver.query(
            Telephony.Sms.Inbox.CONTENT_URI,
            projection,
            null,
            null,
            Telephony.Sms.DATE + " DESC",
        )?.use { cursor ->
            val idIndex = cursor.getColumnIndexOrThrow(Telephony.Sms._ID)
            val addressIndex = cursor.getColumnIndexOrThrow(Telephony.Sms.ADDRESS)
            val bodyIndex = cursor.getColumnIndexOrThrow(Telephony.Sms.BODY)
            val dateIndex = cursor.getColumnIndexOrThrow(Telephony.Sms.DATE)
            val dateSentIndex = cursor.getColumnIndexOrThrow(Telephony.Sms.DATE_SENT)
            var scanned = 0

            while (cursor.moveToNext() && scanned < maxMessages) {
                scanned += 1
                val body = cursor.getString(bodyIndex) ?: continue
                val sender = cursor.getString(addressIndex).orEmpty()
                val receivedAt = cursor.getLong(dateIndex)
                val timestamp = preferredSmsTimestamp(
                    sentAt = cursor.getLong(dateSentIndex),
                    receivedAt = receivedAt,
                )
                val sourceMessageId = "sms-" + cursor.getLong(idIndex)
                val transaction = parser.parse(
                    sender = sender,
                    body = body,
                    timestamp = timestamp,
                    messageId = sourceMessageId,
                    coverageRules = coverageRules,
                )
                val availabilityUpdate = availabilityParser.parse(sender, body, timestamp)
                val creditCardBill = creditCardBillParser.parse(sender, body, timestamp, sourceMessageId)
                transaction?.let(parsed::add)
                availabilityUpdate?.let(availability::add)
                creditCardBill?.let(creditCardBills::add)
                if (transaction == null && availabilityUpdate == null && creditCardBill == null) {
                    smsCoverageReasonOrNull(sender, body)?.let { reason ->
                        coverageCandidates += SmsCoverageCandidate(
                            sourceMessageId = sourceMessageId,
                            sender = sender.ifBlank { "Unknown sender" },
                            body = body,
                            receivedAt = timestamp,
                            reason = reason,
                        )
                    }
                }
            }
        }
        return SmsScanBatch(parsed, availability, coverageCandidates, creditCardBills)
    }
}

/**
 * The live receiver timestamps an SMS at its network sent time. Inbox rows also expose that
 * value, but some providers leave it unset or return a wildly inconsistent value. Keeping the
 * two ingestion paths on the same sane timestamp prevents one physical SMS becoming two ledger
 * entries while retaining the provider's received time as a safe fallback.
 */
internal fun preferredSmsTimestamp(sentAt: Long, receivedAt: Long): Long {
    if (sentAt <= 0L) return receivedAt
    if (receivedAt <= 0L) return sentAt
    val delta = if (sentAt >= receivedAt) sentAt - receivedAt else receivedAt - sentAt
    return if (delta <= MAX_SANE_SMS_TIMESTAMP_DELTA_MILLIS) sentAt else receivedAt
}

private const val MAX_SANE_SMS_TIMESTAMP_DELTA_MILLIS = 7L * 24L * 60L * 60L * 1000L

data class SmsScanBatch(
    val transactions: List<ParsedTransaction>,
    val availabilityUpdates: List<AccountAvailabilityUpdate>,
    val coverageCandidates: List<SmsCoverageCandidate> = emptyList(),
    val creditCardBills: List<ParsedCreditCardBill> = emptyList(),
)
