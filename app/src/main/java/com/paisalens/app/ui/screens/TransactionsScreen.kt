package com.paisalens.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ReceiptLong
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.paisalens.app.data.model.TransactionRecord
import com.paisalens.app.data.model.TransactionType
import com.paisalens.app.ui.components.EmptyState
import com.paisalens.app.ui.components.PaisaCard
import com.paisalens.app.ui.components.TransactionRow

private enum class TransactionFilter(val label: String) {
    ALL("All"),
    EXPENSE("Expenses"),
    INCOME("Income"),
    REFUND("Refunds"),
    TRANSFER("Transfers"),
}

@Composable
fun TransactionsScreen(
    transactions: List<TransactionRecord>,
    onTransactionClick: (TransactionRecord) -> Unit,
) {
    var query by remember { mutableStateOf("") }
    var selectedFilter by remember { mutableStateOf(TransactionFilter.ALL) }

    val filtered = remember(transactions, query, selectedFilter) {
        transactions.filter { transaction ->
            val queryMatch = query.isBlank() ||
                transaction.merchant.contains(query, ignoreCase = true) ||
                transaction.category.label.contains(query, ignoreCase = true) ||
                transaction.sender.contains(query, ignoreCase = true) ||
                transaction.note?.contains(query, ignoreCase = true) == true
            val typeMatch = when (selectedFilter) {
                TransactionFilter.ALL -> true
                TransactionFilter.EXPENSE -> transaction.type == TransactionType.EXPENSE
                TransactionFilter.INCOME -> transaction.type == TransactionType.INCOME
                TransactionFilter.REFUND -> transaction.type == TransactionType.REFUND
                TransactionFilter.TRANSFER -> transaction.type == TransactionType.TRANSFER
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
                FilterChip(
                    selected = selectedFilter == filter,
                    onClick = { selectedFilter = filter },
                    label = { Text(filter.label) },
                )
            }
        }
        Spacer(Modifier.height(10.dp))

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
                    icon = Icons.Rounded.ReceiptLong,
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
