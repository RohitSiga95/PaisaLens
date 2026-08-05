package com.paisalens.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AccountBalanceWallet
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Category
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.paisalens.app.data.model.AccountProfile
import com.paisalens.app.data.model.AccountType
import com.paisalens.app.data.model.CustomCategory

internal enum class BackupAction {
    CREATE,
    RESTORE,
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AccountManagerSheet(
    accounts: List<AccountProfile>,
    onAdd: (String, AccountType, String?) -> Unit,
    onUpdate: (AccountProfile) -> Unit,
    onDelete: (Long) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var hint by remember { mutableStateOf("") }
    var type by remember { mutableStateOf(AccountType.BANK_ACCOUNT) }
    var editing by remember { mutableStateOf<AccountProfile?>(null) }
    var deleting by remember { mutableStateOf<AccountProfile?>(null) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.92f)
                .navigationBarsPadding(),
        ) {
            SheetHeader("Accounts & cards", "Organize spending and keep transfers out of expenses.", onDismiss)
            Column(
                modifier = Modifier.padding(horizontal = 20.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.take(48) },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Account name") },
                    placeholder = { Text("Everyday account") },
                    singleLine = true,
                    shape = MaterialTheme.shapes.medium,
                )
                OutlinedTextField(
                    value = hint,
                    onValueChange = { hint = it.filter(Char::isDigit).take(4) },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Last 4 digits (optional)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
                    singleLine = true,
                    shape = MaterialTheme.shapes.medium,
                )
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(AccountType.entries) { option ->
                        FilterChip(
                            selected = type == option,
                            onClick = { type = option },
                            label = { Text(option.label) },
                        )
                    }
                }
                Button(
                    onClick = {
                        onAdd(name, type, hint.takeIf(String::isNotBlank))
                        name = ""
                        hint = ""
                    },
                    enabled = name.isNotBlank(),
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                    shape = MaterialTheme.shapes.medium,
                ) {
                    Icon(Icons.Rounded.Add, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Add account")
                }
            }
            Text(
                "Detected and added accounts",
                modifier = Modifier.padding(start = 22.dp, top = 20.dp, bottom = 8.dp),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (accounts.isEmpty()) {
                Text(
                    "Accounts with recognizable last-four digits will also appear automatically after an SMS scan.",
                    modifier = Modifier.padding(horizontal = 22.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                LazyColumn(contentPadding = PaddingValues(horizontal = 20.dp, vertical = 4.dp)) {
                    items(accounts, key = { it.id }) { account ->
                        Surface(color = Color.Transparent, shape = MaterialTheme.shapes.medium) {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                ManagementIcon(Icons.Rounded.AccountBalanceWallet)
                                Spacer(Modifier.width(12.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(account.name, style = MaterialTheme.typography.titleMedium)
                                    Text(
                                        account.type.label + (account.accountHint?.let { " · •$it" } ?: ""),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                IconButton(onClick = { editing = account }) {
                                    Icon(Icons.Rounded.Edit, contentDescription = "Edit ${account.name}")
                                }
                                IconButton(onClick = { deleting = account }) {
                                    Icon(
                                        Icons.Rounded.DeleteOutline,
                                        contentDescription = "Delete ${account.name}",
                                        tint = MaterialTheme.colorScheme.error,
                                    )
                                }
                            }
                        }
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    }
                }
            }
        }
    }

    editing?.let { account ->
        AccountEditDialog(
            account = account,
            onDismiss = { editing = null },
            onSave = {
                onUpdate(it)
                editing = null
            },
        )
    }
    deleting?.let { account ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Remove ${account.name}?") },
            text = { Text("Transactions will be kept, but they will no longer be linked to this account.") },
            confirmButton = {
                TextButton(onClick = {
                    onDelete(account.id)
                    deleting = null
                }) { Text("Remove", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun AccountEditDialog(
    account: AccountProfile,
    onDismiss: () -> Unit,
    onSave: (AccountProfile) -> Unit,
) {
    var name by remember(account.id) { mutableStateOf(account.name) }
    var hint by remember(account.id) { mutableStateOf(account.accountHint.orEmpty()) }
    var type by remember(account.id) { mutableStateOf(account.type) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Edit account") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.take(48) },
                    label = { Text("Account name") },
                    singleLine = true,
                )
                OutlinedTextField(
                    value = hint,
                    onValueChange = { hint = it.filter(Char::isDigit).take(4) },
                    label = { Text("Last 4 digits") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(AccountType.entries) { option ->
                        FilterChip(
                            selected = type == option,
                            onClick = { type = option },
                            label = { Text(option.label) },
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSave(account.copy(name = name.trim(), type = type, accountHint = hint.takeIf(String::isNotBlank))) },
                enabled = name.isNotBlank(),
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CustomCategoryManagerSheet(
    categories: List<CustomCategory>,
    onAdd: (String, String) -> Unit,
    onDelete: (Long) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var colorHex by remember { mutableStateOf(CATEGORY_COLORS.first()) }
    var deleting by remember { mutableStateOf<CustomCategory?>(null) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.88f)
                .navigationBarsPadding(),
        ) {
            SheetHeader("Custom categories", "Create categories that match your life.", onDismiss)
            Column(
                modifier = Modifier.padding(horizontal = 20.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.take(32) },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Category name") },
                    placeholder = { Text("Pet care") },
                    singleLine = true,
                    shape = MaterialTheme.shapes.medium,
                )
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(CATEGORY_COLORS) { option ->
                        FilterChip(
                            selected = colorHex == option,
                            onClick = { colorHex = option },
                            leadingIcon = {
                                Box(
                                    Modifier
                                        .size(18.dp)
                                        .background(Color(android.graphics.Color.parseColor(option)), CircleShape),
                                )
                            },
                            label = { Text(option) },
                        )
                    }
                }
                Button(
                    onClick = {
                        onAdd(name, colorHex)
                        name = ""
                    },
                    enabled = name.isNotBlank(),
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                    shape = MaterialTheme.shapes.medium,
                ) {
                    Icon(Icons.Rounded.Add, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Add category")
                }
            }
            Text(
                "Your categories",
                modifier = Modifier.padding(start = 22.dp, top = 20.dp, bottom = 8.dp),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (categories.isEmpty()) {
                Text(
                    "Custom categories will appear beside the built-in choices when editing transactions.",
                    modifier = Modifier.padding(horizontal = 22.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                LazyColumn(contentPadding = PaddingValues(horizontal = 20.dp)) {
                    items(categories, key = { it.id }) { category ->
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Box(
                                Modifier
                                    .size(42.dp)
                                    .background(Color(android.graphics.Color.parseColor(category.colorHex)), CircleShape),
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(Icons.Rounded.Category, contentDescription = null, tint = Color.White)
                            }
                            Spacer(Modifier.width(12.dp))
                            Text(category.name, modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                            IconButton(onClick = { deleting = category }) {
                                Icon(
                                    Icons.Rounded.DeleteOutline,
                                    contentDescription = "Delete ${category.name}",
                                    tint = MaterialTheme.colorScheme.error,
                                )
                            }
                        }
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    }
                }
            }
        }
    }
    deleting?.let { category ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Delete ${category.name}?") },
            text = { Text("Existing transactions will move to Other. The transactions themselves will not be deleted.") },
            confirmButton = {
                TextButton(onClick = {
                    onDelete(category.id)
                    deleting = null
                }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancel") } },
        )
    }
}

@Composable
internal fun BackupPassphraseDialog(
    action: BackupAction,
    onDismiss: () -> Unit,
    onSubmit: (CharArray) -> Unit,
) {
    var passphrase by remember { mutableStateOf("") }
    var confirmation by remember { mutableStateOf("") }
    var visible by remember { mutableStateOf(false) }
    val creating = action == BackupAction.CREATE
    val valid = passphrase.length >= 8 && (!creating || passphrase == confirmation)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (creating) "Create encrypted backup" else "Restore encrypted backup") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    if (creating) {
                        "Choose a password you will remember. It is required to restore this backup on any phone."
                    } else {
                        "Restoring replaces the current local ledger, accounts, categories and budgets. Enter the backup password."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                )
                OutlinedTextField(
                    value = passphrase,
                    onValueChange = { passphrase = it.take(64) },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Backup password") },
                    supportingText = { Text("At least 8 characters") },
                    visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = {
                        IconButton(onClick = { visible = !visible }) {
                            Icon(
                                if (visible) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility,
                                contentDescription = if (visible) "Hide password" else "Show password",
                            )
                        }
                    },
                    singleLine = true,
                )
                if (creating) {
                    OutlinedTextField(
                        value = confirmation,
                        onValueChange = { confirmation = it.take(64) },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Confirm password") },
                        isError = confirmation.isNotEmpty() && confirmation != passphrase,
                        supportingText = {
                            if (confirmation.isNotEmpty() && confirmation != passphrase) Text("Passwords do not match")
                        },
                        visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
                        singleLine = true,
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onSubmit(passphrase.toCharArray()) },
                enabled = valid,
            ) { Text(if (creating) "Choose location" else "Choose backup") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun SheetHeader(title: String, subtitle: String, onDismiss: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, bottom = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        IconButton(onClick = onDismiss) { Icon(Icons.Rounded.Close, contentDescription = "Close") }
    }
}

@Composable
private fun ManagementIcon(icon: androidx.compose.ui.graphics.vector.ImageVector) {
    Box(
        modifier = Modifier.size(42.dp).background(MaterialTheme.colorScheme.primaryContainer, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimaryContainer)
    }
}

private val CATEGORY_COLORS = listOf("#7784FF", "#21D19F", "#FF8A65", "#B48CFF", "#5EB7FF", "#FFC857", "#FF72AE")
