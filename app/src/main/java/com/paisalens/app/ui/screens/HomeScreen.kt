package com.paisalens.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AccountBalance
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.CreditCard
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.paisalens.app.data.model.AccountProfile
import com.paisalens.app.data.model.AccountType
import com.paisalens.app.data.model.CategoryBudget
import com.paisalens.app.data.model.ExpenseCategory
import com.paisalens.app.data.model.ReviewStatus
import com.paisalens.app.data.model.TransactionRecord
import com.paisalens.app.data.model.TransactionType
import com.paisalens.app.ui.components.CategoryIcon
import com.paisalens.app.ui.components.MoneyText
import com.paisalens.app.ui.components.PaisaCard
import com.paisalens.app.ui.components.SectionHeader
import com.paisalens.app.ui.components.SpendingDonut
import com.paisalens.app.ui.components.TransactionRow
import com.paisalens.app.ui.components.formatMoney
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    transactions: List<TransactionRecord>,
    budgets: List<CategoryBudget>,
    accounts: List<AccountProfile>,
    isScanning: Boolean,
    hasSmsPermission: Boolean,
    onScan: () -> Unit,
    onRequestPermission: () -> Unit,
    onAdd: () -> Unit,
    onRefreshAccount: (AccountProfile) -> Unit,
    onTransactionClick: (TransactionRecord) -> Unit,
) {
    val now = remember { ZonedDateTime.now() }
    var selectedCategory by remember { mutableStateOf<ExpenseCategory?>(null) }
    val confirmedTransactions = remember(transactions) {
        transactions.filter { it.reviewStatus == ReviewStatus.CONFIRMED }
    }
    val monthly = remember(confirmedTransactions, now.monthValue, now.year) {
        confirmedTransactions.filter {
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
    val bankAccounts = accounts.filter { it.type == AccountType.BANK_ACCOUNT }
    val creditCards = accounts.filter { it.type == AccountType.CREDIT_CARD }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 18.dp, top = 14.dp, end = 18.dp, bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        item {
            HomeHeader(
                isScanning = isScanning,
                hasSmsPermission = hasSmsPermission,
                onScan = onScan,
                onRequestPermission = onRequestPermission,
                onAdd = onAdd,
            )
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
            SpendOverviewCard(
                expenseTotal = expenseTotal,
                refunds = refunds,
                income = income,
                remaining = remaining,
                hasPlan = budgetTotal > 0 || income > 0,
            )
        }
        item {
            CategorySpendCard(
                categoryTotals = categoryTotals,
                expenseTotal = expenseTotal,
                onCategoryClick = { selectedCategory = it },
            )
        }
        item { SectionHeader("Bank balances") }
        if (bankAccounts.isEmpty()) {
            item {
                AvailabilityEmptyCard(
                    title = "No bank accounts detected",
                    body = "Scan SMS alerts containing account last-four digits, or add an account in Settings.",
                )
            }
        } else {
            items(bankAccounts, key = { "bank-${it.id}" }) { account ->
                AccountAvailabilityTile(
                    account = account,
                    valueMinor = account.balanceMinor,
                    valueLabel = "Current balance",
                    icon = Icons.Rounded.AccountBalance,
                    onRefresh = { onRefreshAccount(account) },
                )
            }
        }
        item { SectionHeader("Credit available") }
        if (creditCards.isEmpty()) {
            item {
                AvailabilityEmptyCard(
                    title = "No credit cards detected",
                    body = "Cards appear after a card SMS is scanned or when you add one in Settings.",
                )
            }
        } else {
            items(creditCards, key = { "card-${it.id}" }) { account ->
                AccountAvailabilityTile(
                    account = account,
                    valueMinor = account.availableCreditMinor,
                    valueLabel = "Available credit limit",
                    icon = Icons.Rounded.CreditCard,
                    onRefresh = { onRefreshAccount(account) },
                )
            }
        }
    }

    selectedCategory?.let { category ->
        val categoryExpenses = remember(monthlyExpenses, category) {
            monthlyExpenses
                .filter { it.category == category }
                .sortedByDescending { it.occurredAt }
        }
        CategorySpendingSheet(
            category = category,
            month = YearMonth.from(now),
            today = now.toLocalDate(),
            expenses = categoryExpenses,
            onDismiss = { selectedCategory = null },
            onTransactionClick = { transaction ->
                selectedCategory = null
                onTransactionClick(transaction)
            },
        )
    }
}

@Composable
private fun HomeHeader(
    isScanning: Boolean,
    hasSmsPermission: Boolean,
    onScan: () -> Unit,
    onRequestPermission: () -> Unit,
    onAdd: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "PaisaLens",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = "Your private money overview",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            HomeActionButton(
                contentDescription = if (hasSmsPermission) "Scan SMS" else "Enable SMS access",
                onClick = if (hasSmsPermission) onScan else onRequestPermission,
                enabled = !isScanning,
            ) {
                if (isScanning) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                } else {
                    Icon(Icons.Rounded.Sync, contentDescription = null)
                }
            }
            HomeActionButton(contentDescription = "Add transaction", onClick = onAdd) {
                Icon(Icons.Rounded.Add, contentDescription = null)
            }
        }
    }
}

