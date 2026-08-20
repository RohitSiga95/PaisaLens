package com.paisalens.app.data.backup

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import java.time.ZonedDateTime
import java.util.UUID

object ScheduledBackupScheduler {
    /** Restores the persisted logical schedule and safely catches up a missed delivery. */
    fun ensure(context: Context, configuration: ScheduledBackupConfiguration) {
        val safe = configuration.normalized()
        if (!safe.isReady) {
            cancel(context)
            return
        }
        val state = statePreferences(context)
        val scheduledFor = state.getLong(KEY_SCHEDULED_FOR, 0L)
        val lastCompletedFor = state.getLong(KEY_LAST_COMPLETED_FOR, 0L)
        val now = System.currentTimeMillis()
        when {
            scheduledFor <= 0L || lastCompletedFor >= scheduledFor -> sync(context, safe)
            scheduledFor <= now -> scheduleAt(context, now + CATCH_UP_DELAY_MILLIS, scheduledFor)
            else -> scheduleAt(context, scheduledFor, scheduledFor)
        }
    }

    fun sync(context: Context, configuration: ScheduledBackupConfiguration) {
        val safe = configuration.normalized()
        if (!safe.isReady) {
            cancel(context)
            return
        }
        val next = nextScheduledBackupAt(safe, ZonedDateTime.now()).toInstant().toEpochMilli()
        scheduleAt(context, next, next)
    }

    @Synchronized
    fun claimExecution(context: Context, logicalScheduledFor: Long): Long? {
        if (logicalScheduledFor <= 0L) return null
        val state = statePreferences(context)
        if (state.getLong(KEY_LAST_COMPLETED_FOR, 0L) >= logicalScheduledFor) return null
        val claimedFor = state.getLong(KEY_CLAIMED_FOR, 0L)
        val claimOwner = state.getString(KEY_CLAIM_OWNER, null)
        // A claim from this process is active. A claim from another process instance is stale:
        // Android cannot keep that worker alive after killing its process, so reclaim it now.
        if (claimedFor > 0L && claimOwner == PROCESS_CLAIM_TOKEN) return null
        state.edit()
            .putLong(KEY_CLAIMED_FOR, logicalScheduledFor)
            .putString(KEY_CLAIM_OWNER, PROCESS_CLAIM_TOKEN)
            .commit()
        return logicalScheduledFor
    }

    @Synchronized
    fun completeExecution(context: Context, logicalScheduledFor: Long) {
        statePreferences(context).edit()
            .putLong(KEY_LAST_COMPLETED_FOR, logicalScheduledFor)
            .remove(KEY_CLAIMED_FOR)
            .remove(KEY_CLAIM_OWNER)
            .remove(KEY_RETRY_FOR)
            .remove(KEY_RETRY_COUNT)
            .commit()
    }

    @Synchronized
    fun releaseExecution(context: Context, logicalScheduledFor: Long) {
        val state = statePreferences(context)
        if (
            state.getLong(KEY_CLAIMED_FOR, 0L) == logicalScheduledFor &&
            state.getString(KEY_CLAIM_OWNER, null) == PROCESS_CLAIM_TOKEN
        ) {
            state.edit().remove(KEY_CLAIMED_FOR).remove(KEY_CLAIM_OWNER).commit()
        }
    }

    fun retryQueue(context: Context, logicalScheduledFor: Long) {
        releaseExecution(context, logicalScheduledFor)
        scheduleAt(
            context,
            System.currentTimeMillis() + QUEUE_RETRY_DELAY_MILLIS,
            logicalScheduledFor,
        )
    }

    /** Retries a failed write twice before deferring to the next configured interval. */
    @Synchronized
    fun retryRunFailure(context: Context, logicalScheduledFor: Long): Boolean {
        val state = statePreferences(context)
        val retryFor = state.getLong(KEY_RETRY_FOR, 0L)
        val attempts = if (retryFor == logicalScheduledFor) state.getInt(KEY_RETRY_COUNT, 0) else 0
        if (attempts >= MAX_RUN_RETRIES) {
            state.edit().remove(KEY_RETRY_FOR).remove(KEY_RETRY_COUNT).commit()
            return false
        }
        releaseExecution(context, logicalScheduledFor)
        state.edit()
            .putLong(KEY_RETRY_FOR, logicalScheduledFor)
            .putInt(KEY_RETRY_COUNT, attempts + 1)
            .commit()
        scheduleAt(
            context,
            System.currentTimeMillis() + RUN_RETRY_DELAY_MILLIS,
            logicalScheduledFor,
        )
        return true
    }

