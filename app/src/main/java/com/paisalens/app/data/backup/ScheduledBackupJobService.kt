package com.paisalens.app.data.backup

import android.app.job.JobParameters
import android.app.job.JobService
import com.paisalens.app.PaisaLensApplication
import com.paisalens.app.data.local.UserPreferences
import java.util.concurrent.Executors
import java.util.concurrent.Future

class ScheduledBackupJobService : JobService() {
    private val executor = Executors.newSingleThreadExecutor()
    @Volatile private var running: Future<*>? = null
    @Volatile private var runningScheduledFor: Long = 0L

    override fun onStartJob(params: JobParameters): Boolean {
        val scheduledFor = params.extras.getLong(
            ScheduledBackupScheduler.EXTRA_LOGICAL_SCHEDULED_FOR,
            ScheduledBackupScheduler.scheduledFor(this),
        )
        val claimedFor = ScheduledBackupScheduler.claimExecution(this, scheduledFor)
        if (claimedFor == null) return false
        runningScheduledFor = claimedFor
        running = executor.submit {
            var runFailed = false
            try {
                val preferences = applicationPreferences()
                val configuration = preferences.scheduledBackup.value
                if (!configuration.isReady) {
                    ScheduledBackupScheduler.cancel(applicationContext)
                    return@submit
                }
                val result = try {
                    val passphrase = ScheduledBackupSecretStore(applicationContext).load()
                        ?: error("Scheduled backup password is unavailable. Save it again in Settings.")
                    ScheduledBackupRunner(applicationContext).run(configuration, passphrase)
                } catch (error: Exception) {
                    ScheduledBackupRunResult(
                        succeeded = false,
                        completedAt = System.currentTimeMillis(),
                        failureMessage = error.message?.take(160)
                            ?: "The scheduled backup could not be completed.",
                    )
                }
                if (Thread.currentThread().isInterrupted) return@submit
                if (result.succeeded) {
                    preferences.recordScheduledBackupSuccess(result)
                } else {
                    runFailed = true
                    preferences.recordScheduledBackupFailure(result)
                }
            } finally {
                val wasStopped = Thread.currentThread().isInterrupted || runningScheduledFor != claimedFor
                if (wasStopped) {
                    ScheduledBackupScheduler.releaseExecution(applicationContext, claimedFor)
                    runningScheduledFor = 0L
                } else {
                    val configuration = applicationPreferences().scheduledBackup.value
                    if (
                        runFailed && configuration.isReady &&
                        ScheduledBackupScheduler.retryRunFailure(applicationContext, claimedFor)
                    ) {
                        runningScheduledFor = 0L
                        jobFinished(params, false)
                    } else if (configuration.isReady) {
                        ScheduledBackupScheduler.completeExecution(applicationContext, claimedFor)
                        ScheduledBackupScheduler.sync(applicationContext, configuration)
                    } else {
                        ScheduledBackupScheduler.cancel(applicationContext)
                    }
                    if (runningScheduledFor != 0L) {
                        runningScheduledFor = 0L
                        jobFinished(params, false)
                    }
                }
            }
        }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        running?.cancel(true)
        val scheduledFor = runningScheduledFor
        if (scheduledFor > 0L) ScheduledBackupScheduler.releaseExecution(applicationContext, scheduledFor)
        runningScheduledFor = 0L
        return true
    }

    override fun onDestroy() {
        running?.cancel(true)
        executor.shutdownNow()
        super.onDestroy()
    }

    private fun applicationPreferences(): UserPreferences =
        (application as? PaisaLensApplication)?.preferences ?: UserPreferences(applicationContext)
}
