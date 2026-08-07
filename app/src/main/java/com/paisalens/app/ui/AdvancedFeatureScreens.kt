package com.paisalens.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.material.icons.rounded.ChevronLeft
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.FileUpload
import androidx.compose.material.icons.rounded.Flight
import androidx.compose.material.icons.rounded.Merge
import androidx.compose.material.icons.rounded.Payments
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.paisalens.app.data.model.AccountProfile
import com.paisalens.app.data.model.ExchangeRate
import com.paisalens.app.data.model.LoanAccount
import com.paisalens.app.data.model.MerchantAliasRule
import com.paisalens.app.data.model.SpendingInsight
import com.paisalens.app.data.model.StatementImportPreview
import com.paisalens.app.data.model.TransactionRecord
import com.paisalens.app.data.model.buildCalendarSpend
import com.paisalens.app.data.model.buildMerchantCleanupGroups
import com.paisalens.app.data.model.buildSpendingAnalytics
import com.paisalens.app.data.model.calculateEmiMinor
import com.paisalens.app.ui.components.MoneyText
import com.paisalens.app.ui.components.PaisaCard
import com.paisalens.app.ui.components.TransactionRow
import com.paisalens.app.ui.components.formatMoney
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToLong

@Composable
fun AnalyticsScreen(
    transactions: List<TransactionRecord>,
    insights: List<SpendingInsight>,
    onTransactionClick: (TransactionRecord) -> Unit,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
    showHeader: Boolean = true,
) {
    val analytics = remember(transactions) { buildSpendingAnalytics(transactions) }
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(18.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        if (showHeader) {
            item {
                if (onBack != null) {
                    FeatureTopBar("Analytics", "Deeper patterns from confirmed expenses", onBack)
                } else {
                    Column {
                        Text("Insights", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                        Text(
                            "Deeper patterns from confirmed expenses",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                AnalyticsMetric("This month", analytics.currentMonthMinor, Modifier.weight(1f))
                AnalyticsMetric("Projected", analytics.projectedMonthMinor, Modifier.weight(1f))
            }
        }
        item {
            PaisaCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Six-month spending", style = MaterialTheme.typography.titleLarge)
                    Text(
                        analytics.monthOverMonthPercent?.let { value ->
                            "${if (value >= 0) "+" else ""}${"%.1f".format(value)}% versus last month"
                        } ?: "A comparison appears after two months of activity",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    MonthlyBars(analytics.monthlyTrend.map { it.amountMinor })
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        analytics.monthlyTrend.forEach { Text(it.month.format(DateTimeFormatter.ofPattern("MMM")), style = MaterialTheme.typography.labelSmall) }
                    }
                }
            }
        }
        if (analytics.categoryBreakdown.isNotEmpty()) {
            item { RankedSpendCard("Category breakdown", analytics.categoryBreakdown.map { it.label to it.amountMinor }) }
        }
        if (analytics.topMerchants.isNotEmpty()) {
            item { RankedSpendCard("Top merchants", analytics.topMerchants.map { it.label to it.amountMinor }) }
        }
        analytics.largestExpense?.let { largest ->
            item {
                Text("Largest expense this month", style = MaterialTheme.typography.titleLarge)
                PaisaCard(Modifier.fillMaxWidth()) {
                    TransactionRow(largest, onClick = { onTransactionClick(largest) }, modifier = Modifier.padding(12.dp))
                }
            }
        }
        if (insights.isNotEmpty()) {
            item { Text("On-device insights", style = MaterialTheme.typography.titleLarge) }
            items(insights) { insight -> InsightRow(insight) }
        }
    }
}

@Composable
private fun AnalyticsMetric(label: String, value: Long, modifier: Modifier) {
    PaisaCard(modifier) {
        Column(Modifier.padding(16.dp)) {
            Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(5.dp))
            MoneyText(value, style = MaterialTheme.typography.titleLarge)
        }
    }
}

@Composable
private fun MonthlyBars(values: List<Long>) {
    val primary = MaterialTheme.colorScheme.primary
    val secondary = MaterialTheme.colorScheme.secondary
    val max = values.maxOrNull()?.coerceAtLeast(1) ?: 1
    Canvas(
        Modifier
            .fillMaxWidth()
            .height(150.dp)
            .semantics { contentDescription = "Six month spending chart: ${values.joinToString { formatMoney(it) }}" },
    ) {
        val gap = 10.dp.toPx()
        val barWidth = (size.width - gap * (values.size - 1)) / values.size
        values.forEachIndexed { index, value ->
            val height = size.height * value.toFloat() / max
            drawRoundRect(
                color = if (index == values.lastIndex) secondary else primary.copy(alpha = 0.56f),
                topLeft = androidx.compose.ui.geometry.Offset(index * (barWidth + gap), size.height - height),
                size = androidx.compose.ui.geometry.Size(barWidth, height.coerceAtLeast(3.dp.toPx())),
                cornerRadius = CornerRadius(8.dp.toPx()),
            )
        }
    }
}

@Composable
private fun RankedSpendCard(title: String, values: List<Pair<String, Long>>) {
    val max = values.maxOfOrNull { it.second }?.coerceAtLeast(1) ?: 1
    PaisaCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(title, style = MaterialTheme.typography.titleLarge)
            values.take(6).forEach { (label, amount) ->
                Column {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(label, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(formatMoney(amount), fontWeight = FontWeight.SemiBold)
                    }
                    Spacer(Modifier.height(5.dp))
                    LinearProgressIndicator(
                        progress = { amount.toFloat() / max },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }
}

@Composable
private fun InsightRow(insight: SpendingInsight) {
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.55f),
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        shape = MaterialTheme.shapes.large,
    ) {
        Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(Icons.Rounded.AutoAwesome, contentDescription = null)
            Column(Modifier.weight(1f)) {
                Text(insight.title, style = MaterialTheme.typography.titleMedium)
                Text(insight.detail, style = MaterialTheme.typography.bodyMedium)
                insight.amountMinor?.let { Text(formatMoney(it), fontWeight = FontWeight.SemiBold) }
            }
        }
    }
}

