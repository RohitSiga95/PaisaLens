package com.paisalens.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.material.icons.automirrored.rounded.ReceiptLong
import androidx.compose.material.icons.rounded.AccountBalance
import androidx.compose.material.icons.rounded.AccountBalanceWallet
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.material.icons.rounded.ChevronLeft
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.CreditCard
import androidx.compose.material.icons.rounded.DashboardCustomize
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.PriorityHigh
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material.icons.rounded.TaskAlt
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
import androidx.compose.material3.TextButton
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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.paisalens.app.data.model.AccountProfile
import com.paisalens.app.data.model.AccountType
import com.paisalens.app.data.model.AdvancedBudgetPlan
import com.paisalens.app.data.model.BillReminder
import com.paisalens.app.data.model.BudgetHealth
import com.paisalens.app.data.model.CategoryBudget
import com.paisalens.app.data.model.CreditCardBill
import com.paisalens.app.data.model.CustomCategory
import com.paisalens.app.data.model.HomeBalanceFreshness
import com.paisalens.app.data.model.HomeBudgetPace
import com.paisalens.app.data.model.HomeCardHealth
import com.paisalens.app.data.model.HomeCardHealthItem
import com.paisalens.app.data.model.HomeDashboardDensity
import com.paisalens.app.data.model.HomeFinancialPulse
import com.paisalens.app.data.model.HomeHeroMetric
import com.paisalens.app.data.model.HomeLayoutConfiguration
import com.paisalens.app.data.model.HomeMoneyTimeline
import com.paisalens.app.data.model.HomeModule
import com.paisalens.app.data.model.HomeTimelineItem
import com.paisalens.app.data.model.HomeTimelineSource
import com.paisalens.app.data.model.LoanAccount
import com.paisalens.app.data.model.AttentionItem
import com.paisalens.app.data.model.AttentionPriority
import com.paisalens.app.data.model.NeedsAttentionSummary
import com.paisalens.app.data.model.PaymentCommitment
import com.paisalens.app.data.model.RecurringPayment
import com.paisalens.app.data.model.ReviewStatus
import com.paisalens.app.data.model.SavingsContribution
import com.paisalens.app.data.model.SavingsGoal
import com.paisalens.app.data.model.SpendingCategoryKey
import com.paisalens.app.data.model.SpendingCategoryTotal
import com.paisalens.app.data.model.TransactionRecord
import com.paisalens.app.data.model.TransactionLink
import com.paisalens.app.data.model.TransactionType
import com.paisalens.app.data.model.USER_ENTERED_UPI_BALANCE_SOURCE
import com.paisalens.app.data.model.calculateCreditUtilization
import com.paisalens.app.data.model.buildSpendingCategoryTotals
import com.paisalens.app.data.model.buildHomeDashboardSnapshot
import com.paisalens.app.data.model.homeDashboardAccountKey
import com.paisalens.app.data.model.transactionIdsAppliedAsExpenseOffsets
import com.paisalens.app.data.model.transactionIdsExcludedFromSpending
import com.paisalens.app.ui.components.CategoryIcon
import com.paisalens.app.ui.components.CustomCategoryIcon
import com.paisalens.app.ui.components.MoneyText
import com.paisalens.app.ui.components.PaisaCard
import com.paisalens.app.ui.components.SectionHeader
import com.paisalens.app.ui.components.SpendingDonut
import com.paisalens.app.ui.components.SpendingDonutSlice
import com.paisalens.app.ui.components.TransactionRow
import com.paisalens.app.ui.components.categoryColor
import com.paisalens.app.ui.components.customCategoryColor
import com.paisalens.app.ui.components.formatCompactMoney
import com.paisalens.app.ui.components.formatMoney
import com.paisalens.app.ui.privacy.PrivacyModeToggleButton
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale
import kotlinx.coroutines.delay
import kotlin.math.abs

@Composable
private fun rememberHomeNow(): ZonedDateTime {
    val lifecycleOwner = LocalLifecycleOwner.current
    var now by remember { mutableStateOf(ZonedDateTime.now()) }
    LaunchedEffect(lifecycleOwner) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                val current = ZonedDateTime.now()
                now = current
                delay(nextHomeClockRefreshDelayMillis(current))
            }
        }
    }
    return now
}

/** Refresh just after the next wall-clock minute, including midnight and month rollover. */
internal fun nextHomeClockRefreshDelayMillis(now: ZonedDateTime): Long {
    val nextMinute = now.withSecond(0).withNano(0).plusMinutes(1)
    return (Duration.between(now, nextMinute).toMillis() + HOME_CLOCK_SCHEDULING_CUSHION_MILLIS)
        .coerceIn(HOME_CLOCK_MIN_DELAY_MILLIS, HOME_CLOCK_MAX_DELAY_MILLIS)
}

