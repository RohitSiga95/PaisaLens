package com.paisalens.app.notification

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.paisalens.app.data.model.NotificationDigestConfiguration
import com.paisalens.app.data.model.NotificationDigestFrequency
import java.time.ZonedDateTime
import java.time.temporal.TemporalAdjusters

object PrivateDigestScheduler {
    /** Restores the persisted logical delivery, including a safe catch-up after process/force-stop loss. */
    fun ensure(context: Context, configuration: NotificationDigestConfiguration) {
        if (!configuration.enabled) {
            cancel(context)
            return
        }
        val state = statePreferences(context)
        val scheduledFor = state.getLong(KEY_SCHEDULED_FOR, 0L)
        val lastDeliveredFor = state.getLong(KEY_LAST_DELIVERED_FOR, 0L)
        val claimedFor = state.getLong(KEY_CLAIMED_FOR, 0L)
        val now = System.currentTimeMillis()
        when {
            scheduledFor <= 0L -> sync(context, configuration)
            lastDeliveredFor >= scheduledFor -> sync(context, configuration)
            scheduledFor <= now -> {
                if (claimedFor == scheduledFor) state.edit().remove(KEY_CLAIMED_FOR).apply()
                scheduleAt(context, now + CATCH_UP_DELAY_MILLIS, scheduledFor)
            }
            else -> scheduleAt(context, scheduledFor, scheduledFor)
        }
    }

    fun sync(context: Context, configuration: NotificationDigestConfiguration) {
        if (!configuration.enabled) {
            cancel(context)
            return
        }
        val triggerAt = nextDigestTriggerAt(configuration.normalized(), ZonedDateTime.now())
        scheduleAt(context, triggerAt.toInstant().toEpochMilli(), triggerAt.toInstant().toEpochMilli())
    }

    /** Atomically suppresses duplicate/catch-up deliveries for the same logical scheduled time. */
    @Synchronized
    fun claimDelivery(context: Context): Long? {
        val state = statePreferences(context)
        val scheduledFor = state.getLong(KEY_SCHEDULED_FOR, 0L).takeIf { it > 0L }
            ?: System.currentTimeMillis()
        if (state.getLong(KEY_LAST_DELIVERED_FOR, 0L) >= scheduledFor) return null
        if (state.getLong(KEY_CLAIMED_FOR, 0L) == scheduledFor) return null
        state.edit().putLong(KEY_CLAIMED_FOR, scheduledFor).commit()
        return scheduledFor
    }

    @Synchronized
    fun completeDelivery(context: Context, scheduledFor: Long) {
        statePreferences(context).edit()
            .putLong(KEY_LAST_DELIVERED_FOR, scheduledFor)
            .remove(KEY_CLAIMED_FOR)
            .commit()
    }

    @Synchronized
    fun releaseDeliveryClaim(context: Context, scheduledFor: Long) {
        val state = statePreferences(context)
        if (state.getLong(KEY_CLAIMED_FOR, 0L) == scheduledFor) {
            state.edit().remove(KEY_CLAIMED_FOR).commit()
        }
    }

    fun retry(context: Context, scheduledFor: Long) {
        releaseDeliveryClaim(context, scheduledFor)
        scheduleAt(context, System.currentTimeMillis() + RETRY_DELAY_MILLIS, scheduledFor)
    }

    private fun scheduleAt(context: Context, alarmAtMillis: Long, logicalScheduledFor: Long) {
        val alarmManager = context.getSystemService(AlarmManager::class.java) ?: return
        // This is intentionally inexact. PaisaLens neither requests nor needs exact-alarm access.
        alarmManager.setAndAllowWhileIdle(
            AlarmManager.RTC_WAKEUP,
            alarmAtMillis,
            pendingIntent(context),
        )
        val state = statePreferences(context)
        state.edit()
            .putLong(KEY_SCHEDULED_FOR, logicalScheduledFor)
            .also { editor ->
                if (state.getLong(KEY_LAST_DELIVERED_FOR, 0L) >= logicalScheduledFor) {
                    editor.remove(KEY_LAST_DELIVERED_FOR)
                }
                if (state.getLong(KEY_CLAIMED_FOR, 0L) != logicalScheduledFor) {
                    editor.remove(KEY_CLAIMED_FOR)
                }
            }
            .apply()
    }

    fun cancel(context: Context) {
        val existing = existingPendingIntent(context)
        context.getSystemService(AlarmManager::class.java)?.let { manager ->
            if (existing != null) manager.cancel(existing)
        }
        existing?.cancel()
        statePreferences(context).edit()
            .remove(KEY_SCHEDULED_FOR)
            .remove(KEY_LAST_DELIVERED_FOR)
            .remove(KEY_CLAIMED_FOR)
            .apply()
    }

    private fun pendingIntent(context: Context): PendingIntent = PendingIntent.getBroadcast(
        context,
        REQUEST_CODE,
        Intent(context, PrivateDigestReceiver::class.java).setAction(ACTION_DELIVER_DIGEST),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun existingPendingIntent(context: Context): PendingIntent? = PendingIntent.getBroadcast(
        context,
        REQUEST_CODE,
        Intent(context, PrivateDigestReceiver::class.java).setAction(ACTION_DELIVER_DIGEST),
        PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun statePreferences(context: Context) = context.applicationContext.getSharedPreferences(
        STATE_PREFERENCES,
        Context.MODE_PRIVATE,
    )

    private const val REQUEST_CODE = 1801
    internal const val ACTION_DELIVER_DIGEST = "com.paisalens.app.action.DELIVER_PRIVATE_DIGEST"
    private const val STATE_PREFERENCES = "paisalens.private_digest_schedule"
    private const val KEY_SCHEDULED_FOR = "scheduled_for"
    private const val KEY_LAST_DELIVERED_FOR = "last_delivered_for"
    private const val KEY_CLAIMED_FOR = "claimed_for"
    private const val CATCH_UP_DELAY_MILLIS = 1_000L
    private const val RETRY_DELAY_MILLIS = 15L * 60L * 1_000L
}

fun nextDigestTriggerAt(
    configuration: NotificationDigestConfiguration,
    now: ZonedDateTime,
): ZonedDateTime {
    val safe = configuration.normalized()
    return when (safe.frequency) {
        NotificationDigestFrequency.DAILY -> {
            val today = now.toLocalDate().atTime(safe.hour, 0).atZone(now.zone)
            if (today.isAfter(now)) today else today.plusDays(1)
        }
        NotificationDigestFrequency.WEEKLY -> {
            val date = now.toLocalDate().with(TemporalAdjusters.nextOrSame(safe.weekday))
            val candidate = date.atTime(safe.hour, 0).atZone(now.zone)
            if (candidate.isAfter(now)) candidate else candidate.plusWeeks(1)
        }
    }
}
