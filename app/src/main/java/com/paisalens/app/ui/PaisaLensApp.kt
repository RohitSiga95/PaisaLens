package com.paisalens.app.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ReceiptLong
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.AddCircle
import androidx.compose.material.icons.rounded.Analytics
import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.material.icons.rounded.CameraAlt
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.DonutLarge
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.UploadFile
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
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
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.paisalens.app.data.model.ExpenseCategory
import com.paisalens.app.data.model.ExchangeRate
import com.paisalens.app.data.model.AccountProfile
import com.paisalens.app.data.model.CategorySelection
import com.paisalens.app.data.model.CustomCategory
import com.paisalens.app.data.model.MerchantTransactionGroup
import com.paisalens.app.data.model.ReceiptOcrDraft
import com.paisalens.app.data.model.ReviewStatus
import com.paisalens.app.data.model.TransactionRecord
import com.paisalens.app.data.model.TransactionType
import com.paisalens.app.data.model.findUncategorizedMerchantGroups
import com.paisalens.app.data.model.normalizedMerchantKey
import com.paisalens.app.ui.components.CategoryIcon
import com.paisalens.app.ui.components.CustomCategoryIcon
import com.paisalens.app.ui.components.MoneyText
import com.paisalens.app.ui.components.categoryColor
import com.paisalens.app.ui.components.customCategoryColor
import com.paisalens.app.ui.components.formatMoney
import com.paisalens.app.ui.components.formatTransactionTime
import com.paisalens.app.ui.screens.HomeScreen
import com.paisalens.app.ui.screens.OnboardingScreen
import com.paisalens.app.ui.screens.SettingsScreen
import com.paisalens.app.ui.screens.TransactionsScreen
import com.paisalens.app.ui.theme.PaisaLensTheme
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToLong

