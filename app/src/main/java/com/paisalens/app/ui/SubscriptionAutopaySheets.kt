package com.paisalens.app.ui

import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AccountBalanceWallet
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.AutoMode
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Event
import androidx.compose.material.icons.rounded.PauseCircle
import androidx.compose.material.icons.rounded.Payments
import androidx.compose.material.icons.rounded.PlayCircle
import androidx.compose.material.icons.rounded.Subscriptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.paisalens.app.data.model.AccountProfile
import com.paisalens.app.data.model.PaymentCommitment
import com.paisalens.app.data.model.PaymentCommitmentKind
import com.paisalens.app.data.model.PaymentCommitmentSource
import com.paisalens.app.data.model.PaymentCommitmentStatus
import com.paisalens.app.data.model.PaymentFrequency
import com.paisalens.app.data.model.currentPaymentDueDate
import com.paisalens.app.data.model.buildPaymentCommitmentDueItems
import com.paisalens.app.data.model.normalizedMerchantKey
import com.paisalens.app.ui.components.PaisaCard
import com.paisalens.app.ui.components.formatMoney
import java.time.LocalDate
import kotlin.math.roundToLong

private enum class CommitmentFilter(val label: String) {
    ALL("All"),
    SUBSCRIPTIONS("Subscriptions"),
    UPI_AUTOPAY("UPI AutoPay"),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SubscriptionAutopayCenterSheet(
    commitments: List<PaymentCommitment>,
    detectedSuggestions: List<PaymentCommitment>,
    accounts: List<AccountProfile>,
    onAddCommitment: () -> Unit,
    onEditCommitment: (PaymentCommitment) -> Unit,
    onUpdateCommitment: (PaymentCommitment) -> Unit,
    onDeleteCommitment: (PaymentCommitment) -> Unit,
    onAcceptSuggestion: (PaymentCommitment) -> Unit,
    onDismiss: () -> Unit,
) {
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
            SharedFinanceSheetHeader(
                title = "Subscriptions & AutoPay",
                subtitle = "Recurring charges and UPI mandates",
                onDismiss = onDismiss,
            )
            SubscriptionAutopayCenterContent(
                commitments = commitments,
                detectedSuggestions = detectedSuggestions,
                accounts = accounts,
                onAddCommitment = onAddCommitment,
                onEditCommitment = onEditCommitment,
                onUpdateCommitment = onUpdateCommitment,
                onDeleteCommitment = onDeleteCommitment,
                onAcceptSuggestion = onAcceptSuggestion,
            )
        }
    }
}

