package com.paisalens.app.ui

import android.content.Context
import android.text.format.DateFormat
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ChevronLeft
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.NotificationsActive
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.paisalens.app.data.model.NotificationDigestConfiguration
import com.paisalens.app.data.model.NotificationDigestFrequency
import java.time.DayOfWeek
import java.time.format.TextStyle
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PrivateDigestSettingsSheet(
    configuration: NotificationDigestConfiguration,
    hasNotificationPermission: Boolean,
    onConfigurationChange: (NotificationDigestConfiguration) -> Unit,
    onRequestNotificationPermission: () -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val locale = LocalConfiguration.current.locales[0]
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val safeConfiguration = configuration.normalized()
    val timeLabel = formatDigestHour(context, safeConfiguration.hour)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxWidth().navigationBarsPadding(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                start = 20.dp,
                top = 4.dp,
                end = 20.dp,
                bottom = 24.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item {
                Text(
                    text = "Private notification digest",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = "A quiet local summary of items that may need your attention.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            item {
                Surface(
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                    shape = MaterialTheme.shapes.large,
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Rounded.Lock, contentDescription = null)
                        Column(Modifier.weight(1f)) {
                            Text("Private on the lock screen", fontWeight = FontWeight.SemiBold)
                            Text(
                                "PaisaLens shows a generic public version. Amounts stay hidden unless you explicitly opt in.",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }
            }
            item {
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    shape = MaterialTheme.shapes.large,
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 64.dp)
                            .toggleable(
                                value = safeConfiguration.enabled,
                                role = Role.Switch,
                                onValueChange = { enabled ->
                                    when {
                                        !enabled -> onConfigurationChange(safeConfiguration.copy(enabled = false))
                                        hasNotificationPermission -> onConfigurationChange(safeConfiguration.copy(enabled = true))
                                        else -> onRequestNotificationPermission()
                                    }
                                },
                            )
                            .semantics {
                                contentDescription = "Private notification digest"
                                stateDescription = if (safeConfiguration.enabled) "On" else "Off"
                            }
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Rounded.NotificationsActive, contentDescription = null)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text("Enable digest", style = MaterialTheme.typography.titleMedium)
                            Text(
                                if (hasNotificationPermission) "Scheduled locally on this device." else "Android notification access is required.",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        Switch(
                            checked = safeConfiguration.enabled,
                            onCheckedChange = null,
                        )
                    }
                }
            }
            item {
                Text("FREQUENCY", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
                LazyRow(
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(NotificationDigestFrequency.entries, key = { it.name }) { frequency ->
                        val isSelected = safeConfiguration.frequency == frequency
                        FilterChip(
                            selected = isSelected,
                            onClick = { onConfigurationChange(safeConfiguration.copy(frequency = frequency)) },
                            label = { Text(frequency.label) },
                            modifier = Modifier
                                .heightIn(min = 48.dp)
                                .semantics {
                                    contentDescription = "${frequency.label} notification digest"
                                    stateDescription = if (isSelected) "Selected" else "Not selected"
                                },
                        )
                    }
                }
            }
            if (safeConfiguration.frequency == NotificationDigestFrequency.WEEKLY) {
                item {
                    Text("DELIVERY DAY", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
                    LazyRow(
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(DayOfWeek.entries) { day ->
                            val isSelected = safeConfiguration.weekday == day
                            val dayLabel = day.getDisplayName(TextStyle.FULL, locale)
                            FilterChip(
                                selected = isSelected,
                                onClick = { onConfigurationChange(safeConfiguration.copy(weekday = day)) },
                                label = { Text(day.getDisplayName(TextStyle.SHORT, locale)) },
                                modifier = Modifier
                                    .heightIn(min = 48.dp)
                                    .semantics {
                                        contentDescription = "Deliver on $dayLabel"
                                        stateDescription = if (isSelected) "Selected" else "Not selected"
                                    },
                            )
                        }
                    }
                }
            }
            item {
                Text("DELIVERY TIME", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
                Surface(
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    shape = MaterialTheme.shapes.large,
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        val previousHour = (safeConfiguration.hour + 23) % 24
                        val nextHour = (safeConfiguration.hour + 1) % 24
                        IconButton(
                            onClick = {
                                onConfigurationChange(safeConfiguration.copy(hour = previousHour))
                            },
                            modifier = Modifier.size(48.dp),
                        ) {
                            Icon(
                                Icons.Rounded.ChevronLeft,
                                contentDescription = "One hour earlier, ${formatDigestHour(context, previousHour)}",
                            )
                        }
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .semantics(mergeDescendants = true) {
                                    contentDescription = "Digest delivery time $timeLabel, on the hour"
                                },
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Text(timeLabel, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                            Text("On the hour", style = MaterialTheme.typography.bodySmall)
                        }
                        IconButton(
                            onClick = {
                                onConfigurationChange(safeConfiguration.copy(hour = nextHour))
                            },
                            modifier = Modifier.size(48.dp),
                        ) {
                            Icon(
                                Icons.Rounded.ChevronRight,
                                contentDescription = "One hour later, ${formatDigestHour(context, nextHour)}",
                            )
                        }
                    }
                }
                LazyRow(
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(listOf(8, 12, 18, 20), key = { it }) { hour ->
                        val isSelected = safeConfiguration.hour == hour
                        val label = formatDigestHour(context, hour)
                        FilterChip(
                            selected = isSelected,
                            onClick = { onConfigurationChange(safeConfiguration.copy(hour = hour)) },
                            label = { Text(label) },
                            modifier = Modifier
                                .heightIn(min = 48.dp)
                                .semantics {
                                    contentDescription = "Set delivery time to $label"
                                    stateDescription = if (isSelected) "Selected" else "Not selected"
                                },
                        )
                    }
                }
                Text(
                    "Android may deliver an inexact digest near this hour to protect battery life.",
                    modifier = Modifier.padding(top = 6.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            item {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 64.dp)
                        .padding(top = 4.dp)
                        .toggleable(
                            value = safeConfiguration.showAmounts,
                            role = Role.Switch,
                            onValueChange = {
                                onConfigurationChange(safeConfiguration.copy(showAmounts = it))
                            },
                        )
                        .semantics {
                            contentDescription = "Show amounts in private notification view"
                            stateDescription = if (safeConfiguration.showAmounts) "On" else "Off"
                        }
                        .padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("Show amounts", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "Include totals only in the private, unlocked notification view.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = safeConfiguration.showAmounts,
                        onCheckedChange = null,
                    )
                }
            }
            item {
                Button(
                    onClick = onDismiss,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                ) {
                    Text("Done")
                }
            }
        }
    }
}

private fun formatDigestHour(context: Context, hour: Int): String {
    val locale = context.resources.configuration.locales[0] ?: Locale.getDefault()
    val skeleton = if (DateFormat.is24HourFormat(context)) "Hm" else "hm"
    val pattern = DateFormat.getBestDateTimePattern(locale, skeleton)
    val calendar = Calendar.getInstance(locale).apply {
        set(Calendar.HOUR_OF_DAY, hour.coerceIn(0, 23))
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }
    return SimpleDateFormat(pattern, locale).format(calendar.time)
}