private enum class AppDestination(
    val label: String,
    val icon: ImageVector,
    val showInNavigation: Boolean = true,
) {
    HOME("Home", Icons.Rounded.Home),
    ACTIVITY("Activity", Icons.AutoMirrored.Rounded.ReceiptLong),
    PLAN("Plan", Icons.Rounded.DonutLarge),
    INSIGHTS("Insights", Icons.Rounded.Analytics),
    SETTINGS("Settings", Icons.Rounded.Settings),
    CALENDAR("Calendar", Icons.Rounded.CalendarMonth, false),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PaisaLensApp(
    viewModel: PaisaLensViewModel,
    hasSmsPermission: Boolean,
    onRequestSmsPermission: () -> Unit,
    onExportData: () -> Unit,
    onCreateBackup: (CharArray) -> Unit,
    onRestoreBackup: (CharArray) -> Unit,
    onImportStatement: (Long?) -> Unit,
    onAppLockChange: (Boolean) -> Unit,
    onCaptureReceipt: () -> Unit,
    onPickReceipt: () -> Unit,
    onComposeBalanceSms: (AccountProfile) -> Unit,
) {
    val transactions by viewModel.transactions.collectAsStateWithLifecycle()
    val budgets by viewModel.budgets.collectAsStateWithLifecycle()
    val categorizedMerchantKeys by viewModel.categorizedMerchantKeys.collectAsStateWithLifecycle()
    val accounts by viewModel.accounts.collectAsStateWithLifecycle()
    val balanceHistory by viewModel.balanceHistory.collectAsStateWithLifecycle()
    val bills by viewModel.bills.collectAsStateWithLifecycle()
    val netWorthItems by viewModel.netWorthItems.collectAsStateWithLifecycle()
    val smartCategoryRules by viewModel.smartCategoryRules.collectAsStateWithLifecycle()
    val customCategories by viewModel.customCategories.collectAsStateWithLifecycle()
    val recurringPayments by viewModel.recurringPayments.collectAsStateWithLifecycle()
    val loans by viewModel.loans.collectAsStateWithLifecycle()
    val exchangeRates by viewModel.exchangeRates.collectAsStateWithLifecycle()
    val merchantAliases by viewModel.merchantAliases.collectAsStateWithLifecycle()
    val insights by viewModel.insights.collectAsStateWithLifecycle()
    val isScanning by viewModel.isScanning.collectAsStateWithLifecycle()
    val onboardingComplete by viewModel.onboardingComplete.collectAsStateWithLifecycle()
    val darkMode by viewModel.darkMode.collectAsStateWithLifecycle()
    val lastScanAt by viewModel.lastScanAt.collectAsStateWithLifecycle()
    val appLockEnabled by viewModel.appLockEnabled.collectAsStateWithLifecycle()
    val widgetAmountsVisible by viewModel.widgetAmountsVisible.collectAsStateWithLifecycle()
    val travelModeEnabled by viewModel.travelModeEnabled.collectAsStateWithLifecycle()
    val baseCurrency by viewModel.baseCurrency.collectAsStateWithLifecycle()
    val statementPreview by viewModel.statementPreview.collectAsStateWithLifecycle()
    val isImporting by viewModel.isImporting.collectAsStateWithLifecycle()
    val isRefreshingRate by viewModel.isRefreshingRate.collectAsStateWithLifecycle()
    val receiptDraft by viewModel.receiptDraft.collectAsStateWithLifecycle()
    val isReceiptOcrRunning by viewModel.isReceiptOcrRunning.collectAsStateWithLifecycle()
    var destination by rememberSaveable { mutableStateOf(AppDestination.HOME) }
    var showManualSheet by remember { mutableStateOf(false) }
    var selectedTransaction by remember { mutableStateOf<TransactionRecord?>(null) }
    var selectedMerchantGroup by remember { mutableStateOf<MerchantTransactionGroup?>(null) }
    var selectedAccount by remember { mutableStateOf<AccountProfile?>(null) }
    var showAccountManager by remember { mutableStateOf(false) }
    var showCategoryManager by remember { mutableStateOf(false) }
    var showMerchantCleanup by remember { mutableStateOf(false) }
    var showLoanManager by remember { mutableStateOf(false) }
    var showTravelMode by remember { mutableStateOf(false) }
    var showStatementImport by remember { mutableStateOf(false) }
    var showSmartCategoryRules by remember { mutableStateOf(false) }
    var backupAction by remember { mutableStateOf<BackupAction?>(null) }
    val uncategorizedMerchants = remember(transactions, categorizedMerchantKeys) {
        findUncategorizedMerchantGroups(transactions, categorizedMerchantKeys)
    }
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current

    BackHandler(enabled = !destination.showInNavigation) {
        destination = if (destination == AppDestination.CALENDAR) AppDestination.ACTIVITY else AppDestination.HOME
    }

    LaunchedEffect(viewModel) {
        viewModel.events.collect { message ->
            snackbarHostState.showSnackbar(message)
        }
    }

    LaunchedEffect(receiptDraft) {
        if (receiptDraft != null) showManualSheet = true
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
                    AppDestination.entries.filter { it.showInNavigation }.forEach { item ->
                        NavigationBarItem(
                            selected = destination == item ||
                                (destination == AppDestination.CALENDAR && item == AppDestination.ACTIVITY),
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
                            accounts = accounts,
                            isScanning = isScanning,
                            hasSmsPermission = hasSmsPermission,
                            onScan = { viewModel.scanSms(context) },
                            onRequestPermission = onRequestSmsPermission,
                            onAdd = { showManualSheet = true },
                            onRefreshAccount = onComposeBalanceSms,
                            onAccountClick = { selectedAccount = it },
                            onTransactionClick = { selectedTransaction = it },
                        )
                        AppDestination.ACTIVITY -> TransactionsScreen(
                            transactions = transactions,
                            uncategorizedMerchants = uncategorizedMerchants,
                            onCategorizeMerchant = { selectedMerchantGroup = it },
                            onCalendar = { destination = AppDestination.CALENDAR },
                            onTransactionClick = { selectedTransaction = it },
                        )
                        AppDestination.PLAN -> PlanningScreen(
                            transactions = transactions,
                            budgets = budgets,
                            bills = bills,
                            recurringPayments = recurringPayments,
                            loans = loans,
                            accounts = accounts,
                            onSetBudget = viewModel::setBudget,
                            onSaveBill = viewModel::saveBill,
                            onMarkBillPaid = viewModel::markBillPaid,
                            onDeleteBill = viewModel::deleteBill,
                        )
                        AppDestination.INSIGHTS -> InsightsScreen(
                            transactions = transactions,
                            accounts = accounts,
                            balanceHistory = balanceHistory,
                            bills = bills,
                            recurringPayments = recurringPayments,
                            loans = loans,
                            netWorthItems = netWorthItems,
                            insights = insights,
                            onTransactionClick = { selectedTransaction = it },
                            onSaveNetWorthItem = viewModel::saveNetWorthItem,
                            onDeleteNetWorthItem = viewModel::deleteNetWorthItem,
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
                            accountCount = accounts.size,
                            customCategoryCount = customCategories.size,
                            recurringCount = recurringPayments.size,
                            reviewCount = transactions.count { it.reviewStatus == ReviewStatus.NEEDS_REVIEW },
                            loanCount = loans.size,
                            merchantAliasCount = merchantAliases.size,
                            smartRuleCount = smartCategoryRules.size,
                            rateCount = exchangeRates.count { it.baseCurrency == baseCurrency },
                            appLockEnabled = appLockEnabled,
                            widgetAmountsVisible = widgetAmountsVisible,
                            travelModeEnabled = travelModeEnabled,
                            baseCurrency = baseCurrency,
                            onExportData = onExportData,
                            onManageAccounts = { showAccountManager = true },
                            onManageCategories = { showCategoryManager = true },
                            onMerchantCleanup = { showMerchantCleanup = true },
                            onSmartCategoryRules = { showSmartCategoryRules = true },
                            onManageLoans = { showLoanManager = true },
                            onTravelMode = { showTravelMode = true },
                            onImportStatement = { showStatementImport = true },
                            onAppLockChange = onAppLockChange,
                            onWidgetAmountsChange = viewModel::setWidgetAmountsVisible,
                            onCreateBackup = { backupAction = BackupAction.CREATE },
                            onRestoreBackup = { backupAction = BackupAction.RESTORE },
                            onReviewTransactions = { destination = AppDestination.ACTIVITY },
                            onClearAll = viewModel::clearAll,
                        )
                        AppDestination.CALENDAR -> CalendarScreen(
                            transactions = transactions,
                            onBack = { destination = AppDestination.ACTIVITY },
                            onTransactionClick = { selectedTransaction = it },
                        )
                    }
                }
            }
        }

        if (showManualSheet) {
            ManualTransactionSheet(
                accounts = accounts,
                customCategories = customCategories,
                travelModeEnabled = travelModeEnabled,
                baseCurrency = baseCurrency,
                exchangeRates = exchangeRates,
                receiptDraft = receiptDraft,
                receiptOcrRunning = isReceiptOcrRunning,
                onCaptureReceipt = onCaptureReceipt,
                onPickReceipt = onPickReceipt,
                onAddCustomCategory = viewModel::addCustomCategory,
                onDismiss = {
                    showManualSheet = false
                    viewModel.clearReceiptDraft()
                },
                onSave = { amount, merchant, category, type, note, accountId, tags, originalAmount, originalCurrency, rate ->
                    viewModel.addManual(amount, merchant, category, type, note, accountId, tags, originalAmount, originalCurrency, rate)
                    showManualSheet = false
                    viewModel.clearReceiptDraft()
                },
            )
        }

        selectedMerchantGroup?.let { group ->
            val matchingExpenses = remember(transactions, group.merchantKey) {
                transactions
                    .filter {
                        it.type == TransactionType.EXPENSE &&
                            normalizedMerchantKey(it.merchant) == group.merchantKey
                    }
                    .sortedByDescending { it.occurredAt }
            }
            MerchantCategorySheet(
                group = group,
                matchingExpenses = matchingExpenses,
                customCategories = customCategories,
                onAddCustomCategory = viewModel::addCustomCategory,
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
                accounts = accounts,
                customCategories = customCategories,
                onAddCustomCategory = viewModel::addCustomCategory,
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
                onTagsSave = { tags -> viewModel.updateTags(transaction.id, tags) },
                onConfirm = {
                    viewModel.confirmTransaction(transaction.id)
                    selectedTransaction = null
                },
                onAccountChange = { accountId ->
                    viewModel.updateTransactionAccount(transaction.id, accountId)
                    selectedTransaction = null
                },
                onTypeChange = { type ->
                    viewModel.updateTransactionType(transaction.id, type)
                    selectedTransaction = null
                },
                onDelete = {
                    viewModel.deleteTransaction(transaction.id)
                    selectedTransaction = null
                },
            )
        }

        if (showAccountManager) {
            AccountManagerSheet(
                accounts = accounts,
                onAdd = viewModel::addAccount,
                onUpdate = viewModel::updateAccount,
                onDelete = viewModel::deleteAccount,
                onDismiss = { showAccountManager = false },
            )
        }

        selectedAccount?.let { selected ->
            val current = accounts.firstOrNull { it.id == selected.id }?.let { raw ->
                raw.copy(
                    accountHint = raw.accountHint ?: selected.accountHint,
                    institution = raw.institution?.takeIf(String::isNotBlank) ?: selected.institution,
                    balanceMinor = raw.balanceMinor ?: selected.balanceMinor,
                    availableCreditMinor = raw.availableCreditMinor ?: selected.availableCreditMinor,
                    creditLimitMinor = raw.creditLimitMinor ?: selected.creditLimitMinor,
                    availabilityFetchedAt = raw.availabilityFetchedAt ?: selected.availabilityFetchedAt,
                    availabilitySender = raw.availabilitySender ?: selected.availabilitySender,
                )
            } ?: selected
            val relatedAccountIds = current.accountHint
                ?.filter(Char::isDigit)
                ?.takeLast(4)
                ?.takeIf(String::isNotBlank)
                ?.let { lastFour ->
                    accounts.filter {
                        it.type == current.type && it.accountHint?.filter(Char::isDigit)?.takeLast(4) == lastFour
                    }.map { it.id }.toSet()
                }
                ?: setOf(current.id)
            AccountFinanceSheet(
                account = current,
                history = balanceHistory
                    .filter { it.accountId in relatedAccountIds }
                    .map { if (it.accountId == current.id) it else it.copy(accountId = current.id) },
                onUpdateAccount = viewModel::updateAccount,
                onDismiss = { selectedAccount = null },
            )
        }

        if (showCategoryManager) {
            CustomCategoryManagerSheet(
                categories = customCategories,
                onAdd = viewModel::addCustomCategory,
                onDelete = viewModel::deleteCustomCategory,
                onDismiss = { showCategoryManager = false },
            )
        }

        if (showMerchantCleanup) {
            MerchantCleanupSheet(
                transactions = transactions,
                aliases = merchantAliases,
                onRename = viewModel::renameMerchant,
                onDeleteAlias = viewModel::deleteMerchantAlias,
                onDismiss = { showMerchantCleanup = false },
            )
        }

        if (showSmartCategoryRules) {
            SmartCategoryRulesSheet(
                rules = smartCategoryRules,
                transactions = transactions,
                exactMerchantKeys = categorizedMerchantKeys,
                accounts = accounts,
                customCategories = customCategories,
                onSave = viewModel::saveSmartCategoryRule,
                onDelete = viewModel::deleteSmartCategoryRule,
                onDismiss = { showSmartCategoryRules = false },
            )
        }

        if (showLoanManager) {
            LoanManagerSheet(
                loans = loans,
                accounts = accounts,
                onSave = viewModel::saveLoan,
                onDelete = viewModel::deleteLoan,
                onDismiss = { showLoanManager = false },
            )
        }

        if (showTravelMode) {
            TravelModeSheet(
                enabled = travelModeEnabled,
                baseCurrency = baseCurrency,
                rates = exchangeRates,
                refreshing = isRefreshingRate,
                onEnabledChange = viewModel::setTravelMode,
                onRefresh = viewModel::refreshExchangeRate,
                onDismiss = { showTravelMode = false },
            )
        }

        if (showStatementImport) {
            StatementImportSheet(
                accounts = accounts,
                preview = statementPreview,
                importing = isImporting,
                onChooseFile = onImportStatement,
                onConfirm = viewModel::confirmStatementImport,
                onCancelPreview = viewModel::cancelStatementImport,
                onDismiss = { showStatementImport = false },
            )
        }

        backupAction?.let { action ->
            BackupPassphraseDialog(
                action = action,
                onDismiss = { backupAction = null },
                onSubmit = { passphrase ->
                    if (action == BackupAction.CREATE) onCreateBackup(passphrase) else onRestoreBackup(passphrase)
                    backupAction = null
                },
            )
        }
    }
}