@Composable
private fun HomeActionButton(
    contentDescription: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
    content: @Composable () -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        shape = CircleShape,
    ) {
        IconButton(
            onClick = onClick,
            enabled = enabled,
            modifier = Modifier.size(48.dp).semantics { this.contentDescription = contentDescription },
        ) {
            content()
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
                MoneyText(amountMinor = spent, style = MaterialTheme.typography.displayLarge, color = Color.White)
                Spacer(Modifier.height(22.dp))
                Surface(
                    color = Color.Black.copy(alpha = 0.15f),
                    contentColor = Color.White,
                    shape = MaterialTheme.shapes.medium,
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
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
                                text = (if (remaining < 0) "−" else "") + formatMoney(abs(remaining)),
                                style = MaterialTheme.typography.titleMedium,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SpendOverviewCard(
    expenseTotal: Long,
    refunds: Long,
    income: Long,
    remaining: Long,
    hasPlan: Boolean,
) {
    Column {
        SectionHeader("Spend overview")
        Spacer(Modifier.height(8.dp))
        PaisaCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(horizontal = 18.dp, vertical = 8.dp)) {
                OverviewRow("Gross expenses", expenseTotal)
                OverviewRow("Refunds received", refunds)
                OverviewRow("Income this month", income)
                if (hasPlan) OverviewRow("Available after spending", remaining, signed = true)
            }
        }
    }
}

@Composable
private fun OverviewRow(label: String, amountMinor: Long, signed: Boolean = false) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            text = if (signed && amountMinor < 0) "−${formatMoney(abs(amountMinor))}" else formatMoney(amountMinor),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
private fun CategorySpendCard(
    categoryTotals: List<Pair<ExpenseCategory, Long>>,
    expenseTotal: Long,
    onCategoryClick: (ExpenseCategory) -> Unit,
) {
    Column {
        SectionHeader("Spending breakdown")
        Spacer(Modifier.height(8.dp))
        PaisaCard(Modifier.fillMaxWidth()) {
            if (categoryTotals.isEmpty()) {
                Text(
                    text = "No categorized expenses this month.",
                    modifier = Modifier.padding(18.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 18.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    SpendingDonut(values = categoryTotals.take(5), totalMinor = expenseTotal)
                    Spacer(Modifier.height(14.dp))
                    Text(
                        text = "Tap a category to see its daily trend and expenses",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                    Spacer(Modifier.height(6.dp))
                    categoryTotals.forEach { (category, amount) ->
                        CategoryLegend(
                            category = category,
                            amountMinor = amount,
                            totalMinor = expenseTotal,
                            onClick = { onCategoryClick(category) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CategoryLegend(
    category: ExpenseCategory,
    amountMinor: Long,
    totalMinor: Long,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        color = Color.Transparent,
        shape = MaterialTheme.shapes.medium,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CategoryIcon(category, modifier = Modifier.size(40.dp), iconSize = 19)
            Spacer(Modifier.width(11.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(category.label, style = MaterialTheme.typography.labelLarge, maxLines = 1)
                Text(
                    if (totalMinor > 0) "${amountMinor * 100 / totalMinor}% of spend" else "No spend",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(formatMoney(amountMinor), style = MaterialTheme.typography.labelLarge)
            Spacer(Modifier.width(4.dp))
            Icon(
                imageVector = Icons.Rounded.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private data class CategoryDailySpend(
    val date: LocalDate,
    val amountMinor: Long,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CategorySpendingSheet(
    category: ExpenseCategory,
    month: YearMonth,
    today: LocalDate,
    expenses: List<TransactionRecord>,
    onDismiss: () -> Unit,
    onTransactionClick: (TransactionRecord) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val monthFormatter = remember { DateTimeFormatter.ofPattern("MMMM yyyy", Locale.ENGLISH) }
    val totalMinor = remember(expenses) { expenses.sumOf { it.amountMinor } }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxWidth().navigationBarsPadding(),
            contentPadding = PaddingValues(bottom = 28.dp),
        ) {
            item {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CategoryIcon(category = category, modifier = Modifier.size(48.dp), iconSize = 22)
                    Spacer(Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(category.label, style = MaterialTheme.typography.headlineMedium)
                        Text(
                            text = month.format(monthFormatter),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    IconButton(onClick = onDismiss, modifier = Modifier.size(48.dp)) {
                        Icon(Icons.Rounded.Close, contentDescription = "Close category details")
                    }
                }
            }
            item {
                CategoryDailySpendingChart(
                    expenses = expenses,
                    month = month,
                    today = today,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 18.dp),
                )
            }
            item {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column {
                        Text("Expenses", style = MaterialTheme.typography.titleLarge)
                        Text(
                            text = "${expenses.size} ${if (expenses.size == 1) "transaction" else "transactions"}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    MoneyText(amountMinor = totalMinor, style = MaterialTheme.typography.titleLarge)
                }
            }
            if (expenses.isEmpty()) {
                item {
                    Text(
                        text = "No expenses were recorded in this category during this month.",
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 24.dp),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                items(expenses, key = { "category-expense-${it.id}" }) { expense ->
                    TransactionRow(
                        transaction = expense,
                        onClick = { onTransactionClick(expense) },
                        modifier = Modifier.padding(horizontal = 16.dp),
                    )
                    HorizontalDivider(modifier = Modifier.padding(horizontal = 20.dp))
                }
            }
        }
    }
}

@Composable
private fun CategoryDailySpendingChart(
    expenses: List<TransactionRecord>,
    month: YearMonth,
    today: LocalDate,
    modifier: Modifier = Modifier,
) {
    val zoneId = remember { ZoneId.systemDefault() }
    val dateFormatter = remember { DateTimeFormatter.ofPattern("d MMM", Locale.forLanguageTag("en-IN")) }
    val points = remember(expenses, month, today, zoneId) {
        val totalsByDate = expenses
            .groupBy { Instant.ofEpochMilli(it.occurredAt).atZone(zoneId).toLocalDate() }
            .mapValues { (_, rows) -> rows.sumOf { it.amountMinor } }
        val lastDay = if (YearMonth.from(today) == month) today.dayOfMonth else month.lengthOfMonth()
        (1..lastDay).map { day ->
            val date = month.atDay(day)
            CategoryDailySpend(date, totalsByDate[date] ?: 0L)
        }
    }
    val totalMinor = expenses.sumOf { it.amountMinor }
    val peakPoint = points.maxByOrNull { it.amountMinor }
    val peakMinor = peakPoint?.amountMinor ?: 0L
    val maxValue = peakMinor.coerceAtLeast(1L)
    val midpoint = points.getOrNull(points.lastIndex / 2)?.date
    val chartDescription = buildString {
        append("Daily spending line chart for ${month.month.name.lowercase().replaceFirstChar { it.titlecase() }}. ")
        append("Horizontal axis is date and vertical axis is spending in rupees. ")
        append("Total ${formatMoney(totalMinor)} across ${expenses.size} expenses. ")
        peakPoint?.takeIf { it.amountMinor > 0 }?.let {
            append("Highest daily spend ${formatMoney(it.amountMinor)} on ${it.date.format(dateFormatter)}. ")
        }
        append("Exact transactions are listed below.")
    }
    val lineColor = MaterialTheme.colorScheme.primary
    val gridColor = MaterialTheme.colorScheme.outlineVariant

    PaisaCard(modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Daily spending", style = MaterialTheme.typography.titleMedium)
                    Text(
                        text = "Y-axis: spend · X-axis: daily date",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    text = "Peak ${formatMoney(peakMinor)}",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            Row(modifier = Modifier.fillMaxWidth().height(158.dp)) {
                Column(
                    modifier = Modifier.width(64.dp).height(142.dp),
                    verticalArrangement = Arrangement.SpaceBetween,
                    horizontalAlignment = Alignment.End,
                ) {
                    Text(formatAxisMoney(maxValue), style = MaterialTheme.typography.labelSmall)
                    Text(formatAxisMoney(maxValue / 2), style = MaterialTheme.typography.labelSmall)
                    Text("₹0", style = MaterialTheme.typography.labelSmall)
                }
                Spacer(Modifier.width(8.dp))
                Canvas(
                    modifier = Modifier
                        .weight(1f)
                        .height(142.dp)
                        .semantics { contentDescription = chartDescription },
                ) {
                    val verticalInset = 6.dp.toPx()
                    val plotHeight = (size.height - verticalInset * 2).coerceAtLeast(1f)
                    repeat(3) { index ->
                        val y = verticalInset + plotHeight * index / 2f
                        drawLine(
                            color = gridColor,
                            start = Offset(0f, y),
                            end = Offset(size.width, y),
                            strokeWidth = 1.dp.toPx(),
                        )
                    }
                    val coordinates = points.mapIndexed { index, point ->
                        val x = if (points.size <= 1) size.width / 2f else size.width * index / points.lastIndex
                        val y = verticalInset + plotHeight * (1f - point.amountMinor.toFloat() / maxValue.toFloat())
                        Offset(x, y)
                    }
                    if (coordinates.size > 1) {
                        val path = Path().apply {
                            moveTo(coordinates.first().x, coordinates.first().y)
                            coordinates.drop(1).forEach { lineTo(it.x, it.y) }
                        }
                        drawPath(
                            path = path,
                            color = lineColor,
                            style = Stroke(3.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round),
                        )
                    }
                    coordinates.forEachIndexed { index, coordinate ->
                        if (points[index].amountMinor > 0) {
                            drawCircle(color = lineColor, radius = 4.dp.toPx(), center = coordinate)
                        }
                    }
                }
            }
            Row(modifier = Modifier.fillMaxWidth()) {
                Spacer(Modifier.width(72.dp))
                Row(modifier = Modifier.weight(1f), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(points.firstOrNull()?.date?.format(dateFormatter).orEmpty(), style = MaterialTheme.typography.labelSmall)
                    midpoint?.let { Text(it.format(dateFormatter), style = MaterialTheme.typography.labelSmall) }
                    Text(points.lastOrNull()?.date?.format(dateFormatter).orEmpty(), style = MaterialTheme.typography.labelSmall)
                }
            }
            Text(
                text = "Date",
                modifier = Modifier.fillMaxWidth().padding(start = 72.dp),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}

private fun formatAxisMoney(amountMinor: Long): String = when {
    amountMinor >= 10_000_000 -> compactAxisValue(amountMinor / 10_000_000.0, "L")
    amountMinor >= 100_000 -> compactAxisValue(amountMinor / 100_000.0, "K")
    else -> "₹${amountMinor / 100}"
}

private fun compactAxisValue(value: Double, suffix: String): String {
    val formatted = String.format(Locale.US, "%.1f", value).removeSuffix(".0")
    return "₹$formatted$suffix"
}

@Composable
private fun AccountAvailabilityTile(
    account: AccountProfile,
    valueMinor: Long?,
    valueLabel: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onRefresh: () -> Unit,
) {
    PaisaCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(18.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                Surface(
                    color = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    shape = CircleShape,
                ) {
                    Icon(icon, contentDescription = null, modifier = Modifier.padding(10.dp).size(22.dp))
                }
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = account.name,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = account.accountHint?.let { "•••• $it" } ?: account.type.label,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(
                    onClick = onRefresh,
                    modifier = Modifier.size(48.dp),
                ) {
                    Icon(Icons.Rounded.Refresh, contentDescription = "Refresh ${account.name}")
                }
            }
            Spacer(Modifier.height(12.dp))
            Text(valueLabel, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (valueMinor == null) {
                Text("Not available", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            } else {
                MoneyText(valueMinor, style = MaterialTheme.typography.headlineSmall)
            }
            Spacer(Modifier.height(6.dp))
            Text(
                text = account.availabilityFetchedAt?.let { "Fetched ${formatAvailabilityTime(it)}" }
                    ?: "Not fetched from SMS yet",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun AvailabilityEmptyCard(title: String, body: String) {
    PaisaCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(18.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(4.dp))
            Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private fun formatAvailabilityTime(timestamp: Long): String = Instant.ofEpochMilli(timestamp)
    .atZone(ZoneId.systemDefault())
    .format(DateTimeFormatter.ofPattern("d MMM, h:mm a", Locale.forLanguageTag("en-IN")))
