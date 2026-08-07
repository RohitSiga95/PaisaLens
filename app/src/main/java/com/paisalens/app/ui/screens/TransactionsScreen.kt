package com.paisalens.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ReceiptLong
import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.paisalens.app.data.model.MerchantTransactionGroup
import com.paisalens.app.data.model.TransactionRecord
import com.paisalens.app.data.model.TransactionType
import com.paisalens.app.data.model.ReviewStatus
import com.paisalens.app.data.model.categoryLabel
import com.paisalens.app.ui.components.EmptyState
import com.paisalens.app.ui.components.PaisaCard
import com.paisalens.app.ui.components.TransactionRow

private enum class TransactionFilter(val label: String) {
    ALL("All"),
    EXPENSE("Expenses"),
    INCOME("Income"),
    REFUND("Refunds"),
    TRANSFER("Transfers"),
    REVIEW("Needs review"),
}

@Composable
fun TransactionsScreen(
    transactions: List<TransactionRecord>,
    uncategorizedMerchants: List<MerchantTransactionGroup>,
    onCategorizeMerchant: (MerchantTransactionGroup) -> Unit,
    onCalendar: () -> Unit,
    onTransactionClick: (TransactionRecord) -> Unit,
) {
    var query by remember { mutableStateOf("") }
    var selectedFilter by remember { mutableStateOf(TransactionFilter.ALL) }

    val filtered = remember(transactions, query, selectedFilter) {
        transactions.filter { transaction ->
            val queryMatch = query.isBlank() ||
                transaction.merchant.contains(query, ignoreCase = true) ||
                transaction.categoryLabel().contains(query, ignoreCase = true) ||
                transaction.sender.contains(query, ignoreCase = true) ||
                transaction.note?.contains(query, ignoreCase = true) == true ||
                transaction.accountName?.contains(query, ignoreCase = true) == true ||
                transaction.tags.any { it.contains(query, ignoreCase = true) }
            val typeMatch = when (selectedFilter) {
                TransactionFilter.ALL -> true
                TransactionFilter.EXPENSE -> transaction.type == TransactionType.EXPENSE
                TransactionFilter.INCOME -> transaction.type == TransactionType.INCOME
                TransactionFilter.REFUND -> transaction.type == TransactionType.REFUND
                TransactionFilter.TRANSFER -> transaction.type == TransactionType.TRANSFER
                TransactionFilter.REVIEW -> transaction.reviewStatus == ReviewStatus.NEEDS_REVIEW
            }
            queryMatch && typeMatch
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(top = 12.dp),
    ) {
        Column(modifier = Modifier.padding(horizontal = 18.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        text = "Activity",
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        text = "Every transaction, easy to find",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = onCalendar) {
                    Icon(Icons.Rounded.CalendarMonth, contentDescription = "Open spending calendar")
                }
            }
            Spacer(Modifier.height(16.dp))
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                leadingIcon = {
                    Icon(Icons.Rounded.Search, contentDescription = null)
                },
                label = { Text("Search transactions") },
                shape = MaterialTheme.shapes.medium,
            )
            Spacer(Modifier.height(10.dp))
        }

        LazyRow(
            contentPadding = PaddingValues(horizontal = 18.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(TransactionFilter.entries) { filter ->
                val reviewCount = transactions.count { it.reviewStatus == ReviewStatus.NEEDS_REVIEW }
                FilterChip(
                    selected = selectedFilter == filter,
                    onClick = { selectedFilter = filter },
                    label = {
                        Text(
                            if (filter == TransactionFilter.REVIEW && reviewCount > 0) {
                                "${filter.label} ($reviewCount)"
                            } else {
                                filter.label
                            },
                        )
                    },
                )
            }
        }
        Spacer(Modifier.height(10.dp))

        uncategorizedMerchants.firstOrNull()?.let { merchant ->
            PaisaCard(
                modifier = Modifier.padding(horizontal = 18.dp).fillMaxWidth(),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Categorize ${merchant.merchant}",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            text = "${uncategorizedMerchants.size} merchant" +
                                (if (uncategorizedMerchants.size == 1) "" else "s") + " need categories",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Button(onClick = { onCategorizeMerchant(merchant) }) { Text("Review") }
                }
            }
            Spacer(Modifier.height(10.dp))
        }

        if (filtered.isEmpty()) {
            PaisaCard(
                modifier = Modifier
                    .padding(horizontal = 18.dp)
                    .fillMaxWidth(),
            ) {
                EmptyState(
                    title = if (transactions.isEmpty()) "No transactions yet" else "Nothing matches",
                    body = if (transactions.isEmpty()) {
                        "Scan SMS alerts or add an expense manually."
                    } else {
                        "Try a different search or filter."
                    },
                    icon = Icons.AutoMirrored.Rounded.ReceiptLong,
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 18.dp, end = 18.dp, bottom = 88.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(filtered, key = { it.id }) { transaction ->
                    PaisaCard {
                        TransactionRow(
                            transaction = transaction,
                            onClick = { onTransactionClick(transaction) },
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 2.dp),
                        )
                    }
                }
            }
        }
    }
}
