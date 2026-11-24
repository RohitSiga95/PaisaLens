package com.paisalens.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.automirrored.rounded.ReceiptLong
import androidx.compose.material.icons.automirrored.rounded.Rule
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.AccountBalance
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.CreditCard
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Sms
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.paisalens.app.data.model.AccountBalanceDay
import com.paisalens.app.data.model.AccountBalanceSnapshot
import com.paisalens.app.data.model.AccountBalanceSourceGroup
import com.paisalens.app.data.model.AccountProfile
import com.paisalens.app.data.model.AccountType
import com.paisalens.app.data.model.CustomCategory
import com.paisalens.app.data.model.ExpenseCategory
import com.paisalens.app.data.model.SmartCategoryRule
import com.paisalens.app.data.model.SmartRuleMatchType
import com.paisalens.app.data.model.TransactionRecord
import com.paisalens.app.data.model.balanceSourceDisplayName
import com.paisalens.app.data.model.calculateCreditUtilization
import com.paisalens.app.data.model.currentBalanceReading
import com.paisalens.app.data.model.currentBalanceReadingDate
import com.paisalens.app.data.model.dailyAccountBalances
import com.paisalens.app.data.model.isSmsBalanceSource
import com.paisalens.app.data.model.previewSmartCategoryRuleApplication
import com.paisalens.app.sms.BankSmsSupport
import com.paisalens.app.ui.components.CategoryIcon
import com.paisalens.app.ui.components.CreditUtilizationBar
import com.paisalens.app.ui.components.CustomCategoryIcon
import com.paisalens.app.ui.components.DailyBalanceChart
import com.paisalens.app.ui.components.MoneyText
import com.paisalens.app.ui.components.PaisaCard
import com.paisalens.app.ui.components.categoryColor
import com.paisalens.app.ui.components.customCategoryColor
import com.paisalens.app.ui.components.dashboardWidth
import com.paisalens.app.ui.components.formatMoney
import java.math.BigDecimal
import java.math.RoundingMode
import java.text.SimpleDateFormat
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Date
import java.util.Locale

private enum class BalanceHistoryRange(val label: String, val days: Int) {
    WEEK("7 days", 7),
    MONTH("30 days", 30),
    QUARTER("90 days", 90),
}