@Composable
private fun BuiltInCategoryChip(
    category: ExpenseCategory,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val color = categoryColor(category)
    FilterChip(
        selected = selected,
        onClick = onClick,
        modifier = Modifier.heightIn(min = 48.dp),
        leadingIcon = {
            CategoryIcon(category = category, modifier = Modifier.size(30.dp), iconSize = 16)
        },
        label = { Text(category.label) },
        colors = FilterChipDefaults.filterChipColors(
            containerColor = color.copy(alpha = 0.10f),
            labelColor = MaterialTheme.colorScheme.onSurface,
            selectedContainerColor = color.copy(alpha = 0.24f),
            selectedLabelColor = MaterialTheme.colorScheme.onSurface,
        ),
    )
}

@Composable
private fun CustomCategoryChoiceChip(
    category: CustomCategory,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val color = customCategoryColor(category.colorHex)
    FilterChip(
        selected = selected,
        onClick = onClick,
        modifier = Modifier.heightIn(min = 48.dp),
        leadingIcon = {
            CustomCategoryIcon(category = category, modifier = Modifier.size(30.dp), iconSize = 16)
        },
        label = { Text(category.name) },
        colors = FilterChipDefaults.filterChipColors(
            containerColor = color.copy(alpha = 0.10f),
            labelColor = MaterialTheme.colorScheme.onSurface,
            selectedContainerColor = color.copy(alpha = 0.24f),
            selectedLabelColor = MaterialTheme.colorScheme.onSurface,
        ),
    )
}

