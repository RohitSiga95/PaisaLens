package com.paisalens.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.FactCheck
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.automirrored.rounded.Rule
import androidx.compose.material.icons.automirrored.rounded.Undo
import androidx.compose.material.icons.rounded.Backup
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DataObject
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Sms
import androidx.compose.material.icons.rounded.Verified
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.paisalens.app.data.model.AuditBatchSummary
import com.paisalens.app.data.model.AuditAction
import com.paisalens.app.data.model.AuditEntityType
import com.paisalens.app.data.model.AuditEvent
import com.paisalens.app.data.model.AuditUndoResult
import com.paisalens.app.data.model.BackupVerificationMetadata
import com.paisalens.app.data.model.DataHealthFinding
import com.paisalens.app.data.model.DataHealthSeverity
import com.paisalens.app.data.model.DataHealthSummary
import com.paisalens.app.ui.components.PaisaCard
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Local data-quality and recovery surface. The caller owns scanning, backup selection,
 * verification, and undo execution; this sheet only presents state and captures intent.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DataHealthCenterSheet(
    hasSmsPermission: Boolean,
    lastScanAt: Long?,
    reviewCount: Int,
    uncategorizedMerchantCount: Int,
    unassignedAccountCount: Int,
    lastBackupCreatedAt: Long?,
    lastBackupVerifiedAt: Long?,
    backupVerification: BackupVerificationMetadata?,
    backupVerificationError: String?,
    dataHealth: DataHealthSummary,
    auditBatches: List<AuditBatchSummary>,
    auditEvents: List<AuditEvent>,
    lastUndoResult: AuditUndoResult?,
    isLoading: Boolean,
    errorMessage: String?,
    isScanning: Boolean,
    isVerifyingBackup: Boolean,
    undoInProgressBatchId: String?,
    onRequestSmsPermission: () -> Unit,
    onScanSms: () -> Unit,
    onReviewTransactions: () -> Unit,
    onReviewCategories: () -> Unit,
    onReviewUnassignedTransactions: () -> Unit,
    onRefreshBalancesOnHome: () -> Unit,
    onOpenTrustCenter: () -> Unit,
    onCreateBackup: () -> Unit,
    onVerifyBackup: () -> Unit,
    onUndoAuditBatch: (String) -> Unit,
    onDismissUndoResult: () -> Unit,
    onRetry: () -> Unit,
    onDismiss: () -> Unit,
) {
    var pendingUndo by remember { mutableStateOf<AuditBatchSummary?>(null) }
    var selectedAuditBatch by remember { mutableStateOf<AuditBatchSummary?>(null) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.background,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.95f)
                .navigationBarsPadding(),
        ) {
            DataHealthHeader(onDismiss)
            when {
                isLoading -> TrustLoadingState(
                    title = "Checking data health",
                    detail = "Reading local records, backup status, and audit history.",
                )
                errorMessage != null -> TrustErrorState(errorMessage, onRetry)
                else -> LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = 18.dp, end = 18.dp, bottom = 32.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    item {
                        LocalTrustNotice(
                            "All checks run locally. Backup verification reads the file you choose and does not restore or upload it.",
                        )
                    }
                    lastUndoResult?.let { result ->
                        item {
                            UndoResultCard(result = result, onDismiss = onDismissUndoResult)
                        }
                    }
                    item {
                        OverallHealthCard(
                            dataHealth = dataHealth,
                            hasSmsPermission = hasSmsPermission,
                            reviewCount = reviewCount,
                            uncategorizedMerchantCount = uncategorizedMerchantCount,
                            unassignedAccountCount = unassignedAccountCount,
                            lastBackupCreatedAt = lastBackupCreatedAt,
                            lastBackupVerifiedAt = lastBackupVerifiedAt,
                        )
                    }
                    item {
                        Text("Data checks", style = MaterialTheme.typography.titleLarge)
                    }
                    item {
                        SmsHealthCard(
                            hasPermission = hasSmsPermission,
                            lastScanAt = lastScanAt,
                            isScanning = isScanning,
                            onRequestPermission = onRequestSmsPermission,
                            onScan = onScanSms,
                        )
                    }
                    if (reviewCount > 0) {
                        item {
                            HealthFindingCard(
                                icon = Icons.AutoMirrored.Rounded.FactCheck,
                                title = "Transactions need review",
                                detail = "$reviewCount transaction${pluralSuffix(reviewCount)} need confirmation before analytics can trust them.",
                                actionLabel = "Review transactions",
                                severity = HealthVisualSeverity.ACTION,
                                onAction = onReviewTransactions,
                            )
                        }
                    }
                    if (uncategorizedMerchantCount > 0) {
                        item {
                            HealthFindingCard(
                                icon = Icons.Rounded.DataObject,
                                title = "Merchant categories are incomplete",
                                detail = "$uncategorizedMerchantCount merchant${pluralSuffix(uncategorizedMerchantCount)} still use Other.",
                                actionLabel = "Review categories",
                                severity = HealthVisualSeverity.WARNING,
                                onAction = onReviewCategories,
                            )
                        }
                    }
                    if (unassignedAccountCount > 0) {
                        item {
                            HealthFindingCard(
                                icon = Icons.Rounded.WarningAmber,
                                title = "Transactions lack an account",
                                detail = "$unassignedAccountCount transaction${pluralSuffix(unassignedAccountCount)} cannot be included in account reconciliation.",
                                actionLabel = "Assign accounts",
                                severity = HealthVisualSeverity.WARNING,
                                onAction = onReviewUnassignedTransactions,
                            )
                        }
                    }
                    val representedFindingKeys = setOf("review", "uncategorized")
                    items(
                        items = dataHealth.findings.filterNot { it.key in representedFindingKeys },
                        key = { "health:${it.key}" },
                    ) { finding ->
                        CoreHealthFindingCard(
                            finding = finding,
                            onAction = when (finding.key) {
                                "stale-balances" -> onRefreshBalancesOnHome
                                "reconciliation", "unlinked-transfers" -> onOpenTrustCenter
                                else -> onReviewTransactions
                            },
                        )
                    }
                    item {
                        BackupHealthCard(
                            lastBackupCreatedAt = lastBackupCreatedAt,
                            lastBackupVerifiedAt = lastBackupVerifiedAt,
                            verification = backupVerification,
                            verificationError = backupVerificationError,
                            isVerifying = isVerifyingBackup,
                            onCreateBackup = onCreateBackup,
                            onVerifyBackup = onVerifyBackup,
                        )
                    }
                    item {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text("Audit history", style = MaterialTheme.typography.titleLarge)
                            Text(
                                "Recent changes are grouped so a reversible batch can be undone safely.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    if (auditBatches.isEmpty()) {
                        item {
                            TrustEmptyState(
                                icon = Icons.Rounded.History,
                                title = "No tracked changes yet",
                                detail = "Imports, links, reconciliation edits, and their reversals will appear here.",
                            )
                        }
                    } else {
                        items(auditBatches, key = AuditBatchSummary::batchId) { batch ->
                            AuditBatchCard(
                                batch = batch,
                                isUndoing = undoInProgressBatchId == batch.batchId,
                                isAnyUndoInProgress = undoInProgressBatchId != null,
                                onOpen = { selectedAuditBatch = batch },
                                onUndo = { pendingUndo = batch },
                            )
                        }
                    }
                }
            }
        }
    }

    pendingUndo?.let { batch ->
        AlertDialog(
            onDismissRequest = { pendingUndo = null },
            icon = { Icon(Icons.AutoMirrored.Rounded.Undo, contentDescription = null) },
            title = { Text("Undo this batch?") },
            text = {
                Text(
                    "“${batch.label}” changed ${batch.eventCount} record${pluralSuffix(batch.eventCount)}. " +
                        "PaisaLens will add a new reversal entry so the history remains traceable.",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (undoInProgressBatchId != null) return@TextButton
                        onUndoAuditBatch(batch.batchId)
                        pendingUndo = null
                    },
                    enabled = undoInProgressBatchId == null,
                    modifier = Modifier.heightIn(min = 48.dp),
                ) {
                    Text(if (undoInProgressBatchId == null) "Undo batch" else "Undo in progress…")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { pendingUndo = null },
                    modifier = Modifier.heightIn(min = 48.dp),
                ) {
                    Text("Cancel")
                }
            },
        )
    }
    selectedAuditBatch?.let { batch ->
        AuditBatchDetailsDialog(
            batch = batch,
            events = auditEvents.filter { it.batchId == batch.batchId }.sortedByDescending(AuditEvent::occurredAt),
            onDismiss = { selectedAuditBatch = null },
        )
    }
}

