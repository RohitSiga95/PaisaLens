package com.paisalens.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.scrollBy
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.ReceiptLong
import androidx.compose.material.icons.rounded.AccountBalance
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.CreditCard
import androidx.compose.material.icons.rounded.DragIndicator
import androidx.compose.material.icons.rounded.Insights
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.Payments
import androidx.compose.material.icons.rounded.PieChartOutline
import androidx.compose.material.icons.rounded.Savings
import androidx.compose.material.icons.rounded.Today
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.zIndex
import com.paisalens.app.data.model.DashboardWeek
import com.paisalens.app.data.model.HomeDashboardDensity
import com.paisalens.app.data.model.HomeDashboardPreset
import com.paisalens.app.data.model.HomeHeroMetric
import com.paisalens.app.data.model.HomeLayoutConfiguration
import com.paisalens.app.data.model.HomeModule
import com.paisalens.app.ui.components.dashboardWidth
import com.paisalens.app.ui.screens.dateLabel
import java.time.LocalDate
import java.time.format.DateTimeFormatter

@Composable
fun HomeCustomizationSheet(
    configuration: HomeLayoutConfiguration,
    today: LocalDate,
    onConfigurationChange: (HomeLayoutConfiguration) -> Unit,
    onDismiss: () -> Unit,
) {
    val saved = configuration.toStorageString()
    var draftKey by rememberSaveable(saved) { mutableStateOf(saved) }
    val draft = remember(draftKey) { HomeLayoutConfiguration.fromStorageString(draftKey) }
    val visible = draft.orderedVisibleModules
    val ordered = visible + HomeModule.customizationOrder.filterNot(visible::contains)
    val listState = rememberLazyListState()
    val scheme = MaterialTheme.colorScheme
    var dragged by remember { mutableStateOf<HomeModule?>(null) }
    var dragCenter by remember { mutableFloatStateOf(0f) }
    val edge = with(LocalDensity.current) { 52.dp.toPx() }

    fun setDraft(value: HomeLayoutConfiguration) { draftKey = value.toStorageString() }
    fun move(module: HomeModule, to: Int) {
        val latest = HomeLayoutConfiguration.fromStorageString(draftKey)
        setDraft(latest.move(module, to))
    }
    fun updateDropTarget(module: HomeModule) {
        val current = listState.visibleModuleCenter(module) ?: return
        val order = HomeLayoutConfiguration.fromStorageString(draftKey).orderedVisibleModules
        val byId = order.associateBy(HomeModule::storageId)
        val visibleCenters = listState.layoutInfo.visibleItemsInfo.mapNotNull { info ->
            val target = byId[info.key] ?: return@mapNotNull null
            if (target == module) null else target to (info.offset + info.size / 2f)
        }
        val target = if (dragCenter > current) {
            visibleCenters.filter { (_, center) -> center > current && center <= dragCenter }
                .maxByOrNull { it.second }
        } else {
            visibleCenters.filter { (_, center) -> center < current && center >= dragCenter }
                .minByOrNull { it.second }
        }
        target?.let { move(module, order.indexOf(it.first)) }
    }

    LaunchedEffect(dragged, edge) {
        val module = dragged ?: return@LaunchedEffect
        while (true) {
            withFrameNanos { }
            val bounds = listState.layoutInfo
            val fromTop = dragCenter - bounds.viewportStartOffset
            val fromBottom = bounds.viewportEndOffset - dragCenter
            val scroll = when {
                fromTop < edge && listState.canScrollBackward -> -(edge - fromTop)
                fromBottom < edge && listState.canScrollForward -> edge - fromBottom
                else -> 0f
            }.coerceIn(-edge, edge)
            if (scroll != 0f) {
                listState.scrollBy(scroll * 0.16f)
                updateDropTarget(module)
            }
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(Modifier.fillMaxSize(), color = scheme.background) {
            Column(Modifier.dashboardWidth(820.dp).fillMaxSize()) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = onDismiss) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back without saving") }
                    Text("PaisaLens", Modifier.weight(1f).padding(start = 6.dp), style = MaterialTheme.typography.titleLarge)
                    HomeLayoutOptions(draft, ::setDraft)
                }
                LazyColumn(
                    state = listState,
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    contentPadding = PaddingValues(start = 16.dp, top = 12.dp, end = 16.dp, bottom = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    item(key = "home-layout-title") {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(
                                "Customize Home",
                                style = MaterialTheme.typography.headlineMedium,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.semantics { heading() },
                            )
                            Text("Choose what shows on Home and in what order.", color = scheme.onSurfaceVariant)
                        }
                    }
                    item(key = "home-layout-date") { HomeLayoutDate(today) }
                    item(key = "home-layout-density") {
                        Column(
                            Modifier.padding(top = 4.dp, bottom = 8.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Text("Density", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                            Text("Control how much fits on Home.", color = scheme.onSurfaceVariant)
                            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                                val options = listOf(HomeDashboardDensity.COMFORTABLE, HomeDashboardDensity.COMPACT)
                                options.forEachIndexed { index, density ->
                                    SegmentedButton(
                                        selected = draft.density == density,
                                        onClick = { setDraft(draft.copy(density = density)) },
                                        shape = SegmentedButtonDefaults.itemShape(index, options.size),
                                        label = { Text(density.label, textAlign = TextAlign.Center) },
                                    )
                                }
                            }
                        }
                    }
                    item(key = "home-layout-modules") {
                        HorizontalDivider(Modifier.padding(bottom = 12.dp), color = scheme.outlineVariant)
                        Text("Dashboard modules", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                        Text(
                            "Hold and drag a handle to reorder. Tap Shown or Hidden to change what’s visible.",
                            style = MaterialTheme.typography.bodySmall,
                            color = scheme.onSurfaceVariant,
                        )
                    }
                    items(ordered, key = HomeModule::storageId, contentType = { "home-module" }) { module ->
                        val index = visible.indexOf(module)
                        val shown = index >= 0
                        HomeModuleEditorRow(
                            module = module,
                            shown = shown,
                            canToggle = !shown || visible.size > 1,
                            canMoveUp = index > 0,
                            canMoveDown = shown && index < visible.lastIndex,
                            listState = listState,
                            dragging = dragged == module,
                            getDragCenter = { dragCenter },
                            onToggle = { setDraft(draft.withVisibility(module, !shown)) },
                            onMoveUp = { move(module, index - 1) },
                            onMoveDown = { move(module, index + 1) },
                            onDragStart = {
                                listState.visibleModuleCenter(module)?.let { center ->
                                    dragCenter = center
                                    dragged = module
                                }
                            },
                            onDrag = { dy ->
                                if (dragged == module) {
                                    dragCenter += dy
                                    updateDropTarget(module)
                                }
                            },
                            onDragEnd = { if (dragged == module) dragged = null },
                        )
                    }
                }
                Surface(color = scheme.background) {
                    Column(
                        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Button(
                            onClick = {
                                if (draftKey != saved) onConfigurationChange(draft)
                                onDismiss()
                            },
                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                        ) { Text("Save layout") }
                        TextButton(
                            onClick = { setDraft(HomeLayoutConfiguration()) },
                            modifier = Modifier.heightIn(min = 48.dp),
                            enabled = draft != HomeLayoutConfiguration(),
                        ) { Text("Reset layout") }
                    }
                }
            }
        }
    }
}

