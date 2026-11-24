package com.paisalens.app.ui

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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.AutoGraph
import androidx.compose.material.icons.rounded.Calculate
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Event
import androidx.compose.material.icons.rounded.Payments
import androidx.compose.material.icons.rounded.Savings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.paisalens.app.data.model.AccountBalanceSnapshot
import com.paisalens.app.data.model.AccountProfile
import com.paisalens.app.data.model.AccountType
import com.paisalens.app.data.model.BillReminder
import com.paisalens.app.data.model.BillsTimelineSnapshot
import com.paisalens.app.data.model.AdvancedBudgetPlan
import com.paisalens.app.data.model.CreditCardBill
import com.paisalens.app.data.model.CreditCardPaymentSuggestion
import com.paisalens.app.data.model.CategoryBudget
import com.paisalens.app.data.model.CustomCategory
import com.paisalens.app.data.model.ExpenseCategory
import com.paisalens.app.data.model.ExpenseSplit
import com.paisalens.app.data.model.LoanAccount
import com.paisalens.app.data.model.NetWorthItem
import com.paisalens.app.data.model.NetWorthKind
import com.paisalens.app.data.model.PaymentCommitment
import com.paisalens.app.data.model.PaymentReminderRule
import com.paisalens.app.data.model.PaymentReminderSettings
import com.paisalens.app.data.model.PaymentReminderTarget
import com.paisalens.app.data.model.PaymentReminderTargetType
import com.paisalens.app.data.model.RecurringPayment
import com.paisalens.app.data.model.SavingsContribution
import com.paisalens.app.data.model.SavingsGoal
import com.paisalens.app.data.model.ReviewStatus
import com.paisalens.app.data.model.SpendingInsight
import com.paisalens.app.data.model.TransactionLink
import com.paisalens.app.data.model.TransactionRecord
import com.paisalens.app.data.model.TransactionType
import com.paisalens.app.data.model.UtilitySmsBill
import com.paisalens.app.data.model.WhatIfScenario
import com.paisalens.app.data.model.buildCashFlowForecast
import com.paisalens.app.data.model.buildDueItems
import com.paisalens.app.data.model.buildPaymentCommitmentDueItems
import com.paisalens.app.data.model.buildEffectiveExpenseTransactions
import com.paisalens.app.data.model.buildNetWorthSummary
import com.paisalens.app.data.model.manualBillReminderTargetKey
import com.paisalens.app.data.model.simulateWhatIfMonthly
import com.paisalens.app.data.model.normalizedMerchantKey
import com.paisalens.app.data.model.paymentCommitmentIdentityKey
import com.paisalens.app.data.model.recurringPaymentIdentityKey
import com.paisalens.app.data.model.transactionIdsAppliedAsExpenseOffsets
import com.paisalens.app.notification.consolidatedCashFlowOpeningBalance
import com.paisalens.app.ui.components.MoneyChartPoint
import com.paisalens.app.ui.components.MoneyLineChart
import com.paisalens.app.ui.components.MoneyText
import com.paisalens.app.ui.components.PaisaCard
import com.paisalens.app.ui.components.dashboardWidth
import com.paisalens.app.ui.components.formatMoney
import com.paisalens.app.ui.screens.AdvancedBudgetingScreen
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.abs

enum class PlanSection(val label: String) {
    BUDGETS("Budgets"),
    BILLS("Bills & dues"),
    GOALS("Goals"),
    AUTOPAY("AutoPay"),
    REMINDERS("Reminders"),
    CARD_BILLS("Card bills"),
    WHAT_IF("What-if"),
}

enum class InsightSection(val label: String) {
    OVERVIEW("Overview"),
    CASH_FLOW("Cash flow"),
    NET_WORTH("Net worth"),
}

