package com.paisalens.app.ui

import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.FactCheck
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ChevronLeft
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.LinkOff
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.AlertDialog
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.paisalens.app.data.model.AccountProfile
import com.paisalens.app.data.model.MonthlyReconciliation
import com.paisalens.app.data.model.ReconciliationMetrics
import com.paisalens.app.data.model.ReconciliationStatus
import com.paisalens.app.data.model.ReviewStatus
import com.paisalens.app.data.model.TransactionLink
import com.paisalens.app.data.model.TransactionLinkSuggestion
import com.paisalens.app.data.model.TransactionLinkType
import com.paisalens.app.data.model.TransactionRecord
import com.paisalens.app.data.model.deduplicateReconciliationTransactions
import com.paisalens.app.data.model.suggestReconciliationStatus
import com.paisalens.app.ui.components.PaisaCard
import com.paisalens.app.ui.components.formatMoney
import java.math.BigDecimal
import java.math.RoundingMode
import java.text.SimpleDateFormat
import java.time.Instant
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Date
import java.util.Locale

/**
 * Read-only comparison and explicit link-review surface. This sheet never changes a transaction
 * until one of its callbacks is invoked by the user.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrustCenterSheet(
    selectedPeriod: YearMonth,
    accounts: List<AccountProfile>,
    selectedAccountId: Long?,
    reconciliation: MonthlyReconciliation?,
    metrics: ReconciliationMetrics?,
    suggestions: List<TransactionLinkSuggestion>,
    links: List<TransactionLink>,
    transactionsById: Map<Long, TransactionRecord>,
    isLoading: Boolean,
    errorMessage: String?,
    canNavigateToNextMonth: Boolean,
    onAccountSelected: (Long) -> Unit,
    onPreviousMonth: () -> Unit,
    onNextMonth: () -> Unit,
    onSaveReconciliation: (MonthlyReconciliation) -> Unit,
    onStatusChange: (ReconciliationStatus) -> Unit,
    onAcceptSuggestion: (TransactionLinkSuggestion) -> Unit,
    onIgnoreSuggestion: (TransactionLinkSuggestion) -> Unit,
    onUnlink: (TransactionLink) -> Unit,
    onTransactionClick: (Long) -> Unit,
    onRetry: () -> Unit,
    onDismiss: () -> Unit,
) {
    var selectedSuggestion by remember { mutableStateOf<TransactionLinkSuggestion?>(null) }
    var pendingUnlink by remember { mutableStateOf<TransactionLink?>(null) }
    var showReconciliationEditor by remember { mutableStateOf(false) }
    val appTransactionCount = remember(transactionsById, links, selectedPeriod, selectedAccountId) {
        selectedAccountId?.let { accountId ->
            deduplicateReconciliationTransactions(
                transactions = transactionsById.values.filter { transaction ->
                    transaction.accountId == accountId &&
                        transaction.reviewStatus == ReviewStatus.CONFIRMED &&
                        transactionPeriod(transaction.occurredAt) == selectedPeriod
                },
                transactionLinks = links,
            ).size
        }
    }
    val visibleLinks = remember(links, transactionsById, selectedPeriod, selectedAccountId) {
        links.filter { link ->
            val source = transactionsById[link.sourceTransactionId]
            val target = transactionsById[link.targetTransactionId]
            val inSelectedMonth = listOfNotNull(source, target).any { transactionPeriod(it.occurredAt) == selectedPeriod }
            val inSelectedAccount = selectedAccountId == null || listOfNotNull(source, target).any {
                it.accountId == selectedAccountId
            }
            inSelectedMonth && inSelectedAccount
        }
    }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.background,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.95f)
                .navigationBarsPadding(),
        ) {
            TrustCenterHeader(onDismiss = onDismiss)
            when {
                isLoading -> TrustLoadingState(
                    title = "Checking this month",
                    detail = "Comparing statement totals with local PaisaLens records.",
                )
                errorMessage != null -> TrustErrorState(
                    message = errorMessage,
                    onRetry = onRetry,
                )
                else -> LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = 18.dp, end = 18.dp, bottom = 32.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    item {
                        LocalTrustNotice(
                            "Reconciliation runs on this phone. PaisaLens shows the source behind every match and never moves money.",
                        )
                    }
                    item {
                        MonthSelector(
                            period = selectedPeriod,
                            canNavigateToNextMonth = canNavigateToNextMonth,
                            onPreviousMonth = onPreviousMonth,
                            onNextMonth = onNextMonth,
                        )
                    }
                    item {
                        AccountPicker(
                            accounts = accounts,
                            selectedAccountId = selectedAccountId,
                            onAccountSelected = onAccountSelected,
                        )
                    }
                    if (reconciliation == null) {
                        item {
                            TrustEmptyState(
                                icon = Icons.AutoMirrored.Rounded.FactCheck,
                                title = "No statement checked for this month",
                                detail = "Start a reconciliation after importing a statement or entering its summary manually.",
                                actionLabel = "Start reconciliation",
                                onAction = { showReconciliationEditor = true },
                            )
                        }
                    } else {
                        item {
                            ReconciliationSummaryCard(
                                reconciliation = reconciliation,
                                metrics = metrics,
                                onEdit = { showReconciliationEditor = true },
                                onStatusChange = onStatusChange,
                            )
                        }
                        item { ReconciliationSourceCard(reconciliation, metrics) }
                    }
                    item {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("Suggested transaction links", style = MaterialTheme.typography.titleLarge)
                                Text(
                                    "Review before linking transfers, refunds, reversals, or card payments.",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Surface(
                                shape = CircleShape,
                                color = MaterialTheme.colorScheme.primaryContainer,
                                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                            ) {
                                Text(
                                    suggestions.size.toString(),
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
                                    style = MaterialTheme.typography.labelLarge,
                                )
                            }
                        }
                    }
                    if (suggestions.isEmpty()) {
                        item {
                            TrustEmptyState(
                                icon = Icons.Rounded.CheckCircle,
                                title = "No links need review",
                                detail = "New suggestions appear only when local transactions have matching amounts and dates.",
                            )
                        }
                    } else {
                        items(
                            items = suggestions,
                            key = { "${it.sourceTransactionId}:${it.targetTransactionId}:${it.type}" },
                        ) { suggestion ->
                            TransactionLinkSuggestionCard(
                                suggestion = suggestion,
                                source = transactionsById[suggestion.sourceTransactionId],
                                target = transactionsById[suggestion.targetTransactionId],
                                onOpen = { selectedSuggestion = suggestion },
                                onAccept = { onAcceptSuggestion(suggestion) },
                                onIgnore = { onIgnoreSuggestion(suggestion) },
                            )
                        }
                    }
                    item {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text("Linked transactions", style = MaterialTheme.typography.titleLarge)
                            Text(
                                "Confirmed links for this account and month. Unlinking adds an auditable change.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    if (visibleLinks.isEmpty()) {
                        item {
                            TrustEmptyState(
                                icon = Icons.Rounded.LinkOff,
                                title = "No confirmed links this month",
                                detail = "Accepted transaction pairs will appear here with their local source records.",
                            )
                        }
                    } else {
                        items(visibleLinks, key = TransactionLink::id) { link ->
                            ExistingTransactionLinkCard(
                                link = link,
                                source = transactionsById[link.sourceTransactionId],
                                target = transactionsById[link.targetTransactionId],
                                onUnlink = { pendingUnlink = link },
                            )
                        }
                    }
                }
            }
        }
    }

    selectedSuggestion?.let { suggestion ->
        TransactionLinkDetailsDialog(
            suggestion = suggestion,
            source = transactionsById[suggestion.sourceTransactionId],
            target = transactionsById[suggestion.targetTransactionId],
            onOpenTransaction = onTransactionClick,
            onAccept = {
                onAcceptSuggestion(suggestion)
                selectedSuggestion = null
            },
            onDismiss = { selectedSuggestion = null },
        )
    }
    pendingUnlink?.let { link ->
        AlertDialog(
            onDismissRequest = { pendingUnlink = null },
            icon = { Icon(Icons.Rounded.LinkOff, contentDescription = null) },
            title = { Text("Unlink these transactions?") },
            text = {
                Text(
                    "This removes the ${linkTypeLabel(link.type).lowercase()} between " +
                        "${transactionsById[link.sourceTransactionId]?.merchant ?: "transaction #${link.sourceTransactionId}"} and " +
                        "${transactionsById[link.targetTransactionId]?.merchant ?: "transaction #${link.targetTransactionId}"}. " +
                        "The underlying transactions will not be deleted.",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onUnlink(link)
                        pendingUnlink = null
                    },
                    modifier = Modifier.heightIn(min = 48.dp),
                ) {
                    Text("Unlink")
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingUnlink = null }, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text("Cancel")
                }
            },
        )
    }
    if (showReconciliationEditor) {
        val fallbackAccountId = selectedAccountId ?: accounts.firstOrNull()?.id ?: 0L
        ReconciliationEditorDialog(
            initial = reconciliation ?: MonthlyReconciliation(
                accountId = fallbackAccountId,
                year = selectedPeriod.year,
                month = selectedPeriod.monthValue,
            ),
            accounts = accounts,
            selectedPeriod = selectedPeriod,
            appTransactionCount = appTransactionCount,
            onAccountSelected = onAccountSelected,
            onSave = {
                onSaveReconciliation(it)
                showReconciliationEditor = false
            },
            onDismiss = { showReconciliationEditor = false },
        )
    }
}

@Composable
private fun TrustCenterHeader(onDismiss: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 20.dp, end = 12.dp, bottom = 14.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .background(MaterialTheme.colorScheme.primaryContainer, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Rounded.Shield, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            }
            Column {
                Text("Trust Center", style = MaterialTheme.typography.headlineMedium)
                Text(
                    "Trace, compare, and confirm",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        IconButton(
            onClick = onDismiss,
            modifier = Modifier.size(48.dp),
        ) {
            Icon(Icons.Rounded.Close, contentDescription = "Close Trust Center")
        }
    }
}

@Composable
private fun MonthSelector(
    period: YearMonth,
    canNavigateToNextMonth: Boolean,
    onPreviousMonth: () -> Unit,
    onNextMonth: () -> Unit,
) {
    PaisaCard(Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(
                onClick = onPreviousMonth,
                modifier = Modifier.size(48.dp),
            ) {
                Icon(Icons.Rounded.ChevronLeft, contentDescription = "Previous month")
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    period.format(DateTimeFormatter.ofPattern("MMMM uuuu", Locale.ENGLISH)),
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    "Monthly reconciliation",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(
                onClick = onNextMonth,
                enabled = canNavigateToNextMonth,
                modifier = Modifier.size(48.dp),
            ) {
                Icon(Icons.Rounded.ChevronRight, contentDescription = "Next month")
            }
        }
    }
}

@Composable
private fun AccountPicker(
    accounts: List<AccountProfile>,
    selectedAccountId: Long?,
    onAccountSelected: (Long) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Account", style = MaterialTheme.typography.labelLarge)
        if (accounts.isEmpty()) {
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = MaterialTheme.shapes.medium,
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(14.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Rounded.WarningAmber, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Text(
                        "No accounts are available. Add or detect an account before reconciling a statement.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        } else {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(accounts, key = AccountProfile::id) { account ->
                    FilterChip(
                        selected = selectedAccountId == account.id,
                        onClick = { onAccountSelected(account.id) },
                        modifier = Modifier.heightIn(min = 48.dp),
                        label = {
                            Text(
                                account.name + account.accountHint?.filter(Char::isDigit)?.takeLast(4)?.let { " · •••• $it" }.orEmpty(),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun ReconciliationSummaryCard(
    reconciliation: MonthlyReconciliation,
    metrics: ReconciliationMetrics?,
    onEdit: () -> Unit,
    onStatusChange: (ReconciliationStatus) -> Unit,
) {
    val matchPercent = metrics?.matchPercent
    val status = reconciliation.status
    val statusColor = reconciliationStatusColor(status)
    val canMarkReconciled = metrics?.let {
        it.matchedTransactionCount <= it.appTransactionCount &&
            suggestReconciliationStatus(it) == ReconciliationStatus.BALANCED
    } == true && status != ReconciliationStatus.RECONCILED

    PaisaCard(Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Reconciliation status", style = MaterialTheme.typography.labelLarge)
                    Text(
                        reconciliationStatusLabel(status),
                        style = MaterialTheme.typography.titleLarge,
                        color = statusColor,
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = if (status == ReconciliationStatus.RECONCILED || status == ReconciliationStatus.BALANCED) {
                            Icons.Rounded.CheckCircle
                        } else {
                            Icons.Rounded.WarningAmber
                        },
                        contentDescription = null,
                        tint = statusColor,
                        modifier = Modifier.size(28.dp),
                    )
                    IconButton(onClick = onEdit, modifier = Modifier.size(48.dp)) {
                        Icon(
                            imageVector = Icons.Rounded.Edit,
                            contentDescription = "Edit reconciliation values",
                        )
                    }
                }
            }
            if (matchPercent != null) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Transactions matched", style = MaterialTheme.typography.bodyMedium)
                        Text("$matchPercent%", fontWeight = FontWeight.SemiBold)
                    }
                    LinearProgressIndicator(
                        progress = { matchPercent.coerceIn(0, 100) / 100f },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(8.dp)
                            .semantics {
                                contentDescription = "$matchPercent percent of statement transactions matched"
                            },
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                ReconciliationMetric(
                    label = "Matched",
                    value = metrics?.matchedTransactionCount ?: reconciliation.matchedTransactionCount,
                    modifier = Modifier.weight(1f),
                )
                ReconciliationMetric(
                    label = "Statement only",
                    value = metrics?.unmatchedStatementCount ?: reconciliation.unmatchedStatementCount,
                    modifier = Modifier.weight(1f),
                )
                ReconciliationMetric(
                    label = "App only",
                    value = metrics?.unmatchedAppCount ?: reconciliation.unmatchedAppCount,
                    modifier = Modifier.weight(1f),
                )
            }
            metrics?.balanceDifferenceMinor?.let { difference ->
                Surface(
                    color = if (difference == 0L) {
                        MaterialTheme.colorScheme.secondaryContainer
                    } else {
                        MaterialTheme.colorScheme.errorContainer
                    },
                    contentColor = if (difference == 0L) {
                        MaterialTheme.colorScheme.onSecondaryContainer
                    } else {
                        MaterialTheme.colorScheme.onErrorContainer
                    },
                    shape = MaterialTheme.shapes.medium,
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(14.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text("Closing balance difference")
                        Text(formatSignedMoney(difference), fontWeight = FontWeight.Bold)
                    }
                }
            }
            if (canMarkReconciled) {
                Button(
                    onClick = { onStatusChange(ReconciliationStatus.RECONCILED) },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                ) {
                    Icon(Icons.Rounded.CheckCircle, contentDescription = null)
                    Spacer(Modifier.size(8.dp))
                    Text("Mark month reconciled")
                }
            }
            reconciliation.notes?.takeIf(String::isNotBlank)?.let { notes ->
                Text(
                    notes,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun ExistingTransactionLinkCard(
    link: TransactionLink,
    source: TransactionRecord?,
    target: TransactionRecord?,
    onUnlink: () -> Unit,
) {
    PaisaCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.Link, contentDescription = null, tint = MaterialTheme.colorScheme.secondary)
                    Text(linkTypeLabel(link.type), style = MaterialTheme.typography.titleMedium)
                }
                Text(
                    fullDateTime(link.createdAt),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            LinkEndpoint(source, "From", link.sourceTransactionId)
            HorizontalDivider()
            LinkEndpoint(target, "To", link.targetTransactionId)
            link.note?.takeIf(String::isNotBlank)?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            OutlinedButton(
                onClick = onUnlink,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
            ) {
                Icon(Icons.Rounded.LinkOff, contentDescription = null)
                Spacer(Modifier.size(8.dp))
                Text("Unlink")
            }
        }
    }
}

/** Editor for one account/month reconciliation. Amounts are entered in major currency units. */
@Composable
fun ReconciliationEditorDialog(
    initial: MonthlyReconciliation,
    accounts: List<AccountProfile>,
    selectedPeriod: YearMonth,
    appTransactionCount: Int? = null,
    onAccountSelected: ((Long) -> Unit)? = null,
    onSave: (MonthlyReconciliation) -> Unit,
    onDismiss: () -> Unit,
) {
    var accountId by remember(initial, accounts) {
        mutableStateOf(initial.accountId.takeIf { id -> accounts.any { it.id == id } } ?: accounts.firstOrNull()?.id ?: 0L)
    }
    var openingBalance by remember(initial) { mutableStateOf(initial.openingBalanceMinor?.let(::editableTrustMoney).orEmpty()) }
    var closingBalance by remember(initial) { mutableStateOf(initial.closingBalanceMinor?.let(::editableTrustMoney).orEmpty()) }
    var statementCount by remember(initial) { mutableStateOf(initial.statementTransactionCount.toString()) }
    var matchedCount by remember(initial) { mutableStateOf(initial.matchedTransactionCount.toString()) }
    var unmatchedStatementCount by remember(initial) { mutableStateOf(initial.unmatchedStatementCount.toString()) }
    var unmatchedAppCount by remember(initial) { mutableStateOf(initial.unmatchedAppCount.toString()) }
    var notes by remember(initial) { mutableStateOf(initial.notes.orEmpty()) }
    var error by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.AutoMirrored.Rounded.FactCheck, contentDescription = null) },
        title = { Text("Reconciliation details") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    selectedPeriod.format(DateTimeFormatter.ofPattern("MMMM uuuu", Locale.ENGLISH)),
                    style = MaterialTheme.typography.titleMedium,
                )
                AccountPicker(
                    accounts = accounts,
                    selectedAccountId = accountId.takeIf { it != 0L },
                    onAccountSelected = {
                        accountId = it
                        onAccountSelected?.invoke(it)
                    },
                )
                Text(
                    "Enter balances from the statement summary. Leave a balance blank if it is not printed clearly.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    TrustMoneyField(
                        label = "Opening balance",
                        value = openingBalance,
                        modifier = Modifier.weight(1f),
                        onChange = { openingBalance = it },
                    )
                    TrustMoneyField(
                        label = "Closing balance",
                        value = closingBalance,
                        modifier = Modifier.weight(1f),
                        onChange = { closingBalance = it },
                    )
                }
                TrustCountField("Statement transactions", statementCount) { statementCount = it }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    TrustCountField("Matched", matchedCount, Modifier.weight(1f)) { matchedCount = it }
                    TrustCountField("Statement only", unmatchedStatementCount, Modifier.weight(1f)) {
                        unmatchedStatementCount = it
                    }
                }
                TrustCountField("App only", unmatchedAppCount) { unmatchedAppCount = it }
                OutlinedTextField(
                    value = notes,
                    onValueChange = { notes = it.take(500) },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Notes") },
                    minLines = 2,
                    maxLines = 5,
                    supportingText = { Text("Optional context about missing rows or manual corrections.") },
                )
                error?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                }
                LocalTrustNotice(
                    "Saving records the values and an audit entry. It does not change statement or transaction amounts.",
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    if (accountId == 0L || accounts.none { it.id == accountId }) {
                        error = "Choose an account before saving."
                        return@TextButton
                    }
                    val statement = statementCount.toIntOrNull()
                    val matched = matchedCount.toIntOrNull()
                    val unmatchedStatement = unmatchedStatementCount.toIntOrNull()
                    val unmatchedApp = unmatchedAppCount.toIntOrNull()
                    if (listOf(statement, matched, unmatchedStatement, unmatchedApp).any { it == null || it < 0 }) {
                        error = "Transaction counts must be whole numbers of zero or more."
                        return@TextButton
                    }
                    val statementValue = requireNotNull(statement)
                    val matchedValue = requireNotNull(matched)
                    val unmatchedStatementValue = requireNotNull(unmatchedStatement)
                    val unmatchedAppValue = requireNotNull(unmatchedApp)
                    if (matchedValue + unmatchedStatementValue != statementValue) {
                        error = "Matched plus unmatched statement rows must equal statement count."
                        return@TextButton
                    }
                    if (appTransactionCount != null && matchedValue > appTransactionCount) {
                        error = "Matched rows cannot exceed the $appTransactionCount confirmed app " +
                            "transaction${if (appTransactionCount == 1) "" else "s"} available for this account and month."
                        return@TextButton
                    }
                    val opening = parseOptionalTrustMoney(openingBalance)
                    val closing = parseOptionalTrustMoney(closingBalance)
                    if (openingBalance.isNotBlank() && opening == null) {
                        error = "Opening balance is not a valid amount."
                        return@TextButton
                    }
                    if (closingBalance.isNotBlank() && closing == null) {
                        error = "Closing balance is not a valid amount."
                        return@TextButton
                    }
                    error = null
                    onSave(
                        initial.copy(
                            accountId = accountId,
                            year = selectedPeriod.year,
                            month = selectedPeriod.monthValue,
                            openingBalanceMinor = opening,
                            closingBalanceMinor = closing,
                            statementTransactionCount = statementValue,
                            matchedTransactionCount = matchedValue,
                            unmatchedStatementCount = unmatchedStatementValue,
                            unmatchedAppCount = unmatchedAppValue,
                            notes = notes.trim().takeIf(String::isNotBlank),
                            updatedAt = System.currentTimeMillis(),
                        ),
                    )
                },
                modifier = Modifier.heightIn(min = 48.dp),
            ) {
                Text("Save reconciliation")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp)) {
                Text("Cancel")
            }
        },
    )
}

