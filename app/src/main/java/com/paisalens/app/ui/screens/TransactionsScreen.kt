package com.paisalens.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ReceiptLong
import androidx.compose.material.icons.automirrored.rounded.FactCheck
import androidx.compose.material.icons.rounded.AccountBalance
import androidx.compose.material.icons.rounded.BookmarkAdd
import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.CreditCard
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Groups
import androidx.compose.material.icons.rounded.RestartAlt
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.paisalens.app.data.model.AccountProfile
import com.paisalens.app.data.model.AccountType
import com.paisalens.app.data.model.CustomCategory
import com.paisalens.app.data.model.MerchantTransactionGroup
import com.paisalens.app.data.model.ExpenseCategory
import com.paisalens.app.data.model.TransactionRecord
import com.paisalens.app.data.model.TransactionSource
import com.paisalens.app.data.model.TransactionType
import com.paisalens.app.data.model.ReviewStatus
import com.paisalens.app.sms.BankSmsSupport
import com.paisalens.app.ui.components.EmptyState
import com.paisalens.app.ui.components.PaisaCard
import com.paisalens.app.ui.components.TransactionRow
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import java.time.ZoneId
import java.util.Locale
import kotlinx.coroutines.delay

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
    customCategories: List<CustomCategory> = emptyList(),
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
    var dateRangeName by rememberSaveable { mutableStateOf(ActivityDateRange.ANY_TIME.name) }
    var customStartEpochDay by rememberSaveable { mutableStateOf<Long?>(null) }
    var customEndEpochDay by rememberSaveable { mutableStateOf<Long?>(null) }
    var categoryKey by rememberSaveable { mutableStateOf<String?>(null) }
    var minimumAmountMinor by rememberSaveable { mutableStateOf<Long?>(null) }
    var maximumAmountMinor by rememberSaveable { mutableStateOf<Long?>(null) }
    var sourceName by rememberSaveable { mutableStateOf<String?>(null) }
    var institution by rememberSaveable { mutableStateOf<String?>(null) }
    var tag by rememberSaveable { mutableStateOf<String?>(null) }
    var duplicateOnly by rememberSaveable { mutableStateOf(false) }
    var reviewStatusName by rememberSaveable { mutableStateOf<String?>(null) }
    var showAdvancedFilters by rememberSaveable { mutableStateOf(false) }
    var showSaveViewDialog by rememberSaveable { mutableStateOf(false) }
    var renameView by remember { mutableStateOf<ActivitySavedView?>(null) }
    var deleteView by remember { mutableStateOf<ActivitySavedView?>(null) }

    val applicationContext = LocalContext.current.applicationContext
    val savedViewStore = remember(applicationContext) { ActivitySavedViewStore(applicationContext) }
    var savedViews by remember(savedViewStore) { mutableStateOf(savedViewStore.read()) }
    val selectedFilter = TransactionFilter.entries.firstOrNull { it.name == selectedFilterName }
        ?: TransactionFilter.ALL
    val selectedAccountKeys = selectedAccountKeysList.toSet()
    val dateRange = ActivityDateRange.entries.firstOrNull { it.name == dateRangeName }
        ?: ActivityDateRange.ANY_TIME
    val source = sourceName?.let { name -> TransactionSource.entries.firstOrNull { it.name == name } }
    val reviewStatus = reviewStatusName?.let { name -> ReviewStatus.entries.firstOrNull { it.name == name } }

    val filterState = ActivityFilterState(
        query = query,
        typeFilter = selectedFilter,
        selectedAccountKeys = selectedAccountKeys,
        dateRange = dateRange,
        customStartEpochDay = customStartEpochDay,
        customEndEpochDay = customEndEpochDay,
        categoryKey = categoryKey,
        minimumAmountMinor = minimumAmountMinor,
        maximumAmountMinor = maximumAmountMinor,
        source = source,
        institution = institution,
        tag = tag,
        duplicateOnly = duplicateOnly,
        reviewStatus = reviewStatus,
    ).normalized()

    val applyFilterState: (ActivityFilterState) -> Unit = { incoming ->
        val value = incoming.normalized()
        query = value.query
        selectedFilterName = value.typeFilter.name
        selectedAccountKeysList = value.selectedAccountKeys.toList()
        dateRangeName = value.dateRange.name
        customStartEpochDay = value.customStartEpochDay
        customEndEpochDay = value.customEndEpochDay
        categoryKey = value.categoryKey
        minimumAmountMinor = value.minimumAmountMinor
        maximumAmountMinor = value.maximumAmountMinor
        sourceName = value.source?.name
        institution = value.institution
        tag = value.tag
        duplicateOnly = value.duplicateOnly
        reviewStatusName = value.reviewStatus?.name
    }

    DisposableEffect(savedViewStore) {
        val stopObserving = savedViewStore.observe { views, storageWasCleared ->
            savedViews = views
            if (storageWasCleared) applyFilterState(ActivityFilterState())
        }
        onDispose(stopObserving)
    }

    val accountOptions = remember(accounts, transactions) {
        activityAccountFilterOptions(accounts, transactions)
    }
    val accountSelectionResolution = remember(selectedAccountKeys, accountOptions) {
        resolveActivityAccountSelections(selectedAccountKeys, accountOptions)
    }
    val categoryOptions = remember(customCategories, transactions) {
        activityCategoryOptions(customCategories, transactions)
    }
    val institutionOptions = remember(transactions) { activityInstitutionOptions(transactions) }
    val tagOptions = remember(transactions) { activityTagOptions(transactions) }
    val activityClock = rememberCurrentActivityDateClock()
    val relativeDateNowMillis = remember(activityClock) {
        activityStartOfDayMillis(activityClock.epochDay, activityClock.zoneId)
    }

    LaunchedEffect(initialFilter) {
        selectedFilterName = initialFilter.name
    }

    LaunchedEffect(accountOptions, selectedAccountKeysList) {
        val safeSelections = validActivityAccountSelections(
            selectedAccountKeysList.toSet(),
            accountOptions,
        ).toList()
        if (safeSelections.toSet() != selectedAccountKeysList.toSet()) {
            selectedAccountKeysList = safeSelections
        }
    }

    val filtered = remember(
        transactions,
        filterState,
        accountOptions,
        relativeDateNowMillis,
        activityClock.zoneId,
    ) {
        filterActivityTransactions(
            transactions = transactions,
            filters = filterState,
            accountOptions = accountOptions,
            nowMillis = relativeDateNowMillis,
            zoneId = activityClock.zoneId,
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
                onValueChange = { query = it.take(80) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                leadingIcon = {
                    Icon(Icons.Rounded.Search, contentDescription = null)
                },
                label = { Text("Search transactions") },
                shape = MaterialTheme.shapes.medium,
            )
            Spacer(Modifier.height(10.dp))
            BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
                val compactActions = maxWidth < 400.dp
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    OutlinedButton(
                        onClick = { showAdvancedFilters = true },
                        modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                    ) {
                        Icon(Icons.Rounded.Tune, contentDescription = null)
                        Spacer(Modifier.size(8.dp))
                        Text(
                            text = filterState.activeFilterCount().let { count ->
                                if (count == 0) "Filters" else "Filters ($count)"
                            },
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    FilledTonalButton(
                        onClick = { showSaveViewDialog = true },
                        modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                    ) {
                        Icon(Icons.Rounded.BookmarkAdd, contentDescription = null)
                        Spacer(Modifier.size(8.dp))
                        Text(
                            text = if (compactActions) "Save" else "Save view",
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    if (filterState.activeFilterCount(includeQuery = true) > 0) {
                        IconButton(
                            onClick = { applyFilterState(ActivityFilterState()) },
                            modifier = Modifier.semantics { contentDescription = "Reset all Activity filters" },
                        ) {
                            Icon(Icons.Rounded.RestartAlt, contentDescription = null)
                        }
                    }
                }
            }
            }
        }

        if (savedViews.isNotEmpty()) {
            item(key = "saved-views") {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        text = "Saved views",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    LazyRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(savedViews, key = ActivitySavedView::id) { view ->
                            AssistChip(
                                onClick = { applyFilterState(view.filters) },
                                label = {
                                    Text(
                                        text = view.name,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                },
                                modifier = Modifier
                                    .heightIn(min = 48.dp)
                                    .widthIn(max = 220.dp)
                                    .semantics {
                                        contentDescription = "Apply saved Activity view ${view.name}"
                                    },
                            )
                        }
                    }
                }
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
                        modifier = Modifier.heightIn(min = 48.dp),
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
                        buildString {
                            append("Bank & card (${selectedAccountKeys.size} selected")
                            if (accountSelectionResolution.unavailableKeys.isNotEmpty()) {
                                append(" · ${accountSelectionResolution.unavailableKeys.size} unavailable")
                            }
                            append(')')
                        }
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
                            modifier = Modifier
                                .heightIn(min = 48.dp)
                                .semantics {
                                    contentDescription = "Show activity from all bank accounts and credit cards"
                                },
                        )
                    }
                    items(accountOptions, key = { it.key }) { option ->
                        val isSelected = option.key in accountSelectionResolution.resolvedKeys
                        FilterChip(
                            selected = isSelected,
                            onClick = {
                                val safeSelections = accountSelectionResolution.selectionKeys
                                selectedAccountKeysList = if (isSelected) {
                                    safeSelections - option.key
                                } else {
                                    safeSelections + option.key
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
                            modifier = Modifier
                                .heightIn(min = 48.dp)
                                .semantics {
                                    contentDescription = "${option.type.label}: ${option.accessibilityLabel}"
                                },
                        )
                    }
                }
            }
            if (accountSelectionResolution.unavailableKeys.isNotEmpty()) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Text(
                        text = "This view includes ${accountSelectionResolution.unavailableKeys.size} " +
                            "account selection" +
                            (if (accountSelectionResolution.unavailableKeys.size == 1) "" else "s") +
                            " that could not be mapped after an account changed. " +
                            "Unavailable accounts match no activity.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                    TextButton(
                        onClick = {
                            selectedAccountKeysList = accountSelectionResolution.resolvedKeys.toList()
                        },
                        modifier = Modifier.heightIn(min = 48.dp),
                    ) {
                        Text(
                            if (accountSelectionResolution.resolvedKeys.isEmpty()) {
                                "Use all accounts"
                            } else {
                                "Remove unavailable"
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
                            selectedAccountKeys.isNotEmpty() &&
                                accountSelectionResolution.resolvedKeys.isEmpty() -> "Saved account unavailable"
                            selectedAccountKeys.isNotEmpty() -> "No activity for these accounts"
                            else -> "Nothing matches"
                        },
                        body = if (transactions.isEmpty()) {
                            "Scan SMS alerts or add an expense manually."
                        } else if (
                            selectedAccountKeys.isNotEmpty() &&
                            accountSelectionResolution.resolvedKeys.isEmpty()
                        ) {
                            "This view references an account that was removed or could not be mapped after a " +
                                "merge. Choose an available bank or card, or explicitly use all accounts."
                        } else if (selectedAccountKeys.isNotEmpty()) {
                            "Choose another bank or card, or clear the account filters."
                        } else {
                            "Try a different search, adjust Filters, or reset the current view."
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

    if (showAdvancedFilters) {
        ActivityFiltersSheet(
            filters = filterState,
            accountOptions = accountOptions,
            categoryOptions = categoryOptions,
            institutionOptions = institutionOptions,
            tagOptions = tagOptions,
            savedViews = savedViews,
            onApply = {
                applyFilterState(it)
                showAdvancedFilters = false
            },
            onApplySavedView = {
                applyFilterState(it.filters)
                showAdvancedFilters = false
            },
            onRenameSavedView = { renameView = it },
            onDeleteSavedView = { deleteView = it },
            onDismiss = { showAdvancedFilters = false },
        )
    }

    if (showSaveViewDialog) {
        ActivityViewNameDialog(
            title = "Save Activity view",
            actionLabel = "Save",
            initialName = "",
            helperText = "The search, accounts, and every filter will be saved on this device.",
            existingNames = savedViews.map(ActivitySavedView::name),
            allowExistingName = true,
            onConfirm = { name ->
                savedViews = savedViewStore.save(name, filterState)
                showSaveViewDialog = false
            },
            onDismiss = { showSaveViewDialog = false },
        )
    }

    renameView?.let { view ->
        ActivityViewNameDialog(
            title = "Rename saved view",
            actionLabel = "Rename",
            initialName = view.name,
            helperText = "Choose a unique, memorable name.",
            existingNames = savedViews.filterNot { it.id == view.id }.map(ActivitySavedView::name),
            allowExistingName = false,
            onConfirm = { name ->
                savedViews = savedViewStore.rename(view.id, name)
                renameView = null
            },
            onDismiss = { renameView = null },
        )
    }

    deleteView?.let { view ->
        AlertDialog(
            onDismissRequest = { deleteView = null },
            title = { Text("Delete ${view.name}?") },
            text = { Text("This removes the saved view. Your transactions are not affected.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        savedViews = savedViewStore.delete(view.id)
                        deleteView = null
                    },
                ) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { deleteView = null }) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun rememberCurrentActivityDateClock(): ActivityDateClock {
    val lifecycleOwner = LocalLifecycleOwner.current
    var clock by remember {
        mutableStateOf(activityDateClock())
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                clock = activityDateClock()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(Unit) {
        while (true) {
            val nowMillis = System.currentTimeMillis()
            val current = activityDateClock(nowMillis)
            clock = current
            delay(
                minOf(
                    millisUntilNextActivityDay(nowMillis, current.zoneId) + 100L,
                    ACTIVITY_TIME_ZONE_POLL_MILLIS,
                ),
            )
        }
    }
    return clock
}

private const val ACTIVITY_TIME_ZONE_POLL_MILLIS = 60_000L

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ActivityFiltersSheet(
    filters: ActivityFilterState,
    accountOptions: List<ActivityAccountFilterOption>,
    categoryOptions: List<ActivityNamedFilterOption>,
    institutionOptions: List<String>,
    tagOptions: List<String>,
    savedViews: List<ActivitySavedView>,
    onApply: (ActivityFilterState) -> Unit,
    onApplySavedView: (ActivitySavedView) -> Unit,
    onRenameSavedView: (ActivitySavedView) -> Unit,
    onDeleteSavedView: (ActivitySavedView) -> Unit,
    onDismiss: () -> Unit,
) {
    var draft by remember(filters) { mutableStateOf(filters) }
    var minimumAmount by remember(filters.minimumAmountMinor) {
        mutableStateOf(formatActivityAmountMinor(filters.minimumAmountMinor))
    }
    var maximumAmount by remember(filters.maximumAmountMinor) {
        mutableStateOf(formatActivityAmountMinor(filters.maximumAmountMinor))
    }
    var startDate by remember(filters.customStartEpochDay) {
        mutableStateOf(formatActivityDate(filters.customStartEpochDay))
    }
    var endDate by remember(filters.customEndEpochDay) {
        mutableStateOf(formatActivityDate(filters.customEndEpochDay))
    }
    val parsedMinimum = parseActivityAmountMinor(minimumAmount)
    val parsedMaximum = parseActivityAmountMinor(maximumAmount)
    val minimumInvalid = minimumAmount.isNotBlank() && parsedMinimum == null
    val maximumInvalid = maximumAmount.isNotBlank() && parsedMaximum == null
    val amountOrderInvalid = parsedMinimum != null && parsedMaximum != null && parsedMinimum > parsedMaximum
    val parsedStartDate = parseActivityDate(startDate)
    val parsedEndDate = parseActivityDate(endDate)
    val customDatesMissing = draft.dateRange == ActivityDateRange.CUSTOM &&
        startDate.isBlank() && endDate.isBlank()
    val startDateInvalid = draft.dateRange == ActivityDateRange.CUSTOM &&
        startDate.isNotBlank() && parsedStartDate == null
    val endDateInvalid = draft.dateRange == ActivityDateRange.CUSTOM &&
        endDate.isNotBlank() && parsedEndDate == null
    val dateOrderInvalid = parsedStartDate != null && parsedEndDate != null && parsedStartDate > parsedEndDate
    val draftAccountSelection = remember(draft.selectedAccountKeys, accountOptions) {
        resolveActivityAccountSelections(draft.selectedAccountKeys, accountOptions)
    }
    val canApply = !minimumInvalid && !maximumInvalid && !amountOrderInvalid &&
        !customDatesMissing && !startDateInvalid && !endDateInvalid && !dateOrderInvalid

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Box(modifier = Modifier.fillMaxWidth()) {
            LazyColumn(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .widthIn(max = 720.dp)
                    .heightIn(max = 720.dp),
                contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                item(key = "filter-sheet-header") {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Activity filters",
                                style = MaterialTheme.typography.headlineSmall,
                                fontWeight = FontWeight.Bold,
                            )
                            Text(
                                text = "Criteria combine together; selected accounts match any chosen account.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        IconButton(
                            onClick = onDismiss,
                            modifier = Modifier.semantics { contentDescription = "Close Activity filters" },
                        ) {
                            Icon(Icons.Rounded.Close, contentDescription = null)
                        }
                    }
                }

                item(key = "filter-date") {
                    ActivityFilterSection(title = "Date") {
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(ActivityDateRange.entries) { option ->
                                FilterChip(
                                    selected = draft.dateRange == option,
                                    onClick = {
                                        draft = draft.copy(
                                            dateRange = option,
                                            customStartEpochDay = if (option == ActivityDateRange.CUSTOM) {
                                                draft.customStartEpochDay
                                            } else {
                                                null
                                            },
                                            customEndEpochDay = if (option == ActivityDateRange.CUSTOM) {
                                                draft.customEndEpochDay
                                            } else {
                                                null
                                            },
                                        )
                                    },
                                    label = { Text(option.label) },
                                    modifier = Modifier.heightIn(min = 48.dp),
                                )
                            }
                        }
                        if (draft.dateRange == ActivityDateRange.CUSTOM) {
                            Spacer(Modifier.height(8.dp))
                            BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
                                if (maxWidth < 520.dp) {
                                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                        ActivityDateField("From", startDate, startDateInvalid) { startDate = it }
                                        ActivityDateField("To", endDate, endDateInvalid) { endDate = it }
                                    }
                                } else {
                                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                        Box(Modifier.weight(1f)) {
                                            ActivityDateField("From", startDate, startDateInvalid) { startDate = it }
                                        }
                                        Box(Modifier.weight(1f)) {
                                            ActivityDateField("To", endDate, endDateInvalid) { endDate = it }
                                        }
                                    }
                                }
                            }
                            if (customDatesMissing || dateOrderInvalid) {
                                Text(
                                    text = if (customDatesMissing) {
                                        "Enter at least one date."
                                    } else {
                                        "The From date must not be after the To date."
                                    },
                                    color = MaterialTheme.colorScheme.error,
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                        }
                    }
                }

                item(key = "filter-type") {
                    ActivityFilterSection(title = "Transaction type") {
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(TransactionFilter.entries) { option ->
                                FilterChip(
                                    selected = draft.typeFilter == option,
                                    onClick = { draft = draft.copy(typeFilter = option) },
                                    label = { Text(option.label) },
                                    modifier = Modifier.heightIn(min = 48.dp),
                                )
                            }
                        }
                    }
                }

                if (accountOptions.isNotEmpty() || draft.selectedAccountKeys.isNotEmpty()) {
                    item(key = "filter-accounts") {
                        ActivityFilterSection(title = "Bank & card") {
                            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                item(key = "filter-account-any") {
                                    FilterChip(
                                        selected = draft.selectedAccountKeys.isEmpty(),
                                        onClick = { draft = draft.copy(selectedAccountKeys = emptySet()) },
                                        label = { Text("All accounts") },
                                        modifier = Modifier.heightIn(min = 48.dp),
                                    )
                                }
                                items(accountOptions, key = ActivityAccountFilterOption::key) { option ->
                                    val selected = option.key in draftAccountSelection.resolvedKeys
                                    FilterChip(
                                        selected = selected,
                                        onClick = {
                                            val safeSelections = draftAccountSelection.selectionKeys
                                            draft = draft.copy(
                                                selectedAccountKeys = if (selected) {
                                                    safeSelections - option.key
                                                } else {
                                                    safeSelections + option.key
                                                },
                                            )
                                        },
                                        label = {
                                            Text(
                                                text = option.label,
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
                                        modifier = Modifier
                                            .heightIn(min = 48.dp)
                                            .widthIn(max = 240.dp)
                                            .semantics {
                                                contentDescription = "${option.type.label}: ${option.accessibilityLabel}"
                                            },
                                    )
                                }
                            }
                            if (draftAccountSelection.unavailableKeys.isNotEmpty()) {
                                Text(
                                    text = "${draftAccountSelection.unavailableKeys.size} saved account " +
                                        "selection" +
                                        (if (draftAccountSelection.unavailableKeys.size == 1) " is" else "s are") +
                                        " unavailable and will match no activity.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error,
                                )
                                TextButton(
                                    onClick = {
                                        draft = draft.copy(
                                            selectedAccountKeys = draftAccountSelection.resolvedKeys,
                                        )
                                    },
                                    modifier = Modifier.heightIn(min = 48.dp),
                                ) {
                                    Text(
                                        if (draftAccountSelection.resolvedKeys.isEmpty()) {
                                            "Use all accounts"
                                        } else {
                                            "Remove unavailable"
                                        },
                                    )
                                }
                            }
                        }
                    }
                }

                item(key = "filter-category") {
                    ActivityFilterSection(title = "Category") {
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            item(key = "category-any") {
                                FilterChip(
                                    selected = draft.categoryKey == null,
                                    onClick = { draft = draft.copy(categoryKey = null) },
                                    label = { Text("Any category") },
                                    modifier = Modifier.heightIn(min = 48.dp),
                                )
                            }
                            items(categoryOptions, key = ActivityNamedFilterOption::key) { option ->
                                FilterChip(
                                    selected = draft.categoryKey == option.key,
                                    onClick = { draft = draft.copy(categoryKey = option.key) },
                                    label = {
                                        Text(
                                            text = option.label,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    },
                                    modifier = Modifier.heightIn(min = 48.dp).widthIn(max = 220.dp),
                                )
                            }
                        }
                    }
                }

                item(key = "filter-amount") {
                    ActivityFilterSection(title = "Amount") {
                        BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
                            if (maxWidth < 520.dp) {
                                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    ActivityAmountField("Minimum amount", minimumAmount, minimumInvalid) {
                                        minimumAmount = it
                                    }
                                    ActivityAmountField("Maximum amount", maximumAmount, maximumInvalid) {
                                        maximumAmount = it
                                    }
                                }
                            } else {
                                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                    Box(Modifier.weight(1f)) {
                                        ActivityAmountField("Minimum amount", minimumAmount, minimumInvalid) {
                                            minimumAmount = it
                                        }
                                    }
                                    Box(Modifier.weight(1f)) {
                                        ActivityAmountField("Maximum amount", maximumAmount, maximumInvalid) {
                                            maximumAmount = it
                                        }
                                    }
                                }
                            }
                        }
                        if (amountOrderInvalid) {
                            Text(
                                text = "Minimum amount must not exceed maximum amount.",
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }

                item(key = "filter-source") {
                    ActivityFilterSection(title = "Source") {
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            item(key = "source-any") {
                                FilterChip(
                                    selected = draft.source == null,
                                    onClick = { draft = draft.copy(source = null) },
                                    label = { Text("Any source") },
                                    modifier = Modifier.heightIn(min = 48.dp),
                                )
                            }
                            items(TransactionSource.entries) { option ->
                                FilterChip(
                                    selected = draft.source == option,
                                    onClick = { draft = draft.copy(source = option) },
                                    label = { Text(option.activityLabel()) },
                                    modifier = Modifier.heightIn(min = 48.dp),
                                )
                            }
                        }
                    }
                }

                if (institutionOptions.isNotEmpty()) {
                    item(key = "filter-institution") {
                        ActivityFilterSection(title = "Institution") {
                            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                item(key = "institution-any") {
                                    FilterChip(
                                        selected = draft.institution == null,
                                        onClick = { draft = draft.copy(institution = null) },
                                        label = { Text("Any institution") },
                                        modifier = Modifier.heightIn(min = 48.dp),
                                    )
                                }
                                items(institutionOptions, key = { it.lowercase(Locale.ROOT) }) { option ->
                                    FilterChip(
                                        selected = draft.institution.equals(option, ignoreCase = true),
                                        onClick = { draft = draft.copy(institution = option) },
                                        label = {
                                            Text(option, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        },
                                        modifier = Modifier.heightIn(min = 48.dp).widthIn(max = 220.dp),
                                    )
                                }
                            }
                        }
                    }
                }

                if (tagOptions.isNotEmpty()) {
                    item(key = "filter-tags") {
                        ActivityFilterSection(title = "Tag") {
                            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                item(key = "tag-any") {
                                    FilterChip(
                                        selected = draft.tag == null,
                                        onClick = { draft = draft.copy(tag = null) },
                                        label = { Text("Any tag") },
                                        modifier = Modifier.heightIn(min = 48.dp),
                                    )
                                }
                                items(tagOptions, key = { it.lowercase(Locale.ROOT) }) { option ->
                                    FilterChip(
                                        selected = draft.tag.equals(option, ignoreCase = true),
                                        onClick = { draft = draft.copy(tag = option) },
                                        label = { Text("#$option") },
                                        modifier = Modifier.heightIn(min = 48.dp),
                                    )
                                }
                            }
                        }
                    }
                }

                item(key = "filter-review") {
                    ActivityFilterSection(title = "Review status") {
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            item(key = "review-any") {
                                FilterChip(
                                    selected = draft.reviewStatus == null,
                                    onClick = { draft = draft.copy(reviewStatus = null) },
                                    label = { Text("Any status") },
                                    modifier = Modifier.heightIn(min = 48.dp),
                                )
                            }
                            item(key = "review-confirmed") {
                                FilterChip(
                                    selected = draft.reviewStatus == ReviewStatus.CONFIRMED,
                                    onClick = { draft = draft.copy(reviewStatus = ReviewStatus.CONFIRMED) },
                                    label = { Text("Confirmed") },
                                    modifier = Modifier.heightIn(min = 48.dp),
                                )
                            }
                            item(key = "review-needed") {
                                FilterChip(
                                    selected = draft.reviewStatus == ReviewStatus.NEEDS_REVIEW,
                                    onClick = { draft = draft.copy(reviewStatus = ReviewStatus.NEEDS_REVIEW) },
                                    label = { Text("Needs review") },
                                    modifier = Modifier.heightIn(min = 48.dp),
                                )
                            }
                        }
                    }
                }

                item(key = "filter-duplicates") {
                    Row(
                        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Merged duplicate SMS", style = MaterialTheme.typography.titleSmall)
                            Text(
                                text = "Only show entries formed from two or more matching messages.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(
                            checked = draft.duplicateOnly,
                            onCheckedChange = { draft = draft.copy(duplicateOnly = it) },
                            modifier = Modifier.semantics {
                                contentDescription = "Only show merged duplicate SMS entries"
                            },
                        )
                    }
                }

                if (savedViews.isNotEmpty()) {
                    item(key = "manage-saved-heading") {
                        HorizontalDivider()
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = "Manage saved views",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                    items(savedViews, key = { "manage-${it.id}" }) { view ->
                        Row(
                            modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            TextButton(
                                onClick = { onApplySavedView(view) },
                                modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                            ) {
                                Text(
                                    text = view.name,
                                    modifier = Modifier.fillMaxWidth(),
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            IconButton(
                                onClick = { onRenameSavedView(view) },
                                modifier = Modifier.semantics {
                                    contentDescription = "Rename saved view ${view.name}"
                                },
                            ) {
                                Icon(Icons.Rounded.Edit, contentDescription = null)
                            }
                            IconButton(
                                onClick = { onDeleteSavedView(view) },
                                modifier = Modifier.semantics {
                                    contentDescription = "Delete saved view ${view.name}"
                                },
                            ) {
                                Icon(Icons.Rounded.Delete, contentDescription = null)
                            }
                        }
                    }
                }

                item(key = "filter-actions") {
                    HorizontalDivider()
                    Spacer(Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth().navigationBarsPadding(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        TextButton(
                            onClick = {
                                draft = ActivityFilterState()
                                minimumAmount = ""
                                maximumAmount = ""
                                startDate = ""
                                endDate = ""
                            },
                            modifier = Modifier.heightIn(min = 48.dp),
                        ) { Text("Reset") }
                        Button(
                            onClick = {
                                onApply(
                                    draft.copy(
                                        selectedAccountKeys = draftAccountSelection.selectionKeys,
                                        customStartEpochDay = if (draft.dateRange == ActivityDateRange.CUSTOM) {
                                            parsedStartDate
                                        } else {
                                            null
                                        },
                                        customEndEpochDay = if (draft.dateRange == ActivityDateRange.CUSTOM) {
                                            parsedEndDate
                                        } else {
                                            null
                                        },
                                        minimumAmountMinor = parsedMinimum,
                                        maximumAmountMinor = parsedMaximum,
                                    ),
                                )
                            },
                            enabled = canApply,
                            modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                        ) { Text("Apply filters") }
                    }
                }
            }
        }
    }
}

@Composable
private fun ActivityFilterSection(
    title: String,
    content: @Composable () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
        )
        content()
    }
}

