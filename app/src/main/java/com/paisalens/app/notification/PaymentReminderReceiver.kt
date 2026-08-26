package com.paisalens.app.notification

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.paisalens.app.data.local.PaisaLensDatabase
import com.paisalens.app.data.model.PaymentReminderDelivery
import com.paisalens.app.data.model.PaymentReminderStatus
import com.paisalens.app.data.model.buildPaymentReminderAlerts
import java.time.Instant
import java.time.ZoneId
import java.util.concurrent.Executors

class PaymentReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        if (action !in ALLOWED_ACTIONS) return
        val appContext = context.applicationContext
        val pendingResult = goAsync()
        val executor = Executors.newSingleThreadExecutor()
        executor.execute {
            try {
                when (action) {
                    PaymentReminderScheduler.ACTION_DELIVER -> deliver(appContext, intent)
                    PaymentReminderScheduler.ACTION_SNOOZE -> snooze(appContext, intent)
                }
            } finally {
                pendingResult.finish()
                executor.shutdown()
            }
        }
    }

    private fun deliver(context: Context, intent: Intent) {
        val token = intent.getLongExtra(PaymentReminderScheduler.EXTRA_SCHEDULE_TOKEN, 0L)
        val claim = PaymentReminderScheduler.claimDelivery(context, token) ?: return
        var complete = false
        try {
            if (!PaymentReminderNotifier.canPost(context)) {
                PaymentReminderScheduler.cancel(context)
                complete = true
                return
            }
            val now = System.currentTimeMillis()
            val zone = ZoneId.systemDefault()
            val runtime = loadPaymentReminderRuntime(context, now)
            val rulesById = runtime.rules.associateBy { it.id }
            val database = PaisaLensDatabase(context)
            try {
                duePaymentReminderAlertGroups(runtime, now, zone).forEach { group ->
                    if (!PaymentReminderScheduler.isClaimCurrent(context, claim)) return
                    val chosen = group.last()
                    val runtimeRule = rulesById[chosen.occurrence.ruleId] ?: return@forEach
                    val (rule, freshOccurrence) = currentRuleAndOccurrence(
                        database = database,
                        runtimeRule = runtimeRule,
                        expectedOccurrenceEpochDay = chosen.occurrence.dueDateEpochDay,
                        nowMillis = now,
                        zoneId = zone,
                    ) ?: run {
                        PaymentReminderNotifier.cancelForRule(context, runtimeRule.id)
                        return@forEach
                    }
                    if (!PaymentReminderScheduler.isClaimCurrent(context, claim)) return
                    val freshChosen = chosen.copy(occurrence = freshOccurrence)
                    val accepted = PaymentReminderScheduler.withCurrentClaim(context, claim) {
                        PaymentReminderNotifier.recoverActiveAlert(
                            context,
                            paymentReminderNotificationTag(freshChosen, rule.generation),
                            rule.id,
                        ) || PaymentReminderNotifier.post(context, freshChosen, rule)
                    } ?: return
                    // A disabled per-style channel is a user decision. Record the attempted group
                    // so Android is not woken in a tight retry loop for a channel it must not use.
                    if (
                        accepted || !PaymentReminderNotifier.canPost(
                            context,
                            chosen.occurrence.notificationStyle,
                            chosen.occurrence.vibrate,
                        )
                    ) {
                        val deliveredKeys = buildList {
                            addAll(group)
                            if (chosen.isSnooze) {
                                val clearedRule = rule.copy(
                                    snoozedOccurrenceEpochDay = null,
                                    snoozedUntilMillis = null,
                                )
                                addAll(
                                    buildPaymentReminderAlerts(
                                        rule = clearedRule,
                                        occurrence = freshOccurrence,
                                        settings = runtime.settings,
                                        nowMillis = (now - SNOOZE_SUPERSEDE_WINDOW_MILLIS).coerceAtLeast(0L),
                                        throughMillis = now + DELIVERY_GRACE_MILLIS,
                                        systemZone = zone,
                                    ).filter { it.scheduledAtMillis <= now + DELIVERY_GRACE_MILLIS },
                                )
                            }
                        }.distinctBy { it.stableKey }
                        PaymentReminderScheduler.withCurrentClaim(context, claim) {
                            database.recordPaymentReminderDeliveries(
                                deliveredKeys.map { alert ->
                                    PaymentReminderDelivery(
                                        stableKey = alert.stableKey,
                                        ruleId = alert.occurrence.ruleId,
                                        occurrenceEpochDay = alert.occurrence.dueDateEpochDay,
                                        scheduledAtMillis = alert.scheduledAtMillis,
                                        deliveredAtMillis = now,
                                    )
                                },
                            )
                            if (chosen.isSnooze) {
                                database.consumePaymentReminderSnooze(
                                    ruleId = rule.id,
                                    generation = rule.generation,
                                    occurrenceEpochDay = chosen.occurrence.dueDateEpochDay,
                                )
                            }
                        } ?: return
                    }
                }
                if (claim.includesSummary) {
                    if (!PaymentReminderScheduler.isClaimCurrent(context, claim)) return
                    val summaryDay = Instant.ofEpochMilli(claim.scheduledForMillis).atZone(zone).toLocalDate().toEpochDay()
                    if (PaymentReminderScheduler.lastSummaryEpochDay(context) != summaryDay) {
                        val freshRuntime = loadPaymentReminderRuntime(context, System.currentTimeMillis())
                        val summaryItems = freshRuntime.occurrences.filter { occurrence ->
                            occurrence.status != PaymentReminderStatus.SKIPPED &&
                                occurrence.dueDateEpochDay <= summaryDay + SUMMARY_HORIZON_DAYS
                        }
                        if (!PaymentReminderScheduler.isClaimCurrent(context, claim)) return
                        PaymentReminderScheduler.withCurrentClaim(context, claim) {
                            PaymentReminderNotifier.postSummary(context, summaryItems)
                            PaymentReminderScheduler.recordSummaryDelivered(context, summaryDay)
                        } ?: return
                    }
                }
            } finally {
                database.close()
            }
            PaymentReminderScheduler.completeDelivery(context, claim)
            PaymentReminderScheduler.sync(context)
            complete = true
        } finally {
            if (!complete) PaymentReminderScheduler.retry(context, claim)
        }
    }

    private fun currentRuleAndOccurrence(
        database: PaisaLensDatabase,
        runtimeRule: com.paisalens.app.data.model.PaymentReminderRule,
        expectedOccurrenceEpochDay: Long,
        nowMillis: Long,
        zoneId: ZoneId,
    ): Pair<
        com.paisalens.app.data.model.PaymentReminderRule,
        com.paisalens.app.data.model.PaymentReminderOccurrence,
    >? {
        val currentRule = database.getPaymentReminderRule(runtimeRule.id)
            ?.takeIf { it.enabled && it.generation == runtimeRule.generation }
            ?: return null
        val bills = database.getBills()
        val cards = database.getCreditCardBills()
        val accountNamesById = database.getAccounts().associate { it.id to it.name }
        val today = Instant.ofEpochMilli(nowMillis).atZone(zoneId).toLocalDate()
        val currentOccurrence = com.paisalens.app.data.model.resolvePaymentReminderOccurrences(
            rules = listOf(currentRule),
            targets = com.paisalens.app.data.model.buildPaymentReminderTargets(
                bills,
                cards,
                today,
                accountNamesById,
            ),
            manualBills = bills,
            nowMillis = nowMillis,
            systemZone = zoneId,
        ).singleOrNull()
        return currentOccurrence
            ?.takeIf { it.dueDateEpochDay == expectedOccurrenceEpochDay }
            ?.let { currentRule to it }
    }

    private fun snooze(context: Context, intent: Intent) {
        if (intent.data?.scheme != "paisalens" || intent.data?.authority != "bill-reminder-snooze") return
        val ruleId = intent.getLongExtra(PaymentReminderScheduler.EXTRA_RULE_ID, 0L)
        val generation = intent.getLongExtra(PaymentReminderScheduler.EXTRA_RULE_GENERATION, -1L)
        val occurrenceEpochDay = intent.getLongExtra(
            PaymentReminderScheduler.EXTRA_OCCURRENCE_EPOCH_DAY,
            Long.MIN_VALUE,
        )
        val minutes = intent.getIntExtra(PaymentReminderScheduler.EXTRA_SNOOZE_MINUTES, 0)
        if (ruleId <= 0L || generation < 1L || occurrenceEpochDay == Long.MIN_VALUE || minutes <= 0) return
        val now = System.currentTimeMillis()
        val runtime = loadPaymentReminderRuntime(context, now)
        val rule = runtime.rules.firstOrNull { it.id == ruleId && it.generation == generation } ?: return
        if (runtime.occurrences.none { it.ruleId == ruleId && it.dueDateEpochDay == occurrenceEpochDay }) return
        PaymentReminderScheduler.cancel(context)
        val database = PaisaLensDatabase(context)
        val changed = try {
            database.snoozePaymentReminder(
                ruleId = ruleId,
                occurrenceEpochDay = occurrenceEpochDay,
                minutes = minutes,
                expectedGeneration = generation,
                nowMillis = now,
            )
        } catch (error: Throwable) {
            PaymentReminderScheduler.sync(context)
            throw error
        } finally {
            database.close()
        }
        if (changed) {
            PaymentReminderNotifier.cancelForRule(context, ruleId)
        }
        PaymentReminderScheduler.sync(context)
    }

    private companion object {
        val ALLOWED_ACTIONS = setOf(
            PaymentReminderScheduler.ACTION_DELIVER,
            PaymentReminderScheduler.ACTION_SNOOZE,
        )
        const val SUMMARY_HORIZON_DAYS = 30L
        const val DELIVERY_GRACE_MILLIS = 2L * 60L * 1_000L
        const val SNOOZE_SUPERSEDE_WINDOW_MILLIS = 31L * 24L * 60L * 60L * 1_000L
    }
}
