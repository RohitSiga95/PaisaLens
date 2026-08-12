package com.paisalens.app.ui

import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.automirrored.rounded.Rule
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.CreditCard
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.paisalens.app.data.model.AccountBalanceSnapshot
import com.paisalens.app.data.model.AccountProfile
import com.paisalens.app.data.model.AccountType
import com.paisalens.app.data.model.CustomCategory
import com.paisalens.app.data.model.ExpenseCategory
import com.paisalens.app.data.model.SmartCategoryRule
import com.paisalens.app.data.model.SmartRuleMatchType
import com.paisalens.app.data.model.TransactionRecord
import com.paisalens.app.data.model.buildDailyBalanceHistory
import com.paisalens.app.data.model.balanceSourceDisplayName
import com.paisalens.app.data.model.calculateCreditUtilization
import com.paisalens.app.data.model.previewSmartCategoryRuleApplication
import com.paisalens.app.ui.components.CategoryIcon
import com.paisalens.app.ui.components.CreditUtilizationBar
import com.paisalens.app.ui.components.CustomCategoryIcon
import com.paisalens.app.ui.components.MoneyChartPoint
import com.paisalens.app.ui.components.MoneyLineChart
import com.paisalens.app.ui.components.PaisaCard
import com.paisalens.app.ui.components.categoryColor
import com.paisalens.app.ui.components.customCategoryColor
import com.paisalens.app.ui.components.formatMoney
import java.math.BigDecimal
import java.math.RoundingMode
import java.text.SimpleDateFormat
import java.time.ZoneId
import java.util.Date
import java.util.Locale

