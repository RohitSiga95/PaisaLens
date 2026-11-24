package com.paisalens.app.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.paisalens.app.data.model.CustomCategory
import com.paisalens.app.data.model.DashboardWeek
import com.paisalens.app.data.model.ExpenseCategory
import com.paisalens.app.data.model.SpendingCategoryKey
import com.paisalens.app.data.model.SpendingCategoryTotal
import com.paisalens.app.data.model.TransactionRecord
import com.paisalens.app.ui.components.CategoryIcon
import com.paisalens.app.ui.components.CustomCategoryIcon
import com.paisalens.app.ui.components.MoneyText
import com.paisalens.app.ui.components.PaisaCard
import com.paisalens.app.ui.components.categoryColor
import com.paisalens.app.ui.components.customCategoryColor
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

internal fun spendingMonths(
    expenses: List<TransactionRecord>,
    currentMonth: YearMonth,
    zoneId: ZoneId,
): List<YearMonth> = (expenses.mapTo(hashSetOf(currentMonth)) {
    YearMonth.from(Instant.ofEpochMilli(it.occurredAt).atZone(zoneId))
}).filter { it <= currentMonth }.sortedDescending()

internal fun DashboardWeek.dateLabel(): String =
    "${start.format(DateTimeFormatter.ofPattern("EEE d MMM"))} – " +
        endInclusive.format(DateTimeFormatter.ofPattern("EEE d MMM uuuu"))

private const val OTHER_SEGMENTS_ID = "group:other-categories"

private data class SpendingSegment(
    val id: String,
    val title: String,
    val amountMinor: Long,
    val categoryIds: Set<String>,
    val key: SpendingCategoryKey? = null,
)