@Composable
internal fun AccountFinanceSheet(
    account: AccountProfile,
    history: List<AccountBalanceSnapshot>,
    sources: List<AccountBalanceSourceGroup>,
    today: LocalDate,
    zoneId: ZoneId,
    onUpdateAccount: (AccountProfile) -> Unit,
    onViewTransactions: () -> Unit,
    onDismiss: () -> Unit,
) {
    var selectedRangeName by rememberSaveable(account.id) { mutableStateOf(BalanceHistoryRange.MONTH.name) }
    var selectedEpochDay by rememberSaveable(account.id) { mutableStateOf<Long?>(null) }
    var selectedSourceId by rememberSaveable(account.id) { mutableStateOf<Long?>(null) }
    var showAllDays by rememberSaveable(account.id) { mutableStateOf(false) }
    var showRawReadings by rememberSaveable(account.id) { mutableStateOf(false) }
    var showLimitDialog by remember { mutableStateOf(false) }
    var showRenameDialog by remember { mutableStateOf(false) }
    var showOptions by remember { mutableStateOf(false) }
    val range = BalanceHistoryRange.valueOf(selectedRangeName)
    val scheme = MaterialTheme.colorScheme
    val credit = account.type == AccountType.CREDIT_CARD
    val valueLabel = if (credit) "Available credit" else "Available balance"
    val current = account.currentBalanceReading()
    val partial = sources.size > 1 && current != null && account.availabilityFetchedAt == null
    val sourceView = sources.firstOrNull { it.accountIds.minOrNull() == selectedSourceId }
    val chartSources = if (sourceView != null) listOf(sourceView) else sources
    val chartHistory = remember(history, account, sources) {
        val sourceId = sources.singleOrNull()?.accountIds?.singleOrNull()
        val recordedAt = account.availabilityFetchedAt
        if (sourceId != null && recordedAt != null && current != null &&
            history.none { (it.physicalAccountId ?: it.accountId) == sourceId && it.recordedAt == recordedAt && it.chartAmount(account.type) != null }
        ) {
            history + AccountBalanceSnapshot(
                accountId = sourceId,
                balanceMinor = account.balanceMinor,
                availableCreditMinor = account.availableCreditMinor,
                creditLimitMinor = account.creditLimitMinor,
                recordedAt = recordedAt,
                sender = account.availabilitySender,
            )
        } else history
    }
    val days = remember(chartHistory, account.type, chartSources, today, range, zoneId) {
        dailyAccountBalances(chartHistory, account.type, chartSources, today, range.days, zoneId)
    }
    val hasReadings = days.any { it.amountMinor != null }
    val selectedDate = selectedEpochDay?.let(LocalDate::ofEpochDay)?.takeIf { it in days.first().date..today } ?: today
    val utilization = remember(account, partial) {
        calculateCreditUtilization(
            accountId = account.id,
            availableCreditMinor = account.availableCreditMinor.takeUnless { partial },
            creditLimitMinor = account.creditLimitMinor,
        )
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = scheme.background) {
            Column(Modifier.dashboardWidth(860.dp).fillMaxSize()) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = onDismiss) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back from account details") }
                    Text(
                        account.name,
                        modifier = Modifier.weight(1f).padding(start = 4.dp),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                    )
                    Box {
                        IconButton(onClick = { showOptions = true }) {
                            Icon(Icons.Rounded.MoreVert, contentDescription = "Account options")
                        }
                        DropdownMenu(expanded = showOptions, onDismissRequest = { showOptions = false }) {
                            DropdownMenuItem(
                                text = { Text("Rename account") },
                                onClick = { showOptions = false; showRenameDialog = true },
                            )
                            if (credit && account.mergedMemberCount <= 1) {
                                DropdownMenuItem(
                                    text = { Text("Edit total credit limit") },
                                    onClick = { showOptions = false; showLimitDialog = true },
                                )
                            }
                            if (history.isNotEmpty()) {
                                DropdownMenuItem(
                                    text = { Text(if (showRawReadings) "Daily history" else "All saved readings") },
                                    onClick = { showOptions = false; showRawReadings = !showRawReadings },
                                )
                            }
                            DropdownMenuItem(
                                text = { Text("View transactions") },
                                onClick = { showOptions = false; onViewTransactions() },
                            )
                        }
                    }
                }
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = 16.dp, top = 12.dp, end = 16.dp, bottom = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    item(key = "account-balance-header") {
                        AccountBalanceHeader(account, valueLabel, today, zoneId, partial, sources.size > 1)
                    }
                    item(key = "account-balance-ranges") {
                        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                            BalanceHistoryRange.entries.forEachIndexed { index, option ->
                                SegmentedButton(
                                    selected = option == range,
                                    onClick = {
                                        selectedRangeName = option.name
                                        selectedEpochDay = null
                                        showAllDays = false
                                    },
                                    shape = SegmentedButtonDefaults.itemShape(index, BalanceHistoryRange.entries.size),
                                    colors = SegmentedButtonDefaults.colors(
                                        activeContainerColor = scheme.primary,
                                        activeContentColor = scheme.onPrimary,
                                    ),
                                    label = { Text(option.label, textAlign = TextAlign.Center) },
                                )
                            }
                        }
                    }
                    if (sources.size > 1) {
                        item(key = "account-balance-sources") {
                            var chooseSource by remember { mutableStateOf(false) }
                            Box {
                                OutlinedButton(onClick = { chooseSource = true }, modifier = Modifier.heightIn(min = 48.dp)) {
                                    Text(sourceView?.label ?: "Combined balance")
                                    Icon(Icons.Rounded.ExpandMore, contentDescription = "Choose a source account")
                                }
                                DropdownMenu(expanded = chooseSource, onDismissRequest = { chooseSource = false }) {
                                    DropdownMenuItem(text = { Text("Combined balance") }, onClick = {
                                        selectedSourceId = null
                                        chooseSource = false
                                    })
                                    sources.forEach { source ->
                                        DropdownMenuItem(text = { Text(source.label) }, onClick = {
                                            selectedSourceId = source.accountIds.minOrNull()
                                            chooseSource = false
                                        })
                                    }
                                }
                            }
                            if (sourceView == null) {
                                Text(
                                    "Combined history starts once every account has a saved reading. See each source separately above.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = scheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                    item(key = "account-balance-chart") {
                        DailyBalanceChart(days, selectedDate, valueLabel) { selectedEpochDay = it.toEpochDay() }
                    }
                    item(key = "account-balance-history-heading") {
                        Text(
                            if (showRawReadings) "Saved readings" else "Balance history",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                    if (showRawReadings) {
                        items(history, key = AccountBalanceSnapshot::id) { reading ->
                            BalanceHistoryRow(reading, account.type)
                        }
                    } else if (hasReadings) {
                        val visibleDays = days.asReversed().let { if (showAllDays) it else it.take(3) }
                        items(visibleDays, key = { it.date.toEpochDay() }) { day ->
                            AccountDailyBalanceRow(
                                day = day,
                                selected = selectedDate == day.date,
                                today = today,
                                onClick = { selectedEpochDay = day.date.toEpochDay() },
                            )
                        }
                        item(key = "account-balance-show-all") {
                            TextButton(onClick = { showAllDays = !showAllDays }, modifier = Modifier.heightIn(min = 48.dp)) {
                                Text(if (showAllDays) "Show recent days" else "Show all ${range.days} days")
                            }
                        }
                    } else {
                        item(key = "account-balance-empty-history") {
                            Text("No dated balance readings yet.", color = scheme.onSurfaceVariant)
                        }
                    }
                    item(key = "account-view-transactions") {
                        FilledTonalButton(
                            onClick = onViewTransactions,
                            modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
                        ) {
                            Icon(Icons.AutoMirrored.Rounded.ReceiptLong, contentDescription = null)
                            Text("View transactions", modifier = Modifier.weight(1f).padding(start = 10.dp))
                            Icon(Icons.AutoMirrored.Rounded.ArrowForward, contentDescription = null)
                        }
                    }
                    if (credit) {
                        item(key = "account-credit-utilization") {
                            PaisaCard(Modifier.fillMaxWidth()) {
                                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Text("Credit utilisation", style = MaterialTheme.typography.titleMedium)
                                    utilization.utilizationBasisPoints?.let { basisPoints ->
                                        CreditUtilizationBar(basisPoints, utilization.band, Modifier.fillMaxWidth())
                                    } ?: Text(
                                        if (partial) "Credit usage needs a reading from every merged card."
                                        else "Add the total credit limit to calculate credit use.",
                                        color = scheme.onSurfaceVariant,
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                    if (account.creditLimitMinor != null) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                                        ) {
                                            Column(Modifier.weight(1f)) { FinanceValue("Used", utilization.usedMinor) }
                                            Column(Modifier.weight(1f)) { FinanceValue("Credit limit", account.creditLimitMinor) }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (showLimitDialog) {
        CreditLimitDialog(
            account = account,
            onDismiss = { showLimitDialog = false },
            onSave = { newLimit ->
                onUpdateAccount(account.copy(creditLimitMinor = newLimit))
                showLimitDialog = false
            },
        )
    }
    if (showRenameDialog) {
        AccountRenameDialog(
            account = account,
            onDismiss = { showRenameDialog = false },
            onSave = { name ->
                onUpdateAccount(account.copy(name = name))
                showRenameDialog = false
            },
        )
    }
}

@Composable
private fun AccountBalanceHeader(
    account: AccountProfile,
    valueLabel: String,
    today: LocalDate,
    zoneId: ZoneId,
    partial: Boolean,
    combined: Boolean,
) {
    val scheme = MaterialTheme.colorScheme
    val amount = account.currentBalanceReading()
    val date = account.currentBalanceReadingDate(zoneId)
    val asOf = when (date) {
        today -> "Today"
        today.minusDays(1) -> "Yesterday"
        null -> null
        else -> date.format(DateTimeFormatter.ofPattern("d MMM uuuu"))
    }
    val source = when {
        partial -> "Partial · missing source readings"
        amount == null -> "No saved reading"
        combined -> listOfNotNull("Combined readings", asOf?.let { "oldest $it" }).joinToString(" · ")
        isSmsBalanceSource(account.availabilitySender) -> listOfNotNull("From SMS", asOf).joinToString(" · ")
        else -> listOfNotNull("Saved balance", asOf).joinToString(" · ")
    }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Surface(
                color = scheme.primaryContainer,
                contentColor = scheme.onPrimaryContainer,
                shape = MaterialTheme.shapes.medium,
            ) {
                Icon(
                    if (account.type == AccountType.CREDIT_CARD) Icons.Rounded.CreditCard else Icons.Rounded.AccountBalance,
                    contentDescription = null,
                    modifier = Modifier.padding(12.dp).size(26.dp),
                )
            }
            Text(account.financeSubtitle(), modifier = Modifier.weight(1f), color = scheme.onSurfaceVariant)
        }
        Column {
            Text(valueLabel, style = MaterialTheme.typography.titleMedium, color = scheme.onSurfaceVariant)
            if (amount == null) Text("Not available", style = MaterialTheme.typography.headlineMedium)
            else MoneyText(amount, style = MaterialTheme.typography.displaySmall, color = scheme.onSurface)
        }
        Surface(color = scheme.secondaryContainer, contentColor = scheme.onSecondaryContainer, shape = CircleShape) {
            Row(
                Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Rounded.Sms, contentDescription = null, modifier = Modifier.size(17.dp))
                Text(source, style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}

@Composable
private fun AccountDailyBalanceRow(
    day: AccountBalanceDay,
    selected: Boolean,
    today: LocalDate,
    onClick: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
        shape = MaterialTheme.shapes.small,
        color = if (selected) scheme.primaryContainer.copy(alpha = 0.5f) else scheme.surface,
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(day.date.format(DateTimeFormatter.ofPattern("d MMM uuuu")), style = MaterialTheme.typography.titleSmall)
                Text(
                    when {
                        day.amountMinor == null -> "No saved reading"
                        !day.allReadingsFromThisDay -> "Carried forward"
                        day.date == today -> "Current reading"
                        else -> "Saved reading"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = scheme.onSurfaceVariant,
                )
            }
            if (day.amountMinor == null) Text("—")
            else MoneyText(day.amountMinor, style = MaterialTheme.typography.titleSmall)
        }
    }
}

@Composable
private fun AccountRenameDialog(
    account: AccountProfile,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
) {
    var name by remember(account.id, account.name) { mutableStateOf(account.name) }
    val cleanName = name.trim()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(if (account.type == AccountType.CREDIT_CARD) "Rename credit card" else "Rename bank account")
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.take(48) },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Display name") },
                    supportingText = {
                        Text("This name is used across Home, Activity, filters, exports, and future matching SMS.")
                    },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSave(cleanName) },
                enabled = cleanName.isNotBlank() && cleanName != account.name,
            ) { Text("Save name") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SmartCategoryRulesSheet(
    rules: List<SmartCategoryRule>,
    transactions: List<TransactionRecord>,
    exactMerchantKeys: Set<String>,
    accounts: List<AccountProfile>,
    customCategories: List<CustomCategory>,
    onSave: (SmartCategoryRule, Boolean) -> Unit,
    onDelete: (Long) -> Unit,
    onDismiss: () -> Unit,
) {
    var editingRule by remember { mutableStateOf<SmartCategoryRule?>(null) }
    var showEditor by remember { mutableStateOf(false) }
    var deletingRule by remember { mutableStateOf<SmartCategoryRule?>(null) }
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
            FinancialSheetHeader(
                title = "Smart category rules",
                subtitle = "Automatically categorize expenses using your rules.",
                onDismiss = onDismiss,
            )
            LazyColumn(
                contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 32.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item {
                    PrivateDataNotice("Rules run entirely on this phone. Merchant and transaction data are never uploaded.")
                }
                item {
                    Button(
                        onClick = {
                            editingRule = null
                            showEditor = true
                        },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                    ) {
                        Icon(Icons.Rounded.Add, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("Create rule")
                    }
                }
                if (rules.isEmpty()) {
                    item {
                        PaisaCard(Modifier.fillMaxWidth()) {
                            EmptyFinanceState(
                                title = "No smart rules yet",
                                detail = "Create a rule for merchants, amounts or accounts. Higher-priority rules are checked first.",
                            )
                        }
                    }
                } else {
                    items(rules, key = { it.id }) { rule ->
                        SmartRuleCard(
                            rule = rule,
                            accounts = accounts,
                            customCategories = customCategories,
                            onToggle = { enabled -> onSave(rule.copy(enabled = enabled), false) },
                            onEdit = {
                                editingRule = rule
                                showEditor = true
                            },
                            onDelete = { deletingRule = rule },
                        )
                    }
                }
            }
        }
    }

    if (showEditor) {
        SmartRuleEditorDialog(
            original = editingRule,
            transactions = transactions,
            savedRules = rules,
            exactMerchantKeys = exactMerchantKeys,
            accounts = accounts,
            customCategories = customCategories,
            onDismiss = { showEditor = false },
            onSave = { rule, applyToExisting ->
                onSave(rule, applyToExisting)
                showEditor = false
            },
        )
    }
    deletingRule?.let { rule ->
        AlertDialog(
            onDismissRequest = { deletingRule = null },
            title = { Text("Delete ${rule.name}?") },
            text = { Text("Existing transaction categories will stay unchanged. Future matches will no longer use this rule.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        onDelete(rule.id)
                        deletingRule = null
                    },
                ) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { deletingRule = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun SmartRuleCard(
    rule: SmartCategoryRule,
    accounts: List<AccountProfile>,
    customCategories: List<CustomCategory>,
    onToggle: (Boolean) -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    val accountName = accounts.firstOrNull { it.id == rule.accountId }?.name
    val target = customCategories.firstOrNull { it.id == rule.customCategoryId }?.name ?: rule.category.label
    PaisaCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(start = 16.dp, top = 14.dp, end = 8.dp, bottom = 14.dp),
            verticalArrangement = Arrangement.spacedBy(9.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier.size(42.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.AutoMirrored.Rounded.Rule, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                }
                Spacer(Modifier.width(8.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(rule.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        "Priority ${rule.priority} · → $target",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = rule.enabled,
                    onCheckedChange = onToggle,
                    modifier = Modifier.semantics { contentDescription = "Enable ${rule.name}" },
                )
            }
            Text(
                rule.conditionsDescription(accountName),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                IconButton(onClick = onEdit) {
                    Icon(Icons.Rounded.Edit, contentDescription = "Edit ${rule.name}")
                }
                IconButton(onClick = onDelete) {
                    Icon(
                        Icons.Rounded.DeleteOutline,
                        contentDescription = "Delete ${rule.name}",
                        tint = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
    }
}

@Composable
private fun SmartRuleEditorDialog(
    original: SmartCategoryRule?,
    transactions: List<TransactionRecord>,
    savedRules: List<SmartCategoryRule>,
    exactMerchantKeys: Set<String>,
    accounts: List<AccountProfile>,
    customCategories: List<CustomCategory>,
    onDismiss: () -> Unit,
    onSave: (SmartCategoryRule, Boolean) -> Unit,
) {
    var name by remember(original?.id) { mutableStateOf(original?.name.orEmpty()) }
    var pattern by remember(original?.id) { mutableStateOf(original?.merchantPattern.orEmpty()) }
    var matchType by remember(original?.id) { mutableStateOf(original?.matchType ?: SmartRuleMatchType.CONTAINS) }
    var minimum by remember(original?.id) { mutableStateOf(original?.minAmountMinor.toRupeeInput()) }
    var maximum by remember(original?.id) { mutableStateOf(original?.maxAmountMinor.toRupeeInput()) }
    var accountId by remember(original?.id) { mutableStateOf(original?.accountId) }
    var category by remember(original?.id) { mutableStateOf(original?.category ?: ExpenseCategory.OTHER) }
    var customCategoryId by remember(original?.id) { mutableStateOf(original?.customCategoryId) }
    var priority by remember(original?.id) { mutableStateOf((original?.priority ?: 0).toString()) }
    var enabled by remember(original?.id) { mutableStateOf(original?.enabled ?: true) }
    var applyToExisting by remember(original?.id) { mutableStateOf(false) }
    val initialUpdatedAt = remember(original?.id) { original?.updatedAt ?: System.currentTimeMillis() }

    val minMinor = minimum.toMinorOrNull()
    val maxMinor = maximum.toMinorOrNull()
    val minValid = minimum.isBlank() || minMinor != null
    val maxValid = maximum.isBlank() || maxMinor != null
    val rangeValid = minMinor == null || maxMinor == null || minMinor <= maxMinor
    val priorityValue = priority.toIntOrNull()
    val rule = SmartCategoryRule(
        id = original?.id ?: 0,
        name = name.trim().ifBlank { pattern.trim() },
        merchantPattern = pattern.trim(),
        matchType = matchType,
        minAmountMinor = minMinor,
        maxAmountMinor = maxMinor,
        accountId = accountId,
        category = category,
        customCategoryId = customCategoryId,
        enabled = enabled,
        priority = priorityValue ?: 0,
        updatedAt = initialUpdatedAt,
    )
    val preview = remember(rule, transactions, savedRules, exactMerchantKeys) {
        previewSmartCategoryRuleApplication(rule, transactions, savedRules, exactMerchantKeys)
    }
    val canSave = pattern.isNotBlank() && minValid && maxValid && rangeValid && priorityValue != null

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.AutoMirrored.Rounded.Rule, contentDescription = null) },
        title = { Text(if (original == null) "Create smart rule" else "Edit smart rule") },
        text = {
            Column(
                modifier = Modifier.heightIn(max = 560.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Text(
                    "Rules are evaluated locally. Higher priority wins when more than one rule matches.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.take(64) },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Rule name") },
                    placeholder = { Text("Weekend coffee") },
                    supportingText = { Text("Optional — merchant pattern is used if blank") },
                    singleLine = true,
                )
                OutlinedTextField(
                    value = pattern,
                    onValueChange = { pattern = it.take(64) },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Merchant pattern") },
                    placeholder = { Text("Starbucks") },
                    supportingText = { Text("Required · matching ignores capitalisation and extra spaces") },
                    singleLine = true,
                )
                Text("Merchant match", style = MaterialTheme.typography.labelLarge)
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(
                        listOf(SmartRuleMatchType.EXACT, SmartRuleMatchType.CONTAINS, SmartRuleMatchType.STARTS_WITH),
                    ) { option ->
                        FilterChip(
                            selected = matchType == option,
                            onClick = { matchType = option },
                            modifier = Modifier.heightIn(min = 48.dp),
                            label = { Text(option.readableLabel) },
                        )
                    }
                }
                Text("Optional amount range", style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = minimum,
                        onValueChange = { minimum = it.filterAmountInput() },
                        modifier = Modifier.weight(1f),
                        label = { Text("Min ₹") },
                        isError = !minValid || !rangeValid,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        singleLine = true,
                    )
                    OutlinedTextField(
                        value = maximum,
                        onValueChange = { maximum = it.filterAmountInput() },
                        modifier = Modifier.weight(1f),
                        label = { Text("Max ₹") },
                        isError = !maxValid || !rangeValid,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        singleLine = true,
                    )
                }
                if (!rangeValid) {
                    Text(
                        "Maximum must be greater than or equal to minimum.",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Text("Account", style = MaterialTheme.typography.labelLarge)
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    item {
                        FilterChip(
                            selected = accountId == null,
                            onClick = { accountId = null },
                            modifier = Modifier.heightIn(min = 48.dp),
                            label = { Text("Any account") },
                        )
                    }
                    items(accounts, key = { it.id }) { account ->
                        FilterChip(
                            selected = accountId == account.id,
                            onClick = { accountId = account.id },
                            modifier = Modifier.heightIn(min = 48.dp),
                            label = { Text(account.name) },
                        )
                    }
                }
                Text("Set category", style = MaterialTheme.typography.labelLarge)
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(
                        ExpenseCategory.entries.filterNot {
                            it == ExpenseCategory.INCOME || it == ExpenseCategory.TRANSFER
                        },
                    ) { option ->
                        val color = categoryColor(option)
                        FilterChip(
                            selected = customCategoryId == null && category == option,
                            onClick = {
                                category = option
                                customCategoryId = null
                            },
                            modifier = Modifier.heightIn(min = 48.dp),
                            leadingIcon = { CategoryIcon(option, Modifier.size(30.dp), 16) },
                            label = { Text(option.label) },
                            colors = FilterChipDefaults.filterChipColors(
                                containerColor = color.copy(alpha = 0.10f),
                                selectedContainerColor = color.copy(alpha = 0.24f),
                            ),
                        )
                    }
                    items(customCategories, key = { it.id }) { option ->
                        val color = customCategoryColor(option.colorHex)
                        FilterChip(
                            selected = customCategoryId == option.id,
                            onClick = {
                                category = ExpenseCategory.OTHER
                                customCategoryId = option.id
                            },
                            modifier = Modifier.heightIn(min = 48.dp),
                            leadingIcon = { CustomCategoryIcon(option, Modifier.size(30.dp), 16) },
                            label = { Text(option.name) },
                            colors = FilterChipDefaults.filterChipColors(
                                containerColor = color.copy(alpha = 0.10f),
                                selectedContainerColor = color.copy(alpha = 0.24f),
                            ),
                        )
                    }
                }
                OutlinedTextField(
                    value = priority,
                    onValueChange = { value ->
                        priority = value.filterIndexed { index, char -> char.isDigit() || (char == '-' && index == 0) }.take(6)
                    },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Priority") },
                    supportingText = { Text("Higher numbers run first") },
                    isError = priorityValue == null,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
                    singleLine = true,
                )
                SwitchSettingRow(
                    title = "Rule enabled",
                    detail = "Apply this rule to future matching expenses",
                    checked = enabled,
                    onCheckedChange = { enabled = it },
                )
                PaisaCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("Preview", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "${preview.matchedCount} existing match${if (preview.matchedCount == 1) "" else "es"} · ${formatMoney(preview.totalAmountMinor)}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                }
                SwitchSettingRow(
                    title = "Apply to existing matches",
                    detail = "Also recategorize the matching expenses shown in the preview",
                    checked = applyToExisting,
                    onCheckedChange = { applyToExisting = it },
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onSave(rule, applyToExisting) },
                enabled = canSave,
            ) { Text("Save rule") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun CreditLimitDialog(
    account: AccountProfile,
    onDismiss: () -> Unit,
    onSave: (Long?) -> Unit,
) {
    var input by remember(account.id) { mutableStateOf(account.creditLimitMinor.toRupeeInput()) }
    val parsed = input.toMinorOrNull()
    val valid = input.isBlank() || (parsed != null && parsed > 0)
    val lowerThanAvailable = parsed != null && account.availableCreditMinor != null && parsed < account.availableCreditMinor
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Rounded.CreditCard, contentDescription = null) },
        title = { Text("Total credit limit") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "Enter the card's full limit. PaisaLens uses it with the latest available-credit SMS to calculate utilisation.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it.filterAmountInput() },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Credit limit (₹)") },
                    placeholder = { Text("150000") },
                    supportingText = {
                        Text(
                            when {
                                lowerThanAvailable -> "Limit cannot be below available credit"
                                input.isBlank() -> "Leave blank to remove the saved limit"
                                else -> "Stored only on this phone"
                            },
                        )
                    },
                    isError = !valid || lowerThanAvailable,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Done),
                    singleLine = true,
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onSave(parsed) },
                enabled = valid && !lowerThanAvailable,
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun BalanceHistoryRow(snapshot: AccountBalanceSnapshot, accountType: AccountType) {
    val primary = snapshot.chartAmount(accountType)
    val detail = if (accountType == AccountType.CREDIT_CARD) {
        snapshot.creditLimitMinor?.let { "Limit ${formatMoney(it)}" }
    } else {
        balanceSourceDisplayName(snapshot.sender)?.takeIf(String::isNotBlank)
    }
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Surface(
            modifier = Modifier.size(42.dp),
            shape = CircleShape,
            color = MaterialTheme.colorScheme.primaryContainer,
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.History, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimaryContainer)
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(fullDateTime(snapshot.recordedAt), style = MaterialTheme.typography.bodyMedium)
            detail?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Text(
            primary?.let(::formatMoney) ?: "—",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
}

@Composable
private fun FinanceValue(label: String, value: Long?) {
    Column {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value?.let(::formatMoney) ?: "—", style = MaterialTheme.typography.titleSmall)
    }
}

@Composable
private fun SwitchSettingRow(
    title: String,
    detail: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clickable { onCheckedChange(!checked) }
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.width(12.dp))
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            modifier = Modifier.semantics { contentDescription = title },
        )
    }
}

@Composable
private fun PrivateDataNotice(text: String) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Rounded.Lock, contentDescription = null, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(10.dp))
            Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onPrimaryContainer)
        }
    }
}

