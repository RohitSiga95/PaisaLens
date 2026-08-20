package com.paisalens.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.CreditCard
import androidx.compose.material.icons.rounded.History
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import com.paisalens.app.data.model.AccountProfile
import com.paisalens.app.data.model.AccountType
import com.paisalens.app.data.model.CreditCardBill
import com.paisalens.app.data.model.CreditCardBillStatus
import com.paisalens.app.data.model.ReviewStatus
import com.paisalens.app.data.model.TransactionRecord
import com.paisalens.app.data.model.TransactionType
import com.paisalens.app.data.model.creditCardBillHistory
import com.paisalens.app.data.model.creditCardBillGroupKey
import com.paisalens.app.data.model.currentCreditCardBills
import com.paisalens.app.data.model.totalCurrentCreditCardDueMinor
import com.paisalens.app.data.model.unassignedCreditCardBills
import com.paisalens.app.ui.components.MoneyChartPoint
import com.paisalens.app.ui.components.MoneyLineChart
import com.paisalens.app.ui.components.MoneyText
import com.paisalens.app.ui.components.PaisaCard
import com.paisalens.app.ui.components.formatMoney
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
fun CreditCardBillsDashboardContent(
    bills: List<CreditCardBill>,
    transactions: List<TransactionRecord>,
    accounts: List<AccountProfile>,
    onMarkPaid: (Long) -> Unit,
    onAssignBill: (billId: Long, accountId: Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    var pendingPaidConfirmation by remember { mutableStateOf<CreditCardBill?>(null) }
    var selectedCardKey by remember { mutableStateOf<String?>(null) }
    var assigningBill by remember { mutableStateOf<CreditCardBill?>(null) }
    val latestByCard = remember(bills) { currentCreditCardBills(bills) }
    val unassigned = remember(bills) { unassignedCreditCardBills(bills) }
    val currentDue = remember(bills) { totalCurrentCreditCardDueMinor(bills) }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 18.dp, top = 8.dp, end = 18.dp, bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Surface(
                shape = MaterialTheme.shapes.extraLarge,
                color = Color.Transparent,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .then(
                            Modifier.background(
                                Brush.linearGradient(
                                    listOf(
                                        MaterialTheme.colorScheme.primaryContainer,
                                        MaterialTheme.colorScheme.tertiaryContainer,
                                    ),
                                ),
                                MaterialTheme.shapes.extraLarge,
                            ),
                        )
                        .padding(22.dp),
                ) {
                    Text("TOTAL CURRENT CARD DUE", style = MaterialTheme.typography.labelLarge)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        formatMoney(currentDue),
                        style = MaterialTheme.typography.displaySmall,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        buildString {
                            val dueCount = latestByCard.count { it.status == CreditCardBillStatus.DUE }
                            append("Across $dueCount assigned card bill${if (dueCount == 1) "" else "s"}")
                            if (unassigned.isNotEmpty()) append(" · ${unassigned.size} need a card")
                        },
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }

        if (unassigned.isNotEmpty()) {
            item {
                Text("Needs card assignment", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            }
            item {
                Text(
                    "These statement alerts did not include card digits. Choose the correct card before they affect totals or graphs.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            items(unassigned, key = { "unassigned:${it.id}" }) { bill ->
                UnassignedCreditCardBillCard(bill = bill, onAssign = { assigningBill = bill })
            }
        }

        item {
            Text("Card bills", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        }

        if (latestByCard.isEmpty() && unassigned.isEmpty()) {
            item {
                PaisaCard(Modifier.fillMaxWidth()) {
                    Column(
                        Modifier.fillMaxWidth().padding(20.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Icon(Icons.Rounded.CreditCard, contentDescription = null, modifier = Modifier.size(34.dp))
                        Text("No card bills detected yet", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "Scan statement-summary SMS alerts containing a total due and due date. They are parsed locally.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        } else {
            items(latestByCard, key = CreditCardBill::cardIdentityKey) { bill ->
                CreditCardBillCard(
                    bill = bill,
                    displayName = cardDisplayName(bill, accounts),
                    onOpen = { selectedCardKey = bill.creditCardBillGroupKey },
                    onPaidChange = { if (bill.status == CreditCardBillStatus.DUE) pendingPaidConfirmation = bill },
                )
            }
        }

        if (bills.any { it.status == CreditCardBillStatus.PAID }) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Rounded.History, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("Paid bill history is kept on-device inside each card.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }

    assigningBill?.let { bill ->
        AssignCreditCardBillDialog(
            bill = bill,
            accounts = accounts.filter { it.type == AccountType.CREDIT_CARD },
            onAssign = { accountId ->
                onAssignBill(bill.id, accountId)
                assigningBill = null
            },
            onDismiss = { assigningBill = null },
        )
    }

    pendingPaidConfirmation?.let { bill ->
        AlertDialog(
            onDismissRequest = { pendingPaidConfirmation = null },
            icon = { Icon(Icons.Rounded.CreditCard, contentDescription = null) },
            title = { Text("Mark this card bill paid?") },
            text = {
                Text(
                    "Confirm that ${formatMoney(bill.totalDueMinor)} due on ${LocalDate.ofEpochDay(bill.dueDateEpochDay).format(fullDate)} has been paid. The cycle stays in history.",
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        onMarkPaid(bill.id)
                        pendingPaidConfirmation = null
                    },
                ) { Text("Confirm paid") }
            },
            dismissButton = {
                TextButton(onClick = { pendingPaidConfirmation = null }) { Text("Cancel") }
            },
        )
    }

    selectedCardKey?.let { key ->
        val history = remember(bills, key) { creditCardBillHistory(bills, key) }
        CreditCardBillDetailSheet(
            history = history,
            transactions = transactions,
            accounts = accounts,
            onMarkPaid = { pendingPaidConfirmation = it },
            onDismiss = { selectedCardKey = null },
        )
    }
}

@Composable
private fun CreditCardBillCard(
    bill: CreditCardBill,
    displayName: String,
    onOpen: (() -> Unit)?,
    onPaidChange: () -> Unit,
) {
    val paid = bill.status == CreditCardBillStatus.PAID
    PaisaCard(Modifier.fillMaxWidth().alpha(if (paid) 0.62f else 1f)) {
        Column {
            Surface(
                onClick = { onOpen?.invoke() },
                enabled = onOpen != null,
                color = Color.Transparent,
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(start = 16.dp, top = 16.dp, end = 10.dp, bottom = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Icon(Icons.Rounded.CreditCard, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Column(Modifier.weight(1f)) {
                        Text(
                            displayName,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            textDecoration = if (paid) TextDecoration.LineThrough else null,
                        )
                        Text(
                            "Due ${LocalDate.ofEpochDay(bill.dueDateEpochDay).format(fullDate)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textDecoration = if (paid) TextDecoration.LineThrough else null,
                        )
                    }
                    MoneyText(bill.totalDueMinor, style = MaterialTheme.typography.titleLarge)
                    if (onOpen != null) {
                        Icon(Icons.Rounded.ChevronRight, contentDescription = "Open $displayName bill history")
                    }
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Surface(
                onClick = onPaidChange,
                enabled = !paid,
                color = Color.Transparent,
                modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(checked = paid, onCheckedChange = { onPaidChange() }, enabled = !paid)
                    Text(if (paid) "Paid · saved in history" else "Paid?", style = MaterialTheme.typography.labelLarge)
                    Spacer(Modifier.weight(1f))
                    bill.minimumDueMinor?.let {
                        Text("Minimum ${formatMoney(it)}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CreditCardBillDetailSheet(
    history: List<CreditCardBill>,
    transactions: List<TransactionRecord>,
    accounts: List<AccountProfile>,
    onMarkPaid: (CreditCardBill) -> Unit,
    onDismiss: () -> Unit,
) {
    val latest = history.firstOrNull() ?: return
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val points = remember(history, transactions) {
        monthlyCardSpendPoints(latest, transactions)
    }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxWidth().navigationBarsPadding(),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(cardDisplayName(latest, accounts), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                        Text("Bill history and card spending", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    IconButton(onClick = onDismiss, modifier = Modifier.size(48.dp)) {
                        Icon(Icons.Rounded.Close, contentDescription = "Close card bill history")
                    }
                }
            }
            item {
                PaisaCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Text("Six-month card spending", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.height(12.dp))
                        if (points.all { it.amountMinor == 0L }) {
                            Text("No linked card expenses are available for this graph yet.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        } else {
                            MoneyLineChart(points = points, modifier = Modifier.fillMaxWidth())
                        }
                    }
                }
            }
            item { Text("Statement cycles", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold) }
            items(history, key = CreditCardBill::id) { bill ->
                CreditCardBillCard(
                    bill = bill,
                    displayName = LocalDate.ofEpochDay(bill.dueDateEpochDay).format(fullDate),
                    onOpen = null,
                    onPaidChange = { if (bill.status == CreditCardBillStatus.DUE) onMarkPaid(bill) },
                )
            }
        }
    }
}

@Composable
private fun UnassignedCreditCardBillCard(
    bill: CreditCardBill,
    onAssign: () -> Unit,
) {
    PaisaCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Icon(Icons.Rounded.CreditCard, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Column(Modifier.weight(1f)) {
                    Text(bill.institutionName, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text(
                        "Due ${LocalDate.ofEpochDay(bill.dueDateEpochDay).format(fullDate)}",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                MoneyText(bill.totalDueMinor, style = MaterialTheme.typography.titleLarge)
            }
            Button(onClick = onAssign, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                Text("Choose credit card")
            }
        }
    }
}

@Composable
private fun AssignCreditCardBillDialog(
    bill: CreditCardBill,
    accounts: List<AccountProfile>,
    onAssign: (Long) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Choose credit card") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "Assign ${formatMoney(bill.totalDueMinor)} from ${bill.institutionName}. Future totals and history will use this card.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (accounts.isEmpty()) {
                    Text("Add a credit card in Settings → Accounts & cards first.")
                } else {
                    accounts.forEach { account ->
                        Surface(
                            onClick = { onAssign(account.id) },
                            shape = MaterialTheme.shapes.medium,
                            color = MaterialTheme.colorScheme.surfaceContainerHigh,
                            modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
                        ) {
                            Row(
                                Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(Icons.Rounded.CreditCard, contentDescription = null)
                                Spacer(Modifier.width(10.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(account.name, fontWeight = FontWeight.SemiBold)
                                    Text(
                                        listOfNotNull(account.institution, account.accountHint?.let { "•••• $it" })
                                            .joinToString(" · "),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

private fun monthlyCardSpendPoints(
    bill: CreditCardBill,
    transactions: List<TransactionRecord>,
): List<MoneyChartPoint> {
    val zoneId = ZoneId.systemDefault()
    val currentMonth = YearMonth.now()
    return (5 downTo 0).map { offset ->
        val month = currentMonth.minusMonths(offset.toLong())
        val amount = transactions.asSequence()
            .filter { it.type == TransactionType.EXPENSE && it.reviewStatus == ReviewStatus.CONFIRMED }
            .filter { transaction ->
                when {
                    bill.accountId != null && transaction.accountId != null -> transaction.accountId == bill.accountId
                    !bill.accountHint.isNullOrBlank() -> transaction.accountHint == bill.accountHint &&
                        transaction.institutionName.equals(bill.institutionName, ignoreCase = true)
                    else -> transaction.institutionName.equals(bill.institutionName, ignoreCase = true)
                }
            }
            .filter {
                YearMonth.from(Instant.ofEpochMilli(it.occurredAt).atZone(zoneId)) == month
            }
            .sumOf(TransactionRecord::amountMinor)
        MoneyChartPoint(month.format(monthLabel), amount)
    }
}

private fun cardDisplayName(bill: CreditCardBill, accounts: List<AccountProfile>): String {
    val accountName = bill.accountId?.let { accountId -> accounts.firstOrNull { it.id == accountId }?.name }
    val suffix = bill.accountHint?.let { " •$it" }.orEmpty()
    return (accountName ?: bill.institutionName) + suffix
}

private val fullDate: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM yyyy")
private val monthLabel: DateTimeFormatter = DateTimeFormatter.ofPattern("MMM")