@Composable
private fun NewCategoryChoiceChip(onClick: () -> Unit) {
    FilterChip(
        selected = false,
        onClick = onClick,
        modifier = Modifier.heightIn(min = 48.dp),
        leadingIcon = {
            Icon(
                imageVector = Icons.Rounded.AddCircle,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
            )
        },
        label = { Text("New category") },
        colors = FilterChipDefaults.filterChipColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
            labelColor = MaterialTheme.colorScheme.onPrimaryContainer,
        ),
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ManualTransactionSheet(
    accounts: List<AccountProfile>,
    customCategories: List<CustomCategory>,
    travelModeEnabled: Boolean,
    baseCurrency: String,
    exchangeRates: List<ExchangeRate>,
    receiptDraft: ReceiptOcrDraft?,
    receiptOcrRunning: Boolean,
    onCaptureReceipt: () -> Unit,
    onPickReceipt: () -> Unit,
    onAddCustomCategory: (String, String, (CustomCategory) -> Unit) -> Unit,
    onDismiss: () -> Unit,
    onSave: (Long, String, CategorySelection, TransactionType, String?, Long?, String, Long?, String?, Double?) -> Unit,
) {
    var amount by remember { mutableStateOf("") }
    var merchant by remember { mutableStateOf("") }
    var type by remember { mutableStateOf(TransactionType.EXPENSE) }
    var category by remember { mutableStateOf(ExpenseCategory.OTHER) }
    var customCategoryId by remember { mutableStateOf<Long?>(null) }
    var note by remember { mutableStateOf("") }
    var tags by remember { mutableStateOf("") }
    var accountId by remember { mutableStateOf<Long?>(null) }
    var selectedCurrency by remember { mutableStateOf(baseCurrency) }
    var showNewCategoryDialog by remember { mutableStateOf(false) }
    LaunchedEffect(receiptDraft) {
        receiptDraft?.let { draft ->
            amount = draft.amountMinor?.let { minor ->
                BigDecimal(minor).divide(BigDecimal(100)).stripTrailingZeros().toPlainString()
            }.orEmpty()
            merchant = draft.merchant
            type = TransactionType.EXPENSE
            category = draft.category
            customCategoryId = null
            note = draft.note
            tags = "receipt"
            selectedCurrency = baseCurrency
        }
    }
    val parsedOriginalAmount = amount.toBigDecimalOrNull()
        ?.multiply(BigDecimal(100))
        ?.setScale(0, RoundingMode.HALF_UP)
        ?.toLong()
    val selectedRate = if (selectedCurrency == baseCurrency) 1.0 else exchangeRates
        .firstOrNull { it.baseCurrency == baseCurrency && it.quoteCurrency == selectedCurrency }
        ?.rate
    val parsedAmount = parsedOriginalAmount?.let { original -> selectedRate?.let { (original * it).roundToLong() } }
    val availableCurrencies = remember(exchangeRates, baseCurrency) {
        (listOf(baseCurrency) + exchangeRates.filter { it.baseCurrency == baseCurrency }.map { it.quoteCurrency }).distinct()
    }
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
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = "Add from a bill",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    OutlinedButton(
                        onClick = onCaptureReceipt,
                        enabled = !receiptOcrRunning,
                        modifier = Modifier.weight(1f).height(48.dp),
                    ) {
                        Icon(Icons.Rounded.CameraAlt, contentDescription = null)
                        Spacer(Modifier.width(7.dp))
                        Text("Camera")
                    }
                    OutlinedButton(
                        onClick = onPickReceipt,
                        enabled = !receiptOcrRunning,
                        modifier = Modifier.weight(1f).height(48.dp),
                    ) {
                        Icon(Icons.Rounded.UploadFile, contentDescription = null)
                        Spacer(Modifier.width(7.dp))
                        Text("Upload")
                    }
                }
                if (receiptOcrRunning) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                        Text("Reading bill locally…", style = MaterialTheme.typography.bodySmall)
                    }
                } else if (receiptDraft != null) {
                    Text(
                        text = "Details filled from ${receiptDraft.sourceLabel}. Review them before saving.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
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
                    prefix = { Text(selectedCurrency) },
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Decimal,
                        imeAction = ImeAction.Next,
                    ),
                    singleLine = true,
                    shape = MaterialTheme.shapes.medium,
                )
                if (travelModeEnabled) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Currency", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(availableCurrencies) { currency ->
                                FilterChip(
                                    selected = selectedCurrency == currency,
                                    onClick = { selectedCurrency = currency },
                                    label = { Text(currency) },
                                )
                            }
                        }
                        if (selectedCurrency != baseCurrency) {
                            Text(
                                selectedRate?.let { "Latest reference rate · ${"%.4f".format(it)} $baseCurrency per $selectedCurrency · converted ${parsedAmount?.let(::formatMoney) ?: "—"}" }
                                    ?: "Refresh $selectedCurrency/$baseCurrency in Settings → Travel before saving.",
                                style = MaterialTheme.typography.bodySmall,
                                color = if (selectedRate == null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
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
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        OutlinedTextField(
                            value = note,
                            onValueChange = { note = it.take(160) },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("Note (optional)") },
                            placeholder = { Text("What was this expense for?") },
                            minLines = 2,
                            maxLines = 3,
                            shape = MaterialTheme.shapes.medium,
                        )
                        OutlinedTextField(
                            value = tags,
                            onValueChange = { tags = it.take(120) },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("Tags (optional)") },
                            placeholder = { Text("Vacation, reimbursable") },
                            supportingText = { Text("Separate up to 6 tags with commas") },
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                            singleLine = true,
                            shape = MaterialTheme.shapes.medium,
                        )
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(
                        TransactionType.EXPENSE to "Expense",
                        TransactionType.INCOME to "Income",
                        TransactionType.REFUND to "Refund",
                        TransactionType.TRANSFER to "Transfer",
                    ).forEach { (option, label) ->
                        FilterChip(
                            selected = type == option,
                            onClick = { type = option },
                            label = { Text(label) },
                        )
                    }
                }
            }
            AnimatedVisibility(type == TransactionType.EXPENSE || type == TransactionType.REFUND) {
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
                            BuiltInCategoryChip(
                                category = option,
                                selected = category == option,
                                onClick = {
                                    category = option
                                    customCategoryId = null
                                },
                            )
                        }
                        items(customCategories, key = { "custom-${it.id}" }) { custom ->
                            CustomCategoryChoiceChip(
                                category = custom,
                                selected = customCategoryId == custom.id,
                                onClick = {
                                    category = ExpenseCategory.OTHER
                                    customCategoryId = custom.id
                                },
                            )
                        }
                        item {
                            NewCategoryChoiceChip(onClick = { showNewCategoryDialog = true })
                        }
                    }
                }
            }
            if (accounts.isNotEmpty()) {
                Text(
                    text = "Account (optional)",
                    modifier = Modifier.padding(start = 20.dp, top = 12.dp, bottom = 4.dp),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 20.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    item {
                        FilterChip(
                            selected = accountId == null,
                            onClick = { accountId = null },
                            label = { Text("Unassigned") },
                        )
                    }
                    items(accounts, key = { it.id }) { account ->
                        FilterChip(
                            selected = accountId == account.id,
                            onClick = { accountId = account.id },
                            label = { Text(account.name) },
                        )
                    }
                }
            }
            Button(
                onClick = {
                    onSave(
                        parsedAmount ?: return@Button,
                        merchant,
                        when (type) {
                            TransactionType.INCOME -> CategorySelection(ExpenseCategory.INCOME)
                            TransactionType.TRANSFER -> CategorySelection(ExpenseCategory.TRANSFER)
                            else -> CategorySelection(
                                builtIn = category,
                                customCategoryId = customCategoryId,
                                customCategoryName = customCategories.firstOrNull { it.id == customCategoryId }?.name,
                            )
                        },
                        type,
                        note.takeIf { type == TransactionType.EXPENSE && it.isNotBlank() },
                        accountId,
                        tags,
                        parsedOriginalAmount?.takeIf { selectedCurrency != baseCurrency },
                        selectedCurrency.takeIf { it != baseCurrency },
                        selectedRate?.takeIf { selectedCurrency != baseCurrency },
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

    if (showNewCategoryDialog) {
        NewCustomCategoryDialog(
            existingCategories = customCategories,
            onDismiss = { showNewCategoryDialog = false },
            onCreate = { name, colorHex ->
                onAddCustomCategory(name, colorHex) { created ->
                    category = ExpenseCategory.OTHER
                    customCategoryId = created.id
                    showNewCategoryDialog = false
                }
            },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MerchantCategorySheet(
    group: MerchantTransactionGroup,
    matchingExpenses: List<TransactionRecord>,
    customCategories: List<CustomCategory>,
    onAddCustomCategory: (String, String, (CustomCategory) -> Unit) -> Unit,
    onDismiss: () -> Unit,
    onCategoryChange: (CategorySelection) -> Unit,
) {
    var showNewCategoryDialog by remember { mutableStateOf(false) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding(),
            contentPadding = PaddingValues(bottom = 28.dp),
        ) {
            item {
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
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Rounded.Close, contentDescription = "Close")
                    }
                }
            }
            item {
                MerchantSpendingChart(
                    expenses = matchingExpenses,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
                )
            }
            item {
                Text(
                    text = "Choose a category",
                    modifier = Modifier.padding(horizontal = 20.dp),
                    style = MaterialTheme.typography.titleLarge,
                )
                Text(
                    text = if (matchingExpenses.size == 1) {
                        "Your choice will be remembered for future expenses from this merchant."
                    } else {
                        "Your choice will update all ${matchingExpenses.size} matching expenses and future ones."
                    },
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            item {
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 20.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(ExpenseCategory.entries.filterNot { it == ExpenseCategory.INCOME }) { category ->
                        BuiltInCategoryChip(
                            category = category,
                            selected = false,
                            onClick = { onCategoryChange(CategorySelection(category)) },
                        )
                    }
                    items(customCategories, key = { "custom-${it.id}" }) { custom ->
                        CustomCategoryChoiceChip(
                            category = custom,
                            selected = false,
                            onClick = {
                                onCategoryChange(
                                    CategorySelection(
                                        builtIn = ExpenseCategory.OTHER,
                                        customCategoryId = custom.id,
                                        customCategoryName = custom.name,
                                    ),
                                )
                            },
                        )
                    }
                    item {
                        NewCategoryChoiceChip(onClick = { showNewCategoryDialog = true })
                    }
                }
            }
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 20.dp, end = 20.dp, top = 24.dp, bottom = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Matching expenses", style = MaterialTheme.typography.titleLarge)
                    Text(
                        text = matchingExpenses.size.toString(),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            if (matchingExpenses.isEmpty()) {
                item {
                    Text(
                        text = "No matching expenses are available.",
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 18.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                items(matchingExpenses, key = { it.id }) { expense ->
                    MerchantExpenseRow(expense)
                    HorizontalDivider(modifier = Modifier.padding(horizontal = 20.dp))
                }
            }
        }
    }

    if (showNewCategoryDialog) {
        NewCustomCategoryDialog(
            existingCategories = customCategories,
            onDismiss = { showNewCategoryDialog = false },
            onCreate = { name, colorHex ->
                onAddCustomCategory(name, colorHex) { created ->
                    onCategoryChange(
                        CategorySelection(
                            builtIn = ExpenseCategory.OTHER,
                            customCategoryId = created.id,
                            customCategoryName = created.name,
                        ),
                    )
                }
            },
        )
    }
}

private data class MerchantDailySpend(
    val date: LocalDate,
    val amountMinor: Long,
)

@Composable
private fun MerchantSpendingChart(
    expenses: List<TransactionRecord>,
    modifier: Modifier = Modifier,
) {
    val dateFormatter = remember { DateTimeFormatter.ofPattern("d MMM", Locale.forLanguageTag("en-IN")) }
    val dailySpend = remember(expenses) {
        expenses
            .groupBy {
                Instant.ofEpochMilli(it.occurredAt)
                    .atZone(ZoneId.systemDefault())
                    .toLocalDate()
            }
            .map { (date, transactions) ->
                MerchantDailySpend(date, transactions.sumOf { it.amountMinor })
            }
            .sortedBy { it.date }
    }
    val totalMinor = expenses.sumOf { it.amountMinor }
    val peakMinor = dailySpend.maxOfOrNull { it.amountMinor } ?: 0L
    val chartDescription = if (dailySpend.isEmpty()) {
        "No merchant spending data"
    } else {
        "Merchant spending line chart with ${dailySpend.size} days from " +
            "${dailySpend.first().date.format(dateFormatter)} to " +
            "${dailySpend.last().date.format(dateFormatter)}. Total ${formatMoney(totalMinor)}; " +
            "highest daily spend ${formatMoney(peakMinor)}. Exact expenses are listed below."
    }
    val lineColor = MaterialTheme.colorScheme.primary
    val gridColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.65f)

    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.48f),
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        shape = MaterialTheme.shapes.extraLarge,
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Spending over time", style = MaterialTheme.typography.titleMedium)
                    Text(
                        text = "${expenses.size} ${if (expenses.size == 1) "expense" else "expenses"} · Peak ${formatMoney(peakMinor)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.76f),
                    )
                }
                Text(
                    text = formatMoney(totalMinor),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                )
            }
            if (dailySpend.isEmpty()) {
                Text(
                    text = "A trend will appear when this merchant has expenses.",
                    modifier = Modifier.padding(vertical = 28.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.76f),
                )
            } else {
                Canvas(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(142.dp)
                        .semantics { contentDescription = chartDescription },
                ) {
                    val horizontalInset = 8.dp.toPx()
                    val verticalInset = 10.dp.toPx()
                    val plotWidth = (size.width - horizontalInset * 2).coerceAtLeast(1f)
                    val plotHeight = (size.height - verticalInset * 2).coerceAtLeast(1f)
                    val maxValue = dailySpend.maxOf { it.amountMinor }.coerceAtLeast(1L)

                    repeat(3) { index ->
                        val y = verticalInset + plotHeight * index / 2f
                        drawLine(
                            color = gridColor,
                            start = Offset(horizontalInset, y),
                            end = Offset(size.width - horizontalInset, y),
                            strokeWidth = 1.dp.toPx(),
                        )
                    }

                    val coordinates = dailySpend.mapIndexed { index, point ->
                        val x = if (dailySpend.size == 1) {
                            horizontalInset + plotWidth / 2f
                        } else {
                            horizontalInset + plotWidth * index / dailySpend.lastIndex
                        }
                        val y = verticalInset + plotHeight *
                            (1f - point.amountMinor.toFloat() / maxValue.toFloat())
                        Offset(x, y)
                    }
                    if (coordinates.size > 1) {
                        val path = Path().apply {
                            moveTo(coordinates.first().x, coordinates.first().y)
                            coordinates.drop(1).forEach { point -> lineTo(point.x, point.y) }
                        }
                        drawPath(
                            path = path,
                            color = lineColor,
                            style = Stroke(
                                width = 3.dp.toPx(),
                                cap = StrokeCap.Round,
                                join = StrokeJoin.Round,
                            ),
                        )
                    }
                    coordinates.forEachIndexed { index, point ->
                        if (coordinates.size <= 14 || index == 0 || index == coordinates.lastIndex) {
                            drawCircle(color = lineColor, radius = 4.dp.toPx(), center = point)
                        }
                    }
                }
                if (dailySpend.size == 1) {
                    Text(
                        text = dailySpend.first().date.format(dateFormatter),
                        modifier = Modifier.fillMaxWidth(),
                        textAlign = TextAlign.Center,
                        style = MaterialTheme.typography.labelSmall,
                    )
                } else {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(dailySpend.first().date.format(dateFormatter), style = MaterialTheme.typography.labelSmall)
                        Text(dailySpend.last().date.format(dateFormatter), style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }
    }
}

@Composable
private fun MerchantExpenseRow(expense: TransactionRecord) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CategoryIcon(
            category = expense.category,
            modifier = Modifier.size(44.dp),
            iconSize = 21,
        )
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = formatTransactionTime(expense.occurredAt),
                style = MaterialTheme.typography.titleSmall,
            )
            Text(
                text = expense.accountName ?: expense.accountHint ?: expense.source.name.lowercase()
                    .replaceFirstChar { it.titlecase() },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            expense.note?.takeIf { it.isNotBlank() }?.let { note ->
                Text(
                    text = "Note: $note",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        MoneyText(
            amountMinor = expense.amountMinor,
            style = MaterialTheme.typography.titleMedium,
            prefix = "−",
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TransactionDetailSheet(
    transaction: TransactionRecord,
    accounts: List<AccountProfile>,
    customCategories: List<CustomCategory>,
    onAddCustomCategory: (String, String, (CustomCategory) -> Unit) -> Unit,
    matchingMerchantCount: Int,
    onDismiss: () -> Unit,
    onCategoryChange: (CategorySelection) -> Unit,
    onNoteSave: (String) -> Unit,
    onTagsSave: (String) -> Unit,
    onConfirm: () -> Unit,
    onAccountChange: (Long?) -> Unit,
    onTypeChange: (TransactionType) -> Unit,
    onDelete: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var note by remember(transaction.id, transaction.note) {
        mutableStateOf(transaction.note.orEmpty())
    }
    var savedNote by remember(transaction.id, transaction.note) {
        mutableStateOf(transaction.note.orEmpty())
    }
    var tags by remember(transaction.id, transaction.tags) {
        mutableStateOf(transaction.tags.joinToString(", "))
    }
    var savedTags by remember(transaction.id, transaction.tags) {
        mutableStateOf(transaction.tags.joinToString(", "))
    }
    var showNewCategoryDialog by remember { mutableStateOf(false) }
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
            val activeCustomCategory = customCategories.firstOrNull { it.id == transaction.customCategoryId }
            if (activeCustomCategory == null) {
                CategoryIcon(transaction.category, modifier = Modifier.size(62.dp), iconSize = 29)
            } else {
                CustomCategoryIcon(activeCustomCategory, modifier = Modifier.size(62.dp), iconSize = 29)
            }
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
            transaction.accountName?.let { accountName ->
                Text(
                    accountName,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            if (transaction.reviewStatus == ReviewStatus.NEEDS_REVIEW) {
                Spacer(Modifier.height(16.dp))
                Surface(
                    color = MaterialTheme.colorScheme.errorContainer,
                    contentColor = MaterialTheme.colorScheme.onErrorContainer,
                    shape = MaterialTheme.shapes.medium,
                ) {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text("Needs review", style = MaterialTheme.typography.titleMedium)
                        Text(
                            transaction.reviewReason ?: "Confirm that this transaction is correct.",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Button(onClick = onConfirm, modifier = Modifier.fillMaxWidth()) {
                            Text("Confirm transaction")
                        }
                    }
                }
            }
            Spacer(Modifier.height(22.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Text(
                text = "Transaction type",
                modifier = Modifier.fillMaxWidth().padding(top = 18.dp, bottom = 8.dp),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            LazyRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(TransactionType.entries) { type ->
                    FilterChip(
                        selected = type == transaction.type,
                        onClick = { if (type != transaction.type) onTypeChange(type) },
                        label = { Text(type.name.lowercase().replaceFirstChar(Char::titlecase)) },
                    )
                }
            }
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
                    BuiltInCategoryChip(
                        category = category,
                        selected = category == transaction.category && transaction.customCategoryId == null,
                        onClick = { onCategoryChange(CategorySelection(category)) },
                    )
                }
                items(customCategories, key = { "custom-${it.id}" }) { custom ->
                    CustomCategoryChoiceChip(
                        category = custom,
                        selected = custom.id == transaction.customCategoryId,
                        onClick = {
                            onCategoryChange(
                                CategorySelection(
                                    builtIn = ExpenseCategory.OTHER,
                                    customCategoryId = custom.id,
                                    customCategoryName = custom.name,
                                ),
                            )
                        },
                    )
                }
                item {
                    NewCategoryChoiceChip(onClick = { showNewCategoryDialog = true })
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
            if (accounts.isNotEmpty()) {
                Spacer(Modifier.height(18.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Text(
                    text = "Account or card",
                    modifier = Modifier.fillMaxWidth().padding(top = 18.dp, bottom = 8.dp),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                LazyRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    item {
                        FilterChip(
                            selected = transaction.accountId == null,
                            onClick = { onAccountChange(null) },
                            label = { Text("Unassigned") },
                        )
                    }
                    items(accounts, key = { it.id }) { account ->
                        FilterChip(
                            selected = transaction.accountId == account.id,
                            onClick = { onAccountChange(account.id) },
                            label = { Text(account.name) },
                        )
                    }
                }
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
                    Spacer(Modifier.height(14.dp))
                    OutlinedTextField(
                        value = tags,
                        onValueChange = { tags = it.take(120) },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Tags (optional)") },
                        placeholder = { Text("Vacation, reimbursable") },
                        supportingText = { Text("Separate up to 6 tags with commas") },
                        singleLine = true,
                        shape = MaterialTheme.shapes.medium,
                    )
                    OutlinedButton(
                        onClick = {
                            onTagsSave(tags)
                            savedTags = tags.trim()
                        },
                        enabled = tags.trim() != savedTags.trim(),
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp).height(48.dp),
                        shape = MaterialTheme.shapes.medium,
                    ) {
                        Text("Save tags")
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

    if (showNewCategoryDialog) {
        NewCustomCategoryDialog(
            existingCategories = customCategories,
            onDismiss = { showNewCategoryDialog = false },
            onCreate = { name, colorHex ->
                onAddCustomCategory(name, colorHex) { created ->
                    onCategoryChange(
                        CategorySelection(
                            builtIn = ExpenseCategory.OTHER,
                            customCategoryId = created.id,
                            customCategoryName = created.name,
                        ),
                    )
                }
            },
        )
    }
}
