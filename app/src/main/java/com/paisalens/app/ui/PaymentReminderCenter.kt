package com.paisalens.app.ui

import android.content.Context
import android.graphics.Color as AndroidColor
import android.text.format.DateFormat
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ViewList
import androidx.compose.material.icons.rounded.AddAlert
import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.material.icons.rounded.CreditCard
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.ElectricBolt
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Event
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.NotificationsActive
import androidx.compose.material.icons.rounded.PauseCircle
import androidx.compose.material.icons.rounded.PlayCircle
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.Snooze
import androidx.compose.material.icons.rounded.Subscriptions
import androidx.compose.material.icons.rounded.ViewAgenda
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.paisalens.app.data.model.AccountProfile
import com.paisalens.app.data.model.BillReminder
import com.paisalens.app.data.model.CreditCardBill
import com.paisalens.app.data.model.PaymentReminderKind
import com.paisalens.app.data.model.PaymentReminderNotificationStyle
import com.paisalens.app.data.model.PaymentReminderOccurrence
import com.paisalens.app.data.model.PaymentReminderPriority
import com.paisalens.app.data.model.PaymentReminderRule
import com.paisalens.app.data.model.PaymentReminderSettings
import com.paisalens.app.data.model.PaymentReminderStatus
import com.paisalens.app.data.model.PaymentReminderTarget
import com.paisalens.app.data.model.PaymentReminderTargetType
import com.paisalens.app.data.model.buildPaymentReminderTargets
import com.paisalens.app.data.model.manualBillReminderTargetKey
import com.paisalens.app.data.model.resolvePaymentReminderOccurrences
import com.paisalens.app.ui.components.MoneyText
import com.paisalens.app.ui.components.PaisaCard
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

internal enum class PaymentReminderFilter(val label: String) {
    ALL("All"),
    TODAY("Today"),
    UPCOMING("Upcoming"),
    OVERDUE("Overdue"),
    SNOOZED("Snoozed"),
    PAUSED("Paused"),
}

internal enum class PaymentReminderViewMode(val label: String, val icon: ImageVector) {
    COMFORTABLE("Comfortable", Icons.Rounded.ViewAgenda),
    COMPACT("Compact", Icons.AutoMirrored.Rounded.ViewList),
    MONTH("By month", Icons.Rounded.CalendarMonth),
}

internal data class PaymentReminderListItem(
    val rule: PaymentReminderRule,
    val target: PaymentReminderTarget?,
    val occurrence: PaymentReminderOccurrence?,
) {
    val title: String
        get() = target?.title
            ?: occurrence?.title
            ?: rule.categoryLabel
            ?: rule.kind.label
}

internal fun buildPaymentReminderListItems(
    rules: List<PaymentReminderRule>,
    targets: List<PaymentReminderTarget>,
    occurrences: List<PaymentReminderOccurrence>,
    query: String,
    filter: PaymentReminderFilter,
    nowMillis: Long,
    systemZone: ZoneId = ZoneId.systemDefault(),
): List<PaymentReminderListItem> {
    val targetsByKey = targets.associateBy { it.targetType to it.targetKey }
    val occurrencesByRule = occurrences.associateBy(PaymentReminderOccurrence::ruleId)
    val normalizedQuery = query.trim().lowercase(Locale.ROOT)
    return rules.map { rule ->
        PaymentReminderListItem(
            rule = rule,
            target = targetsByKey[rule.targetType to rule.targetKey],
            occurrence = occurrencesByRule[rule.id],
        )
    }.filter { item ->
        normalizedQuery.isBlank() || listOfNotNull(
            item.title,
            item.target?.accountName,
            item.rule.categoryLabel,
            item.rule.kind.label,
            item.rule.notes,
        ).any { it.lowercase(Locale.ROOT).contains(normalizedQuery) }
    }.filter { item ->
        val occurrence = item.occurrence
        when (filter) {
            PaymentReminderFilter.ALL -> true
            PaymentReminderFilter.TODAY -> {
                val zone = runCatching { ZoneId.of(item.rule.zoneId) }.getOrDefault(systemZone)
                val ruleToday = Instant.ofEpochMilli(nowMillis).atZone(zone).toLocalDate()
                occurrence?.dueDateEpochDay == ruleToday.toEpochDay()
            }
            PaymentReminderFilter.UPCOMING -> occurrence?.status == PaymentReminderStatus.UPCOMING
            PaymentReminderFilter.OVERDUE -> occurrence?.status == PaymentReminderStatus.OVERDUE
            PaymentReminderFilter.SNOOZED -> occurrence?.status == PaymentReminderStatus.SNOOZED
            PaymentReminderFilter.PAUSED -> !item.rule.enabled
        }
    }.sortedWith(
        compareBy<PaymentReminderListItem> { it.occurrence?.dueAtMillis ?: Long.MAX_VALUE }
            .thenBy { it.title.lowercase(Locale.ROOT) },
    )
}

internal fun unconfiguredPaymentReminderTargets(
    rules: List<PaymentReminderRule>,
    targets: List<PaymentReminderTarget>,
): List<PaymentReminderTarget> {
    val configured = rules.mapTo(mutableSetOf()) { it.targetType to it.targetKey }
    return targets.filterNot { (it.targetType to it.targetKey) in configured }
}

internal fun groupPaymentReminderItemsByMonth(
    items: List<PaymentReminderListItem>,
): List<Pair<YearMonth?, List<PaymentReminderListItem>>> = items
    .groupBy { item -> item.occurrence?.dueDateEpochDay?.let { YearMonth.from(LocalDate.ofEpochDay(it)) } }
    .entries
    .sortedWith(compareBy<Map.Entry<YearMonth?, List<PaymentReminderListItem>>> { it.key == null }.thenBy { it.key })
    .map { it.key to it.value }

