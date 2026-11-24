package com.paisalens.app.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.paisalens.app.data.model.AccountBalanceDay
import com.paisalens.app.ui.privacy.PrivacyModeRuntime
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt
import kotlin.math.roundToLong

@Composable
internal fun DailyBalanceChart(
    days: List<AccountBalanceDay>,
    selectedDate: LocalDate,
    valueLabel: String,
    onSelectDate: (LocalDate) -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val known = remember(days) { days.mapNotNull(AccountBalanceDay::amountMinor) }
    val dateFormat = remember { DateTimeFormatter.ofPattern("d MMM") }
    if (PrivacyModeRuntime.active || known.isEmpty()) {
        Surface(color = scheme.surfaceContainerLow, shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    if (PrivacyModeRuntime.active) "Chart hidden in Privacy mode" else "No balance readings in this range",
                    style = MaterialTheme.typography.titleSmall,
                )
                if (!PrivacyModeRuntime.active) {
                    Text(
                        "PaisaLens draws the chart as balance updates are saved. Unknown dates aren’t shown as zero.",
                        style = MaterialTheme.typography.bodySmall,
                        color = scheme.onSurfaceVariant,
                    )
                }
            }
        }
        return
    }
    val select by rememberUpdatedState(onSelectDate)
    val selection = days.indexOfFirst { it.date == selectedDate }.takeIf { it >= 0 } ?: days.lastIndex
    val chosen = days[selection]
    val minValue = known.min().toDouble()
    val maxValue = known.max().toDouble()
    val padding = ((maxValue - minValue) * 0.08).coerceAtLeast(100.0)
    val floor = minValue - padding
    val ceiling = maxValue + padding
    val caption = when {
        chosen.amountMinor == null -> "No saved reading yet"
        !chosen.allReadingsFromThisDay -> "Includes earlier readings"
        else -> "Saved reading"
    }
    val accent = scheme.primary
    val gridColor = scheme.outlineVariant
    val firstKnown = days.indexOfFirst { it.amountMinor != null }
    val calendarSpan = days.lastIndex.coerceAtLeast(1)
    val chartFill = accent.copy(alpha = 0.20f)
    val carriedColor = accent.copy(alpha = 0.62f)

    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Surface(
            modifier = Modifier.align(if (selection > days.lastIndex / 2) Alignment.End else Alignment.Start),
            color = scheme.primaryContainer,
            contentColor = scheme.onPrimaryContainer,
            shape = MaterialTheme.shapes.medium,
        ) {
            Column(Modifier.padding(horizontal = 12.dp, vertical = 7.dp)) {
                Text(
                    chosen.date.format(dateFormat) + " · " + (chosen.amountMinor?.let(::formatMoney) ?: "—"),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(caption, style = MaterialTheme.typography.labelSmall)
            }
        }
        // Financial time charts keep calendar order left-to-right; the surrounding UI still mirrors.
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                val scale = LocalDensity.current.fontScale
                val denseLabels = maxWidth >= 350.dp && scale < 1.4f
                val tickCount = when {
                    maxWidth >= 620.dp && denseLabels -> 7
                    denseLabels -> 5
                    maxWidth >= 280.dp && scale < 1.4f -> 3
                    else -> 2
                }
                val plotHeight = if (maxWidth >= 600.dp) 230.dp else 164.dp
                val labelPadding = if (denseLabels) 54.dp else 50.dp
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row {
                        Column(
                            modifier = Modifier.height(plotHeight).widthIn(min = labelPadding).padding(end = 5.dp),
                            verticalArrangement = Arrangement.SpaceBetween,
                        ) {
                            repeat(4) { index ->
                                val tick = (ceiling - (ceiling - floor) * index / 3).roundToLong()
                                Text(formatCompactMoney(tick), style = MaterialTheme.typography.labelSmall)
                            }
                        }
                        Canvas(
                            modifier = Modifier.weight(1f).height(plotHeight).semantics {
                                contentDescription = "$valueLabel chart. ${chosen.date.format(dateFormat)}. " +
                                    (chosen.amountMinor?.let(::formatMoney) ?: "No saved reading") + ". $caption."
                                customActions = buildList {
                                    if (selection > 0) add(CustomAccessibilityAction("Previous day") {
                                        select(days[selection - 1].date)
                                        true
                                    })
                                    if (selection < days.lastIndex) add(CustomAccessibilityAction("Next day") {
                                        select(days[selection + 1].date)
                                        true
                                    })
                                }
                            }.pointerInput(days) {
                                detectTapGestures { point ->
                                    val inset = 5.dp.toPx()
                                    val index = ((point.x - inset) / (size.width - 2 * inset).coerceAtLeast(1f) * calendarSpan)
                                        .roundToInt().coerceIn(days.indices)
                                    select(days[index].date)
                                }
                            }.pointerInput(days) {
                                detectHorizontalDragGestures { change, _ ->
                                    change.consume()
                                    val inset = 5.dp.toPx()
                                    val index = ((change.position.x - inset) / (size.width - 2 * inset).coerceAtLeast(1f) * calendarSpan)
                                        .roundToInt().coerceIn(days.indices)
                                    select(days[index].date)
                                }
                            },
                        ) {
                            val inset = 5.dp.toPx()
                            val plotWidth = (size.width - inset * 2).coerceAtLeast(1f)
                            val plotHeightPx = (size.height - inset * 2).coerceAtLeast(1f)
                            fun x(index: Int) = inset + plotWidth * index / calendarSpan
                            fun y(value: Long) = inset + plotHeightPx *
                                (1.0 - (value.toDouble() - floor) / (ceiling - floor)).toFloat()
                            val baseline = size.height - inset
                            val dashed = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 4.dp.toPx()))
                            repeat(4) { index ->
                                val gridY = inset + plotHeightPx * index / 3f
                                drawLine(gridColor, Offset(inset, gridY), Offset(size.width, gridY), pathEffect = dashed)
                            }
                            val firstAmount = checkNotNull(days[firstKnown].amountMinor)
                            val area = Path().apply {
                                moveTo(x(firstKnown), baseline)
                                lineTo(x(firstKnown), y(firstAmount))
                            }
                            val held = Path().apply { moveTo(x(firstKnown), y(firstAmount)) }
                            val observedChanges = Path()
                            var previous = firstAmount
                            for (i in (firstKnown + 1)..days.lastIndex) {
                                val amount = days[i].amountMinor ?: continue
                                val previousY = y(previous)
                                val nextY = y(amount)
                                held.lineTo(x(i), previousY)
                                if (previous != amount) {
                                    held.moveTo(x(i), nextY)
                                    observedChanges.moveTo(x(i), previousY)
                                    observedChanges.lineTo(x(i), nextY)
                                }
                                area.lineTo(x(i), previousY)
                                area.lineTo(x(i), nextY)
                                previous = amount
                            }
                            area.lineTo(x(days.lastIndex), baseline)
                            area.close()
                            drawPath(area, brush = Brush.verticalGradient(listOf(chartFill, accent.copy(alpha = 0.02f))))
                            drawPath(held, carriedColor, style = Stroke(2.dp.toPx(), pathEffect = dashed))
                            drawPath(observedChanges, accent, style = Stroke(2.5.dp.toPx(), cap = StrokeCap.Round))
                            days.forEachIndexed { index, point ->
                                if (point.allReadingsFromThisDay) point.amountMinor?.let {
                                    drawCircle(accent, radius = 2.5.dp.toPx(), center = Offset(x(index), y(it)))
                                }
                            }
                            chosen.amountMinor?.let { amount ->
                                val center = Offset(x(selection), y(amount))
                                drawLine(accent, center, Offset(center.x, baseline), pathEffect = dashed)
                                drawCircle(scheme.surface, radius = 5.dp.toPx(), center = center)
                                drawCircle(accent, radius = 3.5.dp.toPx(), center = center)
                            }
                        }
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(start = labelPadding),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        (0 until tickCount).map { tick -> days.lastIndex * tick / (tickCount - 1) }.distinct().forEach { index ->
                            Text(days[index].date.format(dateFormat), style = MaterialTheme.typography.labelSmall,
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        }
        Text(
            "Tap or drag to see a day. Dashed lines carry the last saved value forward.",
            style = MaterialTheme.typography.bodySmall,
            color = scheme.onSurfaceVariant,
        )
    }
}
