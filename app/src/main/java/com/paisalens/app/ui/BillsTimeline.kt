package com.paisalens.app.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.paisalens.app.data.model.BillReminder
import com.paisalens.app.data.model.BillTimelineEntry
import com.paisalens.app.data.model.BillTimelineSource
import com.paisalens.app.data.model.BillsTimelineSnapshot
import com.paisalens.app.data.model.DueStatus
import com.paisalens.app.data.model.PaymentReminderRule
import com.paisalens.app.data.model.UtilitySmsBill
import com.paisalens.app.data.model.manualBillReminderTargetKey
import com.paisalens.app.ui.components.MoneyText
import com.paisalens.app.ui.components.dashboardWidth
import com.paisalens.app.ui.components.timelineIcon
import com.paisalens.app.ui.screens.dateLabel
import java.time.format.DateTimeFormatter

private enum class BillAction { MARK_PAID, DELETE, DISMISS_NOTICE }

@Composable
internal fun BillsTimelineContent(
    timeline: BillsTimelineSnapshot,
    bills: List<BillReminder>,
    utilitySmsBills: List<UtilitySmsBill>,
    paymentReminderRules: List<PaymentReminderRule>,
    onAdd: () -> Unit,
    onEdit: (BillReminder) -> Unit,
    onMarkPaid: (Long) -> Unit,
    onDelete: (Long) -> Unit,
    onTrackUtilitySmsBill: (UtilitySmsBill, (Long?) -> Unit) -> Unit,
    onDismissUtilitySmsBill: (Long) -> Unit,
    onSetReminder: (String) -> Unit,
    onMarkCardPaid: (Long) -> Unit,
    onOpenCardBills: () -> Unit,
    onOpenCommitments: () -> Unit,
) {
    var showPaid by rememberSaveable { mutableStateOf(false) }
    var showPastNotices by rememberSaveable { mutableStateOf(false) }
    var expandedKey by rememberSaveable { mutableStateOf<String?>(null) }
    var trackingNotice by remember { mutableStateOf<Long?>(null) }
    var pendingAction by remember { mutableStateOf<Pair<BillAction, String>?>(null) }
    val manualById = remember(bills) { bills.associateBy(BillReminder::id) }
    val utilityById = remember(utilitySmsBills) { utilitySmsBills.associateBy(UtilitySmsBill::id) }
    val reminderKeys = remember(paymentReminderRules) { paymentReminderRules.mapTo(hashSetOf(), PaymentReminderRule::targetKey) }
    val entries = if (showPaid) timeline.paid else timeline.upcoming

    val toggle: (String) -> Unit = { key -> expandedKey = key.takeUnless { it == expandedKey } }
    val confirm: (BillAction, BillTimelineEntry) -> Unit = { action, entry -> pendingAction = action to entry.key }
    val trackNotice: (UtilitySmsBill) -> Unit = { bill ->
        trackingNotice = bill.id
        onTrackUtilitySmsBill(bill) { id ->
            trackingNotice = null
            if (id != null && id > 0L) onSetReminder(manualBillReminderTargetKey(id))
        }
    }

    LazyColumn(
        modifier = Modifier.dashboardWidth(920.dp).fillMaxSize(),
        contentPadding = PaddingValues(start = 14.dp, top = 12.dp, end = 14.dp, bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item(key = "bills-summary") { BillsWeekSummary(timeline) }
        item(key = "bills-tabs") {
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                listOf("Upcoming (${timeline.upcoming.size})", "Paid").forEachIndexed { index, label ->
                    SegmentedButton(
                        selected = showPaid == (index == 1),
                        onClick = {
                            showPaid = index == 1
                            expandedKey = null
                        },
                        shape = SegmentedButtonDefaults.itemShape(index, 2),
                        colors = SegmentedButtonDefaults.colors(
                            activeContainerColor = MaterialTheme.colorScheme.primary,
                            activeContentColor = MaterialTheme.colorScheme.onPrimary,
                        ),
                        label = { Text(label, textAlign = TextAlign.Center, fontWeight = FontWeight.SemiBold) },
                    )
                }
            }
        }
        if (entries.isEmpty()) {
            item(key = "bills-empty") {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                    shape = MaterialTheme.shapes.large,
                ) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(if (showPaid) "No paid bills yet" else "No upcoming bills", style = MaterialTheme.typography.titleMedium)
                        Text(
                            if (showPaid) "Bills move here when you mark them paid or confirm a matching card payment."
                            else "Add a bill or sync SMS to find card statements and utility bills.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
        itemsIndexed(entries, key = { _, entry -> entry.key }) { index, entry ->
            BillTimelineRow(
                entry = entry,
                expanded = expandedKey == entry.key,
                first = index == 0,
                last = index == entries.lastIndex,
                onToggle = { toggle(entry.key) },
            ) {
                val manual = entry.manualBillId?.let(manualById::get)
                val notice = entry.utilityBillId?.let(utilityById::get)
                BillDetails(
                    entry = entry,
                    manual = manual,
                    notice = notice,
                    reminderConfigured = entry.reminderTargetKey in reminderKeys,
                    noticeActionEnabled = trackingNotice == null,
                    onSetReminder = onSetReminder,
                    onTrackNotice = trackNotice,
                    onEdit = onEdit,
                    onConfirm = { confirm(it, entry) },
                    onOpenCardBills = onOpenCardBills,
                    onOpenCommitments = onOpenCommitments,
                )
            }
        }
        if (!showPaid) {
            item(key = "bill-actions") {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedButton(
                        onClick = onAdd,
                        modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                    ) {
                        Icon(Icons.Rounded.Add, contentDescription = null, modifier = Modifier.padding(end = 4.dp))
                        Text("Add bill")
                    }
                    if (timeline.pastSmsNotices.isNotEmpty()) {
                        OutlinedButton(
                            onClick = { showPastNotices = !showPastNotices },
                            modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                        ) {
                            Text(
                                if (showPastNotices) "Hide past notices" else "Past SMS (${timeline.pastSmsNotices.size})",
                                textAlign = TextAlign.Center,
                            )
                        }
                    }
                }
            }
            if (showPastNotices) {
                itemsIndexed(timeline.pastSmsNotices, key = { _, entry -> entry.key }) { index, entry ->
                    BillTimelineRow(
                        entry = entry,
                        expanded = expandedKey == entry.key,
                        first = index == 0,
                        last = index == timeline.pastSmsNotices.lastIndex,
                        onToggle = { toggle(entry.key) },
                    ) {
                        BillDetails(
                            entry = entry,
                            manual = null,
                            notice = utilityById[entry.utilityBillId],
                            reminderConfigured = false,
                            noticeActionEnabled = trackingNotice == null,
                            onSetReminder = onSetReminder,
                            onTrackNotice = trackNotice,
                            onEdit = onEdit,
                            onConfirm = { confirm(it, entry) },
                            onOpenCardBills = onOpenCardBills,
                            onOpenCommitments = onOpenCommitments,
                        )
                    }
                }
            }
        }
    }

    pendingAction?.let { (action, entryKey) ->
        val entry = (timeline.upcoming.asSequence() + timeline.pastSmsNotices.asSequence())
            .firstOrNull { it.key == entryKey }
        if (entry != null) {
            val (title, message, buttonLabel) = when (action) {
                BillAction.MARK_PAID -> Triple("Mark bill paid?", "This records the payment for ${entry.title}. It doesn’t send money.", "Mark paid")
                BillAction.DELETE -> Triple("Delete ${entry.title}?", "This removes the bill and its reminder.", "Delete")
                BillAction.DISMISS_NOTICE -> Triple("Dismiss this SMS bill?", "Repeat notices for this bill won’t add it back. This won’t delete the SMS.", "Dismiss bill")
            }
            AlertDialog(
                onDismissRequest = { pendingAction = null },
                title = { Text(title) },
                text = { Text(message) },
                confirmButton = {
                    TextButton(onClick = {
                        when (action) {
                            BillAction.MARK_PAID -> {
                                entry.manualBillId?.let(onMarkPaid)
                                entry.creditCardBillId?.let(onMarkCardPaid)
                            }
                            BillAction.DELETE -> entry.manualBillId?.let(onDelete)
                            BillAction.DISMISS_NOTICE -> entry.utilityBillId?.let(onDismissUtilitySmsBill)
                        }
                        pendingAction = null
                    }) { Text(buttonLabel) }
                },
                dismissButton = { TextButton(onClick = { pendingAction = null }) { Text("Cancel") } },
            )
        }
    }
}

@Composable
private fun BillsWeekSummary(timeline: BillsTimelineSnapshot) {
    val scheme = MaterialTheme.colorScheme
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = scheme.primaryContainer,
        contentColor = scheme.onPrimaryContainer,
    ) {
        BoxWithConstraints(Modifier.padding(horizontal = 18.dp, vertical = 16.dp)) {
            val narrow = maxWidth < 265.dp || LocalDensity.current.fontScale > 1.35f
            val total: @Composable () -> Unit = {
                Column {
                    Text("Due this week", style = MaterialTheme.typography.titleSmall)
                    MoneyText(timeline.dueThisWeekMinor, style = MaterialTheme.typography.headlineLarge, color = scheme.onPrimaryContainer)
                }
            }
            val period: @Composable () -> Unit = {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Rounded.CalendarMonth, contentDescription = null)
                    Text(timeline.week.dateLabel(), style = MaterialTheme.typography.bodyMedium)
                }
            }
            if (narrow) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    total()
                    period()
                }
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.weight(1f)) { total() }
                    Box(Modifier.weight(1f)) { period() }
                }
            }
        }
    }
}

