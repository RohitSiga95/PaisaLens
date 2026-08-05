package com.paisalens.app.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.DonutLarge
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.ReceiptLong
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.paisalens.app.data.model.ExpenseCategory
import com.paisalens.app.data.model.MerchantTransactionGroup
import com.paisalens.app.data.model.TransactionRecord
import com.paisalens.app.data.model.TransactionType
import com.paisalens.app.data.model.findUncategorizedMerchantGroups
import com.paisalens.app.data.model.normalizedMerchantKey
import com.paisalens.app.ui.components.CategoryIcon
import com.paisalens.app.ui.components.MoneyText
import com.paisalens.app.ui.components.formatTransactionTime
import com.paisalens.app.ui.screens.BudgetsScreen
import com.paisalens.app.ui.screens.HomeScreen
import com.paisalens.app.ui.screens.OnboardingScreen
import com.paisalens.app.ui.screens.SettingsScreen
import com.paisalens.app.ui.screens.TransactionsScreen
import com.paisalens.app.ui.theme.PaisaLensTheme
import java.math.BigDecimal
import java.math.RoundingMode

private enum class AppDestination(
    val label: String,
    val icon: ImageVector,
) {
    HOME("Home", Icons.Rounded.Home),
    ACTIVITY("Activity", Icons.Rounded.ReceiptLong),
    BUDGETS("Budgets", Icons.Rounded.DonutLarge),
    SETTINGS("Settings", Icons.Rounded.Settings),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PaisaLensApp(
    viewModel: PaisaLensViewModel,
    hasSmsPermission: Boolean,
    onRequestSmsPermission: () -> Unit,
    onExportData: () -> Unit,
) {
    val transactions by viewModel.transactions.collectAsStateWithLifecycle()
    val budgets by viewModel.budgets.collectAsStateWithLifecycle()
    val categorizedMerchantKeys by viewModel.categorizedMerchantKeys.collectAsStateWithLifecycle()
    val isScanning by viewModel.isScanning.collectAsStateWithLifecycle()
    val onboardingComplete by viewModel.onboardingComplete.collectAsStateWithLifecycle()
    val darkMode by viewModel.darkMode.collectAsStateWithLifecycle()
    val lastScanAt by viewModel.lastScanAt.collectAsStateWithLifecycle()
    var destination by remember { mutableStateOf(AppDestination.HOME) }
    var showManualSheet by remember { mutableStateOf(false) }
    var selectedTransaction by remember { mutableStateOf<TransactionRecord?>(null) }
    var selectedMerchantGroup by remember { mutableStateOf<MerchantTransactionGroup?>(null) }
    val uncategorizedMerchants = remember(transactions, categorizedMerchantKeys) {
        findUncategorizedMerchantGroups(transactions, categorizedMerchantKeys)
    }
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current

    LaunchedEffect(viewModel) {
        viewModel.events.collect { message ->
            snackbarHostState.showSnackbar(message)
        }
    }

    PaisaLensTheme(darkTheme = darkMode) {
        if (!onboardingComplete) {
            OnboardingScreen(
                onAllowSms = {
                    viewModel.completeOnboarding()
                    onRequestSmsPermission()
                },
                onManualOnly = viewModel::completeOnboarding,
            )
            return@PaisaLensTheme
        }

        Scaffold(
            modifier = Modifier.fillMaxSize(),
            contentWindowInsets = androidx.compose.foundation.layout.WindowInsets(0, 0, 0, 0),
            snackbarHost = { SnackbarHost(snackbarHostState) },
            bottomBar = {
                NavigationBar(
                    containerColor = MaterialTheme.colorScheme.surface,
                    tonalElevation = 0.dp,
                ) {
                    AppDestination.entries.forEach { item ->
                        NavigationBarItem(
                            selected = destination == item,
                            onClick = { destination = item },
                            icon = {
                                Icon(item.icon, contentDescription = item.label)
                            },
                            label = { Text(item.label) },
                            colors = NavigationBarItemDefaults.colors(
                                indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                            ),
                        )
                    }
                }
            },
            floatingActionButton = {
                if (destination == AppDestination.ACTIVITY) {
                    FloatingActionButton(
                        onClick = { showManualSheet = true },
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary,
                    ) {
                        Icon(Icons.Rounded.Add, contentDescription = "Add transaction")
                    }
                }
            },
        ) { innerPadding ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .statusBarsPadding(),
            ) {
                AnimatedContent(
                    targetState = destination,
                    transitionSpec = {
                        val forward = targetState.ordinal > initialState.ordinal
                        val direction = if (forward) 1 else -1
                        (fadeIn(tween(180)) + slideInHorizontally(tween(260)) { it / 10 * direction })
                            .togetherWith(
                                fadeOut(tween(110)) +
                                    slideOutHorizontally(tween(180)) { -it / 14 * direction },
                            )
                    },
                    label = "mainNavigation",
                ) { screen ->
                    when (screen) {
                        AppDestination.HOME -> HomeScreen(
                            transactions = transactions,
                            budgets = budgets,
                            isScanning = isScanning,
                            hasSmsPermission = hasSmsPermission,
                            onScan = { viewModel.scanSms(context) },
                            onRequestPermission = onRequestSmsPermission,
                            onAdd = { showManualSheet = true },
                            onSeeAll = { destination = AppDestination.ACTIVITY },
                            onTransactionClick = { selectedTransaction = it },
                            uncategorizedMerchants = uncategorizedMerchants,
                            onCategorizeMerchant = { selectedMerchantGroup = it },
                        )
                        AppDestination.ACTIVITY -> TransactionsScreen(
                            transactions = transactions,
                            onTransactionClick = { selectedTransaction = it },
                        )
                        AppDestination.BUDGETS -> BudgetsScreen(
                            transactions = transactions,
                            budgets = budgets,
                            onSetBudget = viewModel::setBudget,
                        )
                        AppDestination.SETTINGS -> SettingsScreen(
                            darkMode = darkMode,
                            hasSmsPermission = hasSmsPermission,
                            isScanning = isScanning,
                            lastScanAt = lastScanAt,
                            onDarkModeChange = viewModel::setDarkMode,
                            onRequestSms = onRequestSmsPermission,
                            onScan = { viewModel.scanSms(context) },
                            transactionCount = transactions.size,
                            onExportData = onExportData,
                            onClearAll = viewModel::clearAll,
                        )
                    }
                }
            }
        }

        if (showManualSheet) {
            ManualTransactionSheet(
                onDismiss = { showManualSheet = false },
                onSave = { amount, merchant, category, type, note ->
                    viewModel.addManual(amount, merchant, category, type, note)
                    showManualSheet = false
                },
            )
        }

        selectedMerchantGroup?.let { group ->
            MerchantCategorySheet(
                group = group,
                onDismiss = { selectedMerchantGroup = null },
                onCategoryChange = { category ->
                    viewModel.updateMerchantCategory(group.merchant, category)
                    selectedMerchantGroup = null
                },
            )
        }

        selectedTransaction?.let { transaction ->
            TransactionDetailSheet(
                transaction = transaction,
                matchingMerchantCount = transactions.count {
                    it.type == TransactionType.EXPENSE &&
                        normalizedMerchantKey(it.merchant) == normalizedMerchantKey(transaction.merchant)
                },
                onDismiss = { selectedTransaction = null },
                onCategoryChange = { category ->
                    viewModel.updateCategory(transaction, category)
                    selectedTransaction = null
                },
                onNoteSave = { note -> viewModel.updateNote(transaction.id, note) },
                onDelete = {
                    viewModel.deleteTransaction(transaction.id)
                    selectedTransaction = null
                },
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ManualTransactionSheet(
    onDismiss: () -> Unit,
    onSave: (Long, String, ExpenseCategory, TransactionType, String?) -> Unit,
) {
    var amount by remember { mutableStateOf("") }
    var merchant by remember { mutableStateOf("") }
    var type by remember { mutableStateOf(TransactionType.EXPENSE) }
    var category by remember { mutableStateOf(ExpenseCategory.OTHER) }
    var note by remember { mutableStateOf("") }
    val parsedAmount = amount.toBigDecimalOrNull()
        ?.multiply(BigDecimal(100))
        ?.setScale(0, RoundingMode.HALF_UP)
        ?.toLong()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(bottom = 12.dp),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Add transaction", style = MaterialTheme.typography.headlineMedium)
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Rounded.Close, contentDescription = "Close")
                }
            }
            Spacer(Modifier.height(14.dp))
            Column(
                modifier = Modifier.padding(horizontal = 20.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                OutlinedTextField(
                    value = amount,
                    onValueChange = { value ->
                        if (value.matches(Regex("""\d{0,9}(\.\d{0,2})?"""))) amount = value
                    },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Amount") },
                    prefix = { Text("₹") },
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Decimal,
                        imeAction = ImeAction.Next,
                    ),
                    singleLine = true,
                    shape = MaterialTheme.shapes.medium,
                )
                OutlinedTextField(
                    value = merchant,
                    onValueChange = { merchant = it.take(48) },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Merchant") },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                    singleLine = true,
                    shape = MaterialTheme.shapes.medium,
                )
                AnimatedVisibility(type == TransactionType.EXPENSE) {
                    OutlinedTextField(
                        value = note,
                        onValueChange = { note = it.take(160) },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Note (optional)") },
                        placeholder = { Text("What was this expense for?") },
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        minLines = 2,
                        maxLines = 3,
                        shape = MaterialTheme.shapes.medium,
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(
                        TransactionType.EXPENSE to "Expense",
                        TransactionType.INCOME to "Income",
                        TransactionType.REFUND to "Refund",
                    ).forEach { (option, label) ->
                        FilterChip(
                            selected = type == option,
                            onClick = { type = option },
                            label = { Text(label) },
                        )
                    }
                }
            }
            AnimatedVisibility(type != TransactionType.INCOME) {
                Column {
                    Text(
                        text = "Category",
                        modifier = Modifier.padding(start = 20.dp, top = 12.dp, bottom = 4.dp),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 20.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(
                            ExpenseCategory.entries.filterNot {
                                it == ExpenseCategory.INCOME || it == ExpenseCategory.TRANSFER
                            },
                        ) { option ->
                            FilterChip(
                                selected = category == option,
                                onClick = { category = option },
                                leadingIcon = {
                                    CategoryIcon(
                                        category = option,
                                        modifier = Modifier.size(28.dp),
                                        iconSize = 15,
                                    )
                                },
                                label = { Text(option.label) },
                            )
                        }
                    }
                }
            }
            Button(
                onClick = {
                    onSave(
                        parsedAmount ?: return@Button,
                        merchant,
                        if (type == TransactionType.INCOME) ExpenseCategory.INCOME else category,
                        type,
                        note.takeIf { type == TransactionType.EXPENSE && it.isNotBlank() },
                    )
                },
                enabled = parsedAmount != null && parsedAmount > 0 && merchant.isNotBlank(),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 18.dp)
                    .height(54.dp),
                shape = MaterialTheme.shapes.medium,
            ) {
                Text("Save transaction")
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MerchantCategorySheet(
    group: MerchantTransactionGroup,
    onDismiss: () -> Unit,
    onCategoryChange: (ExpenseCategory) -> Unit,
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
                .navigationBarsPadding()
                .padding(bottom = 24.dp),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Categorize merchant", style = MaterialTheme.typography.headlineMedium)
                    Text(
                        text = group.merchant,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Rounded.Close, contentDescription = "Close")
                }
            }
            Text(
                text = if (group.transactionCount == 1) {
                    "Your choice will be remembered for future expenses from this merchant."
                } else {
                    "Your choice will update all ${group.transactionCount} matching expenses and future ones."
                },
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 14.dp),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            LazyRow(
                contentPadding = PaddingValues(horizontal = 20.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(ExpenseCategory.entries.filterNot { it == ExpenseCategory.INCOME }) { category ->
                    FilterChip(
                        selected = false,
                        onClick = { onCategoryChange(category) },
                        leadingIcon = {
                            CategoryIcon(
                                category = category,
                                modifier = Modifier.size(28.dp),
                                iconSize = 15,
                            )
                        },
                        label = { Text(category.label) },
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TransactionDetailSheet(
    transaction: TransactionRecord,
    matchingMerchantCount: Int,
    onDismiss: () -> Unit,
    onCategoryChange: (ExpenseCategory) -> Unit,
    onNoteSave: (String) -> Unit,
    onDelete: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var note by remember(transaction.id, transaction.note) {
        mutableStateOf(transaction.note.orEmpty())
    }
    var savedNote by remember(transaction.id, transaction.note) {
        mutableStateOf(transaction.note.orEmpty())
    }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 4.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            CategoryIcon(transaction.category, modifier = Modifier.size(62.dp), iconSize = 29)
            Spacer(Modifier.height(12.dp))
            Text(
                transaction.merchant,
                style = MaterialTheme.typography.headlineMedium,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(5.dp))
            MoneyText(
                amountMinor = transaction.amountMinor,
                style = MaterialTheme.typography.headlineLarge,
                color = when (transaction.type) {
                    TransactionType.INCOME, TransactionType.REFUND -> MaterialTheme.colorScheme.secondary
                    TransactionType.TRANSFER -> MaterialTheme.colorScheme.onSurfaceVariant
                    TransactionType.EXPENSE -> MaterialTheme.colorScheme.onSurface
                },
                prefix = when (transaction.type) {
                    TransactionType.INCOME, TransactionType.REFUND -> "+"
                    TransactionType.EXPENSE -> "−"
                    TransactionType.TRANSFER -> ""
                },
            )
            Text(
                formatTransactionTime(transaction.occurredAt) + " · " + transaction.source.name.lowercase()
                    .replaceFirstChar(Char::titlecase),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(22.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Text(
                text = if (transaction.type == TransactionType.EXPENSE && matchingMerchantCount > 1) {
                    "Change category for all ${transaction.merchant} expenses"
                } else {
                    "Change category"
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 18.dp, bottom = 8.dp),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            LazyRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(
                    ExpenseCategory.entries.filterNot { it == ExpenseCategory.INCOME },
                ) { category ->
                    FilterChip(
                        selected = category == transaction.category,
                        onClick = { onCategoryChange(category) },
                        label = { Text(category.label) },
                    )
                }
            }
            if (transaction.type == TransactionType.EXPENSE && matchingMerchantCount > 1) {
                Text(
                    text = "This will update $matchingMerchantCount matching expenses and remember the merchant.",
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            AnimatedVisibility(transaction.type == TransactionType.EXPENSE) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Spacer(Modifier.height(18.dp))
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    Text(
                        text = "Expense note",
                        modifier = Modifier.padding(top = 18.dp, bottom = 8.dp),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedTextField(
                        value = note,
                        onValueChange = { note = it.take(160) },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Note (optional)") },
                        placeholder = { Text("What was this expense for?") },
                        supportingText = { Text("${note.length}/160") },
                        minLines = 2,
                        maxLines = 4,
                        shape = MaterialTheme.shapes.medium,
                    )
                    Button(
                        onClick = {
                            onNoteSave(note)
                            savedNote = note.trim()
                        },
                        enabled = note.trim() != savedNote.trim(),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 10.dp)
                            .height(48.dp),
                        shape = MaterialTheme.shapes.medium,
                    ) {
                        Text(if (note.isBlank()) "Remove note" else "Save note")
                    }
                }
            }
            Spacer(Modifier.height(18.dp))
            OutlinedButton(
                onClick = onDelete,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
                shape = MaterialTheme.shapes.medium,
            ) {
                Icon(
                    Icons.Rounded.DeleteOutline,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                )
                Spacer(Modifier.width(8.dp))
                Text("Delete transaction", color = MaterialTheme.colorScheme.error)
            }
            TextButton(
                onClick = onDismiss,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 8.dp),
            ) {
                Text("Done")
            }
        }
    }
}