private const val HOME_CLOCK_SCHEDULING_CUSHION_MILLIS = 100L
private const val HOME_CLOCK_MIN_DELAY_MILLIS = 250L
private const val HOME_CLOCK_MAX_DELAY_MILLIS = 60_100L

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    transactions: List<TransactionRecord>,
    effectiveExpenseTransactions: List<TransactionRecord>,
    transactionLinks: List<TransactionLink>,
    budgets: List<CategoryBudget>,
    advancedBudgets: List<AdvancedBudgetPlan>,
    bills: List<BillReminder>,
    recurringPayments: List<RecurringPayment>,
    loans: List<LoanAccount>,
    creditCardBills: List<CreditCardBill>,
    customCategories: List<CustomCategory>,
    accounts: List<AccountProfile>,
    homeLayout: HomeLayoutConfiguration,
    savingsGoals: List<SavingsGoal>,
    savingsContributions: List<SavingsContribution>,
    paymentCommitments: List<PaymentCommitment>,
    needsAttention: NeedsAttentionSummary,
    lastScanAt: Long,
    privacyModeActive: Boolean,
    isScanning: Boolean,
    hasSmsPermission: Boolean,
    onScan: () -> Unit,
    onRequestPermission: () -> Unit,
    onAdd: () -> Unit,
    onCustomizeHome: () -> Unit,
    onOpenSavingsGoals: () -> Unit,
    onOpenCommitments: () -> Unit,
    onOpenBudgets: () -> Unit,
    onOpenBills: () -> Unit,
    onOpenCreditCardBills: () -> Unit,
    onOpenWeeklyReview: () -> Unit,
    onTogglePrivacy: () -> Unit,
    onAttentionAction: (AttentionItem) -> Unit,
    onRefreshAccount: (AccountProfile) -> Unit,
    onCheckBalanceViaUpi: (AccountProfile, Set<Long>) -> Unit,
    onAccountClick: (AccountProfile) -> Unit,
    onTransactionClick: (TransactionRecord) -> Unit,
) {
    val now = rememberHomeNow()
    val currentMonth = remember(now) { YearMonth.from(now) }
    var selectedMonth by remember { mutableStateOf(currentMonth) }
    var lastObservedCurrentMonth by remember { mutableStateOf(currentMonth) }
    var selectedCategory by remember { mutableStateOf<SpendingCategoryKey?>(null) }
    var unavailableAccountsExpanded by remember { mutableStateOf(false) }
    LaunchedEffect(homeLayout, currentMonth) {
        if (currentMonth != lastObservedCurrentMonth && selectedMonth == lastObservedCurrentMonth) {
            selectedMonth = currentMonth
        }
        if (!homeLayout.isVisible(HomeModule.SPENDING_BREAKDOWN)) selectedMonth = currentMonth
        lastObservedCurrentMonth = currentMonth
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
    val categoryTotals = remember(monthlyExpenses, customCategories) {
        buildSpendingCategoryTotals(monthlyExpenses, customCategories)
    }
    val customCategoriesById = remember(customCategories) { customCategories.associateBy(CustomCategory::id) }
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
    val dashboardSnapshot = remember(
        transactions,
        effectiveExpenseTransactions,
        transactionLinks,
        accounts,
        budgets,
        advancedBudgets,
        bills,
        recurringPayments,
        loans,
        creditCardBills,
        paymentCommitments,
        savingsGoals,
        savingsContributions,
        now,
    ) {
        buildHomeDashboardSnapshot(
            transactions = transactions,
            effectiveExpenseTransactions = effectiveExpenseTransactions,
            transactionLinks = transactionLinks,
            accounts = accounts,
            legacyBudgets = budgets,
            advancedBudgets = advancedBudgets,
            manualBills = bills,
            recurringPayments = recurringPayments,
            loans = loans,
            creditCardBills = creditCardBills,
            paymentCommitments = paymentCommitments,
            savingsGoals = savingsGoals,
            savingsContributions = savingsContributions,
            now = now,
        )
    }
    val normalizedHomeLayout = homeLayout.normalized()

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 18.dp, top = 14.dp, end = 18.dp, bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(
            if (normalizedHomeLayout.density == HomeDashboardDensity.COMPACT) 14.dp else 18.dp,
        ),
    ) {
        item {
            HomeHeader(
                isScanning = isScanning,
                hasSmsPermission = hasSmsPermission,
                onScan = onScan,
                onRequestPermission = onRequestPermission,
                onAdd = onAdd,
                onCustomizeHome = onCustomizeHome,
                lastScanAt = lastScanAt,
                now = now,
                privacyModeActive = privacyModeActive,
                onTogglePrivacy = onTogglePrivacy,
            )
        }
        normalizedHomeLayout.orderedVisibleModules.forEach { module ->
            when (module) {
                HomeModule.FINANCIAL_PULSE -> item(key = module.storageId) {
                    FinancialPulseHomeModule(
                        pulse = dashboardSnapshot.pulse,
                        metric = normalizedHomeLayout.heroMetric,
                        density = normalizedHomeLayout.density,
                    )
                }
                HomeModule.NEEDS_ATTENTION -> item(key = module.storageId) {
                    NeedsAttentionHomeModule(
                        summary = needsAttention,
                        onOpenWeeklyReview = onOpenWeeklyReview,
                        onAction = onAttentionAction,
                    )
                }
                HomeModule.MONEY_TIMELINE -> item(key = module.storageId) {
                    MoneyTimelineHomeModule(
                        timeline = dashboardSnapshot.timeline,
                        density = normalizedHomeLayout.density,
                        onOpenBills = onOpenBills,
                        onOpenCommitments = onOpenCommitments,
                        onOpenCreditCardBills = onOpenCreditCardBills,
                    )
                }
                HomeModule.BUDGET_PACE -> item(key = module.storageId) {
                    BudgetPaceHomeModule(
                        pace = dashboardSnapshot.budgetPace,
                        onOpenBudgets = onOpenBudgets,
                    )
                }
                HomeModule.CARD_HEALTH -> item(key = module.storageId) {
                    CardHealthHomeModule(
                        health = dashboardSnapshot.cardHealth,
                        density = normalizedHomeLayout.density,
                        onOpenCreditCardBills = onOpenCreditCardBills,
                    )
                }
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
                        customCategoriesById = customCategoriesById,
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
                                onCheckViaUpi = if (group.profileCount > 1) null else {
                                    { onCheckBalanceViaUpi(group.account, group.accountIds) }
                                },
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
                .filter(category::matches)
                .sortedByDescending { it.occurredAt }
        }
        CategorySpendingSheet(
            category = category,
            customCategory = category.customCategoryId?.let(customCategoriesById::get),
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
private fun NeedsAttentionHomeModule(
    summary: NeedsAttentionSummary,
    onOpenWeeklyReview: () -> Unit,
    onAction: (AttentionItem) -> Unit,
) {
    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SectionHeader("Needs your attention", modifier = Modifier.weight(1f))
            TextButton(onClick = onOpenWeeklyReview) {
                Text("Weekly review")
                Spacer(Modifier.width(4.dp))
                Icon(Icons.Rounded.ChevronRight, contentDescription = null, modifier = Modifier.size(18.dp))
            }
        }
        Spacer(Modifier.height(8.dp))
        PaisaCard(Modifier.fillMaxWidth()) {
            if (summary.isClear) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(18.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(13.dp),
                ) {
                    Surface(
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.secondaryContainer,
                        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                    ) {
                        Icon(
                            Icons.Rounded.TaskAlt,
                            contentDescription = null,
                            modifier = Modifier.padding(10.dp).size(24.dp),
                        )
                    }
                    Column(Modifier.weight(1f)) {
                        Text("All caught up", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Text(
                            "No transactions, balances, bills, goals, or backups need action right now.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            } else {
                Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                    summary.items.take(3).forEach { item ->
                        Surface(
                            onClick = { onAction(item) },
                            modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp),
                            color = Color.Transparent,
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                Icon(
                                    imageVector = if (item.priority == AttentionPriority.URGENT) {
                                        Icons.Rounded.PriorityHigh
                                    } else {
                                        Icons.Rounded.TaskAlt
                                    },
                                    contentDescription = null,
                                    tint = if (item.priority == AttentionPriority.URGENT) {
                                        MaterialTheme.colorScheme.error
                                    } else {
                                        MaterialTheme.colorScheme.primary
                                    },
                                )
                                Column(Modifier.weight(1f)) {
                                    Text(item.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                                    Text(
                                        item.detail,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                                item.amountMinor?.let { MoneyText(it, style = MaterialTheme.typography.labelLarge) }
                                Icon(Icons.Rounded.ChevronRight, contentDescription = "Open ${item.title}")
                            }
                        }
                    }
                    if (summary.items.size > 3) {
                        TextButton(
                            onClick = onOpenWeeklyReview,
                            modifier = Modifier.align(Alignment.End).heightIn(min = 48.dp),
                        ) {
                            Text("View all ${summary.totalActionCount} actions")
                        }
                    }
                }
            }
        }
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
    lastScanAt: Long,
    now: ZonedDateTime,
    privacyModeActive: Boolean,
    onTogglePrivacy: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val stackActions = this.maxWidth < 350.dp
            if (stackActions) {
                Column(Modifier.fillMaxWidth()) {
                    Text(
                        text = "PaisaLens",
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold,
                    )
                    HomeHeaderActions(
                        modifier = Modifier.align(Alignment.End),
                        isScanning = isScanning,
                        hasSmsPermission = hasSmsPermission,
                        onScan = onScan,
                        onRequestPermission = onRequestPermission,
                        onAdd = onAdd,
                        onCustomizeHome = onCustomizeHome,
                        privacyModeActive = privacyModeActive,
                        onTogglePrivacy = onTogglePrivacy,
                    )
                }
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "PaisaLens",
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                    )
                    HomeHeaderActions(
                        isScanning = isScanning,
                        hasSmsPermission = hasSmsPermission,
                        onScan = onScan,
                        onRequestPermission = onRequestPermission,
                        onAdd = onAdd,
                        onCustomizeHome = onCustomizeHome,
                        privacyModeActive = privacyModeActive,
                        onTogglePrivacy = onTogglePrivacy,
                    )
                }
            }
        }
        Text(
            text = "Your private money overview · ${formatLastHomeSync(lastScanAt, now)}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun HomeHeaderActions(
    isScanning: Boolean,
    hasSmsPermission: Boolean,
    onScan: () -> Unit,
    onRequestPermission: () -> Unit,
    onAdd: () -> Unit,
    onCustomizeHome: () -> Unit,
    privacyModeActive: Boolean,
    onTogglePrivacy: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(modifier = modifier, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        HomeActionButton(contentDescription = "Customise Home", onClick = onCustomizeHome) {
            Icon(Icons.Rounded.DashboardCustomize, contentDescription = null)
        }
        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant,
            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            shape = CircleShape,
        ) {
            PrivacyModeToggleButton(
                active = privacyModeActive,
                onToggle = onTogglePrivacy,
            )
        }
        HomeActionButton(
            contentDescription = when {
                isScanning -> "Resyncing SMS"
                hasSmsPermission -> "Resync SMS"
                else -> "Enable SMS access"
            },
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
private fun FinancialPulseHomeModule(
    pulse: HomeFinancialPulse,
    metric: HomeHeroMetric,
    density: HomeDashboardDensity,
) {
    val primary = MaterialTheme.colorScheme.primaryContainer
    val secondary = MaterialTheme.colorScheme.secondaryContainer
    val tertiary = MaterialTheme.colorScheme.tertiaryContainer
    val foreground = MaterialTheme.colorScheme.onPrimaryContainer
    val dateFormatter = remember { DateTimeFormatter.ofPattern("d MMM", Locale.forLanguageTag("en-IN")) }
    val (heroLabel, heroAmount) = when (metric) {
        HomeHeroMetric.SAFE_TO_SPEND -> "SAFE TO SPEND" to pulse.safeToSpendMinor
        HomeHeroMetric.AVAILABLE_CASH -> "AVAILABLE CASH" to pulse.availableCashMinor
        HomeHeroMetric.MONTHLY_SPEND -> "SPENT THIS MONTH" to pulse.monthlySpendMinor
    }
    val heroDescription = buildString {
        append(heroLabel.lowercase(Locale.ENGLISH).replaceFirstChar(Char::titlecase))
        append(" ${heroAmount?.let(::formatMoney) ?: "not available"}. ")
        append("Balances ${balanceFreshnessLabel(pulse.balanceFreshness).lowercase(Locale.ENGLISH)}. ")
        append("${formatMoney(pulse.upcomingObligationsMinor)} scheduled through ${pulse.throughDate.format(dateFormatter)}.")
    }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .semantics { contentDescription = heroDescription },
        shape = MaterialTheme.shapes.extraLarge,
        color = Color.Transparent,
    ) {
        Box(
            modifier = Modifier.background(
                Brush.linearGradient(listOf(primary, tertiary, secondary)),
            ),
        ) {
            Canvas(Modifier.matchParentSize()) {
                drawCircle(
                    color = foreground.copy(alpha = 0.07f),
                    radius = size.minDimension * 0.44f,
                    center = Offset(size.width * 0.96f, size.height * 0.02f),
                )
            }
            Column(
                modifier = Modifier.padding(
                    horizontal = 20.dp,
                    vertical = if (density == HomeDashboardDensity.COMPACT) 18.dp else 22.dp,
                ),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            heroLabel,
                            style = MaterialTheme.typography.labelMedium,
                            color = foreground.copy(alpha = 0.82f),
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            if (metric == HomeHeroMetric.SAFE_TO_SPEND) {
                                "Through ${pulse.throughDate.format(dateFormatter)} · on-device estimate"
                            } else {
                                "Current snapshot · on-device"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = foreground.copy(alpha = 0.74f),
                        )
                    }
                    Surface(
                        color = foreground.copy(alpha = 0.12f),
                        contentColor = foreground,
                        shape = CircleShape,
                    ) {
                        Text(
                            balanceFreshnessLabel(pulse.balanceFreshness),
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
                if (heroAmount == null) {
                    Text(
                        "Update a balance",
                        style = MaterialTheme.typography.headlineLarge,
                        color = foreground,
                        fontWeight = FontWeight.Bold,
                    )
                } else {
                    MoneyText(
                        amountMinor = heroAmount,
                        style = MaterialTheme.typography.displayMedium,
                        color = foreground,
                    )
                }
                Spacer(Modifier.height(if (density == HomeDashboardDensity.COMPACT) 14.dp else 18.dp))
                PulseMetricRow(
                    firstLabel = "Available cash",
                    firstAmount = pulse.availableCashMinor,
                    secondLabel = "Scheduled",
                    secondAmount = pulse.upcomingObligationsMinor,
                    foreground = foreground,
                )
                Spacer(Modifier.height(8.dp))
                PulseMetricRow(
                    firstLabel = "Goal reserve",
                    firstAmount = pulse.goalReserveMinor,
                    secondLabel = "Safety buffer",
                    secondAmount = pulse.safetyBufferMinor,
                    foreground = foreground,
                )
                Spacer(Modifier.height(12.dp))
                pulse.assumptions.take(if (density == HomeDashboardDensity.COMPACT) 2 else 3).forEach { assumption ->
                    Text(
                        text = "• $assumption",
                        style = MaterialTheme.typography.bodySmall,
                        color = foreground.copy(alpha = 0.78f),
                    )
                }
            }
        }
    }
}

@Composable
private fun PulseMetricRow(
    firstLabel: String,
    firstAmount: Long?,
    secondLabel: String,
    secondAmount: Long?,
    foreground: Color,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        PulseMetric(firstLabel, firstAmount, foreground, Modifier.weight(1f))
        PulseMetric(secondLabel, secondAmount, foreground, Modifier.weight(1f))
    }
}

@Composable
private fun PulseMetric(
    label: String,
    amountMinor: Long?,
    foreground: Color,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        color = foreground.copy(alpha = 0.10f),
        contentColor = foreground,
        shape = MaterialTheme.shapes.medium,
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
            Text(label, style = MaterialTheme.typography.labelSmall, color = foreground.copy(alpha = 0.74f))
            Text(
                amountMinor?.let(::formatMoney) ?: "Not available",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun MoneyTimelineHomeModule(
    timeline: HomeMoneyTimeline,
    density: HomeDashboardDensity,
    onOpenBills: () -> Unit,
    onOpenCommitments: () -> Unit,
    onOpenCreditCardBills: () -> Unit,
) {
    val dateFormatter = remember { DateTimeFormatter.ofPattern("EEE, d MMM", Locale.forLanguageTag("en-IN")) }
    Column {
        SectionHeader("Next 14 days")
        Spacer(Modifier.height(8.dp))
        PaisaCard(Modifier.fillMaxWidth()) {
            if (timeline.items.isEmpty()) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(18.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Rounded.CalendarMonth, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Column(Modifier.weight(1f)) {
                        Text("No scheduled money movement", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "Add bills, card statements, EMIs, subscriptions, or AutoPay mandates to build this timeline.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            } else {
                val shown = timeline.items.take(if (density == HomeDashboardDensity.COMPACT) 4 else 6)
                shown.forEachIndexed { index, item ->
                    TimelineItemRow(
                        item = item,
                        dateLabel = if (item.date.isBefore(timeline.startDate)) {
                            "Overdue · ${item.date.format(dateFormatter)}"
                        } else {
                            item.date.format(dateFormatter)
                        },
                        onClick = when (item.source) {
                            HomeTimelineSource.BILL, HomeTimelineSource.LOAN_EMI -> onOpenBills
                            HomeTimelineSource.RECURRING_PAYMENT,
                            HomeTimelineSource.PAYMENT_COMMITMENT,
                            -> onOpenCommitments
                            HomeTimelineSource.CREDIT_CARD_BILL -> onOpenCreditCardBills
                            HomeTimelineSource.EXPECTED_INCOME -> null
                        },
                    )
                    if (index != shown.lastIndex) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column {
                        Text(
                            "${formatMoney(timeline.outgoingMinor)} out",
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            "${formatMoney(timeline.incomingMinor)} expected in",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (timeline.items.size > shown.size) {
                        Text(
                            "+${timeline.items.size - shown.size} more",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun TimelineItemRow(
    item: HomeTimelineItem,
    dateLabel: String,
    onClick: (() -> Unit)?,
) {
    val content: @Composable () -> Unit = {
        Row(
            modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(horizontal = 16.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Surface(
                color = if (item.isIncoming) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                contentColor = if (item.isIncoming) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                shape = CircleShape,
            ) {
                Icon(
                    imageVector = when (item.source) {
                        HomeTimelineSource.EXPECTED_INCOME -> Icons.Rounded.ArrowDownward
                        HomeTimelineSource.CREDIT_CARD_BILL -> Icons.Rounded.CreditCard
                        HomeTimelineSource.BILL -> Icons.AutoMirrored.Rounded.ReceiptLong
                        HomeTimelineSource.LOAN_EMI -> Icons.Rounded.AccountBalance
                        HomeTimelineSource.RECURRING_PAYMENT,
                        HomeTimelineSource.PAYMENT_COMMITMENT,
                        -> Icons.Rounded.ArrowUpward
                    },
                    contentDescription = null,
                    modifier = Modifier.padding(9.dp).size(20.dp),
                )
            }
            Column(Modifier.weight(1f)) {
                Text(item.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Text(
                    buildString {
                        append(dateLabel)
                        if (item.isEstimate) append(" · Expected from past deposits")
                        item.accountName?.takeIf(String::isNotBlank)?.let { append(" · $it") }
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                text = (if (item.isIncoming) "+" else "−") + formatMoney(item.amountMinor),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
            )
            if (onClick != null) Icon(Icons.Rounded.ChevronRight, contentDescription = "Open ${item.title}")
        }
    }
    if (onClick != null) {
        Surface(onClick = onClick, modifier = Modifier.fillMaxWidth(), color = Color.Transparent) { content() }
    } else {
        content()
    }
}

@Composable
private fun BudgetPaceHomeModule(
    pace: HomeBudgetPace?,
    onOpenBudgets: () -> Unit,
) {
    Column {
        SectionHeader("Budget pace", action = "Manage", onAction = onOpenBudgets)
        Spacer(Modifier.height(8.dp))
        PaisaCard(Modifier.fillMaxWidth()) {
            if (pace == null) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("No active budget", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Create a calendar-month or payday budget to compare time elapsed with spending pace.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.Top,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                if (pace.actualVsPlannedMinor > 0) "Spending is ahead of pace" else "Spending is on pace",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                            )
                            Text(
                                "${pace.planCount} ${if (pace.planCount == 1) "budget" else "budgets"} · " +
                                    if (pace.usesAdvancedPlans) "Budgeting 2.0" else "Monthly budgets",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Surface(
                            color = budgetHealthContainerColor(pace.health),
                            contentColor = budgetHealthContentColor(pace.health),
                            shape = CircleShape,
                        ) {
                            Text(
                                budgetHealthLabel(pace.health),
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                            )
                        }
                    }
                    LinearProgressIndicator(
                        progress = { (pace.spentBasisPoints / 10_000f).coerceIn(0f, 1f) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(8.dp)
                            .semantics {
                                contentDescription = "Budget ${basisPointsLabel(pace.spentBasisPoints)} spent; " +
                                    "${basisPointsLabel(pace.periodElapsedBasisPoints)} of the period elapsed"
                            },
                        color = if (pace.health == BudgetHealth.EXCEEDED) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                        trackColor = MaterialTheme.colorScheme.surfaceVariant,
                        strokeCap = StrokeCap.Round,
                    )
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(
                            "${basisPointsLabel(pace.periodElapsedBasisPoints)} time elapsed",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            "${basisPointsLabel(pace.spentBasisPoints)} spent",
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        BudgetMetric("Remaining", pace.remainingMinor, Modifier.weight(1f))
                        BudgetMetric(
                            if (pace.actualVsPlannedMinor > 0) "Ahead by" else "Under pace",
                            abs(pace.actualVsPlannedMinor),
                            Modifier.weight(1f),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun BudgetMetric(label: String, amountMinor: Long, modifier: Modifier = Modifier) {
    Surface(modifier = modifier, color = MaterialTheme.colorScheme.surfaceVariant, shape = MaterialTheme.shapes.medium) {
        Column(Modifier.padding(12.dp)) {
            Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                formatMoney(amountMinor),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun CardHealthHomeModule(
    health: HomeCardHealth,
    density: HomeDashboardDensity,
    onOpenCreditCardBills: () -> Unit,
) {
    val dateFormatter = remember { DateTimeFormatter.ofPattern("d MMM", Locale.forLanguageTag("en-IN")) }
    Column {
        SectionHeader("Card health", action = "Details", onAction = onOpenCreditCardBills)
        Spacer(Modifier.height(8.dp))
        PaisaCard(Modifier.fillMaxWidth()) {
            if (health.cards.isEmpty()) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("No credit cards detected", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Card balances and statement dues will appear here after an SMS is scanned or a card is added.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                Column(Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(18.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        CardSummaryMetric("Total due", health.totalDueMinor, Modifier.weight(1f))
                        CardSummaryMetric("Credit available", health.totalAvailableCreditMinor, Modifier.weight(1f))
                    }
                    if (health.highUtilizationCount > 0) {
                        Text(
                            "${health.highUtilizationCount} ${if (health.highUtilizationCount == 1) "card has" else "cards have"} high utilisation",
                            modifier = Modifier.padding(horizontal = 18.dp, vertical = 2.dp),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                    health.cards.take(if (density == HomeDashboardDensity.COMPACT) 3 else 4).forEach { card ->
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        CardHealthRow(card, dateFormatter, onOpenCreditCardBills)
                    }
                }
            }
        }
    }
}

@Composable
private fun CardSummaryMetric(label: String, amountMinor: Long?, modifier: Modifier = Modifier) {
    Surface(modifier = modifier, color = MaterialTheme.colorScheme.surfaceVariant, shape = MaterialTheme.shapes.medium) {
        Column(Modifier.padding(12.dp)) {
            Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                amountMinor?.let(::formatMoney) ?: "Not available",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun CardHealthRow(
    card: HomeCardHealthItem,
    dateFormatter: DateTimeFormatter,
    onClick: () -> Unit,
) {
    Surface(onClick = onClick, modifier = Modifier.fillMaxWidth(), color = Color.Transparent) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 13.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(card.name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    Text(
                        buildString {
                            card.accountHint?.let { append("•••• ${it.takeLast(4)}") }
                            card.dueDate?.let {
                                if (isNotEmpty()) append(" · ")
                                append("Due ${it.format(dateFormatter)}")
                            }
                        }.ifBlank { "No current statement due" },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                card.totalDueMinor?.let {
                    Text(formatMoney(it), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                }
                Spacer(Modifier.width(4.dp))
                Icon(Icons.Rounded.ChevronRight, contentDescription = "Open ${card.name} card health")
            }
            card.utilizationBasisPoints?.let { utilization ->
                Spacer(Modifier.height(9.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Utilisation", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(basisPointsLabel(utilization), style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
                }
                Spacer(Modifier.height(4.dp))
                LinearProgressIndicator(
                    progress = { (utilization / 10_000f).coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth().height(6.dp),
                    color = when (card.utilizationBand) {
                        com.paisalens.app.data.model.CreditUtilizationBand.CRITICAL,
                        com.paisalens.app.data.model.CreditUtilizationBand.HIGH,
                        -> MaterialTheme.colorScheme.error
                        else -> MaterialTheme.colorScheme.primary
                    },
                    trackColor = MaterialTheme.colorScheme.surfaceVariant,
                    strokeCap = StrokeCap.Round,
                )
            }
        }
    }
}

@Composable
private fun budgetHealthContainerColor(health: BudgetHealth): Color = when (health) {
    BudgetHealth.EXCEEDED -> MaterialTheme.colorScheme.errorContainer
    BudgetHealth.WARNING -> MaterialTheme.colorScheme.tertiaryContainer
    else -> MaterialTheme.colorScheme.secondaryContainer
}

@Composable
private fun budgetHealthContentColor(health: BudgetHealth): Color = when (health) {
    BudgetHealth.EXCEEDED -> MaterialTheme.colorScheme.onErrorContainer
    BudgetHealth.WARNING -> MaterialTheme.colorScheme.onTertiaryContainer
    else -> MaterialTheme.colorScheme.onSecondaryContainer
}

private fun budgetHealthLabel(health: BudgetHealth): String = when (health) {
    BudgetHealth.EXCEEDED -> "Exceeded"
    BudgetHealth.WARNING -> "Watch pace"
    BudgetHealth.ON_TRACK -> "On track"
    BudgetHealth.NOT_STARTED -> "Not started"
    BudgetHealth.ENDED -> "Ended"
}

private fun basisPointsLabel(basisPoints: Int): String = String.format(
    Locale.US,
    if (basisPoints % 100 == 0) "%.0f%%" else "%.1f%%",
    basisPoints / 100.0,
)

private fun balanceFreshnessLabel(freshness: HomeBalanceFreshness): String = when (freshness) {
    HomeBalanceFreshness.FRESH -> "Balances fresh"
    HomeBalanceFreshness.AGING -> "Balances aging"
    HomeBalanceFreshness.STALE -> "Balances stale"
    HomeBalanceFreshness.PARTIAL -> "Partial balances"
    HomeBalanceFreshness.UNAVAILABLE -> "No balances"
}

private fun formatLastHomeSync(lastScanAt: Long, now: ZonedDateTime): String {
    if (lastScanAt <= 0) return "SMS not synced yet"
    val scanTime = Instant.ofEpochMilli(lastScanAt).atZone(now.zone)
    val minutes = ChronoUnit.MINUTES.between(scanTime, now).coerceAtLeast(0)
    return when {
        minutes < 2 -> "SMS synced just now"
        minutes < 60 -> "SMS synced ${minutes}m ago"
        minutes < 24 * 60 -> "SMS synced ${minutes / 60}h ago"
        else -> "SMS synced ${scanTime.format(DateTimeFormatter.ofPattern("d MMM", Locale.forLanguageTag("en-IN")))}"
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
    categoryTotals: List<SpendingCategoryTotal>,
    customCategoriesById: Map<Long, CustomCategory>,
    expenseTotal: Long,
    month: YearMonth,
    canGoPrevious: Boolean,
    canGoNext: Boolean,
    onPreviousMonth: () -> Unit,
    onNextMonth: () -> Unit,
    onCategoryClick: (SpendingCategoryKey) -> Unit,
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
                    SpendingDonut(
                        values = categoryTotals.take(5).map { total ->
                            val custom = total.key.customCategoryId?.let(customCategoriesById::get)
                            SpendingDonutSlice(
                                label = total.key.label,
                                valueMinor = total.amountMinor,
                                color = custom?.let { customCategoryColor(it.colorHex) }
                                    ?: categoryColor(total.key.builtIn),
                            )
                        },
                        totalMinor = expenseTotal,
                        periodLabel = month.format(DateTimeFormatter.ofPattern("MMM yyyy", Locale.ENGLISH)),
                    )
                    Spacer(Modifier.height(14.dp))
                    Text(
                        text = "Tap a category to see its daily trend and expenses",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                    Spacer(Modifier.height(6.dp))
                    categoryTotals.forEach { total ->
                        CategoryLegend(
                            category = total.key,
                            customCategory = total.key.customCategoryId?.let(customCategoriesById::get),
                            amountMinor = total.amountMinor,
                            totalMinor = expenseTotal,
                            onClick = { onCategoryClick(total.key) },
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
    category: SpendingCategoryKey,
    customCategory: CustomCategory?,
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
            if (customCategory != null) {
                CustomCategoryIcon(customCategory, modifier = Modifier.size(40.dp), iconSize = 19)
            } else {
                CategoryIcon(category.builtIn, modifier = Modifier.size(40.dp), iconSize = 19)
            }
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
    category: SpendingCategoryKey,
    customCategory: CustomCategory?,
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
                    if (customCategory != null) {
                        CustomCategoryIcon(customCategory, modifier = Modifier.size(48.dp), iconSize = 22)
                    } else {
                        CategoryIcon(category = category.builtIn, modifier = Modifier.size(48.dp), iconSize = 22)
                    }
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
                    Text(formatAxisMoney(0), style = MaterialTheme.typography.labelSmall)
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

private fun formatAxisMoney(amountMinor: Long): String = formatCompactMoney(amountMinor)

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
    val isPartialMergedValue = account.mergedMemberCount > 1 &&
        valueMinor != null && account.availabilityFetchedAt == null
    val utilization = if (account.type == AccountType.CREDIT_CARD && !isPartialMergedValue) {
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
                Text(
                    if (isPartialMergedValue) "$valueLabel · partial" else valueLabel,
                    style = MaterialTheme.typography.labelLarge,
                    color = style.foreground.copy(alpha = 0.76f),
                )
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
                        text = if (isPartialMergedValue) {
                            "Some merged sources do not have a current value"
                        } else {
                            account.availabilityFetchedAt?.let {
                                val action = if (
                                    account.availabilitySender?.startsWith(USER_ENTERED_UPI_BALANCE_SOURCE) == true
                                ) {
                                    "Entered"
                                } else {
                                    "Fetched"
                                }
                                "$action ${formatAvailabilityTime(it)}"
                            } ?: "No balance saved yet"
                        },
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
                                onCheckViaUpi = if (group.profileCount > 1) null else {
                                    { onCheckBalanceViaUpi(group.account, group.accountIds) }
                                },
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
    .groupBy(::homeDashboardAccountKey)
    .map { (key, matches) ->
        val preferred = matches.maxWithOrNull(
            compareBy<AccountProfile> { if (availabilityValue(it, type) != null) 1 else 0 }
                .thenBy { it.availabilityFetchedAt ?: Long.MIN_VALUE },
        ) ?: matches.first()
        val lastFour = matches.firstNotNullOfOrNull(::accountLastFour)
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
            profileCount = matches.sumOf { it.mergedMemberCount.coerceAtLeast(1) },
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
