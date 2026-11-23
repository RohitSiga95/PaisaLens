package com.paisalens.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.paisalens.app.data.model.PaymentReminderNotificationStyle
import com.paisalens.app.data.model.PaymentReminderSettings
import com.paisalens.app.notification.PaymentReminderDeliveryPreview
import com.paisalens.app.notification.PaymentReminderNotifier
import com.paisalens.app.notification.PaymentReminderScheduler
import com.paisalens.app.notification.ReminderChannelStatus
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
internal fun PaymentReminderDeliveryCheck(
    settings: PaymentReminderSettings,
    nowMillis: Long,
    onRequestNotificationPermission: () -> Unit,
) {
    val context = LocalContext.current
    val locale = LocalConfiguration.current.locales[0]
    var notificationSettingsRevision by remember { mutableIntStateOf(0) }
    var previewResult by remember { mutableStateOf<Result<PaymentReminderDeliveryPreview?>?>(null) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { notificationSettingsRevision++ }
    LaunchedEffect(settings, nowMillis, notificationSettingsRevision) {
        previewResult = withContext(Dispatchers.IO) {
            runCatching { PaymentReminderScheduler.previewNextDelivery(context, System.currentTimeMillis()) }
        }
    }

    val preview = previewResult?.getOrNull()
    val style = preview?.style ?: PaymentReminderNotificationStyle.STANDARD
    val vibrate = preview?.vibrate ?: true
    var testResult by remember(style, vibrate) { mutableStateOf<ReminderChannelStatus?>(null) }
    val status = remember(context, style, vibrate, notificationSettingsRevision, testResult) {
        PaymentReminderNotifier.channelStatus(context, style, vibrate)
    }
    val channel = when (style) {
        PaymentReminderNotificationStyle.SILENT -> "Silent"
        PaymentReminderNotificationStyle.STANDARD -> if (vibrate) "Standard with vibration" else "Standard"
        PaymentReminderNotificationStyle.PROMINENT -> if (vibrate) "Prominent with vibration" else "Prominent"
    }
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        shape = MaterialTheme.shapes.large,
    ) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Check delivery", style = MaterialTheme.typography.titleMedium)
            Text(
                when {
                    !settings.enabled -> "Scheduled reminders are paused"
                    previewResult == null -> "Checking the next planned alert…"
                    previewResult?.isFailure == true -> "Could not load the next alert. Reopen settings to try again."
                    preview != null -> {
                        val date = Instant.ofEpochMilli(preview.scheduledAtMillis).atZone(ZoneId.systemDefault())
                            .format(DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT).withLocale(locale))
                        if (preview.isSummary) "Next planned summary · $date" else "Next planned alert · $date"
                    }
                    else -> "No alert is currently planned"
                },
            )
            Text(
                when (status) {
                    ReminderChannelStatus.AVAILABLE -> "Test channel · $channel"
                    ReminderChannelStatus.APP_BLOCKED -> "PaisaLens notifications are off in Android"
                    ReminderChannelStatus.CHANNEL_BLOCKED -> "The $channel channel is blocked"
                    ReminderChannelStatus.UNAVAILABLE -> "The notification channel is unavailable"
                },
                style = MaterialTheme.typography.bodyMedium,
            )
            Button(
                onClick = { testResult = PaymentReminderNotifier.postTest(context, style, vibrate) },
                enabled = status == ReminderChannelStatus.AVAILABLE,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
            ) {
                Text("Send test reminder")
            }
            if (status == ReminderChannelStatus.APP_BLOCKED) {
                TextButton(onClick = onRequestNotificationPermission, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text("Allow notifications")
                }
            }
            TextButton(
                onClick = {
                    val appSettings = PaymentReminderNotifier.settingsIntent(context)
                    val intent = if (status == ReminderChannelStatus.APP_BLOCKED) appSettings
                        else PaymentReminderNotifier.channelSettingsIntent(context, style, vibrate)
                    try {
                        context.startActivity(intent)
                    } catch (_: android.content.ActivityNotFoundException) {
                        context.startActivity(appSettings)
                    }
                },
                modifier = Modifier.heightIn(min = 48.dp),
            ) {
                Text(if (status == ReminderChannelStatus.APP_BLOCKED) "Open Android settings" else "Open channel settings")
            }
            testResult?.let { result ->
                Text(
                    when (result) {
                        ReminderChannelStatus.AVAILABLE -> "Test reminder sent. Check your notifications."
                        ReminderChannelStatus.APP_BLOCKED, ReminderChannelStatus.CHANNEL_BLOCKED ->
                            "Test reminder blocked. Open settings to allow notifications."
                        ReminderChannelStatus.UNAVAILABLE -> "Could not send the test reminder. Try again."
                    },
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}