private fun spendingSegments(totals: List<SpendingCategoryTotal>): List<SpendingSegment> {
    val positive = totals.filter { it.amountMinor > 0 }
    val primary = if (positive.size > 4) positive.take(3) else positive
    return buildList {
        primary.forEach { total ->
            val id = total.key.stableId
            add(SpendingSegment(id, total.key.label, total.amountMinor, setOf(id), total.key))
        }
        if (primary.size < positive.size) {
            val remaining = positive.drop(primary.size)
            add(
                SpendingSegment(
                    OTHER_SEGMENTS_ID,
                    "Other categories",
                    remaining.sumOf(SpendingCategoryTotal::amountMinor),
                    remaining.mapTo(hashSetOf()) { it.key.stableId },
                ),
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun SpendingLens(
    categoryTotals: List<SpendingCategoryTotal>,
    customCategoriesById: Map<Long, CustomCategory>,
    expenses: List<TransactionRecord>,
    month: YearMonth,
    availableMonths: List<YearMonth>,
    today: LocalDate,
    onMonthChange: (YearMonth) -> Unit,
    onTransactionClick: (TransactionRecord) -> Unit,
    onOpenCategory: (SpendingCategoryKey) -> Unit,
    compact: Boolean = false,
) {
    val monthLabel = month.format(DateTimeFormatter.ofPattern("MMM uuuu"))
    val segments = remember(categoryTotals) { spendingSegments(categoryTotals) }
    val total = remember(categoryTotals) { categoryTotals.sumOf(SpendingCategoryTotal::amountMinor) }
    val week = remember(today) { DashboardWeek.containing(today) }
    var selectedId by rememberSaveable(monthLabel) { mutableStateOf<String?>(null) }
    val otherIds = segments.firstOrNull { it.id == OTHER_SEGMENTS_ID }?.categoryIds.orEmpty()
    val activeId = selectedId?.takeIf { id ->
        if (id == OTHER_SEGMENTS_ID) otherIds.isNotEmpty() else categoryTotals.any { it.key.stableId == id }
    }
    val toggle: (String) -> Unit = { id -> selectedId = id.takeUnless { it == activeId } }
    val shownTotals = if (activeId == OTHER_SEGMENTS_ID) {
        categoryTotals.filter { it.key.stableId in otherIds }
    } else {
        categoryTotals
    }
    val scheme = MaterialTheme.colorScheme
    val chartColors = segments.map { segment ->
        val category = segment.key
        category?.customCategoryId?.let(customCategoriesById::get)?.let { customCategoryColor(it.colorHex) }
            ?: when (category?.builtIn) {
                ExpenseCategory.FOOD -> scheme.primary
                ExpenseCategory.SHOPPING -> scheme.secondary
                ExpenseCategory.TRANSPORT -> scheme.tertiary
                ExpenseCategory.OTHER, null -> scheme.primaryContainer
                else -> categoryColor(category.builtIn)
            }
    }
    val spacing = if (compact) 8.dp else 12.dp

    PaisaCard(Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(if (compact) 14.dp else 18.dp),
            verticalArrangement = Arrangement.spacedBy(spacing),
        ) {
            if (!compact && month == YearMonth.from(today)) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Rounded.CalendarMonth, contentDescription = null, tint = scheme.primary)
                    Column(Modifier.weight(1f)) {
                        Text("Current week", style = MaterialTheme.typography.labelMedium, color = scheme.primary)
                        Text(week.dateLabel(), style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
            SpendingMonthPicker(month, availableMonths, onMonthChange)
            Column {
                Text("Total spent in $monthLabel", color = scheme.onSurfaceVariant)
                MoneyText(
                    total,
                    style = if (compact) MaterialTheme.typography.headlineMedium else MaterialTheme.typography.displaySmall,
                    color = scheme.primary,
                )
            }
            if (segments.isNotEmpty() && total > 0) {
                BoxWithConstraints(Modifier.fillMaxWidth()) {
                    val chartWidth = maxWidth
                    val labelScale = LocalDensity.current.fontScale.coerceAtLeast(1f)
                    Row(
                        Modifier.fillMaxWidth().height(54.dp).clip(RoundedCornerShape(12.dp)),
                    ) {
                        segments.forEachIndexed { index, segment ->
                            val fraction = (segment.amountMinor.toDouble() / total).toFloat()
                            val selected = activeId == segment.id || activeId in segment.categoryIds
                            val color = chartColors[index]
                            val fractionWidth = chartWidth * fraction
                            val selection = if (fractionWidth >= 48.dp) {
                                Modifier.selectable(
                                    selected = selected,
                                    onClick = { toggle(segment.id) },
                                    role = Role.Button,
                                ).semantics {
                                    contentDescription = "${segment.title}, ${shareLabel(segment.amountMinor, total)}"
                                }
                            } else {
                                Modifier
                            }
                            val foreground = if (color.luminance() > 0.179f) Color.Black else Color.White
                            Box(
                                modifier = Modifier.weight(fraction).fillMaxHeight()
                                    .background(color)
                                    .then(
                                        if (selected) Modifier.border(2.dp, foreground, RoundedCornerShape(9.dp))
                                        else Modifier,
                                    )
                                    .then(selection),
                                contentAlignment = Alignment.Center,
                            ) {
                                if (fractionWidth >= 76.dp * labelScale) {
                                    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(horizontal = 4.dp)) {
                                        if (fractionWidth >= 104.dp && labelScale <= 1.2f) {
                                            Text(
                                                segment.title, color = foreground,
                                                style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold,
                                                maxLines = 1, overflow = TextOverflow.Ellipsis,
                                            )
                                        }
                                        Text(
                                            shareLabel(segment.amountMinor, total),
                                            color = foreground,
                                            style = MaterialTheme.typography.labelLarge,
                                            fontWeight = FontWeight.Bold,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
                BoxWithConstraints(Modifier.fillMaxWidth()) {
                    val scale = LocalDensity.current.fontScale
                    val columns = when {
                        maxWidth >= 560.dp && scale < 1.4f -> 4
                        segments.size == 3 && maxWidth >= 288.dp && scale <= 1.2f -> 3
                        else -> 2
                    }
                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        maxItemsInEachRow = columns,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        segments.forEachIndexed { index, segment ->
                            val selected = activeId == segment.id || activeId in segment.categoryIds
                            Surface(
                                modifier = Modifier.weight(1f).heightIn(min = 48.dp)
                                    .selectable(selected, role = Role.Button, onClick = { toggle(segment.id) }),
                                shape = MaterialTheme.shapes.medium,
                                color = if (selected) scheme.primaryContainer else scheme.surfaceContainerLow,
                            ) {
                                Column(Modifier.padding(horizontal = 8.dp, vertical = 6.dp)) {
                                    Row(
                                        horizontalArrangement = Arrangement.spacedBy(5.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Box(Modifier.size(9.dp).background(chartColors[index], RoundedCornerShape(50)))
                                        Text(segment.title, style = MaterialTheme.typography.labelLarge)
                                    }
                                    MoneyText(segment.amountMinor, style = MaterialTheme.typography.labelLarge)
                                }
                            }
                        }
                    }
                }
            }
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalArrangement = Arrangement.Center,
            ) {
                Text(
                    "Spending breakdown",
                    modifier = Modifier.align(Alignment.CenterVertically),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                )
                if (activeId != null) {
                    TextButton(onClick = { selectedId = null }, modifier = Modifier.heightIn(min = 48.dp)) {
                        Text("Clear selection")
                    }
                }
            }
            if (total == 0L) {
                Text(
                    "No confirmed spending in $monthLabel.",
                    color = scheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            shownTotals.forEach { category ->
                key(category.key.stableId) {
                    SpendingCategoryRow(
                        category = category,
                        custom = category.key.customCategoryId?.let(customCategoriesById::get),
                        expanded = activeId == category.key.stableId,
                        totalMinor = total,
                        expenses = expenses,
                        onToggle = { toggle(category.key.stableId) },
                        onTransactionClick = onTransactionClick,
                        onOpenCategory = { onOpenCategory(category.key) },
                    )
                }
            }
        }
    }
}

@Composable
private fun SpendingMonthPicker(
    month: YearMonth,
    availableMonths: List<YearMonth>,
    onMonthChange: (YearMonth) -> Unit,
) {
    var showMonths by remember { mutableStateOf(false) }
    val current = availableMonths.firstOrNull() ?: month
    val oldest = availableMonths.lastOrNull() ?: month
    val formatter = remember { DateTimeFormatter.ofPattern("MMM uuuu") }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = { onMonthChange(month.minusMonths(1)) }, enabled = month > oldest) {
            Icon(Icons.AutoMirrored.Rounded.KeyboardArrowLeft, contentDescription = "Previous month")
        }
        Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
            TextButton(onClick = { showMonths = true }) {
                Text(
                    month.format(formatter),
                    style = MaterialTheme.typography.titleMedium,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f, fill = false),
                )
                Icon(Icons.Rounded.ExpandMore, contentDescription = "Choose month")
            }
            DropdownMenu(expanded = showMonths, onDismissRequest = { showMonths = false }) {
                (availableMonths + month).distinct().sortedDescending().forEach { option ->
                    DropdownMenuItem(
                        text = { Text(option.format(formatter)) },
                        onClick = {
                            onMonthChange(option)
                            showMonths = false
                        },
                    )
                }
            }
        }
        IconButton(onClick = { onMonthChange(month.plusMonths(1)) }, enabled = month < current) {
            Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, contentDescription = "Next month")
        }
    }
}

@Composable
private fun SpendingCategoryRow(
    category: SpendingCategoryTotal,
    custom: CustomCategory?,
    expanded: Boolean,
    totalMinor: Long,
    expenses: List<TransactionRecord>,
    onToggle: () -> Unit,
    onTransactionClick: (TransactionRecord) -> Unit,
    onOpenCategory: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val label = custom?.name ?: category.key.label
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = if (expanded) scheme.primaryContainer.copy(alpha = 0.55f) else scheme.surfaceContainerLow,
    ) {
        Column {
            Surface(
                onClick = onToggle,
                modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).semantics {
                    contentDescription = if (expanded) "Collapse $label" else "Expand $label"
                },
                color = Color.Transparent,
            ) {
                Row(
                    Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (custom != null) {
                        CustomCategoryIcon(custom, Modifier.size(36.dp), iconSize = 18)
                    } else {
                        CategoryIcon(category.key.builtIn, Modifier.size(36.dp), iconSize = 18)
                    }
                    Column(Modifier.weight(1f)) {
                        Text(label, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                        Text(
                            if (category.amountMinor > 0) "${shareLabel(category.amountMinor, totalMinor)} of spending"
                            else "No spending",
                            color = scheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    MoneyText(category.amountMinor, style = MaterialTheme.typography.labelLarge, modifier = Modifier.widthIn(max = 120.dp))
                    Icon(
                        if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
            AnimatedVisibility(expanded) {
                val latest = remember(expenses, category.key) {
                    expenses.asSequence().filter(category.key::matches).sortedByDescending { it.occurredAt }
                        .take(3).toList()
                }
                Column(Modifier.padding(horizontal = 10.dp, vertical = 4.dp)) {
                    if (latest.isEmpty()) {
                        Text("No transactions for this category this month.", modifier = Modifier.padding(vertical = 8.dp))
                    }
                    latest.forEach { expense ->
                        HorizontalDivider(color = scheme.outlineVariant)
                        Surface(onClick = { onTransactionClick(expense) }, color = Color.Transparent) {
                            Row(
                                Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(expense.merchant, style = MaterialTheme.typography.titleSmall)
                                    Text(
                                        Instant.ofEpochMilli(expense.occurredAt)
                                            .atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("d MMM uuuu")),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = scheme.onSurfaceVariant,
                                    )
                                }
                                MoneyText(expense.amountMinor, style = MaterialTheme.typography.labelLarge)
                            }
                        }
                    }
                    if (latest.isNotEmpty()) {
                        TextButton(onClick = onOpenCategory, modifier = Modifier.heightIn(min = 48.dp)) {
                            Text("View category details")
                        }
                    }
                }
            }
        }
    }
}

private fun shareLabel(amount: Long, total: Long): String =
    if (total <= 0) "0%" else {
        val percentage = amount.toDouble() * 100.0 / total
        if (percentage > 0.0 && percentage < 0.5) "<1%" else "${percentage.roundToInt()}%"
    }