@Composable
fun CalendarScreen(
    transactions: List<TransactionRecord>,
    onBack: () -> Unit,
    onTransactionClick: (TransactionRecord) -> Unit,
) {
    var month by remember { mutableStateOf(YearMonth.now()) }
    var selectedDate by remember { mutableStateOf(LocalDate.now()) }
    val daily = remember(transactions, month) { buildCalendarSpend(transactions, month) }
    val firstOffset = month.atDay(1).dayOfWeek.value - 1
    val cells = List(firstOffset) { null } + (1..month.lengthOfMonth()).map(month::atDay)
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(18.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item { FeatureTopBar("Calendar", "Daily spending and transaction history", onBack) }
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { month = month.minusMonths(1); selectedDate = month.atDay(1) }) { Icon(Icons.Rounded.ChevronLeft, "Previous month") }
                Text(month.format(DateTimeFormatter.ofPattern("MMMM uuuu")), style = MaterialTheme.typography.titleLarge)
                IconButton(onClick = { month = month.plusMonths(1); selectedDate = month.atDay(1) }) { Icon(Icons.Rounded.ChevronRight, "Next month") }
            }
        }
        item {
            Column {
                Row(Modifier.fillMaxWidth()) {
                    listOf("M", "T", "W", "T", "F", "S", "S").forEach { Text(it, Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
                cells.chunked(7).forEach { week ->
                    Row(Modifier.fillMaxWidth()) {
                        (week + List(7 - week.size) { null }).forEach { date ->
                            Box(Modifier.weight(1f).padding(2.dp)) {
                                if (date != null) {
                                    val spend = daily[date]?.amountMinor ?: 0
                                    Surface(
                                        onClick = { selectedDate = date },
                                        modifier = Modifier.fillMaxWidth().height(70.dp),
                                        color = if (selectedDate == date) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
                                        shape = MaterialTheme.shapes.medium,
                                    ) {
                                        Column(Modifier.padding(7.dp), verticalArrangement = Arrangement.SpaceBetween) {
                                            Text(date.dayOfMonth.toString(), style = MaterialTheme.typography.labelLarge)
                                            if (spend > 0) Text(compactMoney(spend), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        item {
            Text(selectedDate.format(DateTimeFormatter.ofPattern("EEEE, d MMMM")), style = MaterialTheme.typography.titleLarge)
        }
        val selectedRows = daily[selectedDate]?.transactions.orEmpty()
        if (selectedRows.isEmpty()) {
            item { Text("No confirmed expenses on this day.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        } else {
            items(selectedRows) { transaction ->
                PaisaCard(Modifier.fillMaxWidth()) {
                    TransactionRow(transaction, { onTransactionClick(transaction) }, Modifier.padding(10.dp))
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MerchantCleanupSheet(
    transactions: List<TransactionRecord>,
    aliases: List<MerchantAliasRule>,
    onRename: (String, String) -> Unit,
    onDeleteAlias: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var search by remember { mutableStateOf("") }
    var editing by remember { mutableStateOf<String?>(null) }
    var canonical by remember { mutableStateOf("") }
    val groups = remember(transactions, search) {
        buildMerchantCleanupGroups(transactions).filter { it.merchant.contains(search, ignoreCase = true) }
    }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        LazyColumn(
            modifier = Modifier.fillMaxWidth().navigationBarsPadding(),
            contentPadding = PaddingValues(20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item { SheetHeader("Merchant cleanup", onDismiss) }
            item { Text("Rename or merge a merchant once. The rule also cleans future SMS and statement imports.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
            item { OutlinedTextField(search, { search = it }, label = { Text("Search merchants") }, modifier = Modifier.fillMaxWidth(), singleLine = true) }
            if (aliases.isNotEmpty()) {
                item { Text("Saved cleanup rules", style = MaterialTheme.typography.titleMedium) }
                items(aliases) { rule ->
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.Merge, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                            Text("${rule.aliasName} → ${rule.canonicalName}")
                        }
                        IconButton(onClick = { onDeleteAlias(rule.aliasKey) }) { Icon(Icons.Rounded.DeleteOutline, "Delete cleanup rule") }
                    }
                }
                item { HorizontalDivider() }
            }
            items(groups) { group ->
                Surface(
                    onClick = { editing = group.merchant; canonical = group.merchant },
                    shape = MaterialTheme.shapes.large,
                    color = MaterialTheme.colorScheme.surfaceContainer,
                ) {
                    Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(group.merchant, style = MaterialTheme.typography.titleMedium)
                            Text("${group.transactionCount} transactions · ${formatMoney(group.totalMinor)}", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Icon(Icons.Rounded.Edit, contentDescription = null)
                    }
                }
            }
        }
    }
    editing?.let { alias ->
        AlertDialog(
            onDismissRequest = { editing = null },
            title = { Text("Rename or merge merchant") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("All transactions named $alias will use the canonical name below.")
                    OutlinedTextField(canonical, { canonical = it }, label = { Text("Canonical merchant name") }, singleLine = true)
                }
            },
            confirmButton = { Button(enabled = canonical.isNotBlank(), onClick = { onRename(alias, canonical); editing = null }) { Text("Apply to all") } },
            dismissButton = { TextButton(onClick = { editing = null }) { Text("Cancel") } },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LoanManagerSheet(
    loans: List<LoanAccount>,
    accounts: List<AccountProfile>,
    onSave: (LoanAccount) -> Unit,
    onDelete: (Long) -> Unit,
    onDismiss: () -> Unit,
) {
    var editing by remember { mutableStateOf<LoanAccount?>(null) }
    var adding by remember { mutableStateOf(false) }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        LazyColumn(
            modifier = Modifier.fillMaxWidth().navigationBarsPadding(),
            contentPadding = PaddingValues(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { SheetHeader("EMI & loan tracker", onDismiss) }
            item { Button(onClick = { adding = true }, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Rounded.Add, null); Spacer(Modifier.size(8.dp)); Text("Add loan") } }
            if (loans.isEmpty()) item { Text("Track principal, rate, EMI progress, and the next due date.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
            items(loans) { loan ->
                PaisaCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Column(Modifier.weight(1f)) { Text(loan.name, style = MaterialTheme.typography.titleMedium); Text(loan.lender, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                            IconButton(onClick = { editing = loan }) { Icon(Icons.Rounded.Edit, "Edit loan") }
                        }
                        MoneyText(loan.emiMinor, prefix = "EMI ", style = MaterialTheme.typography.titleMedium)
                        LinearProgressIndicator(progress = { loan.progress.coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                        Text("${loan.paidInstallments}/${loan.tenureMonths} installments · next ${loan.nextDueDate.format(DateTimeFormatter.ofPattern("d MMM uuuu"))}")
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(
                                onClick = { if (loan.paidInstallments < loan.tenureMonths) onSave(loan.copy(paidInstallments = loan.paidInstallments + 1)) },
                                enabled = loan.paidInstallments < loan.tenureMonths,
                                modifier = Modifier.weight(1f),
                            ) { Text("Mark EMI paid") }
                            IconButton(onClick = { onDelete(loan.id) }) { Icon(Icons.Rounded.DeleteOutline, "Delete loan") }
                        }
                    }
                }
            }
        }
    }
    if (adding || editing != null) {
        LoanEditorDialog(editing, accounts, onDismiss = { adding = false; editing = null }, onSave = { onSave(it); adding = false; editing = null })
    }
}

@Composable
private fun LoanEditorDialog(
    existing: LoanAccount?,
    accounts: List<AccountProfile>,
    onDismiss: () -> Unit,
    onSave: (LoanAccount) -> Unit,
) {
    var name by remember(existing) { mutableStateOf(existing?.name.orEmpty()) }
    var lender by remember(existing) { mutableStateOf(existing?.lender.orEmpty()) }
    var principal by remember(existing) { mutableStateOf(existing?.principalMinor?.div(100.0)?.toString().orEmpty()) }
    var rate by remember(existing) { mutableStateOf(existing?.annualRateBasisPoints?.div(100.0)?.toString().orEmpty()) }
    var tenure by remember(existing) { mutableStateOf(existing?.tenureMonths?.toString().orEmpty()) }
    var paid by remember(existing) { mutableStateOf(existing?.paidInstallments?.toString() ?: "0") }
    var startDate by remember(existing) { mutableStateOf(existing?.let { LocalDate.ofEpochDay(it.startDateEpochDay).toString() } ?: LocalDate.now().toString()) }
    var accountId by remember(existing) { mutableStateOf(existing?.accountId) }
    val principalMinor = principal.toDoubleOrNull()?.times(100)?.roundToLong()
    val rateBps = rate.toDoubleOrNull()?.times(100)?.roundToLong()?.toInt()
    val months = tenure.toIntOrNull()
    val date = runCatching { LocalDate.parse(startDate) }.getOrNull()
    val emi = if (principalMinor != null && rateBps != null && months != null) calculateEmiMinor(principalMinor, rateBps, months) else 0
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (existing == null) "Add loan" else "Edit loan") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text("Loan name") }, singleLine = true)
                OutlinedTextField(lender, { lender = it }, label = { Text("Lender") }, singleLine = true)
                OutlinedTextField(principal, { principal = it }, label = { Text("Principal") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), singleLine = true)
                OutlinedTextField(rate, { rate = it }, label = { Text("Annual interest %") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), singleLine = true)
                OutlinedTextField(tenure, { tenure = it }, label = { Text("Tenure in months") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true)
                OutlinedTextField(paid, { paid = it }, label = { Text("Installments already paid") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true)
                OutlinedTextField(startDate, { startDate = it }, label = { Text("First due date (YYYY-MM-DD)") }, singleLine = true)
                if (accounts.isNotEmpty()) {
                    Text("Payment account", style = MaterialTheme.typography.labelLarge)
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        item { FilterChip(selected = accountId == null, onClick = { accountId = null }, label = { Text("None") }) }
                        items(accounts) { account -> FilterChip(selected = accountId == account.id, onClick = { accountId = account.id }, label = { Text(account.name) }) }
                    }
                }
                if (emi > 0) Text("Calculated EMI: ${formatMoney(emi)}", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
            }
        },
        confirmButton = {
            Button(
                enabled = name.isNotBlank() && lender.isNotBlank() && principalMinor != null && principalMinor > 0 && rateBps != null && months != null && months > 0 && date != null,
                onClick = {
                    onSave(
                        LoanAccount(
                            id = existing?.id ?: 0,
                            name = name,
                            lender = lender,
                            principalMinor = principalMinor!!,
                            annualRateBasisPoints = rateBps!!,
                            tenureMonths = months!!,
                            startDateEpochDay = date!!.toEpochDay(),
                            emiMinor = emi,
                            paidInstallments = paid.toIntOrNull()?.coerceIn(0, months) ?: 0,
                            accountId = accountId,
                            notes = existing?.notes,
                        ),
                    )
                },
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TravelModeSheet(
    enabled: Boolean,
    baseCurrency: String,
    rates: List<ExchangeRate>,
    refreshing: Boolean,
    onEnabledChange: (Boolean) -> Unit,
    onRefresh: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val currencies = listOf("INR", "USD", "EUR", "GBP", "AED", "SGD", "JPY", "AUD", "CAD")
    var selectedForeign by remember { mutableStateOf("USD") }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            Modifier.fillMaxWidth().navigationBarsPadding().verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            SheetHeader("Travel mode", onDismiss)
            Text("Rates refresh only when you tap the button. Transaction details are never sent; the request contains only the currency pair.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) { Text("Multi-currency entry", style = MaterialTheme.typography.titleMedium); Text("Store the original amount and converted base value.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
                Switch(enabled, onEnabledChange)
            }
            Text("Home currency · $baseCurrency", style = MaterialTheme.typography.titleMedium)
            Text("Foreign purchases are converted into $baseCurrency so the main dashboard remains comparable.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("Refresh a latest reference rate", style = MaterialTheme.typography.labelLarge)
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(currencies.filter { it != baseCurrency }) { code -> FilterChip(selected = selectedForeign == code, onClick = { selectedForeign = code }, label = { Text(code) }) }
            }
            Button(onClick = { onRefresh(selectedForeign) }, enabled = !refreshing, modifier = Modifier.fillMaxWidth()) {
                if (refreshing) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp) else Icon(Icons.Rounded.Refresh, null)
                Spacer(Modifier.size(8.dp)); Text(if (refreshing) "Refreshing…" else "Refresh $selectedForeign/$baseCurrency")
            }
            rates.filter { it.baseCurrency == baseCurrency }.forEach { rate ->
                PaisaCard(Modifier.fillMaxWidth()) {
                    Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("1 ${rate.quoteCurrency}")
                        Column(horizontalAlignment = Alignment.End) {
                            Text("${"%.4f".format(rate.rate)} ${rate.baseCurrency}", fontWeight = FontWeight.Bold)
                            Text("Reference date ${rate.rateDate}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
            Text("Provider: Frankfurter over HTTPS. Reference rates can differ from card-network or bank settlement rates.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StatementImportSheet(
    accounts: List<AccountProfile>,
    preview: StatementImportPreview?,
    importing: Boolean,
    onChooseFile: (Long?) -> Unit,
    onConfirm: () -> Unit,
    onCancelPreview: () -> Unit,
    onDismiss: () -> Unit,
) {
    var accountId by remember { mutableStateOf<Long?>(accounts.firstOrNull()?.id) }
    ModalBottomSheet(onDismissRequest = { onCancelPreview(); onDismiss() }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            Modifier.fillMaxWidth().navigationBarsPadding().verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            SheetHeader("Import statement") { onCancelPreview(); onDismiss() }
            if (preview == null) {
                Text("Import CSV or XLSX bank statements. PaisaLens detects common date, narration, debit, credit, amount, and currency columns locally.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("Assign account", style = MaterialTheme.typography.labelLarge)
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    item { FilterChip(selected = accountId == null, onClick = { accountId = null }, label = { Text("Unassigned") }) }
                    items(accounts) { account -> FilterChip(selected = accountId == account.id, onClick = { accountId = account.id }, label = { Text(account.name) }) }
                }
                Button(onClick = { onChooseFile(accountId) }, enabled = !importing, modifier = Modifier.fillMaxWidth()) {
                    if (importing) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    } else {
                        Icon(Icons.Rounded.FileUpload, null)
                    }
                    Spacer(Modifier.size(8.dp)); Text(if (importing) "Reading statement…" else "Choose CSV or XLSX")
                }
            } else {
                Text("Preview", style = MaterialTheme.typography.titleLarge)
                Text("${preview.rows.size} transaction${if (preview.rows.size == 1) "" else "s"} ready · ${preview.skippedRows} skipped")
                preview.warnings.forEach { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                preview.rows.take(8).forEach { row ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(row.transaction.merchant, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(formatMoney(row.transaction.amountMinor), fontWeight = FontWeight.SemiBold)
                    }
                }
                if (preview.rows.size > 8) Text("+ ${preview.rows.size - 8} more", color = MaterialTheme.colorScheme.onSurfaceVariant)
                Button(onClick = onConfirm, enabled = preview.rows.isNotEmpty() && !importing, modifier = Modifier.fillMaxWidth()) {
                    if (importing) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.size(8.dp))
                    Text(if (importing) "Importing…" else "Import transactions")
                }
                OutlinedButton(onClick = onCancelPreview, enabled = !importing, modifier = Modifier.fillMaxWidth()) { Text("Choose another file") }
            }
        }
    }
}

@Composable
private fun FeatureTopBar(title: String, subtitle: String, onBack: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") }
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Text(subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun SheetHeader(title: String, onDismiss: () -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        IconButton(onClick = onDismiss) { Icon(Icons.Rounded.Close, "Close") }
    }
}

private fun compactMoney(amountMinor: Long): String = when {
    amountMinor >= 10_000_000 -> "₹${"%.1f".format(amountMinor / 10_000_000.0)}L"
    amountMinor >= 100_000 -> "₹${"%.1f".format(amountMinor / 100_000.0)}k"
    else -> "₹${amountMinor / 100}"
}
