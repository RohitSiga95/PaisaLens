package com.paisalens.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.AccountBalance
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.CreditCard
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Merge
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.paisalens.app.data.model.AccountProfile
import com.paisalens.app.data.model.AccountType

internal data class AccountMergeDraft(
    val sourceAccountIds: List<Long>,
    val mergedName: String,
    val type: AccountType,
) {
    init {
        require(sourceAccountIds.size >= 2) { "At least two sources are required." }
        require(sourceAccountIds.distinct().size == sourceAccountIds.size) { "Sources must be unique." }
        require(mergedName.isNotBlank()) { "A merged name is required." }
        require(type == AccountType.BANK_ACCOUNT || type == AccountType.CREDIT_CARD) {
            "Only bank accounts or credit cards can be merged."
        }
    }
}

private enum class AccountMergeStep {
    SELECT,
    CONFIRM,
}

private val mergeableAccountTypes = listOf(
    AccountType.BANK_ACCOUNT,
    AccountType.CREDIT_CARD,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AccountMergeSheet(
    accounts: List<AccountProfile>,
    isSaving: Boolean,
    errorMessage: String?,
    onConfirm: (AccountMergeDraft) -> Unit,
    onDismiss: () -> Unit,
) {
    val mergeableAccounts = remember(accounts) {
        accounts.filter { it.type in mergeableAccountTypes }
    }
    var selectedType by rememberSaveable {
        mutableStateOf(
            mergeableAccountTypes.firstOrNull { type -> mergeableAccounts.any { it.type == type } }
                ?: AccountType.BANK_ACCOUNT,
        )
    }
    var selectedIds by rememberSaveable { mutableStateOf<List<Long>>(emptyList()) }
    var mergedName by rememberSaveable { mutableStateOf("") }
    var step by rememberSaveable { mutableStateOf(AccountMergeStep.SELECT) }

    val visibleAccounts = mergeableAccounts.filter { it.type == selectedType }
    val selectedAccounts = visibleAccounts.filter { it.id in selectedIds }
    val canReview = selectedAccounts.size >= 2 && mergedName.isNotBlank()
    val savingState by rememberUpdatedState(isSaving)
    val sheetState = rememberModalBottomSheetState(
        skipPartiallyExpanded = true,
        confirmValueChange = { !savingState },
    )

    BackHandler(enabled = isSaving || step == AccountMergeStep.CONFIRM) {
        if (!isSaving) step = AccountMergeStep.SELECT
    }

    ModalBottomSheet(
        onDismissRequest = { if (!isSaving) onDismiss() },
        sheetState = sheetState,
        sheetGesturesEnabled = !isSaving,
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.94f)
                .navigationBarsPadding()
                .imePadding(),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item(key = "merge-header") {
                AccountMergeHeader(
                    step = step,
                    isSaving = isSaving,
                    onDismiss = onDismiss,
                )
            }

            if (step == AccountMergeStep.SELECT) {
                item(key = "merge-type-heading") {
                    SectionHeading("Choose what to merge")
                }
                items(mergeableAccountTypes, key = { "merge-type-${it.name}" }) { type ->
                    AccountTypeChoice(
                        type = type,
                        selected = selectedType == type,
                        sourceCount = mergeableAccounts.count { it.type == type },
                        enabled = !isSaving && selectedAccounts.isEmpty(),
                        onSelect = {
                            selectedType = type
                            selectedIds = emptyList()
                        },
                    )
                }
                if (selectedAccounts.isNotEmpty()) {
                    item(key = "merge-type-lock") {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = "Type is locked so accounts and cards cannot be mixed.",
                                modifier = Modifier.weight(1f),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            TextButton(
                                onClick = { selectedIds = emptyList() },
                                enabled = !isSaving,
                                modifier = Modifier.heightIn(min = 48.dp),
                            ) {
                                Text("Clear selection")
                            }
                        }
                    }
                }

                item(key = "merge-sources-heading") {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        SectionHeading(
                            if (selectedType == AccountType.CREDIT_CARD) {
                                "Select credit cards"
                            } else {
                                "Select bank accounts"
                            },
                        )
                        Text(
                            text = when {
                                visibleAccounts.isEmpty() -> "No ${selectedType.pluralLabel()} are available."
                                selectedAccounts.size < 2 -> {
                                    val remaining = 2 - selectedAccounts.size
                                    "Select $remaining more to continue."
                                }
                                else -> "${selectedAccounts.size} selected"
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (selectedAccounts.size >= 2) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                        )
                    }
                }

                if (visibleAccounts.isEmpty()) {
                    item(key = "merge-sources-empty") {
                        MergeEmptyState(selectedType)
                    }
                } else {
                    items(visibleAccounts, key = { "merge-source-${it.id}" }) { account ->
                        SelectableMergeSource(
                            account = account,
                            selected = account.id in selectedIds,
                            enabled = !isSaving,
                            onSelectedChange = { selected ->
                                selectedIds = if (selected) {
                                    (selectedIds + account.id).distinct()
                                } else {
                                    selectedIds.filterNot { it == account.id }
                                }
                            },
                        )
                    }
                }

                item(key = "merge-name") {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        SectionHeading("Name the merged ${selectedType.singularLabel()}")
                        OutlinedTextField(
                            value = mergedName,
                            onValueChange = { mergedName = it.take(48) },
                            modifier = Modifier.fillMaxWidth(),
                            enabled = !isSaving,
                            label = { Text("Merged name") },
                            placeholder = {
                                Text(
                                    if (selectedType == AccountType.CREDIT_CARD) {
                                        "My credit cards"
                                    } else {
                                        "Everyday accounts"
                                    },
                                )
                            },
                            supportingText = {
                                Text("This name will represent every selected source across PaisaLens.")
                            },
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                            singleLine = true,
                            shape = MaterialTheme.shapes.medium,
                        )
                    }
                }

                item(key = "merge-retention-summary") {
                    MergeRetentionNotice(
                        mergedName = mergedName.trim(),
                        selectedCount = selectedAccounts.size,
                    )
                }

                errorMessage?.takeIf(String::isNotBlank)?.let { message ->
                    item(key = "merge-error") {
                        AccountMergeError(message)
                    }
                }

                item(key = "merge-review-action") {
                    Button(
                        onClick = { step = AccountMergeStep.CONFIRM },
                        enabled = canReview && !isSaving,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp),
                        shape = MaterialTheme.shapes.medium,
                    ) {
                        Text("Review merge")
                    }
                }
            } else {
                item(key = "merge-confirm-heading") {
                    SectionHeading("Review your merge")
                }

                item(key = "merge-confirm-summary") {
                    MergeConfirmationSummary(
                        mergedName = mergedName.trim(),
                        type = selectedType,
                        sourceCount = selectedAccounts.size,
                    )
                }

                item(key = "merge-confirm-sources-heading") {
                    SectionHeading("Sources that will appear together")
                }
                items(selectedAccounts, key = { "merge-confirm-source-${it.id}" }) { account ->
                    MergeSourceSummary(account)
                }

                item(key = "merge-confirm-retention") {
                    MergeRetentionNotice(
                        mergedName = mergedName.trim(),
                        selectedCount = selectedAccounts.size,
                    )
                }

                errorMessage?.takeIf(String::isNotBlank)?.let { message ->
                    item(key = "merge-confirm-error") {
                        AccountMergeError(message)
                    }
                }

                item(key = "merge-confirm-actions") {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = {
                                onConfirm(
                                    AccountMergeDraft(
                                        sourceAccountIds = selectedAccounts.map(AccountProfile::id),
                                        mergedName = mergedName.trim(),
                                        type = selectedType,
                                    ),
                                )
                            },
                            enabled = canReview && !isSaving,
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 48.dp),
                            shape = MaterialTheme.shapes.medium,
                        ) {
                            if (isSaving) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(20.dp),
                                    color = MaterialTheme.colorScheme.onPrimary,
                                    strokeWidth = 2.dp,
                                )
                                Spacer(Modifier.width(8.dp))
                                Text("Merging…")
                            } else {
                                Icon(Icons.Rounded.Merge, contentDescription = null)
                                Spacer(Modifier.width(8.dp))
                                Text("Merge ${selectedAccounts.size} ${selectedType.pluralLabel()}")
                            }
                        }
                        OutlinedButton(
                            onClick = { step = AccountMergeStep.SELECT },
                            enabled = !isSaving,
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 48.dp),
                            shape = MaterialTheme.shapes.medium,
                        ) {
                            Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text("Back and edit")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AccountMergeHeader(
    step: AccountMergeStep,
    isSaving: Boolean,
    onDismiss: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top,
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(top = 4.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = "Merge accounts or cards",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.semantics { heading() },
            )
            Text(
                text = if (step == AccountMergeStep.SELECT) "Step 1 of 2 · Select and name" else "Step 2 of 2 · Confirm",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        IconButton(
            onClick = onDismiss,
            enabled = !isSaving,
            modifier = Modifier.size(48.dp),
        ) {
            Icon(
                Icons.Rounded.Close,
                contentDescription = if (isSaving) "Cannot close while merge is saving" else "Close merge accounts or cards",
            )
        }
    }
}

@Composable
private fun SectionHeading(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.semantics { heading() },
    )
}

