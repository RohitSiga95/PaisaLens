package com.paisalens.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Savings
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.paisalens.app.data.model.AdvancedBudgetPlan
import com.paisalens.app.data.model.BudgetCadence
import com.paisalens.app.data.model.BudgetHealth
import com.paisalens.app.data.model.BudgetPeriodAnchor
import com.paisalens.app.data.model.BudgetPeriodResult
import com.paisalens.app.data.model.BudgetRolloverMode
import com.paisalens.app.data.model.CategoryBudget
import com.paisalens.app.data.model.CustomCategory
import com.paisalens.app.data.model.ExpenseCategory
import com.paisalens.app.data.model.TransactionRecord
import com.paisalens.app.data.model.evaluateBudgetPlan
import com.paisalens.app.ui.components.MoneyText
import com.paisalens.app.ui.components.PaisaCard
import com.paisalens.app.ui.components.formatMoney
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import java.time.Month
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.time.format.TextStyle
import java.util.Locale

private data class BudgetCategoryChoice(
    val builtIn: ExpenseCategory? = null,
    val customId: Long? = null,
    val label: String,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AdvancedBudgetingScreen(
    transactions: List<TransactionRecord>,
    plans: List<AdvancedBudgetPlan>,
    customCategories: List<CustomCategory>,
    legacyBudgets: List<CategoryBudget>,
    onSavePlan: (AdvancedBudgetPlan) -> Unit,
    onDeletePlan: (Long) -> Unit,
    onSetLegacyBudget: (ExpenseCategory, Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val today = remember { LocalDate.now() }
    val zoneId = ZoneId.systemDefault()
    val results = remember(plans, transactions, today) {
        plans.mapNotNull { plan -> evaluateBudgetPlan(plan, transactions, today, zoneId)?.let { plan to it } }
    }
    var editing by remember { mutableStateOf<AdvancedBudgetPlan?>(null) }
    var adding by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<AdvancedBudgetPlan?>(null) }
    var showQuickLimits by remember { mutableStateOf(false) }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 18.dp, top = 8.dp, end = 18.dp, bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            BudgetingV2Summary(results.map { it.second }.filterNot { it.health == BudgetHealth.ENDED })
        }
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Flexible budget plans", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text(
                        "Monthly, payday, annual, or one-off envelopes with optional rollover.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Button(onClick = { adding = true }, modifier = Modifier.heightIn(min = 48.dp)) {
                    Icon(Icons.Rounded.Add, contentDescription = null)
                    Spacer(Modifier.size(6.dp))
                    Text("Add")
                }
            }
        }
        if (plans.isEmpty()) {
            item {
                PaisaCard(Modifier.fillMaxWidth()) {
                    Column(
                        Modifier.padding(20.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Icon(Icons.Rounded.Savings, contentDescription = null, modifier = Modifier.size(34.dp), tint = MaterialTheme.colorScheme.primary)
                        Text("Build a budget around real life", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        Text(
                            "Anchor a monthly plan to payday, carry unused money forward, plan a year, or create a dated event budget.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        } else {
            items(plans, key = AdvancedBudgetPlan::id) { plan ->
                AdvancedBudgetCard(
                    plan = plan,
                    result = results.firstOrNull { it.first.id == plan.id }?.second,
                    customCategory = plan.customCategoryId?.let { id -> customCategories.firstOrNull { it.id == id } },
                    onEdit = { editing = plan },
                    onDelete = { deleting = plan },
                )
            }
        }
        item {
            OutlinedButton(
                onClick = { showQuickLimits = true },
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
            ) { Text("Open quick monthly category limits") }
        }
    }

    if (adding || editing != null) {
        AdvancedBudgetEditorSheet(
            existing = editing,
            customCategories = customCategories,
            onSave = {
                onSavePlan(it)
                adding = false
                editing = null
            },
            onDismiss = { adding = false; editing = null },
        )
    }

    deleting?.let { plan ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Delete ${plan.name}?") },
            text = { Text("This removes the plan and its calculated rollover history. Transactions are not changed.") },
            confirmButton = {
                Button(onClick = { onDeletePlan(plan.id); deleting = null }) { Text("Delete plan") }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancel") } },
        )
    }

    if (showQuickLimits) {
        val quickSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(
            onDismissRequest = { showQuickLimits = false },
            sheetState = quickSheetState,
            containerColor = MaterialTheme.colorScheme.surface,
        ) {
            BudgetsScreen(
                transactions = transactions,
                budgets = legacyBudgets,
                onSetBudget = onSetLegacyBudget,
                modifier = Modifier.fillMaxSize().navigationBarsPadding(),
                showHeader = true,
            )
        }
    }
}

@Composable
private fun BudgetingV2Summary(results: List<BudgetPeriodResult>) {
    val available = results.sumOf { it.availableMinor }
    val spent = results.sumOf { it.actualMinor }
    val remaining = available - spent
    PaisaCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(if (results.isEmpty()) "Budgeting 2.0" else "Current envelopes", style = MaterialTheme.typography.titleMedium)
            Text(
                if (results.isEmpty()) "Plan months, pay cycles, years, and special events" else "${formatMoney(spent)} of ${formatMoney(available)}",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
            )
            LinearProgressIndicator(
                progress = { if (available > 0) (spent.toFloat() / available).coerceIn(0f, 1f) else 0f },
                modifier = Modifier.fillMaxWidth().height(9.dp),
                color = if (remaining < 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.surfaceVariant,
            )
            if (results.isNotEmpty()) {
                Text(
                    if (remaining >= 0) "${formatMoney(remaining)} remains across active plans" else "${formatMoney(-remaining)} over across active plans",
                    color = if (remaining < 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun AdvancedBudgetCard(
    plan: AdvancedBudgetPlan,
    result: BudgetPeriodResult?,
    customCategory: CustomCategory?,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    val healthColor = when (result?.health) {
        BudgetHealth.EXCEEDED -> MaterialTheme.colorScheme.error
        BudgetHealth.WARNING -> MaterialTheme.colorScheme.tertiary
        BudgetHealth.ON_TRACK -> MaterialTheme.colorScheme.primary
        BudgetHealth.ENDED, BudgetHealth.NOT_STARTED, null -> MaterialTheme.colorScheme.outline
    }
    PaisaCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(plan.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text(
                        listOfNotNull(
                            customCategory?.name ?: plan.category?.label ?: "All spending",
                            plan.cadence.label(),
                            plan.rolloverMode.takeUnless { it == BudgetRolloverMode.NONE }?.label(),
                        ).joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = onEdit, modifier = Modifier.size(48.dp)) { Icon(Icons.Rounded.Edit, contentDescription = "Edit ${plan.name}") }
                IconButton(onClick = onDelete, modifier = Modifier.size(48.dp)) { Icon(Icons.Rounded.DeleteOutline, contentDescription = "Delete ${plan.name}") }
            }
            if (result == null) {
                Text(if (plan.enabled) "This plan is outside its active dates." else "Paused", color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                Row(verticalAlignment = Alignment.Bottom) {
                    Column(Modifier.weight(1f)) {
                        Text("${formatMoney(result.actualMinor)} spent", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                        Text(
                            "${result.range.start.format(shortDate)} – ${result.range.endInclusive.format(shortDate)}",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    MoneyText(result.remainingMinor, style = MaterialTheme.typography.titleMedium, color = healthColor)
                }
                LinearProgressIndicator(
                    progress = { ((result.utilizationBasisPoints ?: 0) / 10_000f).coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth().height(8.dp),
                    color = healthColor,
                    trackColor = MaterialTheme.colorScheme.surfaceVariant,
                )
                Text(
                    "${result.health.label()} · ${formatMoney(result.plannedToDateMinor)} planned by today" +
                        if (result.rolloverInMinor != 0L) " · ${formatMoney(result.rolloverInMinor)} rolled in" else "",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AdvancedBudgetEditorSheet(
    existing: AdvancedBudgetPlan?,
    customCategories: List<CustomCategory>,
    onSave: (AdvancedBudgetPlan) -> Unit,
    onDismiss: () -> Unit,
) {
    val today = LocalDate.now()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val categoryChoices = remember(customCategories) {
        listOf(BudgetCategoryChoice(label = "All spending")) +
            ExpenseCategory.entries.filterNot { it == ExpenseCategory.INCOME || it == ExpenseCategory.TRANSFER }
                .map { BudgetCategoryChoice(builtIn = it, label = it.label) } +
            customCategories.map { BudgetCategoryChoice(customId = it.id, label = it.name) }
    }
    var name by remember(existing) { mutableStateOf(existing?.name.orEmpty()) }
    var amount by remember(existing) { mutableStateOf(existing?.allocationMinor?.toMajorInput().orEmpty()) }
    var selectedCategory by remember(existing, categoryChoices) {
        mutableStateOf(
            categoryChoices.firstOrNull {
                if (existing?.customCategoryId != null) it.customId == existing.customCategoryId
                else it.customId == null && it.builtIn == existing?.category
            } ?: categoryChoices.first(),
        )
    }
    var cadence by remember(existing) { mutableStateOf(existing?.cadence ?: BudgetCadence.MONTHLY) }
    var anchor by remember(existing) { mutableStateOf(existing?.periodAnchor ?: BudgetPeriodAnchor.CALENDAR_MONTH) }
    var payday by remember(existing) { mutableStateOf((existing?.paydayDay ?: 1).toString()) }
    var annualMonth by remember(existing) { mutableStateOf((existing?.annualStartMonth ?: 1).toString()) }
    var irregularStart by remember(existing) { mutableStateOf(existing?.irregularStartEpochDay?.let { LocalDate.ofEpochDay(it).toString() }.orEmpty()) }
    var irregularEnd by remember(existing) { mutableStateOf(existing?.irregularEndEpochDay?.let { LocalDate.ofEpochDay(it).toString() }.orEmpty()) }
    var rollover by remember(existing) { mutableStateOf(existing?.rolloverMode ?: BudgetRolloverMode.NONE) }
    var threshold by remember(existing) { mutableStateOf(existing?.warningThresholdBasisPoints ?: 8_000) }
    var startingRollover by remember(existing) { mutableStateOf(existing?.startingRolloverMinor?.toMajorInput().orEmpty()) }
    var enabled by remember(existing) { mutableStateOf(existing?.enabled ?: true) }
    val parsedAmount = amount.majorToMinorOrNull()
    val parsedStartingRollover = startingRollover.majorToMinorOrNull() ?: 0L
    val parsedStart = irregularStart.isoDateOrNull()
    val parsedEnd = irregularEnd.isoDateOrNull()
    val isValid = name.isNotBlank() && parsedAmount != null && parsedAmount > 0 &&
        (cadence != BudgetCadence.IRREGULAR || (parsedStart != null && parsedEnd != null && !parsedEnd.isBefore(parsedStart)))

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxWidth().navigationBarsPadding(),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(if (existing == null) "New budget plan" else "Edit budget plan", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                        Text("Flexible periods, warnings, and rollover", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    IconButton(onClick = onDismiss, modifier = Modifier.size(48.dp)) { Icon(Icons.Rounded.Close, contentDescription = "Close budget editor") }
                }
            }
            item { OutlinedTextField(name, { name = it.take(64) }, Modifier.fillMaxWidth(), label = { Text("Plan name") }, singleLine = true) }
            item {
                OutlinedTextField(
                    amount,
                    { if (it.matches(moneyInput)) amount = it },
                    Modifier.fillMaxWidth(),
                    label = { Text("Allocation") },
                    prefix = { Text("₹") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true,
                )
            }
            item { BudgetChoiceRow("What does it cover?", categoryChoices, selectedCategory, BudgetCategoryChoice::label) { selectedCategory = it } }
            item { BudgetChoiceRow("Cadence", BudgetCadence.entries, cadence, BudgetCadence::label) { cadence = it } }
            if (cadence == BudgetCadence.MONTHLY) {
                item { BudgetChoiceRow("Month starts", BudgetPeriodAnchor.entries, anchor, BudgetPeriodAnchor::label) { anchor = it } }
                if (anchor == BudgetPeriodAnchor.PAYDAY) {
                    item { OutlinedTextField(payday, { if (it.matches(Regex("\\d{0,2}"))) payday = it }, Modifier.fillMaxWidth(), label = { Text("Payday (1–31)") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true) }
                }
            }
            if (cadence == BudgetCadence.ANNUAL) {
                item {
                    BudgetChoiceRow("Year starts", Month.entries, Month.of(annualMonth.toIntOrNull()?.coerceIn(1, 12) ?: 1), { it.getDisplayName(TextStyle.SHORT, Locale.getDefault()) }) {
                        annualMonth = it.value.toString()
                    }
                }
            }
            if (cadence == BudgetCadence.IRREGULAR) {
                item { OutlinedTextField(irregularStart, { irregularStart = it.take(10) }, Modifier.fillMaxWidth(), label = { Text("Start date (YYYY-MM-DD)") }, singleLine = true) }
                item { OutlinedTextField(irregularEnd, { irregularEnd = it.take(10) }, Modifier.fillMaxWidth(), label = { Text("End date (YYYY-MM-DD)") }, singleLine = true) }
            }
            item { BudgetChoiceRow("Rollover", BudgetRolloverMode.entries, rollover, BudgetRolloverMode::label) { rollover = it } }
            if (rollover != BudgetRolloverMode.NONE) {
                item {
                    OutlinedTextField(
                        startingRollover,
                        { if (it.matches(signedMoneyInput)) startingRollover = it },
                        Modifier.fillMaxWidth(),
                        label = { Text("Starting rollover (optional)") },
                        prefix = { Text("₹") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
                        singleLine = true,
                    )
                }
            }
            item { BudgetChoiceRow("Warn me at", listOf(7_000, 8_000, 9_000, 10_000), threshold, { "${it / 100}%" }) { threshold = it } }
            item {
                Surface(onClick = { enabled = !enabled }, color = Color.Transparent, modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp)) {
                    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Plan active", style = MaterialTheme.typography.titleMedium)
                            Text("Paused plans keep their history but stop evaluating.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Switch(checked = enabled, onCheckedChange = { enabled = it })
                    }
                }
            }
            item {
                Button(
                    enabled = isValid,
                    onClick = {
                        onSave(
                            AdvancedBudgetPlan(
                                id = existing?.id ?: 0,
                                name = name.trim(),
                                category = selectedCategory.builtIn,
                                customCategoryId = selectedCategory.customId,
                                allocationMinor = requireNotNull(parsedAmount),
                                cadence = cadence,
                                periodAnchor = anchor,
                                paydayDay = payday.toIntOrNull()?.coerceIn(1, 31) ?: 1,
                                annualStartMonth = annualMonth.toIntOrNull()?.coerceIn(1, 12) ?: 1,
                                irregularStartEpochDay = parsedStart?.toEpochDay(),
                                irregularEndEpochDay = parsedEnd?.toEpochDay(),
                                rolloverMode = rollover,
                                warningThresholdBasisPoints = threshold,
                                startingRolloverMinor = parsedStartingRollover,
                                effectiveFromEpochDay = existing?.effectiveFromEpochDay ?: today.toEpochDay(),
                                enabled = enabled,
                                createdAt = existing?.createdAt ?: System.currentTimeMillis(),
                                updatedAt = System.currentTimeMillis(),
                            ),
                        )
                    },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
                ) { Text("Save budget plan") }
            }
        }
    }
}

@Composable
private fun <T> BudgetChoiceRow(title: String, values: List<T>, selected: T, label: (T) -> String, onSelect: (T) -> Unit) {
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

private fun BudgetCadence.label(): String = when (this) {
    BudgetCadence.MONTHLY -> "Monthly"
    BudgetCadence.ANNUAL -> "Annual"
    BudgetCadence.IRREGULAR -> "Dated event"
}

private fun BudgetPeriodAnchor.label(): String = when (this) {
    BudgetPeriodAnchor.CALENDAR_MONTH -> "Calendar month"
    BudgetPeriodAnchor.PAYDAY -> "Payday"
}

private fun BudgetRolloverMode.label(): String = when (this) {
    BudgetRolloverMode.NONE -> "No rollover"
    BudgetRolloverMode.POSITIVE_ONLY -> "Unused money"
    BudgetRolloverMode.FULL_BALANCE -> "Full envelope"
}

private fun BudgetHealth.label(): String = name.lowercase().replace('_', ' ').replaceFirstChar(Char::titlecase)

private fun Long.toMajorInput(): String = BigDecimal(this).movePointLeft(2).stripTrailingZeros().toPlainString()

private fun String.majorToMinorOrNull(): Long? = runCatching {
    if (isBlank() || this == "-") null else BigDecimal(this)
        .multiply(BigDecimal(100))
        .setScale(0, RoundingMode.HALF_UP)
        .longValueExact()
}.getOrNull()

private fun String.isoDateOrNull(): LocalDate? = try {
    if (isBlank()) null else LocalDate.parse(this)
} catch (_: DateTimeParseException) {
    null
}

private val moneyInput = Regex("\\d{0,12}(?:\\.\\d{0,2})?")
private val signedMoneyInput = Regex("-?\\d{0,12}(?:\\.\\d{0,2})?")
private val shortDate = DateTimeFormatter.ofPattern("d MMM")