    fun scheduledFor(context: Context): Long =
        statePreferences(context).getLong(KEY_SCHEDULED_FOR, 0L)

    fun cancel(context: Context) {
        val existing = existingPendingIntent(context)
        context.getSystemService(AlarmManager::class.java)?.let { manager ->
            if (existing != null) manager.cancel(existing)
        }
        existing?.cancel()
        statePreferences(context).edit()
            .remove(KEY_SCHEDULED_FOR)
            .remove(KEY_LAST_COMPLETED_FOR)
            .remove(KEY_CLAIMED_FOR)
            .remove(KEY_CLAIM_OWNER)
            .remove(KEY_RETRY_FOR)
            .remove(KEY_RETRY_COUNT)
            .apply()
    }

    private fun scheduleAt(context: Context, alarmAtMillis: Long, logicalScheduledFor: Long) {
        val manager = context.getSystemService(AlarmManager::class.java) ?: return
        manager.setAndAllowWhileIdle(
            AlarmManager.RTC_WAKEUP,
            alarmAtMillis,
            pendingIntent(context, logicalScheduledFor),
        )
        val state = statePreferences(context)
        state.edit()
            .putLong(KEY_SCHEDULED_FOR, logicalScheduledFor)
            .also { editor ->
                if (state.getLong(KEY_LAST_COMPLETED_FOR, 0L) >= logicalScheduledFor) {
                    editor.remove(KEY_LAST_COMPLETED_FOR)
                }
                if (state.getLong(KEY_CLAIMED_FOR, 0L) != logicalScheduledFor) {
                    editor.remove(KEY_CLAIMED_FOR)
                    editor.remove(KEY_CLAIM_OWNER)
                }
                if (state.getLong(KEY_RETRY_FOR, 0L) != logicalScheduledFor) {
                    editor.remove(KEY_RETRY_FOR)
                    editor.remove(KEY_RETRY_COUNT)
                }
            }
            .apply()
    }

    private fun pendingIntent(context: Context, logicalScheduledFor: Long): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            REQUEST_CODE,
            Intent(context, ScheduledBackupAlarmReceiver::class.java)
                .setAction(ACTION_RUN_SCHEDULED_BACKUP)
                .putExtra(EXTRA_LOGICAL_SCHEDULED_FOR, logicalScheduledFor),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    private fun existingPendingIntent(context: Context): PendingIntent? = PendingIntent.getBroadcast(
        context,
        REQUEST_CODE,
        Intent(context, ScheduledBackupAlarmReceiver::class.java).setAction(ACTION_RUN_SCHEDULED_BACKUP),
        PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun statePreferences(context: Context) = context.applicationContext.getSharedPreferences(
        STATE_PREFERENCES,
        Context.MODE_PRIVATE,
    )

    internal const val ACTION_RUN_SCHEDULED_BACKUP =
        "com.paisalens.app.action.RUN_SCHEDULED_BACKUP"
    internal const val EXTRA_LOGICAL_SCHEDULED_FOR = "logical_scheduled_for"
    private const val REQUEST_CODE = 1901
    private const val STATE_PREFERENCES = "paisalens.scheduled_backup_schedule"
    private const val KEY_SCHEDULED_FOR = "scheduled_for"
    private const val KEY_LAST_COMPLETED_FOR = "last_completed_for"
    private const val KEY_CLAIMED_FOR = "claimed_for"
    private const val KEY_CLAIM_OWNER = "claim_owner"
    private const val KEY_RETRY_FOR = "retry_for"
    private const val KEY_RETRY_COUNT = "retry_count"
    private const val CATCH_UP_DELAY_MILLIS = 2_000L
    private const val QUEUE_RETRY_DELAY_MILLIS = 15L * 60L * 1_000L
    private const val RUN_RETRY_DELAY_MILLIS = 15L * 60L * 1_000L
    private const val MAX_RUN_RETRIES = 2
    private val PROCESS_CLAIM_TOKEN = UUID.randomUUID().toString()
}