@Composable
private fun EmptyFinanceState(title: String, detail: String) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(22.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(Icons.Rounded.History, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        Text(title, style = MaterialTheme.typography.titleMedium, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        Text(
            detail,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
    }
}

@Composable
private fun FinancialSheetHeader(
    title: String,
    subtitle: String,
    onDismiss: () -> Unit,
    onEditTitle: (() -> Unit)? = null,
    editTitleDescription: String = "Edit name",
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, bottom = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        onEditTitle?.let { editTitle ->
            IconButton(onClick = editTitle) {
                Icon(Icons.Rounded.Edit, contentDescription = editTitleDescription)
            }
        }
        IconButton(onClick = onDismiss) { Icon(Icons.Rounded.Close, contentDescription = "Close") }
    }
}

private fun AccountProfile.currentFinanceAmount(): Long? =
    if (type == AccountType.CREDIT_CARD) availableCreditMinor else balanceMinor

private fun AccountProfile.financeSubtitle(): String = buildList {
    val institutionName = BankSmsSupport.accountBankKey(institution, name)
        ?.let(BankSmsSupport::institutionName)
        ?: institution?.trim()?.takeIf(String::isNotBlank)
    institutionName
        ?.takeUnless { it.equals(name, ignoreCase = true) }
        ?.let(::add)
    add(type.label)
    accountHint?.let { add("•••• $it") }
    if (mergedMemberCount > 1) add("$mergedMemberCount merged")
}.joinToString(" · ")

private fun AccountBalanceSnapshot.chartAmount(accountType: AccountType): Long? =
    if (accountType == AccountType.CREDIT_CARD) availableCreditMinor else balanceMinor

private fun SmartCategoryRule.conditionsDescription(accountName: String?): String = buildList {
    add("Merchant ${matchType.readableLabel.lowercase()} \"$merchantPattern\"")
    minAmountMinor?.let { add("at least ${formatMoney(it)}") }
    maxAmountMinor?.let { add("at most ${formatMoney(it)}") }
    accountName?.let { add("on $it") }
}.joinToString(" · ")

private val SmartRuleMatchType.readableLabel: String
    get() = when (this) {
        SmartRuleMatchType.EXACT -> "Exact"
        SmartRuleMatchType.CONTAINS -> "Contains"
        SmartRuleMatchType.STARTS_WITH -> "Starts with"
        SmartRuleMatchType.REGEX -> "Pattern"
    }

private fun Long?.toRupeeInput(): String = this?.let { minor ->
    BigDecimal.valueOf(minor, 2).stripTrailingZeros().toPlainString()
}.orEmpty()

private fun String.toMinorOrNull(): Long? {
    val clean = trim()
    if (clean.isBlank()) return null
    return runCatching {
        BigDecimal(clean).multiply(BigDecimal(100)).setScale(0, RoundingMode.HALF_UP).longValueExact()
    }.getOrNull()?.takeIf { it >= 0 }
}

private fun String.filterAmountInput(): String {
    val filtered = filter { it.isDigit() || it == '.' }
    val firstDot = filtered.indexOf('.')
    return if (firstDot < 0) filtered.take(10) else {
        filtered.substring(0, firstDot).take(10) + "." +
            filtered.substring(firstDot + 1).filter(Char::isDigit).take(2)
    }
}

private fun fullDateTime(timestamp: Long): String =
    SimpleDateFormat("d MMM yyyy, h:mm a", Locale.getDefault()).format(Date(timestamp))