@Composable
fun PaymentReminderCenterContent(
    rules: List<PaymentReminderRule>,
    settings: PaymentReminderSettings,
    manualBills: List<BillReminder>,
    creditCardBills: List<CreditCardBill>,
    accounts: List<AccountProfile>,
    hasNotificationPermission: Boolean,
    onRequestNotificationPermission: () -> Unit,
    onOpenSystemNotificationSettings: () -> Unit,
    onAddManualBill: () -> Unit,
    onOpenTarget: (PaymentReminderTarget) -> Unit,
    onSaveRule: suspend (PaymentReminderRule) -> Unit,
    onDeleteRule: suspend (Long) -> Unit,
    onSnooze: suspend (ruleId: Long, occurrenceEpochDay: Long, minutes: Int, expectedGeneration: Long) -> Unit,
    onClearSnooze: suspend (ruleId: Long, expectedGeneration: Long) -> Unit,
    onSkipNext: suspend (ruleId: Long, occurrenceEpochDay: Long, expectedGeneration: Long) -> Unit,
    onSettingsChange: suspend (PaymentReminderSettings) -> Unit,
    modifier: Modifier = Modifier,
    requestedBillId: Long? = null,
    onReminderRequestHandled: () -> Unit = {},
) {
    val systemZone = remember { ZoneId.systemDefault() }
    var nowMillis by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(60_000L)
            nowMillis = System.currentTimeMillis()
        }
    }
    val today = remember(nowMillis, systemZone) {
        Instant.ofEpochMilli(nowMillis).atZone(systemZone).toLocalDate()
    }
    val accountNames = remember(accounts) { accounts.associate { it.id to it.name } }
    val targets = remember(manualBills, creditCardBills, accountNames, today) {
        buildPaymentReminderTargets(manualBills, creditCardBills, today).map { target ->
            val renamedAccount = target.accountId?.let(accountNames::get)?.takeIf(String::isNotBlank)
            target.copy(
                title = if (target.targetType == PaymentReminderTargetType.CREDIT_CARD) {
                    renamedAccount ?: target.title
                } else {
                    target.title
                },
                accountName = renamedAccount ?: target.accountName,
            )
        }
    }
    val occurrences = remember(rules, targets, manualBills, nowMillis, systemZone) {
        resolvePaymentReminderOccurrences(rules, targets, manualBills, nowMillis, systemZone)
    }
    var query by rememberSaveable { mutableStateOf("") }
    var filterName by rememberSaveable { mutableStateOf(PaymentReminderFilter.ALL.name) }
    var viewModeName by rememberSaveable { mutableStateOf(PaymentReminderViewMode.COMFORTABLE.name) }
    val filter = PaymentReminderFilter.entries.firstOrNull { it.name == filterName } ?: PaymentReminderFilter.ALL
    val viewMode = PaymentReminderViewMode.entries.firstOrNull { it.name == viewModeName }
        ?: PaymentReminderViewMode.COMFORTABLE
    val visibleItems = remember(rules, targets, occurrences, query, filter, nowMillis, systemZone) {
        buildPaymentReminderListItems(rules, targets, occurrences, query, filter, nowMillis, systemZone)
    }
    val unconfiguredTargets = remember(targets, rules) {
        unconfiguredPaymentReminderTargets(rules, targets)
    }
    var editorRule by remember { mutableStateOf<PaymentReminderRule?>(null) }
    var editorTarget by remember { mutableStateOf<PaymentReminderTarget?>(null) }
    var showEditor by remember { mutableStateOf(false) }
    LaunchedEffect(requestedBillId, targets, rules) {
        val billId = requestedBillId ?: return@LaunchedEffect
        val key = manualBillReminderTargetKey(billId)
        val target = targets.firstOrNull { it.targetKey == key } ?: return@LaunchedEffect
        editorTarget = target
        editorRule = rules.firstOrNull { it.targetKey == target.targetKey }
        showEditor = true
        onReminderRequestHandled()
    }
    var showSettings by remember { mutableStateOf(false) }
    var snoozing by remember { mutableStateOf<PaymentReminderListItem?>(null) }
    var deleting by remember { mutableStateOf<PaymentReminderListItem?>(null) }
    var skipping by remember { mutableStateOf<PaymentReminderListItem?>(null) }
    var actionInProgressId by remember { mutableStateOf<Long?>(null) }
    var actionError by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    fun runRuleAction(ruleId: Long, action: suspend () -> Unit) {
        if (actionInProgressId != null) return
        actionInProgressId = ruleId
        actionError = null
        scope.launch {
            runCatching { action() }
                .onFailure { actionError = it.message ?: "Could not update this reminder. Try again." }
            actionInProgressId = null
        }
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 18.dp, top = 12.dp, end = 18.dp, bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Payment reminders", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    Text(
                        "Custom alerts for utility and credit-card bills",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(
                    onClick = { showSettings = true },
                    modifier = Modifier.size(48.dp),
                ) {
                    Icon(Icons.Rounded.Settings, contentDescription = "Reminder settings")
                }
            }
        }
        if (!hasNotificationPermission) {
            item {
                Surface(
                    color = MaterialTheme.colorScheme.errorContainer,
                    contentColor = MaterialTheme.colorScheme.onErrorContainer,
                    shape = MaterialTheme.shapes.large,
                ) {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Rounded.NotificationsActive, contentDescription = null)
                            Text("Notifications are off", style = MaterialTheme.typography.titleMedium)
                        }
                        Text("Reminders are saved, but Android cannot show them until notification access is enabled.")
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = onRequestNotificationPermission, modifier = Modifier.heightIn(min = 48.dp)) {
                                Text("Allow notifications")
                            }
                            TextButton(onClick = onOpenSystemNotificationSettings, modifier = Modifier.heightIn(min = 48.dp)) {
                                Text("Android settings")
                            }
                        }
                    }
                }
            }
        }
        item {
            ReminderSummary(
                activeCount = rules.count(PaymentReminderRule::enabled),
                overdueCount = occurrences.count { it.status == PaymentReminderStatus.OVERDUE },
                snoozedCount = occurrences.count { it.status == PaymentReminderStatus.SNOOZED },
            )
        }
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = {
                        editorRule = null
                        editorTarget = unconfiguredTargets.firstOrNull()
                        showEditor = true
                    },
                    enabled = unconfiguredTargets.isNotEmpty(),
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                ) {
                    Icon(Icons.Rounded.AddAlert, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Set reminder")
                }
                OutlinedButton(onClick = onAddManualBill, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) {
                    Icon(Icons.Rounded.Event, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Add bill")
                }
            }
        }
        if (unconfiguredTargets.isNotEmpty()) {
            item {
                Text("READY TO CONFIGURE", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                LazyRow(
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(unconfiguredTargets, key = { "${it.targetType}:${it.targetKey}" }) { target ->
                        FilterChip(
                            selected = false,
                            onClick = {
                                editorRule = null
                                editorTarget = target
                                showEditor = true
                            },
                            modifier = Modifier.heightIn(min = 48.dp),
                            leadingIcon = { Icon(target.targetType.icon(), contentDescription = null) },
                            label = { Text("Set ${target.title}") },
                        )
                    }
                }
            }
        }
        item {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it.take(80) },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Search reminders") },
                leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
                singleLine = true,
            )
        }
        item {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(PaymentReminderFilter.entries, key = PaymentReminderFilter::name) { option ->
                    val selected = filter == option
                    FilterChip(
                        selected = selected,
                        onClick = { filterName = option.name },
                        modifier = Modifier.heightIn(min = 48.dp).semantics {
                            stateDescription = if (selected) "Selected" else "Not selected"
                        },
                        label = { Text(option.label) },
                    )
                }
            }
        }
        item {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(PaymentReminderViewMode.entries, key = PaymentReminderViewMode::name) { option ->
                    val selected = viewMode == option
                    FilterChip(
                        selected = selected,
                        onClick = { viewModeName = option.name },
                        modifier = Modifier.heightIn(min = 48.dp),
                        leadingIcon = { Icon(option.icon, contentDescription = null) },
                        label = { Text(option.label) },
                    )
                }
            }
        }
        actionError?.let { error ->
            item {
                Surface(
                    color = MaterialTheme.colorScheme.errorContainer,
                    contentColor = MaterialTheme.colorScheme.onErrorContainer,
                    shape = MaterialTheme.shapes.medium,
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(12.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Rounded.ErrorOutline, contentDescription = null)
                        Text(error, modifier = Modifier.weight(1f))
                        TextButton(onClick = { actionError = null }) { Text("Dismiss") }
                    }
                }
            }
        }
        if (visibleItems.isEmpty()) {
            item {
                ReminderEmptyState(
                    title = if (rules.isEmpty()) "No reminders yet" else "No reminders match",
                    detail = if (rules.isEmpty()) {
                        "Add a bill or choose a detected card statement, then set exactly when PaisaLens should remind you."
                    } else {
                        "Try another search or filter. Paused reminders stay available under Paused."
                    },
                )
            }
        } else if (viewMode == PaymentReminderViewMode.MONTH) {
            groupPaymentReminderItemsByMonth(visibleItems).forEach { (month, monthItems) ->
                item(key = "month:${month ?: "waiting"}") {
                    Text(
                        month?.format(DateTimeFormatter.ofPattern("MMMM uuuu", LocalConfiguration.current.locales[0]))
                            ?: "Waiting for next bill",
                        style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
                items(monthItems, key = { it.rule.id }) { item ->
                    PaymentReminderCard(
                        item = item,
                        compact = true,
                        overdueHighlightEnabled = settings.overdueHighlightEnabled,
                        busy = actionInProgressId != null,
                        nowMillis = nowMillis,
                        onOpenTarget = onOpenTarget,
                        onEdit = { editorRule = item.rule; editorTarget = item.target; showEditor = true },
                        onToggleEnabled = { enabled -> runRuleAction(item.rule.id) { onSaveRule(item.rule.copy(enabled = enabled)) } },
                        onSnooze = { snoozing = item },
                        onClearSnooze = {
                            runRuleAction(item.rule.id) { onClearSnooze(item.rule.id, item.rule.generation) }
                        },
                        onSkip = { skipping = item },
                        onDelete = { deleting = item },
                    )
                }
            }
        } else {
            items(visibleItems, key = { it.rule.id }) { item ->
                PaymentReminderCard(
                    item = item,
                    compact = viewMode == PaymentReminderViewMode.COMPACT,
                    overdueHighlightEnabled = settings.overdueHighlightEnabled,
                    busy = actionInProgressId != null,
                    nowMillis = nowMillis,
                    onOpenTarget = onOpenTarget,
                    onEdit = { editorRule = item.rule; editorTarget = item.target; showEditor = true },
                    onToggleEnabled = { enabled -> runRuleAction(item.rule.id) { onSaveRule(item.rule.copy(enabled = enabled)) } },
                    onSnooze = { snoozing = item },
                    onClearSnooze = {
                        runRuleAction(item.rule.id) { onClearSnooze(item.rule.id, item.rule.generation) }
                    },
                    onSkip = { skipping = item },
                    onDelete = { deleting = item },
                )
            }
        }
        item {
            Surface(
                color = MaterialTheme.colorScheme.secondaryContainer,
                contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                shape = MaterialTheme.shapes.large,
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Rounded.Lock, contentDescription = null)
                    Text(
                        "Schedules and bill details stay on this device. Amounts are hidden in notifications and widgets unless you opt in.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }
    }

    if (showEditor) {
        PaymentReminderEditorSheet(
            existing = editorRule,
            initialTarget = editorTarget,
            targets = if (editorRule == null) unconfiguredTargets else targets,
            settings = settings,
            onSave = onSaveRule,
            onDismiss = { showEditor = false; editorRule = null; editorTarget = null },
        )
    }
    if (showSettings) {
        PaymentReminderSettingsSheet(
            settings = settings,
            nowMillis = nowMillis,
            hasNotificationPermission = hasNotificationPermission,
            onRequestNotificationPermission = onRequestNotificationPermission,
            onSettingsChange = onSettingsChange,
            onDismiss = { showSettings = false },
        )
    }
    snoozing?.let { item ->
        PaymentReminderSnoozeDialog(
            title = item.title,
            options = item.rule.snoozeMinutes,
            busy = actionInProgressId != null,
            onSnooze = { minutes ->
                val occurrenceDay = item.occurrence?.dueDateEpochDay ?: return@PaymentReminderSnoozeDialog
                snoozing = null
                runRuleAction(item.rule.id) {
                    onSnooze(item.rule.id, occurrenceDay, minutes, item.rule.generation)
                }
            },
            onDismiss = { if (actionInProgressId == null) snoozing = null },
        )
    }
    deleting?.let { item ->
        AlertDialog(
            onDismissRequest = { if (actionInProgressId == null) deleting = null },
            title = { Text("Delete reminder?") },
            text = { Text("${item.title} will stop sending reminders. The bill itself will not be deleted.") },
            confirmButton = {
                Button(
                    enabled = actionInProgressId == null,
                    onClick = {
                        deleting = null
                        runRuleAction(item.rule.id) { onDeleteRule(item.rule.id) }
                    },
                ) { Text("Delete reminder") }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancel") } },
        )
    }
    skipping?.let { item ->
        AlertDialog(
            onDismissRequest = { if (actionInProgressId == null) skipping = null },
            title = { Text("Skip this bill reminder?") },
            text = {
                Text(
                    if (item.rule.targetType == PaymentReminderTargetType.CREDIT_CARD) {
                        "PaisaLens will wait for the next detected statement before reminding you again. The bill will stay unpaid."
                    } else {
                        "Only this due occurrence will be skipped. A recurring bill will continue with its next cycle."
                    },
                )
            },
            confirmButton = {
                Button(
                    enabled = actionInProgressId == null,
                    onClick = {
                        val occurrenceDay = item.occurrence?.dueDateEpochDay ?: return@Button
                        skipping = null
                        runRuleAction(item.rule.id) {
                            onSkipNext(item.rule.id, occurrenceDay, item.rule.generation)
                        }
                    },
                ) { Text("Skip once") }
            },
            dismissButton = { TextButton(onClick = { skipping = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun ReminderSummary(activeCount: Int, overdueCount: Int, snoozedCount: Int) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val stack = maxWidth < 340.dp || LocalDensity.current.fontScale >= 1.3f
        val values = listOf(
            Triple("Active", activeCount, Icons.Rounded.NotificationsActive),
            Triple("Overdue", overdueCount, Icons.Rounded.ErrorOutline),
            Triple("Snoozed", snoozedCount, Icons.Rounded.Snooze),
        )
        if (stack) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                values.forEach { (label, value, icon) -> ReminderMetric(label, value, icon, Modifier.fillMaxWidth()) }
            }
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                values.forEach { (label, value, icon) -> ReminderMetric(label, value, icon, Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
private fun ReminderMetric(label: String, value: Int, icon: ImageVector, modifier: Modifier) {
    PaisaCard(modifier) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
            Text(value.toString(), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun PaymentReminderCard(
    item: PaymentReminderListItem,
    compact: Boolean,
    overdueHighlightEnabled: Boolean,
    busy: Boolean,
    nowMillis: Long,
    onOpenTarget: (PaymentReminderTarget) -> Unit,
    onEdit: () -> Unit,
    onToggleEnabled: (Boolean) -> Unit,
    onSnooze: () -> Unit,
    onClearSnooze: () -> Unit,
    onSkip: () -> Unit,
    onDelete: () -> Unit,
) {
    var menuExpanded by remember { mutableStateOf(false) }
    val occurrence = item.occurrence
    val overdue = occurrence?.status == PaymentReminderStatus.OVERDUE
    val accent = remember(item.rule.colorHex) { parseReminderColor(item.rule.colorHex) }
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = if (overdue && overdueHighlightEnabled) MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.48f)
        else MaterialTheme.colorScheme.surfaceContainer,
        tonalElevation = 1.dp,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(if (compact) 12.dp else 16.dp),
            verticalArrangement = Arrangement.spacedBy(if (compact) 6.dp else 10.dp),
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier.size(44.dp).background(accent.copy(alpha = 0.18f), CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(item.rule.kind.icon(), contentDescription = null, tint = accent)
                }
                Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                    Text(
                        item.title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = if (compact) 1 else 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        buildString {
                            append(item.rule.kind.label)
                            item.rule.categoryLabel?.takeIf(String::isNotBlank)?.let { append(" · $it") }
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (occurrence != null && item.rule.showAmount) {
                    MoneyText(occurrence.amountMinor, style = MaterialTheme.typography.titleMedium)
                }
                Box {
                    IconButton(
                        enabled = !busy,
                        onClick = { menuExpanded = true },
                        modifier = Modifier.size(48.dp),
                    ) { Icon(Icons.Rounded.MoreVert, contentDescription = "More options for ${item.title}") }
                    DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                        DropdownMenuItem(
                            text = { Text("Edit reminder") },
                            leadingIcon = { Icon(Icons.Rounded.Edit, contentDescription = null) },
                            onClick = { menuExpanded = false; onEdit() },
                        )
                        DropdownMenuItem(
                            text = { Text(if (item.rule.enabled) "Pause reminder" else "Resume reminder") },
                            leadingIcon = {
                                Icon(if (item.rule.enabled) Icons.Rounded.PauseCircle else Icons.Rounded.PlayCircle, contentDescription = null)
                            },
                            onClick = { menuExpanded = false; onToggleEnabled(!item.rule.enabled) },
                        )
                        if (
                            occurrence != null &&
                            occurrence.status != PaymentReminderStatus.SKIPPED &&
                            item.rule.enabled
                        ) {
                            DropdownMenuItem(
                                text = { Text("Skip this occurrence") },
                                leadingIcon = { Icon(Icons.Rounded.SkipNext, contentDescription = null) },
                                onClick = { menuExpanded = false; onSkip() },
                            )
                        }
                        DropdownMenuItem(
                            text = { Text("Delete reminder") },
                            leadingIcon = { Icon(Icons.Rounded.DeleteOutline, contentDescription = null) },
                            onClick = { menuExpanded = false; onDelete() },
                        )
                    }
                }
            }
            ReminderStatusLine(item, nowMillis)
            if (!compact) {
                Text(
                    reminderScheduleSummary(item.rule),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                item.rule.notes?.takeIf(String::isNotBlank)?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (busy) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                    Text("Updating…", style = MaterialTheme.typography.bodySmall)
                }
            } else if (occurrence != null && item.rule.enabled) {
                BoxWithConstraints(Modifier.fillMaxWidth()) {
                    val stack = maxWidth < 310.dp || LocalDensity.current.fontScale >= 1.3f
                    ReminderQuickActions(
                        item = item,
                        onOpenTarget = onOpenTarget,
                        onSnooze = onSnooze,
                        onClearSnooze = onClearSnooze,
                        stacked = stack,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }
}

@Composable
private fun ReminderQuickActions(
    item: PaymentReminderListItem,
    onOpenTarget: (PaymentReminderTarget) -> Unit,
    onSnooze: () -> Unit,
    onClearSnooze: () -> Unit,
    stacked: Boolean,
    modifier: Modifier,
) {
    val snoozed = item.occurrence?.status == PaymentReminderStatus.SNOOZED
    val skipped = item.occurrence?.status == PaymentReminderStatus.SKIPPED
    @Composable
    fun SnoozeButton(buttonModifier: Modifier) {
        if (!skipped) {
            OutlinedButton(
                onClick = if (snoozed) onClearSnooze else onSnooze,
                modifier = buttonModifier.heightIn(min = 48.dp),
            ) {
                Icon(Icons.Rounded.Snooze, contentDescription = null)
                Spacer(Modifier.width(6.dp))
                Text(if (snoozed) "Clear snooze" else "Snooze")
            }
        }
    }
    @Composable
    fun ViewBillButton(buttonModifier: Modifier) {
        item.target?.let { target ->
            OutlinedButton(
                onClick = { onOpenTarget(target) },
                modifier = buttonModifier.heightIn(min = 48.dp),
            ) {
                Icon(target.targetType.icon(), contentDescription = null)
                Spacer(Modifier.width(6.dp))
                Text("View bill")
            }
        }
    }
    if (stacked) {
        Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SnoozeButton(Modifier.fillMaxWidth())
            ViewBillButton(Modifier.fillMaxWidth())
        }
    } else {
        Row(modifier, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SnoozeButton(Modifier.weight(1f))
            ViewBillButton(Modifier.weight(1f))
        }
    }
}

@Composable
private fun ReminderStatusLine(item: PaymentReminderListItem, nowMillis: Long) {
    val occurrence = item.occurrence
    val (icon, label, color) = when {
        !item.rule.enabled -> Triple(Icons.Rounded.PauseCircle, "Paused", MaterialTheme.colorScheme.onSurfaceVariant)
        occurrence == null -> Triple(Icons.Rounded.Schedule, "Waiting for the next bill", MaterialTheme.colorScheme.onSurfaceVariant)
        occurrence.status == PaymentReminderStatus.OVERDUE -> Triple(
            Icons.Rounded.ErrorOutline,
            "Overdue · due ${formatOccurrenceDate(occurrence)}",
            MaterialTheme.colorScheme.error,
        )
        occurrence.status == PaymentReminderStatus.DUE_TODAY -> Triple(
            Icons.Rounded.Event,
            "Due today · ${formatOccurrenceTime(occurrence, item.rule.zoneId)}",
            MaterialTheme.colorScheme.tertiary,
        )
        occurrence.status == PaymentReminderStatus.SNOOZED -> Triple(
            Icons.Rounded.Snooze,
            "Snoozed · due ${formatOccurrenceDate(occurrence)}",
            MaterialTheme.colorScheme.primary,
        )
        occurrence.status == PaymentReminderStatus.SKIPPED -> Triple(
            Icons.Rounded.SkipNext,
            "Skipped once",
            MaterialTheme.colorScheme.onSurfaceVariant,
        )
        else -> Triple(
            Icons.Rounded.Schedule,
            "Due ${formatOccurrenceDate(occurrence)} · ${relativeDueLabel(occurrence, nowMillis, item.rule.zoneId)}",
            MaterialTheme.colorScheme.primary,
        )
    }
    Row(
        modifier = Modifier.semantics(mergeDescendants = true) { contentDescription = label },
        horizontalArrangement = Arrangement.spacedBy(7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp), tint = color)
        Text(label, style = MaterialTheme.typography.bodyMedium, color = color, fontWeight = FontWeight.Medium)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PaymentReminderEditorSheet(
    existing: PaymentReminderRule?,
    initialTarget: PaymentReminderTarget?,
    targets: List<PaymentReminderTarget>,
    settings: PaymentReminderSettings,
    onSave: suspend (PaymentReminderRule) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val locale = LocalConfiguration.current.locales[0]
    val scope = rememberCoroutineScope()
    var selectedTargetKey by remember(existing, initialTarget) {
        mutableStateOf(existing?.let { it.targetType to it.targetKey } ?: initialTarget?.let { it.targetType to it.targetKey })
    }
    val selectedTarget = targets.firstOrNull { (it.targetType to it.targetKey) == selectedTargetKey }
        ?: initialTarget?.takeIf { (it.targetType to it.targetKey) == selectedTargetKey }
    var kind by remember(existing, selectedTarget) {
        mutableStateOf(existing?.kind ?: if (selectedTarget?.targetType == PaymentReminderTargetType.CREDIT_CARD) {
            PaymentReminderKind.CREDIT_CARD
        } else {
            PaymentReminderKind.UTILITY
        })
    }
    var enabled by remember(existing) { mutableStateOf(existing?.enabled ?: true) }
    var alertTime by remember(existing) { mutableIntStateOf(existing?.alertTimeMinutes ?: 9 * 60) }
    var zoneId by remember(existing) { mutableStateOf(existing?.zoneId ?: ZoneId.systemDefault().id) }
    var advanceDays by remember(existing, settings) {
        mutableStateOf((existing?.advanceDays ?: settings.defaultAdvanceDays).toSet())
    }
    var customAdvance by remember { mutableStateOf("") }
    var overdueRepeatDays by remember(existing) { mutableIntStateOf(existing?.overdueRepeatDays ?: 1) }
    var snoozeMinutes by remember(existing, settings) {
        mutableStateOf((existing?.snoozeMinutes ?: settings.defaultSnoozeMinutes).toSet())
    }
    var customSnooze by remember { mutableStateOf("") }
    var priority by remember(existing) { mutableStateOf(existing?.priority ?: PaymentReminderPriority.HIGH) }
    var notificationStyle by remember(existing) {
        mutableStateOf(existing?.notificationStyle ?: PaymentReminderNotificationStyle.STANDARD)
    }
    var showAmount by remember(existing) { mutableStateOf(existing?.showAmount ?: false) }
    var vibrate by remember(existing) { mutableStateOf(existing?.vibrate ?: true) }
    var category by remember(existing) { mutableStateOf(existing?.categoryLabel.orEmpty()) }
    var colorHex by remember(existing) { mutableStateOf(existing?.colorHex ?: reminderColors.first()) }
    var notes by remember(existing) { mutableStateOf(existing?.notes.orEmpty()) }
    var moreOptions by rememberSaveable { mutableStateOf(false) }
    var dirty by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var saveError by remember { mutableStateOf<String?>(null) }
    val customAdvanceValue = customAdvance.toIntOrNull()
    val customSnoozeValue = customSnooze.toIntOrNull()
    val advanceError = customAdvance.isNotBlank() && customAdvanceValue !in 0..3650
    val snoozeError = customSnooze.isNotBlank() && customSnoozeValue !in 1..43_200
    val targetError = selectedTargetKey == null
    val canSave = !saving && !targetError && !advanceError && !snoozeError &&
        (advanceDays.isNotEmpty() || customAdvanceValue != null) &&
        (snoozeMinutes.isNotEmpty() || customSnoozeValue != null)

    FinanceEditorDialog(dirty, saving, onDismiss) { requestClose ->
        LazyColumn(
            modifier = Modifier.fillMaxWidth().navigationBarsPadding(),
            contentPadding = PaddingValues(start = 20.dp, top = 4.dp, end = 20.dp, bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item {
                Text(if (existing == null) "Set payment reminder" else "Edit payment reminder", style = MaterialTheme.typography.headlineSmall)
                Text(
                    "The bill remains the source of truth for its amount, due date, and paid status.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            item {
                ReminderEditorSection("BILL") {
                    if (targets.isEmpty() && selectedTarget == null) {
                        Text("Add a utility bill or wait for a credit-card statement before creating a reminder.")
                    } else {
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(targets, key = { "${it.targetType}:${it.targetKey}" }) { target ->
                                val selected = selectedTargetKey == (target.targetType to target.targetKey)
                                FilterChip(
                                    selected = selected,
                                    enabled = existing == null,
                                    onClick = {
                                        selectedTargetKey = target.targetType to target.targetKey
                                        kind = if (target.targetType == PaymentReminderTargetType.CREDIT_CARD) {
                                            PaymentReminderKind.CREDIT_CARD
                                        } else {
                                            PaymentReminderKind.UTILITY
                                        }
                                        dirty = true
                                    },
                                    modifier = Modifier.heightIn(min = 48.dp),
                                    leadingIcon = { Icon(target.targetType.icon(), contentDescription = null) },
                                    label = { Text(target.title) },
                                )
                            }
                        }
                        selectedTarget?.let { target ->
                            Text(
                                buildString {
                                    append("Due ${LocalDate.ofEpochDay(target.dueDateEpochDay).format(DateTimeFormatter.ofPattern("d MMM uuuu", locale))}")
                                    target.accountName?.takeIf(String::isNotBlank)?.let { append(" · $it") }
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
            item {
                ReminderEditorSection("BILL TYPE") {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(PaymentReminderKind.entries, key = PaymentReminderKind::name) { option ->
                            FilterChip(
                                selected = kind == option,
                                onClick = { kind = option; dirty = true },
                                modifier = Modifier.heightIn(min = 48.dp),
                                leadingIcon = { Icon(option.icon(), contentDescription = null) },
                                label = { Text(option.label) },
                            )
                        }
                    }
                }
            }
            item {
                ReminderEditorSection("REMINDER TIME") {
                    ReminderTimePicker(
                        context = context,
                        minutes = alertTime,
                        onMinutesChange = { alertTime = it; dirty = true },
                    )
                    Text(
                        "Saved timezone: $zoneId. Travel does not silently shift the chosen time.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    val currentZoneId = ZoneId.systemDefault().id
                    if (zoneId != currentZoneId) {
                        OutlinedButton(
                            onClick = { zoneId = currentZoneId; dirty = true },
                            modifier = Modifier.heightIn(min = 48.dp),
                        ) {
                            Text("Use current timezone ($currentZoneId)")
                        }
                    }
                }
            }
            item {
                ReminderEditorSection("REMIND ME BEFORE") {
                    MultiSelectIntChips(
                        values = listOf(0, 1, 3, 7, 14),
                        selected = advanceDays,
                        label = ::advanceDayLabel,
                        contentDescriptionSuffix = "advance reminder",
                        onToggle = { value ->
                            advanceDays = if (value in advanceDays) advanceDays - value else advanceDays + value
                            dirty = true
                        },
                    )
                    OutlinedTextField(
                        value = customAdvance,
                        onValueChange = { value -> if (value.all(Char::isDigit) && value.length <= 4) customAdvance = value; dirty = true },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Custom days before (optional)") },
                        supportingText = { Text(if (advanceError) "Enter 0 to 3650 days." else "Added alongside the selected reminders.") },
                        isError = advanceError,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                    )
                }
            }
            item {
                ReminderEditorSection("SNOOZE OPTIONS") {
                    MultiSelectIntChips(
                        values = listOf(10, 30, 60, 180, 1_440),
                        selected = snoozeMinutes,
                        label = ::snoozeLabel,
                        contentDescriptionSuffix = "snooze option",
                        onToggle = { value ->
                            snoozeMinutes = if (value in snoozeMinutes) snoozeMinutes - value else snoozeMinutes + value
                            dirty = true
                        },
                    )
                    OutlinedTextField(
                        value = customSnooze,
                        onValueChange = { value -> if (value.all(Char::isDigit) && value.length <= 5) customSnooze = value; dirty = true },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Custom snooze minutes (optional)") },
                        supportingText = { Text(if (snoozeError) "Enter 1 to 43200 minutes." else "Up to 30 days.") },
                        isError = snoozeError,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                    )
                }
            }
            item {
                TextButton(onClick = { moreOptions = !moreOptions }, modifier = Modifier.heightIn(min = 48.dp)) {
                    Icon(Icons.Rounded.Settings, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(if (moreOptions) "Hide more options" else "More options")
                }
            }
            if (moreOptions) {
                item {
                    ReminderEditorSection("OVERDUE REPEAT") {
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(listOf(0, 1, 3, 7)) { days ->
                                FilterChip(
                                    selected = overdueRepeatDays == days,
                                    onClick = { overdueRepeatDays = days; dirty = true },
                                    modifier = Modifier.heightIn(min = 48.dp),
                                    label = { Text(if (days == 0) "Off" else "Every $days day${if (days == 1) "" else "s"}") },
                                )
                            }
                        }
                    }
                }
                item {
                    ReminderEditorSection("PRIORITY") {
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(PaymentReminderPriority.entries, key = PaymentReminderPriority::name) { option ->
                                FilterChip(
                                    selected = priority == option,
                                    onClick = { priority = option; dirty = true },
                                    modifier = Modifier.heightIn(min = 48.dp),
                                    label = { Text(option.label) },
                                )
                            }
                        }
                    }
                }
                item {
                    ReminderEditorSection("NOTIFICATION") {
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(PaymentReminderNotificationStyle.entries, key = PaymentReminderNotificationStyle::name) { option ->
                                FilterChip(
                                    selected = notificationStyle == option,
                                    onClick = {
                                        notificationStyle = option
                                        if (option == PaymentReminderNotificationStyle.SILENT) vibrate = false
                                        dirty = true
                                    },
                                    modifier = Modifier.heightIn(min = 48.dp),
                                    label = { Text(option.label) },
                                )
                            }
                        }
                        if (notificationStyle == PaymentReminderNotificationStyle.SILENT) {
                            Text(
                                "Silent reminders do not vibrate.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        } else {
                            ReminderToggleRow(
                                title = "Vibrate",
                                detail = "Android notification settings can still override vibration.",
                                checked = vibrate,
                                onCheckedChange = { vibrate = it; dirty = true },
                            )
                        }
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        ReminderToggleRow(
                            title = "Show amount in private alert",
                            detail = "Always hidden on the public lock screen and while App lock or Privacy mode is on.",
                            checked = showAmount,
                            onCheckedChange = { showAmount = it; dirty = true },
                        )
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        ReminderToggleRow(
                            title = "Reminder enabled",
                            detail = "Paused reminders keep their customization without sending alerts.",
                            checked = enabled,
                            onCheckedChange = { enabled = it; dirty = true },
                        )
                    }
                }
                item {
                    ReminderEditorSection("CATEGORY & COLOR") {
                        OutlinedTextField(
                            value = category,
                            onValueChange = { category = it.take(48); dirty = true },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("Category label (optional)") },
                            singleLine = true,
                        )
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            items(reminderColors, key = { it }) { option ->
                                val selected = colorHex == option
                                FilterChip(
                                    selected = selected,
                                    onClick = { colorHex = option; dirty = true },
                                    modifier = Modifier.heightIn(min = 48.dp).semantics {
                                        contentDescription = "Reminder color ${reminderColorName(option)}"
                                        stateDescription = if (selected) "Selected" else "Not selected"
                                    },
                                    leadingIcon = {
                                        Box(Modifier.size(18.dp).background(parseReminderColor(option), CircleShape))
                                    },
                                    label = { Text(reminderColorName(option)) },
                                )
                            }
                        }
                    }
                }
                item {
                    OutlinedTextField(
                        value = notes,
                        onValueChange = { notes = it.take(240); dirty = true },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Private notes (optional)") },
                        supportingText = { Text("Stored only on this device; never shown on a public lock screen.") },
                        minLines = 2,
                    )
                }
            }
            saveError?.let { error ->
                item {
                    Surface(
                        color = MaterialTheme.colorScheme.errorContainer,
                        contentColor = MaterialTheme.colorScheme.onErrorContainer,
                        shape = MaterialTheme.shapes.medium,
                    ) {
                        Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Icon(Icons.Rounded.ErrorOutline, contentDescription = null)
                            Text(error, modifier = Modifier.weight(1f))
                        }
                    }
                }
            }
            item {
                Button(
                    enabled = canSave,
                    onClick = {
                        val targetKey = selectedTargetKey ?: return@Button
                        val finalAdvanceDays = (advanceDays + listOfNotNull(customAdvanceValue)).toList()
                        val finalSnoozeMinutes = (snoozeMinutes + listOfNotNull(customSnoozeValue)).toList()
                        val now = System.currentTimeMillis()
                        val rule = PaymentReminderRule(
                            id = existing?.id ?: 0L,
                            targetType = targetKey.first,
                            targetKey = targetKey.second,
                            kind = kind,
                            enabled = enabled,
                            alertTimeMinutes = alertTime,
                            zoneId = zoneId,
                            advanceDays = finalAdvanceDays,
                            overdueRepeatDays = overdueRepeatDays,
                            snoozeMinutes = finalSnoozeMinutes,
                            priority = priority,
                            notificationStyle = notificationStyle,
                            showAmount = showAmount,
                            vibrate = vibrate,
                            categoryLabel = category.trim().takeIf(String::isNotBlank),
                            colorHex = colorHex,
                            notes = notes.trim().takeIf(String::isNotBlank),
                            skippedOccurrenceEpochDay = existing?.skippedOccurrenceEpochDay,
                            snoozedOccurrenceEpochDay = existing?.snoozedOccurrenceEpochDay,
                            snoozedUntilMillis = existing?.snoozedUntilMillis,
                            generation = existing?.generation ?: 0L,
                            createdAt = existing?.createdAt?.takeIf { it > 0L } ?: now,
                            updatedAt = now,
                        ).normalized()
                        saving = true
                        saveError = null
                        scope.launch {
                            runCatching { onSave(rule) }
                                .onSuccess { onDismiss() }
                                .onFailure { saveError = it.message ?: "Could not save this reminder. Your form is still here." }
                            saving = false
                        }
                    },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                ) {
                    if (saving) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                        Text("Saving…")
                    } else {
                        Text("Save reminder")
                    }
                }
                TextButton(onClick = requestClose, enabled = !saving, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                    Text("Cancel")
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PaymentReminderSettingsSheet(
    settings: PaymentReminderSettings,
    nowMillis: Long,
    hasNotificationPermission: Boolean,
    onRequestNotificationPermission: () -> Unit,
    onSettingsChange: suspend (PaymentReminderSettings) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val original = remember { settings.normalized() }
    var draft by remember { mutableStateOf(original) }
    var saving by remember { mutableStateOf(false) }
    var saveError by remember { mutableStateOf<String?>(null) }
    val safe = draft.normalized()
    FinanceEditorDialog(safe != original, saving, onDismiss) { requestClose ->
        LazyColumn(
            modifier = Modifier.fillMaxWidth().navigationBarsPadding(),
            contentPadding = PaddingValues(start = 20.dp, top = 4.dp, end = 20.dp, bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item {
                Text("Payment reminder settings", style = MaterialTheme.typography.headlineSmall)
                Text(
                    "Private defaults for bill alerts, quiet hours, summaries, and overdue cues.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            item {
                PaymentReminderDeliveryCheck(settings, nowMillis, onRequestNotificationPermission)
            }
            item {
                ReminderToggleRow(
                    title = "Enable payment reminders",
                    detail = "Turn every payment reminder on or off without deleting customizations.",
                    checked = safe.enabled,
                    onCheckedChange = { enabled ->
                        draft = safe.copy(enabled = enabled)
                        if (enabled && !hasNotificationPermission) onRequestNotificationPermission()
                    },
                )
            }
            item {
                ReminderEditorSection("QUIET HOURS") {
                    ReminderToggleRow(
                        title = "Defer alerts during quiet hours",
                        detail = if (safe.quietHoursEnabled) {
                            "Alerts wait until ${formatMinutes(context, safe.quietHoursEndMinutes)}."
                        } else {
                            "Allow alerts near their chosen time. Android may defer delivery to conserve battery."
                        },
                        checked = safe.quietHoursEnabled,
                        onCheckedChange = { draft = safe.copy(quietHoursEnabled = it) },
                    )
                    if (safe.quietHoursEnabled) {
                        Text("Starts", style = MaterialTheme.typography.labelLarge)
                        ReminderTimePicker(
                            context = context,
                            minutes = safe.quietHoursStartMinutes,
                            onMinutesChange = { draft = safe.copy(quietHoursStartMinutes = it) },
                        )
                        Text("Ends", style = MaterialTheme.typography.labelLarge)
                        ReminderTimePicker(
                            context = context,
                            minutes = safe.quietHoursEndMinutes,
                            onMinutesChange = { draft = safe.copy(quietHoursEndMinutes = it) },
                        )
                        if (safe.quietHoursStartMinutes == safe.quietHoursEndMinutes) {
                            Text(
                                "Start and end match, so quiet hours are inactive. Choose different times.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                }
            }
            item {
                ReminderEditorSection("DAILY SUMMARY") {
                    ReminderToggleRow(
                        title = "Morning bill summary",
                        detail = if (safe.dailySummaryEnabled) {
                            "One private summary near ${formatMinutes(context, safe.summaryTimeMinutes)}."
                        } else {
                            "Optional daily overview of due and overdue bills."
                        },
                        checked = safe.dailySummaryEnabled,
                        onCheckedChange = { enabled ->
                            draft = safe.copy(dailySummaryEnabled = enabled)
                            if (enabled && !hasNotificationPermission) onRequestNotificationPermission()
                        },
                    )
                    if (safe.dailySummaryEnabled) {
                        ReminderTimePicker(
                            context = context,
                            minutes = safe.summaryTimeMinutes,
                            onMinutesChange = { draft = safe.copy(summaryTimeMinutes = it) },
                        )
                    }
                }
            }
            item {
                ReminderEditorSection("NEW REMINDER DEFAULTS") {
                    Text("Advance alerts", style = MaterialTheme.typography.labelLarge)
                    MultiSelectIntChips(
                        values = listOf(0, 1, 3, 7, 14),
                        selected = safe.defaultAdvanceDays.toSet(),
                        label = ::advanceDayLabel,
                        contentDescriptionSuffix = "default advance reminder",
                        onToggle = { value ->
                            val next = if (value in safe.defaultAdvanceDays) safe.defaultAdvanceDays - value else safe.defaultAdvanceDays + value
                            if (next.isNotEmpty()) draft = safe.copy(defaultAdvanceDays = next)
                        },
                    )
                    Text("Snooze choices", style = MaterialTheme.typography.labelLarge)
                    MultiSelectIntChips(
                        values = listOf(10, 30, 60, 180, 1_440),
                        selected = safe.defaultSnoozeMinutes.toSet(),
                        label = ::snoozeLabel,
                        contentDescriptionSuffix = "default snooze option",
                        onToggle = { value ->
                            val next = if (value in safe.defaultSnoozeMinutes) safe.defaultSnoozeMinutes - value else safe.defaultSnoozeMinutes + value
                            if (next.isNotEmpty()) draft = safe.copy(defaultSnoozeMinutes = next)
                        },
                    )
                }
            }
            item {
                ReminderToggleRow(
                    title = "Highlight overdue reminders",
                    detail = "Uses a warning icon and text as well as color, so status never depends on color alone.",
                    checked = safe.overdueHighlightEnabled,
                    onCheckedChange = { draft = safe.copy(overdueHighlightEnabled = it) },
                )
            }
            saveError?.let { error ->
                item {
                    Surface(
                        color = MaterialTheme.colorScheme.errorContainer,
                        contentColor = MaterialTheme.colorScheme.onErrorContainer,
                        shape = MaterialTheme.shapes.medium,
                    ) {
                        Text(error, modifier = Modifier.fillMaxWidth().padding(12.dp))
                    }
                }
            }
            item {
                Button(
                    enabled = !saving,
                    onClick = {
                        saving = true
                        saveError = null
                        scope.launch {
                            runCatching { onSettingsChange(safe) }
                                .onSuccess { onDismiss() }
                                .onFailure {
                                    saveError = it.message ?: "Could not save reminder settings. Try again."
                                }
                            saving = false
                        }
                    },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                ) {
                    if (saving) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                        Text("Saving…")
                    } else {
                        Text("Save settings")
                    }
                }
                TextButton(
                    onClick = requestClose,
                    enabled = !saving,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                ) {
                    Text("Cancel")
                }
            }
        }
    }
}

@Composable
private fun ReminderEditorSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(title, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        content()
    }
}

@Composable
private fun ReminderToggleRow(
    title: String,
    detail: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .toggleable(value = checked, role = Role.Switch, onValueChange = onCheckedChange)
            .semantics {
                contentDescription = title
                stateDescription = if (checked) "On" else "Off"
            }
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = null)
    }
}

@Composable
private fun ReminderTimePicker(context: Context, minutes: Int, onMinutesChange: (Int) -> Unit) {
    Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainer) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(
                onClick = { onMinutesChange((minutes - 15 + 1_440) % 1_440) },
                modifier = Modifier.size(48.dp),
            ) { Text("−", style = MaterialTheme.typography.headlineSmall) }
            Column(
                modifier = Modifier.weight(1f).semantics(mergeDescendants = true) {
                    contentDescription = "Reminder time ${formatMinutes(context, minutes)}"
                },
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Icon(Icons.Rounded.Schedule, contentDescription = null)
                Text(formatMinutes(context, minutes), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                Text("15-minute steps", style = MaterialTheme.typography.bodySmall)
            }
            IconButton(
                onClick = { onMinutesChange((minutes + 15) % 1_440) },
                modifier = Modifier.size(48.dp),
            ) { Text("+", style = MaterialTheme.typography.headlineSmall) }
        }
    }
}

@Composable
private fun MultiSelectIntChips(
    values: List<Int>,
    selected: Set<Int>,
    label: (Int) -> String,
    contentDescriptionSuffix: String,
    onToggle: (Int) -> Unit,
) {
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items(values) { value ->
            val checked = value in selected
            FilterChip(
                selected = checked,
                onClick = { onToggle(value) },
                modifier = Modifier.heightIn(min = 48.dp).semantics {
                    contentDescription = "${label(value)} $contentDescriptionSuffix"
                    stateDescription = if (checked) "Selected" else "Not selected"
                },
                label = { Text(label(value)) },
            )
        }
    }
}

@Composable
private fun PaymentReminderSnoozeDialog(
    title: String,
    options: List<Int>,
    busy: Boolean,
    onSnooze: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Snooze $title") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                options.distinct().sorted().forEach { minutes ->
                    OutlinedButton(
                        enabled = !busy,
                        onClick = { onSnooze(minutes) },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                    ) { Text(snoozeLabel(minutes)) }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text("Cancel") } },
    )
}

@Composable
private fun ReminderEmptyState(title: String, detail: String) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainer, shape = MaterialTheme.shapes.large) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(7.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(Icons.Rounded.AddAlert, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(detail, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private fun PaymentReminderTargetType.icon(): ImageVector = when (this) {
    PaymentReminderTargetType.MANUAL_BILL -> Icons.Rounded.Event
    PaymentReminderTargetType.CREDIT_CARD -> Icons.Rounded.CreditCard
}

private fun PaymentReminderKind.icon(): ImageVector = when (this) {
    PaymentReminderKind.CREDIT_CARD -> Icons.Rounded.CreditCard
    PaymentReminderKind.UTILITY -> Icons.Rounded.ElectricBolt
    PaymentReminderKind.EMI_LOAN -> Icons.Rounded.Event
    PaymentReminderKind.SUBSCRIPTION -> Icons.Rounded.Subscriptions
    PaymentReminderKind.OTHER -> Icons.Rounded.NotificationsActive
}

private val PaymentReminderKind.label: String
    get() = when (this) {
        PaymentReminderKind.CREDIT_CARD -> "Credit card"
        PaymentReminderKind.UTILITY -> "Utility"
        PaymentReminderKind.EMI_LOAN -> "EMI or loan"
        PaymentReminderKind.SUBSCRIPTION -> "Subscription"
        PaymentReminderKind.OTHER -> "Other bill"
    }

private val PaymentReminderPriority.label: String
    get() = name.lowercase(Locale.ROOT).replaceFirstChar(Char::titlecase)

private val PaymentReminderNotificationStyle.label: String
    get() = when (this) {
        PaymentReminderNotificationStyle.STANDARD -> "Standard"
        PaymentReminderNotificationStyle.PROMINENT -> "Prominent"
        PaymentReminderNotificationStyle.SILENT -> "Silent"
    }

private fun reminderScheduleSummary(rule: PaymentReminderRule): String = buildString {
    append(rule.advanceDays.sortedDescending().joinToString { advanceDayLabel(it) })
    append(" · ")
    append(rule.alertTime.format(DateTimeFormatter.ofPattern("HH:mm")))
    if (rule.overdueRepeatDays > 0) append(" · repeats overdue every ${rule.overdueRepeatDays}d")
}

private fun formatOccurrenceDate(occurrence: PaymentReminderOccurrence): String =
    LocalDate.ofEpochDay(occurrence.dueDateEpochDay).format(DateTimeFormatter.ofPattern("d MMM uuuu"))

private fun formatOccurrenceTime(occurrence: PaymentReminderOccurrence, zoneId: String): String =
    Instant.ofEpochMilli(occurrence.dueAtMillis).atZone(safeReminderZone(zoneId)).toLocalTime()
        .format(DateTimeFormatter.ofPattern("HH:mm"))

private fun relativeDueLabel(occurrence: PaymentReminderOccurrence, nowMillis: Long, zoneId: String): String {
    val zone = safeReminderZone(zoneId)
    val today = Instant.ofEpochMilli(nowMillis).atZone(zone).toLocalDate()
    val due = LocalDate.ofEpochDay(occurrence.dueDateEpochDay)
    val days = java.time.temporal.ChronoUnit.DAYS.between(today, due)
    return when (days) {
        0L -> "today"
        1L -> "tomorrow"
        else -> "in $days days"
    }
}

private fun safeReminderZone(zoneId: String): ZoneId = runCatching { ZoneId.of(zoneId) }
    .getOrDefault(ZoneId.systemDefault())

private fun formatMinutes(context: Context, minutes: Int): String {
    val time = LocalTime.of((minutes.coerceIn(0, 1_439)) / 60, minutes.coerceIn(0, 1_439) % 60)
    val pattern = if (DateFormat.is24HourFormat(context)) "HH:mm" else "h:mm a"
    return time.format(DateTimeFormatter.ofPattern(pattern, context.resources.configuration.locales[0]))
}

private fun advanceDayLabel(days: Int): String = when (days) {
    0 -> "On due day"
    1 -> "1 day"
    else -> "$days days"
}

private fun snoozeLabel(minutes: Int): String = when {
    minutes % 1_440 == 0 -> "${minutes / 1_440} day${if (minutes == 1_440) "" else "s"}"
    minutes % 60 == 0 -> "${minutes / 60} hour${if (minutes == 60) "" else "s"}"
    else -> "$minutes minutes"
}

private val reminderColors = listOf("#FF8A4C", "#4F9DFF", "#A978F8", "#35B985", "#E55E72")

private fun reminderColorName(colorHex: String): String = when (colorHex) {
    "#FF8A4C" -> "Orange"
    "#4F9DFF" -> "Blue"
    "#A978F8" -> "Purple"
    "#35B985" -> "Green"
    "#E55E72" -> "Rose"
    else -> "Custom"
}

private fun parseReminderColor(value: String): Color = runCatching { Color(AndroidColor.parseColor(value)) }
    .getOrDefault(Color(0xFFFF8A4C))
