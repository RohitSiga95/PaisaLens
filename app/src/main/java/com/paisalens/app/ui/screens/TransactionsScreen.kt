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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ReceiptLong
import androidx.compose.material.icons.automirrored.rounded.FactCheck
import androidx.compose.material.icons.rounded.AccountBalance
import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.material.icons.rounded.CreditCard
import androidx.compose.material.icons.rounded.Groups
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.paisalens.app.data.model.AccountProfile
import com.paisalens.app.data.model.AccountType
import com.paisalens.app.data.model.MerchantTransactionGroup
import com.paisalens.app.data.model.ExpenseCategory
import com.paisalens.app.data.model.TransactionRecord
import com.paisalens.app.data.model.TransactionSource
import com.paisalens.app.data.model.TransactionType
import com.paisalens.app.data.model.ReviewStatus
import com.paisalens.app.data.model.categoryLabel
import com.paisalens.app.sms.BankSmsSupport
import com.paisalens.app.ui.components.EmptyState
import com.paisalens.app.ui.components.PaisaCard
import com.paisalens.app.ui.components.TransactionRow
import java.util.Locale

enum class TransactionFilter(val label: String) {
    ALL("All"),
    EXPENSE("Expenses"),
    INCOME("Income"),
    REFUND("Refunds"),
    TRANSFER("Transfers"),
    REVIEW("Needs review"),
    UNCATEGORIZED("Uncategorized"),
    UNASSIGNED("Unassigned"),
}

