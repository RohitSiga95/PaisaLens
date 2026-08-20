package com.paisalens.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Rule
import androidx.compose.material.icons.rounded.AddTask
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.MarkEmailUnread
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.paisalens.app.data.model.ExpenseCategory
import com.paisalens.app.data.model.SmsCoverageMessage
import com.paisalens.app.data.model.SmsCoverageReason
import com.paisalens.app.data.model.SmsCoverageRule
import com.paisalens.app.data.model.SmsCoverageStatus
import com.paisalens.app.data.model.TransactionSource
import com.paisalens.app.data.model.TransactionType
import com.paisalens.app.ui.components.PaisaCard
import com.paisalens.app.ui.components.formatTransactionTime

private enum class CoverageSection(val label: String) {
    REVIEW("Needs review"),
    RULES("Local rules"),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SmsCoverageCenterSheet(
    messages: List<SmsCoverageMessage>,
    rules: List<SmsCoverageRule>,
    onSaveRule: (SmsCoverageRule) -> Unit,
    onDeleteRule: (Long) -> Unit,
    onDeleteMessage: (Long) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var section by remember { mutableStateOf(CoverageSection.REVIEW) }
    var ruleDraftFor by remember { mutableStateOf<SmsCoverageMessage?>(null) }
    var editingRule by remember { mutableStateOf<SmsCoverageRule?>(null) }
    var deletingRule by remember { mutableStateOf<SmsCoverageRule?>(null) }
    val waiting = messages.filter { it.status == SmsCoverageStatus.NEEDS_REVIEW }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxWidth().navigationBarsPadding(),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("SMS Coverage Centre", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                        Text(
                            "Teach PaisaLens unsupported financial alerts without uploading any message.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    IconButton(onClick = onDismiss, modifier = Modifier.size(48.dp)) {
                        Icon(Icons.Rounded.Close, contentDescription = "Close SMS Coverage Centre")
                    }
                }
            }
            item {
                Surface(
                    color = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    shape = MaterialTheme.shapes.large,
                ) {
                    Row(
                        Modifier.fillMaxWidth().padding(16.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Rounded.MarkEmailUnread, contentDescription = null)
                        Column(Modifier.weight(1f)) {
                            Text("${waiting.size} alert${if (waiting.size == 1) "" else "s"} waiting", fontWeight = FontWeight.Bold)
                            Text("OTP and authentication messages are never offered here.", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
            item {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(CoverageSection.entries) { item ->
                        FilterChip(
                            selected = section == item,
                            onClick = { section = item },
                            modifier = Modifier.heightIn(min = 48.dp),
                            label = { Text("${item.label}${if (item == CoverageSection.REVIEW) " (${waiting.size})" else " (${rules.size})"}") },
                        )
                    }
                }
            }

            when (section) {
                CoverageSection.REVIEW -> {
                    if (waiting.isEmpty()) {
                        item {
                            CoverageEmptyCard(
                                "No unsupported alerts waiting",
                                "Run a local SMS scan after receiving a new bank format. Potential transaction alerts will appear here.",
                            )
                        }
                    } else {
                        items(waiting, key = SmsCoverageMessage::id) { message ->
                            CoverageMessageCard(
                                message = message,
                                onCreateRule = { ruleDraftFor = message },
                                onDelete = { onDeleteMessage(message.id) },
                            )
                        }
                    }
                }
                CoverageSection.RULES -> {
                    if (rules.isEmpty()) {
                        item {
                            CoverageEmptyCard(
                                "No local parser rules yet",
                                "Create a rule from a waiting alert. Future matching SMS will be imported into the review inbox.",
                            )
                        }
                    } else {
                        items(rules, key = SmsCoverageRule::id) { rule ->
                            CoverageRuleCard(
                                rule = rule,
                                onEdit = { editingRule = rule },
                                onToggle = { onSaveRule(rule.copy(enabled = !rule.enabled)) },
                                onDelete = { deletingRule = rule },
                            )
                        }
                    }
                }
            }
        }
    }

    if (ruleDraftFor != null || editingRule != null) {
        SmsCoverageRuleEditorDialog(
            message = ruleDraftFor,
            existing = editingRule,
            onSave = { rule ->
                onSaveRule(rule)
                ruleDraftFor = null
                editingRule = null
                section = CoverageSection.RULES
            },
            onDismiss = {
                ruleDraftFor = null
                editingRule = null
            },
        )
    }

    deletingRule?.let { rule ->
        AlertDialog(
            onDismissRequest = { deletingRule = null },
            title = { Text("Delete ${rule.name}?") },
            text = { Text("Future SMS will no longer use this local parser rule. Existing transactions are not changed.") },
            confirmButton = {
                Button(onClick = { onDeleteRule(rule.id); deletingRule = null }) { Text("Delete rule") }
            },
            dismissButton = { TextButton(onClick = { deletingRule = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun CoverageMessageCard(
    message: SmsCoverageMessage,
    onCreateRule: () -> Unit,
    onDelete: () -> Unit,
) {
    PaisaCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(message.sender, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text(
                        "${message.reason.label()} · ${formatTransactionTime(message.receivedAt)}",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Icon(Icons.Rounded.MarkEmailUnread, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            }
            Text(
                message.body,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 5,
                overflow = TextOverflow.Ellipsis,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onCreateRule, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) {
                    Icon(Icons.Rounded.AddTask, contentDescription = null)
                    Spacer(Modifier.size(6.dp))
                    Text("Create rule")
                }
                TextButton(onClick = onDelete, modifier = Modifier.heightIn(min = 48.dp)) { Text("Delete") }
            }
        }
    }
}

@Composable
private fun CoverageRuleCard(
    rule: SmsCoverageRule,
    onEdit: () -> Unit,
    onToggle: () -> Unit,
    onDelete: () -> Unit,
) {
    PaisaCard(Modifier.fillMaxWidth()) {
        Column {
            Surface(onClick = onEdit, color = Color.Transparent) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Icon(Icons.AutoMirrored.Rounded.Rule, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Column(Modifier.weight(1f)) {
                        Text(rule.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        Text(
                            "${rule.senderKey} · ${rule.requiredPhrases.joinToString(" + ")} → ${rule.merchantName}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Switch(checked = rule.enabled, onCheckedChange = { onToggle() })
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onDelete, modifier = Modifier.heightIn(min = 48.dp)) {
                    Icon(Icons.Rounded.DeleteOutline, contentDescription = null)
                    Spacer(Modifier.size(5.dp))
                    Text("Delete")
                }
            }
        }
    }
}

@Composable
private fun CoverageEmptyCard(title: String, body: String) {
    PaisaCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun SmsCoverageRuleEditorDialog(
    message: SmsCoverageMessage?,
    existing: SmsCoverageRule?,
    onSave: (SmsCoverageRule) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember(message, existing) { mutableStateOf(existing?.name ?: "${message?.sender.orEmpty()} alert") }
    var sender by remember(message, existing) { mutableStateOf(existing?.senderKey ?: message?.sender.orEmpty()) }
    var phrases by remember(message, existing) {
        mutableStateOf(existing?.requiredPhrases?.joinToString(", ") ?: suggestedCoveragePhrases(message?.body.orEmpty()))
    }
    var merchant by remember(message, existing) { mutableStateOf(existing?.merchantName.orEmpty()) }
    var type by remember(message, existing) { mutableStateOf(existing?.type ?: TransactionType.EXPENSE) }
    var source by remember(message, existing) { mutableStateOf(existing?.source ?: TransactionSource.BANK) }
    var category by remember(message, existing) { mutableStateOf(existing?.category ?: ExpenseCategory.OTHER) }
    val phraseList = phrases.split(',').map(String::trim).filter(String::isNotBlank)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (existing == null) "Create local SMS rule" else "Edit local SMS rule") },
        text = {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                item {
                    Text(
                        "Use literal phrases visible in this sender's alerts. The rule stays on this device and matching imports still require review.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                item { OutlinedTextField(name, { name = it.take(48) }, Modifier.fillMaxWidth(), label = { Text("Rule name") }, singleLine = true) }
                item { OutlinedTextField(sender, { sender = it.take(48) }, Modifier.fillMaxWidth(), label = { Text("Exact sender") }, singleLine = true) }
                item {
                    OutlinedTextField(
                        phrases,
                        { phrases = it.take(180) },
                        Modifier.fillMaxWidth(),
                        label = { Text("Required phrases, comma separated") },
                        supportingText = { Text("All phrases must be present. Avoid amounts and reference numbers.") },
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None),
                        minLines = 2,
                    )
                }
                item { OutlinedTextField(merchant, { merchant = it.take(64) }, Modifier.fillMaxWidth(), label = { Text("Merchant name") }, singleLine = true) }
                item { ChoiceRow("Transaction type", TransactionType.entries, type, { it.name.lowercase().replaceFirstChar(Char::titlecase) }) { type = it } }
                item { ChoiceRow("Source", TransactionSource.entries, source, { it.name.lowercase().replaceFirstChar(Char::titlecase) }) { source = it } }
                if (type == TransactionType.EXPENSE || type == TransactionType.REFUND) {
                    item {
                        ChoiceRow(
                            "Category",
                            ExpenseCategory.entries.filterNot { it == ExpenseCategory.INCOME || it == ExpenseCategory.TRANSFER },
                            category,
                            ExpenseCategory::label,
                        ) { category = it }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                enabled = name.isNotBlank() && sender.isNotBlank() && phraseList.isNotEmpty() && merchant.isNotBlank(),
                onClick = {
                    onSave(
                        SmsCoverageRule(
                            id = existing?.id ?: 0,
                            name = name,
                            senderKey = sender,
                            requiredPhrases = phraseList,
                            merchantName = merchant,
                            category = category,
                            type = type,
                            source = source,
                            enabled = existing?.enabled ?: true,
                            createdAt = existing?.createdAt ?: System.currentTimeMillis(),
                        ),
                    )
                },
            ) { Text("Save rule") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun <T> ChoiceRow(
    title: String,
    values: List<T>,
    selected: T,
    label: (T) -> String,
    onSelect: (T) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(title, style = MaterialTheme.typography.labelLarge)
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(values) { item ->
                FilterChip(
                    selected = item == selected,
                    onClick = { onSelect(item) },
                    modifier = Modifier.heightIn(min = 48.dp),
                    label = { Text(label(item)) },
                )
            }
        }
    }
}

private fun SmsCoverageReason.label(): String = when (this) {
    SmsCoverageReason.MISSING_AMOUNT -> "Amount not found"
    SmsCoverageReason.MISSING_DIRECTION -> "Debit or credit not found"
    SmsCoverageReason.UNSUPPORTED_FORMAT -> "Unsupported format"
}

private fun suggestedCoveragePhrases(body: String): String = body
    .lowercase()
    .split(Regex("[^a-z]+"))
    .filter { it.length >= 5 && it !in volatileCoverageWords }
    .distinct()
    .take(2)
    .joinToString(", ")

private val volatileCoverageWords = setOf(
    "transaction", "reference", "account", "amount", "rupees", "balance", "available",
)
