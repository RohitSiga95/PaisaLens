package com.paisalens.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AccountBalance
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Event
import androidx.compose.material.icons.rounded.Flag
import androidx.compose.material.icons.rounded.Payments
import androidx.compose.material.icons.rounded.Savings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.paisalens.app.data.model.AccountProfile
import com.paisalens.app.data.model.ContributionFrequency
import com.paisalens.app.data.model.SavingsContribution
import com.paisalens.app.data.model.SavingsGoal
import com.paisalens.app.data.model.SavingsGoalKind
import com.paisalens.app.data.model.calculateSavingsGoalProgress
import com.paisalens.app.ui.components.PaisaCard
import com.paisalens.app.ui.components.customCategoryColor
import com.paisalens.app.ui.components.formatMoney
import java.time.LocalDate
import java.time.Instant
import java.time.ZoneId

private enum class SavingsGoalFilter(val label: String) {
    ACTIVE("Active"),
    COMPLETED("Completed"),
    ALL("All"),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SavingsGoalsCenterSheet(
    goals: List<SavingsGoal>,
    contributions: List<SavingsContribution>,
    accounts: List<AccountProfile>,
    onAddGoal: () -> Unit,
    onEditGoal: (SavingsGoal) -> Unit,
    onDeleteGoal: (SavingsGoal) -> Unit,
    onContribute: (SavingsGoal) -> Unit,
    onSaveContribution: (SavingsContribution) -> Unit = {},
    onDeleteContribution: (SavingsContribution) -> Unit = {},
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
                title = "Savings goals",
                subtitle = "Goals and planned sinking funds",
                onDismiss = onDismiss,
            )
            SavingsGoalsCenterContent(
                goals = goals,
                contributions = contributions,
                accounts = accounts,
                onAddGoal = onAddGoal,
                onEditGoal = onEditGoal,
                onDeleteGoal = onDeleteGoal,
                onContribute = onContribute,
                onSaveContribution = onSaveContribution,
                onDeleteContribution = onDeleteContribution,
            )
        }
    }
}

