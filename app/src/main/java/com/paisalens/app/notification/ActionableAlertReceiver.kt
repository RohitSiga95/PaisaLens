package com.paisalens.app.notification

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.paisalens.app.data.local.PaisaLensDatabase
import com.paisalens.app.data.local.UserPreferences
import com.paisalens.app.data.model.ActionableAlertCategory
import com.paisalens.app.data.model.BackupReviewState
import com.paisalens.app.data.model.PlanningReviewInput
import com.paisalens.app.data.model.buildCashFlowForecast
import com.paisalens.app.data.model.buildCreditUtilizations
import com.paisalens.app.data.model.buildDueItems
import com.paisalens.app.data.model.buildNeedsAttentionSummary
import com.paisalens.app.data.model.buildPaymentCommitmentDueItems
import com.paisalens.app.data.model.evaluateBudgetPlan
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.Executors

class ActionableAlertReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != ActionableAlertScheduler.ACTION_EVALUATE_ALERTS) return
        val pendingResult = goAsync()
        val appContext = context.applicationContext
        val executor = Executors.newSingleThreadExecutor()
        executor.execute {
            try {
                evaluateAndDeliver(appContext)
            } finally {
                pendingResult.finish()
                executor.shutdown()
            }
        }
    }

    private fun evaluateAndDeliver(context: Context) {
        // Claim before constructing UserPreferences: its initialization ensures alarms, and must
        // see this live claim rather than enqueueing a second catch-up evaluation.
        val scheduledFor = ActionableAlertScheduler.claimDelivery(context) ?: return
        val preferences = UserPreferences(context)
        val configuration = preferences.actionableAlerts.value
        if (!configuration.enabled) {
            ActionableAlertScheduler.cancel(context)
            return
        }
        var completed = false
        try {
            val nowMillis = System.currentTimeMillis()
            val zoneId = ZoneId.systemDefault()
            val today = LocalDate.now(zoneId)
            val database = PaisaLensDatabase(context)
            val candidates = try {
                val transactions = database.getTransactions()
                val transactionLinks = database.getTransactionLinks()
                val expenseSplits = database.getExpenseSplits()
                val accounts = database.getAccounts()
                val balanceHistory = database.getBalanceHistory()
                val bills = database.getBills()
                val creditCardBills = database.getCreditCardBills()
                val paymentCommitments = database.getPaymentCommitments()
                val savingsGoals = database.getSavingsGoals()
                val savingsContributions = database.getSavingsContributions()
                val loans = database.getLoans()
                val backupStatus = preferences.scheduledBackupStatus.value
                val attention = buildNeedsAttentionSummary(
                    input = PlanningReviewInput(
                        transactions = transactions,
                        accounts = accounts,
                        bills = bills,
                        creditCardBills = creditCardBills,
                        savingsGoals = savingsGoals,
                        savingsContributions = savingsContributions,
                        paymentCommitments = paymentCommitments,
                        transactionLinks = transactionLinks,
                        expenseSplits = expenseSplits,
                        backup = BackupReviewState(
                            lastSuccessfulAt = backupStatus.lastSuccessfulAt,
                            lastVerifiedAt = backupStatus.lastVerifiedAt,
                            lastFailureAt = backupStatus.lastFailureAt,
                            lastFailureMessage = backupStatus.lastFailureMessage,
                            scheduledBackupEnabled = preferences.scheduledBackup.value.enabled,
                        ),
                    ),
                    today = today,
                    zoneId = zoneId,
                    upcomingDueDays = configuration.dueWindowDays,
                )
                val budgetTransactions = buildActionableBudgetTransactions(
                    transactions,
                    transactionLinks,
                    expenseSplits,
                )
                val budgetPeriods = database.getAdvancedBudgetPlans().mapNotNull { plan ->
                    evaluateBudgetPlan(plan, budgetTransactions, today, zoneId)
                }
                val creditUtilizations = buildCreditUtilizations(accounts, balanceHistory)
                val cashFlowForecast = if (
                    configuration.isEnabled(ActionableAlertCategory.LOW_CASH_FLOW)
                ) {
                    val openingBalance = consolidatedCashFlowOpeningBalance(accounts)
                    if (openingBalance == null) {
                        null
                    } else {
                        val dueItems = buildDueItems(
                            manualBills = bills,
                            recurringPayments = emptyList(),
                            loans = loans,
                            today = today,
                            zoneId = zoneId,
                            horizonDays = CASH_FLOW_HORIZON_DAYS,
                            includeRepeatingOccurrences = true,
                        ) + buildPaymentCommitmentDueItems(
                            commitments = paymentCommitments,
                            today = today,
                            horizonDays = CASH_FLOW_HORIZON_DAYS,
                            includeRepeatingOccurrences = true,
                            accountNamesById = accounts.associate { it.id to it.name },
                        )
                        val bankAccountIds = accounts
                            .filter { it.type == com.paisalens.app.data.model.AccountType.BANK_ACCOUNT }
                            .mapTo(mutableSetOf()) { it.id }
                        val cashFlowTransactions = transactions.filter {
                            it.accountId == null || it.accountId in bankAccountIds
                        }
                        buildCashFlowForecast(
                            openingBalanceMinor = openingBalance,
                            transactions = cashFlowTransactions,
                            dueItems = dueItems,
                            asOf = today,
                            zoneId = zoneId,
                            horizonDays = CASH_FLOW_HORIZON_DAYS,
                            transactionLinks = transactionLinks,
                        )
                    }
                } else {
                    null
                }
                buildActionableAlertCandidates(
                    input = ActionableAlertInput(
                        attentionItems = attention.items,
                        budgetPeriods = budgetPeriods,
                        creditUtilizations = creditUtilizations,
                        cashFlowForecast = cashFlowForecast,
                        expenseSplits = expenseSplits,
                    ),
                    configuration = configuration,
                    nowMillis = nowMillis,
                    zoneId = zoneId,
                )
            } finally {
                database.close()
            }
            val deliveryStore = ActionableAlertDeliveryStore(context)
            val selected = selectActionableAlertsForDelivery(
                candidates = candidates,
                deliveryHistory = deliveryStore.historyFor(candidates),
                nowMillis = nowMillis,
                minimumRepeatHours = configuration.minimumRepeatHours,
            )
            val content = buildActionableAlertText(selected, configuration)
            if (content != null && ActionableAlertNotifier.post(context, content)) {
                deliveryStore.record(selected, nowMillis)
            }
            ActionableAlertScheduler.completeDelivery(context, scheduledFor)
            completed = true
        } finally {
            if (completed) {
                ActionableAlertScheduler.sync(context, configuration)
            } else {
                ActionableAlertScheduler.retry(context, scheduledFor)
            }
        }
    }

    private companion object {
        const val CASH_FLOW_HORIZON_DAYS = 14
    }
}
