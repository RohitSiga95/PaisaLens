package com.paisalens.app.sms

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import com.paisalens.app.PaisaLensApplication
import com.paisalens.app.data.model.SmsCoverageCandidate
import com.paisalens.app.data.model.smsCoverageFingerprint
import com.paisalens.app.data.model.smsCoverageReasonOrNull
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class TransactionSmsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return
        val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent)
        if (messages.isEmpty()) return

        val sender = messages.first().displayOriginatingAddress.orEmpty()
        val body = messages.joinToString(separator = "") { it.displayMessageBody.orEmpty() }
        val timestamp = messages.first().timestampMillis
        val app = context.applicationContext as PaisaLensApplication
        val pendingResult = goAsync()

        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                // A manifest receiver can cold-start the process before the ViewModel has loaded
                // repository flows, so read durable rules before parsing this live message.
                val parsed = app.parser.parse(
                    sender = sender,
                    body = body,
                    timestamp = timestamp,
                    coverageRules = app.repository.smsCoverageRulesForParsing(),
                )
                val availability = app.availabilityParser.parse(sender, body, timestamp)
                val creditCardBill = app.creditCardBillParser.parse(sender, body, timestamp)
                val coverageCandidate = if (parsed == null && availability == null && creditCardBill == null) {
                    smsCoverageReasonOrNull(sender, body)?.let { reason ->
                        SmsCoverageCandidate(
                            sourceMessageId = stableReceiverMessageId(sender, body, timestamp),
                            sender = sender.ifBlank { "Unknown sender" },
                            body = body,
                            receivedAt = timestamp,
                            reason = reason,
                        )
                    }
                } else {
                    null
                }
                if (parsed != null || availability != null || creditCardBill != null || coverageCandidate != null) {
                    app.repository.ingestSms(
                        items = listOfNotNull(parsed),
                        availabilityUpdates = listOfNotNull(availability),
                        coverageCandidates = listOfNotNull(coverageCandidate),
                        creditCardBills = listOfNotNull(creditCardBill),
                    )
                }
            } finally {
                pendingResult.finish()
            }
        }
    }

    private fun stableReceiverMessageId(sender: String, body: String, timestamp: Long): String =
        "received-${smsCoverageFingerprint(sender, body).take(24)}-$timestamp"
}
