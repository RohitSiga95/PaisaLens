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

object PrivateDigestNotifier {
    const val CHANNEL_ID = "private_money_digest"

    fun canPost(context: Context): Boolean =
        (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) &&
            (context.getSystemService(NotificationManager::class.java)?.areNotificationsEnabled() != false) &&
            (Build.VERSION.SDK_INT < Build.VERSION_CODES.O ||
                context.getSystemService(NotificationManager::class.java)
                    ?.getNotificationChannel(CHANNEL_ID)
                    ?.importance != NotificationManager.IMPORTANCE_NONE)

    fun settingsIntent(context: Context): Intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
        putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
    }

    fun ensureChannel(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Private money digest",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "Private daily or weekly summaries calculated on this device"
            lockscreenVisibility = Notification.VISIBILITY_PRIVATE
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    fun post(context: Context, content: PrivateDigestText) {
        if (!canPost(context)) return
        ensureChannel(context)
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        val openApp = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val publicVersion = Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(content.publicTitle)
            .setContentText(content.publicText)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setLocalOnly(true)
            .build()
        val notification = Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(content.title)
            .setContentText(content.text)
            .setStyle(Notification.BigTextStyle().bigText(content.text))
            .setContentIntent(openApp)
            .setAutoCancel(true)
            .setCategory(Notification.CATEGORY_REMINDER)
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .setLocalOnly(true)
            .setPublicVersion(publicVersion)
            .build()
        manager.notify(NOTIFICATION_ID, notification)
    }

    fun cancel(context: Context) {
        context.getSystemService(NotificationManager::class.java)?.cancel(NOTIFICATION_ID)
    }

    private const val NOTIFICATION_ID = 1801
}
