package com.paisalens.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AccountBalance
import androidx.compose.material.icons.rounded.BookmarkAdd
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.CreditCard
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.VerifiedUser
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.paisalens.app.data.model.AccountType
import com.paisalens.app.data.model.CustomCategory
import com.paisalens.app.data.model.ReviewStatus
import com.paisalens.app.data.model.TransactionRecord
import com.paisalens.app.data.model.TransactionType
import com.paisalens.app.data.model.categoryLabel
import com.paisalens.app.ui.components.CategoryIcon
import com.paisalens.app.ui.components.CustomCategoryIcon
import com.paisalens.app.ui.components.MoneyText
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

internal fun activityTransactionsByDay(
    transactions: List<TransactionRecord>,
    zoneId: ZoneId,
): Map<Long, List<TransactionRecord>> = transactions.groupBy { activityEpochDay(it.occurredAt, zoneId) }
    .toSortedMap(reverseOrder())

@Composable
internal fun SmartActivityHeader(
    query: String,
    onQueryChange: (String) -> Unit,
    accountOptions: List<ActivityAccountFilterOption>,
    accountSelection: ActivityAccountSelectionResolution,
    activeFilterCount: Int,
    onRemoveAccount: (String) -> Unit,
    onOpenFilters: () -> Unit,
    onReset: (() -> Unit)?,
    onCategorize: (() -> Unit)?,
    onTrustCenter: () -> Unit,
    onSharedExpenses: () -> Unit,
    onCalendar: () -> Unit,
) {
    var showMenu by remember { mutableStateOf(false) }
    val scheme = MaterialTheme.colorScheme
    val selectedOptions = remember(accountOptions, accountSelection) {
        accountOptions.filter { it.key in accountSelection.resolvedKeys }
    }
    val selectedAccountScroll = rememberLazyListState()
    LaunchedEffect(selectedOptions.map(ActivityAccountFilterOption::key)) {
        if (selectedOptions.isNotEmpty()) selectedAccountScroll.scrollToItem(0)
    }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    "Activity",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.semantics { heading() },
                )
                Text("Smart Activity", style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant)
            }
            Surface(
                onClick = onTrustCenter,
                shape = CircleShape,
                color = scheme.secondaryContainer,
                contentColor = scheme.onSecondaryContainer,
                modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = "Local data and Trust Center" },
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Rounded.VerifiedUser, contentDescription = null, modifier = Modifier.size(17.dp))
                    Text("Local", style = MaterialTheme.typography.labelLarge)
                }
            }
            Box {
                IconButton(onClick = { showMenu = true }) {
                    Icon(Icons.Rounded.MoreVert, contentDescription = "More Activity options")
                }
                DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                    DropdownMenuItem(text = { Text("Spending calendar") }, onClick = {
                        showMenu = false
                        onCalendar()
                    })
                    DropdownMenuItem(text = { Text("Shared expenses") }, onClick = {
                        showMenu = false
                        onSharedExpenses()
                    })
                    onCategorize?.let { categorize ->
                        DropdownMenuItem(text = { Text("Categorize merchants") }, onClick = {
                            showMenu = false
                            categorize()
                        })
                    }
                    onReset?.let { reset ->
                        DropdownMenuItem(text = { Text("Clear filters and search") }, onClick = {
                            showMenu = false
                            reset()
                        })
                    }
                }
            }
        }
        OutlinedTextField(
            value = query,
            onValueChange = onQueryChange,
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
            placeholder = { Text("Search transactions") },
            trailingIcon = if (query.isNotEmpty()) {
                {
                    IconButton(onClick = { onQueryChange("") }) {
                        Icon(Icons.Rounded.Close, contentDescription = "Clear search")
                    }
                }
            } else null,
            shape = MaterialTheme.shapes.medium,
            colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
                focusedContainerColor = scheme.surface,
                unfocusedContainerColor = scheme.surface,
            ),
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            LazyRow(
                state = selectedAccountScroll,
                modifier = Modifier.weight(1f),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                items(selectedOptions, key = ActivityAccountFilterOption::key) { option ->
                    FilterChip(
                        selected = true,
                        onClick = { onRemoveAccount(option.key) },
                        shape = CircleShape,
                        label = { Text(option.selectedChipLabel(accountOptions), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = scheme.primaryContainer,
                            selectedLabelColor = scheme.onPrimaryContainer,
                            selectedLeadingIconColor = scheme.onPrimaryContainer,
                            selectedTrailingIconColor = scheme.onPrimaryContainer,
                        ),
                        leadingIcon = {
                            Icon(
                                if (option.type == AccountType.CREDIT_CARD) Icons.Rounded.CreditCard else Icons.Rounded.AccountBalance,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                            )
                        },
                        trailingIcon = { Icon(Icons.Rounded.Close, contentDescription = null, modifier = Modifier.size(16.dp)) },
                        modifier = Modifier.heightIn(min = 48.dp).widthIn(max = 188.dp)
                            .semantics { contentDescription = "Remove ${option.accessibilityLabel} filter" },
                    )
                }
                if (accountOptions.isNotEmpty() && selectedOptions.isEmpty()) {
                    item(key = "choose-accounts") {
                        OutlinedButton(
                            onClick = onOpenFilters,
                            contentPadding = PaddingValues(horizontal = 10.dp),
                            modifier = Modifier.heightIn(min = 48.dp),
                        ) {
                            Text(
                                if (accountOptions.any { it.type !in listOf(AccountType.BANK_ACCOUNT, AccountType.CREDIT_CARD) }) "Accounts"
                                else "Bank & card",
                            )
                        }
                    }
                }
            }
            OutlinedButton(
                onClick = onOpenFilters,
                shape = MaterialTheme.shapes.medium,
                contentPadding = PaddingValues(horizontal = 10.dp),
                modifier = Modifier.heightIn(min = 48.dp),
            ) {
                Icon(Icons.Rounded.Tune, contentDescription = null, modifier = Modifier.padding(end = 4.dp).size(18.dp))
                Text("Filters")
                if (activeFilterCount > 0) {
                    Surface(
                        color = scheme.primary,
                        contentColor = scheme.onPrimary,
                        shape = CircleShape,
                        modifier = Modifier.padding(start = 5.dp),
                    ) {
                        Text(activeFilterCount.toString(), modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp),
                            style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }
    }
}

private fun ActivityAccountFilterOption.selectedChipLabel(options: List<ActivityAccountFilterOption>): String {
    val institution = institutionName ?: return label
    val baseName = lastFour?.let(label::removeSuffix)?.trimEnd(' ', '•', '·') ?: label
    if (!baseName.equals(institution, ignoreCase = true)) return label
    val shortName = when (institution) {
        "State Bank of India" -> "SBI"
        else -> institution.removeSuffix(" Bank")
    }
    return if (options.count { it.institutionName == institution } > 1 && lastFour != null) {
        "$shortName · $lastFour"
    } else shortName
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ActivityResultSummary(count: Int, totalMinor: Long, onSaveView: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        FlowRow(
            modifier = Modifier.weight(1f).padding(end = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                "$count ${if (count == 1) "transaction" else "transactions"} ·",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            MoneyText(totalMinor, style = MaterialTheme.typography.bodyMedium)
        }
        TextButton(onClick = onSaveView, modifier = Modifier.heightIn(min = 48.dp)) {
            Icon(Icons.Rounded.BookmarkAdd, contentDescription = null, modifier = Modifier.padding(end = 4.dp).size(20.dp))
            Text("Save view")
        }
    }
}

@Composable
internal fun ActivityDateBand(epochDay: Long, todayEpochDay: Long) {
    val day = LocalDate.ofEpochDay(epochDay)
    val today = LocalDate.ofEpochDay(todayEpochDay)
    val label = when (epochDay) {
        todayEpochDay -> "Today"
        todayEpochDay - 1 -> "Yesterday"
        else -> day.format(DateTimeFormatter.ofPattern("EEE"))
    }
    val date = day.format(DateTimeFormatter.ofPattern(if (day.year == today.year) "d MMM" else "d MMM uuuu"))
    Surface(
        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.62f),
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            "$label · $date",
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp).semantics { heading() },
            fontWeight = FontWeight.SemiBold,
            style = MaterialTheme.typography.titleSmall,
        )
    }
}