@Composable
fun PlanningScreen(
    initialSection: PlanSection = PlanSection.BUDGETS,
    transactions: List<TransactionRecord>,
    transactionLinks: List<TransactionLink>,
    expenseSplits: List<ExpenseSplit>,
    budgets: List<CategoryBudget>,
    advancedBudgets: List<AdvancedBudgetPlan>,
    customCategories: List<CustomCategory>,
    bills: List<BillReminder>,
    billsTimeline: BillsTimelineSnapshot,
    recurringPayments: List<RecurringPayment>,
    loans: List<LoanAccount>,
    accounts: List<AccountProfile>,
    savingsGoals: List<SavingsGoal>,
    savingsContributions: List<SavingsContribution>,
    paymentCommitments: List<PaymentCommitment>,
    paymentCommitmentSuggestions: List<PaymentCommitment>,
    paymentReminderRules: List<PaymentReminderRule> = emptyList(),
    paymentReminderSettings: PaymentReminderSettings = PaymentReminderSettings(),
    hasPaymentReminderPermission: Boolean = false,
    onSetBudget: (ExpenseCategory, Long) -> Unit,
    onSaveAdvancedBudget: (AdvancedBudgetPlan) -> Unit,
    onDeleteAdvancedBudget: (Long) -> Unit,
    onSaveBill: (BillReminder) -> Unit,
    onMarkBillPaid: (Long) -> Unit,
    onDeleteBill: (Long) -> Unit,
    onAddSavingsGoal: () -> Unit,
    onEditSavingsGoal: (SavingsGoal) -> Unit,
    onDeleteSavingsGoal: (SavingsGoal) -> Unit,
    onContributeSavingsGoal: (SavingsGoal) -> Unit,
    onSaveSavingsContribution: (SavingsContribution) -> Unit,
    onDeleteSavingsContribution: (SavingsContribution) -> Unit,
    onAddPaymentCommitment: () -> Unit,
    onEditPaymentCommitment: (PaymentCommitment) -> Unit,
    onUpdatePaymentCommitment: (PaymentCommitment) -> Unit,
    onDeletePaymentCommitment: (PaymentCommitment) -> Unit,
    onAcceptPaymentSuggestion: (PaymentCommitment) -> Unit,
    onRequestPaymentReminderPermission: () -> Unit = {},
    onOpenPaymentReminderSettings: () -> Unit = {},
    onSavePaymentReminderRule: suspend (PaymentReminderRule) -> Unit = {},
    onDeletePaymentReminderRule: suspend (Long) -> Unit = {},
    onSnoozePaymentReminder: suspend (
        ruleId: Long,
        occurrenceEpochDay: Long,
        minutes: Int,
        expectedGeneration: Long,
    ) -> Unit = { _, _, _, _ -> },
    onClearPaymentReminderSnooze: suspend (ruleId: Long, expectedGeneration: Long) -> Unit = { _, _ -> },
    onSkipNextPaymentReminder: suspend (
        ruleId: Long,
        occurrenceEpochDay: Long,
        expectedGeneration: Long,
    ) -> Unit = { _, _, _ -> },
    onPaymentReminderSettingsChange: suspend (PaymentReminderSettings) -> Unit = {},
    creditCardBills: List<CreditCardBill> = emptyList(),
    onMarkCreditCardBillPaid: (Long) -> Unit = {},
    onAssignCreditCardBill: (billId: Long, accountId: Long) -> Unit = { _, _ -> },
    creditCardPaymentSuggestions: List<CreditCardPaymentSuggestion> = emptyList(),
    onConfirmCreditCardPayment: (CreditCardPaymentSuggestion) -> Unit = {},
    onDismissCreditCardPayment: (String) -> Unit = {},
    onRemoveCreditCardPaymentMatch: (String, Long) -> Unit = { _, _ -> },
    utilitySmsBills: List<UtilitySmsBill> = emptyList(),
    onTrackUtilitySmsBill: (UtilitySmsBill, (Long?) -> Unit) -> Unit = { _, done -> done(null) },
    onDismissUtilitySmsBill: (Long) -> Unit = {},
) {
    var sectionName by rememberSaveable { mutableStateOf(initialSection.name) }
    val section = PlanSection.entries.firstOrNull { it.name == sectionName } ?: PlanSection.BUDGETS
    LaunchedEffect(initialSection) { sectionName = initialSection.name }
    var editingBill by remember { mutableStateOf<BillReminder?>(null) }
    var addingBill by remember { mutableStateOf(false) }
    var requestedReminderTargetKey by rememberSaveable { mutableStateOf<String?>(null) }
    fun openBillReminder(targetKey: String) {
        requestedReminderTargetKey = targetKey
        sectionName = PlanSection.REMINDERS.name
    }
    val effectiveExpenseTransactions = remember(transactions, transactionLinks, expenseSplits) {
        buildEffectiveExpenseTransactions(transactions, transactionLinks, expenseSplits)
    }
    val budgetTransactions = remember(transactions, transactionLinks, effectiveExpenseTransactions) {
        val appliedOffsets = transactionIdsAppliedAsExpenseOffsets(transactions, transactionLinks)
        effectiveExpenseTransactions + transactions.filter {
            it.type == TransactionType.REFUND &&
                it.reviewStatus == ReviewStatus.CONFIRMED &&
                it.id !in appliedOffsets
        }
    }
    Column(Modifier.dashboardWidth(920.dp).fillMaxSize()) {
        HubHeader("Plan", "Budgets, due dates, and planned payments")
        HubSectionPicker(PlanSection.entries, section, { it.label }) { sectionName = it.name }
        Box(Modifier.fillMaxSize()) {
            when (section) {
                PlanSection.BUDGETS -> AdvancedBudgetingScreen(
                    transactions = budgetTransactions,
                    plans = advancedBudgets,
                    customCategories = customCategories,
                    legacyBudgets = budgets,
                    onSavePlan = onSaveAdvancedBudget,
                    onDeletePlan = onDeleteAdvancedBudget,
                    onSetLegacyBudget = onSetBudget,
                )
                PlanSection.BILLS -> BillsTimelineContent(
                    timeline = billsTimeline,
                    bills = bills,
                    utilitySmsBills = utilitySmsBills,
                    paymentReminderRules = paymentReminderRules,
                    onAdd = { addingBill = true },
                    onEdit = { editingBill = it },
                    onMarkPaid = onMarkBillPaid,
                    onDelete = onDeleteBill,
                    onTrackUtilitySmsBill = onTrackUtilitySmsBill,
                    onDismissUtilitySmsBill = onDismissUtilitySmsBill,
                    onSetReminder = ::openBillReminder,
                    onMarkCardPaid = onMarkCreditCardBillPaid,
                    onOpenCardBills = { sectionName = PlanSection.CARD_BILLS.name },
                    onOpenCommitments = { sectionName = PlanSection.AUTOPAY.name },
                )
                PlanSection.REMINDERS -> PaymentReminderCenterContent(
                    rules = paymentReminderRules,
                    settings = paymentReminderSettings,
                    manualBills = bills,
                    creditCardBills = creditCardBills,
                    accounts = accounts,
                    hasNotificationPermission = hasPaymentReminderPermission,
                    onRequestNotificationPermission = onRequestPaymentReminderPermission,
                    onOpenSystemNotificationSettings = onOpenPaymentReminderSettings,
                    onAddManualBill = { addingBill = true },
                    onOpenTarget = { target: PaymentReminderTarget ->
                        sectionName = when (target.targetType) {
                            PaymentReminderTargetType.MANUAL_BILL -> PlanSection.BILLS.name
                            PaymentReminderTargetType.CREDIT_CARD -> PlanSection.CARD_BILLS.name
                        }
                    },
                    onSaveRule = onSavePaymentReminderRule,
                    onDeleteRule = onDeletePaymentReminderRule,
                    onSnooze = onSnoozePaymentReminder,
                    onClearSnooze = onClearPaymentReminderSnooze,
                    onSkipNext = onSkipNextPaymentReminder,
                    onSettingsChange = onPaymentReminderSettingsChange,
                    requestedTargetKey = requestedReminderTargetKey,
                    onReminderRequestHandled = { requestedReminderTargetKey = null },
                    modifier = Modifier.fillMaxSize(),
                )
                PlanSection.CARD_BILLS -> CreditCardBillsDashboardContent(
                    bills = creditCardBills,
                    transactions = effectiveExpenseTransactions,
                    accounts = accounts,
                    onMarkPaid = onMarkCreditCardBillPaid,
                    onAssignBill = onAssignCreditCardBill,
                    paymentSuggestions = creditCardPaymentSuggestions,
                    onConfirmPayment = onConfirmCreditCardPayment,
                    onDismissPayment = onDismissCreditCardPayment,
                    onRemoveMatch = onRemoveCreditCardPaymentMatch,
                )
                PlanSection.GOALS -> SavingsGoalsCenterContent(
                    goals = savingsGoals,
                    contributions = savingsContributions,
                    accounts = accounts,
                    onAddGoal = onAddSavingsGoal,
                    onEditGoal = onEditSavingsGoal,
                    onDeleteGoal = onDeleteSavingsGoal,
                    onContribute = onContributeSavingsGoal,
                    onSaveContribution = onSaveSavingsContribution,
                    onDeleteContribution = onDeleteSavingsContribution,
                    modifier = Modifier.fillMaxSize(),
                )
                PlanSection.AUTOPAY -> SubscriptionAutopayCenterContent(
                    commitments = paymentCommitments,
                    detectedSuggestions = paymentCommitmentSuggestions,
                    accounts = accounts,
                    onAddCommitment = onAddPaymentCommitment,
                    onEditCommitment = onEditPaymentCommitment,
                    onUpdateCommitment = onUpdatePaymentCommitment,
                    onDeleteCommitment = onDeletePaymentCommitment,
                    onAcceptSuggestion = onAcceptPaymentSuggestion,
                    modifier = Modifier.fillMaxSize(),
                )
                PlanSection.WHAT_IF -> WhatIfContent(
                    transactions = transactions,
                    transactionLinks = transactionLinks,
                    effectiveExpenseTransactions = effectiveExpenseTransactions,
                    accounts = accounts,
                    recurringPayments = recurringPayments,
                    loans = loans,
                )
            }
        }
    }
    if (addingBill || editingBill != null) {
        BillEditorDialog(
            existing = editingBill,
            accounts = accounts,
            onDismiss = { addingBill = false; editingBill = null },
            onSave = {
                onSaveBill(it)
                addingBill = false
                editingBill = null
            },
        )
    }
}


