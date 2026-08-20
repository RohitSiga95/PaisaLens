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
            var scanned = 0

            while (cursor.moveToNext() && scanned < maxMessages) {
                scanned += 1
                val body = cursor.getString(bodyIndex) ?: continue
                val sender = cursor.getString(addressIndex).orEmpty()
                val timestamp = cursor.getLong(dateIndex)
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

data class SmsScanBatch(
    val transactions: List<ParsedTransaction>,
    val availabilityUpdates: List<AccountAvailabilityUpdate>,
    val coverageCandidates: List<SmsCoverageCandidate> = emptyList(),
    val creditCardBills: List<ParsedCreditCardBill> = emptyList(),
)
