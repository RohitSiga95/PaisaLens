package com.paisalens.app.ui.screens

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.paisalens.app.data.model.CategoryBudget
import com.paisalens.app.data.model.ExpenseCategory
import com.paisalens.app.data.model.TransactionRecord
import com.paisalens.app.data.model.TransactionType
import com.paisalens.app.ui.components.CategoryIcon
import com.paisalens.app.ui.components.EmptyState
import com.paisalens.app.ui.components.MoneyText
import com.paisalens.app.ui.components.PaisaCard
import com.paisalens.app.ui.components.SectionHeader
import com.paisalens.app.ui.components.SpendingDonut
import com.paisalens.app.ui.components.TransactionRow
import com.paisalens.app.ui.components.formatMoney
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime

@Composable
fun HomeScreen(
    transactions: List<TransactionRecord>,
    budgets: List<CategoryBudget>,
    isScanning: Boolean,
    hasSmsPermission: Boolean,
    onScan: () -> Unit,
    onRequestPermission: () -> Unit,
    onAdd: () -> Unit,
    onSeeAll: () -> Unit,
    onTransactionClick: (TransactionRecord) -> Unit,
) {
    val now = remember { ZonedDateTime.now() }
    val monthly = remember(transactions, now.monthValue, now.year) {
        transactions.filter {
            val date = Instant.ofEpochMilli(it.occurredAt).atZone(ZoneId.systemDefault())
            date.monthValue == now.monthValue && date.year == now.year
        }
    }
    val monthlyExpenses = monthly.filter { it.type == TransactionType.EXPENSE }
    val expenseTotal = monthlyExpenses.sumOf { it.amountMinor }
    val refunds = monthly.filter { it.type == TransactionType.REFUND }.sumOf { it.amountMinor }
    val spent = (expenseTotal - refunds).coerceAtLeast(0)
    val income = monthly.filter { it.type == TransactionType.INCOME }.sumOf { it.amountMinor }
    val budgetTotal = budgets.sumOf { it.limitMinor }
    val remaining = if (budgetTotal > 0) budgetTotal - spent else income - spent
    val categoryTotals = monthlyExpenses
        .groupBy { it.category }
        .mapValues { (_, records) -> records.sumOf { it.amountMinor } }
        .toList()
        .sortedByDescending { it.second }
    val recent = transactions.take(5)

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 18.dp, top = 14.dp, end = 18.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        item {
            HomeHeader()
        }
        item {
            BalanceHero(
                spent = spent,
                remaining = remaining,
                hasBudget = budgetTotal > 0,
                hasIncome = income > 0,
            )
        }
        item {
            QuickActions(
                isScanning = isScanning,
                hasSmsPermission = hasSmsPermission,
                onScan = onScan,
                onRequestPermission = onRequestPermission,
                onAdd = onAdd,
            )
        }
        if (transactions.isEmpty()) {
            item {
                PaisaCard {
                    EmptyState(
                        title = "Your dashboard is ready",
                        body = if (hasSmsPermission) {
                            "Scan transaction alerts or add your first expense."
                        } else {
                            "Allow SMS access or add transactions manually."
                        },
                    )
                }
            }
        } else {
            item {
                Column {
                    SectionHeader("Spending breakdown")
                    Spacer(Modifier.height(10.dp))
                    PaisaCard {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(18.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            SpendingDonut(
                                values = categoryTotals.take(5),
                                totalMinor = expenseTotal,
                            )
                            Spacer(Modifier.width(18.dp))
                            Column(
                                modifier = Modifier.weight(1f),
                                verticalArrangement = Arrangement.spacedBy(11.dp),
                            ) {
                                categoryTotals.take(4).forEach { (category, amount) ->
                                    CategoryLegend(category, amount, expenseTotal)
                                }
                                if (categoryTotals.isEmpty()) {
                                    Text(
                                        "No expenses this month",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }
                }
            }
            if (categoryTotals.isNotEmpty()) {
                item {
                    InsightCard(
                        category = categoryTotals.first().first,
                        amountMinor = categoryTotals.first().second,
                        totalMinor = expenseTotal,
                    )
                }
            }
            item {
                Column {
                    SectionHeader(
                        title = "Recent activity",
                        action = "See all",
                        onAction = onSeeAll,
                    )
                    Spacer(Modifier.height(8.dp))
                    PaisaCard(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
                            recent.forEach { transaction ->
                                TransactionRow(
                                    transaction = transaction,
                                    onClick = { onTransactionClick(transaction) },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun HomeHeader() {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column {
            Text(
                text = "PaisaLens",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = "A clear view of your money",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Surface(
            color = MaterialTheme.colorScheme.secondaryContainer,
            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
            shape = CircleShape,
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.Rounded.Lock,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                )
                Spacer(Modifier.width(6.dp))
                Text("Private", style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}

@Composable
private fun BalanceHero(
    spent: Long,
    remaining: Long,
    hasBudget: Boolean,
    hasIncome: Boolean,
) {
    val primary = MaterialTheme.colorScheme.primary
    val secondary = MaterialTheme.colorScheme.secondary
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.extraLarge,
        color = Color.Transparent,
    ) {
        Box(
            modifier = Modifier.background(
                Brush.linearGradient(
                    listOf(primary.copy(alpha = 0.92f), Color(0xFF4656D9), secondary.copy(alpha = 0.82f)),
                ),
            ),
        ) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 10.dp, end = 2.dp)
                    .size(126.dp)
                    .background(Color.White.copy(alpha = 0.07f), CircleShape),
            )
            Column(modifier = Modifier.padding(horizontal = 22.dp, vertical = 24.dp)) {
                Text(
                    text = "SPENT THIS MONTH",
                    style = MaterialTheme.typography.labelMedium,
                    color = Color.White.copy(alpha = 0.82f),
                    fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.height(5.dp))
                MoneyText(
                    amountMinor = spent,
                    style = MaterialTheme.typography.displayLarge,
                    color = Color.White,
                )
                Spacer(Modifier.height(22.dp))
                Surface(
                    color = Color.Black.copy(alpha = 0.15f),
                    contentColor = Color.White,
                    shape = MaterialTheme.shapes.medium,
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            when {
                                hasBudget -> "Left in budgets"
                                hasIncome -> "Net cash flow"
                                else -> "Add income or a budget"
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = Color.White.copy(alpha = 0.85f),
                        )
                        if (hasBudget || hasIncome) {
                            Text(
                                text = (if (remaining < 0) "−" else "") + formatMoney(kotlin.math.abs(remaining)),
                                style = MaterialTheme.typography.titleMedium,
                                color = Color.White,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun QuickActions(
    isScanning: Boolean,
    hasSmsPermission: Boolean,
    onScan: () -> Unit,
    onRequestPermission: () -> Unit,
    onAdd: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        val scanInteraction = remember { MutableInteractionSource() }
        val scanPressed by scanInteraction.collectIsPressedAsState()
        val scanScale by animateFloatAsState(
            if (scanPressed) 0.97f else 1f,
            animationSpec = tween(120),
            label = "scanPress",
        )
        Button(
            onClick = if (hasSmsPermission) onScan else onRequestPermission,
            enabled = !isScanning,
            modifier = Modifier
                .weight(1f)
                .height(52.dp)
                .scale(scanScale),
            interactionSource = scanInteraction,
            shape = MaterialTheme.shapes.medium,
        ) {
            if (isScanning) {
                CircularProgressIndicator(
                    modifier = Modifier.size(19.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onPrimary,
                )
            } else {
                Icon(Icons.Rounded.Sync, contentDescription = null, modifier = Modifier.size(20.dp))
            }
            Spacer(Modifier.width(8.dp))
            Text(if (isScanning) "Scanning…" else if (hasSmsPermission) "Scan SMS" else "Enable SMS")
        }

        val addInteraction = remember { MutableInteractionSource() }
        val addPressed by addInteraction.collectIsPressedAsState()
        val addScale by animateFloatAsState(
            if (addPressed) 0.97f else 1f,
            animationSpec = tween(120),
            label = "addPress",
        )
        OutlinedButton(
            onClick = onAdd,
            modifier = Modifier
                .weight(1f)
                .height(52.dp)
                .scale(addScale),
            interactionSource = addInteraction,
            shape = MaterialTheme.shapes.medium,
            colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.onSurface),
        ) {
            Icon(Icons.Rounded.Add, contentDescription = null, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
            Text("Add manually")
        }
    }
}

@Composable
private fun CategoryLegend(
    category: ExpenseCategory,
    amountMinor: Long,
    totalMinor: Long,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        CategoryIcon(category, modifier = Modifier.size(34.dp), iconSize = 17)
        Spacer(Modifier.width(9.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                category.label,
                style = MaterialTheme.typography.labelLarge,
                maxLines = 1,
            )
            Text(
                if (totalMinor > 0) (amountMinor * 100 / totalMinor).toString() + "% of spend" else "No spend",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(formatMoney(amountMinor), style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
private fun InsightCard(
    category: ExpenseCategory,
    amountMinor: Long,
    totalMinor: Long,
) {
    Surface(
        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.58f),
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        shape = MaterialTheme.shapes.large,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(18.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(46.dp)
                    .background(MaterialTheme.colorScheme.primary, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Rounded.AutoAwesome,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimary,
                )
            }
            Spacer(Modifier.width(13.dp))
            Column {
                Text("Monthly insight", style = MaterialTheme.typography.labelLarge)
                Text(
                    text = category.label + " is your largest category at " +
                        (if (totalMinor > 0) (amountMinor * 100 / totalMinor) else 0) + "%.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}
