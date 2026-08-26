package com.paisalens.app.notification

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import com.paisalens.app.MainActivity
import com.paisalens.app.PaisaLensApplication
import com.paisalens.app.R
import com.paisalens.app.data.local.UserPreferences
import com.paisalens.app.data.model.PaymentReminderAlert
import com.paisalens.app.data.model.PaymentReminderNotificationStyle
import com.paisalens.app.data.model.PaymentReminderOccurrence
import com.paisalens.app.data.model.PaymentReminderPriority
import com.paisalens.app.data.model.PaymentReminderRule
import com.paisalens.app.data.model.PaymentReminderStatus
import com.paisalens.app.ui.privacy.PrivacyModeRuntime
import com.paisalens.app.widget.WIDGET_DESTINATION_EXTRA
import com.paisalens.app.widget.WIDGET_DESTINATION_PAYMENT_REMINDERS
import java.text.NumberFormat
import java.time.LocalDate
import java.util.Currency
import java.util.Locale

object PaymentReminderNotifier {
    const val CHANNEL_STANDARD = "bill_payment_reminders"
    const val CHANNEL_STANDARD_VIBRATE = "bill_payment_reminders_vibrate"
    const val CHANNEL_PROMINENT = "bill_payment_reminders_prominent"
    const val CHANNEL_PROMINENT_NO_VIBRATE = "bill_payment_reminders_prominent_no_vibrate"
    const val CHANNEL_SILENT = "bill_payment_reminders_silent"
    const val EXTRA_TARGET_TYPE = "com.paisalens.app.extra.REMINDER_TARGET_TYPE"
    const val EXTRA_TARGET_KEY = "com.paisalens.app.extra.REMINDER_TARGET_KEY"

