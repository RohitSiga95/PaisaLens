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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ReceiptLong
import androidx.compose.material.icons.automirrored.rounded.FactCheck
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.automirrored.rounded.Rule
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Calculate
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.CreditCard
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.UploadFile
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.paisalens.app.data.model.StatementAmountDirection
import com.paisalens.app.data.model.StatementAuditConfidence
import com.paisalens.app.data.model.StatementAuditIssue
import com.paisalens.app.data.model.StatementAuditIssueSeverity
import com.paisalens.app.data.model.StatementAuditLineResult
import com.paisalens.app.data.model.StatementAuditLineStatus
import com.paisalens.app.data.model.StatementAuditMetadata
import com.paisalens.app.data.model.StatementAuditReport
import com.paisalens.app.data.model.StatementAuditRow
import com.paisalens.app.data.model.StatementAuditTotals
import com.paisalens.app.data.model.StatementInputMode
import com.paisalens.app.data.model.StatementLineKind
import com.paisalens.app.data.model.StatementSourceSupport
import com.paisalens.app.ui.components.PaisaCard
import com.paisalens.app.ui.components.formatMoney
import java.math.BigDecimal
import java.math.RoundingMode
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeParseException
import java.util.Currency
import java.util.Date
import java.util.Locale

private enum class AuditLineFilter(val label: String) {
    ALL("All"),
    MATCHED("Matched"),
    UNMATCHED("Unmatched"),
    POSSIBLE_DUPLICATE("Possible duplicate"),
}

private enum class MetadataEditorPurpose {
    CHOOSE_STRUCTURED_FILE,
    REAUDIT_REPORT,
}