@Composable
private fun ActivityDateField(
    label: String,
    value: String,
    isError: Boolean,
    onValueChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = value,
        onValueChange = { onValueChange(it.take(10)) },
        label = { Text(label) },
        placeholder = { Text("YYYY-MM-DD") },
        supportingText = {
            Text(if (isError) "Use YYYY-MM-DD" else "Date format: YYYY-MM-DD")
        },
        isError = isError,
        singleLine = true,
        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
    )
}

@Composable
private fun ActivityAmountField(
    label: String,
    value: String,
    isError: Boolean,
    onValueChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = value,
        onValueChange = { onValueChange(it.filter { character -> character.isDigit() || character in ".," }.take(16)) },
        label = { Text(label) },
        prefix = { Text("₹") },
        supportingText = { if (isError) Text("Enter a valid non-negative amount") },
        isError = isError,
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
    )
}

@Composable
private fun ActivityViewNameDialog(
    title: String,
    actionLabel: String,
    initialName: String,
    helperText: String,
    existingNames: List<String>,
    allowExistingName: Boolean,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember(initialName) { mutableStateOf(initialName) }
    val cleanName = normalizedActivitySavedViewName(name)
    val duplicate = cleanName != null && existingNames.any { it.equals(cleanName, ignoreCase = true) }
    val invalid = cleanName == null || (duplicate && !allowExistingName)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it.take(32) },
                label = { Text("View name") },
                supportingText = {
                    Text(
                        when {
                            duplicate && !allowExistingName -> "A saved view already uses this name."
                            duplicate -> "Saving will update the existing view with this name."
                            else -> helperText
                        },
                    )
                },
                isError = duplicate && !allowExistingName,
                singleLine = true,
                modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
            )
        },
        confirmButton = {
            TextButton(
                onClick = { cleanName?.let(onConfirm) },
                enabled = !invalid,
            ) { Text(actionLabel) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

private fun TransactionSource.activityLabel(): String = when (this) {
    TransactionSource.BANK -> "Bank SMS"
    TransactionSource.CARD -> "Card SMS"
    TransactionSource.UPI -> "UPI"
    TransactionSource.WALLET -> "Wallet"
    TransactionSource.MANUAL -> "Manual"
    TransactionSource.STATEMENT -> "Statement"
}

internal data class ActivityAccountFilterOption(
    val key: String,
    val type: AccountType,
    val label: String,
    val accessibilityLabel: String,
    val institutionName: String?,
    val institutionNames: Set<String>,
    val lastFour: String?,
    val lastFourValues: Set<String>,
    val accountIds: Set<Long>,
    val accountNames: Set<String>,
    /** Previous physical-account option keys that now resolve to this logical account. */
    val legacyKeys: Set<String>,
)

private data class TaggedActivityAccount(
    val type: AccountType,
    val institutionName: String,
    val lastFour: String?,
    val accountName: String?,
    val accountId: Long?,
)

internal data class ActivityAccountSelectionResolution(
    val resolvedKeys: Set<String>,
    val unavailableKeys: Set<String>,
) {
    val selectionKeys: Set<String>
        get() = buildSet {
            addAll(resolvedKeys)
            addAll(unavailableKeys)
        }
}

internal fun activityAccountFilterOptions(
    accounts: List<AccountProfile>,
    transactions: List<TransactionRecord> = emptyList(),
): List<ActivityAccountFilterOption> {
    val accountsById = accounts.associateBy(AccountProfile::id)
    val taggedAccounts = transactions.mapNotNull { transaction ->
        val linkedAccount = transaction.accountId?.let(accountsById::get)
        val type = linkedAccount?.type
            ?.takeIf { it == AccountType.BANK_ACCOUNT || it == AccountType.CREDIT_CARD }
            ?: transaction.activityAccountType()
            ?: return@mapNotNull null
        val institution = transactionCanonicalInstitution(transaction) ?: return@mapNotNull null
        TaggedActivityAccount(
            type = type,
            institutionName = institution,
            lastFour = accountLastFour(transaction.accountHint),
            accountName = transaction.accountName?.trim()?.takeIf(String::isNotBlank),
            accountId = transaction.accountId?.takeIf(accountsById::containsKey),
        )
    }

    val profileOptions = accounts
        .filter { it.type == AccountType.BANK_ACCOUNT || it.type == AccountType.CREDIT_CARD }
        .groupBy { account ->
            activityAccountOptionKey(
                type = account.type,
                institution = accountCanonicalInstitution(account),
                lastFour = accountLastFour(account.accountHint),
                fallbackAccountId = account.id,
            )
        }
        .map { (key, matches) ->
            val preferred = matches.minBy(AccountProfile::id)
            val accountIds = matches.mapTo(linkedSetOf(), AccountProfile::id)
            val profileInstitutions = matches.mapNotNull(::accountCanonicalInstitution)
            val profileInstitutionKeys = profileInstitutions
                .mapTo(hashSetOf(), ::normalizedAccountValue)
            val linkedTaggedMatches = taggedAccounts.filter { tagged ->
                tagged.type == preferred.type && tagged.accountId in accountIds
            }
            val profileLastFourValues = matches.mapNotNullTo(linkedSetOf()) {
                accountLastFour(it.accountHint)
            }
            val exactUnlinkedMatches = taggedAccounts.filter { tagged ->
                tagged.type == preferred.type &&
                    tagged.accountId == null &&
                    tagged.lastFour != null &&
                    tagged.lastFour in profileLastFourValues &&
                    normalizedAccountValue(tagged.institutionName) in profileInstitutionKeys
            }
            val taggedMatches = (linkedTaggedMatches + exactUnlinkedMatches).distinct()
            val lastFourValues = (profileLastFourValues + taggedMatches.mapNotNull(TaggedActivityAccount::lastFour))
                .toCollection(linkedSetOf())
            val lastFour = lastFourValues.singleOrNull()
            val institutions = (profileInstitutions + taggedMatches.map(TaggedActivityAccount::institutionName))
                .distinctBy(::normalizedAccountValue)
                .toCollection(linkedSetOf())
            val institution = profileInstitutions.firstOrNull()
                ?: institutions.singleOrNull()
            val name = preferred.name.trim().ifBlank { institution ?: preferred.type.label }
            val legacyKeys = linkedTaggedMatches.mapNotNullTo(linkedSetOf()) { tagged ->
                activityPhysicalAccountOptionKey(tagged.type, tagged.institutionName, tagged.lastFour)
            }.apply { remove(key) }
            activityAccountFilterOption(
                key = key,
                type = preferred.type,
                name = name,
                institution = institution,
                institutions = institutions,
                lastFour = lastFour,
                lastFourValues = lastFourValues,
                accountIds = accountIds,
                accountNames = (matches.mapNotNull { it.name.trim().takeIf(String::isNotBlank) } +
                    taggedMatches.mapNotNull(TaggedActivityAccount::accountName)).toCollection(linkedSetOf()),
                legacyKeys = legacyKeys,
            )
        }

    val virtualOptions = taggedAccounts
        .filter { it.accountId == null }
        .filterNot { tagged ->
            profileOptions.any { option ->
                val institutionMatches = option.institutionNames.any {
                    normalizedAccountValue(it) == normalizedAccountValue(tagged.institutionName)
                }
                option.type == tagged.type &&
                    institutionMatches &&
                    (tagged.lastFour == null || tagged.lastFour in option.lastFourValues)
            }
        }
        .groupBy { tagged ->
            activityPhysicalAccountOptionKey(tagged.type, tagged.institutionName, tagged.lastFour)
                ?: "${tagged.type.name}|institution:${normalizedAccountValue(tagged.institutionName)}|last4:unknown"
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
                lastFourValues = matches.mapNotNullTo(linkedSetOf(), TaggedActivityAccount::lastFour),
                accountIds = emptySet(),
                accountNames = matches.mapNotNullTo(linkedSetOf(), TaggedActivityAccount::accountName),
                legacyKeys = emptySet(),
            )
        }

    return (profileOptions + virtualOptions).sortedWith(
        compareBy<ActivityAccountFilterOption> { if (it.type == AccountType.BANK_ACCOUNT) 0 else 1 }
            .thenBy(String.CASE_INSENSITIVE_ORDER, ActivityAccountFilterOption::label)
            .thenBy(ActivityAccountFilterOption::key),
    )
}

internal fun resolveActivityAccountSelections(
    selectedAccountKeys: Set<String>,
    accountOptions: List<ActivityAccountFilterOption>,
): ActivityAccountSelectionResolution {
    val resolved = linkedSetOf<String>()
    val unavailable = linkedSetOf<String>()
    val optionsByKey = accountOptions.associateBy(ActivityAccountFilterOption::key)

    selectedAccountKeys.asSequence().filter(String::isNotBlank).forEach { selectedKey ->
        val exact = optionsByKey[selectedKey]
        if (exact != null) {
            resolved += exact.key
            return@forEach
        }

        val legacyCandidates = accountOptions.filter { selectedKey in it.legacyKeys }
        val compatibleCandidates = if (legacyCandidates.isEmpty()) {
            legacyLastFourSelection(selectedKey)?.let { (type, lastFour) ->
                accountOptions.filter { option ->
                    option.type == type && lastFour in option.lastFourValues
                }
            }.orEmpty()
        } else {
            emptyList()
        }
        val candidates = (legacyCandidates + compatibleCandidates).distinctBy(ActivityAccountFilterOption::key)
        if (candidates.size == 1) {
            resolved += candidates.single().key
        } else {
            // Retaining an unresolved key makes the view narrower (zero matches) instead of
            // silently turning a stale account-scoped view into "All accounts".
            unavailable += selectedKey
        }
    }
    return ActivityAccountSelectionResolution(
        resolvedKeys = resolved,
        unavailableKeys = unavailable,
    )
}

internal fun validActivityAccountSelections(
    selectedAccountKeys: Set<String>,
    accountOptions: List<ActivityAccountFilterOption>,
): Set<String> = resolveActivityAccountSelections(selectedAccountKeys, accountOptions).selectionKeys

internal fun filterActivityTransactions(
    transactions: List<TransactionRecord>,
    query: String,
    typeFilter: TransactionFilter,
    selectedAccountKeys: Set<String>,
    accountOptions: List<ActivityAccountFilterOption>,
): List<TransactionRecord> = filterActivityTransactions(
    transactions = transactions,
    filters = ActivityFilterState(
        query = query,
        typeFilter = typeFilter,
        selectedAccountKeys = selectedAccountKeys,
    ),
    accountOptions = accountOptions,
)

internal fun filterActivityTransactions(
    transactions: List<TransactionRecord>,
    filters: ActivityFilterState,
    accountOptions: List<ActivityAccountFilterOption>,
    nowMillis: Long = System.currentTimeMillis(),
    zoneId: ZoneId = ZoneId.systemDefault(),
): List<TransactionRecord> {
    val accountSelection = resolveActivityAccountSelections(filters.selectedAccountKeys, accountOptions)
    if (filters.selectedAccountKeys.isNotEmpty() && accountSelection.resolvedKeys.isEmpty()) return emptyList()
    return transactions.filter { transaction ->
        transactionMatchesActivityFilters(transaction, filters, nowMillis, zoneId) &&
            transactionMatchesActivityAccounts(transaction, accountSelection.resolvedKeys, accountOptions)
    }
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
        // A canonical institution tag is stronger than a user-facing account name. Never let
        // a shared label such as "Savings" bridge two different institutions.
        val identityMatch = if (normalizedInstitution != null) institutionMatch else accountNameMatch
        val sourceTypeMatch = when (option.type) {
            AccountType.BANK_ACCOUNT -> transaction.source == TransactionSource.BANK ||
                transaction.source == TransactionSource.UPI
            AccountType.CREDIT_CARD -> transaction.source == TransactionSource.CARD
            else -> false
        }
        val identityPeers = accountOptions.filter { peer ->
            peer.type == option.type &&
                (if (normalizedInstitution != null) {
                    peer.institutionNames.any {
                        normalizedAccountValue(it) == normalizedInstitution
                    }
                } else {
                    accountNameMatch && peer.accountNames.any { name ->
                        name.equals(transactionAccountName, ignoreCase = true)
                    }
                })
        }
        val identityIsUnambiguous = identityPeers.size == 1
        val accountIdentityMatch = if (transactionLastFour != null) {
            transactionLastFour in option.lastFourValues
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
    lastFourValues: Set<String>,
    accountIds: Set<Long>,
    accountNames: Set<String>,
    legacyKeys: Set<String>,
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
        lastFourValues = lastFourValues,
        accountIds = accountIds,
        accountNames = accountNames,
        legacyKeys = legacyKeys,
    )
}

private fun activityAccountOptionKey(
    type: AccountType,
    institution: String?,
    lastFour: String?,
    fallbackAccountId: Long,
): String {
    val physicalKey = activityPhysicalAccountOptionKey(type, institution, lastFour)
    if (physicalKey != null) return physicalKey
    val institutionKey = institution?.let(::normalizedAccountValue)?.takeIf(String::isNotBlank)
    return "${type.name}|institution:${institutionKey ?: "unknown"}|account:$fallbackAccountId"
}

private fun activityPhysicalAccountOptionKey(
    type: AccountType,
    institution: String?,
    lastFour: String?,
): String? {
    val institutionKey = institution?.let(::normalizedAccountValue)?.takeIf(String::isNotBlank)
        ?: return null
    val cleanLastFour = lastFour?.takeIf { it.length == 4 && it.all(Char::isDigit) }
        ?: return null
    return "${type.name}|institution:$institutionKey|last4:$cleanLastFour"
}

private fun legacyLastFourSelection(key: String): Pair<AccountType, String>? {
    val match = LEGACY_LAST_FOUR_SELECTION.matchEntire(key) ?: return null
    val type = AccountType.entries.firstOrNull { it.name == match.groupValues[1] } ?: return null
    return type to match.groupValues[2]
}

private val LEGACY_LAST_FOUR_SELECTION = Regex("^([A-Z_]+)\\|last4:(\\d{4})$")

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