@Composable
private fun DataHealthHeader(onDismiss: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 12.dp, bottom = 14.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier.size(48.dp).background(MaterialTheme.colorScheme.secondaryContainer, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Rounded.Verified, contentDescription = null, tint = MaterialTheme.colorScheme.secondary)
            }
            Column {
                Text("Data Health Centre", style = MaterialTheme.typography.headlineMedium)
                Text(
                    "Accuracy, backups, and recovery",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        IconButton(onClick = onDismiss, modifier = Modifier.size(48.dp)) {
            Icon(Icons.Rounded.Close, contentDescription = "Close Data Health Centre")
        }
    }
}

@Composable
private fun OverallHealthCard(
    dataHealth: DataHealthSummary,
    hasSmsPermission: Boolean,
    reviewCount: Int,
    uncategorizedMerchantCount: Int,
    unassignedAccountCount: Int,
    lastBackupCreatedAt: Long?,
    lastBackupVerifiedAt: Long?,
) {
    val issueCount = reviewCount + uncategorizedMerchantCount + unassignedAccountCount
    val backupVerified = lastBackupCreatedAt != null && lastBackupVerifiedAt != null &&
        lastBackupVerifiedAt >= lastBackupCreatedAt
    val hasWarningOrActionFinding = dataHealth.findings.any {
        it.severity == DataHealthSeverity.WARNING || it.severity == DataHealthSeverity.ACTION_REQUIRED
    }
    val isHealthy = hasSmsPermission && issueCount == 0 && backupVerified &&
        !hasWarningOrActionFinding && dataHealth.score >= 90
    val statusColor = if (isHealthy) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.primary
    PaisaCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Overall status", style = MaterialTheme.typography.labelLarge)
                    Text(
                        if (isHealthy) "Healthy" else "A few checks need attention",
                        style = MaterialTheme.typography.titleLarge,
                        color = statusColor,
                    )
                }
                Icon(
                    if (isHealthy) Icons.Rounded.CheckCircle else Icons.AutoMirrored.Rounded.FactCheck,
                    contentDescription = null,
                    tint = statusColor,
                    modifier = Modifier.size(30.dp),
                )
            }
            Text(
                "${dataHealth.score} / 100 local data-quality score",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MiniHealthStatus("SMS", hasSmsPermission, Modifier.weight(1f))
                MiniHealthStatus("Records", issueCount == 0 && !hasWarningOrActionFinding, Modifier.weight(1f))
                MiniHealthStatus("Backup", backupVerified, Modifier.weight(1f))
            }
            Text(
                "This status reflects completeness and recoverability, not bank confirmation or fraud detection.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun CoreHealthFindingCard(
    finding: DataHealthFinding,
    onAction: () -> Unit,
) {
    val severity = when (finding.severity) {
        DataHealthSeverity.INFO -> HealthVisualSeverity.GOOD
        DataHealthSeverity.WARNING -> HealthVisualSeverity.WARNING
        DataHealthSeverity.ACTION_REQUIRED -> HealthVisualSeverity.ACTION
    }
    val actionLabel = when (finding.key) {
        "stale-balances" -> "Refresh balances on Home"
        "reconciliation", "unlinked-transfers" -> "Open Trust Center"
        else -> when (finding.severity) {
            DataHealthSeverity.INFO, DataHealthSeverity.WARNING -> "Review details"
            DataHealthSeverity.ACTION_REQUIRED -> "Fix now"
        }
    }
    HealthFindingCard(
        icon = when (finding.key) {
            "stale-balances" -> Icons.Rounded.Refresh
            "reconciliation" -> Icons.AutoMirrored.Rounded.FactCheck
            "unlinked-transfers" -> Icons.AutoMirrored.Rounded.Rule
            else -> Icons.Rounded.Info
        },
        title = finding.title,
        detail = finding.detail,
        actionLabel = actionLabel,
        severity = severity,
        onAction = onAction,
    )
}