@Composable
internal fun SubscriptionAutopayCenterContent(
    commitments: List<PaymentCommitment>,
    detectedSuggestions: List<PaymentCommitment>,
    accounts: List<AccountProfile>,
    onAddCommitment: () -> Unit,
    onEditCommitment: (PaymentCommitment) -> Unit,
    onUpdateCommitment: (PaymentCommitment) -> Unit,
    onDeleteCommitment: (PaymentCommitment) -> Unit,
    onAcceptSuggestion: (PaymentCommitment) -> Unit,
    modifier: Modifier = Modifier,
) {
    var filter by remember { mutableStateOf(CommitmentFilter.ALL) }
    var deleting by remember { mutableStateOf<PaymentCommitment?>(null) }
    var ignoredSuggestionKeys by remember { mutableStateOf(emptySet<String>()) }
    val today = LocalDate.now()
    val accountNames = remember(accounts) { accounts.associate { it.id to it.name } }
    val visibleCommitments = remember(commitments, filter, today) {
        commitments.filter {
            when (filter) {
                CommitmentFilter.ALL -> true
                CommitmentFilter.SUBSCRIPTIONS -> it.kind == PaymentCommitmentKind.SUBSCRIPTION
                CommitmentFilter.UPI_AUTOPAY -> it.kind == PaymentCommitmentKind.UPI_AUTOPAY
            }
        }.sortedWith(
            compareBy<PaymentCommitment> { it.status != PaymentCommitmentStatus.ACTIVE }
                .thenBy { currentPaymentDueDate(it, today) }
                .thenBy { it.name.lowercase() },
        )
    }
    val visibleSuggestions = remember(detectedSuggestions, ignoredSuggestionKeys) {
        detectedSuggestions.filterNot { suggestionSessionKey(it) in ignoredSuggestionKeys }
    }
    val active = commitments.filter { it.status == PaymentCommitmentStatus.ACTIVE }
    val monthlyEstimate = active.sumOf(::monthlyEquivalentMinor)
    val dueSoon = remember(active, today) {
        buildPaymentCommitmentDueItems(
            commitments = active,
            today = today,
            horizonDays = 31,
            includeRepeatingOccurrences = true,
        ).sumOf { it.amountMinor }
    }

    LazyColumn(
        modifier = modifier,
        contentPadding = PaddingValues(start = 18.dp, end = 18.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            SharedFinancePrivateNotice(
                "PaisaLens detects recurring patterns locally. It cannot create, pause, or cancel a mandate at your bank or UPI app.",
            )
        }
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                CommitmentMetric("Monthly estimate", formatMoney(monthlyEstimate), Modifier.weight(1f))
                CommitmentMetric("Next 30 days", formatMoney(dueSoon), Modifier.weight(1f))
            }
        }
        if (visibleSuggestions.isNotEmpty()) {
            item {
                Text("Detected on this device", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(top = 4.dp))
                Text(
                    "Review these patterns before adding them to your centre.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            items(visibleSuggestions, key = { "suggestion:${suggestionSessionKey(it)}" }) { suggestion ->
                DetectedCommitmentCard(
                    suggestion = suggestion,
                    onAccept = { onAcceptSuggestion(suggestion) },
                    onIgnore = {
                        ignoredSuggestionKeys = ignoredSuggestionKeys + suggestionSessionKey(suggestion)
                    },
                )
            }
        }
        item {
            Button(
                onClick = onAddCommitment,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
            ) {
                Icon(Icons.Rounded.Add, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Add subscription or AutoPay")
            }
        }
        item {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(CommitmentFilter.entries) { option ->
                    FilterChip(
                        selected = filter == option,
                        onClick = { filter = option },
                        label = { Text(option.label) },
                        modifier = Modifier.heightIn(min = 48.dp),
                    )
                }
            }
        }
        if (visibleCommitments.isEmpty()) {
            item {
                PaisaCard(Modifier.fillMaxWidth()) {
                    SharedFinanceEmptyState(
                        icon = Icons.Rounded.Subscriptions,
                        title = if (commitments.isEmpty()) "No commitments saved" else "No items in this view",
                        detail = if (commitments.isEmpty()) {
                            "Add a subscription or UPI AutoPay mandate to see upcoming charges and monthly impact."
                        } else {
                            "Choose another filter to see your other recurring payments."
                        },
                    )
                }
            }
        } else {
            items(visibleCommitments, key = PaymentCommitment::id) { commitment ->
                PaymentCommitmentCard(
                    commitment = commitment,
                    accountName = commitment.accountId?.let(accountNames::get),
                    onEdit = { onEditCommitment(commitment) },
                    onTogglePause = {
                        val newStatus = if (commitment.status == PaymentCommitmentStatus.ACTIVE) {
                            PaymentCommitmentStatus.PAUSED
                        } else {
                            PaymentCommitmentStatus.ACTIVE
                        }
                        onUpdateCommitment(commitment.copy(status = newStatus, updatedAt = System.currentTimeMillis()))
                    },
                    onDelete = { deleting = commitment },
                )
            }
        }
        item {
            Text(
                "Pausing an item here pauses reminders and forecasts only. Cancel the actual mandate with the merchant, bank, or UPI app.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    deleting?.let { commitment ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            icon = { Icon(Icons.Rounded.DeleteOutline, contentDescription = null) },
            title = { Text("Remove ${commitment.name}?") },
            text = { Text("This removes the PaisaLens record only. It does not cancel the real subscription or UPI mandate.") },
            confirmButton = {
                Button(
                    onClick = {
                        onDeleteCommitment(commitment)
                        deleting = null
                    },
                ) { Text("Remove") }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun CommitmentMetric(label: String, value: String, modifier: Modifier = Modifier) {
    Surface(modifier = modifier, shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun DetectedCommitmentCard(
    suggestion: PaymentCommitment,
    onAccept: () -> Unit,
    onIgnore: () -> Unit,
) {
    val dueDate = currentPaymentDueDate(suggestion, LocalDate.now())
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.58f),
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
    ) {
        Column(Modifier.padding(15.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.AutoMode, contentDescription = null, modifier = Modifier.size(28.dp))
                Column(Modifier.weight(1f).padding(horizontal = 11.dp)) {
                    Text(suggestion.name, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(
                        "About ${formatMoney(suggestion.amountMinor)} · ${suggestion.frequency.label()}",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Text(
                    dueDate.sharedFinanceDateLabel(),
                    style = MaterialTheme.typography.labelMedium,
                )
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = onIgnore,
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                ) {
                    Text("Ignore for now")
                }
                Button(
                    onClick = onAccept,
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                ) {
                    Icon(Icons.Rounded.CheckCircle, contentDescription = null)
                    Spacer(Modifier.width(7.dp))
                    Text("Review")
                }
            }
        }
    }
}

@Composable
private fun PaymentCommitmentCard(
    commitment: PaymentCommitment,
    accountName: String?,
    onEdit: () -> Unit,
    onTogglePause: () -> Unit,
    onDelete: () -> Unit,
) {
    val active = commitment.status == PaymentCommitmentStatus.ACTIVE
    val dueDate = currentPaymentDueDate(commitment, LocalDate.now())
    PaisaCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    color = if (active) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHighest,
                    contentColor = if (active) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                    shape = MaterialTheme.shapes.medium,
                ) {
                    Icon(
                        if (commitment.kind == PaymentCommitmentKind.UPI_AUTOPAY) Icons.Rounded.Payments else Icons.Rounded.Subscriptions,
                        contentDescription = null,
                        modifier = Modifier.padding(10.dp),
                    )
                }
                Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                    Text(commitment.name, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(
                        "${commitment.kind.label()} · ${commitment.status.label()}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = onEdit, modifier = Modifier.size(48.dp)) {
                    Icon(Icons.Rounded.Edit, contentDescription = "Edit ${commitment.name}")
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(formatMoney(commitment.amountMinor), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text(commitment.frequency.label(), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Event, contentDescription = null, modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.width(7.dp))
                Text(
                    "Next expected ${dueDate.sharedFinanceDateLabel()}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            accountName?.let {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.AccountBalanceWallet, contentDescription = null, modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.width(7.dp))
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (commitment.kind == PaymentCommitmentKind.UPI_AUTOPAY) {
                commitment.maxMandateMinor?.let {
                    Text("Mandate cap ${formatMoney(it)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                commitment.upiHandle?.takeIf(String::isNotBlank)?.let {
                    Text("UPI: $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            commitment.notes?.takeIf(String::isNotBlank)?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = onTogglePause,
                    enabled = commitment.status in setOf(PaymentCommitmentStatus.ACTIVE, PaymentCommitmentStatus.PAUSED),
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                ) {
                    Icon(if (active) Icons.Rounded.PauseCircle else Icons.Rounded.PlayCircle, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text(if (active) "Pause tracking" else "Resume tracking")
                }
                IconButton(onClick = onDelete, modifier = Modifier.size(48.dp)) {
                    Icon(Icons.Rounded.DeleteOutline, contentDescription = "Remove ${commitment.name}")
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PaymentCommitmentEditorSheet(
    existing: PaymentCommitment?,
    accounts: List<AccountProfile>,
    onSave: (PaymentCommitment) -> Unit,
    onDismiss: () -> Unit,
    isSaving: Boolean = false,
) {
    val currentIsSaving = rememberUpdatedState(isSaving)
    val sheetState = rememberModalBottomSheetState(
        skipPartiallyExpanded = true,
        confirmValueChange = { !currentIsSaving.value },
    )
    val today = LocalDate.now()
    val initialNextDue = remember(existing, today) {
        existing?.let { currentPaymentDueDate(it, today).toString() }
            ?: today.plusMonths(1).toString()
    }
    var name by remember(existing) { mutableStateOf(existing?.name.orEmpty()) }
    var kind by remember(existing) { mutableStateOf(existing?.kind ?: PaymentCommitmentKind.SUBSCRIPTION) }
    var amount by remember(existing) { mutableStateOf(existing?.amountMinor?.sharedFinanceInput().orEmpty()) }
    var maxMandate by remember(existing) { mutableStateOf(existing?.maxMandateMinor?.sharedFinanceInput().orEmpty()) }
    var frequency by remember(existing) { mutableStateOf(existing?.frequency ?: PaymentFrequency.MONTHLY) }
    var customDays by remember(existing) { mutableStateOf(existing?.customIntervalDays?.toString().orEmpty()) }
    var nextDue by remember(existing) { mutableStateOf(initialNextDue) }
    var accountId by remember(existing) { mutableStateOf(existing?.accountId) }
    var upiHandle by remember(existing) { mutableStateOf(existing?.upiHandle.orEmpty()) }
    var categoryLabel by remember(existing) { mutableStateOf(existing?.categoryLabel.orEmpty()) }
    var notes by remember(existing) { mutableStateOf(existing?.notes.orEmpty()) }
    var status by remember(existing) { mutableStateOf(existing?.status ?: PaymentCommitmentStatus.ACTIVE) }
    var submitted by remember { mutableStateOf(false) }
    val hasChanges = existing == null || name.trim() != existing.name ||
        amount.sharedFinanceMinorOrNull() != existing.amountMinor || kind != existing.kind ||
        frequency != existing.frequency || nextDue != initialNextDue ||
        customDays != existing.customIntervalDays?.toString().orEmpty() ||
        maxMandate.sharedFinanceMinorOrNull() != existing.maxMandateMinor ||
        accountId != existing.accountId || upiHandle.trim() != existing.upiHandle.orEmpty() ||
        categoryLabel.trim() != existing.categoryLabel.orEmpty() || notes.trim() != existing.notes.orEmpty() ||
        status != existing.status
    val parsedAmount = amount.sharedFinanceMinorOrNull()
    val parsedMaximum = maxMandate.sharedFinanceMinorOrNull()
    val parsedDate = runCatching { LocalDate.parse(nextDue) }.getOrNull()
    val parsedCustomDays = customDays.toIntOrNull()
    val error = when {
        name.isBlank() -> "Enter a merchant or service name."
        parsedAmount == null || parsedAmount <= 0 -> "Enter an expected amount greater than zero."
        parsedDate == null -> "Use a valid next payment date in YYYY-MM-DD format."
        frequency == PaymentFrequency.CUSTOM && (parsedCustomDays == null || parsedCustomDays <= 0) ->
            "Enter a custom interval of at least one day."
        kind == PaymentCommitmentKind.UPI_AUTOPAY && maxMandate.isNotBlank() && parsedMaximum == null ->
            "Enter a valid mandate cap, or leave it blank."
        kind == PaymentCommitmentKind.UPI_AUTOPAY && parsedMaximum != null && parsedMaximum < parsedAmount ->
            "The mandate cap cannot be lower than the expected payment."
        else -> null
    }

    ModalBottomSheet(
        onDismissRequest = { if (!isSaving) onDismiss() },
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.94f)
                .navigationBarsPadding(),
        ) {
            SharedFinanceSheetHeader(
                title = if (existing == null) "Add recurring payment" else "Edit recurring payment",
                subtitle = "Track expected charges privately",
                onDismiss = { if (!isSaving) onDismiss() },
            )
            LazyColumn(
                contentPadding = PaddingValues(start = 18.dp, end = 18.dp, bottom = 32.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                item {
                    SharedFinancePrivateNotice("Saving this record does not activate, modify, or cancel a real subscription or mandate.")
                }
                item {
                    Text("Payment type", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(8.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        PaymentCommitmentKind.entries.forEach { option ->
                            FilterChip(
                                selected = kind == option,
                                onClick = { kind = option },
                                label = { Text(option.label()) },
                                modifier = Modifier.heightIn(min = 48.dp).weight(1f),
                            )
                        }
                    }
                }
                item {
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it.take(72) },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Merchant or service") },
                        singleLine = true,
                        isError = submitted && name.isBlank(),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                    )
                }
                item {
                    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        OutlinedTextField(
                            value = amount,
                            onValueChange = { amount = it.sharedFinanceAmountInput() },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("Expected amount") },
                            prefix = { Text("₹") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Next),
                        )
                        if (kind == PaymentCommitmentKind.UPI_AUTOPAY) {
                            OutlinedTextField(
                                value = maxMandate,
                                onValueChange = { maxMandate = it.sharedFinanceAmountInput() },
                                modifier = Modifier.fillMaxWidth(),
                                label = { Text("Mandate cap") },
                                prefix = { Text("₹") },
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Next),
                            )
                        }
                    }
                }
                item {
                    Text("Frequency", style = MaterialTheme.typography.titleMedium)
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(PaymentFrequency.entries) { option ->
                            FilterChip(
                                selected = frequency == option,
                                onClick = { frequency = option },
                                label = { Text(option.label()) },
                                modifier = Modifier.heightIn(min = 48.dp),
                            )
                        }
                    }
                }
                if (frequency == PaymentFrequency.CUSTOM) {
                    item {
                        OutlinedTextField(
                            value = customDays,
                            onValueChange = { customDays = it.filter(Char::isDigit).take(4) },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("Repeat every (days)") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Next),
                        )
                    }
                }
                item {
                    OutlinedTextField(
                        value = nextDue,
                        onValueChange = { nextDue = it.take(10) },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Next expected payment") },
                        placeholder = { Text("YYYY-MM-DD") },
                        singleLine = true,
                        isError = submitted && parsedDate == null,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text, imeAction = ImeAction.Next),
                    )
                }
                item {
                    Text("Payment account (optional)", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(8.dp))
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        item {
                            FilterChip(
                                selected = accountId == null,
                                onClick = { accountId = null },
                                label = { Text("Not set") },
                                modifier = Modifier.heightIn(min = 48.dp),
                            )
                        }
                        items(accounts, key = AccountProfile::id) { account ->
                            FilterChip(
                                selected = accountId == account.id,
                                onClick = { accountId = account.id },
                                label = { Text(account.name) },
                                modifier = Modifier.heightIn(min = 48.dp),
                            )
                        }
                    }
                }
                if (kind == PaymentCommitmentKind.UPI_AUTOPAY) {
                    item {
                        OutlinedTextField(
                            value = upiHandle,
                            onValueChange = { upiHandle = it.trim().take(80) },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("UPI handle (optional)") },
                            placeholder = { Text("merchant@bank") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                        )
                    }
                }
                item {
                    OutlinedTextField(
                        value = categoryLabel,
                        onValueChange = { categoryLabel = it.take(48) },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Category (optional)") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                    )
                }
                item {
                    OutlinedTextField(
                        value = notes,
                        onValueChange = { notes = it.take(240) },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Notes (optional)") },
                        minLines = 2,
                        maxLines = 4,
                    )
                }
                if (existing != null) {
                    item {
                        Text("Tracking status", style = MaterialTheme.typography.titleMedium)
                        LazyRow(
                            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            items(PaymentCommitmentStatus.entries) { option ->
                                FilterChip(
                                    selected = status == option,
                                    onClick = { status = option },
                                    label = { Text(option.label()) },
                                    modifier = Modifier.heightIn(min = 48.dp),
                                )
                            }
                        }
                        Text(
                            "This changes PaisaLens tracking only; it does not change the real subscription or mandate.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                existing?.let { saved ->
                    item {
                        Text(
                            if (saved.source == PaymentCommitmentSource.ON_DEVICE_SUGGESTION) {
                                "Originally detected from recurring on-device transaction patterns."
                            } else {
                                "Added manually."
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                if (submitted && error != null) {
                    item { Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
                }
                item {
                    Button(
                        onClick = {
                            submitted = true
                            if (error == null) {
                                val now = System.currentTimeMillis()
                                onSave(
                                    PaymentCommitment(
                                        id = existing?.id ?: 0,
                                        name = name.trim(),
                                        merchantKey = if (
                                            existing != null && name.trim().equals(existing.name, ignoreCase = true)
                                        ) {
                                            existing.merchantKey.takeIf(String::isNotBlank) ?: normalizedMerchantKey(name)
                                        } else {
                                            normalizedMerchantKey(name)
                                        },
                                        kind = kind,
                                        frequency = frequency,
                                        customIntervalDays = parsedCustomDays.takeIf { frequency == PaymentFrequency.CUSTOM },
                                        amountMinor = parsedAmount!!,
                                        maxMandateMinor = parsedMaximum.takeIf { kind == PaymentCommitmentKind.UPI_AUTOPAY },
                                        nextDueEpochDay = if (
                                            existing != null &&
                                            nextDue == initialNextDue &&
                                            frequency == existing.frequency &&
                                            parsedCustomDays == existing.customIntervalDays
                                        ) {
                                            existing.nextDueEpochDay
                                        } else {
                                            parsedDate!!.toEpochDay()
                                        },
                                        accountId = accountId,
                                        upiHandle = upiHandle.trim().takeIf { kind == PaymentCommitmentKind.UPI_AUTOPAY && it.isNotBlank() },
                                        status = status,
                                        source = existing?.source ?: PaymentCommitmentSource.MANUAL,
                                        categoryLabel = categoryLabel.trim().takeIf(String::isNotBlank),
                                        notes = notes.trim().takeIf(String::isNotBlank),
                                        createdAt = existing?.createdAt ?: now,
                                        updatedAt = now,
                                    ),
                                )
                            }
                        },
                        enabled = !isSaving,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                    ) {
                        Text(
                            if (isSaving) "Saving…"
                            else if (existing == null) "Add recurring payment"
                            else "Save changes",
                        )
                    }
                }
                if (hasChanges) {
                    item {
                        Text(
                            "Unsaved changes are discarded if you swipe this sheet away.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

private fun monthlyEquivalentMinor(commitment: PaymentCommitment): Long = when (commitment.frequency) {
    PaymentFrequency.WEEKLY -> commitment.amountMinor * 52 / 12
    PaymentFrequency.MONTHLY -> commitment.amountMinor
    PaymentFrequency.QUARTERLY -> commitment.amountMinor / 3
    PaymentFrequency.YEARLY -> commitment.amountMinor / 12
    PaymentFrequency.CUSTOM -> {
        val days = commitment.customIntervalDays?.coerceAtLeast(1) ?: 30
        (commitment.amountMinor * 30.4375 / days).roundToLong()
    }
}

private fun suggestionSessionKey(commitment: PaymentCommitment): String = buildString {
    append(normalizedMerchantKey(commitment.merchantKey.ifBlank { commitment.name }))
    append(':')
    append(commitment.accountId ?: "unscoped")
    append(':')
    append(commitment.kind.name)
}

private fun PaymentCommitmentKind.label(): String = when (this) {
    PaymentCommitmentKind.SUBSCRIPTION -> "Subscription"
    PaymentCommitmentKind.UPI_AUTOPAY -> "UPI AutoPay"
}

private fun PaymentFrequency.label(): String = when (this) {
    PaymentFrequency.WEEKLY -> "Weekly"
    PaymentFrequency.MONTHLY -> "Monthly"
    PaymentFrequency.QUARTERLY -> "Quarterly"
    PaymentFrequency.YEARLY -> "Yearly"
    PaymentFrequency.CUSTOM -> "Custom"
}

private fun PaymentCommitmentStatus.label(): String = when (this) {
    PaymentCommitmentStatus.ACTIVE -> "Active"
    PaymentCommitmentStatus.PAUSED -> "Paused"
    PaymentCommitmentStatus.CANCELLED -> "Cancelled"
    PaymentCommitmentStatus.EXPIRED -> "Expired"
}
