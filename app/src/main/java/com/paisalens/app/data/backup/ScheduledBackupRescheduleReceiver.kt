package com.paisalens.app.data.backup

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.paisalens.app.data.local.UserPreferences

class ScheduledBackupRescheduleReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val configuration = UserPreferences(context.applicationContext).scheduledBackup.value
        if (intent?.action == Intent.ACTION_TIME_CHANGED || intent?.action == Intent.ACTION_TIMEZONE_CHANGED) {
            ScheduledBackupScheduler.sync(context, configuration)
        } else {
            ScheduledBackupScheduler.ensure(context, configuration)
        }
    }
}