private fun LazyListState.visibleModuleCenter(module: HomeModule): Float? =
    layoutInfo.visibleItemsInfo.firstOrNull { it.key == module.storageId }?.let { it.offset + it.size / 2f }

@Composable
private fun HomeLayoutDate(today: LocalDate) {
    val scheme = MaterialTheme.colorScheme
    val week = remember(today) { DashboardWeek.containing(today) }
    Surface(color = scheme.primaryContainer, contentColor = scheme.onPrimaryContainer, shape = MaterialTheme.shapes.large) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(Icons.Rounded.CalendarMonth, contentDescription = null)
            Column(Modifier.weight(1f)) {
                Text(today.format(DateTimeFormatter.ofPattern("EEE, d MMM uuuu")), style = MaterialTheme.typography.titleMedium)
                Text("Current week · ${week.dateLabel()}", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun HomeLayoutOptions(
    draft: HomeLayoutConfiguration,
    onUpdate: (HomeLayoutConfiguration) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) { Icon(Icons.Rounded.MoreVert, "Layout presets and hero metric") }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            Text("Layout presets", Modifier.padding(horizontal = 12.dp, vertical = 4.dp), style = MaterialTheme.typography.labelLarge)
            HomeDashboardPreset.entries.forEach { preset ->
                DropdownMenuItem(
                    text = { Text(preset.label) },
                    trailingIcon = if (draft.matchingPreset() == preset) {
                        { Icon(Icons.Rounded.Check, contentDescription = "Selected") }
                    } else null,
                    onClick = {
                        onUpdate(HomeLayoutConfiguration.forPreset(preset, draft.density, draft.heroMetric))
                        open = false
                    },
                )
            }
            HorizontalDivider()
            Text("Hero metric", Modifier.padding(horizontal = 12.dp, vertical = 4.dp), style = MaterialTheme.typography.labelLarge)
            HomeHeroMetric.entries.forEach { metric ->
                DropdownMenuItem(
                    text = { Text(metric.label) },
                    trailingIcon = if (draft.heroMetric == metric) {
                        { Icon(Icons.Rounded.Check, contentDescription = "Selected") }
                    } else null,
                    onClick = { onUpdate(draft.copy(heroMetric = metric)); open = false },
                )
            }
        }
    }
}

