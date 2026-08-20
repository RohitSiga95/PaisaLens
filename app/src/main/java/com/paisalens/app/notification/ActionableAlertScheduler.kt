package com.paisalens.app.notification

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.paisalens.app.data.model.ActionableAlertsConfiguration
import java.security.MessageDigest
import java.time.ZonedDateTime

object ActionableAlertScheduler {
    fun ensure(context: Context, configuration: ActionableAlertsConfiguration) {
        if (!configuration.enabled) {
            cancel(context)
            return
        }
        val state = statePreferences(context)
        val scheduledFor = state.getLong(KEY_SCHEDULED_FOR, 0L)
        val lastDeliveredFor = state.getLong(KEY_LAST_DELIVERED_FOR, 0L)
        val claimedFor = state.getLong(KEY_CLAIMED_FOR, 0L)
        val claimedAt = state.getLong(KEY_CLAIMED_AT, 0L)
        val now = System.currentTimeMillis()
        when {
            scheduledFor <= 0L || lastDeliveredFor >= scheduledFor -> sync(context, configuration)
            scheduledFor <= now -> {
                if (claimedFor == scheduledFor && claimedAt > 0L && now - claimedAt < CLAIM_TIMEOUT_MILLIS) {
                    // A receiver is already evaluating this logical run. Keep one recovery alarm in
                    // case Android kills that process, but never clear a live claim or run twice.
                    scheduleAt(
                        context = context,
                        alarmAtMillis = (claimedAt + CLAIM_TIMEOUT_MILLIS).coerceAtLeast(now + CATCH_UP_DELAY_MILLIS),
                        logicalScheduledFor = scheduledFor,
                    )
                } else {
                    state.edit().remove(KEY_CLAIMED_FOR).remove(KEY_CLAIMED_AT).apply()
                    scheduleAt(context, now + CATCH_UP_DELAY_MILLIS, scheduledFor)
                }
            }
            else -> scheduleAt(context, scheduledFor, scheduledFor)
        }
    }

    fun sync(context: Context, configuration: ActionableAlertsConfiguration) {
        if (!configuration.enabled) {
            cancel(context)
            return
        }
        val trigger = nextActionableAlertTriggerAt(configuration.normalized(), ZonedDateTime.now())
        val triggerMillis = trigger.toInstant().toEpochMilli()
        scheduleAt(context, triggerMillis, triggerMillis)
    }

    @Synchronized
    fun claimDelivery(context: Context): Long? {
        val state = statePreferences(context)
        val scheduledFor = state.getLong(KEY_SCHEDULED_FOR, 0L).takeIf { it > 0L }
            ?: System.currentTimeMillis()
        if (state.getLong(KEY_LAST_DELIVERED_FOR, 0L) >= scheduledFor) return null
        if (state.getLong(KEY_CLAIMED_FOR, 0L) == scheduledFor) return null
        state.edit()
            .putLong(KEY_CLAIMED_FOR, scheduledFor)
            .putLong(KEY_CLAIMED_AT, System.currentTimeMillis())
            .commit()
        return scheduledFor
    }

    @Synchronized
    fun completeDelivery(context: Context, scheduledFor: Long) {
        statePreferences(context).edit()
            .putLong(KEY_LAST_DELIVERED_FOR, scheduledFor)
            .remove(KEY_CLAIMED_FOR)
            .remove(KEY_CLAIMED_AT)
            .commit()
    }

    fun retry(context: Context, scheduledFor: Long) {
        val state = statePreferences(context)
        if (state.getLong(KEY_CLAIMED_FOR, 0L) == scheduledFor) {
            state.edit().remove(KEY_CLAIMED_FOR).remove(KEY_CLAIMED_AT).commit()
        }
        scheduleAt(context, System.currentTimeMillis() + RETRY_DELAY_MILLIS, scheduledFor)
    }

    private fun scheduleAt(context: Context, alarmAtMillis: Long, logicalScheduledFor: Long) {
        val manager = context.getSystemService(AlarmManager::class.java) ?: return
        manager.setAndAllowWhileIdle(
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
                    editor.remove(KEY_CLAIMED_AT)
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
        statePreferences(context).edit().clear().apply()
    }

    private fun pendingIntent(context: Context): PendingIntent = PendingIntent.getBroadcast(
        context,
        REQUEST_CODE,
        Intent(context, ActionableAlertReceiver::class.java).setAction(ACTION_EVALUATE_ALERTS),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun existingPendingIntent(context: Context): PendingIntent? = PendingIntent.getBroadcast(
        context,
        REQUEST_CODE,
        Intent(context, ActionableAlertReceiver::class.java).setAction(ACTION_EVALUATE_ALERTS),
        PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun statePreferences(context: Context) = context.applicationContext.getSharedPreferences(
        STATE_PREFERENCES,
        Context.MODE_PRIVATE,
    )

    internal const val ACTION_EVALUATE_ALERTS = "com.paisalens.app.action.EVALUATE_ACTIONABLE_ALERTS"
    private const val REQUEST_CODE = 1802
    private const val STATE_PREFERENCES = "paisalens.actionable_alert_schedule"
    private const val KEY_SCHEDULED_FOR = "scheduled_for"
    private const val KEY_LAST_DELIVERED_FOR = "last_delivered_for"
    private const val KEY_CLAIMED_FOR = "claimed_for"
    private const val KEY_CLAIMED_AT = "claimed_at"
    private const val CATCH_UP_DELAY_MILLIS = 1_000L
    private const val CLAIM_TIMEOUT_MILLIS = 15L * 60L * 1_000L
    private const val RETRY_DELAY_MILLIS = 15L * 60L * 1_000L
}

fun nextActionableAlertTriggerAt(
    configuration: ActionableAlertsConfiguration,
    now: ZonedDateTime,
): ZonedDateTime {
    val candidate = now.toLocalDate()
        .atTime(configuration.normalized().evaluationHour, 0)
        .atZone(now.zone)
    return if (candidate.isAfter(now)) candidate else candidate.plusDays(1)
}

internal class ActionableAlertDeliveryStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE,
    )

    fun historyFor(candidates: List<ActionableAlertCandidate>): Map<String, ActionableAlertDeliveryRecord> =
        candidates.mapNotNull { candidate ->
            val key = candidate.stableId.deliveryKey()
            val sentAt = preferences.getLong("$key:at", 0L).takeIf { it > 0L } ?: return@mapNotNull null
            val priority = runCatching {
                ActionableAlertPriority.valueOf(preferences.getString("$key:priority", null).orEmpty())
            }.getOrDefault(ActionableAlertPriority.NORMAL)
            candidate.stableId to ActionableAlertDeliveryRecord(sentAt, priority)
        }.toMap()

    fun record(candidates: List<ActionableAlertCandidate>, sentAtMillis: Long) {
        preferences.edit().also { editor ->
            candidates.forEach { candidate ->
                val key = candidate.stableId.deliveryKey()
                editor.putLong("$key:at", sentAtMillis)
                editor.putString("$key:priority", candidate.priority.name)
            }
        }.apply()
    }

    fun clear() {
        preferences.edit().clear().apply()
    }

    private fun String.deliveryKey(): String = MessageDigest.getInstance("SHA-256")
        .digest(toByteArray(Charsets.UTF_8))
        .take(12)
        .joinToString("") { "%02x".format(it) }

    private companion object {
        const val PREFERENCES_NAME = "paisalens.actionable_alert_delivery"
    }
}