/** Presents an auditor result without mutating or importing any transaction automatically. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CreditCardStatementAuditSheet(
    report: StatementAuditReport?,
    sourceSupport: StatementSourceSupport?,
    initialMetadata: StatementAuditMetadata,
    isLoading: Boolean,
    errorMessage: String?,
    onChooseStructuredFile: (StatementAuditMetadata) -> Unit,
    onReauditWithMetadata: (StatementAuditMetadata) -> Unit,
    onOpenManualFallback: () -> Unit,
    onOpenTransaction: (Long) -> Unit,
    onRetry: () -> Unit,
    onDismiss: () -> Unit,
) {
    var lineFilter by remember { mutableStateOf(AuditLineFilter.ALL) }
    var selectedLine by remember { mutableStateOf<StatementAuditLineResult?>(null) }
    var metadataEditorPurpose by remember { mutableStateOf<MetadataEditorPurpose?>(null) }
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
            StatementAuditHeader(onDismiss)
            when {
                isLoading -> TrustLoadingState(
                    title = "Auditing statement",
                    detail = "Matching statement rows with local PaisaLens transactions.",
                )
                errorMessage != null -> TrustErrorState(errorMessage, onRetry)
                report == null -> StatementAuditEmptyState(
                    sourceSupport = sourceSupport,
                    onChooseStructuredFile = {
                        metadataEditorPurpose = MetadataEditorPurpose.CHOOSE_STRUCTURED_FILE
                    },
                    onManualFallback = onOpenManualFallback,
                )
                else -> {
                    val filteredLines = remember(report.lines, lineFilter) {
                        when (lineFilter) {
                            AuditLineFilter.ALL -> report.lines
                            AuditLineFilter.MATCHED -> report.lines.filter { it.status == StatementAuditLineStatus.MATCHED }
                            AuditLineFilter.UNMATCHED -> report.lines.filter { it.status == StatementAuditLineStatus.UNMATCHED }
                            AuditLineFilter.POSSIBLE_DUPLICATE -> report.lines.filter {
                                it.status == StatementAuditLineStatus.POSSIBLE_DUPLICATE
                            }
                        }
                    }
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(start = 18.dp, end = 18.dp, bottom = 32.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        item {
                            LocalTrustNotice(
                                "The audit is read-only and runs locally. Matches are suggestions based on amount, date, account, and description.",
                            )
                        }
                        sourceSupport?.let { support ->
                            if (!support.directParsingSupported) {
                                item {
                                    ManualFallbackBanner(
                                        support = support,
                                        onOpenManualFallback = onOpenManualFallback,
                                    )
                                }
                            }
                        }
                        item {
                            StatementMetadataCard(
                                metadata = report.metadata,
                                onEdit = { metadataEditorPurpose = MetadataEditorPurpose.REAUDIT_REPORT },
                            )
                        }
                        item { AuditCoverageCard(report) }
                        if (report.issues.isNotEmpty() || report.warnings.isNotEmpty()) {
                            item {
                                AuditIssuesCard(report.issues, report.warnings)
                            }
                        }
                        item { StatementTotalsCard(report.totals, report.metadata.currency) }
                        item {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text("Statement lines", style = MaterialTheme.typography.titleLarge)
                                Text(
                                    "Tap a line to see its evidence, score, and possible matches.",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    items(AuditLineFilter.entries) { filter ->
                                        val count = when (filter) {
                                            AuditLineFilter.ALL -> report.lines.size
                                            AuditLineFilter.MATCHED -> report.matchedCount
                                            AuditLineFilter.UNMATCHED -> report.unmatchedCount
                                            AuditLineFilter.POSSIBLE_DUPLICATE -> report.possibleDuplicateCount
                                        }
                                        FilterChip(
                                            selected = lineFilter == filter,
                                            onClick = { lineFilter = filter },
                                            modifier = Modifier.heightIn(min = 48.dp),
                                            label = { Text("${filter.label} ($count)") },
                                        )
                                    }
                                }
                            }
                        }
                        if (filteredLines.isEmpty()) {
                            item {
                                TrustEmptyState(
                                    icon = Icons.Rounded.CheckCircle,
                                    title = "No ${lineFilter.label.lowercase()} lines",
                                    detail = "Choose another filter to inspect the remaining statement rows.",
                                )
                            }
                        } else {
                            items(filteredLines, key = { it.row.rowNumber }) { line ->
                                StatementAuditLineCard(
                                    line = line,
                                    currency = report.metadata.currency,
                                    onClick = { selectedLine = line },
                                )
                            }
                        }
                        if (report.unmatchedExistingTransactions.isNotEmpty()) {
                            item {
                                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Text("App-only transactions", style = MaterialTheme.typography.titleLarge)
                                    Text(
                                        "These local records were not found on the audited statement.",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                            items(report.unmatchedExistingTransactions, key = { "app-only:${it.id}" }) { transaction ->
                                Surface(
                                    onClick = { onOpenTransaction(transaction.id) },
                                    modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp).semantics { role = Role.Button },
                                    color = MaterialTheme.colorScheme.surface,
                                    shape = MaterialTheme.shapes.large,
                                ) {
                                    Row(
                                        modifier = Modifier.padding(16.dp),
                                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Icon(Icons.AutoMirrored.Rounded.OpenInNew, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(transaction.merchant, style = MaterialTheme.typography.titleMedium)
                                            Text(
                                                "Local ${transaction.source.name.lowercase()} · ${auditDateTime(transaction.occurredAt)}",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                        }
                                        Text(formatMoney(transaction.amountMinor), fontWeight = FontWeight.Bold)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    selectedLine?.let { line ->
        StatementLineDetailsDialog(
            line = line,
            currency = report?.metadata?.currency ?: line.row.currency,
            onOpenTransaction = onOpenTransaction,
            onDismiss = { selectedLine = null },
        )
    }
    metadataEditorPurpose?.let { purpose ->
        StatementMetadataEditorDialog(
            initialMetadata = if (purpose == MetadataEditorPurpose.REAUDIT_REPORT && report != null) {
                report.metadata
            } else {
                initialMetadata
            },
            onSave = { metadata ->
                when (purpose) {
                    MetadataEditorPurpose.CHOOSE_STRUCTURED_FILE -> onChooseStructuredFile(metadata)
                    MetadataEditorPurpose.REAUDIT_REPORT -> onReauditWithMetadata(metadata)
                }
                metadataEditorPurpose = null
            },
            onDismiss = { metadataEditorPurpose = null },
        )
    }
}

@Composable
private fun StatementAuditHeader(onDismiss: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 12.dp, bottom = 14.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier.size(48.dp).background(MaterialTheme.colorScheme.primaryContainer, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Rounded.CreditCard, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            }
            Column {
                Text("Statement audit", style = MaterialTheme.typography.headlineMedium)
                Text(
                    "Credit-card totals and matches",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        IconButton(onClick = onDismiss, modifier = Modifier.size(48.dp)) {
            Icon(Icons.Rounded.Close, contentDescription = "Close statement audit")
        }
    }
}

@Composable
private fun StatementAuditEmptyState(
    sourceSupport: StatementSourceSupport?,
    onChooseStructuredFile: () -> Unit,
    onManualFallback: () -> Unit,
) {
    Box(Modifier.fillMaxSize().padding(20.dp), contentAlignment = Alignment.Center) {
        Column(verticalArrangement = Arrangement.spacedBy(14.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                Icons.AutoMirrored.Rounded.ReceiptLong,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(52.dp),
            )
            Text("No audit report yet", style = MaterialTheme.typography.titleLarge)
            Text(
                sourceSupport?.message
                    ?: "Choose a CSV/XLSX statement, or enter statement details manually when a PDF cannot be parsed safely.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Button(onClick = onChooseStructuredFile, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                Icon(Icons.Rounded.UploadFile, contentDescription = null)
                Spacer(Modifier.size(8.dp))
                Text("Choose CSV/XLSX")
            }
            OutlinedButton(onClick = onManualFallback, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                Icon(Icons.Rounded.Edit, contentDescription = null)
                Spacer(Modifier.size(8.dp))
                Text("Enter statement manually")
            }
        }
    }
}

@Composable
private fun ManualFallbackBanner(
    support: StatementSourceSupport,
    onOpenManualFallback: () -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        shape = MaterialTheme.shapes.large,
    ) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Edit, contentDescription = null)
                Text("Structured manual fallback", style = MaterialTheme.typography.titleMedium)
            }
            Text(support.message, style = MaterialTheme.typography.bodyMedium)
            if (support.requiredManualFields.isNotEmpty()) {
                Text(
                    "Needed: ${support.requiredManualFields.joinToString()}",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            OutlinedButton(
                onClick = onOpenManualFallback,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
            ) {
                Text("Review manual values")
            }
        }
    }
}

@Composable
private fun StatementMetadataCard(
    metadata: StatementAuditMetadata,
    onEdit: () -> Unit,
) {
    PaisaCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(metadata.issuer?.takeIf(String::isNotBlank) ?: "Credit-card statement", style = MaterialTheme.typography.titleLarge)
                    Text(
                        buildString {
                            metadata.accountName?.takeIf(String::isNotBlank)?.let(::append)
                            metadata.cardLast4?.takeIf(String::isNotBlank)?.let {
                                if (isNotEmpty()) append(" · ")
                                append("•••• $it")
                            }
                        }.ifBlank { "Account not assigned" },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = onEdit, modifier = Modifier.size(48.dp)) {
                    Icon(Icons.Rounded.Edit, contentDescription = "Edit statement details")
                }
            }
            AuditSourceRow("Source file", metadata.sourceFileName ?: "Manual entry")
            AuditSourceRow("Statement ID", metadata.statementId)
            metadata.statementDateEpochDay?.let { AuditSourceRow("Statement date", auditDate(it)) }
            metadata.dueDateEpochDay?.let { AuditSourceRow("Payment due", auditDate(it)) }
            metadata.totalDueMinor?.let {
                AuditSourceRow("Declared total due", formatAuditMoney(it, metadata.currency))
            }
            Text(
                "These values come from the selected file or your manual review. Edit them if the statement summary was read incorrectly.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun AuditCoverageCard(report: StatementAuditReport) {
    val total = report.lines.size
    val covered = report.matchedCount
    val percent = if (total == 0) 0 else covered * 100 / total
    PaisaCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Audit coverage", style = MaterialTheme.typography.labelLarge)
                    Text("$percent% matched", style = MaterialTheme.typography.headlineMedium)
                }
                Icon(
                    if (report.unmatchedCount == 0 && report.possibleDuplicateCount == 0) Icons.Rounded.CheckCircle
                    else Icons.Rounded.WarningAmber,
                    contentDescription = null,
                    tint = if (report.unmatchedCount == 0 && report.possibleDuplicateCount == 0) {
                        MaterialTheme.colorScheme.secondary
                    } else {
                        MaterialTheme.colorScheme.primary
                    },
                    modifier = Modifier.size(30.dp),
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AuditCount("Matched", report.matchedCount, Modifier.weight(1f))
                AuditCount("Unmatched", report.unmatchedCount, Modifier.weight(1f))
                AuditCount("Possible dupes", report.possibleDuplicateCount, Modifier.weight(1f))
            }
            Text(
                "A matched line means PaisaLens found a plausible local record; it is not a bank authorisation check.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun AuditCount(label: String, count: Int, modifier: Modifier = Modifier) {
    Surface(modifier = modifier, color = MaterialTheme.colorScheme.surfaceVariant, shape = MaterialTheme.shapes.medium) {
        Column(Modifier.padding(10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(count.toString(), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text(label, style = MaterialTheme.typography.labelMedium, maxLines = 2)
        }
    }
}

@Composable
private fun AuditIssuesCard(issues: List<StatementAuditIssue>, warnings: List<String>) {
    PaisaCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Checks needing attention", style = MaterialTheme.typography.titleLarge)
            issues.forEach { issue -> AuditIssueRow(issue) }
            warnings.forEach { warning ->
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Top) {
                    Icon(Icons.Rounded.Info, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Text(warning, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}

@Composable
private fun AuditIssueRow(issue: StatementAuditIssue) {
    val color = when (issue.severity) {
        StatementAuditIssueSeverity.INFO -> MaterialTheme.colorScheme.primary
        StatementAuditIssueSeverity.WARNING -> MaterialTheme.colorScheme.primary
        StatementAuditIssueSeverity.ERROR -> MaterialTheme.colorScheme.error
    }
    Surface(color = color.copy(alpha = 0.10f), shape = MaterialTheme.shapes.medium) {
        Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Top) {
            Icon(
                if (issue.severity == StatementAuditIssueSeverity.ERROR) Icons.Rounded.ErrorOutline else Icons.Rounded.WarningAmber,
                contentDescription = null,
                tint = color,
            )
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(issue.title, style = MaterialTheme.typography.titleMedium)
                Text(issue.detail, style = MaterialTheme.typography.bodyMedium)
                Text("Next: ${issue.recommendation}", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium)
                if (issue.rowNumbers.isNotEmpty()) {
                    Text("Rows: ${issue.rowNumbers.joinToString()}", style = MaterialTheme.typography.labelMedium)
                }
            }
        }
    }
}

@Composable
private fun StatementTotalsCard(totals: StatementAuditTotals, currency: String) {
    PaisaCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Calculate, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Text("Statement totals", style = MaterialTheme.typography.titleLarge)
            }
            TotalsRow("Purchases", totals.purchasesMinor, currency)
            TotalsRow("Fees", totals.feesMinor, currency)
            TotalsRow("Interest", totals.interestMinor, currency)
            TotalsRow("GST", totals.gstMinor, currency)
            TotalsRow("Other debits", totals.otherDebitsMinor, currency)
            HorizontalDivider()
            TotalsRow("Refunds", totals.refundsMinor, currency)
            TotalsRow("Payments", totals.paymentsMinor, currency)
            TotalsRow("Other credits", totals.otherCreditsMinor, currency)
            HorizontalDivider()
            TotalsRow("Calculated closing", totals.calculatedClosingBalanceMinor, currency, strong = true)
            totals.declaredTotalDueMinor?.let { TotalsRow("Declared total due", it, currency, strong = true) }
            totals.totalDueDifferenceMinor?.let { difference ->
                Surface(
                    color = if (difference == 0L) MaterialTheme.colorScheme.secondaryContainer
                    else MaterialTheme.colorScheme.errorContainer,
                    contentColor = if (difference == 0L) MaterialTheme.colorScheme.onSecondaryContainer
                    else MaterialTheme.colorScheme.onErrorContainer,
                    shape = MaterialTheme.shapes.medium,
                ) {
                    Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Total-due difference")
                        Text(formatSignedAuditMoney(difference, currency), fontWeight = FontWeight.Bold)
                    }
                }
            }
            totals.minimumDueMinor?.let { TotalsRow("Minimum due", it, currency) }
            totals.dueDateEpochDay?.let { AuditSourceRow("Due date", auditDate(it)) }
        }
    }
}

@Composable
private fun TotalsRow(label: String, value: Long, currency: String, strong: Boolean = false) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = if (strong) MaterialTheme.typography.titleMedium else MaterialTheme.typography.bodyMedium)
        Text(
            formatAuditMoney(value, currency),
            style = if (strong) MaterialTheme.typography.titleMedium else MaterialTheme.typography.bodyMedium,
            fontWeight = if (strong) FontWeight.Bold else FontWeight.Medium,
        )
    }
}

@Composable
private fun StatementAuditLineCard(
    line: StatementAuditLineResult,
    currency: String,
    onClick: () -> Unit,
) {
    val statusColor = auditStatusColor(line.status)
    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().heightIn(min = 72.dp).semantics {
            role = Role.Button
            contentDescription = "Statement row ${line.row.rowNumber}, ${auditStatusLabel(line.status)}, ${line.row.description}"
        },
        color = MaterialTheme.colorScheme.surface,
        shape = MaterialTheme.shapes.large,
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(line.row.description, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(
                        "Row ${line.row.rowNumber} · ${auditDateTime(line.row.occurredAt)} · ${lineKindLabel(line.kind)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    formatAuditMoney(line.row.amountMinor, currency),
                    fontWeight = FontWeight.Bold,
                    color = if (line.row.direction == StatementAmountDirection.CREDIT) MaterialTheme.colorScheme.secondary
                    else MaterialTheme.colorScheme.onSurface,
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        when (line.status) {
                            StatementAuditLineStatus.MATCHED -> Icons.Rounded.CheckCircle
                            StatementAuditLineStatus.UNMATCHED -> Icons.Rounded.ErrorOutline
                            StatementAuditLineStatus.POSSIBLE_DUPLICATE -> Icons.Rounded.WarningAmber
                        },
                        contentDescription = null,
                        tint = statusColor,
                        modifier = Modifier.size(18.dp),
                    )
                    Text(auditStatusLabel(line.status), style = MaterialTheme.typography.labelLarge, color = statusColor)
                }
                Text(
                    confidenceLabel(line.confidence, line.score),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun StatementLineDetailsDialog(
    line: StatementAuditLineResult,
    currency: String,
    onOpenTransaction: (Long) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.AutoMirrored.Rounded.Rule, contentDescription = null) },
        title = { Text("Statement row ${line.row.rowNumber}") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(line.row.description, style = MaterialTheme.typography.titleMedium)
                AuditSourceRow("Amount", formatAuditMoney(line.row.amountMinor, currency))
                AuditSourceRow("Direction", line.row.direction.name.lowercase().replaceFirstChar(Char::titlecase))
                AuditSourceRow("Date", auditDateTime(line.row.occurredAt))
                AuditSourceRow("Classification", lineKindLabel(line.kind))
                AuditSourceRow("Result", auditStatusLabel(line.status))
                AuditSourceRow("Confidence", confidenceLabel(line.confidence, line.score))
                line.row.sourceReference?.let { AuditSourceRow("Source reference", it) }
                if (line.reasons.isNotEmpty()) {
                    Text("Evidence", style = MaterialTheme.typography.labelLarge)
                    line.reasons.forEach { Text("• $it", style = MaterialTheme.typography.bodyMedium) }
                }
                line.matchedTransactionId?.let { transactionId ->
                    Button(
                        onClick = { onOpenTransaction(transactionId) },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                    ) {
                        Icon(Icons.AutoMirrored.Rounded.OpenInNew, contentDescription = null)
                        Spacer(Modifier.size(8.dp))
                        Text("Open matched transaction")
                    }
                }
                if (line.candidates.isNotEmpty()) {
                    Text("Other possible matches", style = MaterialTheme.typography.labelLarge)
                    line.candidates.forEach { candidate ->
                        Surface(
                            onClick = { onOpenTransaction(candidate.transactionId) },
                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            shape = MaterialTheme.shapes.medium,
                        ) {
                            Column(Modifier.padding(12.dp)) {
                                Text("Transaction #${candidate.transactionId} · ${candidate.score}%", fontWeight = FontWeight.SemiBold)
                                Text(candidate.reasons.joinToString(), style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
                Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = MaterialTheme.shapes.medium) {
                    Text(
                        "The auditor never changes a transaction. Review the source record before correcting or importing anything.",
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

/** Editor for values printed in the statement summary; all dates use ISO YYYY-MM-DD. */
@Composable
fun StatementMetadataEditorDialog(
    initialMetadata: StatementAuditMetadata,
    onSave: (StatementAuditMetadata) -> Unit,
    onDismiss: () -> Unit,
) {
    var fields by remember(initialMetadata) { mutableStateOf(MetadataFields.from(initialMetadata)) }
    var validationMessage by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Rounded.Edit, contentDescription = null) },
        title = { Text("Statement details") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    "Confirm values against the statement summary. PaisaLens does not contact the card issuer.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                MetadataEditorFields(fields = fields, onFieldsChange = { fields = it })
                validationMessage?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val result = fields.toMetadata(initialMetadata)
                    validationMessage = result.error
                    result.metadata?.let(onSave)
                },
                modifier = Modifier.heightIn(min = 48.dp),
            ) {
                Text("Save details")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp)) {
                Text("Cancel")
            }
        },
    )
}

