package com.paisalens.app.data.local

import android.content.Context
import com.paisalens.app.sms.SmsInboxCheckpoint

/** A bookmark into this phone's inbox, never exported with the financial ledger. */
internal class SmsInboxProgressStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences("paisalens.sms_sync", Context.MODE_PRIVATE)

    fun read(): SmsInboxCheckpoint? = SmsInboxCheckpoint.decode(preferences.getString(CHECKPOINT, null))

    fun write(checkpoint: SmsInboxCheckpoint?) {
        // Commit only after durable ingest. Losing the bookmark is safe: ingestion is replayable.
        check(preferences.edit().putString(CHECKPOINT, checkpoint?.encode()).commit()) {
            "Could not save SMS sync progress"
        }
    }

    private companion object {
        const val CHECKPOINT = "inbox_checkpoint"
    }
}