@Composable
internal fun SavingsGoalsCenterContent(
    goals: List<SavingsGoal>,
    contributions: List<SavingsContribution>,
    accounts: List<AccountProfile>,
    onAddGoal: () -> Unit,
    onEditGoal: (SavingsGoal) -> Unit,
    onDeleteGoal: (SavingsGoal) -> Unit,
    onContribute: (SavingsGoal) -> Unit,
    onSaveContribution: (SavingsContribution) -> Unit = {},
    onDeleteContribution: (SavingsContribution) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    var filter by remember { mutableStateOf(SavingsGoalFilter.ACTIVE) }
    var deleting by remember { mutableStateOf<SavingsGoal?>(null) }
    var deletingContribution by remember { mutableStateOf<SavingsContribution?>(null) }
    var correctingContribution by remember { mutableStateOf<Pair<SavingsGoal, SavingsContribution>?>(null) }
    val accountNames = remember(accounts) { accounts.associate { it.id to it.name } }
    val progressByGoal = remember(goals, contributions) {
        goals.associate { it.id to calculateSavingsGoalProgress(it, contributions) }
    }
    val visibleGoals = remember(goals, progressByGoal, filter) {
        goals.filter { goal ->
            val complete = progressByGoal[goal.id]?.isComplete == true
            when (filter) {
                SavingsGoalFilter.ACTIVE -> goal.isActive && !complete
                SavingsGoalFilter.COMPLETED -> complete
                SavingsGoalFilter.ALL -> true
            }
        }.sortedWith(
            compareBy<SavingsGoal> { progressByGoal[it.id]?.isComplete == true }
                .thenBy { it.targetDateEpochDay ?: Long.MAX_VALUE }
                .thenBy { it.name.lowercase() },
        )
    }
    val totalTarget = goals.filter(SavingsGoal::isActive).sumOf(SavingsGoal::targetMinor)
    val totalSaved = goals.filter(SavingsGoal::isActive).sumOf { progressByGoal[it.id]?.currentSavedMinor ?: 0 }

    LazyColumn(
        modifier = modifier,
        contentPadding = PaddingValues(start = 18.dp, end = 18.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            SharedFinancePrivateNotice(
                "Goals are planning records only. PaisaLens never moves money or contacts your bank.",
            )
        }
        item {
            PaisaCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Column(Modifier.weight(1f)) {
                            Text("Saved across goals", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(formatMoney(totalSaved), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                        }
                        Icon(Icons.Rounded.Savings, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(32.dp))
                    }
                    val overallProgress = if (totalTarget <= 0) 0f else (totalSaved.toFloat() / totalTarget).coerceIn(0f, 1f)
                    LinearProgressIndicator(progress = { overallProgress }, modifier = Modifier.fillMaxWidth().height(9.dp))
                    Text(
                        if (totalTarget > 0) "Target ${formatMoney(totalTarget)}" else "Add your first target to start planning",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        item {
            Button(
                onClick = onAddGoal,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
            ) {
                Icon(Icons.Rounded.Add, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Add goal or sinking fund")
            }
        }
        item {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(SavingsGoalFilter.entries) { option ->
                    FilterChip(
                        selected = filter == option,
                        onClick = { filter = option },
                        label = { Text(option.label) },
                        modifier = Modifier.heightIn(min = 48.dp),
                    )
                }
            }
        }
        if (visibleGoals.isEmpty()) {
            item {
                PaisaCard(Modifier.fillMaxWidth()) {
                    SharedFinanceEmptyState(
                        icon = Icons.Rounded.Flag,
                        title = if (goals.isEmpty()) "No savings goals yet" else "No goals in this view",
                        detail = if (goals.isEmpty()) {
                            "Plan a purchase, emergency fund, holiday, or annual bill without linking a bank."
                        } else {
                            "Choose another filter to see your other goals."
                        },
                    )
                }
            }
        } else {
            items(visibleGoals, key = SavingsGoal::id) { goal ->
                SavingsGoalCard(
                    goal = goal,
                    contributions = contributions.filter { it.goalId == goal.id },
                    accountName = goal.linkedAccountId?.let(accountNames::get),
                    onContribute = { onContribute(goal) },
                    onEdit = { onEditGoal(goal) },
                    onDelete = { deleting = goal },
                    onCorrectContribution = { correctingContribution = goal to it },
                    onDeleteContribution = { deletingContribution = it },
                )
            }
        }
    }

    deleting?.let { goal ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            icon = { Icon(Icons.Rounded.DeleteOutline, contentDescription = null) },
            title = { Text("Delete ${goal.name}?") },
            text = { Text("The goal and its contribution history will be permanently removed. No bank balance will be affected.") },
            confirmButton = {
                Button(
                    onClick = {
                        onDeleteGoal(goal)
                        deleting = null
                    },
                ) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancel") } },
        )
    }

    deletingContribution?.let { contribution ->
        val goalName = goals.firstOrNull { it.id == contribution.goalId }?.name ?: "this goal"
        AlertDialog(
            onDismissRequest = { deletingContribution = null },
            icon = { Icon(Icons.Rounded.DeleteOutline, contentDescription = null) },
            title = { Text("Remove this contribution?") },
            text = {
                Text(
                    "Remove ${formatMoney(contribution.amountMinor)} from $goalName? " +
                        "Use the pencil action instead if you only need to correct the amount, date, or note. " +
                        "Any linked expense transaction stays unchanged.",
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        onDeleteContribution(contribution)
                        deletingContribution = null
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError,
                    ),
                ) { Text("Remove entry") }
            },
            dismissButton = {
                TextButton(onClick = { deletingContribution = null }) { Text("Keep entry") }
            },
        )
    }

    correctingContribution?.let { (goal, contribution) ->
        SavingsContributionDialog(
            goal = goal,
            existing = contribution,
            onSave = {
                onSaveContribution(it)
                correctingContribution = null
            },
            onDismiss = { correctingContribution = null },
        )
    }
}

@Composable
private fun SavingsGoalCard(
    goal: SavingsGoal,
    contributions: List<SavingsContribution>,
    accountName: String?,
    onContribute: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onCorrectContribution: (SavingsContribution) -> Unit,
    onDeleteContribution: (SavingsContribution) -> Unit,
) {
    val progress = remember(goal, contributions) { calculateSavingsGoalProgress(goal, contributions) }
    val accent = customCategoryColor(goal.colorHex)
    PaisaCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(11.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier.size(48.dp).clip(CircleShape).background(accent.copy(alpha = 0.18f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        if (goal.kind == SavingsGoalKind.SINKING_FUND) Icons.Rounded.Payments else Icons.Rounded.Savings,
                        contentDescription = null,
                        tint = accent,
                    )
                }
                Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                    Text(goal.name, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(
                        goal.kind.label(),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = onEdit, modifier = Modifier.size(48.dp)) {
                    Icon(Icons.Rounded.Edit, contentDescription = "Edit ${goal.name}")
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(formatMoney(progress.currentSavedMinor), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text(
                    "of ${formatMoney(goal.targetMinor)}",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.align(Alignment.Bottom),
                )
            }
            LinearProgressIndicator(
                progress = { progress.progressBasisPoints / 10_000f },
                modifier = Modifier.fillMaxWidth().height(9.dp),
                color = accent,
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(
                    "${progress.progressBasisPoints / 100}% funded",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    if (progress.isComplete) "Goal reached" else "${formatMoney(progress.remainingMinor)} to go",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            goal.targetDateEpochDay?.let { epochDay ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.Event, contentDescription = null, modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.width(7.dp))
                    Text(
                        buildString {
                            append("Target ")
                            append(LocalDate.ofEpochDay(epochDay).sharedFinanceDateLabel())
                            progress.requiredMonthlyMinor?.takeIf {
                                !progress.isComplete && goal.contributionFrequency == ContributionFrequency.MONTHLY
                            }?.let {
                                append(" · ")
                                append(formatMoney(it))
                                append("/month suggested")
                            }
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            accountName?.let {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.AccountBalance, contentDescription = null, modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.width(7.dp))
                    Text("Reference account: $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            goal.notes?.takeIf(String::isNotBlank)?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = onContribute,
                    enabled = goal.isActive && !progress.isComplete,
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                ) {
                    Icon(Icons.Rounded.Add, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text("Add savings")
                }
                IconButton(onClick = onDelete, modifier = Modifier.size(48.dp)) {
                    Icon(Icons.Rounded.DeleteOutline, contentDescription = "Delete ${goal.name}")
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Text(
                "Contribution history (${contributions.size})",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            if (contributions.isEmpty()) {
                Text(
                    "No contributions recorded yet.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                contributions.sortedByDescending(SavingsContribution::contributedAt).forEachIndexed { index, contribution ->
                    ContributionHistoryRow(
                        contribution = contribution,
                        onCorrect = { onCorrectContribution(contribution) },
                        onDelete = { onDeleteContribution(contribution) },
                    )
                    if (index != contributions.lastIndex) {
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.72f))
                    }
                }
            }
        }
    }
}

@Composable
private fun ContributionHistoryRow(
    contribution: SavingsContribution,
    onCorrect: () -> Unit,
    onDelete: () -> Unit,
) {
    val date = remember(contribution.contributedAt) {
        Instant.ofEpochMilli(contribution.contributedAt)
            .atZone(ZoneId.systemDefault())
            .toLocalDate()
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                formatMoney(contribution.amountMinor),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                date.sharedFinanceDateLabel(),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            contribution.note?.takeIf(String::isNotBlank)?.let { note ->
                Text(
                    note,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (contribution.linkedTransactionId != null) {
                Text(
                    "Linked transaction retained if this entry is corrected",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            IconButton(
                onClick = onCorrect,
                modifier = Modifier.size(48.dp),
            ) {
                Icon(
                    Icons.Rounded.Edit,
                    contentDescription = "Correct ${formatMoney(contribution.amountMinor)} contribution from ${date.sharedFinanceDateLabel()}",
                )
            }
            IconButton(
                onClick = onDelete,
                modifier = Modifier.size(48.dp),
            ) {
                Icon(
                    Icons.Rounded.DeleteOutline,
                    contentDescription = "Remove ${formatMoney(contribution.amountMinor)} contribution from ${date.sharedFinanceDateLabel()}",
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SavingsGoalEditorSheet(
    existing: SavingsGoal?,
    accounts: List<AccountProfile>,
    onSave: (SavingsGoal) -> Unit,
    onDismiss: () -> Unit,
    isSaving: Boolean = false,
) {
    val currentIsSaving = rememberUpdatedState(isSaving)
    val sheetState = rememberModalBottomSheetState(
        skipPartiallyExpanded = true,
        confirmValueChange = { !currentIsSaving.value },
    )
    var name by remember(existing) { mutableStateOf(existing?.name.orEmpty()) }
    var target by remember(existing) { mutableStateOf(existing?.targetMinor?.sharedFinanceInput().orEmpty()) }
    var startingSaved by remember(existing) { mutableStateOf(existing?.startingSavedMinor?.sharedFinanceInput() ?: "0") }
    var targetDate by remember(existing) {
        mutableStateOf(existing?.targetDateEpochDay?.let { LocalDate.ofEpochDay(it).toString() }.orEmpty())
    }
    var linkedAccountId by remember(existing) { mutableStateOf(existing?.linkedAccountId) }
    var kind by remember(existing) { mutableStateOf(existing?.kind ?: SavingsGoalKind.SAVINGS_GOAL) }
    var frequency by remember(existing) { mutableStateOf(existing?.contributionFrequency ?: ContributionFrequency.MONTHLY) }
    var notes by remember(existing) { mutableStateOf(existing?.notes.orEmpty()) }
    var colorHex by remember(existing) { mutableStateOf(existing?.colorHex ?: SAVINGS_GOAL_COLORS.first()) }
    var active by remember(existing) { mutableStateOf(existing?.isActive ?: true) }
    var submitted by remember { mutableStateOf(false) }
    val hasChanges = existing == null ||
        name.trim() != existing.name ||
        target.sharedFinanceMinorOrNull() != existing.targetMinor ||
        startingSaved.sharedFinanceMinorOrNull() != existing.startingSavedMinor ||
        targetDate.trim() != existing.targetDateEpochDay?.let { LocalDate.ofEpochDay(it).toString() }.orEmpty() ||
        linkedAccountId != existing.linkedAccountId || kind != existing.kind || frequency != existing.contributionFrequency ||
        notes.trim() != existing.notes.orEmpty() || colorHex != existing.colorHex || active != existing.isActive
    val parsedTarget = target.sharedFinanceMinorOrNull()
    val parsedStarting = startingSaved.sharedFinanceMinorOrNull()
    val parsedDate = targetDate.trim().takeIf(String::isNotBlank)?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
    val formError = when {
        name.isBlank() -> "Enter a name for this goal."
        parsedTarget == null || parsedTarget <= 0 -> "Enter a target amount greater than zero."
        parsedStarting == null -> "Enter a valid amount already saved, or use zero."
        targetDate.isNotBlank() && parsedDate == null -> "Use a valid target date in YYYY-MM-DD format."
        else -> null
    }

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
                title = if (existing == null) "New savings plan" else "Edit savings plan",
                subtitle = "Set a target you can revisit anytime",
                onDismiss = { if (!isSaving) onDismiss() },
            )
            LazyColumn(
                contentPadding = PaddingValues(start = 18.dp, end = 18.dp, bottom = 32.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                item {
                    SharedFinancePrivateNotice("This is a private planning tool. It does not transfer or reserve money in your account.")
                }
                item {
                    Text("Plan type", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(8.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        SavingsGoalKind.entries.forEach { option ->
                            FilterChip(
                                selected = kind == option,
                                onClick = { kind = option },
                                label = { Text(option.label()) },
                                modifier = Modifier.heightIn(min = 48.dp).weight(1f),
                            )
                        }
                    }
                    Text(
                        if (kind == SavingsGoalKind.SINKING_FUND) {
                            "Use a sinking fund for predictable future costs such as insurance, repairs, or annual fees."
                        } else {
                            "Use a goal for an emergency fund, holiday, purchase, or personal milestone."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
                item {
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it.take(64) },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Name") },
                        singleLine = true,
                        isError = submitted && name.isBlank(),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                    )
                }
                item {
                    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        OutlinedTextField(
                            value = target,
                            onValueChange = { target = it.sharedFinanceAmountInput() },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("Target") },
                            prefix = { Text("₹") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Next),
                        )
                        OutlinedTextField(
                            value = startingSaved,
                            onValueChange = { startingSaved = it.sharedFinanceAmountInput() },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("Already saved") },
                            prefix = { Text("₹") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Next),
                        )
                    }
                }
                item {
                    OutlinedTextField(
                        value = targetDate,
                        onValueChange = { targetDate = it.take(10) },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Target date (optional)") },
                        placeholder = { Text("YYYY-MM-DD") },
                        singleLine = true,
                        isError = submitted && targetDate.isNotBlank() && parsedDate == null,
                        supportingText = { Text("A date lets PaisaLens estimate a suggested monthly contribution.") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text, imeAction = ImeAction.Next),
                    )
                }
                item {
                    Text("Contribution rhythm", style = MaterialTheme.typography.titleMedium)
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(ContributionFrequency.entries) { option ->
                            FilterChip(
                                selected = frequency == option,
                                onClick = { frequency = option },
                                label = { Text(option.label()) },
                                modifier = Modifier.heightIn(min = 48.dp),
                            )
                        }
                    }
                }
                item {
                    Text("Reference account (optional)", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(8.dp))
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        item {
                            FilterChip(
                                selected = linkedAccountId == null,
                                onClick = { linkedAccountId = null },
                                label = { Text("None") },
                                modifier = Modifier.heightIn(min = 48.dp),
                            )
                        }
                        items(accounts, key = AccountProfile::id) { account ->
                            FilterChip(
                                selected = linkedAccountId == account.id,
                                onClick = { linkedAccountId = account.id },
                                label = { Text(account.name) },
                                modifier = Modifier.heightIn(min = 48.dp),
                            )
                        }
                    }
                    Text(
                        "This labels where you keep the savings; PaisaLens does not change the account balance.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
                item {
                    Text("Goal color", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(10.dp))
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        items(SAVINGS_GOAL_COLORS) { hex ->
                            val selectedColor = colorHex.equals(hex, ignoreCase = true)
                            val color = customCategoryColor(hex)
                            Surface(
                                onClick = { colorHex = hex },
                                modifier = Modifier
                                    .size(52.dp)
                                    .semantics {
                                        role = Role.RadioButton
                                        selected = selectedColor
                                        contentDescription = "Goal color ${SAVINGS_GOAL_COLORS.indexOf(hex) + 1}"
                                    },
                                shape = CircleShape,
                                color = if (selectedColor) MaterialTheme.colorScheme.surfaceContainerHighest else Color.Transparent,
                            ) {
                                Box(Modifier.padding(8.dp).clip(CircleShape).background(color), contentAlignment = Alignment.Center) {
                                    if (selectedColor) {
                                        Surface(
                                            color = MaterialTheme.colorScheme.surface,
                                            contentColor = MaterialTheme.colorScheme.onSurface,
                                            shape = CircleShape,
                                        ) {
                                            Icon(
                                                Icons.Rounded.CheckCircle,
                                                contentDescription = null,
                                                modifier = Modifier.padding(2.dp).size(18.dp),
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                item {
                    OutlinedTextField(
                        value = notes,
                        onValueChange = { notes = it.take(240) },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Notes (optional)") },
                        minLines = 2,
                        maxLines = 4,
                    )
                }
                if (existing != null) {
                    item {
                        FilterChip(
                            selected = active,
                            onClick = { active = !active },
                            label = { Text(if (active) "Active" else "Archived") },
                            leadingIcon = {
                                Icon(
                                    if (active) Icons.Rounded.CheckCircle else Icons.Rounded.Flag,
                                    contentDescription = null,
                                )
                            },
                            modifier = Modifier.heightIn(min = 48.dp),
                        )
                    }
                }
                if (submitted && formError != null) {
                    item { Text(formError, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
                }
                item {
                    Button(
                        onClick = {
                            submitted = true
                            if (formError == null) {
                                val now = System.currentTimeMillis()
                                onSave(
                                    SavingsGoal(
                                        id = existing?.id ?: 0,
                                        name = name.trim(),
                                        targetMinor = parsedTarget!!,
                                        startingSavedMinor = parsedStarting!!,
                                        targetDateEpochDay = parsedDate?.toEpochDay(),
                                        linkedAccountId = linkedAccountId,
                                        kind = kind,
                                        contributionFrequency = frequency,
                                        notes = notes.trim().takeIf(String::isNotBlank),
                                        colorHex = colorHex,
                                        isActive = active,
                                        createdAt = existing?.createdAt ?: now,
                                        updatedAt = now,
                                    ),
                                )
                            }
                        },
                        enabled = !isSaving,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                    ) {
                        Text(
                            if (isSaving) "Saving…"
                            else if (existing == null) "Create savings plan"
                            else "Save changes",
                        )
                    }
                }
                if (hasChanges) {
                    item {
                        Text(
                            "Unsaved changes are discarded if you swipe this sheet away.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

@Composable
internal fun SavingsContributionDialog(
    goal: SavingsGoal,
    existing: SavingsContribution? = null,
    onSave: (SavingsContribution) -> Unit,
    onDismiss: () -> Unit,
    isSaving: Boolean = false,
) {
    val initialDate = remember(existing?.contributedAt) {
        existing?.let {
            Instant.ofEpochMilli(it.contributedAt).atZone(ZoneId.systemDefault()).toLocalDate().toString()
        } ?: LocalDate.now().toString()
    }
    var amount by remember(goal.id, existing?.id) {
        mutableStateOf(existing?.amountMinor?.sharedFinanceInput().orEmpty())
    }
    var date by remember(goal.id, existing?.id) { mutableStateOf(initialDate) }
    var note by remember(goal.id, existing?.id) { mutableStateOf(existing?.note.orEmpty()) }
    var submitted by remember { mutableStateOf(false) }
    val parsedAmount = amount.sharedFinanceMinorOrNull()
    val parsedDate = runCatching { LocalDate.parse(date) }.getOrNull()
    val error = when {
        parsedAmount == null || parsedAmount <= 0 -> "Enter an amount greater than zero."
        parsedDate == null -> "Use a valid date in YYYY-MM-DD format."
        parsedDate.isAfter(LocalDate.now()) -> "A contribution date cannot be in the future."
        else -> null
    }
    val hasChanges = if (existing == null) {
        amount.isNotBlank() || date != initialDate || note.isNotBlank()
    } else {
        parsedAmount != existing.amountMinor || date != initialDate || note.trim() != existing.note.orEmpty()
    }
    AlertDialog(
        onDismissRequest = { if (!isSaving) onDismiss() },
        icon = { Icon(Icons.Rounded.Savings, contentDescription = null) },
        title = { Text(if (existing == null) "Add to ${goal.name}" else "Correct contribution") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    if (existing == null) {
                        "Record money you have actually set aside. This does not initiate a transfer."
                    } else {
                        "Update this tracking entry only. Any linked expense transaction stays unchanged."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = amount,
                    onValueChange = { amount = it.sharedFinanceAmountInput() },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Amount saved") },
                    prefix = { Text("₹") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Next),
                )
                OutlinedTextField(
                    value = date,
                    onValueChange = { date = it.take(10) },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Contribution date") },
                    placeholder = { Text("YYYY-MM-DD") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text, imeAction = ImeAction.Next),
                )
                OutlinedTextField(
                    value = note,
                    onValueChange = { note = it.take(160) },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Note (optional)") },
                    minLines = 2,
                    maxLines = 3,
                )
                if (submitted && error != null) {
                    Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
                if (hasChanges) {
                    Text(
                        "Unsaved changes are discarded if you close this dialog.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    submitted = true
                    if (error == null) {
                        val instant = parsedDate!!.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
                        onSave(
                            SavingsContribution(
                                id = existing?.id ?: 0,
                                goalId = goal.id,
                                amountMinor = parsedAmount!!,
                                contributedAt = instant,
                                note = note.trim().takeIf(String::isNotBlank),
                                linkedTransactionId = existing?.linkedTransactionId,
                            ),
                        )
                    }
                },
                enabled = !isSaving,
            ) {
                Text(
                    if (isSaving) "Saving…"
                    else if (existing == null) "Add savings"
                    else "Save correction",
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !isSaving) { Text("Cancel") }
        },
    )
}

private fun SavingsGoalKind.label(): String = when (this) {
    SavingsGoalKind.SAVINGS_GOAL -> "Savings goal"
    SavingsGoalKind.SINKING_FUND -> "Sinking fund"
}

private fun ContributionFrequency.label(): String = when (this) {
    ContributionFrequency.WEEKLY -> "Weekly"
    ContributionFrequency.MONTHLY -> "Monthly"
    ContributionFrequency.ONE_TIME -> "One-time"
}

private val SAVINGS_GOAL_COLORS = listOf(
    "#21D19F",
    "#4F7DFF",
    "#8B6EF6",
    "#E66A8C",
    "#E38A23",
    "#00A6A6",
    "#3268A8",
    "#70823A",
)