@Composable
private fun TrustMoneyField(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    onChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = value,
        onValueChange = { input ->
            onChange(input.filter { it.isDigit() || it == '.' || it == '-' }.take(18))
        },
        modifier = modifier,
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Next),
    )
}

@Composable
private fun TrustCountField(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    onChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = value,
        onValueChange = { onChange(it.filter(Char::isDigit).take(6)) },
        modifier = modifier.fillMaxWidth(),
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Next),
    )
}

@Composable
private fun ReconciliationMetric(label: String, value: Int, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = MaterialTheme.shapes.medium,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(value.toString(), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text(
                label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
            )
        }
    }
}

@Composable
private fun ReconciliationSourceCard(
    reconciliation: MonthlyReconciliation,
    metrics: ReconciliationMetrics?,
) {
    PaisaCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.AutoMirrored.Rounded.FactCheck, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Text("How this was checked", style = MaterialTheme.typography.titleMedium)
            }
            SourceLine(
                "Statement source",
                "${reconciliation.statementTransactionCount} statement lines and the entered opening/closing balances",
            )
            SourceLine(
                "PaisaLens source",
                "${metrics?.appTransactionCount ?: 0} confirmed local transactions assigned to this account and month",
            )
            SourceLine(
                "Last calculated",
                fullDateTime(reconciliation.updatedAt),
            )
            Text(
                "A zero difference confirms arithmetic consistency; it does not prove that every charge is authorised.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun SourceLine(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            label,
            modifier = Modifier.weight(0.38f),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(value, modifier = Modifier.weight(0.62f), style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun TransactionLinkSuggestionCard(
    suggestion: TransactionLinkSuggestion,
    source: TransactionRecord?,
    target: TransactionRecord?,
    onOpen: () -> Unit,
    onAccept: () -> Unit,
    onIgnore: () -> Unit,
) {
    PaisaCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Surface(
                onClick = onOpen,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .semantics { role = Role.Button },
                color = Color.Transparent,
                shape = MaterialTheme.shapes.medium,
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(linkTypeLabel(suggestion.type), style = MaterialTheme.typography.titleMedium)
                            Text(
                                "${suggestion.confidence.coerceIn(0, 100)}% confidence",
                                style = MaterialTheme.typography.labelMedium,
                                color = confidenceColor(suggestion.confidence),
                            )
                        }
                        Icon(Icons.AutoMirrored.Rounded.OpenInNew, contentDescription = "Open link details")
                    }
                    LinkEndpoint(source, "From", suggestion.sourceTransactionId)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Center,
                    ) {
                        Icon(Icons.Rounded.Link, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    }
                    LinkEndpoint(target, "To", suggestion.targetTransactionId)
                    Text(
                        suggestion.reason,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(
                    onClick = onIgnore,
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                ) {
                    Text("Not a match")
                }
                Button(
                    onClick = onAccept,
                    enabled = source != null && target != null,
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                ) {
                    Text("Link")
                }
            }
        }
    }
}

