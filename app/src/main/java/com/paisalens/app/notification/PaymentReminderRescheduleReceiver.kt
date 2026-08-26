package com.paisalens.app.notification

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import java.util.concurrent.Executors

/** Recomputes local wall-clock reminder times after boot, timezone/time changes, or app update. */
class PaymentReminderRescheduleReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action !in ALLOWED_SYSTEM_ACTIONS) return
        val result = goAsync()
        val executor = Executors.newSingleThreadExecutor()
        executor.execute {
            try {
                PaymentReminderScheduler.sync(context.applicationContext)
            } finally {
                result.finish()
                executor.shutdown()
            }
        }
    }

    private companion object {
        val ALLOWED_SYSTEM_ACTIONS = setOf(
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_TIMEZONE_CHANGED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
        )
    }
}
