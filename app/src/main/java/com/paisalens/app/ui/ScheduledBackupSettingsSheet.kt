package com.paisalens.app.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.paisalens.app.data.backup.ScheduledBackupConfiguration
import com.paisalens.app.data.backup.ScheduledBackupFrequency
import com.paisalens.app.data.backup.ScheduledBackupStatus
import com.paisalens.app.ui.components.PaisaCard
import java.time.DayOfWeek
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Standalone Settings UI; the host owns the SAF OpenDocumentTree launcher. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScheduledBackupSettingsSheet(
    configuration: ScheduledBackupConfiguration,
    status: ScheduledBackupStatus,
    hasStoredPassphrase: Boolean,
    selectedDestinationUri: String? = configuration.destinationUri,
    onChooseFolder: () -> Unit,
    onSave: (ScheduledBackupConfiguration, CharArray?) -> Result<Unit>,
    onForgetPassphrase: () -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var draft by remember { mutableStateOf(configuration) }
    var hourText by remember { mutableStateOf(configuration.hour.toString()) }
    var monthDayText by remember {
        mutableStateOf(configuration.monthDay.toString())
    }
    var retentionText by remember {
        mutableStateOf(configuration.retentionCount.toString())
    }
    var passphrase by remember { mutableStateOf("") }
    var confirmation by remember { mutableStateOf("") }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(selectedDestinationUri) {
        if (!selectedDestinationUri.isNullOrBlank() && selectedDestinationUri != draft.destinationUri) {
            draft = draft.copy(destinationUri = selectedDestinationUri)
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text("Scheduled encrypted backups", style = MaterialTheme.typography.headlineSmall)
            Text(
                "PaisaLens writes only to the Android folder you choose. Every copy is encrypted and verified on-device before older copies are rotated.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            PaisaCard(Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("Automatic backups", style = MaterialTheme.typography.titleMedium)
                        Text(
                            if (draft.enabled) "Enabled" else "Off — manual backups still work",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = draft.enabled,
                        onCheckedChange = { draft = draft.copy(enabled = it) },
                    )
                }
            }

            Text("Frequency", style = MaterialTheme.typography.titleSmall)
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                ScheduledBackupFrequency.entries.forEach { frequency ->
                    FilterChip(
                        selected = draft.frequency == frequency,
                        onClick = { draft = draft.copy(frequency = frequency) },
                        label = { Text(frequency.name.lowercase().replaceFirstChar(Char::uppercase)) },
                    )
                }
            }

            if (draft.frequency == ScheduledBackupFrequency.WEEKLY) {
                Text("Backup day", style = MaterialTheme.typography.titleSmall)
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    DayOfWeek.entries.forEach { day ->
                        FilterChip(
                            selected = draft.weekday == day,
                            onClick = { draft = draft.copy(weekday = day) },
                            label = { Text(day.name.take(3).lowercase().replaceFirstChar(Char::uppercase)) },
                        )
                    }
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = hourText,
                    onValueChange = { hourText = it.filter(Char::isDigit).take(2) },
                    modifier = Modifier.weight(1f),
                    label = { Text("Hour (0–23)") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
                OutlinedTextField(
                    value = retentionText,
                    onValueChange = { retentionText = it.filter(Char::isDigit).take(2) },
                    modifier = Modifier.weight(1f),
                    label = { Text("Copies (1–30)") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
            }

            if (draft.frequency == ScheduledBackupFrequency.MONTHLY) {
                OutlinedTextField(
                    value = monthDayText,
                    onValueChange = { monthDayText = it.filter(Char::isDigit).take(2) },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Day of month (1–31)") },
                    supportingText = { Text("Short months automatically use their last day.") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
            }

            PaisaCard(Modifier.fillMaxWidth()) {
                Column(Modifier.fillMaxWidth().padding(16.dp)) {
                    Text("Backup folder", style = MaterialTheme.typography.titleMedium)
                    Text(
                        draft.destinationUri?.let { "Android folder access granted" }
                            ?: "No folder selected",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(10.dp))
                    OutlinedButton(onClick = onChooseFolder) {
                        Text(if (draft.destinationUri == null) "Choose folder" else "Change folder")
                    }
                }
            }

            Text(
                if (hasStoredPassphrase) {
                    "Leave these blank to keep the saved backup password, or enter a new one."
                } else {
                    "Create a password with at least 8 characters. You will need it to restore a backup."
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(
                value = passphrase,
                onValueChange = { passphrase = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Backup password") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
            )
            if (passphrase.isNotEmpty()) {
                OutlinedTextField(
                    value = confirmation,
                    onValueChange = { confirmation = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Confirm password") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                )
            }

            BackupStatusCard(status)
            errorMessage?.let {
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (hasStoredPassphrase) {
                    OutlinedButton(
                        onClick = onForgetPassphrase,
                        contentPadding = PaddingValues(horizontal = 12.dp),
                    ) {
                        Text("Forget password")
                    }
                }
                Button(
                    modifier = Modifier.weight(1f),
                    onClick = {
                        val hour = hourText.toIntOrNull()
                        val monthDay = monthDayText.toIntOrNull()
                        val retention = retentionText.toIntOrNull()
                        errorMessage = when {
                            hour == null || hour !in 0..23 -> "Enter an hour between 0 and 23."
                            monthDay == null || monthDay !in 1..31 -> "Enter a day between 1 and 31."
                            retention == null || retention !in 1..30 -> "Keep between 1 and 30 copies."
                            draft.enabled && draft.destinationUri.isNullOrBlank() -> "Choose a backup folder first."
                            draft.enabled && !hasStoredPassphrase && passphrase.isEmpty() -> "Create a backup password first."
                            passphrase.isNotEmpty() && passphrase.length < 8 -> "Use at least 8 characters."
                            passphrase.isNotEmpty() && passphrase != confirmation -> "The passwords do not match."
                            else -> null
                        }
                        if (errorMessage == null) {
                            val safeDraft = draft.copy(
                                hour = requireNotNull(hour),
                                monthDay = requireNotNull(monthDay),
                                retentionCount = requireNotNull(retention),
                            )
                            onSave(
                                safeDraft,
                                passphrase.takeIf(String::isNotEmpty)?.toCharArray(),
                            ).onSuccess {
                                onDismiss()
                            }.onFailure { error ->
                                errorMessage = when (error) {
                                    is SecurityException -> "Choose the backup folder again to restore access."
                                    else -> error.message?.take(140)
                                        ?: "Could not save this backup schedule."
                                }
                            }
                        }
                    },
                ) {
                    Text("Save schedule")
                }
            }
            Spacer(Modifier.height(12.dp))
        }
    }
}

@Composable
private fun BackupStatusCard(status: ScheduledBackupStatus) {
    if (status.lastAttemptAt <= 0L) return
    PaisaCard(Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Latest scheduled backup", style = MaterialTheme.typography.titleMedium)
            val successful = status.lastSuccessfulAt >= status.lastFailureAt
            Text(
                if (successful) {
                    "Verified ${formatBackupTime(status.lastVerifiedAt)}"
                } else {
                    status.lastFailureMessage ?: "The latest attempt needs attention."
                },
                style = MaterialTheme.typography.bodySmall,
                color = if (successful) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.error
                },
            )
            status.lastFileName?.let { Text(it, style = MaterialTheme.typography.labelSmall) }
            status.lastWarningMessage?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.tertiary)
            }
        }
    }
}

private fun formatBackupTime(timestamp: Long): String = if (timestamp <= 0L) {
    "not yet"
} else {
    BACKUP_TIME_FORMATTER.format(Instant.ofEpochMilli(timestamp).atZone(ZoneId.systemDefault()))
}

private val BACKUP_TIME_FORMATTER = DateTimeFormatter.ofPattern("d MMM yyyy, h:mm a")