@Composable
private fun LinkEndpoint(transaction: TransactionRecord?, label: String, fallbackId: Long) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                transaction?.merchant ?: "Transaction #$fallbackId unavailable",
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            transaction?.let {
                Text(
                    "${fullDateTime(it.occurredAt)} · ${it.source.name.lowercase().replaceFirstChar(Char::titlecase)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        transaction?.let {
            Text(formatMoney(it.amountMinor), fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun TransactionLinkDetailsDialog(
    suggestion: TransactionLinkSuggestion,
    source: TransactionRecord?,
    target: TransactionRecord?,
    onOpenTransaction: (Long) -> Unit,
    onAccept: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Rounded.Link, contentDescription = null) },
        title = { Text("Review ${linkTypeLabel(suggestion.type).lowercase()}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(suggestion.reason)
                DetailTransactionBlock("Source record", source, suggestion.sourceTransactionId, onOpenTransaction)
                HorizontalDivider()
                DetailTransactionBlock("Target record", target, suggestion.targetTransactionId, onOpenTransaction)
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = MaterialTheme.shapes.medium,
                ) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("Source transparency", style = MaterialTheme.typography.labelLarge)
                        Text(
                            "The suggestion uses amount, date proximity, transaction direction, and account assignment. Merchant text is only supporting evidence.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = onAccept,
                enabled = source != null && target != null,
                modifier = Modifier.heightIn(min = 48.dp),
            ) {
                Text("Confirm link")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp)) {
                Text("Cancel")
            }
        },
    )
}