@Composable
private fun MiniHealthStatus(label: String, healthy: Boolean, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = MaterialTheme.shapes.medium,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Icon(
                if (healthy) Icons.Rounded.CheckCircle else Icons.Rounded.WarningAmber,
                contentDescription = null,
                tint = if (healthy) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp),
            )
            Text(label, style = MaterialTheme.typography.labelMedium)
        }
    }
}

@Composable
private fun SmsHealthCard(
    hasPermission: Boolean,
    lastScanAt: Long?,
    isScanning: Boolean,
    onRequestPermission: () -> Unit,
    onScan: () -> Unit,
) {
    HealthFindingCard(
        icon = Icons.Rounded.Sms,
        title = if (hasPermission) "Financial SMS access is ready" else "Financial SMS access is off",
        detail = if (hasPermission) {
            lastScanAt?.let { "Last local scan: ${fullHealthDateTime(it)}" } ?: "No inbox scan has completed yet."
        } else {
            "Grant access to scan financial messages. Non-financial SMS should never be retained."
        },
        actionLabel = if (isScanning) "Scanning…" else if (hasPermission) "Scan now" else "Review permission",
        severity = if (hasPermission) HealthVisualSeverity.GOOD else HealthVisualSeverity.ACTION,
        actionEnabled = !isScanning,
        showProgress = isScanning,
        onAction = if (hasPermission) onScan else onRequestPermission,
    )
}