@Composable
private fun BillEditorDialog(
    existing: BillReminder?,
    accounts: List<AccountProfile>,
    onDismiss: () -> Unit,
    onSave: (BillReminder) -> Unit,
) {
    var title by remember(existing) { mutableStateOf(existing?.title.orEmpty()) }
    var amount by remember(existing) { mutableStateOf(existing?.amountMinor?.let(::minorToInput).orEmpty()) }
    var dueDate by remember(existing) {
        mutableStateOf(existing?.let { LocalDate.ofEpochDay(it.dueDateEpochDay).toString() } ?: LocalDate.now().plusDays(7).toString())
    }
    var recurrence by remember(existing) { mutableIntStateOf(existing?.recurrenceMonths ?: 1) }
    var accountId by remember(existing) { mutableStateOf(existing?.accountId) }
    var notes by remember(existing) { mutableStateOf(existing?.notes.orEmpty()) }
    val parsedAmount = amount.toMinorOrNull()
    val parsedDate = runCatching { LocalDate.parse(dueDate) }.getOrNull()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (existing == null) "Add bill" else "Edit bill") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(title, { title = it.take(64) }, label = { Text("Bill name") }, singleLine = true)
                OutlinedTextField(
                    amount,
                    { if (it.matches(Regex("\\d{0,10}(\\.\\d{0,2})?"))) amount = it },
                    label = { Text("Expected amount") },
                    prefix = { Text("₹") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true,
                )
                OutlinedTextField(dueDate, { dueDate = it.take(10) }, label = { Text("Next due date (YYYY-MM-DD)") }, singleLine = true)
                Text("Repeats", style = MaterialTheme.typography.labelLarge)
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(listOf(0 to "One-time", 1 to "Monthly", 3 to "Quarterly", 12 to "Yearly")) { option ->
                        FilterChip(selected = recurrence == option.first, onClick = { recurrence = option.first }, label = { Text(option.second) })
                    }
                }
                if (accounts.isNotEmpty()) {
                    Text("Pay from", style = MaterialTheme.typography.labelLarge)
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        item { FilterChip(selected = accountId == null, onClick = { accountId = null }, label = { Text("Unassigned") }) }
                        items(accounts) { account ->
                            FilterChip(selected = accountId == account.id, onClick = { accountId = account.id }, label = { Text(account.name) })
                        }
                    }
                }
                OutlinedTextField(notes, { notes = it.take(240) }, label = { Text("Notes (optional)") }, minLines = 2)
            }
        },
        confirmButton = {
            Button(
                enabled = title.isNotBlank() && parsedAmount != null && parsedAmount > 0 && parsedDate != null,
                onClick = {
                    onSave(
                        BillReminder(
                            id = existing?.id ?: 0,
                            title = title.trim(),
                            amountMinor = parsedAmount!!,
                            dueDateEpochDay = parsedDate!!.toEpochDay(),
                            recurrenceMonths = recurrence,
                            accountId = accountId,
                            notes = notes.trim().takeIf(String::isNotBlank),
                            isActive = true,
                            lastPaidEpochDay = existing?.lastPaidEpochDay,
                        ),
                    )
                },
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun WhatIfContent(
    transactions: List<TransactionRecord>,
    transactionLinks: List<TransactionLink>,
    effectiveExpenseTransactions: List<TransactionRecord>,
    accounts: List<AccountProfile>,
    recurringPayments: List<RecurringPayment>,
    loans: List<LoanAccount>,
) {
    val today = remember { LocalDate.now() }
    val zoneId = remember { ZoneId.systemDefault() }
    var months by remember { mutableIntStateOf(12) }
    var incomeChange by remember { mutableStateOf("0") }
    var reduction by remember { mutableIntStateOf(20) }
    var oneTimeExpense by remember { mutableStateOf("0") }
    var expenseMonth by remember { mutableIntStateOf(1) }
    val opening = remember(accounts) { consolidatedCashFlowOpeningBalance(accounts) }
    val lookbackStart = today.minusDays(90)
    val recent = remember(transactions, today, zoneId) {
        transactions.filter {
            val date = Instant.ofEpochMilli(it.occurredAt).atZone(zoneId).toLocalDate()
            it.reviewStatus == ReviewStatus.CONFIRMED && !date.isBefore(lookbackStart) && date.isBefore(today)
        }
    }
    val linkedTransactionIds = remember(transactionLinks) {
        transactionLinks.flatMap { listOf(it.sourceTransactionId, it.targetTransactionId) }.toSet()
    }
    val monthlyIncome = recent.filter {
        (it.type == TransactionType.INCOME || it.type == TransactionType.REFUND) &&
            it.id !in linkedTransactionIds
    }
        .sumOf { it.amountMinor } / 3
    val recentEffectiveExpenses = remember(effectiveExpenseTransactions, today, zoneId) {
        effectiveExpenseTransactions.filter {
            val date = Instant.ofEpochMilli(it.occurredAt).atZone(zoneId).toLocalDate()
            !date.isBefore(lookbackStart) && date.isBefore(today)
        }
    }
    val monthlyExpense = recentEffectiveExpenses.sumOf { it.amountMinor } / 3
    val monthlyFixed = (
        recurringPayments.sumOf { if (it.intervalDays <= 9) it.typicalAmountMinor * 52 / 12 else it.typicalAmountMinor } +
            loans.filter { it.remainingInstallments > 0 }.sumOf { it.emiMinor }
        ).coerceAtMost(monthlyExpense)
    val monthlyFlexible = (monthlyExpense - monthlyFixed).coerceAtLeast(0)
    val scenario = WhatIfScenario(
        name = "My scenario",
        extraMonthlyIncomeMinor = incomeChange.toMinorOrNull() ?: 0,
        flexibleExpenseReductionBasisPoints = reduction * 100,
        oneTimeExpenseMinor = oneTimeExpense.toMinorOrNull() ?: 0,
        oneTimeExpenseMonth = expenseMonth.coerceIn(1, months),
    )
    val simulation = opening?.let {
        simulateWhatIfMonthly(it, monthlyIncome, monthlyFixed, monthlyFlexible, scenario, months)
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(18.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = MaterialTheme.shapes.large) {
                Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Icon(Icons.Rounded.Calculate, contentDescription = null)
                    Text("Explore choices without changing your transactions, budgets, or balances.")
                }
            }
        }
        item {
            Text("Horizon", style = MaterialTheme.typography.titleMedium)
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(listOf(3, 6, 12, 24)) { value ->
                    FilterChip(selected = months == value, onClick = { months = value; expenseMonth = expenseMonth.coerceAtMost(value) }, label = { Text("$value months") })
                }
            }
        }
        item {
            PaisaCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(
                        incomeChange,
                        { if (it.matches(Regex("-?\\d{0,10}(\\.\\d{0,2})?"))) incomeChange = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Extra monthly income") },
                        prefix = { Text("₹") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        singleLine = true,
                    )
                    Text("Reduce flexible spending", style = MaterialTheme.typography.labelLarge)
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(listOf(0, 10, 20, 30, 50)) { value ->
                            FilterChip(selected = reduction == value, onClick = { reduction = value }, label = { Text("$value%") })
                        }
                    }
                    OutlinedTextField(
                        oneTimeExpense,
                        { if (it.matches(Regex("\\d{0,10}(\\.\\d{0,2})?"))) oneTimeExpense = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("One-time purchase or payment") },
                        prefix = { Text("₹") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        singleLine = true,
                    )
                    Text("Apply in month", style = MaterialTheme.typography.labelLarge)
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items((1..months).toList()) { value ->
                            FilterChip(selected = expenseMonth == value, onClick = { expenseMonth = value }, label = { Text(value.toString()) })
                        }
                    }
                }
            }
        }
        if (simulation == null) {
            item {
                EmptyPlannerCard(
                    "Complete merged balances needed",
                    "One or more merged bank accounts has only a partial balance. Add a current balance for every source before running a scenario.",
                )
            }
        } else {
            item {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    PlannerMetric("Baseline", simulation.baselineEndingMinor, Modifier.weight(1f))
                    PlannerMetric("Scenario", simulation.scenarioEndingMinor, Modifier.weight(1f))
                }
            }
            item {
                PaisaCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("Projected cash position", style = MaterialTheme.typography.titleLarge)
                        Text(
                            "${if (simulation.improvementMinor >= 0) "+" else "−"}${formatMoney(abs(simulation.improvementMinor))} versus baseline",
                            color = if (simulation.improvementMinor >= 0) Color(0xFF138A61) else MaterialTheme.colorScheme.error,
                            fontWeight = FontWeight.Bold,
                        )
                        MoneyLineChart(
                            simulation.points.map { MoneyChartPoint("M${it.monthNumber}", it.scenarioBalanceMinor) },
                            Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
        }
        item {
            Text(
                "Baseline: ${formatMoney(monthlyIncome)} monthly income, ${formatMoney(monthlyFixed)} fixed and ${formatMoney(monthlyFlexible)} flexible spending, estimated from recent confirmed activity.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
fun InsightsScreen(
    initialSection: InsightSection = InsightSection.OVERVIEW,
    transactions: List<TransactionRecord>,
    transactionLinks: List<TransactionLink>,
    expenseSplits: List<ExpenseSplit> = emptyList(),
    effectiveExpenseTransactions: List<TransactionRecord>,
    customCategories: List<CustomCategory>,
    today: LocalDate,
    zoneId: ZoneId,
    accounts: List<AccountProfile>,
    balanceHistory: List<AccountBalanceSnapshot>,
    bills: List<BillReminder>,
    recurringPayments: List<RecurringPayment>,
    paymentCommitments: List<PaymentCommitment>,
    loans: List<LoanAccount>,
    netWorthItems: List<NetWorthItem>,
    insights: List<SpendingInsight>,
    onTransactionClick: (TransactionRecord) -> Unit,
    onSaveNetWorthItem: (NetWorthItem) -> Unit,
    onDeleteNetWorthItem: (Long) -> Unit,
) {
    var sectionName by rememberSaveable { mutableStateOf(initialSection.name) }
    val section = InsightSection.entries.firstOrNull { it.name == sectionName } ?: InsightSection.OVERVIEW
    LaunchedEffect(initialSection) { sectionName = initialSection.name }
    Column(Modifier.dashboardWidth(1080.dp).fillMaxSize()) {
        HubHeader(
            "Insights",
            if (section == InsightSection.OVERVIEW) "Spending Lens"
            else "Trends and forward-looking estimates, calculated on-device",
        )
        HubSectionPicker(InsightSection.entries, section, { it.label }) { sectionName = it.name }
        Box(Modifier.fillMaxSize()) {
            when (section) {
                InsightSection.OVERVIEW -> AnalyticsScreen(
                    transactions = transactions,
                    transactionLinks = transactionLinks,
                    expenseSplits = expenseSplits,
                    effectiveExpenseTransactions = effectiveExpenseTransactions,
                    customCategories = customCategories,
                    today = today,
                    zoneId = zoneId,
                    insights = insights,
                    onTransactionClick = onTransactionClick,
                    showHeader = false,
                )
                InsightSection.CASH_FLOW -> CashFlowContent(
                    transactions = transactions,
                    transactionLinks = transactionLinks,
                    accounts = accounts,
                    bills = bills,
                    recurringPayments = recurringPayments,
                    paymentCommitments = paymentCommitments,
                    loans = loans,
                )
                InsightSection.NET_WORTH -> NetWorthContent(
                    accounts,
                    balanceHistory,
                    loans,
                    netWorthItems,
                    onSaveNetWorthItem,
                    onDeleteNetWorthItem,
                )
            }
        }
    }
}

@Composable
private fun CashFlowContent(
    transactions: List<TransactionRecord>,
    transactionLinks: List<TransactionLink>,
    accounts: List<AccountProfile>,
    bills: List<BillReminder>,
    recurringPayments: List<RecurringPayment>,
    paymentCommitments: List<PaymentCommitment>,
    loans: List<LoanAccount>,
) {
    val today = remember { LocalDate.now() }
    val zoneId = remember { ZoneId.systemDefault() }
    var horizon by remember { mutableIntStateOf(30) }
    val dueItems = remember(bills, recurringPayments, paymentCommitments, accounts, loans, today, zoneId, horizon) {
        val accountIdsByName = accounts.associate { normalizedMerchantKey(it.name) to it.id }
        val commitmentKeys = paymentCommitments.mapTo(mutableSetOf(), ::paymentCommitmentIdentityKey)
        val dedupedRecurring = recurringPayments.filterNot {
            recurringPaymentIdentityKey(it, accountIdsByName) in commitmentKeys
        }
        buildDueItems(
            bills,
            dedupedRecurring,
            loans,
            today,
            zoneId,
            horizonDays = horizon,
            includeRepeatingOccurrences = true,
        ) + buildPaymentCommitmentDueItems(
            commitments = paymentCommitments,
            today = today,
            horizonDays = horizon,
            includeRepeatingOccurrences = true,
            accountNamesById = accounts.associate { it.id to it.name },
        )
    }
    val opening = remember(accounts) { consolidatedCashFlowOpeningBalance(accounts) }
    val bankAccountIds = remember(accounts) {
        accounts.filter { it.type == AccountType.BANK_ACCOUNT }.mapTo(mutableSetOf(), AccountProfile::id)
    }
    val cashFlowTransactions = remember(transactions, bankAccountIds) {
        transactions.filter { it.accountId == null || it.accountId in bankAccountIds }
    }
    val forecast = remember(opening, cashFlowTransactions, transactionLinks, dueItems, today, zoneId, horizon) {
        opening?.let {
            buildCashFlowForecast(
                openingBalanceMinor = it,
                transactions = cashFlowTransactions,
                dueItems = dueItems,
                asOf = today,
                zoneId = zoneId,
                horizonDays = horizon,
                transactionLinks = transactionLinks,
            )
        }
    }
    val chartPoints = remember(forecast, horizon) {
        val step = (horizon / 8).coerceAtLeast(1)
        buildList {
            forecast?.let { availableForecast ->
                add(MoneyChartPoint("Today", availableForecast.openingBalanceMinor))
                availableForecast.points.forEachIndexed { index, point ->
                    if (index % step == 0 || index == availableForecast.points.lastIndex) {
                        add(MoneyChartPoint(point.date.format(DateTimeFormatter.ofPattern("d MMM")), point.projectedBalanceMinor))
                    }
                }
            }
        }
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(listOf(30, 60, 90)) { days ->
                    FilterChip(selected = horizon == days, onClick = { horizon = days }, label = { Text("$days days") })
                }
            }
        }
        if (forecast == null) {
            item {
                EmptyPlannerCard(
                    "Complete merged balances needed",
                    "One or more merged bank accounts has only a partial balance. Add a current balance for every source before forecasting cash flow.",
                )
            }
        } else {
            item {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    PlannerMetric("Opening", forecast.openingBalanceMinor, Modifier.weight(1f))
                    PlannerMetric("Projected", forecast.endingBalanceMinor, Modifier.weight(1f))
                }
            }
            item {
                PaisaCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("Expected bank cash", style = MaterialTheme.typography.titleLarge)
                        MoneyLineChart(chartPoints, Modifier.fillMaxWidth(), forecastStartIndex = 0)
                        HorizontalDivider()
                        Text(
                            "Lowest point: ${formatMoney(forecast.lowestBalanceMinor)}",
                            color = if (forecast.lowestBalanceMinor < 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                }
            }
            item {
                PaisaCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Forecast assumptions", style = MaterialTheme.typography.titleMedium)
                        Text("Daily income · ${formatMoney(forecast.baseline.averageDailyIncomeMinor)}")
                        Text("Flexible daily spending · ${formatMoney(forecast.baseline.averageDailyFlexibleExpenseMinor)}")
                        Text("Scheduled bills and EMIs · ${formatMoney(dueItems.sumOf { it.amountMinor })}")
                        Text(
                            "Uses the last ${forecast.baseline.lookbackDays} days of confirmed bank and unassigned activity. " +
                                "Own-account transfers and assigned card purchases are excluded.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            if (opening == 0L) {
                item { EmptyPlannerCard("No bank opening balance", "Scan a balance SMS or add current assets in Net worth. The forecast currently starts at zero.") }
            }
        }
    }
}

@Composable
private fun NetWorthContent(
    accounts: List<AccountProfile>,
    balanceHistory: List<AccountBalanceSnapshot>,
    loans: List<LoanAccount>,
    manualItems: List<NetWorthItem>,
    onSave: (NetWorthItem) -> Unit,
    onDelete: (Long) -> Unit,
) {
    var adding by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<NetWorthItem?>(null) }
    var deleting by remember { mutableStateOf<NetWorthItem?>(null) }
    val limits = remember(accounts, balanceHistory) {
        accounts.associate { account ->
            val latest = balanceHistory.filter { it.accountId == account.id }.maxByOrNull { it.recordedAt }
            account.id to (account.creditLimitMinor ?: latest?.creditLimitMinor ?: 0L)
        }.filterValues { it > 0 }
    }
    val summary = remember(accounts, loans, manualItems, limits) {
        buildNetWorthSummary(accounts, loans, manualItems, limits)
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item {
            PaisaCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Text("Estimated net worth", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    MoneyText(summary.netWorthMinor, style = MaterialTheme.typography.headlineLarge)
                    Text("Known assets minus known debts", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                PlannerMetric("Assets", summary.assetsMinor, Modifier.weight(1f))
                PlannerMetric("Liabilities", summary.liabilitiesMinor, Modifier.weight(1f))
            }
        }
        item {
            val total = (summary.assetsMinor + summary.liabilitiesMinor).coerceAtLeast(1)
            PaisaCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Composition", style = MaterialTheme.typography.titleMedium)
                    LinearProgressIndicator(
                        progress = { summary.assetsMinor.toFloat() / total },
                        modifier = Modifier.fillMaxWidth().height(10.dp),
                        color = Color(0xFF138A61),
                        trackColor = MaterialTheme.colorScheme.errorContainer,
                    )
                    Text("Green assets · remaining bar liabilities", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        item {
            Button(onClick = { adding = true }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                Icon(Icons.Rounded.Add, contentDescription = null)
                Spacer(Modifier.size(8.dp))
                Text("Add asset or liability")
            }
        }
        if (summary.items.isEmpty()) {
            item { EmptyPlannerCard("No known values yet", "Bank balances, used card credit, loans, and manually added assets appear here.") }
        } else {
            NetWorthKind.entries.forEach { kind ->
                val matching = summary.items.filter { it.kind == kind }
                if (matching.isNotEmpty()) {
                    item { Text(if (kind == NetWorthKind.ASSET) "Assets" else "Liabilities", style = MaterialTheme.typography.titleLarge) }
                    items(matching, key = { "${it.kind}:${it.id}:${it.name}" }) { item ->
                        val manual = manualItems.firstOrNull { it.id == item.id && it.name == item.name && it.kind == item.kind }
                        PaisaCard(Modifier.fillMaxWidth()) {
                            Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(item.name, style = MaterialTheme.typography.titleMedium)
                                    Text(item.category, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                MoneyText(item.valueMinor, style = MaterialTheme.typography.titleMedium)
                                if (manual != null) {
                                    IconButton(onClick = { editing = manual }) { Icon(Icons.Rounded.Edit, "Edit ${manual.name}") }
                                    IconButton(onClick = { deleting = manual }) { Icon(Icons.Rounded.DeleteOutline, "Delete ${manual.name}") }
                                }
                            }
                        }
                    }
                }
            }
        }
        item {
            Text(
                "Only values available to PaisaLens are included. Market prices are not fetched automatically.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    if (adding || editing != null) {
        NetWorthEditorDialog(
            existing = editing,
            onDismiss = { adding = false; editing = null },
            onSave = { onSave(it); adding = false; editing = null },
        )
    }
    deleting?.let { item ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Delete ${item.name}?") },
            text = { Text("This manually added net-worth item will be permanently removed.") },
            confirmButton = {
                Button(
                    onClick = {
                        onDelete(item.id)
                        deleting = null
                    },
                ) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun NetWorthEditorDialog(
    existing: NetWorthItem?,
    onDismiss: () -> Unit,
    onSave: (NetWorthItem) -> Unit,
) {
    var name by remember(existing) { mutableStateOf(existing?.name.orEmpty()) }
    var value by remember(existing) { mutableStateOf(existing?.valueMinor?.let(::minorToInput).orEmpty()) }
    var kind by remember(existing) { mutableStateOf(existing?.kind ?: NetWorthKind.ASSET) }
    var category by remember(existing) { mutableStateOf(existing?.category ?: "Other") }
    val parsed = value.toMinorOrNull()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (existing == null) "Add net-worth item" else "Edit net-worth item") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    NetWorthKind.entries.forEach { option ->
                        FilterChip(selected = kind == option, onClick = { kind = option }, label = { Text(option.name.lowercase().replaceFirstChar(Char::titlecase)) })
                    }
                }
                OutlinedTextField(name, { name = it.take(64) }, label = { Text("Name") }, singleLine = true)
                OutlinedTextField(
                    value,
                    { if (it.matches(Regex("\\d{0,12}(\\.\\d{0,2})?"))) value = it },
                    label = { Text("Current value") },
                    prefix = { Text("₹") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true,
                )
                OutlinedTextField(category, { category = it.take(48) }, label = { Text("Type (investment, property, debt…)") }, singleLine = true)
            }
        },
        confirmButton = {
            Button(
                enabled = name.isNotBlank() && parsed != null && parsed >= 0,
                onClick = {
                    onSave(
                        NetWorthItem(
                            id = existing?.id ?: 0,
                            name = name.trim(),
                            kind = kind,
                            valueMinor = parsed!!,
                            category = category.trim().ifBlank { "Other" },
                            updatedAt = System.currentTimeMillis(),
                        ),
                    )
                },
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun HubHeader(title: String, subtitle: String) {
    Column(Modifier.fillMaxWidth().padding(start = 18.dp, top = 12.dp, end = 18.dp, bottom = 6.dp)) {
        Text(title, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Text(subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun <T> HubSectionPicker(
    values: List<T>,
    selected: T,
    label: (T) -> String,
    onSelect: (T) -> Unit,
) {
    val rowState = rememberLazyListState()
    val selectedIndex = values.indexOf(selected)
    LaunchedEffect(selectedIndex) {
        if (selectedIndex >= 0 && rowState.layoutInfo.visibleItemsInfo.none { it.index == selectedIndex }) {
            rowState.scrollToItem(selectedIndex)
        }
    }
    LazyRow(
        state = rowState,
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 18.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(values) { value ->
            FilterChip(
                selected = selected == value,
                onClick = { onSelect(value) },
                modifier = Modifier.heightIn(min = 48.dp),
                label = { Text(label(value)) },
            )
        }
    }
}

@Composable
private fun PlannerMetric(label: String, value: Long, modifier: Modifier, money: Boolean = true) {
    PaisaCard(modifier) {
        Column(Modifier.padding(14.dp)) {
            Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(4.dp))
            if (money) MoneyText(value, style = MaterialTheme.typography.titleLarge) else Text(value.toString(), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun EmptyPlannerCard(title: String, body: String) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainer, shape = MaterialTheme.shapes.large) {
        Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Icon(Icons.Rounded.AutoGraph, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(body, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private fun String.toMinorOrNull(): Long? = toBigDecimalOrNull()
    ?.multiply(BigDecimal(100))
    ?.setScale(0, RoundingMode.HALF_UP)
    ?.toLong()

private fun minorToInput(value: Long): String = BigDecimal(value).divide(BigDecimal(100)).stripTrailingZeros().toPlainString()
