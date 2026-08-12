package com.paisalens.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Rule
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.AccountBalanceWallet
import androidx.compose.material.icons.rounded.Backup
import androidx.compose.material.icons.rounded.Category
import androidx.compose.material.icons.rounded.CreditScore
import androidx.compose.material.icons.rounded.DeleteForever
import androidx.compose.material.icons.rounded.DashboardCustomize
import androidx.compose.material.icons.rounded.FileDownload
import androidx.compose.material.icons.rounded.FileUpload
import androidx.compose.material.icons.rounded.Flight
import androidx.compose.material.icons.rounded.HomeWork
import androidx.compose.material.icons.rounded.HealthAndSafety
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.MarkEmailRead
import androidx.compose.material.icons.rounded.Merge
import androidx.compose.material.icons.rounded.NotificationsActive
import androidx.compose.material.icons.rounded.Payments
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material.icons.rounded.Restore
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material.icons.rounded.VerifiedUser
import androidx.compose.material.icons.rounded.WifiOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.paisalens.app.BuildConfig
import com.paisalens.app.data.model.AppThemeConfiguration
import com.paisalens.app.data.model.AppThemeStyle
import com.paisalens.app.data.model.HomeLayoutConfiguration
import com.paisalens.app.data.model.NotificationDigestConfiguration
import com.paisalens.app.ui.components.PaisaCard
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun SettingsScreen(
    themeConfiguration: AppThemeConfiguration,
    homeLayout: HomeLayoutConfiguration,
    notificationDigest: NotificationDigestConfiguration,
    hasSmsPermission: Boolean,
    isScanning: Boolean,
    lastScanAt: Long,
    onCustomizeTheme: () -> Unit,
    onCustomizeHome: () -> Unit,
    onPrivateDigest: () -> Unit,
    onRequestSms: () -> Unit,
    onScan: () -> Unit,
    transactionCount: Int,
    accountCount: Int,
    customCategoryCount: Int,
    recurringCount: Int,
    reviewCount: Int,
    loanCount: Int,
    merchantAliasCount: Int,
    smartRuleCount: Int,
    rateCount: Int,
    appLockEnabled: Boolean,
    widgetAmountsVisible: Boolean,
    travelModeEnabled: Boolean,
    baseCurrency: String,
    onExportData: () -> Unit,
    onManageAccounts: () -> Unit,
    onManageCategories: () -> Unit,
    onMerchantCleanup: () -> Unit,
    onSmartCategoryRules: () -> Unit,
    onManageLoans: () -> Unit,
    onTravelMode: () -> Unit,
    onImportStatement: () -> Unit,
    onAuditCardStatement: () -> Unit,
    onDataHealth: () -> Unit,
    onAppLockChange: (Boolean) -> Unit,
    onWidgetAmountsChange: (Boolean) -> Unit,
    onCreateBackup: () -> Unit,
    onRestoreBackup: () -> Unit,
    onVerifyBackup: () -> Unit,
    onReviewTransactions: () -> Unit,
    onClearAll: () -> Unit,
) {
    var showClearDialog by remember { mutableStateOf(false) }
    val locale = LocalConfiguration.current.locales[0]

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 18.dp, top = 12.dp, end = 18.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            Text(
                text = "Settings",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = "Privacy and preferences",
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
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(18.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        modifier = Modifier
                            .size(48.dp)
                            .background(MaterialTheme.colorScheme.secondary, CircleShape),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            Icons.Rounded.Lock,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSecondary,
                        )
                    }
                    Spacer(Modifier.width(13.dp))
                    Column {
                        Text("Private by design", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "No account, ads, telemetry, or financial-data uploads.",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            }
        }
        item {
            SettingsSection("SMS analysis") {
                SettingsActionRow(
                    icon = Icons.Rounded.MarkEmailRead,
                    title = if (hasSmsPermission) "SMS access enabled" else "Enable SMS access",
                    subtitle = if (hasSmsPermission) {
                        "Incoming transaction alerts are analyzed automatically."
                    } else {
                        "Required only to detect bank, card and UPI alerts."
                    },
                    onClick = if (hasSmsPermission) onScan else onRequestSms,
                    trailing = {
                        if (hasSmsPermission) {
                            Icon(
                                Icons.Rounded.Sync,
                                contentDescription = if (isScanning) "Scanning" else "Scan now",
                                tint = MaterialTheme.colorScheme.primary,
                            )
                        } else {
                            Icon(Icons.Rounded.ChevronRight, contentDescription = null)
                        }
                    },
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                SettingsInfoRow(
                    icon = Icons.Rounded.PhoneAndroid,
                    title = "Last local scan",
                    subtitle = if (lastScanAt > 0) formatScanTime(lastScanAt) else "Not scanned yet",
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                SettingsActionRow(
                    icon = Icons.Rounded.Merge,
                    title = "Merchant cleanup",
                    subtitle = if (merchantAliasCount == 0) "Rename and merge inconsistent merchant names." else "$merchantAliasCount cleanup rule${if (merchantAliasCount == 1) "" else "s"} saved",
                    onClick = onMerchantCleanup,
                    trailing = { Icon(Icons.Rounded.ChevronRight, contentDescription = null) },
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                SettingsActionRow(
                    icon = Icons.Rounded.Payments,
                    title = "EMI & loan tracker",
                    subtitle = if (loanCount == 0) "Track principal, EMI progress, and due dates." else "$loanCount loan${if (loanCount == 1) "" else "s"} tracked",
                    onClick = onManageLoans,
                    trailing = { Icon(Icons.Rounded.ChevronRight, contentDescription = null) },
                )
            }
        }
        item {
            SettingsSection("Money organization") {
                SettingsActionRow(
                    icon = Icons.Rounded.AccountBalanceWallet,
                    title = "Accounts & cards",
                    subtitle = if (accountCount == 0) {
                        "Add an account or scan SMS to detect one automatically."
                    } else {
                        "$accountCount account${if (accountCount == 1) "" else "s"} · rename, assign, and organize"
                    },
                    onClick = onManageAccounts,
                    trailing = { Icon(Icons.Rounded.ChevronRight, contentDescription = null) },
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                SettingsActionRow(
                    icon = Icons.Rounded.Category,
                    title = "Custom categories",
                    subtitle = if (customCategoryCount == 0) "Create categories that match your life." else "$customCategoryCount custom categories",
                    onClick = onManageCategories,
                    trailing = { Icon(Icons.Rounded.ChevronRight, contentDescription = null) },
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                SettingsActionRow(
                    icon = Icons.AutoMirrored.Rounded.Rule,
                    title = "Smart category rules",
                    subtitle = if (smartRuleCount == 0) {
                        "Categorize future expenses by merchant, amount, or account."
                    } else {
                        "$smartRuleCount rule${if (smartRuleCount == 1) "" else "s"} running locally"
                    },
                    onClick = onSmartCategoryRules,
                    trailing = { Icon(Icons.Rounded.ChevronRight, contentDescription = null) },
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                SettingsActionRow(
                    icon = Icons.Rounded.MarkEmailRead,
                    title = "Review inbox",
                    subtitle = if (reviewCount == 0) "No uncertain transactions waiting." else "$reviewCount transaction${if (reviewCount == 1) "" else "s"} need confirmation",
                    onClick = onReviewTransactions,
                    trailing = { Icon(Icons.Rounded.ChevronRight, contentDescription = null) },
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                SettingsInfoRow(
                    icon = Icons.Rounded.Sync,
                    title = "Recurring payments",
                    subtitle = if (recurringCount == 0) "Detected after two consistent weekly or monthly payments." else "$recurringCount recurring payment${if (recurringCount == 1) "" else "s"} detected",
                )
            }
        }
        item {
            SettingsSection("Privacy safeguards") {
                SettingsActionRow(
                    icon = Icons.Rounded.Lock,
                    title = "App lock",
                    subtitle = "Require fingerprint, face, PIN, pattern, or password when opening PaisaLens.",
                    onClick = { onAppLockChange(!appLockEnabled) },
                    trailing = { Switch(checked = appLockEnabled, onCheckedChange = onAppLockChange) },
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                SettingsActionRow(
                    icon = Icons.Rounded.HomeWork,
                    title = "Show amounts on widget",
                    subtitle = if (appLockEnabled) "Disabled while app lock is enabled." else "Amounts are hidden by default for home-screen privacy.",
                    onClick = { if (!appLockEnabled) onWidgetAmountsChange(!widgetAmountsVisible) },
                    trailing = { Switch(checked = widgetAmountsVisible && !appLockEnabled, onCheckedChange = onWidgetAmountsChange, enabled = !appLockEnabled) },
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                SettingsActionRow(
                    icon = Icons.Rounded.NotificationsActive,
                    title = "Private notification digest",
                    subtitle = if (notificationDigest.enabled) {
                        "${notificationDigest.frequency.label} at ${String.format(locale, "%02d:00", notificationDigest.hour)} · " +
                            if (notificationDigest.showAmounts) "amounts included privately" else "amounts hidden"
                    } else {
                        "Optional on-device summary with lock-screen-safe content."
                    },
                    onClick = onPrivateDigest,
                    trailing = { Icon(Icons.Rounded.ChevronRight, contentDescription = null) },
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                SettingsInfoRow(
                    icon = Icons.Rounded.WifiOff,
                    title = "Network isolation",
                    subtitle = "Internet is used only when you explicitly refresh a travel exchange rate.",
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                SettingsInfoRow(
                    icon = Icons.Rounded.Key,
                    title = "Device-key encryption",
                    subtitle = "Stored SMS alert text is encrypted with Android Keystore.",
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                SettingsInfoRow(
                    icon = Icons.Rounded.Lock,
                    title = "Cloud backups disabled",
                    subtitle = "Only password-protected backups you create manually can leave this device.",
                )
            }
        }
        item {
            SettingsSection("Travel") {
                SettingsActionRow(
                    icon = Icons.Rounded.Flight,
                    title = "Multi-currency travel mode",
                    subtitle = if (travelModeEnabled) "$baseCurrency home currency · $rateCount cached reference rate${if (rateCount == 1) "" else "s"}" else "Record foreign purchases using an explicitly refreshed reference rate.",
                    onClick = onTravelMode,
                    trailing = { Icon(Icons.Rounded.ChevronRight, contentDescription = null) },
                )
            }
        }
        item {
            SettingsSection("Trust & accuracy") {
                SettingsActionRow(
                    icon = Icons.Rounded.HealthAndSafety,
                    title = "Data Health Centre",
                    subtitle = "Review data quality, backup readiness, and reversible change history.",
                    onClick = onDataHealth,
                    trailing = { Icon(Icons.Rounded.ChevronRight, contentDescription = null) },
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                SettingsActionRow(
                    icon = Icons.Rounded.CreditScore,
                    title = "Audit credit-card statement",
                    subtitle = "Compare reviewed CSV or XLSX rows with card SMS locally.",
                    onClick = onAuditCardStatement,
                    trailing = { Icon(Icons.Rounded.ChevronRight, contentDescription = null) },
                )
            }
        }
        item {
            SettingsSection("Appearance") {
                SettingsActionRow(
                    icon = Icons.Rounded.DashboardCustomize,
                    title = "Customise Home",
                    subtitle = "${homeLayout.normalized().orderedVisibleModules.size} of ${com.paisalens.app.data.model.HomeModule.entries.size} modules visible · reorder anytime",
                    onClick = onCustomizeHome,
                    trailing = { Icon(Icons.Rounded.ChevronRight, contentDescription = null) },
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                SettingsActionRow(
                    icon = Icons.Rounded.Palette,
                    title = "Theme Studio",
                    subtitle = if (themeConfiguration.style == AppThemeStyle.AMOLED) {
                        "AMOLED black · ${themeConfiguration.palette.label}"
                    } else {
                        "${themeConfiguration.style.label} · ${themeConfiguration.palette.label} · ${themeConfiguration.mode.label}"
                    },
                    onClick = onCustomizeTheme,
                    trailing = { Icon(Icons.Rounded.ChevronRight, contentDescription = null) },
                )
            }
        }
        item {
            SettingsSection("Data controls") {
                SettingsActionRow(
                    icon = Icons.Rounded.FileUpload,
                    title = "Import bank statement",
                    subtitle = "Preview and import CSV or XLSX transactions locally.",
                    onClick = onImportStatement,
                    trailing = { Icon(Icons.Rounded.ChevronRight, contentDescription = null) },
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                SettingsActionRow(
                    icon = Icons.Rounded.FileDownload,
                    title = "Export Excel report",
                    subtitle = if (transactionCount == 0) {
                        "Create an empty formatted workbook ready for future data."
                    } else {
                        "Export $transactionCount transactions with dashboard charts and analysis."
                    },
                    onClick = onExportData,
                    trailing = {
                        Icon(Icons.Rounded.ChevronRight, contentDescription = null)
                    },
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                SettingsActionRow(
                    icon = Icons.Rounded.Backup,
                    title = "Create encrypted backup",
                    subtitle = "Password-protected copy of transactions, accounts, categories and budgets.",
                    onClick = onCreateBackup,
                    trailing = { Icon(Icons.Rounded.ChevronRight, contentDescription = null) },
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                SettingsActionRow(
                    icon = Icons.Rounded.Restore,
                    title = "Restore encrypted backup",
                    subtitle = "Replace this phone's local data from a PaisaLens backup.",
                    onClick = onRestoreBackup,
                    trailing = { Icon(Icons.Rounded.ChevronRight, contentDescription = null) },
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                SettingsActionRow(
                    icon = Icons.Rounded.VerifiedUser,
                    title = "Verify encrypted backup",
                    subtitle = "Check a backup's password, encryption, and record counts without restoring it.",
                    onClick = onVerifyBackup,
                    trailing = { Icon(Icons.Rounded.ChevronRight, contentDescription = null) },
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                SettingsActionRow(
                    icon = Icons.Rounded.DeleteForever,
                    title = "Erase all app data",
                    subtitle = "Permanently deletes transactions, accounts, categories and budgets from this phone.",
                    onClick = { showClearDialog = true },
                    danger = true,
                    trailing = {
                        Icon(
                            Icons.Rounded.ChevronRight,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error,
                        )
                    },
                )
            }
        }
        item {
            Text(
                text = "PaisaLens ${BuildConfig.VERSION_NAME} · Made for private money clarity",
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    if (showClearDialog) {
        AlertDialog(
            onDismissRequest = { showClearDialog = false },
            icon = {
                Icon(
                    Icons.Rounded.DeleteForever,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                )
            },
            title = { Text("Erase local financial data?") },
            text = {
                Text(
                    "This permanently deletes transactions, accounts, categories, merchant rules, loans, cached rates, and budgets. Your original SMS messages and exported files are not changed.",
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        onClearAll()
                        showClearDialog = false
                    },
                ) {
                    Text("Erase everything")
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearDialog = false }) {
                    Text("Cancel")
                }
            },
        )
    }
}

@Composable
private fun SettingsSection(
    title: String,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column {
        Text(
            title,
            modifier = Modifier.padding(start = 4.dp, bottom = 8.dp),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        PaisaCard(modifier = Modifier.fillMaxWidth(), content = content)
    }
}

@Composable
private fun SettingsActionRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    trailing: @Composable () -> Unit,
    danger: Boolean = false,
) {
    Surface(
        onClick = onClick,
        color = androidx.compose.ui.graphics.Color.Transparent,
        shape = MaterialTheme.shapes.medium,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SettingsIcon(icon, danger)
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleMedium,
                    color = if (danger) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.width(10.dp))
            trailing()
        }
    }
}

@Composable
private fun SettingsInfoRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SettingsIcon(icon, false)
        Spacer(Modifier.width(12.dp))
        Column {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(2.dp))
            Text(
                subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun SettingsIcon(icon: ImageVector, danger: Boolean) {
    Box(
        modifier = Modifier
            .size(42.dp)
            .background(
                if (danger) MaterialTheme.colorScheme.errorContainer
                else MaterialTheme.colorScheme.primaryContainer,
                CircleShape,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = if (danger) MaterialTheme.colorScheme.onErrorContainer
            else MaterialTheme.colorScheme.onPrimaryContainer,
            modifier = Modifier.size(21.dp),
        )
    }
}

private fun formatScanTime(timestamp: Long): String =
    SimpleDateFormat("d MMM yyyy, h:mm a", Locale.getDefault()).format(Date(timestamp))
