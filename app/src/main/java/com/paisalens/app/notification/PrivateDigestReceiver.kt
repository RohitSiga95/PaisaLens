package com.paisalens.app.notification

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.paisalens.app.data.local.PaisaLensDatabase
import com.paisalens.app.data.local.UserPreferences
import java.util.concurrent.Executors

class PrivateDigestReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != PrivateDigestScheduler.ACTION_DELIVER_DIGEST) return
        val pendingResult = goAsync()
        val appContext = context.applicationContext
        val executor = Executors.newSingleThreadExecutor()
        executor.execute {
            try {
                val configuration = UserPreferences(appContext).notificationDigest.value
                if (!configuration.enabled) {
                    PrivateDigestScheduler.cancel(appContext)
                    return@execute
                }
                val scheduledFor = PrivateDigestScheduler.claimDelivery(appContext) ?: return@execute
                var completed = false
                try {
                    val database = PaisaLensDatabase(appContext)
                val summary = try {
                    buildPrivateDigestSummary(
                        transactions = database.getTransactions(),
                        transactionLinks = database.getTransactionLinks(),
                        expenseSplits = database.getExpenseSplits(),
                            bills = database.getBills(),
                            savingsGoals = database.getSavingsGoals(),
                            savingsContributions = database.getSavingsContributions(),
                            paymentCommitments = database.getPaymentCommitments(),
                            configuration = configuration,
                        )
                    } finally {
                        database.close()
                    }
                    PrivateDigestNotifier.post(
                        appContext,
                        buildPrivateDigestText(summary, configuration),
                    )
                    PrivateDigestScheduler.completeDelivery(appContext, scheduledFor)
                    completed = true
                } finally {
                    if (completed) {
                        // A repeating exact alarm is intentionally avoided; each completed delivery schedules the next inexact one.
                        PrivateDigestScheduler.sync(appContext, configuration)
                    } else {
                        PrivateDigestScheduler.retry(appContext, scheduledFor)
                    }
                }
            } finally {
                pendingResult.finish()
                executor.shutdown()
            }
        }
    }
}
