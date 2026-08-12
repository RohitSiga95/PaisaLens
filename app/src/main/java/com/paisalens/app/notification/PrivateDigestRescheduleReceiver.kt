package com.paisalens.app.notification

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.paisalens.app.data.local.UserPreferences
import java.util.concurrent.Executors

class PrivateDigestRescheduleReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val pendingResult = goAsync()
        val appContext = context.applicationContext
        val executor = Executors.newSingleThreadExecutor()
        executor.execute {
            try {
                val configuration = UserPreferences(appContext).notificationDigest.value
                if (intent?.action == Intent.ACTION_TIME_CHANGED || intent?.action == Intent.ACTION_TIMEZONE_CHANGED) {
                    PrivateDigestScheduler.sync(appContext, configuration)
                } else {
                    PrivateDigestScheduler.ensure(appContext, configuration)
                }
            } finally {
                pendingResult.finish()
                executor.shutdown()
            }
        }
    }
}