private enum class BalanceHistoryRange(val label: String, val days: Int?) {
    WEEK("7D", 7),
    MONTH("30D", 30),
    QUARTER("3M", 90),
    ALL("All", null),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AccountFinanceSheet(
    account: AccountProfile,
    history: List<AccountBalanceSnapshot>,
    onUpdateAccount: (AccountProfile) -> Unit,
    onDismiss: () -> Unit,
) {
    var selectedRange by remember { mutableStateOf(BalanceHistoryRange.MONTH) }
    var showLimitDialog by remember { mutableStateOf(false) }
    val accountHistory = remember(history, account.id) {
        history.filter { it.accountId == account.id }.sortedByDescending { it.recordedAt }
    }
    val filteredHistory = remember(accountHistory, selectedRange) {
        val cutoff = selectedRange.days?.let { System.currentTimeMillis() - it * DAY_MILLIS }
        accountHistory.filter { cutoff == null || it.recordedAt >= cutoff }
    }
    val chartPoints = remember(filteredHistory, account.id, account.type) {
        val daily = buildDailyBalanceHistory(filteredHistory, ZoneId.systemDefault(), account.id)
        evenlySample(daily, maximumPoints = 36)
            .mapNotNull { point ->
                val amount = if (account.type == AccountType.CREDIT_CARD) point.availableCreditMinor else point.balanceMinor
                amount?.let {
                    MoneyChartPoint(shortDate(point.recordedAt), it)
                }
            }
    }
    val utilization = remember(account) {
        calculateCreditUtilization(account.id, account.availableCreditMinor, account.creditLimitMinor)
    }
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
                title = account.name,
                subtitle = account.type.label + (account.accountHint?.let { " · •••• $it" } ?: ""),
                onDismiss = onDismiss,
            )
            LazyColumn(
                modifier = Modifier.fillMaxWidth(),
                contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 32.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                item {
                    PrivateDataNotice("Balance history stays in PaisaLens' private app storage and never leaves this phone.")
                }
                item {
                    PaisaCard(modifier = Modifier.fillMaxWidth()) {
                        Column(
                            modifier = Modifier.padding(18.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text(
                                if (account.type == AccountType.CREDIT_CARD) "Available credit" else "Current balance",
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                account.currentFinanceAmount()?.let(::formatMoney) ?: "Not available",
                                style = MaterialTheme.typography.headlineMedium,
                                fontWeight = FontWeight.Bold,
                            )
                            Text(
                                account.availabilityFetchedAt?.let { timestamp ->
                                    val action = if (
                                        balanceSourceDisplayName(account.availabilitySender)
                                            ?.startsWith("User entered") == true
                                    ) {
                                        "Entered"
                                    } else {
                                        "Fetched"
                                    }
                                    "$action ${fullDateTime(timestamp)}"
                                } ?: "No balance update has been saved yet",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            balanceSourceDisplayName(account.availabilitySender)
                                ?.takeIf(String::isNotBlank)
                                ?.let { sender ->
                                Text(
                                    "Source: $sender",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
                if (account.type == AccountType.CREDIT_CARD) {
                    item {
                        PaisaCard(modifier = Modifier.fillMaxWidth()) {
                            Column(
                                modifier = Modifier.padding(18.dp),
                                verticalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text("Credit utilisation", style = MaterialTheme.typography.titleMedium)
                                        Text(
                                            account.creditLimitMinor?.let { "Limit ${formatMoney(it)}" }
                                                ?: "Add the total card limit for accurate tracking",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                    IconButton(onClick = { showLimitDialog = true }) {
                                        Icon(Icons.Rounded.Edit, contentDescription = "Edit total credit limit")
                                    }
                                }
                                utilization.utilizationBasisPoints?.let { basisPoints ->
                                    CreditUtilizationBar(
                                        basisPoints = basisPoints,
                                        band = utilization.band,
                                        modifier = Modifier.fillMaxWidth(),
                                    )
                                } ?: Text(
                                    "Utilisation will appear after a total limit and available credit are known.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                if (account.creditLimitMinor != null) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                    ) {
                                        FinanceValue("Used", utilization.usedMinor)
                                        FinanceValue("Available", utilization.availableCreditMinor)
                                    }
                                }
                            }
                        }
                    }
                }
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text("Balance trend", style = MaterialTheme.typography.titleLarge)
                            Icon(Icons.Rounded.History, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        }
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(BalanceHistoryRange.entries) { range ->
                                FilterChip(
                                    selected = selectedRange == range,
                                    onClick = { selectedRange = range },
                                    modifier = Modifier.heightIn(min = 48.dp),
                                    label = { Text(range.label) },
                                )
                            }
                        }
                    }
                }
                item {
                    PaisaCard(modifier = Modifier.fillMaxWidth()) {
                        if (chartPoints.isEmpty()) {
                            EmptyFinanceState(
                                title = "No history in this range",
                                detail = "New SMS and user-entered balance updates will add points to this chart.",
                            )
                        } else {
                            Column(Modifier.padding(16.dp)) {
                                Text(
                                    if (account.type == AccountType.CREDIT_CARD) "Available credit over time" else "Balance over time",
                                    style = MaterialTheme.typography.titleMedium,
                                )
                                Spacer(Modifier.height(14.dp))
                                MoneyLineChart(points = chartPoints, modifier = Modifier.fillMaxWidth())
                            }
                        }
                    }
                }
                item {
                    Text("Balance history", style = MaterialTheme.typography.titleLarge)
                }
                if (filteredHistory.isEmpty()) {
                    item {
                        Text(
                            "There are no saved balance updates for ${selectedRange.label}.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                } else {
                    items(filteredHistory.take(60), key = { it.id }) { snapshot ->
                        BalanceHistoryRow(snapshot = snapshot, accountType = account.type)
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
private fun FinancialSheetHeader(title: String, subtitle: String, onDismiss: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, bottom = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        IconButton(onClick = onDismiss) { Icon(Icons.Rounded.Close, contentDescription = "Close") }
    }
}

private fun AccountProfile.currentFinanceAmount(): Long? =
    if (type == AccountType.CREDIT_CARD) availableCreditMinor else balanceMinor

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

private fun <T> evenlySample(values: List<T>, maximumPoints: Int): List<T> {
    if (values.size <= maximumPoints) return values
    val lastIndex = values.lastIndex
    return (0 until maximumPoints).map { slot ->
        values[(slot.toLong() * lastIndex / (maximumPoints - 1)).toInt()]
    }.distinct()
}

private fun shortDate(timestamp: Long): String =
    SimpleDateFormat("d MMM", Locale.getDefault()).format(Date(timestamp))

private fun fullDateTime(timestamp: Long): String =
    SimpleDateFormat("d MMM yyyy, h:mm a", Locale.getDefault()).format(Date(timestamp))

private const val DAY_MILLIS = 24L * 60L * 60L * 1000L