@Composable
private fun AccountTypeChoice(
    type: AccountType,
    selected: Boolean,
    sourceCount: Int,
    enabled: Boolean,
    onSelect: () -> Unit,
) {
    val typeLabel = if (type == AccountType.CREDIT_CARD) "Credit cards" else "Bank accounts"
    Surface(
        color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainer,
        contentColor = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
        shape = MaterialTheme.shapes.medium,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp)
                .toggleable(
                    value = selected,
                    enabled = enabled,
                    role = Role.RadioButton,
                    onValueChange = { shouldSelect -> if (shouldSelect) onSelect() },
                )
                .semantics {
                    stateDescription = when {
                        selected && !enabled -> "Selected and locked while sources are selected"
                        selected -> "Selected"
                        else -> "Not selected"
                    }
                }
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RadioButton(
                selected = selected,
                onClick = null,
                enabled = enabled,
            )
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text(typeLabel, style = MaterialTheme.typography.titleSmall)
                Text(
                    text = "$sourceCount available",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (selected) {
                        MaterialTheme.colorScheme.onPrimaryContainer
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
        }
    }
}

@Composable
private fun SelectableMergeSource(
    account: AccountProfile,
    selected: Boolean,
    enabled: Boolean,
    onSelectedChange: (Boolean) -> Unit,
) {
    Surface(
        color = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerLow,
        contentColor = if (selected) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurface,
        shape = MaterialTheme.shapes.medium,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 72.dp)
                .toggleable(
                    value = selected,
                    enabled = enabled,
                    role = Role.Checkbox,
                    onValueChange = onSelectedChange,
                )
                .semantics {
                    stateDescription = if (selected) "Selected for merge" else "Not selected for merge"
                }
                .padding(start = 12.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            MergeSourceIcon(account.type)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = account.name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Medium,
                )
                Text(
                    text = account.mergeSourceSubtitle(),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (selected) {
                        MaterialTheme.colorScheme.onSecondaryContainer
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
            Checkbox(
                checked = selected,
                onCheckedChange = null,
                enabled = enabled,
            )
        }
    }
}

