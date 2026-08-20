package com.paisalens.app.notification

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import com.paisalens.app.MainActivity
import com.paisalens.app.R

object ActionableAlertNotifier {
    const val CHANNEL_ID = "private_actionable_alerts"
    const val CRITICAL_CHANNEL_ID = "private_actionable_alerts_urgent"
    const val EXTRA_DESTINATION = "com.paisalens.app.extra.ALERT_DESTINATION"

    fun canPost(context: Context, priority: ActionableAlertPriority? = null): Boolean {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return false
        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return false
        if (!manager.areNotificationsEnabled()) return false
        val channelIds = when (priority) {
            ActionableAlertPriority.CRITICAL -> listOf(CRITICAL_CHANNEL_ID)
            ActionableAlertPriority.HIGH,
            ActionableAlertPriority.NORMAL,
            -> listOf(CHANNEL_ID)
            null -> listOf(CHANNEL_ID, CRITICAL_CHANNEL_ID)
        }
        return channelIds.any { manager.getNotificationChannel(it)?.importance != NotificationManager.IMPORTANCE_NONE }
    }

    fun settingsIntent(context: Context): Intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
        putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
    }

    fun ensureChannels(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "Private money reminders",
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply {
                description = "Optional bills, budgets, credit, cash-flow, and reimbursement reminders"
                lockscreenVisibility = Notification.VISIBILITY_PRIVATE
                setShowBadge(true)
            },
        )
        manager.createNotificationChannel(
            NotificationChannel(
                CRITICAL_CHANNEL_ID,
                "Urgent private money reminders",
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = "Overdue bills and critical credit or forecast reminders"
                lockscreenVisibility = Notification.VISIBILITY_PRIVATE
                setShowBadge(true)
            },
        )
    }

    /** Returns true only after Android accepted the local notification. */
    fun post(context: Context, content: ActionableAlertNotificationContent): Boolean {
        ensureChannels(context)
        if (!canPost(context, content.priority)) return false
        val manager = context.getSystemService(NotificationManager::class.java) ?: return false
        val channelId = if (content.priority == ActionableAlertPriority.CRITICAL) {
            CRITICAL_CHANNEL_ID
        } else {
            CHANNEL_ID
        }
        val openApp = PendingIntent.getActivity(
            context,
            REQUEST_CODE_BASE + content.destination.ordinal,
            Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra(EXTRA_DESTINATION, content.destination.storageId),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val publicVersion = Notification.Builder(context, channelId)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(content.publicTitle)
            .setContentText(content.publicText)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setLocalOnly(true)
            .build()
        val privateStyle = Notification.InboxStyle()
            .setBigContentTitle(content.title)
            .also { style -> content.lines.take(5).forEach(style::addLine) }
        val notification = Notification.Builder(context, channelId)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(content.title)
            .setContentText(content.text)
            .setStyle(privateStyle)
            .setContentIntent(openApp)
            .setAutoCancel(true)
            .setCategory(Notification.CATEGORY_REMINDER)
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .setLocalOnly(true)
            .setPublicVersion(publicVersion)
            .build()
        // Delivery history already suppresses unwanted repeats. Repost as a fresh reminder so
        // a newly eligible item or priority escalation is not silently absorbed by yesterday's
        // still-visible notification (and can move onto the urgent channel when required).
        manager.cancel(NOTIFICATION_ID)
        manager.notify(NOTIFICATION_ID, notification)
        return true
    }

    fun requestedDestination(intent: Intent?): AlertDestination =
        AlertDestination.fromStorageId(intent?.getStringExtra(EXTRA_DESTINATION))

    fun cancel(context: Context) {
        context.getSystemService(NotificationManager::class.java)?.cancel(NOTIFICATION_ID)
    }

    private const val NOTIFICATION_ID = 1802
    private const val REQUEST_CODE_BASE = 18_200
}