private enum class HealthVisualSeverity { GOOD, WARNING, ACTION }

@Composable
private fun HealthFindingCard(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    detail: String,
    actionLabel: String,
    severity: HealthVisualSeverity,
    actionEnabled: Boolean = true,
    showProgress: Boolean = false,
    onAction: () -> Unit,
) {
    val iconColor = when (severity) {
        HealthVisualSeverity.GOOD -> MaterialTheme.colorScheme.secondary
        HealthVisualSeverity.WARNING -> MaterialTheme.colorScheme.primary
        HealthVisualSeverity.ACTION -> MaterialTheme.colorScheme.error
    }
    PaisaCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
                Box(
                    modifier = Modifier.size(48.dp).background(iconColor.copy(alpha = 0.14f), CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(icon, contentDescription = null, tint = iconColor)
                }
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(title, style = MaterialTheme.typography.titleMedium)
                    Text(detail, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            OutlinedButton(
                onClick = onAction,
                enabled = actionEnabled,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
            ) {
                if (showProgress) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.size(8.dp))
                }
                Text(actionLabel)
            }
        }
    }
}

@Composable
private fun BackupHealthCard(
    lastBackupCreatedAt: Long?,
    lastBackupVerifiedAt: Long?,
    verification: BackupVerificationMetadata?,
    verificationError: String?,
    isVerifying: Boolean,
    onCreateBackup: () -> Unit,
    onVerifyBackup: () -> Unit,
) {
    val verificationAfterLatestCreation = lastBackupCreatedAt != null && lastBackupVerifiedAt != null &&
        lastBackupVerifiedAt >= lastBackupCreatedAt
    val hasVerifiedFile = verification != null || lastBackupVerifiedAt != null
    PaisaCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier.size(48.dp).background(MaterialTheme.colorScheme.primaryContainer, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Rounded.Backup, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text("Encrypted backup", style = MaterialTheme.typography.titleMedium)
                    Text(
                        when {
                            verification != null -> "Selected backup file verified"
                            verificationAfterLatestCreation -> "A backup file was verified after the latest export"
                            lastBackupCreatedAt != null -> "A newer backup was created after the last verification"
                            hasVerifiedFile -> "A backup file was verified"
                            else -> "No backup has been created yet"
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (hasVerifiedFile) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Icon(
                    if (hasVerifiedFile) Icons.Rounded.Verified else Icons.Rounded.WarningAmber,
                    contentDescription = null,
                    tint = if (hasVerifiedFile) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.primary,
                )
            }
            lastBackupCreatedAt?.let { SourceHealthRow("Created", fullHealthDateTime(it)) }
            lastBackupVerifiedAt?.let { SourceHealthRow("Verified", fullHealthDateTime(it)) }
            verification?.let { metadata ->
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = MaterialTheme.shapes.medium,
                ) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("Verified contents", style = MaterialTheme.typography.labelLarge)
                        SourceHealthRow("Format", "v${metadata.formatVersion}")
                        SourceHealthRow("Tracked records", metadata.totalRecordCount.toString())
                        SourceHealthRow("Transactions", metadata.transactionCount.toString())
                        SourceHealthRow("Accounts", metadata.accountCount.toString())
                        SourceHealthRow("Reconciliations", metadata.reconciliationCount.toString())
                        SourceHealthRow("Audit entries", metadata.auditEventCount.toString())
                        SourceHealthRow("SHA-256", abbreviateHash(metadata.contentSha256))
                    }
                }
            }
            verificationError?.let { message ->
                Surface(
                    color = MaterialTheme.colorScheme.errorContainer,
                    contentColor = MaterialTheme.colorScheme.onErrorContainer,
                    shape = MaterialTheme.shapes.medium,
                ) {
                    Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(Icons.Rounded.WarningAmber, contentDescription = null)
                        Text(message, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
            Text(
                "Verification checks the encrypted backup structure, counts, and checksum. It does not import or replace current data.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(
                    onClick = onCreateBackup,
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                ) {
                    Text("Create backup")
                }
                Button(
                    onClick = onVerifyBackup,
                    enabled = !isVerifying,
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                ) {
                    if (isVerifying) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.size(8.dp))
                    }
                    Text(if (isVerifying) "Verifying…" else "Verify file")
                }
            }
        }
    }
}