    fun ensureChannels(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_STANDARD,
                "Bill payment reminders",
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply {
                description = "Private reminders for utility and credit-card bills"
                lockscreenVisibility = Notification.VISIBILITY_PRIVATE
                setShowBadge(true)
                enableVibration(false)
            },
        )
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_STANDARD_VIBRATE,
                "Bill payment reminders with vibration",
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply {
                description = "User-selected vibrating reminders for utility and credit-card bills"
                lockscreenVisibility = Notification.VISIBILITY_PRIVATE
                setShowBadge(true)
                enableVibration(true)
            },
        )
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_PROMINENT,
                "Prominent bill payment reminders",
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = "User-selected prominent reminders for urgent bills"
                lockscreenVisibility = Notification.VISIBILITY_PRIVATE
                setShowBadge(true)
                enableVibration(true)
            },
        )
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_PROMINENT_NO_VIBRATE,
                "Prominent bill reminders without vibration",
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = "User-selected prominent bill reminders without vibration"
                lockscreenVisibility = Notification.VISIBILITY_PRIVATE
                setShowBadge(true)
                enableVibration(false)
            },
        )
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_SILENT,
                "Silent bill payment reminders",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "Silent bill reminders and daily bill summary"
                lockscreenVisibility = Notification.VISIBILITY_PRIVATE
                setShowBadge(false)
                setSound(null, null)
                enableVibration(false)
            },
        )
    }

    fun canPost(
        context: Context,
        style: PaymentReminderNotificationStyle? = null,
        vibrate: Boolean? = null,
    ): Boolean {
        ensureChannels(context)
        val manager = context.getSystemService(NotificationManager::class.java) ?: return false
        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return false
        if (!manager.areNotificationsEnabled()) return false
        val channelIds = when (style) {
            PaymentReminderNotificationStyle.PROMINENT -> when (vibrate) {
                true -> listOf(CHANNEL_PROMINENT)
                false -> listOf(CHANNEL_PROMINENT_NO_VIBRATE)
                null -> listOf(CHANNEL_PROMINENT, CHANNEL_PROMINENT_NO_VIBRATE)
            }
            PaymentReminderNotificationStyle.SILENT -> listOf(CHANNEL_SILENT)
            PaymentReminderNotificationStyle.STANDARD -> when (vibrate) {
                true -> listOf(CHANNEL_STANDARD_VIBRATE)
                false -> listOf(CHANNEL_STANDARD)
                null -> listOf(CHANNEL_STANDARD, CHANNEL_STANDARD_VIBRATE)
            }
            null -> listOf(
                CHANNEL_STANDARD,
                CHANNEL_STANDARD_VIBRATE,
                CHANNEL_PROMINENT,
                CHANNEL_PROMINENT_NO_VIBRATE,
                CHANNEL_SILENT,
            )
        }
        return channelIds.any { manager.getNotificationChannel(it)?.importance != NotificationManager.IMPORTANCE_NONE }
    }

    fun settingsIntent(context: Context): Intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
        putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
    }

    /** Returns true only if Android accepted this local notification. */
    fun post(context: Context, alert: PaymentReminderAlert, rule: PaymentReminderRule): Boolean {
        val occurrence = alert.occurrence
        if (!canPost(context, occurrence.notificationStyle, occurrence.vibrate)) return false
        val manager = context.getSystemService(NotificationManager::class.java) ?: return false
        val channelId = channelFor(occurrence.notificationStyle, occurrence.vibrate)
        val preferences = (context.applicationContext as? PaisaLensApplication)?.preferences
            ?: UserPreferences(context.applicationContext)
        val sensitiveDetailsVisible = paymentReminderSensitiveDetailsVisible(
            appLockEnabled = preferences.appLockEnabled,
            privacyDefaultEnabled = preferences.privacyModeConfiguration.value.defaultEnabled,
            sessionPrivacyOverride = preferences.privacyModeSessionOverride.value,
            runtimePrivacyActive = PrivacyModeRuntime.active,
        )
        val content = buildPaymentReminderNotificationContent(
            alert = alert,
            sensitiveDetailsVisible = sensitiveDetailsVisible,
            currencyCode = preferences.baseCurrency,
        )
        val notificationTag = paymentReminderNotificationTag(alert, rule.generation)
        val publicVersion = Notification.Builder(context, channelId)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("PaisaLens bill reminder")
            .setContentText("A bill needs your attention")
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setLocalOnly(true)
            .build()
        val builder = Notification.Builder(context, channelId)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(content.title)
            .setContentText(content.text)
            .setStyle(Notification.BigTextStyle().bigText(content.text))
            .setContentIntent(openBillIntent(context, occurrence))
            .setAutoCancel(true)
            .setCategory(Notification.CATEGORY_REMINDER)
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .setLocalOnly(true)
            .setPublicVersion(publicVersion)
            .setColorized(occurrence.priority == PaymentReminderPriority.URGENT)
        rule.snoozeMinutes.firstOrNull()?.let { minutes ->
            builder.addAction(
                Notification.Action.Builder(
                    null,
                    snoozeLabel(minutes),
                    snoozeIntent(context, rule, occurrence, minutes),
                ).build(),
            )
        }
        builder.addAction(
            Notification.Action.Builder(null, "View reminders", openBillIntent(context, occurrence)).build(),
        )
        manager.notify(notificationTag, REMINDER_NOTIFICATION_ID, builder.build())
        replaceRuleAlertTag(context, notificationTag, rule.id)
        return true
    }

    /**
     * Recovers the idempotency gap if Android accepted a notification immediately before this
     * process died and the delivery row was not committed yet. Reusing the active tagged card
     * avoids a second vibration on timeout recovery.
     */
    internal fun recoverActiveAlert(context: Context, tag: String, ruleId: Long): Boolean {
        val active = context.getSystemService(NotificationManager::class.java)
            ?.activeNotifications
            ?.any { it.id == REMINDER_NOTIFICATION_ID && it.tag == tag }
            ?: false
        if (active) replaceRuleAlertTag(context, tag, ruleId)
        return active
    }

    fun postSummary(context: Context, occurrences: List<PaymentReminderOccurrence>): Boolean {
        if (occurrences.isEmpty() || !canPost(context, PaymentReminderNotificationStyle.SILENT)) return false
        val manager = context.getSystemService(NotificationManager::class.java) ?: return false
        val preferences = (context.applicationContext as? PaisaLensApplication)?.preferences
            ?: UserPreferences(context.applicationContext)
        val sensitiveDetailsVisible = paymentReminderSensitiveDetailsVisible(
            appLockEnabled = preferences.appLockEnabled,
            privacyDefaultEnabled = preferences.privacyModeConfiguration.value.defaultEnabled,
            sessionPrivacyOverride = preferences.privacyModeSessionOverride.value,
            runtimePrivacyActive = PrivacyModeRuntime.active,
        )
        val content = buildPaymentReminderSummaryContent(occurrences, sensitiveDetailsVisible)
        val style = Notification.InboxStyle()
            .setBigContentTitle(content.title)
            .also { inbox -> content.lines.forEach(inbox::addLine) }
        val publicVersion = Notification.Builder(context, CHANNEL_SILENT)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("PaisaLens")
            .setContentText("Your private bill summary is ready")
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setLocalOnly(true)
            .build()
        val notification = Notification.Builder(context, CHANNEL_SILENT)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(content.title)
            .setContentText(content.text)
            .setStyle(style)
            .setContentIntent(summaryOpenIntent(context))
            .setAutoCancel(true)
            .setCategory(Notification.CATEGORY_REMINDER)
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .setLocalOnly(true)
            .setPublicVersion(publicVersion)
            .build()
        manager.notify(SUMMARY_NOTIFICATION_TAG, SUMMARY_NOTIFICATION_ID, notification)
        return true
    }

    fun cancelForRule(context: Context, ruleId: Long) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        notificationTagStore(context).getStringSet(ruleTagKey(ruleId), emptySet()).orEmpty().forEach { tag ->
            manager.cancel(tag, REMINDER_NOTIFICATION_ID)
        }
        notificationTagStore(context).edit().remove(ruleTagKey(ruleId)).apply()
    }

    /**
     * Removes notification cards that no longer match the persisted rule generation or current
     * authoritative bill cycle. This also cleans up rule IDs removed by account merge/restore.
     */
    internal fun reconcileActiveNotifications(
        context: Context,
        rules: List<PaymentReminderRule>,
        occurrences: List<PaymentReminderOccurrence>,
    ) {
        val activePrefixes = activePaymentReminderNotificationPrefixes(rules, occurrences)
        val manager = context.getSystemService(NotificationManager::class.java)
        val store = notificationTagStore(context)
        val editor = store.edit()
        store.all.forEach { (key, value) ->
            val ruleId = key.removePrefix(RULE_TAG_PREFIX).toLongOrNull()
                ?.takeIf { key.startsWith(RULE_TAG_PREFIX) }
                ?: return@forEach
            val tags = (value as? Set<*>)?.filterIsInstance<String>().orEmpty().toSet()
            val retained = retainCurrentPaymentReminderNotificationTags(tags, activePrefixes[ruleId])
            (tags - retained).forEach { tag -> manager?.cancel(tag, REMINDER_NOTIFICATION_ID) }
            if (retained.isEmpty()) editor.remove(key) else editor.putStringSet(key, retained)
        }
        editor.commit()
    }

    fun cancelAll(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        val store = notificationTagStore(context)
        store.all.values.filterIsInstance<Set<*>>().forEach { tags ->
            tags.filterIsInstance<String>().forEach { tag -> manager?.cancel(tag, REMINDER_NOTIFICATION_ID) }
        }
        manager?.cancel(SUMMARY_NOTIFICATION_TAG, SUMMARY_NOTIFICATION_ID)
        store.edit().clear().apply()
    }

    private fun channelFor(style: PaymentReminderNotificationStyle, vibrate: Boolean): String = when (style) {
        PaymentReminderNotificationStyle.STANDARD -> if (vibrate) CHANNEL_STANDARD_VIBRATE else CHANNEL_STANDARD
        PaymentReminderNotificationStyle.PROMINENT -> if (vibrate) CHANNEL_PROMINENT else CHANNEL_PROMINENT_NO_VIBRATE
        PaymentReminderNotificationStyle.SILENT -> CHANNEL_SILENT
    }

    private fun openBillIntent(context: Context, occurrence: PaymentReminderOccurrence): PendingIntent =
        PendingIntent.getActivity(
            context,
            OPEN_REQUEST_CODE,
            Intent(context, MainActivity::class.java)
                .setData(
                    Uri.Builder()
                        .scheme("paisalens")
                        .authority("bill-reminder")
                        .appendPath(occurrence.targetType.name.lowercase(Locale.ROOT))
                        .appendPath(occurrence.ruleId.toString())
                        .build(),
                )
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra(EXTRA_TARGET_TYPE, occurrence.targetType.name)
                .putExtra(EXTRA_TARGET_KEY, occurrence.targetKey)
                .putExtra(WIDGET_DESTINATION_EXTRA, WIDGET_DESTINATION_PAYMENT_REMINDERS),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    private fun summaryOpenIntent(context: Context): PendingIntent = PendingIntent.getActivity(
        context,
        SUMMARY_REQUEST_CODE,
        Intent(context, MainActivity::class.java)
            .setData(Uri.parse("paisalens://bill-reminder/summary"))
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra(WIDGET_DESTINATION_EXTRA, WIDGET_DESTINATION_PAYMENT_REMINDERS),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun snoozeIntent(
        context: Context,
        rule: PaymentReminderRule,
        occurrence: PaymentReminderOccurrence,
        minutes: Int,
    ): PendingIntent = PendingIntent.getBroadcast(
        context,
        SNOOZE_REQUEST_CODE,
        Intent(context, PaymentReminderReceiver::class.java)
            .setAction(PaymentReminderScheduler.ACTION_SNOOZE)
            .setData(
                Uri.Builder()
                    .scheme("paisalens")
                    .authority("bill-reminder-snooze")
                    .appendPath(rule.id.toString())
                    .appendPath(rule.generation.toString())
                    .appendPath(occurrence.dueDateEpochDay.toString())
                    .appendPath(minutes.toString())
                    .build(),
            )
            .putExtra(PaymentReminderScheduler.EXTRA_RULE_ID, rule.id)
            .putExtra(PaymentReminderScheduler.EXTRA_RULE_GENERATION, rule.generation)
            .putExtra(PaymentReminderScheduler.EXTRA_OCCURRENCE_EPOCH_DAY, occurrence.dueDateEpochDay)
            .putExtra(PaymentReminderScheduler.EXTRA_SNOOZE_MINUTES, minutes),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun snoozeLabel(minutes: Int): String = when {
        minutes % (24 * 60) == 0 -> "Snooze ${minutes / (24 * 60)}d"
        minutes % 60 == 0 -> "Snooze ${minutes / 60}h"
        else -> "Snooze ${minutes}m"
    }

    private fun notificationTagStore(context: Context) = context.applicationContext.getSharedPreferences(
        NOTIFICATION_TAG_PREFERENCES,
        Context.MODE_PRIVATE,
    )

    private fun ruleTagKey(ruleId: Long): String = "$RULE_TAG_PREFIX$ruleId"

    private fun replaceRuleAlertTag(context: Context, tag: String, ruleId: Long) {
        val store = notificationTagStore(context)
        val key = ruleTagKey(ruleId)
        val previousTags = store.getStringSet(key, emptySet()).orEmpty().toSet()
        // Android owns the new card before an older card is removed. Persist both briefly so a
        // crash can recover either state, then collapse the visible surface to one card per rule.
        store.edit().putStringSet(key, previousTags + tag).commit()
        val manager = context.getSystemService(NotificationManager::class.java)
        (previousTags - tag).forEach { previous -> manager?.cancel(previous, REMINDER_NOTIFICATION_ID) }
        store.edit().putStringSet(key, setOf(tag)).commit()
    }

    private const val REMINDER_NOTIFICATION_ID = 1903
    private const val SUMMARY_NOTIFICATION_ID = 1904
    private const val SUMMARY_NOTIFICATION_TAG = "payment-reminder-summary"
    private const val OPEN_REQUEST_CODE = 19_030
    private const val SNOOZE_REQUEST_CODE = 19_031
    private const val SUMMARY_REQUEST_CODE = 19_040
    private const val NOTIFICATION_TAG_PREFERENCES = "paisalens.payment_reminder_notifications"
    private const val RULE_TAG_PREFIX = "rule:"
}

internal fun activePaymentReminderNotificationPrefixes(
    rules: List<PaymentReminderRule>,
    occurrences: List<PaymentReminderOccurrence>,
): Map<Long, String> {
    val rulesById = rules.asSequence().filter(PaymentReminderRule::enabled).associateBy(PaymentReminderRule::id)
    return occurrences.asSequence()
        .filter { it.status != PaymentReminderStatus.SKIPPED }
        .mapNotNull { occurrence ->
            val rule = rulesById[occurrence.ruleId] ?: return@mapNotNull null
            rule.id to "payment-reminder-notification:${rule.id}:g${rule.generation}:${occurrence.dueDateEpochDay}:"
        }
        .toMap()
}

internal fun retainCurrentPaymentReminderNotificationTags(
    tags: Set<String>,
    activePrefix: String?,
): Set<String> = activePrefix?.let { prefix -> tags.filterTo(linkedSetOf()) { it.startsWith(prefix) } }.orEmpty()

internal fun paymentReminderNotificationTag(alert: PaymentReminderAlert, generation: Long): String =
    "payment-reminder-notification:${alert.occurrence.ruleId}:g$generation:" +
        "${alert.occurrence.dueDateEpochDay}:${alert.stableKey}"

internal data class PaymentReminderNotificationContent(
    val title: String,
    val text: String,
    val lines: List<String> = emptyList(),
)

internal fun paymentReminderSensitiveDetailsVisible(
    appLockEnabled: Boolean,
    privacyDefaultEnabled: Boolean,
    sessionPrivacyOverride: Boolean?,
    runtimePrivacyActive: Boolean,
): Boolean = !appLockEnabled &&
    !privacyDefaultEnabled &&
    sessionPrivacyOverride != true &&
    !runtimePrivacyActive

internal fun buildPaymentReminderNotificationContent(
    alert: PaymentReminderAlert,
    sensitiveDetailsVisible: Boolean,
    currencyCode: String = "INR",
): PaymentReminderNotificationContent {
    if (!sensitiveDetailsVisible) {
        return PaymentReminderNotificationContent(
            title = "PaisaLens bill reminder",
            text = "A bill needs your attention",
        )
    }
    val occurrence = alert.occurrence
    val title = when {
        alert.isSnooze -> "Snoozed bill reminder"
        alert.isOverdueRepeat || occurrence.status.name == "OVERDUE" -> "Bill payment overdue"
        alert.advanceDays == 0 -> "Bill payment due today"
        else -> "Upcoming bill payment"
    }
    val text = buildString {
        append(occurrence.title)
        append(" · due ")
        append(LocalDate.ofEpochDay(occurrence.dueDateEpochDay))
        if (occurrence.showAmount) {
            append(" · ")
            append(formatReminderMoney(occurrence.amountMinor, currencyCode))
        }
    }
    return PaymentReminderNotificationContent(title, text)
}

internal fun buildPaymentReminderSummaryContent(
    occurrences: List<PaymentReminderOccurrence>,
    sensitiveDetailsVisible: Boolean,
): PaymentReminderNotificationContent {
    if (!sensitiveDetailsVisible) {
        return PaymentReminderNotificationContent(
            title = "PaisaLens bill summary",
            text = "Your private bill summary is ready",
        )
    }
    return PaymentReminderNotificationContent(
        title = "Bill payment summary",
        text = "${occurrences.size} bill${if (occurrences.size == 1) "" else "s"} need attention",
        lines = occurrences.take(5).map { occurrence ->
            "${occurrence.title} · ${LocalDate.ofEpochDay(occurrence.dueDateEpochDay)}"
        },
    )
}

private fun formatReminderMoney(amountMinor: Long, currencyCode: String): String = runCatching {
    NumberFormat.getCurrencyInstance(Locale.Builder().setLanguage("en").setRegion("IN").build()).apply {
        currency = Currency.getInstance(currencyCode)
    }.format(amountMinor / 100.0)
}.getOrElse {
    NumberFormat.getCurrencyInstance(Locale.Builder().setLanguage("en").setRegion("IN").build()).apply {
        currency = Currency.getInstance("INR")
    }.format(amountMinor / 100.0)
}