@Composable
internal fun SmartActivityRow(
    transaction: TransactionRecord,
    customCategory: CustomCategory?,
    onClick: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    Surface(onClick = onClick, color = scheme.surface, modifier = Modifier.fillMaxWidth()) {
        BoxWithConstraints(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 10.dp)) {
            val stackAmount = maxWidth < 260.dp || LocalDensity.current.fontScale > 1.35f
            val amountWidth = maxWidth * 0.38f
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Top) {
                if (customCategory != null) {
                    CustomCategoryIcon(customCategory, modifier = Modifier.size(42.dp), iconSize = 21)
                } else {
                    CategoryIcon(transaction.category, modifier = Modifier.size(42.dp), iconSize = 21)
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(transaction.merchant, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    val account = transaction.accountName?.takeIf(String::isNotBlank)
                        ?: transaction.institutionName?.takeIf(String::isNotBlank)
                    if (account != null) Text(account, style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
                    Text(
                        customCategory?.name ?: transaction.categoryLabel(),
                        style = MaterialTheme.typography.bodySmall,
                        color = scheme.onSurfaceVariant,
                    )
                    if (transaction.reviewStatus == ReviewStatus.NEEDS_REVIEW) {
                        Text("Needs review", style = MaterialTheme.typography.labelSmall, color = scheme.error)
                    }
                    if (stackAmount) ActivityTransactionAmount(transaction, Alignment.Start)
                }
                if (!stackAmount) {
                    Box(Modifier.widthIn(max = amountWidth), contentAlignment = Alignment.TopEnd) {
                        ActivityTransactionAmount(transaction, Alignment.End)
                    }
                }
            }
        }
    }
}

@Composable
private fun ActivityTransactionAmount(transaction: TransactionRecord, alignment: Alignment.Horizontal) {
    val scheme = MaterialTheme.colorScheme
    Column(horizontalAlignment = alignment, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        val incoming = transaction.type == TransactionType.INCOME || transaction.type == TransactionType.REFUND
        MoneyText(
            transaction.amountMinor,
            style = MaterialTheme.typography.titleMedium,
            color = if (incoming) scheme.secondary else scheme.onSurface,
            prefix = when (transaction.type) {
                TransactionType.INCOME, TransactionType.REFUND -> "+"
                TransactionType.EXPENSE -> "−"
                TransactionType.TRANSFER -> ""
            },
        )
        if (transaction.duplicateCount > 1) {
            Surface(
                color = scheme.primaryContainer,
                contentColor = scheme.onPrimaryContainer,
                shape = CircleShape,
                modifier = Modifier.semantics { contentDescription = "${transaction.duplicateCount} SMS alerts merged" },
            ) {
                Text("×${transaction.duplicateCount} SMS", modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp),
                    style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}