@Composable
fun TransactionsScreen(
    transactions: List<TransactionRecord>,
    accounts: List<AccountProfile>,
    uncategorizedMerchants: List<MerchantTransactionGroup>,
    initialFilter: TransactionFilter = TransactionFilter.ALL,
    onCategorizeMerchant: (MerchantTransactionGroup) -> Unit,
    onTrustCenter: () -> Unit,
    onSharedExpenses: () -> Unit,
    onCalendar: () -> Unit,
    onTransactionClick: (TransactionRecord) -> Unit,
) {
    var query by rememberSaveable { mutableStateOf("") }
    var selectedFilterName by rememberSaveable { mutableStateOf(initialFilter.name) }
    var selectedAccountKeysList by rememberSaveable { mutableStateOf(emptyList<String>()) }
    val selectedFilter = TransactionFilter.entries.firstOrNull { it.name == selectedFilterName }
        ?: TransactionFilter.ALL
    val selectedAccountKeys = selectedAccountKeysList.toSet()

    val accountOptions = remember(accounts, transactions) {
        activityAccountFilterOptions(accounts, transactions)
    }

    LaunchedEffect(initialFilter) {
        selectedFilterName = initialFilter.name
    }

    LaunchedEffect(accountOptions) {
        selectedAccountKeysList = validActivityAccountSelections(selectedAccountKeys, accountOptions).toList()
    }

    val filtered = remember(transactions, query, selectedFilter, selectedAccountKeys, accountOptions) {
        filterActivityTransactions(
            transactions = transactions,
            query = query,
            typeFilter = selectedFilter,
            selectedAccountKeys = selectedAccountKeys,
            accountOptions = accountOptions,
        )
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(top = 12.dp),
        contentPadding = PaddingValues(start = 18.dp, end = 18.dp, bottom = 88.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item(key = "activity-header") {
            Column {
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
                IconButton(onClick = onTrustCenter) {
                    Icon(Icons.AutoMirrored.Rounded.FactCheck, contentDescription = "Open Trust Center")
                }
                IconButton(onClick = onSharedExpenses) {
                    Icon(Icons.Rounded.Groups, contentDescription = "Open shared expenses")
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
            }
        }

        item(key = "transaction-type-filters") {
            LazyRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(TransactionFilter.entries) { filter ->
                    val filterCount = when (filter) {
                        TransactionFilter.REVIEW -> transactions.count { it.reviewStatus == ReviewStatus.NEEDS_REVIEW }
                        TransactionFilter.UNCATEGORIZED -> transactions.count {
                            it.type == TransactionType.EXPENSE &&
                                it.category == ExpenseCategory.OTHER &&
                                it.customCategoryId == null
                        }
                        TransactionFilter.UNASSIGNED -> transactions.count { it.accountId == null }
                        else -> 0
                    }
                    FilterChip(
                        selected = selectedFilter == filter,
                        onClick = { selectedFilterName = filter.name },
                        label = {
                            Text(
                                if (filterCount > 0) {
                                    "${filter.label} ($filterCount)"
                                } else {
                                    filter.label
                                },
                            )
                        },
                    )
                }
            }
        }

        item(key = "account-filter-heading") {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = if (selectedAccountKeys.isEmpty()) {
                        "Bank & card"
                    } else {
                        "Bank & card (${selectedAccountKeys.size} selected)"
                    },
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (selectedAccountKeys.isNotEmpty()) {
                    TextButton(onClick = { selectedAccountKeysList = emptyList() }) {
                        Text("Clear")
                    }
                }
            }
        }

        item(key = "account-filters") {
            if (accountOptions.isEmpty()) {
                Text(
                    text = "Add a bank account or credit card in Settings to filter activity.",
                    modifier = Modifier.padding(vertical = 4.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                LazyRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    item(key = "all-accounts") {
                        FilterChip(
                            selected = selectedAccountKeys.isEmpty(),
                            onClick = { selectedAccountKeysList = emptyList() },
                            label = { Text("All accounts") },
                            modifier = Modifier.semantics {
                                contentDescription = "Show activity from all bank accounts and credit cards"
                            },
                        )
                    }
                    items(accountOptions, key = { it.key }) { option ->
                        val isSelected = option.key in selectedAccountKeys
                        FilterChip(
                            selected = isSelected,
                            onClick = {
                                selectedAccountKeysList = if (isSelected) {
                                    selectedAccountKeys - option.key
                                } else {
                                    selectedAccountKeys + option.key
                                }.toList()
                            },
                            label = {
                                Text(
                                    text = option.label,
                                    modifier = Modifier.widthIn(max = 200.dp),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            },
                            leadingIcon = {
                                Icon(
                                    imageVector = if (option.type == AccountType.CREDIT_CARD) {
                                        Icons.Rounded.CreditCard
                                    } else {
                                        Icons.Rounded.AccountBalance
                                    },
                                    contentDescription = null,
                                )
                            },
                            modifier = Modifier.semantics {
                                contentDescription = "${option.type.label}: ${option.accessibilityLabel}"
                            },
                        )
                    }
                }
            }
        }

        uncategorizedMerchants.firstOrNull()?.let { merchant ->
            item(key = "uncategorized-merchant") {
                PaisaCard(modifier = Modifier.fillMaxWidth()) {
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
            }
        }

        if (filtered.isEmpty()) {
            item(key = "empty-activity") {
                PaisaCard(modifier = Modifier.fillMaxWidth()) {
                    EmptyState(
                        title = when {
                            transactions.isEmpty() -> "No transactions yet"
                            selectedAccountKeys.isNotEmpty() -> "No activity for these accounts"
                            else -> "Nothing matches"
                        },
                        body = if (transactions.isEmpty()) {
                            "Scan SMS alerts or add an expense manually."
                        } else if (selectedAccountKeys.isNotEmpty()) {
                            "Choose another bank or card, or clear the account filters."
                        } else {
                            "Try a different search or filter."
                        },
                        icon = Icons.AutoMirrored.Rounded.ReceiptLong,
                    )
                }
            }
        } else {
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

internal data class ActivityAccountFilterOption(
    val key: String,
    val type: AccountType,
    val label: String,
    val accessibilityLabel: String,
    val institutionName: String?,
    val institutionNames: Set<String>,
    val lastFour: String?,
    val accountIds: Set<Long>,
    val accountNames: Set<String>,
)

private data class TaggedActivityAccount(
    val type: AccountType,
    val institutionName: String,
    val lastFour: String?,
    val accountName: String?,
)

internal fun activityAccountFilterOptions(
    accounts: List<AccountProfile>,
    transactions: List<TransactionRecord> = emptyList(),
): List<ActivityAccountFilterOption> {
    val knownAccountIds = accounts.mapTo(hashSetOf(), AccountProfile::id)
    val taggedAccounts = transactions.mapNotNull { transaction ->
        if (transaction.accountId != null && transaction.accountId in knownAccountIds) return@mapNotNull null
        val type = transaction.activityAccountType() ?: return@mapNotNull null
        val institution = transactionCanonicalInstitution(transaction) ?: return@mapNotNull null
        TaggedActivityAccount(
            type = type,
            institutionName = institution,
            lastFour = accountLastFour(transaction.accountHint),
            accountName = transaction.accountName?.trim()?.takeIf(String::isNotBlank),
        )
    }

    val profileOptions = accounts
        .filter { it.type == AccountType.BANK_ACCOUNT || it.type == AccountType.CREDIT_CARD }
        .groupBy { account ->
            val institution = accountCanonicalInstitution(account)
            val lastFour = accountLastFour(account.accountHint)
            if (lastFour == null) {
                "${account.type.name}|${normalizedAccountValue(institution ?: account.name)}|account:${account.id}"
            } else {
                "${account.type.name}|last4:$lastFour"
            }
        }
        .map { (key, matches) ->
            val preferred = matches.minBy(AccountProfile::id)
            val lastFour = matches.firstNotNullOfOrNull { accountLastFour(it.accountHint) }
            val profileInstitutions = matches.mapNotNull(::accountCanonicalInstitution)
            val taggedMatches = taggedAccounts.filter { tagged ->
                tagged.type == preferred.type && lastFour != null && tagged.lastFour == lastFour
            }
            val institutions = (profileInstitutions + taggedMatches.map(TaggedActivityAccount::institutionName))
                .distinctBy(::normalizedAccountValue)
                .toCollection(linkedSetOf())
            val institution = profileInstitutions.firstOrNull() ?: preferredInstitution(institutions)
            val name = preferred.name.trim().ifBlank { institution ?: preferred.type.label }
            activityAccountFilterOption(
                key = key,
                type = preferred.type,
                name = name,
                institution = institution,
                institutions = institutions,
                lastFour = lastFour,
                accountIds = matches.mapTo(linkedSetOf(), AccountProfile::id),
                accountNames = (matches.mapNotNull { it.name.trim().takeIf(String::isNotBlank) } +
                    taggedMatches.mapNotNull(TaggedActivityAccount::accountName)).toCollection(linkedSetOf()),
            )
        }

    val virtualOptions = taggedAccounts
        .filterNot { tagged ->
            if (tagged.lastFour != null) {
                profileOptions.any { it.type == tagged.type && it.lastFour == tagged.lastFour }
            } else {
                profileOptions.any { option ->
                    option.type == tagged.type && option.institutionNames.any {
                        normalizedAccountValue(it) == normalizedAccountValue(tagged.institutionName)
                    }
                }
            }
        }
        .groupBy { tagged ->
            tagged.lastFour?.let { "${tagged.type.name}|last4:$it" }
                ?: "${tagged.type.name}|institution:${normalizedAccountValue(tagged.institutionName)}"
        }
        .map { (key, matches) ->
            val type = matches.first().type
            val institutions = matches.map(TaggedActivityAccount::institutionName)
                .distinctBy(::normalizedAccountValue)
                .toCollection(linkedSetOf())
            val institution = preferredInstitution(institutions) ?: type.label
            activityAccountFilterOption(
                key = key,
                type = type,
                name = institution,
                institution = institution,
                institutions = institutions,
                lastFour = matches.firstNotNullOfOrNull(TaggedActivityAccount::lastFour),
                accountIds = emptySet(),
                accountNames = matches.mapNotNullTo(linkedSetOf(), TaggedActivityAccount::accountName),
            )
        }

    return (profileOptions + virtualOptions).sortedWith(
        compareBy<ActivityAccountFilterOption> { if (it.type == AccountType.BANK_ACCOUNT) 0 else 1 }
            .thenBy(String.CASE_INSENSITIVE_ORDER, ActivityAccountFilterOption::label)
            .thenBy(ActivityAccountFilterOption::key),
    )
}

internal fun validActivityAccountSelections(
    selectedAccountKeys: Set<String>,
    accountOptions: List<ActivityAccountFilterOption>,
): Set<String> {
    val availableKeys = accountOptions.mapTo(mutableSetOf(), ActivityAccountFilterOption::key)
    return selectedAccountKeys.intersect(availableKeys)
}

internal fun filterActivityTransactions(
    transactions: List<TransactionRecord>,
    query: String,
    typeFilter: TransactionFilter,
    selectedAccountKeys: Set<String>,
    accountOptions: List<ActivityAccountFilterOption>,
): List<TransactionRecord> = transactions.filter { transaction ->
    val queryMatch = query.isBlank() ||
        transaction.merchant.contains(query, ignoreCase = true) ||
        transaction.categoryLabel().contains(query, ignoreCase = true) ||
        transaction.sender.contains(query, ignoreCase = true) ||
        transaction.note?.contains(query, ignoreCase = true) == true ||
        transaction.accountName?.contains(query, ignoreCase = true) == true ||
        transaction.institutionName?.contains(query, ignoreCase = true) == true ||
        transaction.tags.any { it.contains(query, ignoreCase = true) }
    val typeMatch = when (typeFilter) {
        TransactionFilter.ALL -> true
        TransactionFilter.EXPENSE -> transaction.type == TransactionType.EXPENSE
        TransactionFilter.INCOME -> transaction.type == TransactionType.INCOME
        TransactionFilter.REFUND -> transaction.type == TransactionType.REFUND
        TransactionFilter.TRANSFER -> transaction.type == TransactionType.TRANSFER
        TransactionFilter.REVIEW -> transaction.reviewStatus == ReviewStatus.NEEDS_REVIEW
        TransactionFilter.UNCATEGORIZED -> transaction.type == TransactionType.EXPENSE &&
            transaction.category == ExpenseCategory.OTHER &&
            transaction.customCategoryId == null
        TransactionFilter.UNASSIGNED -> transaction.accountId == null
    }
    val accountMatch = transactionMatchesActivityAccounts(transaction, selectedAccountKeys, accountOptions)
    queryMatch && typeMatch && accountMatch
}

private fun transactionMatchesActivityAccounts(
    transaction: TransactionRecord,
    selectedAccountKeys: Set<String>,
    accountOptions: List<ActivityAccountFilterOption>,
): Boolean {
    if (selectedAccountKeys.isEmpty()) return true
    val selectedOptions = accountOptions.filter { it.key in selectedAccountKeys }
    if (selectedOptions.any { transaction.accountId in it.accountIds }) return true
    if (transaction.accountId != null) return false

    val transactionInstitution = transaction.institutionName?.takeIf(String::isNotBlank)
    val transactionAccountName = transaction.accountName?.takeIf(String::isNotBlank)
    val normalizedInstitution = transactionInstitution?.let(::normalizedAccountValue)
    val transactionLastFour = transaction.accountHint
        ?.filter(Char::isDigit)
        ?.takeLast(4)
        ?.takeIf { it.length == 4 }
    return selectedOptions.any { option ->
        val institutionMatch = normalizedInstitution != null && option.institutionNames.any {
            normalizedInstitution == normalizedAccountValue(it)
        }
        val accountNameMatch = transactionAccountName != null && option.accountNames.any {
            it.equals(transactionAccountName, ignoreCase = true)
        }
        val identityMatch = institutionMatch || accountNameMatch
        val sourceTypeMatch = when (option.type) {
            AccountType.BANK_ACCOUNT -> transaction.source == TransactionSource.BANK ||
                transaction.source == TransactionSource.UPI
            AccountType.CREDIT_CARD -> transaction.source == TransactionSource.CARD
            else -> false
        }
        val identityPeers = accountOptions.filter { peer ->
            peer.type == option.type &&
                ((institutionMatch && peer.institutionNames.any {
                    normalizedAccountValue(it) == normalizedInstitution
                }) ||
                    (accountNameMatch && peer.accountNames.any { name ->
                        name.equals(transactionAccountName, ignoreCase = true)
                    }))
        }
        val identityIsUnambiguous = identityPeers.size == 1
        val accountIdentityMatch = if (transactionLastFour != null) {
            option.lastFour == transactionLastFour
        } else {
            identityIsUnambiguous
        }
        identityMatch && accountIdentityMatch && sourceTypeMatch
    }
}

private fun accountCanonicalInstitution(account: AccountProfile): String? =
    account.institution?.trim()?.takeIf(String::isNotBlank)
        ?.let { BankSmsSupport.institutionNameOrNull(it) ?: it }
        ?: BankSmsSupport.institutionNameOrNull(account.name)

private fun transactionCanonicalInstitution(transaction: TransactionRecord): String? =
    transaction.institutionName?.trim()?.takeIf(String::isNotBlank)
        ?.let { BankSmsSupport.institutionNameOrNull(it) ?: it }
        ?: BankSmsSupport.institutionNameOrNull(transaction.sender)

private fun TransactionRecord.activityAccountType(): AccountType? = when (source) {
    TransactionSource.CARD -> AccountType.CREDIT_CARD
    TransactionSource.BANK, TransactionSource.UPI -> AccountType.BANK_ACCOUNT
    else -> null
}

private fun activityAccountFilterOption(
    key: String,
    type: AccountType,
    name: String,
    institution: String?,
    institutions: Set<String>,
    lastFour: String?,
    accountIds: Set<Long>,
    accountNames: Set<String>,
): ActivityAccountFilterOption {
    val institutionSuffix = institution
        ?.takeUnless { name.contains(it, ignoreCase = true) }
        ?.let { " · $it" }
        .orEmpty()
    val lastFourSuffix = lastFour?.let { " •••• $it" }.orEmpty()
    return ActivityAccountFilterOption(
        key = key,
        type = type,
        label = "$name$institutionSuffix$lastFourSuffix",
        accessibilityLabel = buildList {
            add(name)
            institution?.takeUnless { name.contains(it, ignoreCase = true) }?.let(::add)
            lastFour?.let { add("ending in $it") }
        }.joinToString(", "),
        institutionName = institution,
        institutionNames = institutions,
        lastFour = lastFour,
        accountIds = accountIds,
        accountNames = accountNames,
    )
}

private fun preferredInstitution(institutions: Set<String>): String? = institutions
    .minWithOrNull(compareBy<String> { it.length }.thenBy(String.CASE_INSENSITIVE_ORDER) { it })

private fun accountLastFour(accountHint: String?): String? = accountHint
    ?.filter(Char::isDigit)
    ?.takeLast(4)
    ?.takeIf { it.length == 4 }

private fun normalizedAccountValue(value: String): String = value
    .trim()
    .lowercase(Locale.ROOT)
    .replace(Regex("[^a-z0-9]+"), "")
