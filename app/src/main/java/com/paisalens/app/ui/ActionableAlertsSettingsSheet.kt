package com.paisalens.app.ui

import android.content.Context
import android.text.format.DateFormat
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.material.icons.automirrored.rounded.FactCheck
import androidx.compose.material.icons.automirrored.rounded.ReceiptLong
import androidx.compose.material.icons.automirrored.rounded.TrendingDown
import androidx.compose.material.icons.rounded.AccountBalanceWallet
import androidx.compose.material.icons.rounded.CreditCard
import androidx.compose.material.icons.rounded.Groups
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.NotificationsActive
import androidx.compose.material.icons.rounded.PieChart
import androidx.compose.material.icons.rounded.Schedule
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
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.paisalens.app.data.model.ActionableAlertCategory
import com.paisalens.app.data.model.ActionableAlertsConfiguration
import com.paisalens.app.ui.components.formatMoney
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ActionableAlertsSettingsSheet(
    configuration: ActionableAlertsConfiguration,
    hasNotificationPermission: Boolean,
    onConfigurationChange: (ActionableAlertsConfiguration) -> Unit,
    onRequestNotificationPermission: () -> Unit,
    onOpenSystemNotificationSettings: () -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val safe = configuration.normalized()

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxWidth().navigationBarsPadding(),
            contentPadding = PaddingValues(start = 20.dp, top = 4.dp, end = 20.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item {
                Text("Actionable alerts", style = MaterialTheme.typography.headlineSmall)
                Text(
                    "Private reminders for items that can affect your next few days.",
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
                            Text("Calculated only on this device", style = MaterialTheme.typography.titleMedium)
                            Text(
                                "Amounts and detailed titles stay out of the public lock-screen version by default.",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }
            }
            item {
                AlertToggleRow(
                    title = "Enable actionable alerts",
                    detail = if (hasNotificationPermission) {
                        "Checked once daily near ${formatAlertHour(context, safe.evaluationHour)}."
                    } else {
                        "Android notification access is required."
                    },
                    checked = safe.enabled,
                    icon = Icons.Rounded.NotificationsActive,
                    onCheckedChange = { enabled ->
                        when {
                            !enabled -> onConfigurationChange(safe.copy(enabled = false))
                            hasNotificationPermission -> onConfigurationChange(
                                safe.copy(
                                    enabled = true,
                                    enabledCategories = safe.enabledCategories.ifEmpty {
                                        ActionableAlertCategory.entries.toSet()
                                    },
                                ),
                            )
                            else -> onRequestNotificationPermission()
                        }
                    },
                )
                if (!hasNotificationPermission) {
                    TextButton(onClick = onOpenSystemNotificationSettings) {
                        Text("Open Android notification settings")
                    }
                }
            }
            item {
                SectionLabel("REMIND ME ABOUT")
                Column(Modifier.padding(top = 8.dp)) {
                    ActionableAlertCategory.entries.forEachIndexed { index, category ->
                        val checked = category in safe.enabledCategories
                        AlertToggleRow(
                            title = category.label,
                            detail = category.description,
                            checked = checked,
                            icon = category.icon(),
                            onCheckedChange = { enabled ->
                                onConfigurationChange(
                                    safe.copy(
                                        enabledCategories = if (enabled) {
                                            safe.enabledCategories + category
                                        } else {
                                            safe.enabledCategories - category
                                        },
                                    ),
                                )
                            },
                        )
                        if (index != ActionableAlertCategory.entries.lastIndex) {
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        }
                    }
                }
            }
            item {
                SectionLabel("DAILY CHECK TIME")
                AlertHourPicker(
                    hour = safe.evaluationHour,
                    onHourChange = { onConfigurationChange(safe.copy(evaluationHour = it)) },
                )
                Text(
                    "Android may run this inexact check near the selected hour to conserve battery.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            item {
                ChoiceChips(
                    title = "DUE-DATE WINDOW",
                    choices = listOf(1 to "1 day", 3 to "3 days", 7 to "7 days"),
                    selected = safe.dueWindowDays,
                    contentDescriptionSuffix = "due-date alert window",
                    onSelected = { onConfigurationChange(safe.copy(dueWindowDays = it)) },
                )
            }
            item {
                ChoiceChips(
                    title = "BUDGET WARNING",
                    choices = listOf(8_000 to "80%", 9_000 to "90%", 10_000 to "100%"),
                    selected = safe.budgetThresholdBasisPoints,
                    contentDescriptionSuffix = "budget warning",
                    onSelected = { onConfigurationChange(safe.copy(budgetThresholdBasisPoints = it)) },
                )
            }
            item {
                ChoiceChips(
                    title = "CREDIT UTILISATION WARNING",
                    choices = listOf(5_000 to "50%", 7_500 to "75%", 9_000 to "90%"),
                    selected = safe.utilizationThresholdBasisPoints,
                    contentDescriptionSuffix = "credit utilisation warning",
                    onSelected = { onConfigurationChange(safe.copy(utilizationThresholdBasisPoints = it)) },
                )
            }
            item {
                ChoiceChips(
                    title = "FORECAST SAFETY FLOOR",
                    choices = listOf(
                        0L to formatMoney(0L),
                        500_000L to formatMoney(500_000L),
                        1_000_000L to formatMoney(1_000_000L),
                        2_500_000L to formatMoney(2_500_000L),
                    ),
                    selected = safe.lowBalanceThresholdMinor,
                    contentDescriptionSuffix = "forecast safety floor",
                    onSelected = { onConfigurationChange(safe.copy(lowBalanceThresholdMinor = it)) },
                )
            }
            item {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                AlertToggleRow(
                    title = "Show amounts after unlock",
                    detail = "Include amounts only in Android's private notification view.",
                    checked = safe.showAmounts,
                    icon = Icons.Rounded.Lock,
                    onCheckedChange = { onConfigurationChange(safe.copy(showAmounts = it)) },
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                AlertToggleRow(
                    title = "Always use generic lock-screen text",
                    detail = if (safe.genericLockScreenText) {
                        "The public version reveals only that a money reminder is ready."
                    } else {
                        "The public version may name the reminder category, but never an amount or merchant."
                    },
                    checked = safe.genericLockScreenText,
                    icon = Icons.Rounded.Lock,
                    onCheckedChange = {
                        onConfigurationChange(safe.copy(genericLockScreenText = it))
                    },
                )
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

@Composable
private fun AlertToggleRow(
    title: String,
    detail: String,
    checked: Boolean,
    icon: ImageVector,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .toggleable(checked, role = Role.Switch, onValueChange = onCheckedChange)
            .semantics {
                contentDescription = title
                stateDescription = if (checked) "On" else "Off"
            }
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(24.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(
                detail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = checked, onCheckedChange = null)
    }
}

@Composable
private fun AlertHourPicker(hour: Int, onHourChange: (Int) -> Unit) {
    val context = LocalContext.current
    Surface(
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(
                onClick = { onHourChange((hour + 23) % 24) },
                modifier = Modifier.size(48.dp),
            ) {
                Text("−", style = MaterialTheme.typography.headlineSmall)
            }
            Column(
                modifier = Modifier.weight(1f).semantics(mergeDescendants = true) {
                    contentDescription = "Daily alert check near ${formatAlertHour(context, hour)}"
                },
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Icon(Icons.Rounded.Schedule, contentDescription = null)
                Text(formatAlertHour(context, hour), style = MaterialTheme.typography.titleLarge)
            }
            IconButton(
                onClick = { onHourChange((hour + 1) % 24) },
                modifier = Modifier.size(48.dp),
            ) {
                Text("+", style = MaterialTheme.typography.headlineSmall)
            }
        }
    }
}

@Composable
private fun <T> ChoiceChips(
    title: String,
    choices: List<Pair<T, String>>,
    selected: T,
    contentDescriptionSuffix: String,
    onSelected: (T) -> Unit,
) {
    SectionLabel(title)
    LazyRow(
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(choices, key = { it.first.toString() }) { (value, label) ->
            val isSelected = value == selected
            FilterChip(
                selected = isSelected,
                onClick = { onSelected(value) },
                label = { Text(label) },
                modifier = Modifier.heightIn(min = 48.dp).semantics {
                    contentDescription = "$label $contentDescriptionSuffix"
                    stateDescription = if (isSelected) "Selected" else "Not selected"
                },
            )
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(text, style = MaterialTheme.typography.labelLarge)
}

private fun ActionableAlertCategory.icon(): ImageVector = when (this) {
    ActionableAlertCategory.CARD_BILL_DUE -> Icons.Rounded.CreditCard
    ActionableAlertCategory.BILL_DUE -> Icons.AutoMirrored.Rounded.ReceiptLong
    ActionableAlertCategory.BUDGET_THRESHOLD -> Icons.Rounded.PieChart
    ActionableAlertCategory.CREDIT_UTILIZATION -> Icons.Rounded.AccountBalanceWallet
    ActionableAlertCategory.LOW_CASH_FLOW -> Icons.AutoMirrored.Rounded.TrendingDown
    ActionableAlertCategory.OVERDUE_REIMBURSEMENT -> Icons.Rounded.Groups
    ActionableAlertCategory.NEEDS_ATTENTION -> Icons.AutoMirrored.Rounded.FactCheck
}

private fun formatAlertHour(context: Context, hour: Int): String {
    val locale = context.resources.configuration.locales[0] ?: Locale.getDefault()
    val skeleton = if (DateFormat.is24HourFormat(context)) "Hm" else "hm"
    val calendar = Calendar.getInstance(locale).apply {
        set(Calendar.HOUR_OF_DAY, hour.coerceIn(0, 23))
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }
    return SimpleDateFormat(DateFormat.getBestDateTimePattern(locale, skeleton), locale)
        .format(calendar.time)
}
