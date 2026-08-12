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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AccountBalance
import androidx.compose.material.icons.rounded.AccountBalanceWallet
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ChevronLeft
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.CreditCard
import androidx.compose.material.icons.rounded.DashboardCustomize
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import com.paisalens.app.data.model.HomeLayoutConfiguration
import com.paisalens.app.data.model.HomeModule
import com.paisalens.app.data.model.PaymentCommitment
import com.paisalens.app.data.model.ReviewStatus
import com.paisalens.app.data.model.SavingsContribution
import com.paisalens.app.data.model.SavingsGoal
import com.paisalens.app.data.model.TransactionRecord
import com.paisalens.app.data.model.TransactionLink
import com.paisalens.app.data.model.TransactionType
import com.paisalens.app.data.model.USER_ENTERED_UPI_BALANCE_SOURCE
import com.paisalens.app.data.model.calculateCreditUtilization
import com.paisalens.app.data.model.transactionIdsAppliedAsExpenseOffsets
import com.paisalens.app.data.model.transactionIdsExcludedFromSpending
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
    effectiveExpenseTransactions: List<TransactionRecord>,
    transactionLinks: List<TransactionLink>,
    budgets: List<CategoryBudget>,
    accounts: List<AccountProfile>,
    homeLayout: HomeLayoutConfiguration,
    savingsGoals: List<SavingsGoal>,
    savingsContributions: List<SavingsContribution>,
    paymentCommitments: List<PaymentCommitment>,
    isScanning: Boolean,
    hasSmsPermission: Boolean,
    onScan: () -> Unit,
    onRequestPermission: () -> Unit,
    onAdd: () -> Unit,
    onCustomizeHome: () -> Unit,
    onOpenSavingsGoals: () -> Unit,
    onOpenCommitments: () -> Unit,
    onRefreshAccount: (AccountProfile) -> Unit,
    onCheckBalanceViaUpi: (AccountProfile, Set<Long>) -> Unit,
    onAccountClick: (AccountProfile) -> Unit,
    onTransactionClick: (TransactionRecord) -> Unit,
) {
    val now = remember { ZonedDateTime.now() }
    val currentMonth = remember(now) { YearMonth.from(now) }
    var selectedMonth by remember { mutableStateOf(currentMonth) }
    var selectedCategory by remember { mutableStateOf<ExpenseCategory?>(null) }
    var unavailableAccountsExpanded by remember { mutableStateOf(false) }
    LaunchedEffect(homeLayout, currentMonth) {
        if (!homeLayout.isVisible(HomeModule.SPENDING_BREAKDOWN)) selectedMonth = currentMonth
    }
    val confirmedTransactions = remember(transactions) {
        transactions.filter { it.reviewStatus == ReviewStatus.CONFIRMED }
    }
    val earliestMonth = remember(effectiveExpenseTransactions, currentMonth) {
        effectiveExpenseTransactions.minOfOrNull {
            YearMonth.from(Instant.ofEpochMilli(it.occurredAt).atZone(ZoneId.systemDefault()))
        } ?: currentMonth
    }
    val monthly = remember(confirmedTransactions, selectedMonth) {
        transactionsForMonth(confirmedTransactions, selectedMonth)
    }
    val monthlyExpenses = remember(effectiveExpenseTransactions, selectedMonth) {
        transactionsForMonth(effectiveExpenseTransactions, selectedMonth)
    }
    val excludedFromSpending = remember(transactionLinks) {
        transactionIdsExcludedFromSpending(transactionLinks)
    }
    val linkedOffsetTransactionIds = remember(transactionLinks, confirmedTransactions) {
        transactionIdsAppliedAsExpenseOffsets(confirmedTransactions, transactionLinks)
    }
    val grossExpenseTotal = monthly
        .filter { it.type == TransactionType.EXPENSE && it.id !in excludedFromSpending }
        .sumOf { it.amountMinor }
    val expenseTotal = monthlyExpenses.sumOf { it.amountMinor }
    val refunds = monthly.filter { it.type == TransactionType.REFUND }.sumOf { it.amountMinor }
    val unlinkedRefunds = monthly
        .filter { it.type == TransactionType.REFUND && it.id !in linkedOffsetTransactionIds }
        .sumOf { it.amountMinor }
    val spent = (expenseTotal - unlinkedRefunds).coerceAtLeast(0)
    val income = monthly
        .filter { it.type == TransactionType.INCOME && it.id !in linkedOffsetTransactionIds }
        .sumOf { it.amountMinor }
    val budgetTotal = budgets.sumOf { it.limitMinor }
    val remaining = if (budgetTotal > 0) budgetTotal - spent else income - spent
    val categoryTotals = monthlyExpenses
        .groupBy { it.category }
        .mapValues { (_, records) -> records.sumOf { it.amountMinor } }
        .toList()
        .sortedByDescending { it.second }
    val bankAccountGroups = remember(accounts) {
        consolidateAvailabilityAccounts(accounts, AccountType.BANK_ACCOUNT)
    }
    val creditCardGroups = remember(accounts) {
        consolidateAvailabilityAccounts(accounts, AccountType.CREDIT_CARD)
    }
    val availableBankAccounts = bankAccountGroups.filter { it.account.balanceMinor != null }
    val unavailableBankAccounts = bankAccountGroups.filter { it.account.balanceMinor == null }
    val availableCreditCards = creditCardGroups.filter { it.account.availableCreditMinor != null }
    val unavailableCreditCards = creditCardGroups.filter { it.account.availableCreditMinor == null }

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
                onCustomizeHome = onCustomizeHome,
            )
        }
        homeLayout.normalized().orderedVisibleModules.forEach { module ->
            when (module) {
                HomeModule.MONTHLY_SPEND -> item(key = module.storageId) {
                    BalanceHero(
                        spent = spent,
                        remaining = remaining,
                        hasBudget = budgetTotal > 0,
                        hasIncome = income > 0,
                        month = selectedMonth,
                        isCurrentMonth = selectedMonth == currentMonth,
                    )
                }
                HomeModule.SPEND_OVERVIEW -> item(key = module.storageId) {
                    SpendOverviewCard(
                        expenseTotal = grossExpenseTotal,
                        refunds = refunds,
                        income = income,
                        remaining = remaining,
                        hasPlan = budgetTotal > 0 || income > 0,
                        month = selectedMonth,
                    )
                }
                HomeModule.SPENDING_BREAKDOWN -> item(key = module.storageId) {
                    CategorySpendCard(
                        categoryTotals = categoryTotals,
                        expenseTotal = expenseTotal,
                        month = selectedMonth,
                        canGoPrevious = selectedMonth > earliestMonth,
                        canGoNext = selectedMonth < currentMonth,
                        onPreviousMonth = { selectedMonth = selectedMonth.minusMonths(1) },
                        onNextMonth = { selectedMonth = selectedMonth.plusMonths(1) },
                        onCategoryClick = { selectedCategory = it },
                    )
                }
                HomeModule.BANK_BALANCES -> {
                    item(key = "${module.storageId}-header") { SectionHeader("Bank balances") }
                    when {
                        bankAccountGroups.isEmpty() -> item(key = "${module.storageId}-empty") {
                            AvailabilityEmptyCard(
                                title = "No bank accounts detected",
                                body = "Scan SMS alerts containing account last-four digits, or add an account in Settings.",
                            )
                        }
                        availableBankAccounts.isEmpty() -> item(key = "${module.storageId}-unfetched") {
                            AvailabilityEmptyCard(
                                title = "No fetched balances yet",
                                body = "Accounts waiting for a balance are tucked into the expandable section below.",
                            )
                        }
                        else -> items(availableBankAccounts, key = { "bank-${it.key}" }) { group ->
                            AccountAvailabilityTile(
                                account = group.account,
                                profileCount = group.profileCount,
                                valueMinor = group.account.balanceMinor,
                                valueLabel = "Current balance",
                                icon = Icons.Rounded.AccountBalance,
                                onRefresh = { onRefreshAccount(group.account) },
                                onCheckViaUpi = { onCheckBalanceViaUpi(group.account, group.accountIds) },
                                onClick = { onAccountClick(group.account) },
                            )
                        }
                    }
                }
                HomeModule.CREDIT_AVAILABLE -> {
                    item(key = "${module.storageId}-header") { SectionHeader("Credit available") }
                    when {
                        creditCardGroups.isEmpty() -> item(key = "${module.storageId}-empty") {
                            AvailabilityEmptyCard(
                                title = "No credit cards detected",
                                body = "Cards appear after a card SMS is scanned or when you add one in Settings.",
                            )
                        }
                        availableCreditCards.isEmpty() -> item(key = "${module.storageId}-unfetched") {
                            AvailabilityEmptyCard(
                                title = "No available limits fetched yet",
                                body = "Cards waiting for an available-credit update are tucked into the expandable section below.",
                            )
                        }
                        else -> items(availableCreditCards, key = { "card-${it.key}" }) { group ->
                            AccountAvailabilityTile(
                                account = group.account,
                                profileCount = group.profileCount,
                                valueMinor = group.account.availableCreditMinor,
                                valueLabel = "Available credit limit",
                                icon = Icons.Rounded.CreditCard,
                                onRefresh = { onRefreshAccount(group.account) },
                                onCheckViaUpi = null,
                                onClick = { onAccountClick(group.account) },
                            )
                        }
                    }
                }
                HomeModule.SAVINGS_GOALS -> item(key = module.storageId) {
                    SavingsGoalsHomeModule(
                        goals = savingsGoals,
                        contributions = savingsContributions,
                        onOpen = onOpenSavingsGoals,
                    )
                }
                HomeModule.UPCOMING_COMMITMENTS -> item(key = module.storageId) {
                    UpcomingCommitmentsHomeModule(
                        commitments = paymentCommitments,
                        onOpen = onOpenCommitments,
                    )
                }
            }
        }
        val showBankUnavailable = homeLayout.isVisible(HomeModule.BANK_BALANCES)
        val showCardUnavailable = homeLayout.isVisible(HomeModule.CREDIT_AVAILABLE)
        val visibleUnavailableBanks = if (showBankUnavailable) unavailableBankAccounts else emptyList()
        val visibleUnavailableCards = if (showCardUnavailable) unavailableCreditCards else emptyList()
        if (visibleUnavailableBanks.isNotEmpty() || visibleUnavailableCards.isNotEmpty()) {
            item {
                UnavailableAccountsPanel(
                    bankAccounts = visibleUnavailableBanks,
                    creditCards = visibleUnavailableCards,
                    expanded = unavailableAccountsExpanded,
                    onExpandedChange = { unavailableAccountsExpanded = it },
                    onRefreshAccount = onRefreshAccount,
                    onCheckBalanceViaUpi = onCheckBalanceViaUpi,
                    onAccountClick = onAccountClick,
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
            month = selectedMonth,
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
    onCustomizeHome: () -> Unit,
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
            HomeActionButton(contentDescription = "Customise Home", onClick = onCustomizeHome) {
                Icon(Icons.Rounded.DashboardCustomize, contentDescription = null)
            }
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
    month: YearMonth,
    isCurrentMonth: Boolean,
) {
    val primary = MaterialTheme.colorScheme.primaryContainer
    val secondary = MaterialTheme.colorScheme.secondaryContainer
    val tertiary = MaterialTheme.colorScheme.tertiaryContainer
    val heroForeground = MaterialTheme.colorScheme.onPrimaryContainer
    val monthFormatter = remember { DateTimeFormatter.ofPattern("MMMM yyyy", Locale.ENGLISH) }
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.extraLarge,
        color = Color.Transparent,
    ) {
        Box(
            modifier = Modifier.background(
                Brush.linearGradient(
                    listOf(primary, tertiary, secondary),
                ),
            ),
        ) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 10.dp, end = 2.dp)
                    .size(126.dp)
                    .background(heroForeground.copy(alpha = 0.07f), CircleShape),
            )
            Column(modifier = Modifier.padding(horizontal = 22.dp, vertical = 24.dp)) {
                Text(
                    text = if (isCurrentMonth) "SPENT THIS MONTH" else "SPENT IN ${month.format(monthFormatter).uppercase(Locale.ENGLISH)}",
                    style = MaterialTheme.typography.labelMedium,
                    color = heroForeground.copy(alpha = 0.82f),
                    fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.height(5.dp))
                MoneyText(amountMinor = spent, style = MaterialTheme.typography.displayLarge, color = heroForeground)
                Spacer(Modifier.height(22.dp))
                Surface(
                    color = heroForeground.copy(alpha = 0.10f),
                    contentColor = heroForeground,
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
                            color = heroForeground.copy(alpha = 0.85f),
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
    month: YearMonth,
) {
    val monthLabel = remember(month) { month.format(DateTimeFormatter.ofPattern("MMMM yyyy", Locale.ENGLISH)) }
    Column {
        SectionHeader("Spend overview · $monthLabel")
        Spacer(Modifier.height(8.dp))
        PaisaCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(horizontal = 18.dp, vertical = 8.dp)) {
                OverviewRow("Gross expenses", expenseTotal)
                OverviewRow("Refunds received", refunds)
                OverviewRow("Income in ${month.month.getDisplayName(java.time.format.TextStyle.FULL, Locale.ENGLISH)}", income)
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
    month: YearMonth,
    canGoPrevious: Boolean,
    canGoNext: Boolean,
    onPreviousMonth: () -> Unit,
    onNextMonth: () -> Unit,
    onCategoryClick: (ExpenseCategory) -> Unit,
) {
    Column {
        SectionHeader("Spending breakdown")
        Spacer(Modifier.height(8.dp))
        MonthNavigator(
            month = month,
            canGoPrevious = canGoPrevious,
            canGoNext = canGoNext,
            onPreviousMonth = onPreviousMonth,
            onNextMonth = onNextMonth,
        )
        Spacer(Modifier.height(8.dp))
        PaisaCard(Modifier.fillMaxWidth()) {
            if (categoryTotals.isEmpty()) {
                Text(
                    text = "No categorized expenses in ${month.format(DateTimeFormatter.ofPattern("MMMM yyyy", Locale.ENGLISH))}.",
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
private fun MonthNavigator(
    month: YearMonth,
    canGoPrevious: Boolean,
    canGoNext: Boolean,
    onPreviousMonth: () -> Unit,
    onNextMonth: () -> Unit,
) {
    val monthFormatter = remember { DateTimeFormatter.ofPattern("MMMM yyyy", Locale.ENGLISH) }
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f),
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 2.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onPreviousMonth, enabled = canGoPrevious) {
                Icon(Icons.Rounded.ChevronLeft, contentDescription = "Previous month")
            }
            Text(
                text = month.format(monthFormatter),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
            )
            IconButton(onClick = onNextMonth, enabled = canGoNext) {
                Icon(Icons.Rounded.ChevronRight, contentDescription = "Next month")
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
    profileCount: Int,
    valueMinor: Long?,
    valueLabel: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onRefresh: () -> Unit,
    onCheckViaUpi: (() -> Unit)?,
    onClick: () -> Unit,
) {
    val style = accountTileStyle(account)
    val utilization = if (account.type == AccountType.CREDIT_CARD) {
        calculateCreditUtilization(account.id, account.availableCreditMinor, account.creditLimitMinor)
    } else {
        null
    }
    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.extraLarge,
        color = Color.Transparent,
        shadowElevation = 3.dp,
    ) {
        Box(modifier = Modifier.background(Brush.linearGradient(style.shades))) {
            Canvas(Modifier.matchParentSize()) {
                drawCircle(
                    color = style.foreground.copy(alpha = 0.07f),
                    radius = size.minDimension * 0.6f,
                    center = Offset(size.width * 0.96f, size.height * 0.02f),
                )
                drawCircle(
                    color = Color.Black.copy(alpha = 0.05f),
                    radius = size.minDimension * 0.42f,
                    center = Offset(size.width * 0.08f, size.height * 1.05f),
                )
                repeat(6) { index ->
                    val lineOffset = size.width * (index - 2) / 5f
                    drawLine(
                        color = style.foreground.copy(alpha = 0.035f),
                        start = Offset(lineOffset, size.height),
                        end = Offset(lineOffset + size.height, 0f),
                        strokeWidth = 1.dp.toPx(),
                    )
                }
            }
            Column(Modifier.padding(18.dp)) {
                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                    Surface(
                        color = style.foreground.copy(alpha = 0.14f),
                        contentColor = style.foreground,
                        shape = CircleShape,
                    ) {
                        Icon(icon, contentDescription = null, modifier = Modifier.padding(10.dp).size(22.dp))
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = account.name,
                            style = MaterialTheme.typography.titleMedium,
                            color = style.foreground,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = buildString {
                                append(account.accountHint?.let { "•••• $it" } ?: account.type.label)
                                if (profileCount > 1) append(" · $profileCount profiles combined")
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = style.foreground.copy(alpha = 0.76f),
                        )
                    }
                }
                Spacer(Modifier.height(16.dp))
                Text(valueLabel, style = MaterialTheme.typography.labelLarge, color = style.foreground.copy(alpha = 0.76f))
                if (valueMinor == null) {
                    Text(
                        "Not available",
                        style = MaterialTheme.typography.headlineSmall,
                        color = style.foreground,
                        fontWeight = FontWeight.Bold,
                    )
                } else {
                    MoneyText(valueMinor, style = MaterialTheme.typography.headlineSmall, color = style.foreground)
                }
                utilization?.utilizationBasisPoints?.let { basisPoints ->
                    Spacer(Modifier.height(10.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Credit used", style = MaterialTheme.typography.bodySmall, color = style.foreground.copy(alpha = 0.76f))
                        Text("${"%.1f".format(Locale.US, basisPoints / 100.0)}%", style = MaterialTheme.typography.bodySmall, color = style.foreground, fontWeight = FontWeight.Bold)
                    }
                    Spacer(Modifier.height(5.dp))
                    LinearProgressIndicator(
                        progress = { (basisPoints / 10_000f).coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth().height(7.dp),
                        color = style.foreground,
                        trackColor = style.foreground.copy(alpha = 0.20f),
                        strokeCap = StrokeCap.Round,
                    )
                }
                Spacer(Modifier.height(8.dp))
                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
                    Text(
                        text = account.availabilityFetchedAt?.let {
                            val action = if (
                                account.availabilitySender?.startsWith(USER_ENTERED_UPI_BALANCE_SOURCE) == true
                            ) {
                                "Entered"
                            } else {
                                "Fetched"
                            }
                            "$action ${formatAvailabilityTime(it)}"
                        } ?: "No balance saved yet",
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodySmall,
                        color = style.foreground.copy(alpha = 0.72f),
                    )
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        onCheckViaUpi?.let { checkViaUpi ->
                            Surface(
                                onClick = checkViaUpi,
                                modifier = Modifier.heightIn(min = 48.dp),
                                color = style.foreground.copy(alpha = 0.14f),
                                contentColor = style.foreground,
                                shape = CircleShape,
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 13.dp, vertical = 12.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Icon(
                                        Icons.Rounded.AccountBalanceWallet,
                                        contentDescription = null,
                                        modifier = Modifier.size(19.dp),
                                    )
                                    Spacer(Modifier.width(6.dp))
                                    Text("UPI check", style = MaterialTheme.typography.labelLarge)
                                }
                            }
                        }
                        Surface(
                            color = style.foreground.copy(alpha = 0.14f),
                            contentColor = style.foreground,
                            shape = CircleShape,
                        ) {
                            IconButton(onClick = onRefresh, modifier = Modifier.size(48.dp)) {
                                Icon(Icons.Rounded.Refresh, contentDescription = "Refresh ${account.name} by SMS")
                            }
                        }
                    }
                }
            }
        }
    }
}

private data class AccountTileStyle(
    val shades: List<Color>,
    val foreground: Color,
)

@Composable
private fun accountTileStyle(account: AccountProfile): AccountTileStyle {
    val identity = "${account.institution.orEmpty()} ${account.name}".uppercase(Locale.ENGLISH)
    return when {
        "HDFC" in identity -> AccountTileStyle(
            shades = listOf(Color(0xFF061A3D), Color(0xFF0A2D63), Color(0xFF164E8B)),
            foreground = Color.White,
        )
        "IDFC" in identity -> AccountTileStyle(
            shades = listOf(Color(0xFF7A0B19), Color(0xFFB5142A), Color(0xFFE04650)),
            foreground = Color.White,
        )
        "SBI" in identity || "STATE BANK" in identity -> AccountTileStyle(
            shades = listOf(Color(0xFF086CA8), Color(0xFF199BD3), Color(0xFF68CAF2)),
            foreground = Color.White,
        )
        else -> AccountTileStyle(
            shades = listOf(MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.secondary),
            foreground = MaterialTheme.colorScheme.onPrimary,
        )
    }
}

@Composable
private fun UnavailableAccountsPanel(
    bankAccounts: List<AccountAvailabilityGroup>,
    creditCards: List<AccountAvailabilityGroup>,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    onRefreshAccount: (AccountProfile) -> Unit,
    onCheckBalanceViaUpi: (AccountProfile, Set<Long>) -> Unit,
    onAccountClick: (AccountProfile) -> Unit,
) {
    val total = bankAccounts.size + creditCards.size
    PaisaCard(Modifier.fillMaxWidth()) {
        Column {
            Surface(
                onClick = { onExpandedChange(!expanded) },
                modifier = Modifier.fillMaxWidth(),
                color = Color.Transparent,
                contentColor = MaterialTheme.colorScheme.onSurface,
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        shape = CircleShape,
                    ) {
                        Icon(
                            Icons.Rounded.Refresh,
                            contentDescription = null,
                            modifier = Modifier.padding(9.dp).size(20.dp),
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Balances not yet available", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "$total ${if (total == 1) "account needs" else "accounts need"} a balance update",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Icon(
                        imageVector = if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                        contentDescription = if (expanded) "Collapse unavailable accounts" else "Expand unavailable accounts",
                    )
                }
            }
            if (expanded) {
                HorizontalDivider()
                Column(
                    modifier = Modifier.fillMaxWidth().padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    if (bankAccounts.isNotEmpty()) {
                        Text(
                            "BANK ACCOUNTS",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontWeight = FontWeight.Bold,
                        )
                        bankAccounts.forEach { group ->
                            AccountAvailabilityTile(
                                account = group.account,
                                profileCount = group.profileCount,
                                valueMinor = null,
                                valueLabel = "Current balance",
                                icon = Icons.Rounded.AccountBalance,
                                onRefresh = { onRefreshAccount(group.account) },
                                onCheckViaUpi = { onCheckBalanceViaUpi(group.account, group.accountIds) },
                                onClick = { onAccountClick(group.account) },
                            )
                        }
                    }
                    if (creditCards.isNotEmpty()) {
                        Text(
                            "CREDIT CARDS",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontWeight = FontWeight.Bold,
                        )
                        creditCards.forEach { group ->
                            AccountAvailabilityTile(
                                account = group.account,
                                profileCount = group.profileCount,
                                valueMinor = null,
                                valueLabel = "Available credit limit",
                                icon = Icons.Rounded.CreditCard,
                                onRefresh = { onRefreshAccount(group.account) },
                                onCheckViaUpi = null,
                                onClick = { onAccountClick(group.account) },
                            )
                        }
                    }
                }
            }
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

internal fun transactionsForMonth(
    transactions: List<TransactionRecord>,
    month: YearMonth,
    zoneId: ZoneId = ZoneId.systemDefault(),
): List<TransactionRecord> = transactions.filter { transaction ->
    YearMonth.from(Instant.ofEpochMilli(transaction.occurredAt).atZone(zoneId)) == month
}

internal data class AccountAvailabilityGroup(
    val key: String,
    val account: AccountProfile,
    val accountIds: Set<Long>,
    val profileCount: Int,
)

internal fun consolidateAvailabilityAccounts(
    accounts: List<AccountProfile>,
    type: AccountType,
): List<AccountAvailabilityGroup> = accounts
    .filter { it.type == type }
    .groupBy { account ->
        accountLastFour(account)?.let { "last4:$it" }
            ?: "account:${account.id}:${account.name.lowercase(Locale.ROOT)}"
    }
    .map { (key, matches) ->
        val preferred = matches.maxWithOrNull(
            compareBy<AccountProfile> { if (availabilityValue(it, type) != null) 1 else 0 }
                .thenBy { it.availabilityFetchedAt ?: Long.MIN_VALUE },
        ) ?: matches.first()
        val lastFour = key.removePrefix("last4:").takeIf { key.startsWith("last4:") }
        AccountAvailabilityGroup(
            key = key,
            account = preferred.copy(
                accountHint = lastFour ?: preferred.accountHint,
                institution = preferred.institution?.takeIf(String::isNotBlank)
                    ?: matches.firstNotNullOfOrNull { it.institution?.takeIf(String::isNotBlank) },
                creditLimitMinor = preferred.creditLimitMinor
                    ?: matches.filter { it.creditLimitMinor != null }
                        .maxByOrNull { it.availabilityFetchedAt ?: Long.MIN_VALUE }
                        ?.creditLimitMinor,
            ),
            accountIds = matches.mapTo(linkedSetOf()) { it.id },
            profileCount = matches.size,
        )
    }
    .sortedWith(
        compareByDescending<AccountAvailabilityGroup> { availabilityValue(it.account, type) != null }
            .thenByDescending { it.account.availabilityFetchedAt ?: Long.MIN_VALUE }
            .thenBy { it.account.name.lowercase(Locale.ROOT) },
    )

private fun accountLastFour(account: AccountProfile): String? = account.accountHint
    ?.filter(Char::isDigit)
    ?.takeLast(4)
    ?.takeIf { it.length == 4 }

private fun availabilityValue(account: AccountProfile, type: AccountType): Long? = when (type) {
    AccountType.BANK_ACCOUNT -> account.balanceMinor
    AccountType.CREDIT_CARD -> account.availableCreditMinor
    else -> account.balanceMinor ?: account.availableCreditMinor
}