@Composable
private fun SourceHealthRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            value,
            modifier = Modifier.padding(start = 12.dp),
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Medium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun AuditBatchCard(
    batch: AuditBatchSummary,
    isUndoing: Boolean,
    isAnyUndoInProgress: Boolean,
    onOpen: () -> Unit,
    onUndo: () -> Unit,
) {
    PaisaCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Surface(
                onClick = onOpen,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).semantics { role = Role.Button },
                color = Color.Transparent,
                shape = MaterialTheme.shapes.medium,
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        modifier = Modifier.size(48.dp).background(
                            if (batch.isUndo) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                            CircleShape,
                        ),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            if (batch.isUndo) Icons.AutoMirrored.Rounded.Undo else Icons.Rounded.History,
                            contentDescription = null,
                            tint = if (batch.isUndo) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.primary,
                        )
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text(batch.label, style = MaterialTheme.typography.titleMedium, maxLines = 2)
                        Text(
                            "${fullHealthDateTime(batch.occurredAt)} · ${batch.eventCount} change${pluralSuffix(batch.eventCount)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            if (batch.isUndo) "Reversal batch" else "Local audit batch",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Icon(Icons.AutoMirrored.Rounded.OpenInNew, contentDescription = "Open audit batch details")
                }
            }
            if (batch.canUndo) {
                OutlinedButton(
                    onClick = onUndo,
                    enabled = !isAnyUndoInProgress,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                ) {
                    if (isUndoing) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.size(8.dp))
                    } else {
                        Icon(Icons.AutoMirrored.Rounded.Undo, contentDescription = null)
                        Spacer(Modifier.size(8.dp))
                    }
                    Text(
                        when {
                            isUndoing -> "Undoing…"
                            isAnyUndoInProgress -> "Another undo is running"
                            else -> "Undo this batch"
                        },
                    )
                }
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.Lock, contentDescription = null, modifier = Modifier.size(18.dp))
                    Text(
                        if (batch.isUndo) "Undo batches preserve the audit trail and cannot be undone again."
                        else "This batch is already reversed or cannot be safely undone.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun UndoResultCard(result: AuditUndoResult, onDismiss: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        shape = MaterialTheme.shapes.large,
    ) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.CheckCircle, contentDescription = null)
                Text("Changes were undone", style = MaterialTheme.typography.titleMedium)
            }
            Text(
                "Restored ${result.insertedEntities}, updated ${result.updatedEntities}, and removed ${result.deletedEntities} record${pluralSuffix(result.deletedEntities)}.",
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                "Reversal batch: ${result.undoBatchId}",
                style = MaterialTheme.typography.bodySmall,
            )
            TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.End).heightIn(min = 48.dp)) {
                Text("Dismiss")
            }
        }
    }
}

