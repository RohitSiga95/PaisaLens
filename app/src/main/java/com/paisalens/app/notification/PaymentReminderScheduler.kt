package com.paisalens.app.notification

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.paisalens.app.data.local.PaisaLensDatabase
import com.paisalens.app.data.model.PaymentReminderAlert
import com.paisalens.app.data.model.PaymentReminderOccurrence
import com.paisalens.app.data.model.PaymentReminderRule
import com.paisalens.app.data.model.PaymentReminderSettings
import com.paisalens.app.data.model.buildPaymentReminderAlerts
import com.paisalens.app.data.model.buildPaymentReminderTargets
import com.paisalens.app.data.model.nextPaymentReminderSummaryAt
import com.paisalens.app.data.model.resolvePaymentReminderOccurrences
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

object PaymentReminderScheduler {
    fun ensure(context: Context) = sync(context)

    /** Reconciles one battery-friendly inexact alarm against the persisted source-of-truth. */
    @Synchronized
    fun sync(context: Context, nowMillis: Long = System.currentTimeMillis()) {
        val appContext = context.applicationContext
        val runtime = loadPaymentReminderRuntime(appContext, nowMillis)
        PaymentReminderNotifier.reconcileActiveNotifications(appContext, runtime.rules, runtime.occurrences)
        val activeOccurrenceRuleIds = runtime.occurrences.mapTo(mutableSetOf(), PaymentReminderOccurrence::ruleId)
        runtime.rules.filter { !it.enabled || it.id !in activeOccurrenceRuleIds }.forEach { rule ->
            PaymentReminderNotifier.cancelForRule(appContext, rule.id)
        }
        if (!runtime.settings.enabled) PaymentReminderNotifier.cancelAll(appContext)
        if (
            !runtime.settings.enabled ||
            runtime.rules.none(PaymentReminderRule::enabled) ||
            runtime.occurrences.isEmpty() ||
            !PaymentReminderNotifier.canPost(appContext)
        ) {
            cancel(appContext)
            return
        }
        val state = statePreferences(appContext)
        val liveToken = state.getLong(KEY_TOKEN, 0L)
        val claimedAt = state.getLong(KEY_CLAIMED_AT, 0L)
        if (isUnexpiredPaymentReminderClaim(
                currentToken = liveToken,
                claimedToken = state.getLong(KEY_CLAIMED_TOKEN, 0L),
                claimedAtMillis = claimedAt,
                nowMillis = nowMillis,
            )
        ) {
            appContext.getSystemService(AlarmManager::class.java)?.setAndAllowWhileIdle(
                AlarmManager.RTC_WAKEUP,
                (claimedAt + CLAIM_TIMEOUT_MILLIS).coerceAtLeast(nowMillis + CATCH_UP_DELAY_MILLIS),
                deliveryPendingIntent(appContext, liveToken),
            )
            return
        }
        val plan = selectNextPaymentReminderAlarm(
            rules = runtime.rules,
            occurrences = runtime.occurrences,
            settings = runtime.settings,
            deliveredKeys = runtime.deliveredKeys,
            nowMillis = nowMillis,
            lastSummaryEpochDay = state.getLong(KEY_LAST_SUMMARY_EPOCH_DAY, Long.MIN_VALUE),
            zoneId = ZoneId.systemDefault(),
        ) ?: run {
            cancel(appContext)
            return
        }
        val previousLogicalKey = state.getString(KEY_LOGICAL_KEY, null)
        val previousAt = state.getLong(KEY_SCHEDULED_FOR, 0L)
        val reusingPlan = previousLogicalKey == plan.logicalKey && previousAt == plan.alarmAtMillis
        val token = if (reusingPlan) {
            state.getLong(KEY_TOKEN, 0L).takeIf { it > 0L }
                ?: nextToken(state.getLong(KEY_TOKEN_COUNTER, 0L))
        } else {
            nextToken(state.getLong(KEY_TOKEN_COUNTER, 0L))
        }
        val editor = state.edit()
                .putLong(KEY_TOKEN_COUNTER, token)
                .putLong(KEY_TOKEN, token)
                .putString(KEY_LOGICAL_KEY, plan.logicalKey)
                .putLong(KEY_SCHEDULED_FOR, plan.alarmAtMillis)
                .putBoolean(KEY_INCLUDES_SUMMARY, plan.includesSummary)
        if (!reusingPlan || state.getLong(KEY_CLAIMED_TOKEN, 0L) != token) {
            editor.remove(KEY_CLAIMED_TOKEN).remove(KEY_CLAIMED_AT)
        }
        check(
            editor.commit(),
        ) { "Bill reminder schedule could not be persisted" }
        val alarmManager = appContext.getSystemService(AlarmManager::class.java) ?: return
        // Bills tolerate Android's inexact delivery window. No exact-alarm or full-screen access.
        alarmManager.setAndAllowWhileIdle(
            AlarmManager.RTC_WAKEUP,
            if (state.getLong(KEY_CLAIMED_TOKEN, 0L) == token) {
                (state.getLong(KEY_CLAIMED_AT, nowMillis) + CLAIM_TIMEOUT_MILLIS)
                    .coerceAtLeast(nowMillis + CATCH_UP_DELAY_MILLIS)
            } else {
                plan.alarmAtMillis
            },
            deliveryPendingIntent(appContext, token),
        )
    }