@Composable
private fun MergeSourceSummary(account: AccountProfile) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = MaterialTheme.shapes.medium,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 64.dp)
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            MergeSourceIcon(account.type)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(account.name, style = MaterialTheme.typography.titleMedium)
                Text(
                    account.mergeSourceSubtitle(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun MergeSourceIcon(type: AccountType) {
    val icon: ImageVector = if (type == AccountType.CREDIT_CARD) Icons.Rounded.CreditCard else Icons.Rounded.AccountBalance
    Box(
        modifier = Modifier
            .size(40.dp)
            .background(MaterialTheme.colorScheme.tertiaryContainer, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onTertiaryContainer,
        )
    }
}

@Composable
private fun MergeConfirmationSummary(
    mergedName: String,
    type: AccountType,
    sourceCount: Int,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        shape = MaterialTheme.shapes.large,
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = mergedName,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
            )
            ConfirmationLine("Type", type.label)
            ConfirmationLine("Sources", "$sourceCount ${type.pluralLabel()}")
            Text(
                text = "This grouping cannot currently be undone. The original source identities remain protected for future SMS matching.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
        }
    }
}

@Composable
private fun ConfirmationLine(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            text = label,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(
            text = value,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
private fun MergeRetentionNotice(
    mergedName: String,
    selectedCount: Int,
) {
    val destination = mergedName.takeIf(String::isNotBlank)?.let { " under “$it”" }.orEmpty()
    val sourceDescription = if (selectedCount >= 2) " from all $selectedCount selected sources" else ""
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.tertiaryContainer,
        contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
        shape = MaterialTheme.shapes.large,
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Icon(Icons.Rounded.History, contentDescription = null)
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = "Your financial history is retained",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = "Nothing is deleted. All transactions, balances, and bills$sourceDescription remain and appear together$destination.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}

@Composable
private fun AccountMergeError(message: String) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .semantics { liveRegion = LiveRegionMode.Assertive },
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
        shape = MaterialTheme.shapes.medium,
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Icon(Icons.Rounded.ErrorOutline, contentDescription = null)
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = "Merge could not be completed",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(message, style = MaterialTheme.typography.bodyMedium)
                Text(
                    text = "Try again, or go back and review your choices.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

@Composable
private fun MergeEmptyState(type: AccountType) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = MaterialTheme.shapes.medium,
    ) {
        Text(
            text = "Add or detect at least two ${type.pluralLabel()} before creating a merge.",
            modifier = Modifier.padding(16.dp),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun AccountProfile.mergeSourceSubtitle(): String {
    val institutionLabel = institution?.trim()?.takeIf(String::isNotBlank) ?: "Institution unknown"
    val hintLabel = accountHint
        ?.filter(Char::isDigit)
        ?.takeLast(4)
        ?.takeIf(String::isNotBlank)
        ?.let { "•••• $it" }
        ?: "No last 4 digits"
    return buildList {
        add(institutionLabel)
        add(type.label)
        add(hintLabel)
        if (mergedMemberCount > 1) add("$mergedMemberCount merged sources")
    }.joinToString(" · ")
}

private fun AccountType.singularLabel(): String = when (this) {
    AccountType.BANK_ACCOUNT -> "account"
    AccountType.CREDIT_CARD -> "card"
    else -> "source"
}

private fun AccountType.pluralLabel(): String = when (this) {
    AccountType.BANK_ACCOUNT -> "bank accounts"
    AccountType.CREDIT_CARD -> "credit cards"
    else -> "sources"
}