@Composable
private fun AuditBatchDetailsDialog(
    batch: AuditBatchSummary,
    events: List<AuditEvent>,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Rounded.History, contentDescription = null) },
        title = { Text(batch.label) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                SourceHealthRow("When", fullHealthDateTime(batch.occurredAt))
                SourceHealthRow("Batch ID", batch.batchId)
                SourceHealthRow("Changes", batch.eventCount.toString())
                SourceHealthRow("Kind", if (batch.isUndo) "Reversal" else "Original change")
                if (events.isEmpty()) {
                    Text(
                        "Detailed events are no longer available for this batch.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    events.forEachIndexed { index, event ->
                        if (index > 0) androidx.compose.material3.HorizontalDivider()
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(
                                "${auditActionLabel(event.action)} ${auditEntityLabel(event.entityType)}",
                                style = MaterialTheme.typography.titleMedium,
                            )
                            Text(
                                "Entity ${event.entityId} · ${fullHealthDateTime(event.occurredAt)}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            event.beforePayload?.let {
                                Text("Before: ${safePayloadSummary(it)}", style = MaterialTheme.typography.bodySmall)
                            }
                            event.afterPayload?.let {
                                Text("After: ${safePayloadSummary(it)}", style = MaterialTheme.typography.bodySmall)
                            }
                            event.reversesEventId?.let {
                                Text("Reverses audit event #$it", style = MaterialTheme.typography.labelMedium)
                            }
                        }
                    }
                }
                Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = MaterialTheme.shapes.medium) {
                    Text(
                        "This history comes from PaisaLens' local audit log. Payload previews are abbreviated and never sent off-device.",
                        modifier = Modifier.padding(12.dp),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp)) {
                Text("Done")
            }
        },
    )
}

private fun auditActionLabel(action: AuditAction): String = when (action) {
    AuditAction.INSERT -> "Added"
    AuditAction.UPDATE -> "Updated"
    AuditAction.DELETE -> "Deleted"
}

private fun auditEntityLabel(type: AuditEntityType): String = when (type) {
    AuditEntityType.TRANSACTION -> "transaction"
    AuditEntityType.TRANSACTION_LINK -> "transaction link"
    AuditEntityType.MONTHLY_RECONCILIATION -> "reconciliation"
}

private fun safePayloadSummary(payload: String): String = payload
    .replace(Regex("\\s+"), " ")
    .take(180)
    .let { if (payload.length > 180) "$it…" else it }

private fun pluralSuffix(count: Int): String = if (count == 1) "" else "s"

private fun abbreviateHash(hash: String): String = when {
    hash.length <= 18 -> hash
    else -> "${hash.take(9)}…${hash.takeLast(8)}"
}

private fun fullHealthDateTime(epochMillis: Long): String = SimpleDateFormat(
    "d MMM yyyy, h:mm a",
    Locale.getDefault(),
).format(Date(epochMillis))
