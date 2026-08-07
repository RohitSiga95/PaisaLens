package com.paisalens.app.sms

import android.content.Context
import android.provider.Telephony
import com.paisalens.app.data.model.AccountAvailabilityUpdate
import com.paisalens.app.data.model.ParsedTransaction
import com.paisalens.app.data.parser.TransactionSmsParser

class SmsInboxScanner(
    private val context: Context,
    private val parser: TransactionSmsParser,
    private val availabilityParser: AccountAvailabilitySmsParser,
) {
    fun scan(maxMessages: Int = 10_000): SmsScanBatch {
        val parsed = mutableListOf<ParsedTransaction>()
        val availability = mutableListOf<AccountAvailabilityUpdate>()
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
                parser.parse(
                    sender = sender,
                    body = body,
                    timestamp = timestamp,
                    messageId = "sms-" + cursor.getLong(idIndex),
                )?.let(parsed::add)
                availabilityParser.parse(sender, body, timestamp)?.let { update ->
                    availability += update
                }
            }
        }
        return SmsScanBatch(parsed, availability)
    }
}

data class SmsScanBatch(
    val transactions: List<ParsedTransaction>,
    val availabilityUpdates: List<AccountAvailabilityUpdate>,
)
