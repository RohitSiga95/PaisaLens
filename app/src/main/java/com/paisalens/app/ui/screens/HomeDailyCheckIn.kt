package com.paisalens.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.automirrored.rounded.FactCheck
import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.material.icons.rounded.CreditCard
import androidx.compose.material.icons.rounded.Payments
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.paisalens.app.data.model.HomeDailyCheckIn
import com.paisalens.app.data.model.HomeTimelineItem
import com.paisalens.app.data.model.TransactionRecord
import com.paisalens.app.ui.SharedFinanceSheetHeader
import com.paisalens.app.ui.components.PaisaCard
import com.paisalens.app.ui.components.TransactionRow
import com.paisalens.app.ui.components.formatMoney
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

private enum class CheckInDetail { SPENDING, BILLS, REVIEW }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun HomeDailyCheckInCard(
    checkIn: HomeDailyCheckIn,
    onOpenTransaction: (TransactionRecord) -> Unit,
    onOpenDueBill: (HomeTimelineItem) -> Unit,
    onReviewTransactions: () -> Unit,
    onReviewCardPayments: () -> Unit,
) {
    var detail by remember { mutableStateOf<CheckInDetail?>(null) }
    val spendingLabel = if (checkIn.hasPreviousVisit) "Spent since last visit" else "Spent today"
    PaisaCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                "Daily check-in",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.semantics { heading() }.padding(bottom = 4.dp),
            )
            CheckInRow(
                icon = Icons.Rounded.Payments,
                label = spendingLabel,
                value = formatMoney(checkIn.spendingMinor),
                onClick = { detail = CheckInDetail.SPENDING },
            )
            CheckInRow(
                icon = Icons.Rounded.CalendarMonth,
                label = "Bills due in the next 7 days",
                value = formatMoney(checkIn.dueMinor),
                onClick = { detail = CheckInDetail.BILLS },
            )
            CheckInRow(
                icon = Icons.AutoMirrored.Rounded.FactCheck,
                label = "Items to review",
                value = checkIn.reviewCount.toString(),
                onClick = { detail = CheckInDetail.REVIEW },
            )
        }
    }

    val openDetail = detail ?: return
    val title = when (openDetail) {
        CheckInDetail.SPENDING -> spendingLabel
        CheckInDetail.BILLS -> "Bills due in the next 7 days"
        CheckInDetail.REVIEW -> "Items to review"
    }
    ModalBottomSheet(
        onDismissRequest = { detail = null },
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        SharedFinanceSheetHeader(
            title = title,
            subtitle = when (openDetail) {
                CheckInDetail.SPENDING -> formatMoney(checkIn.spendingMinor)
                CheckInDetail.BILLS -> formatMoney(checkIn.dueMinor)
                CheckInDetail.REVIEW -> "Review transactions or confirm card payments"
            },
            onDismiss = { detail = null },
        )
        LazyColumn(
            modifier = Modifier.fillMaxWidth().navigationBarsPadding(),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            when (openDetail) {
                CheckInDetail.SPENDING -> {
                    if (checkIn.spending.isEmpty()) item {
                        Text("No confirmed spending in this period.")
                    }
                    items(checkIn.spending, key = TransactionRecord::id) { record ->
                        TransactionRow(record, onClick = { detail = null; onOpenTransaction(record) })
                    }
                }
                CheckInDetail.BILLS -> {
                    if (checkIn.dueBills.isEmpty()) item {
                        Text("No unpaid bills due in the next 7 days.")
                    }
                    items(checkIn.dueBills, key = HomeTimelineItem::stableId) { bill ->
                        CheckInRow(
                            icon = Icons.Rounded.CalendarMonth,
                            label = bill.title,
                            value = "${formatMoney(bill.amountMinor)} · ${bill.date.format(dueDateFormat)}",
                            onClick = { detail = null; onOpenDueBill(bill) },
                        )
                    }
                }
                CheckInDetail.REVIEW -> {
                    if (checkIn.reviewCount == 0) item { Text("You’re caught up.") }
                    if (checkIn.transactionReviewCount > 0) item {
                        CheckInRow(
                            icon = Icons.AutoMirrored.Rounded.FactCheck,
                            label = "Transactions to review",
                            value = checkIn.transactionReviewCount.toString(),
                            onClick = { detail = null; onReviewTransactions() },
                        )
                    }
                    if (checkIn.paymentReviewCount > 0) item {
                        CheckInRow(
                            icon = Icons.Rounded.CreditCard,
                            label = "Card payments to review",
                            value = checkIn.paymentReviewCount.toString(),
                            onClick = { detail = null; onReviewCardPayments() },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CheckInRow(icon: ImageVector, label: String, value: String, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceVariant,
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Column(Modifier.weight(1f)) {
                Text(label, style = MaterialTheme.typography.labelLarge)
                Text(value, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
            }
            Icon(Icons.AutoMirrored.Rounded.ArrowForward, contentDescription = null)
        }
    }
}

private val dueDateFormat = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)