/**
 * Structured manual path for unsupported PDFs. The user must explicitly submit reviewed metadata
 * and rows; this sheet does not OCR, upload, or persist the selected file.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CreditCardStatementManualEntrySheet(
    initialMetadata: StatementAuditMetadata,
    requiredManualFields: List<String>,
    isSubmitting: Boolean,
    errorMessage: String?,
    onSubmit: (StatementAuditMetadata, List<StatementAuditRow>) -> Unit,
    onDismiss: () -> Unit,
) {
    val initialFields = remember(initialMetadata) { MetadataFields.from(initialMetadata) }
    var fields by remember(initialMetadata) { mutableStateOf(initialFields) }
    val rows = remember(initialMetadata.statementId) { mutableStateListOf(ManualRowFields()) }
    var validationMessage by remember { mutableStateOf<String?>(null) }
    var showDiscardConfirmation by remember { mutableStateOf(false) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val hasDraft = fields != initialFields || rows.size > 1 || rows.any(ManualRowFields::hasContent)
    val requestDismiss: () -> Unit = {
        when {
            isSubmitting -> Unit
            hasDraft -> showDiscardConfirmation = true
            else -> onDismiss()
        }
    }

    ModalBottomSheet(
        onDismissRequest = requestDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.background,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.95f)
                .navigationBarsPadding(),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 12.dp, bottom = 14.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Manual statement audit", style = MaterialTheme.typography.headlineMedium)
                    Text(
                        "Enter only values you can verify",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = requestDismiss, modifier = Modifier.size(48.dp)) {
                    Icon(Icons.Rounded.Close, contentDescription = "Close manual statement entry")
                }
            }
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 18.dp, end = 18.dp, bottom = 32.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                item {
                    LocalTrustNotice(
                        "This fallback is entirely on-device. The original PDF is not uploaded, and entered rows remain pending until you submit them.",
                    )
                }
                if (requiredManualFields.isNotEmpty()) {
                    item {
                        Surface(
                            color = MaterialTheme.colorScheme.primaryContainer,
                            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                            shape = MaterialTheme.shapes.large,
                        ) {
                            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text("Check these statement fields", style = MaterialTheme.typography.titleMedium)
                                Text(requiredManualFields.joinToString(), style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    }
                }
                item {
                    PaisaCard(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text("Statement summary", style = MaterialTheme.typography.titleLarge)
                            MetadataEditorFields(fields = fields, onFieldsChange = { fields = it })
                        }
                    }
                }
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("Statement rows", style = MaterialTheme.typography.titleLarge)
                        Text(
                            "Use positive amounts and choose whether the statement shows a debit or credit.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                items(rows.size, key = { index -> rows[index].key }) { index ->
                    ManualStatementRowCard(
                        rowNumber = index + 1,
                        fields = rows[index],
                        canDelete = rows.size > 1,
                        onChange = { rows[index] = it },
                        onDelete = { rows.removeAt(index) },
                    )
                }
                item {
                    OutlinedButton(
                        onClick = { rows += ManualRowFields() },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                    ) {
                        Icon(Icons.Rounded.Add, contentDescription = null)
                        Spacer(Modifier.size(8.dp))
                        Text("Add statement row")
                    }
                }
                validationMessage?.let { message ->
                    item {
                        Surface(
                            color = MaterialTheme.colorScheme.errorContainer,
                            contentColor = MaterialTheme.colorScheme.onErrorContainer,
                            shape = MaterialTheme.shapes.medium,
                        ) {
                            Row(Modifier.fillMaxWidth().padding(14.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Icon(Icons.Rounded.ErrorOutline, contentDescription = null)
                                Text(message)
                            }
                        }
                    }
                }
                errorMessage?.let { message ->
                    item {
                        Surface(
                            color = MaterialTheme.colorScheme.errorContainer,
                            contentColor = MaterialTheme.colorScheme.onErrorContainer,
                            shape = MaterialTheme.shapes.medium,
                        ) {
                            Text(message, modifier = Modifier.fillMaxWidth().padding(14.dp))
                        }
                    }
                }
                item {
                    Button(
                        onClick = {
                            val metadataResult = fields.toMetadata(initialMetadata)
                            if (metadataResult.error != null) {
                                validationMessage = metadataResult.error
                                return@Button
                            }
                            val rowResults = rows.mapIndexed { index, row -> row.toAuditRow(index + 1, fields.currency) }
                            val firstError = rowResults.firstNotNullOfOrNull { it.error }
                            if (firstError != null) {
                                validationMessage = firstError
                                return@Button
                            }
                            validationMessage = null
                            onSubmit(
                                requireNotNull(metadataResult.metadata),
                                rowResults.map { requireNotNull(it.row) },
                            )
                        },
                        enabled = !isSubmitting,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
                    ) {
                        if (isSubmitting) {
                            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.size(8.dp))
                        } else {
                            Icon(Icons.AutoMirrored.Rounded.FactCheck, contentDescription = null)
                            Spacer(Modifier.size(8.dp))
                        }
                        Text(if (isSubmitting) "Building audit…" else "Build local audit")
                    }
                }
            }
        }
    }

    if (showDiscardConfirmation) {
        AlertDialog(
            onDismissRequest = { showDiscardConfirmation = false },
            title = { Text("Discard statement draft?") },
            text = { Text("Your entered statement details and rows will be lost.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDiscardConfirmation = false
                        onDismiss()
                    },
                    modifier = Modifier.heightIn(min = 48.dp),
                ) {
                    Text("Discard")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { showDiscardConfirmation = false },
                    modifier = Modifier.heightIn(min = 48.dp),
                ) {
                    Text("Keep editing")
                }
            },
        )
    }
}

private data class MetadataFields(
    val issuer: String = "",
    val accountName: String = "",
    val cardLast4: String = "",
    val statementDate: String = "",
    val periodStart: String = "",
    val periodEnd: String = "",
    val dueDate: String = "",
    val openingBalance: String = "",
    val totalDue: String = "",
    val minimumDue: String = "",
    val currency: String = "INR",
) {
    companion object {
        fun from(metadata: StatementAuditMetadata): MetadataFields = MetadataFields(
            issuer = metadata.issuer.orEmpty(),
            accountName = metadata.accountName.orEmpty(),
            cardLast4 = metadata.cardLast4.orEmpty(),
            statementDate = metadata.statementDateEpochDay?.let(::auditDate).orEmpty(),
            periodStart = metadata.periodStartEpochDay?.let(::auditDate).orEmpty(),
            periodEnd = metadata.periodEndEpochDay?.let(::auditDate).orEmpty(),
            dueDate = metadata.dueDateEpochDay?.let(::auditDate).orEmpty(),
            openingBalance = minorToEditable(metadata.openingBalanceMinor),
            totalDue = metadata.totalDueMinor?.let(::minorToEditable).orEmpty(),
            minimumDue = metadata.minimumDueMinor?.let(::minorToEditable).orEmpty(),
            currency = metadata.currency,
        )
    }

    fun toMetadata(base: StatementAuditMetadata): MetadataResult {
        val normalizedLast4 = cardLast4.filter(Char::isDigit)
        if (normalizedLast4.isNotBlank() && normalizedLast4.length != 4) {
            return MetadataResult(error = "Card last four digits must contain exactly four digits.")
        }
        val normalizedCurrency = currency.trim().uppercase(Locale.ROOT)
        if (!normalizedCurrency.matches(Regex("[A-Z]{3}"))) {
            return MetadataResult(error = "Currency must be a three-letter code such as INR.")
        }
        fun date(value: String, label: String): Pair<Long?, String?> {
            if (value.isBlank()) return null to null
            return try {
                LocalDate.parse(value.trim()).toEpochDay() to null
            } catch (_: DateTimeParseException) {
                null to "$label must use YYYY-MM-DD."
            }
        }
        val statementDateResult = date(statementDate, "Statement date")
        val periodStartResult = date(periodStart, "Period start")
        val periodEndResult = date(periodEnd, "Period end")
        val dueDateResult = date(dueDate, "Due date")
        listOf(statementDateResult, periodStartResult, periodEndResult, dueDateResult).mapNotNull { it.second }.firstOrNull()?.let {
            return MetadataResult(error = it)
        }
        val opening = parseEditableMinor(openingBalance, allowBlank = true)
            ?: return MetadataResult(error = "Opening balance must be a valid amount.")
        val total = parseOptionalMinor(totalDue) ?: return MetadataResult(error = "Total due must be a valid amount.")
        val minimum = parseOptionalMinor(minimumDue) ?: return MetadataResult(error = "Minimum due must be a valid amount.")
        val totalValue = total.second
        val minimumValue = minimum.second
        if (minimumValue != null && totalValue != null && minimumValue > totalValue) {
            return MetadataResult(error = "Minimum due cannot be greater than total due.")
        }
        val periodStartValue = periodStartResult.first
        val periodEndValue = periodEndResult.first
        if (periodStartValue != null && periodEndValue != null && periodStartValue > periodEndValue) {
            return MetadataResult(error = "Period start cannot be after period end.")
        }
        return MetadataResult(
            metadata = base.copy(
                issuer = issuer.trim().takeIf(String::isNotBlank),
                accountName = accountName.trim().takeIf(String::isNotBlank),
                cardLast4 = normalizedLast4.takeIf(String::isNotBlank),
                statementDateEpochDay = statementDateResult.first,
                periodStartEpochDay = periodStartValue,
                periodEndEpochDay = periodEndValue,
                dueDateEpochDay = dueDateResult.first,
                openingBalanceMinor = opening,
                totalDueMinor = totalValue,
                minimumDueMinor = minimumValue,
                currency = normalizedCurrency,
            ),
        )
    }
}

private data class MetadataResult(val metadata: StatementAuditMetadata? = null, val error: String? = null)

@Composable
private fun MetadataEditorFields(
    fields: MetadataFields,
    onFieldsChange: (MetadataFields) -> Unit,
) {
    OutlinedTextField(
        value = fields.issuer,
        onValueChange = { onFieldsChange(fields.copy(issuer = it.take(60))) },
        modifier = Modifier.fillMaxWidth(),
        label = { Text("Card issuer") },
        singleLine = true,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
    )
    OutlinedTextField(
        value = fields.accountName,
        onValueChange = { onFieldsChange(fields.copy(accountName = it.take(60))) },
        modifier = Modifier.fillMaxWidth(),
        label = { Text("Account or card name") },
        singleLine = true,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
    )
    OutlinedTextField(
        value = fields.cardLast4,
        onValueChange = { onFieldsChange(fields.copy(cardLast4 = it.filter(Char::isDigit).take(4))) },
        modifier = Modifier.fillMaxWidth(),
        label = { Text("Card last 4 digits") },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Next),
        supportingText = { Text("Used only to assign this statement to the correct local account.") },
    )
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        DateField("Statement date", fields.statementDate, Modifier.weight(1f)) {
            onFieldsChange(fields.copy(statementDate = it))
        }
        DateField("Due date", fields.dueDate, Modifier.weight(1f)) {
            onFieldsChange(fields.copy(dueDate = it))
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        DateField("Period start", fields.periodStart, Modifier.weight(1f)) {
            onFieldsChange(fields.copy(periodStart = it))
        }
        DateField("Period end", fields.periodEnd, Modifier.weight(1f)) {
            onFieldsChange(fields.copy(periodEnd = it))
        }
    }
    OutlinedTextField(
        value = fields.currency,
        onValueChange = { onFieldsChange(fields.copy(currency = it.filter(Char::isLetter).uppercase(Locale.ROOT).take(3))) },
        modifier = Modifier.fillMaxWidth(),
        label = { Text("Currency") },
        singleLine = true,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
        supportingText = { Text("Three-letter code, for example INR.") },
    )
    MoneyInput("Opening balance", fields.openingBalance) { onFieldsChange(fields.copy(openingBalance = it)) }
    MoneyInput("Total due", fields.totalDue) { onFieldsChange(fields.copy(totalDue = it)) }
    MoneyInput("Minimum due", fields.minimumDue) { onFieldsChange(fields.copy(minimumDue = it)) }
}

@Composable
private fun DateField(label: String, value: String, modifier: Modifier, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = { onChange(it.take(10)) },
        modifier = modifier,
        label = { Text(label) },
        placeholder = { Text("YYYY-MM-DD") },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Next),
    )
}

@Composable
private fun MoneyInput(label: String, value: String, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = { onChange(it.filter { char -> char.isDigit() || char == '.' || char == '-' }.take(18)) },
        modifier = Modifier.fillMaxWidth(),
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Next),
    )
}

private data class ManualRowFields(
    val key: Long = System.nanoTime(),
    val date: String = "",
    val description: String = "",
    val amount: String = "",
    val direction: StatementAmountDirection = StatementAmountDirection.DEBIT,
) {
    fun hasContent(): Boolean = date.isNotBlank() || description.isNotBlank() || amount.isNotBlank()

    fun toAuditRow(rowNumber: Int, currency: String): ManualRowResult {
        val parsedDate = try {
            LocalDate.parse(date.trim())
        } catch (_: DateTimeParseException) {
            return ManualRowResult(error = "Row $rowNumber date must use YYYY-MM-DD.")
        }
        if (description.isBlank()) return ManualRowResult(error = "Row $rowNumber needs a description.")
        val parsedAmount = parseEditableMinor(amount, allowBlank = false)
            ?: return ManualRowResult(error = "Row $rowNumber needs a valid positive amount.")
        if (parsedAmount <= 0) return ManualRowResult(error = "Row $rowNumber amount must be greater than zero.")
        return ManualRowResult(
            row = StatementAuditRow(
                rowNumber = rowNumber,
                occurredAt = parsedDate.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli(),
                description = description.trim(),
                amountMinor = parsedAmount,
                direction = direction,
                currency = currency.trim().uppercase(Locale.ROOT),
                sourceReference = "manual-row-$rowNumber",
            ),
        )
    }
}

private data class ManualRowResult(val row: StatementAuditRow? = null, val error: String? = null)

@Composable
private fun ManualStatementRowCard(
    rowNumber: Int,
    fields: ManualRowFields,
    canDelete: Boolean,
    onChange: (ManualRowFields) -> Unit,
    onDelete: () -> Unit,
) {
    PaisaCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Row $rowNumber", style = MaterialTheme.typography.titleMedium)
                IconButton(onClick = onDelete, enabled = canDelete, modifier = Modifier.size(48.dp)) {
                    Icon(Icons.Rounded.DeleteOutline, contentDescription = "Delete statement row $rowNumber")
                }
            }
            DateField("Transaction date", fields.date, Modifier.fillMaxWidth()) { onChange(fields.copy(date = it)) }
            OutlinedTextField(
                value = fields.description,
                onValueChange = { onChange(fields.copy(description = it.take(160))) },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Description") },
                singleLine = false,
                maxLines = 3,
            )
            MoneyInput("Amount", fields.amount) { onChange(fields.copy(amount = it)) }
            Text("Statement direction", style = MaterialTheme.typography.labelLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatementAmountDirection.entries.forEach { direction ->
                    FilterChip(
                        selected = fields.direction == direction,
                        onClick = { onChange(fields.copy(direction = direction)) },
                        modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                        label = { Text(direction.name.lowercase().replaceFirstChar(Char::titlecase)) },
                    )
                }
            }
        }
    }
}

@Composable
private fun AuditSourceRow(label: String, value: String) {
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
private fun auditStatusColor(status: StatementAuditLineStatus): Color = when (status) {
    StatementAuditLineStatus.MATCHED -> MaterialTheme.colorScheme.secondary
    StatementAuditLineStatus.UNMATCHED -> MaterialTheme.colorScheme.error
    StatementAuditLineStatus.POSSIBLE_DUPLICATE -> MaterialTheme.colorScheme.primary
}

private fun auditStatusLabel(status: StatementAuditLineStatus): String = when (status) {
    StatementAuditLineStatus.MATCHED -> "Matched"
    StatementAuditLineStatus.UNMATCHED -> "Unmatched"
    StatementAuditLineStatus.POSSIBLE_DUPLICATE -> "Possible duplicate"
}

private fun confidenceLabel(confidence: StatementAuditConfidence, score: Int): String = when (confidence) {
    StatementAuditConfidence.HIGH -> "High confidence · $score%"
    StatementAuditConfidence.MEDIUM -> "Medium confidence · $score%"
    StatementAuditConfidence.LOW -> "Low confidence · $score%"
    StatementAuditConfidence.NONE -> "No confident match"
}

private fun lineKindLabel(kind: StatementLineKind): String = when (kind) {
    StatementLineKind.PURCHASE -> "Purchase"
    StatementLineKind.FEE -> "Fee"
    StatementLineKind.INTEREST -> "Interest"
    StatementLineKind.GST -> "GST"
    StatementLineKind.REFUND -> "Refund"
    StatementLineKind.PAYMENT -> "Payment"
    StatementLineKind.OTHER_DEBIT -> "Other debit"
    StatementLineKind.OTHER_CREDIT -> "Other credit"
}

private fun auditDate(epochDay: Long): String = LocalDate.ofEpochDay(epochDay).toString()

private fun auditDateTime(epochMillis: Long): String = SimpleDateFormat(
    "d MMM yyyy, h:mm a",
    Locale.getDefault(),
).format(Date(epochMillis))

private fun formatAuditMoney(amountMinor: Long, currencyCode: String): String = runCatching {
    val format = NumberFormat.getCurrencyInstance(Locale.getDefault())
    format.currency = Currency.getInstance(currencyCode.uppercase(Locale.ROOT))
    format.format(amountMinor / 100.0)
}.getOrElse {
    if (currencyCode.equals("INR", ignoreCase = true)) formatMoney(amountMinor)
    else "${currencyCode.uppercase(Locale.ROOT)} ${"%.2f".format(Locale.ROOT, amountMinor / 100.0)}"
}

private fun formatSignedAuditMoney(amountMinor: Long, currency: String): String = when {
    amountMinor > 0 -> "+${formatAuditMoney(amountMinor, currency)}"
    amountMinor < 0 -> "-${formatAuditMoney(-amountMinor, currency)}"
    else -> formatAuditMoney(0, currency)
}

private fun minorToEditable(amountMinor: Long): String = BigDecimal(amountMinor)
    .movePointLeft(2)
    .stripTrailingZeros()
    .toPlainString()

private fun parseEditableMinor(value: String, allowBlank: Boolean): Long? {
    if (value.isBlank()) return if (allowBlank) 0L else null
    return runCatching {
        value.trim().replace(",", "").toBigDecimal()
            .multiply(BigDecimal(100))
            .setScale(0, RoundingMode.HALF_UP)
            .longValueExact()
    }.getOrNull()
}

/** Distinguishes a valid blank optional amount from an invalid value. */
private fun parseOptionalMinor(value: String): Pair<Boolean, Long?>? {
    if (value.isBlank()) return true to null
    return parseEditableMinor(value, allowBlank = false)?.let { true to it }
}
