package com.paisalens.app.data.backup

import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.PersistableBundle

class ScheduledBackupAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != ScheduledBackupScheduler.ACTION_RUN_SCHEDULED_BACKUP) return
        val scheduledFor = intent.getLongExtra(
            ScheduledBackupScheduler.EXTRA_LOGICAL_SCHEDULED_FOR,
            ScheduledBackupScheduler.scheduledFor(context),
        )
        if (scheduledFor <= 0L || !enqueueScheduledBackupJob(context, scheduledFor)) {
            if (scheduledFor > 0L) ScheduledBackupScheduler.retryQueue(context, scheduledFor)
        }
    }
}

private fun enqueueScheduledBackupJob(context: Context, scheduledFor: Long): Boolean {
    val extras = PersistableBundle().apply {
        putLong(ScheduledBackupScheduler.EXTRA_LOGICAL_SCHEDULED_FOR, scheduledFor)
    }
    val job = JobInfo.Builder(
        SCHEDULED_BACKUP_JOB_ID,
        ComponentName(context, ScheduledBackupJobService::class.java),
    )
        .setExtras(extras)
        .setMinimumLatency(0L)
        .setOverrideDeadline(1_000L)
        .build()
    return context.getSystemService(JobScheduler::class.java)?.schedule(job) == JobScheduler.RESULT_SUCCESS
}

private const val SCHEDULED_BACKUP_JOB_ID = 1902