@Composable
private fun BillTimelineRow(
    entry: BillTimelineEntry,
    expanded: Boolean,
    first: Boolean,
    last: Boolean,
    onToggle: () -> Unit,
    details: @Composable () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val paid = entry.historyDateLabel != null
    val urgent = entry.status == DueStatus.DUE_TODAY || entry.status == DueStatus.DUE_SOON
    val highlight = urgent && (expanded || entry.status == DueStatus.DUE_TODAY)
    val accent = when {
        entry.status == DueStatus.OVERDUE -> scheme.error
        highlight -> scheme.secondary
        else -> scheme.outline
    }
    val fullDate = entry.date.format(DateTimeFormatter.ofPattern("EEE, d MMM uuuu"))
    val scale = LocalDensity.current.fontScale
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val dateWidth = if (maxWidth < 350.dp) 48.dp else 52.dp
        val rail = scale <= 1.35f && maxWidth >= 290.dp
        val stackAmount = maxWidth < 330.dp || scale > 1.35f
        val amountWidth = maxWidth * 0.27f
        val rowModifier = if (rail) Modifier.drawBehind {
            val fromStart = (dateWidth + 13.dp).toPx()
            val x = if (layoutDirection == LayoutDirection.Ltr) fromStart else size.width - fromStart
            val y = 27.dp.toPx()
            drawLine(
                scheme.outlineVariant,
                Offset(x, if (first) y else 0f),
                Offset(x, if (last) y else size.height + 12.dp.toPx()),
                strokeWidth = 1.dp.toPx(),
            )
        } else Modifier
        Row(
            modifier = Modifier.fillMaxWidth().then(rowModifier),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (rail) {
                Surface(
                    color = if (highlight) scheme.secondaryContainer else scheme.surfaceContainerHigh,
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier.width(dateWidth),
                ) {
                    Column(
                        modifier = Modifier.padding(vertical = 4.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(entry.date.format(DateTimeFormatter.ofPattern("EEE")), style = MaterialTheme.typography.labelSmall)
                        Text(entry.date.dayOfMonth.toString(), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                        Text(entry.date.format(DateTimeFormatter.ofPattern("MMM")), style = MaterialTheme.typography.labelSmall)
                    }
                }
                Box(Modifier.width(14.dp).padding(top = 20.dp), contentAlignment = Alignment.Center) {
                    Surface(
                        modifier = Modifier.size(14.dp),
                        shape = CircleShape,
                        color = scheme.background,
                        border = BorderStroke(2.dp, accent),
                    ) {}
                }
            }
            Surface(
                modifier = Modifier.weight(1f),
                shape = MaterialTheme.shapes.large,
                color = if (paid) scheme.surfaceContainerLow else scheme.surfaceContainerHigh,
                border = BorderStroke(1.dp, if (expanded) scheme.primary.copy(alpha = 0.5f) else scheme.outlineVariant),
            ) {
                Column {
                    Surface(
                        onClick = onToggle,
                        color = Color.Transparent,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)
                            .semantics { stateDescription = if (expanded) "Expanded" else "Collapsed" },
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 10.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.Top,
                        ) {
                            Surface(color = scheme.primaryContainer, contentColor = scheme.onPrimaryContainer, shape = CircleShape) {
                                Icon(entry.timelineIcon(), contentDescription = null, modifier = Modifier.padding(8.dp).size(20.dp))
                            }
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(entry.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                                entry.utilityKind?.let { kind ->
                                    Text(
                                        kind.label + entry.utilityAccountLastFour?.let { " · ••$it" }.orEmpty(),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = scheme.onSurfaceVariant,
                                    )
                                }
                                Text(
                                    "${entry.historyDateLabel ?: "Due"} $fullDate",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = scheme.onSurfaceVariant,
                                )
                                if (entry.status == DueStatus.OVERDUE || entry.status == DueStatus.DUE_TODAY ||
                                    (expanded && entry.status == DueStatus.DUE_SOON)
                                ) {
                                    val overdue = entry.status == DueStatus.OVERDUE
                                    Surface(
                                        color = if (overdue) scheme.errorContainer else scheme.tertiary,
                                        contentColor = if (overdue) scheme.onErrorContainer else scheme.onTertiary,
                                        shape = CircleShape,
                                    ) {
                                        Text(
                                            when (entry.status) {
                                                DueStatus.OVERDUE -> "Overdue"
                                                DueStatus.DUE_TODAY -> "Due today"
                                                else -> "Due soon"
                                            },
                                            modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp),
                                            style = MaterialTheme.typography.labelMedium,
                                            fontWeight = FontWeight.SemiBold,
                                        )
                                    }
                                }
                                if (stackAmount) {
                                    MoneyText(entry.amountMinor, style = MaterialTheme.typography.titleSmall)
                                }
                            }
                            if (!stackAmount) {
                                MoneyText(
                                    entry.amountMinor,
                                    modifier = Modifier.widthIn(max = amountWidth),
                                    style = MaterialTheme.typography.titleSmall,
                                    color = if (paid) scheme.onSurfaceVariant else scheme.onSurface,
                                )
                            }
                            Icon(
                                if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                                contentDescription = null,
                                modifier = Modifier.size(20.dp),
                            )
                        }
                    }
                    AnimatedVisibility(expanded) {
                        Column(Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
                            HorizontalDivider(color = scheme.outlineVariant, modifier = Modifier.padding(bottom = 8.dp))
                            details()
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun BillDetails(
    entry: BillTimelineEntry,
    manual: BillReminder?,
    notice: UtilitySmsBill?,
    reminderConfigured: Boolean,
    noticeActionEnabled: Boolean,
    onSetReminder: (String) -> Unit,
    onTrackNotice: (UtilitySmsBill) -> Unit,
    onEdit: (BillReminder) -> Unit,
    onConfirm: (BillAction) -> Unit,
    onOpenCardBills: () -> Unit,
    onOpenCommitments: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    var showActions by remember { mutableStateOf(false) }
    val reminderAvailable = notice != null || entry.reminderTargetKey != null
    val actions = buildList<Pair<String, () -> Unit>> {
        if (manual != null || (entry.creditCardBillId != null && entry.status != null)) {
            add("Mark paid" to { onConfirm(BillAction.MARK_PAID) })
        }
        if (manual != null) {
            add("Edit bill" to { onEdit(manual) })
            add("Delete bill" to { onConfirm(BillAction.DELETE) })
        }
        if (notice != null && noticeActionEnabled) add("Dismiss notice" to { onConfirm(BillAction.DISMISS_NOTICE) })
        if (entry.creditCardBillId != null) add("Card statements" to onOpenCardBills)
        if (entry.source == BillTimelineSource.COMMITMENT) add("Open AutoPay" to onOpenCommitments)
    }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (notice != null || !reminderAvailable) {
            Text(
                if (notice != null) "Detected by SMS · review before tracking" else entry.source.label,
                style = MaterialTheme.typography.labelMedium,
                color = scheme.onSurfaceVariant,
            )
        }
        entry.accountName?.takeIf(String::isNotBlank)?.let { Text("Pay from $it", style = MaterialTheme.typography.bodySmall) }
        entry.notes?.takeIf(String::isNotBlank)?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        if (entry.smsCount > 1) Text("${entry.smsCount} SMS notices combined", style = MaterialTheme.typography.bodySmall)
        if (reminderAvailable) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Button(
                    onClick = {
                        if (notice != null) onTrackNotice(notice) else entry.reminderTargetKey?.let(onSetReminder)
                    },
                    enabled = notice == null || noticeActionEnabled,
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = scheme.secondary,
                        contentColor = scheme.onSecondary,
                    ),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp),
                ) {
                    Icon(Icons.Rounded.Notifications, contentDescription = null, modifier = Modifier.padding(end = 5.dp).size(20.dp))
                    Text(
                        when {
                            notice != null -> "Add & set reminder"
                            reminderConfigured -> "Edit reminder"
                            else -> "Set reminder"
                        },
                        textAlign = TextAlign.Center,
                    )
                }
                if (actions.isNotEmpty()) {
                    Box {
                        IconButton(onClick = { showActions = true }) {
                            Icon(Icons.Rounded.MoreVert, contentDescription = "More options for ${entry.title}")
                        }
                        DropdownMenu(expanded = showActions, onDismissRequest = { showActions = false }) {
                            actions.forEach { (label, action) ->
                                DropdownMenuItem(
                                    text = { Text(label) },
                                    onClick = {
                                        showActions = false
                                        action()
                                    },
                                )
                            }
                        }
                    }
                }
            }
        } else {
            actions.forEach { (label, action) ->
                FilledTonalButton(onClick = action, modifier = Modifier.heightIn(min = 48.dp)) { Text(label) }
            }
        }
    }
}