@Composable
private fun HomeModuleEditorRow(
    module: HomeModule,
    shown: Boolean,
    canToggle: Boolean,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    listState: LazyListState,
    dragging: Boolean,
    getDragCenter: () -> Float,
    onToggle: () -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onDragStart: () -> Unit,
    onDrag: (Float) -> Unit,
    onDragEnd: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val startDrag by rememberUpdatedState(onDragStart)
    val moveDrag by rememberUpdatedState(onDrag)
    val endDrag by rememberUpdatedState(onDragEnd)
    var reorderMenu by remember { mutableStateOf(false) }
    val canMove = canMoveUp || canMoveDown
    val getCenter by rememberUpdatedState(getDragCenter)
    val dragModifier = if (dragging) {
        Modifier.zIndex(1f).graphicsLayer {
            translationY = listState.visibleModuleCenter(module)?.let { getCenter() - it } ?: 0f
        }.shadow(6.dp, MaterialTheme.shapes.medium)
    } else Modifier
    val visibility: @Composable () -> Unit = {
        Surface(
            onClick = onToggle,
            enabled = canToggle,
            shape = CircleShape,
            color = if (shown) scheme.secondaryContainer else scheme.surfaceContainerHigh,
            contentColor = if (shown) scheme.onSecondaryContainer else scheme.onSurfaceVariant,
            modifier = Modifier.heightIn(min = 48.dp).semantics {
                contentDescription = if (shown) "Hide ${module.label}" else "Show ${module.label}"
                stateDescription = if (shown) "Shown" else "Hidden"
            },
        ) {
            Row(
                Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(if (shown) Icons.Rounded.Check else Icons.Rounded.VisibilityOff, null, Modifier.size(16.dp))
                Text(if (shown) "Shown" else "Hidden", style = MaterialTheme.typography.labelMedium)
            }
        }
    }

    Surface(
        modifier = Modifier.fillMaxWidth().then(dragModifier),
        color = if (dragging) scheme.surfaceContainerHighest else scheme.surface,
        shape = MaterialTheme.shapes.medium,
        border = BorderStroke(if (dragging) 2.dp else 1.dp, if (dragging) scheme.primary else scheme.outlineVariant),
    ) {
        BoxWithConstraints(Modifier.fillMaxWidth().padding(end = 8.dp, top = 7.dp, bottom = 7.dp)) {
            val stacked = maxWidth < 296.dp || LocalDensity.current.fontScale > 1.35f
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Box {
                    IconButton(
                        onClick = { reorderMenu = true },
                        enabled = canMove,
                        modifier = Modifier.semantics {
                            customActions = buildList {
                                if (canMoveUp) add(CustomAccessibilityAction("Move ${module.label} up") { onMoveUp(); true })
                                if (canMoveDown) add(CustomAccessibilityAction("Move ${module.label} down") { onMoveDown(); true })
                            }
                        }.then(
                            if (canMove) Modifier.pointerInput(module) {
                                detectDragGesturesAfterLongPress(
                                    onDragStart = { startDrag() },
                                    onDragCancel = { endDrag() },
                                    onDragEnd = { endDrag() },
                                    onDrag = { change, delta ->
                                        change.consume()
                                        moveDrag(delta.y)
                                    },
                                )
                            } else Modifier,
                        ),
                    ) { Icon(Icons.Rounded.DragIndicator, "Reorder ${module.label}") }
                    DropdownMenu(expanded = reorderMenu, onDismissRequest = { reorderMenu = false }) {
                        DropdownMenuItem(
                            text = { Text("Move up") },
                            leadingIcon = { Icon(Icons.Rounded.ArrowUpward, null) },
                            enabled = canMoveUp,
                            onClick = { onMoveUp(); reorderMenu = false },
                        )
                        DropdownMenuItem(
                            text = { Text("Move down") },
                            leadingIcon = { Icon(Icons.Rounded.ArrowDownward, null) },
                            enabled = canMoveDown,
                            onClick = { onMoveDown(); reorderMenu = false },
                        )
                    }
                }
                if (!stacked) {
                    Surface(shape = MaterialTheme.shapes.small, color = scheme.primaryContainer, contentColor = scheme.onPrimaryContainer) {
                        Icon(module.icon(), contentDescription = null, modifier = Modifier.padding(7.dp).size(20.dp))
                    }
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(module.label, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                    Text(module.description(), style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
                    if (stacked) visibility()
                }
                if (!stacked) visibility()
            }
        }
    }
}

private fun HomeModule.icon(): ImageVector = when (this) {
    HomeModule.DAILY_CHECK_IN, HomeModule.MONEY_TIMELINE -> Icons.Rounded.Today
    HomeModule.UPCOMING_BILLS, HomeModule.UPCOMING_COMMITMENTS -> Icons.AutoMirrored.Rounded.ReceiptLong
    HomeModule.NEEDS_ATTENTION -> Icons.Rounded.Notifications
    HomeModule.BANK_BALANCES -> Icons.Rounded.AccountBalance
    HomeModule.CARD_HEALTH, HomeModule.CREDIT_AVAILABLE -> Icons.Rounded.CreditCard
    HomeModule.BUDGET_PACE, HomeModule.SPENDING_BREAKDOWN -> Icons.Rounded.PieChartOutline
    HomeModule.SAVINGS_GOALS -> Icons.Rounded.Savings
    HomeModule.FINANCIAL_PULSE -> Icons.Rounded.Insights
    HomeModule.MONTHLY_SPEND, HomeModule.SPEND_OVERVIEW -> Icons.Rounded.Payments
}

private fun HomeModule.description(): String = when (this) {
    HomeModule.DAILY_CHECK_IN -> "Spending, bills, and items to review."
    HomeModule.UPCOMING_BILLS -> "Card and utility bills due this week."
    HomeModule.FINANCIAL_PULSE -> "A snapshot of available cash and planned spending."
    HomeModule.NEEDS_ATTENTION -> "Transactions and payments to check."
    HomeModule.MONEY_TIMELINE -> "Expected income and outgoings."
    HomeModule.BUDGET_PACE -> "How your spending compares with your budget."
    HomeModule.CARD_HEALTH -> "Card dues and credit usage."
    HomeModule.MONTHLY_SPEND -> "Net spending for the selected month."
    HomeModule.SPEND_OVERVIEW -> "Expenses, refunds, and income."
    HomeModule.SPENDING_BREAKDOWN -> "See where your money went."
    HomeModule.BANK_BALANCES -> "Latest balances seen from your accounts."
    HomeModule.CREDIT_AVAILABLE -> "Available limits on your cards."
    HomeModule.SAVINGS_GOALS -> "Progress toward goals and savings."
    HomeModule.UPCOMING_COMMITMENTS -> "Upcoming subscriptions and UPI AutoPay."
}