@Composable
private fun DetailTransactionBlock(
    label: String,
    transaction: TransactionRecord?,
    fallbackId: Long,
    onOpenTransaction: (Long) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        if (transaction == null) {
            Text("Transaction #$fallbackId is no longer available.", color = MaterialTheme.colorScheme.error)
        } else {
            Text(transaction.merchant, style = MaterialTheme.typography.titleMedium)
            Text("${formatMoney(transaction.amountMinor)} · ${fullDateTime(transaction.occurredAt)}")
            Text(
                "Source: ${transaction.source.name} / ${transaction.sender.ifBlank { "local entry" }} / ${transaction.sourceMessageId}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            TextButton(
                onClick = { onOpenTransaction(transaction.id) },
                modifier = Modifier.heightIn(min = 48.dp),
            ) {
                Text("Open transaction")
            }
        }
    }
}

@Composable
internal fun LocalTrustNotice(text: String) {
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.68f),
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        shape = MaterialTheme.shapes.large,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(14.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Icon(Icons.Rounded.Shield, contentDescription = null, modifier = Modifier.size(22.dp))
            Text(text, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
internal fun TrustLoadingState(title: String, detail: String) {
    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
            CircularProgressIndicator(modifier = Modifier.size(44.dp))
            Text(title, style = MaterialTheme.typography.titleLarge)
            Text(detail, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
internal fun TrustErrorState(message: String, onRetry: () -> Unit) {
    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(
                Icons.Rounded.ErrorOutline,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(48.dp),
            )
            Text("Could not load this check", style = MaterialTheme.typography.titleLarge)
            Text(message, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Button(onClick = onRetry, modifier = Modifier.heightIn(min = 48.dp)) {
                Text("Try again")
            }
        }
    }
}

@Composable
internal fun TrustEmptyState(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    detail: String,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    PaisaCard(Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(22.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(38.dp))
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(
                detail,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (actionLabel != null && onAction != null) {
                Button(onClick = onAction, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(actionLabel)
                }
            }
        }
    }
}

private fun reconciliationStatusLabel(status: ReconciliationStatus): String = when (status) {
    ReconciliationStatus.DRAFT -> "Draft"
    ReconciliationStatus.REVIEW_REQUIRED -> "Review required"
    ReconciliationStatus.BALANCED -> "Balances agree"
    ReconciliationStatus.RECONCILED -> "Reconciled"
}

@Composable
private fun reconciliationStatusColor(status: ReconciliationStatus): Color = when (status) {
    ReconciliationStatus.DRAFT -> MaterialTheme.colorScheme.onSurfaceVariant
    ReconciliationStatus.REVIEW_REQUIRED -> MaterialTheme.colorScheme.error
    ReconciliationStatus.BALANCED, ReconciliationStatus.RECONCILED -> MaterialTheme.colorScheme.secondary
}

private fun linkTypeLabel(type: TransactionLinkType): String = when (type) {
    TransactionLinkType.TRANSFER -> "Transfer pair"
    TransactionLinkType.REFUND -> "Refund pair"
    TransactionLinkType.REVERSAL -> "Reversal pair"
    TransactionLinkType.CARD_PAYMENT -> "Card payment pair"
    TransactionLinkType.REIMBURSEMENT -> "Reimbursement pair"
}

@Composable
private fun confidenceColor(confidence: Int): Color = when (confidence) {
    in 85..100 -> MaterialTheme.colorScheme.secondary
    in 70..84 -> MaterialTheme.colorScheme.primary
    else -> MaterialTheme.colorScheme.error
}

private fun formatSignedMoney(amountMinor: Long): String = when {
    amountMinor > 0 -> "+${formatMoney(amountMinor)}"
    amountMinor < 0 -> "-${formatMoney(-amountMinor)}"
    else -> formatMoney(0)
}

private fun fullDateTime(epochMillis: Long): String = SimpleDateFormat(
    "d MMM yyyy, h:mm a",
    Locale.getDefault(),
).format(Date(epochMillis))

private fun transactionPeriod(epochMillis: Long): YearMonth = YearMonth.from(
    Instant.ofEpochMilli(epochMillis).atZone(ZoneId.systemDefault()),
)

private fun editableTrustMoney(amountMinor: Long): String = BigDecimal(amountMinor)
    .movePointLeft(2)
    .stripTrailingZeros()
    .toPlainString()

private fun parseOptionalTrustMoney(value: String): Long? {
    if (value.isBlank()) return null
    return runCatching {
        value.trim().replace(",", "").toBigDecimal()
            .multiply(BigDecimal(100))
            .setScale(0, RoundingMode.HALF_UP)
            .longValueExact()
    }.getOrNull()
}