    @Synchronized
    internal fun claimDelivery(
        context: Context,
        token: Long,
        nowMillis: Long = System.currentTimeMillis(),
    ): PaymentReminderAlarmClaim? {
        if (token <= 0L) return null
        val state = statePreferences(context)
        if (state.getLong(KEY_TOKEN, 0L) != token) return null
        val now = nowMillis
        val claimedAt = state.getLong(KEY_CLAIMED_AT, 0L)
        if (isUnexpiredPaymentReminderClaim(token, state.getLong(KEY_CLAIMED_TOKEN, 0L), claimedAt, now)) {
            return null
        }
        val claimNonce = nextToken(state.getLong(KEY_CLAIM_NONCE, 0L))
        if (
            !state.edit()
                .putLong(KEY_CLAIMED_TOKEN, token)
                .putLong(KEY_CLAIMED_AT, now)
                .putLong(KEY_CLAIM_NONCE, claimNonce)
                .commit()
        ) return null
        context.applicationContext.getSystemService(AlarmManager::class.java)?.setAndAllowWhileIdle(
            AlarmManager.RTC_WAKEUP,
            now + CLAIM_TIMEOUT_MILLIS,
            deliveryPendingIntent(context.applicationContext, token),
        )
        return PaymentReminderAlarmClaim(
            token = token,
            claimNonce = claimNonce,
            scheduledForMillis = state.getLong(KEY_SCHEDULED_FOR, 0L),
            includesSummary = state.getBoolean(KEY_INCLUDES_SUMMARY, false),
        )
    }

    @Synchronized
    internal fun completeDelivery(context: Context, claim: PaymentReminderAlarmClaim) {
        val state = statePreferences(context)
        if (!isClaimCurrent(context, claim)) return
        existingDeliveryPendingIntent(context.applicationContext)?.let { pending ->
            context.applicationContext.getSystemService(AlarmManager::class.java)?.cancel(pending)
        }
        state.edit()
            .remove(KEY_TOKEN)
            .remove(KEY_LOGICAL_KEY)
            .remove(KEY_SCHEDULED_FOR)
            .remove(KEY_INCLUDES_SUMMARY)
            .remove(KEY_CLAIMED_TOKEN)
            .remove(KEY_CLAIMED_AT)
            .commit()
    }

    @Synchronized
    internal fun releaseDelivery(context: Context, claim: PaymentReminderAlarmClaim) {
        val state = statePreferences(context)
        if (isClaimCurrent(context, claim)) {
            state.edit().remove(KEY_CLAIMED_TOKEN).remove(KEY_CLAIMED_AT).commit()
        }
    }

    internal fun isClaimCurrent(context: Context, claim: PaymentReminderAlarmClaim): Boolean {
        val state = statePreferences(context)
        return state.getLong(KEY_TOKEN, 0L) == claim.token &&
            state.getLong(KEY_CLAIMED_TOKEN, 0L) == claim.token &&
            state.getLong(KEY_CLAIM_NONCE, 0L) == claim.claimNonce
    }

    /** Fences notification posting against restore/cancel, which uses the same scheduler lock. */
    @Synchronized
    internal fun <T> withCurrentClaim(
        context: Context,
        claim: PaymentReminderAlarmClaim,
        action: () -> T,
    ): T? = if (isClaimCurrent(context, claim)) action() else null

