package com.paisalens.app.sms

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import com.paisalens.app.PaisaLensApplication
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
        val parsed = app.parser.parse(sender, body, timestamp)
        val availability = app.availabilityParser.parse(sender, body, timestamp)
        if (parsed == null && availability == null) return
        val pendingResult = goAsync()

        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                app.repository.ingestSms(
                    items = listOfNotNull(parsed),
                    availabilityUpdates = listOfNotNull(availability),
                )
            } finally {
                pendingResult.finish()
            }
        }
    }
}
