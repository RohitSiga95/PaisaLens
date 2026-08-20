package com.paisalens.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.ScreenshotMonitor
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.paisalens.app.data.model.PrivacyModeConfiguration

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PrivacyModeSettingsSheet(
    configuration: PrivacyModeConfiguration,
    active: Boolean,
    sessionOverride: Boolean?,
    onConfigurationChange: (PrivacyModeConfiguration) -> Unit,
    onSetSessionPrivacy: (Boolean) -> Unit,
    onClearSessionOverride: () -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
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
                Text("One-tap privacy", style = MaterialTheme.typography.headlineSmall)
                Text(
                    "Hide monetary values while keeping your dashboard useful in public spaces.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            item {
                Surface(
                    color = if (active) {
                        MaterialTheme.colorScheme.primaryContainer
                    } else {
                        MaterialTheme.colorScheme.surfaceVariant
                    },
                    contentColor = if (active) {
                        MaterialTheme.colorScheme.onPrimaryContainer
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    shape = MaterialTheme.shapes.large,
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Rounded.VisibilityOff, contentDescription = null)
                        Column(Modifier.weight(1f)) {
                            Text(
                                if (active) "Amounts are hidden" else "Amounts are visible",
                                style = MaterialTheme.typography.titleMedium,
                            )
                            Text(
                                if (sessionOverride == null) {
                                    "Using your saved default."
                                } else {
                                    "Temporarily changed for this app session."
                                },
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }
            }
            item {
                PrivacyToggleRow(
                    title = "Hide amounts now",
                    detail = "The eye button uses this session-only control and does not change your saved default.",
                    checked = active,
                    icon = Icons.Rounded.VisibilityOff,
                    onCheckedChange = onSetSessionPrivacy,
                )
                if (sessionOverride != null) {
                    TextButton(onClick = onClearSessionOverride) {
                        Text("Use saved default")
                    }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                PrivacyToggleRow(
                    title = "Start with amounts hidden",
                    detail = "Use privacy mode automatically whenever PaisaLens starts.",
                    checked = configuration.defaultEnabled,
                    icon = Icons.Rounded.Lock,
                    onCheckedChange = {
                        onConfigurationChange(configuration.copy(defaultEnabled = it))
                    },
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                PrivacyToggleRow(
                    title = "Protect screenshots and Recents",
                    detail = "When privacy mode is active, Android blocks screenshots, screen recording, and the Recents preview.",
                    checked = configuration.protectScreenCapture,
                    icon = Icons.Rounded.ScreenshotMonitor,
                    onCheckedChange = {
                        onConfigurationChange(configuration.copy(protectScreenCapture = it))
                    },
                )
            }
            item {
                Text(
                    "Privacy mode masks values in the interface. It does not change, delete, or round any stored financial data.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
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
private fun PrivacyToggleRow(
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
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null)
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