    @Synchronized
    internal fun retry(context: Context, claim: PaymentReminderAlarmClaim) {
        val state = statePreferences(context)
        if (!isClaimCurrent(context, claim)) return
        val retryAt = System.currentTimeMillis() + RETRY_DELAY_MILLIS
        state.edit()
            .remove(KEY_CLAIMED_TOKEN)
            .remove(KEY_CLAIMED_AT)
            .putLong(KEY_SCHEDULED_FOR, retryAt)
            .commit()
        context.applicationContext.getSystemService(AlarmManager::class.java)?.setAndAllowWhileIdle(
            AlarmManager.RTC_WAKEUP,
            retryAt,
            deliveryPendingIntent(context.applicationContext, claim.token),
        )
    }

    internal fun recordSummaryDelivered(context: Context, epochDay: Long) {
        statePreferences(context).edit().putLong(KEY_LAST_SUMMARY_EPOCH_DAY, epochDay).apply()
    }

    internal fun lastSummaryEpochDay(context: Context): Long =
        statePreferences(context).getLong(KEY_LAST_SUMMARY_EPOCH_DAY, Long.MIN_VALUE)

    @Synchronized
    fun cancel(context: Context) {
        val appContext = context.applicationContext
        existingDeliveryPendingIntent(appContext)?.let { pending ->
            appContext.getSystemService(AlarmManager::class.java)?.cancel(pending)
            pending.cancel()
        }
        val state = statePreferences(appContext)
        val summaryDay = state.getLong(KEY_LAST_SUMMARY_EPOCH_DAY, Long.MIN_VALUE)
        val counter = state.getLong(KEY_TOKEN_COUNTER, 0L)
        check(state.edit().clear().putLong(KEY_TOKEN_COUNTER, counter).also { editor ->
            if (summaryDay != Long.MIN_VALUE) editor.putLong(KEY_LAST_SUMMARY_EPOCH_DAY, summaryDay)
        }.commit()) { "Bill reminder schedule could not be cleared" }
    }

