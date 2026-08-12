package com.paisalens.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ReceiptLong
import androidx.compose.material.icons.automirrored.rounded.CallSplit
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Groups
import androidx.compose.material.icons.rounded.Payments
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.paisalens.app.data.model.ExpenseSplit
import com.paisalens.app.data.model.TransactionRecord
import com.paisalens.app.data.model.TransactionType
import com.paisalens.app.data.model.buildExpenseSplitSummary
import com.paisalens.app.data.model.expenseSplitStatus
import com.paisalens.app.ui.components.PaisaCard
import com.paisalens.app.ui.components.formatMoney
import com.paisalens.app.ui.components.formatTransactionTime
import java.util.Locale

private enum class SharedExpenseFilter(val label: String) {
    ALL("All"),
    OUTSTANDING("Outstanding"),
    SETTLED("Settled"),
}

/**
 * A private, on-device overview of every expense that has participant shares.
 * Updating a participant emits the full replacement row so persistence stays outside UI.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SharedExpensesCenterSheet(
    transactions: List<TransactionRecord>,
    splits: List<ExpenseSplit>,
    onOpenSplitEditor: (TransactionRecord) -> Unit,
    onUpdateSplit: (ExpenseSplit) -> Unit,
    onDeleteSplit: (ExpenseSplit) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.94f)
                .navigationBarsPadding(),
        ) {
            SharedFinanceSheetHeader(
                title = "Shared expenses",
                subtitle = "Split costs and track money owed back",
                onDismiss = onDismiss,
            )
            SharedExpensesCenterContent(
                transactions = transactions,
                splits = splits,
                onOpenSplitEditor = onOpenSplitEditor,
                onUpdateSplit = onUpdateSplit,
                onDeleteSplit = onDeleteSplit,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
internal fun SharedExpensesCenterContent(
    transactions: List<TransactionRecord>,
    splits: List<ExpenseSplit>,
    onOpenSplitEditor: (TransactionRecord) -> Unit,
    onUpdateSplit: (ExpenseSplit) -> Unit,
    onDeleteSplit: (ExpenseSplit) -> Unit,
    modifier: Modifier = Modifier,
) {
    var filter by remember { mutableStateOf(SharedExpenseFilter.ALL) }
    var recordingFor by remember { mutableStateOf<ExpenseSplit?>(null) }
    var deleting by remember { mutableStateOf<ExpenseSplit?>(null) }
    val transactionById = remember(transactions) { transactions.associateBy(TransactionRecord::id) }
    val grouped = remember(splits, transactionById) {
        splits.groupBy(ExpenseSplit::transactionId)
            .mapNotNull { (transactionId, participantRows) ->
                transactionById[transactionId]?.let { transaction ->
                    SharedExpenseGroup(transaction, participantRows.sortedBy { it.participantName.lowercase(Locale.ROOT) })
                }
            }
            .sortedByDescending { it.transaction.occurredAt }
    }
    val visibleGroups = remember(grouped, filter) {
        grouped.filter { group ->
            val summary = buildExpenseSplitSummary(group.transaction, group.splits)
            when (filter) {
                SharedExpenseFilter.ALL -> true
                SharedExpenseFilter.OUTSTANDING -> summary.outstandingMinor > 0
                SharedExpenseFilter.SETTLED -> summary.outstandingMinor == 0L
            }
        }
    }
    val eligibleRecentExpenses = remember(transactions, splits) {
        val usedIds = splits.mapTo(mutableSetOf(), ExpenseSplit::transactionId)
        transactions.asSequence()
            .filter { it.type == TransactionType.EXPENSE && it.id !in usedIds }
            .sortedByDescending(TransactionRecord::occurredAt)
            .take(5)
            .toList()
    }
    val totalOutstanding = remember(grouped) {
        grouped.sumOf { buildExpenseSplitSummary(it.transaction, it.splits).outstandingMinor }
    }
    val totalReimbursed = remember(grouped) {
        grouped.sumOf { buildExpenseSplitSummary(it.transaction, it.splits).reimbursedMinor }
    }
    val linkedIncomingIds = remember(splits) {
        splits.mapNotNullTo(mutableSetOf(), ExpenseSplit::linkedIncomingTransactionId)
    }

    LazyColumn(
        modifier = modifier,
        contentPadding = PaddingValues(start = 18.dp, end = 18.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            SharedFinancePrivateNotice(
                "Participant names, shares, and reimbursements stay in PaisaLens' private storage on this phone.",
            )
        }
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                SharedExpenseMetric(
                    label = "Still owed",
                    value = formatMoney(totalOutstanding),
                    modifier = Modifier.weight(1f),
                )
                SharedExpenseMetric(
                    label = "Received",
                    value = formatMoney(totalReimbursed),
                    modifier = Modifier.weight(1f),
                )
            }
        }
        item {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(SharedExpenseFilter.entries) { option ->
                    FilterChip(
                        selected = filter == option,
                        onClick = { filter = option },
                        label = { Text(option.label) },
                        modifier = Modifier.heightIn(min = 48.dp),
                    )
                }
            }
        }
        if (visibleGroups.isEmpty()) {
            item {
                PaisaCard(Modifier.fillMaxWidth()) {
                    SharedFinanceEmptyState(
                        icon = Icons.Rounded.Groups,
                        title = if (grouped.isEmpty()) "No shared expenses yet" else "Nothing in this view",
                        detail = if (grouped.isEmpty()) {
                            "Choose a recent expense below and add each person's share."
                        } else {
                            "Try a different filter to see your shared expenses."
                        },
                    )
                }
            }
        } else {
            items(visibleGroups, key = { it.transaction.id }) { group ->
                SharedExpenseCard(
                    group = group,
                    onEdit = { onOpenSplitEditor(group.transaction) },
                    onRecord = { recordingFor = it },
                    onUnlink = { split ->
                        onUpdateSplit(
                            split.copy(
                                linkedIncomingTransactionId = null,
                                updatedAt = System.currentTimeMillis(),
                            ),
                        )
                    },
                    onDelete = { deleting = it },
                )
            }
        }
        if (eligibleRecentExpenses.isNotEmpty()) {
            item {
                Text(
                    text = "Recent expenses to split",
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
            items(eligibleRecentExpenses, key = { "candidate:${it.id}" }) { transaction ->
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onOpenSplitEditor(transaction) },
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                    shape = MaterialTheme.shapes.large,
                ) {
                    Row(
                        modifier = Modifier.padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Surface(
                            color = MaterialTheme.colorScheme.secondaryContainer,
                            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                            shape = MaterialTheme.shapes.medium,
                        ) {
                            Icon(
                                Icons.AutoMirrored.Rounded.ReceiptLong,
                                contentDescription = null,
                                modifier = Modifier.padding(10.dp),
                            )
                        }
                        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                            Text(
                                transaction.merchant,
                                style = MaterialTheme.typography.titleMedium,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                formatTransactionTime(transaction.occurredAt),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Column(horizontalAlignment = Alignment.End) {
                            Text(formatMoney(transaction.amountMinor), fontWeight = FontWeight.SemiBold)
                            Text(
                                "Split",
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                }
            }
        }
    }

    recordingFor?.let { split ->
        val outstanding = (split.shareMinor - split.reimbursedMinor).coerceAtLeast(0)
        val eligibleIncoming = remember(split, transactions, linkedIncomingIds) {
            if (split.reimbursedMinor > 0) {
                emptyList()
            } else {
                transactions.asSequence()
                    .filter { transaction ->
                        transaction.id !in linkedIncomingIds &&
                            transaction.id != 0L &&
                            transaction.type in setOf(TransactionType.INCOME, TransactionType.REFUND) &&
                            transaction.amountMinor in 1..outstanding
                    }
                    .sortedWith(
                        compareByDescending<TransactionRecord> { it.amountMinor == outstanding }
                            .thenByDescending(TransactionRecord::occurredAt),
                    )
                    .take(5)
                    .toList()
            }
        }
        RecordReimbursementDialog(
            split = split,
            eligibleIncoming = eligibleIncoming,
            onDismiss = { recordingFor = null },
            onConfirm = { amountToAdd, note, linkedIncoming ->
                val newTotal = if (linkedIncoming != null) {
                    linkedIncoming.amountMinor
                } else {
                    (split.reimbursedMinor + amountToAdd).coerceAtMost(split.shareMinor)
                }
                onUpdateSplit(
                    split.copy(
                        reimbursedMinor = newTotal,
                        linkedIncomingTransactionId = linkedIncoming?.id ?: split.linkedIncomingTransactionId,
                        note = note.ifBlank { split.note },
                        status = expenseSplitStatus(split.shareMinor, newTotal),
                        updatedAt = System.currentTimeMillis(),
                    ),
                )
                recordingFor = null
            },
        )
    }
    deleting?.let { split ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Remove ${split.participantName}?") },
            text = { Text("Their share and reimbursement history for this expense will be removed.") },
            confirmButton = {
                Button(
                    onClick = {
                        onDeleteSplit(split)
                        deleting = null
                    },
                ) { Text("Remove") }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancel") } },
        )
    }
}

private data class SharedExpenseGroup(
    val transaction: TransactionRecord,
    val splits: List<ExpenseSplit>,
)

@Composable
private fun SharedExpenseMetric(label: String, value: String, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun SharedExpenseCard(
    group: SharedExpenseGroup,
    onEdit: () -> Unit,
    onRecord: (ExpenseSplit) -> Unit,
    onUnlink: (ExpenseSplit) -> Unit,
    onDelete: (ExpenseSplit) -> Unit,
) {
    val summary = remember(group) { buildExpenseSplitSummary(group.transaction, group.splits) }
    val progress = if (summary.allocatedMinor <= 0) 0f else {
        summary.reimbursedMinor.toFloat() / summary.allocatedMinor
    }.coerceIn(0f, 1f)
    PaisaCard(Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    color = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    shape = MaterialTheme.shapes.medium,
                ) {
                    Icon(Icons.AutoMirrored.Rounded.CallSplit, contentDescription = null, modifier = Modifier.padding(10.dp))
                }
                Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                    Text(
                        group.transaction.merchant,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        "${formatTransactionTime(group.transaction.occurredAt)} · ${formatMoney(group.transaction.amountMinor)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = onEdit, modifier = Modifier.size(48.dp)) {
                    Icon(Icons.Rounded.Edit, contentDescription = "Edit split for ${group.transaction.merchant}")
                }
            }
            LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth().height(8.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(
                    "${summary.settledParticipantCount}/${summary.participantCount} settled",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    if (summary.outstandingMinor > 0) "${formatMoney(summary.outstandingMinor)} owed" else "Fully settled",
                    style = MaterialTheme.typography.labelLarge,
                    color = if (summary.outstandingMinor > 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.tertiary,
                )
            }
            HorizontalDivider()
            group.splits.forEachIndexed { index, split ->
                if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f))
                ParticipantSettlementRow(
                    split = split,
                    onRecord = { onRecord(split) },
                    onUnlink = { onUnlink(split) },
                    onDelete = { onDelete(split) },
                )
            }
            if (summary.unallocatedMinor > 0) {
                Text(
                    "Your share / not allocated: ${formatMoney(summary.unallocatedMinor)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun ParticipantSettlementRow(
    split: ExpenseSplit,
    onRecord: () -> Unit,
    onUnlink: () -> Unit,
    onDelete: () -> Unit,
) {
    val outstanding = (split.shareMinor - split.reimbursedMinor).coerceAtLeast(0)
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Rounded.Person, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        Column(Modifier.weight(1f).padding(horizontal = 10.dp, vertical = 8.dp)) {
            Text(split.participantName, style = MaterialTheme.typography.titleSmall)
            Text(
                if (outstanding == 0L) {
                    "Settled ${formatMoney(split.shareMinor)}"
                } else {
                    "${formatMoney(split.reimbursedMinor)} received · ${formatMoney(outstanding)} left"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            split.note?.takeIf(String::isNotBlank)?.let {
                Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (split.linkedIncomingTransactionId != null) {
                Text(
                    "Incoming transaction linked",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
        if (split.linkedIncomingTransactionId != null) {
            TextButton(onClick = onUnlink, modifier = Modifier.heightIn(min = 48.dp)) {
                Text("Unlink")
            }
        }
        if (outstanding > 0) {
            TextButton(onClick = onRecord, modifier = Modifier.heightIn(min = 48.dp)) {
                Text("Record")
            }
        } else {
            Icon(Icons.Rounded.CheckCircle, contentDescription = "Settled", tint = MaterialTheme.colorScheme.tertiary)
        }
        IconButton(onClick = onDelete, modifier = Modifier.size(48.dp)) {
            Icon(Icons.Rounded.DeleteOutline, contentDescription = "Remove ${split.participantName}")
        }
    }
}

private data class SplitEditorRow(
    val localKey: String,
    val original: ExpenseSplit?,
    val name: String,
    val share: String,
    val reimbursed: String,
    val note: String,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TransactionSplitEditorSheet(
    transaction: TransactionRecord,
    existingSplits: List<ExpenseSplit>,
    onSave: (upserts: List<ExpenseSplit>, deletedIds: Set<Long>) -> Unit,
    onDismiss: () -> Unit,
    isSaving: Boolean = false,
) {
    val currentIsSaving = rememberUpdatedState(isSaving)
    val sheetState = rememberModalBottomSheetState(
        skipPartiallyExpanded = true,
        confirmValueChange = { !currentIsSaving.value },
    )
    val initialRows = remember(existingSplits, transaction.id) {
        existingSplits.filter { it.transactionId == transaction.id }.map { split ->
            SplitEditorRow(
                localKey = "saved:${split.id}",
                original = split,
                name = split.participantName,
                share = split.shareMinor.sharedFinanceInput(),
                reimbursed = split.reimbursedMinor.sharedFinanceInput(),
                note = split.note.orEmpty(),
            )
        }
    }
    var rows by remember(initialRows) {
        mutableStateOf(
            initialRows.ifEmpty {
                listOf(newSplitEditorRow())
            },
        )
    }
    var submitted by remember { mutableStateOf(false) }
    val parsedRows = rows.map { row -> row.share.sharedFinanceMinorOrNull() to row.reimbursed.sharedFinanceMinorOrNull() }
    val allocated = parsedRows.sumOf { it.first ?: 0 }
    val duplicateNames = rows.map { it.name.trim().lowercase(Locale.ROOT) }.filter(String::isNotBlank)
        .groupingBy { it }.eachCount().any { it.value > 1 }
    val formError = when {
        transaction.type != TransactionType.EXPENSE -> "Only deducted expense transactions can be split."
        rows.isEmpty() -> "Add at least one participant."
        rows.any { it.name.isBlank() } -> "Enter a name for every participant."
        duplicateNames -> "Each participant name must be unique for this expense."
        parsedRows.any { it.first == null || it.first == 0L } -> "Enter a share greater than zero for every participant."
        parsedRows.any { it.second == null } -> "Enter a valid reimbursed amount, or use zero."
        parsedRows.any { (share, reimbursed) -> share != null && reimbursed != null && reimbursed > share } ->
            "A reimbursed amount cannot be greater than that participant's share."
        allocated > transaction.amountMinor ->
            "Participant shares exceed the ${formatMoney(transaction.amountMinor)} expense by ${formatMoney(allocated - transaction.amountMinor)}."
        else -> null
    }
    val remaining = (transaction.amountMinor - allocated).coerceAtLeast(0)

    ModalBottomSheet(
        onDismissRequest = { if (!isSaving) onDismiss() },
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.94f)
                .navigationBarsPadding(),
        ) {
            SharedFinanceSheetHeader(
                title = "Split expense",
                subtitle = transaction.merchant,
                onDismiss = { if (!isSaving) onDismiss() },
            )
            LazyColumn(
                modifier = Modifier.fillMaxWidth(),
                contentPadding = PaddingValues(start = 18.dp, end = 18.dp, bottom = 32.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                item {
                    PaisaCard(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text("Expense total", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(formatMoney(transaction.amountMinor), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                            Text(
                                "${formatTransactionTime(transaction.occurredAt)}${transaction.accountName?.let { " · $it" }.orEmpty()}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                item {
                    SharedFinancePrivateNotice(
                        "Add only the portion other people owe you. Any amount left unallocated remains your share.",
                    )
                }
                items(rows, key = SplitEditorRow::localKey) { row ->
                    val index = rows.indexOfFirst { it.localKey == row.localKey }
                    ParticipantEditorCard(
                        row = row,
                        onChange = { changed -> rows = rows.toMutableList().also { it[index] = changed } },
                        onRemove = { rows = rows.filterNot { it.localKey == row.localKey } },
                    )
                }
                item {
                    OutlinedButton(
                        onClick = { rows = rows + newSplitEditorRow() },
                        enabled = !isSaving,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                    ) {
                        Icon(Icons.Rounded.Add, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("Add participant")
                    }
                }
                item {
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                        shape = MaterialTheme.shapes.large,
                    ) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                            SplitAllocationLine("Participant shares", formatMoney(allocated))
                            SplitAllocationLine("Your share / unallocated", formatMoney(remaining))
                            HorizontalDivider()
                            SplitAllocationLine("Expense total", formatMoney(transaction.amountMinor), emphasize = true)
                        }
                    }
                }
                if (submitted && formError != null) {
                    item {
                        Text(
                            text = formError,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Assertive },
                        )
                    }
                }
                item {
                    Button(
                        onClick = {
                            submitted = true
                            if (formError == null) {
                                val now = System.currentTimeMillis()
                                val upserts = rows.mapIndexed { index, row ->
                                    val original = row.original
                                    val share = parsedRows[index].first!!
                                    val reimbursed = parsedRows[index].second!!
                                    ExpenseSplit(
                                        id = original?.id ?: 0,
                                        transactionId = transaction.id,
                                        participantName = row.name.trim(),
                                        shareMinor = share,
                                        reimbursedMinor = reimbursed,
                                        linkedIncomingTransactionId = original?.linkedIncomingTransactionId,
                                        note = row.note.trim().takeIf(String::isNotBlank),
                                        status = expenseSplitStatus(share, reimbursed),
                                        createdAt = original?.createdAt ?: now,
                                        updatedAt = now,
                                    )
                                }
                                val keptIds = upserts.mapTo(mutableSetOf(), ExpenseSplit::id)
                                val deletedIds = initialRows.mapNotNull { it.original?.id }.filterNot { it in keptIds }.toSet()
                                onSave(upserts, deletedIds)
                            }
                        },
                        enabled = !isSaving,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                    ) {
                        Text(if (isSaving) "Saving…" else "Save split")
                    }
                }
            }
        }
    }
}

@Composable
private fun ParticipantEditorCard(
    row: SplitEditorRow,
    onChange: (SplitEditorRow) -> Unit,
    onRemove: () -> Unit,
) {
    PaisaCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Participant", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                IconButton(onClick = onRemove, modifier = Modifier.size(48.dp)) {
                    Icon(Icons.Rounded.DeleteOutline, contentDescription = "Remove participant")
                }
            }
            OutlinedTextField(
                value = row.name,
                onValueChange = { onChange(row.copy(name = it.take(48))) },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Name") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
            )
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = row.share,
                    onValueChange = { onChange(row.copy(share = it.sharedFinanceAmountInput())) },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Share") },
                    prefix = { Text("₹") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Next),
                )
                OutlinedTextField(
                    value = row.reimbursed,
                    onValueChange = { onChange(row.copy(reimbursed = it.sharedFinanceAmountInput())) },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Received") },
                    prefix = { Text("₹") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Next),
                )
            }
            Text(
                "Received can be updated later when this person pays you back.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(
                value = row.note,
                onValueChange = { onChange(row.copy(note = it.take(120))) },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Note (optional)") },
                minLines = 2,
                maxLines = 3,
            )
        }
    }
}

@Composable
private fun SplitAllocationLine(label: String, value: String, emphasize: Boolean = false) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(
            label,
            style = if (emphasize) MaterialTheme.typography.titleSmall else MaterialTheme.typography.bodyMedium,
            color = if (emphasize) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(value, fontWeight = if (emphasize) FontWeight.Bold else FontWeight.Medium)
    }
}

@Composable
private fun RecordReimbursementDialog(
    split: ExpenseSplit,
    eligibleIncoming: List<TransactionRecord>,
    onDismiss: () -> Unit,
    onConfirm: (amountToAdd: Long, note: String, linkedIncoming: TransactionRecord?) -> Unit,
) {
    val outstanding = (split.shareMinor - split.reimbursedMinor).coerceAtLeast(0)
    var amount by remember(split.id, split.updatedAt) { mutableStateOf(outstanding.sharedFinanceInput()) }
    var note by remember(split.id, split.updatedAt) { mutableStateOf("") }
    var linkedIncoming by remember(split.id, split.updatedAt) { mutableStateOf<TransactionRecord?>(null) }
    var submitted by remember { mutableStateOf(false) }
    val parsedAmount = amount.sharedFinanceMinorOrNull()
    val error = when {
        parsedAmount == null || parsedAmount <= 0 -> "Enter the amount received."
        parsedAmount > outstanding -> "This is more than the ${formatMoney(outstanding)} still owed."
        else -> null
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Rounded.Payments, contentDescription = null) },
        title = { Text("Record reimbursement") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    "${split.participantName} still owes ${formatMoney(outstanding)}.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = amount,
                    onValueChange = { amount = it.sharedFinanceAmountInput() },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Amount received") },
                    prefix = { Text("₹") },
                    singleLine = true,
                    isError = submitted && error != null,
                    supportingText = if (submitted && error != null) {
                        { Text(error) }
                    } else {
                        {
                            Text(
                                if (linkedIncoming == null) "This adds to the amount already recorded."
                                else "Amount is taken from the linked incoming transaction.",
                            )
                        }
                    },
                    readOnly = linkedIncoming != null,
                    trailingIcon = if (linkedIncoming != null) {
                        {
                            IconButton(
                                onClick = {
                                    linkedIncoming = null
                                    amount = outstanding.sharedFinanceInput()
                                },
                            ) {
                                Icon(Icons.Rounded.DeleteOutline, contentDescription = "Remove incoming transaction link")
                            }
                        }
                    } else {
                        null
                    },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Next),
                )
                if (eligibleIncoming.isNotEmpty()) {
                    Text("Link an incoming transaction", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "Linking prevents this reimbursement from counting as separate income in spend analytics.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    eligibleIncoming.forEach { incoming ->
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    linkedIncoming = incoming
                                    amount = incoming.amountMinor.sharedFinanceInput()
                                },
                            shape = MaterialTheme.shapes.medium,
                            color = if (linkedIncoming?.id == incoming.id) {
                                MaterialTheme.colorScheme.primaryContainer
                            } else {
                                MaterialTheme.colorScheme.surfaceContainerHigh
                            },
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(incoming.merchant, style = MaterialTheme.typography.titleSmall)
                                    Text(
                                        formatTransactionTime(incoming.occurredAt),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                Text(formatMoney(incoming.amountMinor), fontWeight = FontWeight.SemiBold)
                                if (incoming.amountMinor == outstanding) {
                                    Spacer(Modifier.width(6.dp))
                                    Text("Exact", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                                }
                            }
                        }
                    }
                } else if (split.reimbursedMinor > 0) {
                    Text(
                        "Incoming transaction linking is available before a partial reimbursement is recorded.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                OutlinedTextField(
                    value = note,
                    onValueChange = { note = it.take(120) },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Note (optional)") },
                    minLines = 2,
                    maxLines = 3,
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    submitted = true
                    if (error == null) onConfirm(parsedAmount!!, note.trim(), linkedIncoming)
                },
            ) { Text("Record") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

private fun newSplitEditorRow(): SplitEditorRow = SplitEditorRow(
    localKey = "new:${System.nanoTime()}",
    original = null,
    name = "",
    share = "",
    reimbursed = "0",
    note = "",
)
