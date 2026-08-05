package com.paisalens.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.paisalens.app.data.model.CategoryBudget
import com.paisalens.app.data.model.ExpenseCategory
import com.paisalens.app.data.model.TransactionRecord
import com.paisalens.app.data.model.ReviewStatus
import com.paisalens.app.data.model.TransactionType
import com.paisalens.app.ui.components.CategoryIcon
import com.paisalens.app.ui.components.PaisaCard
import com.paisalens.app.ui.components.formatMoney
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime

@Composable
fun BudgetsScreen(
    transactions: List<TransactionRecord>,
    budgets: List<CategoryBudget>,
    onSetBudget: (ExpenseCategory, Long) -> Unit,
) {
    val now = remember { ZonedDateTime.now() }
    val monthlyExpenses = remember(transactions, now.monthValue, now.year) {
        transactions.filter {
            val date = Instant.ofEpochMilli(it.occurredAt).atZone(ZoneId.systemDefault())
            it.type == TransactionType.EXPENSE &&
                it.reviewStatus == ReviewStatus.CONFIRMED &&
                date.monthValue == now.monthValue &&
                date.year == now.year
        }
    }
    val spentByCategory = monthlyExpenses
        .groupBy { it.category }
        .mapValues { (_, values) -> values.sumOf { it.amountMinor } }
    val budgetMap = budgets.associateBy { it.category }
    val totalBudget = budgets.sumOf { it.limitMinor }
    val totalSpent = monthlyExpenses.sumOf { it.amountMinor }
    val categories = ExpenseCategory.entries.filterNot {
        it == ExpenseCategory.INCOME || it == ExpenseCategory.TRANSFER
    }
    var editingCategory by remember { mutableStateOf<ExpenseCategory?>(null) }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 18.dp, top = 12.dp, end = 18.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Text(
                text = "Budgets",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = "Plan gently, adjust anytime",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        item {
            BudgetSummary(
                totalBudget = totalBudget,
                totalSpent = totalSpent,
            )
        }
        item {
            Text(
                text = "Category limits",
                modifier = Modifier.padding(top = 6.dp),
                style = MaterialTheme.typography.titleLarge,
            )
        }
        items(categories, key = { it.name }) { category ->
            val budget = budgetMap[category]?.limitMinor ?: 0L
            val spent = spentByCategory[category] ?: 0L
            BudgetCategoryCard(
                category = category,
                spent = spent,
                limit = budget,
                onEdit = { editingCategory = category },
            )
        }
    }

    editingCategory?.let { category ->
        BudgetEditorDialog(
            category = category,
            currentLimit = budgetMap[category]?.limitMinor ?: 0L,
            onDismiss = { editingCategory = null },
            onSave = { amount ->
                onSetBudget(category, amount)
                editingCategory = null
            },
        )
    }
}

@Composable
private fun BudgetSummary(
    totalBudget: Long,
    totalSpent: Long,
) {
    val progress = if (totalBudget > 0) totalSpent.toFloat() / totalBudget else 0f
    PaisaCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(20.dp)) {
            Text(
                text = if (totalBudget > 0) "Monthly plan" else "Start your monthly plan",
                style = MaterialTheme.typography.titleMedium,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = if (totalBudget > 0) {
                    formatMoney(totalSpent) + " of " + formatMoney(totalBudget)
                } else {
                    "Set limits for the categories you care about."
                },
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(14.dp))
            LinearProgressIndicator(
                progress = { progress.coerceIn(0f, 1f) },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(10.dp),
                color = if (progress > 1f) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.surfaceVariant,
                strokeCap = androidx.compose.ui.graphics.StrokeCap.Round,
            )
            if (totalBudget > 0) {
                Spacer(Modifier.height(9.dp))
                Text(
                    text = if (progress <= 1f) {
                        formatMoney((totalBudget - totalSpent).coerceAtLeast(0)) + " left"
                    } else {
                        formatMoney(totalSpent - totalBudget) + " over plan"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (progress > 1f) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
        }
    }
}

@Composable
private fun BudgetCategoryCard(
    category: ExpenseCategory,
    spent: Long,
    limit: Long,
    onEdit: () -> Unit,
) {
    val progress = if (limit > 0) spent.toFloat() / limit else 0f
    PaisaCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CategoryIcon(category)
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(category.label, style = MaterialTheme.typography.titleMedium)
                    Text(
                        if (limit > 0) formatMoney(spent) + " of " + formatMoney(limit)
                        else if (spent > 0) formatMoney(spent) + " spent · no limit"
                        else "No limit set",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = onEdit) {
                    Icon(
                        imageVector = if (limit > 0) Icons.Rounded.Edit else Icons.Rounded.Add,
                        contentDescription = if (limit > 0) {
                            "Edit " + category.label + " budget"
                        } else {
                            "Set " + category.label + " budget"
                        },
                    )
                }
            }
            if (limit > 0) {
                Spacer(Modifier.height(12.dp))
                LinearProgressIndicator(
                    progress = { progress.coerceIn(0f, 1f) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(7.dp),
                    color = if (progress > 1f) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.surfaceVariant,
                    strokeCap = androidx.compose.ui.graphics.StrokeCap.Round,
                )
            }
        }
    }
}

@Composable
private fun BudgetEditorDialog(
    category: ExpenseCategory,
    currentLimit: Long,
    onDismiss: () -> Unit,
    onSave: (Long) -> Unit,
) {
    var amount by remember(currentLimit) {
        mutableStateOf(if (currentLimit > 0) (currentLimit / 100.0).toString().removeSuffix(".0") else "")
    }
    val parsed = amount.toBigDecimalOrNull()
        ?.multiply(BigDecimal(100))
        ?.setScale(0, RoundingMode.HALF_UP)
        ?.toLong()

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { CategoryIcon(category) },
        title = { Text(category.label + " budget") },
        text = {
            Column {
                Text(
                    "Set a monthly limit. Use remove to clear it.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(16.dp))
                OutlinedTextField(
                    value = amount,
                    onValueChange = { value ->
                        if (value.matches(Regex("""\d{0,9}(\.\d{0,2})?"""))) amount = value
                    },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Monthly limit") },
                    prefix = { Text("₹") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true,
                    shape = MaterialTheme.shapes.medium,
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onSave(parsed ?: 0L) },
                enabled = parsed != null && parsed >= 0,
            ) {
                Text("Save budget")
            }
        },
        dismissButton = {
            Row {
                if (currentLimit > 0) {
                    TextButton(onClick = { onSave(0L) }) {
                        Text("Remove", color = MaterialTheme.colorScheme.error)
                    }
                }
                TextButton(onClick = onDismiss) {
                    Text("Cancel")
                }
            }
        },
    )
}