    private fun deliveryPendingIntent(context: Context, token: Long): PendingIntent = PendingIntent.getBroadcast(
        context,
        DELIVERY_REQUEST_CODE,
        Intent(context, PaymentReminderReceiver::class.java)
            .setAction(ACTION_DELIVER)
            .putExtra(EXTRA_SCHEDULE_TOKEN, token),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun existingDeliveryPendingIntent(context: Context): PendingIntent? = PendingIntent.getBroadcast(
        context,
        DELIVERY_REQUEST_CODE,
        Intent(context, PaymentReminderReceiver::class.java).setAction(ACTION_DELIVER),
        PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun statePreferences(context: Context) = context.applicationContext.getSharedPreferences(
        STATE_PREFERENCES,
        Context.MODE_PRIVATE,
    )

    private fun nextToken(previous: Long): Long = if (previous == Long.MAX_VALUE) 1L else previous + 1L

    internal const val ACTION_DELIVER = "com.paisalens.app.action.DELIVER_PAYMENT_REMINDERS"
    internal const val ACTION_SNOOZE = "com.paisalens.app.action.SNOOZE_PAYMENT_REMINDER"
    internal const val EXTRA_SCHEDULE_TOKEN = "schedule_token"
    internal const val EXTRA_RULE_ID = "rule_id"
    internal const val EXTRA_RULE_GENERATION = "rule_generation"
    internal const val EXTRA_OCCURRENCE_EPOCH_DAY = "occurrence_epoch_day"
    internal const val EXTRA_SNOOZE_MINUTES = "snooze_minutes"

    private const val DELIVERY_REQUEST_CODE = 19_030
    private const val STATE_PREFERENCES = "paisalens.payment_reminder_schedule"
    private const val KEY_TOKEN_COUNTER = "token_counter"
    private const val KEY_TOKEN = "token"
    private const val KEY_LOGICAL_KEY = "logical_key"
    private const val KEY_SCHEDULED_FOR = "scheduled_for"
    private const val KEY_INCLUDES_SUMMARY = "includes_summary"
    private const val KEY_CLAIMED_TOKEN = "claimed_token"
    private const val KEY_CLAIMED_AT = "claimed_at"
    private const val KEY_CLAIM_NONCE = "claim_nonce"
    private const val KEY_LAST_SUMMARY_EPOCH_DAY = "last_summary_epoch_day"
}

internal data class PaymentReminderAlarmClaim(
    val token: Long,
    val claimNonce: Long,
    val scheduledForMillis: Long,
    val includesSummary: Boolean,
)

internal data class PaymentReminderAlarmPlan(
    val alarmAtMillis: Long,
    val logicalKey: String,
    val includesSummary: Boolean,
)

internal fun isUnexpiredPaymentReminderClaim(
    currentToken: Long,
    claimedToken: Long,
    claimedAtMillis: Long,
    nowMillis: Long,
): Boolean = currentToken > 0L &&
    claimedToken == currentToken &&
    claimedAtMillis in 1L..nowMillis &&
    nowMillis - claimedAtMillis < CLAIM_TIMEOUT_MILLIS

internal data class PaymentReminderRuntime(
    val rules: List<PaymentReminderRule>,
    val settings: PaymentReminderSettings,
    val occurrences: List<PaymentReminderOccurrence>,
    val deliveredKeys: Set<String>,
)

internal fun loadPaymentReminderRuntime(
    context: Context,
    nowMillis: Long,
): PaymentReminderRuntime {
    val database = PaisaLensDatabase(context.applicationContext)
    return try {
        var rules = database.getPaymentReminderRules()
        val settings = database.getPaymentReminderSettings()
        val bills = database.getBills()
        val cardBills = database.getCreditCardBills()
        val accountNamesById = database.getAccounts().associate { it.id to it.name }
        val zone = ZoneId.systemDefault()
        val today = Instant.ofEpochMilli(nowMillis).atZone(zone).toLocalDate()
        val targets = buildPaymentReminderTargets(bills, cardBills, today, accountNamesById)
        fun resolve(currentRules: List<PaymentReminderRule>) = resolvePaymentReminderOccurrences(
            rules = currentRules,
            targets = targets,
            manualBills = bills,
            nowMillis = nowMillis,
            systemZone = zone,
        )
        var occurrences = resolve(rules)
        database.prunePaymentReminderDeliveries(nowMillis - DELIVERY_RETENTION_MILLIS)
        val deliveredKeys = database.getPaymentReminderDeliveryKeys()
        val deliveredSnoozes = deliveredPaymentReminderSnoozeStates(rules, occurrences, deliveredKeys)
        val consumedDeliveredSnooze = deliveredSnoozes.map { (rule, occurrence) ->
                database.consumePaymentReminderSnooze(
                    ruleId = rule.id,
                    generation = rule.generation,
                    occurrenceEpochDay = occurrence.dueDateEpochDay,
                )
            }.any { it }
        if (consumedDeliveredSnooze) {
            rules = database.getPaymentReminderRules()
            occurrences = resolve(rules)
        }
        PaymentReminderRuntime(
            rules = rules,
            settings = settings,
            occurrences = occurrences,
            deliveredKeys = deliveredKeys,
        )
    } finally {
        database.close()
    }
}

internal fun deliveredPaymentReminderSnoozeStates(
    rules: List<PaymentReminderRule>,
    occurrences: List<PaymentReminderOccurrence>,
    deliveredKeys: Set<String>,
): List<Pair<PaymentReminderRule, PaymentReminderOccurrence>> {
    val occurrencesByRuleId = occurrences.associateBy(PaymentReminderOccurrence::ruleId)
    return rules.mapNotNull { rule ->
        val occurrence = occurrencesByRuleId[rule.id] ?: return@mapNotNull null
        if (
            rule.snoozedOccurrenceEpochDay != occurrence.dueDateEpochDay ||
            rule.snoozedUntilMillis == null
        ) return@mapNotNull null
        val prefix = "payment-reminder:${rule.id}:${occurrence.dueDateEpochDay}:snooze:"
        (rule to occurrence).takeIf { deliveredKeys.any { it.startsWith(prefix) } }
    }
}

internal fun selectNextPaymentReminderAlarm(
    rules: List<PaymentReminderRule>,
    occurrences: List<PaymentReminderOccurrence>,
    settings: PaymentReminderSettings,
    deliveredKeys: Set<String>,
    nowMillis: Long,
    lastSummaryEpochDay: Long,
    zoneId: ZoneId,
): PaymentReminderAlarmPlan? {
    val safeSettings = settings.normalized()
    if (!safeSettings.enabled) return null
    val rulesById = rules.associateBy(PaymentReminderRule::id)
    val planningFrom = (nowMillis - CATCH_UP_WINDOW_MILLIS).coerceAtLeast(0L)
    val through = nowMillis + PLANNING_HORIZON_MILLIS
    val pending = occurrences.flatMap { occurrence ->
        val rule = rulesById[occurrence.ruleId] ?: return@flatMap emptyList()
        buildPaymentReminderAlerts(rule, occurrence, safeSettings, planningFrom, through, zoneId)
    }.filter { it.stableKey !in deliveredKeys }
    val missed = pending.filter { it.scheduledAtMillis <= nowMillis + DELIVERY_GRACE_MILLIS }
    val nextAlertAt = when {
        missed.isNotEmpty() -> nowMillis + CATCH_UP_DELAY_MILLIS
        else -> pending.minOfOrNull(PaymentReminderAlert::scheduledAtMillis)
    }
    val now = Instant.ofEpochMilli(nowMillis).atZone(zoneId)
    val summary = nextPaymentReminderSummaryAt(safeSettings, now)
        ?.takeIf { it.toLocalDate().toEpochDay() != lastSummaryEpochDay }
    val summaryAt = summary?.toInstant()?.toEpochMilli()
    val chosen = listOfNotNull(nextAlertAt, summaryAt).minOrNull()
        ?: if (rules.any(PaymentReminderRule::enabled)) nowMillis + MAINTENANCE_INTERVAL_MILLIS else return null
    val includesSummary = summaryAt != null && summaryAt <= chosen + SIMULTANEOUS_WINDOW_MILLIS
    val logicalKey = when {
        missed.isNotEmpty() && chosen == nextAlertAt -> {
            val identity = missed.minBy(PaymentReminderAlert::stableKey).stableKey
            "catch-up:$identity:${missed.size}"
        }
        nextAlertAt != null && chosen == nextAlertAt -> {
            val identity = pending.filter { it.scheduledAtMillis == nextAlertAt }
                .minBy(PaymentReminderAlert::stableKey).stableKey
            "alert:$identity"
        }
        includesSummary -> "summary:${summary?.toLocalDate()?.toEpochDay()}"
        else -> "maintenance:${Instant.ofEpochMilli(chosen).atZone(zoneId).toLocalDate()}"
    }
    return PaymentReminderAlarmPlan(chosen, logicalKey, includesSummary)
}

internal fun duePaymentReminderAlertGroups(
    runtime: PaymentReminderRuntime,
    nowMillis: Long,
    zoneId: ZoneId,
): List<List<PaymentReminderAlert>> {
    val rulesById = runtime.rules.associateBy(PaymentReminderRule::id)
    val planningFrom = (nowMillis - CATCH_UP_WINDOW_MILLIS).coerceAtLeast(0L)
    val due = runtime.occurrences.flatMap { occurrence ->
        val rule = rulesById[occurrence.ruleId] ?: return@flatMap emptyList()
        buildPaymentReminderAlerts(
            rule,
            occurrence,
            runtime.settings,
            planningFrom,
            nowMillis + DELIVERY_GRACE_MILLIS,
            zoneId,
        )
    }.filter {
        it.scheduledAtMillis <= nowMillis + DELIVERY_GRACE_MILLIS &&
            it.stableKey !in runtime.deliveredKeys
    }
    return due.groupBy { it.occurrence.ruleId to it.occurrence.dueDateEpochDay }
        .values
        .map { it.sortedBy(PaymentReminderAlert::scheduledAtMillis) }
        .sortedBy { it.last().scheduledAtMillis }
}

private const val CATCH_UP_DELAY_MILLIS = 1_000L
private const val DELIVERY_GRACE_MILLIS = 2L * 60L * 1_000L
private const val CATCH_UP_WINDOW_MILLIS = 31L * 24L * 60L * 60L * 1_000L
private const val PLANNING_HORIZON_MILLIS = 400L * 24L * 60L * 60L * 1_000L
private const val MAINTENANCE_INTERVAL_MILLIS = 30L * 24L * 60L * 60L * 1_000L
private const val DELIVERY_RETENTION_MILLIS = 400L * 24L * 60L * 60L * 1_000L
private const val SIMULTANEOUS_WINDOW_MILLIS = 60_000L
private const val RETRY_DELAY_MILLIS = 15L * 60L * 1_000L
internal const val CLAIM_TIMEOUT_MILLIS = 15L * 60L * 1_000L
