package com.paisalens.app.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.paisalens.app.data.model.CreditUtilizationBand
import com.paisalens.app.ui.privacy.PrivacyModeRuntime
import com.paisalens.app.ui.privacy.maskMoneyText
import java.util.Locale

data class MoneyChartPoint(
    val label: String,
    val amountMinor: Long,
)

@Composable
fun MoneyLineChart(
    points: List<MoneyChartPoint>,
    modifier: Modifier = Modifier,
    forecastStartIndex: Int? = null,
    lineColor: Color = MaterialTheme.colorScheme.primary,
) {
    if (points.isEmpty()) return
    val minimum = points.minOf { it.amountMinor }
    val maximum = points.maxOf { it.amountMinor }
    val range = (maximum - minimum).coerceAtLeast(1L)
    val middle = minimum + range / 2
    val summary = points.joinToString { "${it.label} ${formatMoney(it.amountMinor)}" }
    val gridColor = MaterialTheme.colorScheme.outlineVariant

    Column(
        modifier = modifier.semantics {
            contentDescription = "Money trend. $summary"
        },
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(modifier = Modifier.fillMaxWidth().height(168.dp)) {
            Column(
                modifier = Modifier.width(62.dp).height(150.dp),
                verticalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(compactMoney(maximum), style = MaterialTheme.typography.labelSmall)
                Text(compactMoney(middle), style = MaterialTheme.typography.labelSmall)
                Text(compactMoney(minimum), style = MaterialTheme.typography.labelSmall)
            }
            Canvas(modifier = Modifier.weight(1f).height(150.dp)) {
                repeat(3) { index ->
                    val y = size.height * index / 2f
                    drawLine(gridColor, Offset(0f, y), Offset(size.width, y), strokeWidth = 1.dp.toPx())
                }
                fun coordinate(index: Int): Offset {
                    val x = if (points.size == 1) size.width / 2f else size.width * index / (points.lastIndex.toFloat())
                    val normalized = (points[index].amountMinor - minimum).toFloat() / range.toFloat()
                    return Offset(x, size.height - normalized * size.height)
                }
                fun drawRange(start: Int, endInclusive: Int, dashed: Boolean) {
                    if (endInclusive <= start) return
                    val path = Path().apply {
                        moveTo(coordinate(start).x, coordinate(start).y)
                        for (index in start + 1..endInclusive) {
                            val point = coordinate(index)
                            lineTo(point.x, point.y)
                        }
                    }
                    drawPath(
                        path,
                        lineColor,
                        style = Stroke(
                            width = 3.dp.toPx(),
                            cap = StrokeCap.Round,
                            join = StrokeJoin.Round,
                            pathEffect = if (dashed) {
                                PathEffect.dashPathEffect(floatArrayOf(10.dp.toPx(), 7.dp.toPx()))
                            } else {
                                null
                            },
                        ),
                    )
                }

                val forecastIndex = forecastStartIndex?.coerceIn(0, points.lastIndex)
                if (forecastIndex == null) {
                    drawRange(0, points.lastIndex, false)
                } else {
                    drawRange(0, forecastIndex, false)
                    drawRange(forecastIndex, points.lastIndex, true)
                }
                points.forEachIndexed { index, _ ->
                    val point = coordinate(index)
                    drawCircle(lineColor, radius = 3.5.dp.toPx(), center = point)
                    drawCircle(Color.White, radius = 1.4.dp.toPx(), center = point)
                }
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 62.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(points.first().label, style = MaterialTheme.typography.labelSmall)
            if (points.size > 2) Text(points[points.size / 2].label, style = MaterialTheme.typography.labelSmall)
            Text(points.last().label, style = MaterialTheme.typography.labelSmall)
        }
        if (forecastStartIndex != null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(8.dp).background(lineColor, CircleShape))
                Spacer(Modifier.width(6.dp))
                Text("Solid: observed · Dashed: forecast", style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

@Composable
fun CreditUtilizationBar(
    basisPoints: Int,
    band: CreditUtilizationBand,
    modifier: Modifier = Modifier,
) {
    val percent = basisPoints / 100.0
    val color = when (band) {
        CreditUtilizationBand.UNKNOWN -> MaterialTheme.colorScheme.outline
        CreditUtilizationBand.HEALTHY -> Color(0xFF138A61)
        CreditUtilizationBand.MODERATE -> Color(0xFFE29B22)
        CreditUtilizationBand.HIGH -> Color(0xFFE46B32)
        CreditUtilizationBand.CRITICAL -> MaterialTheme.colorScheme.error
    }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(7.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Credit used", style = MaterialTheme.typography.labelLarge)
            Text("${String.format(Locale.US, "%.1f", percent)}%", fontWeight = FontWeight.Bold, color = color)
        }
        LinearProgressIndicator(
            progress = { (basisPoints / 10_000f).coerceIn(0f, 1f) },
            modifier = Modifier.fillMaxWidth().height(9.dp),
            color = color,
            trackColor = MaterialTheme.colorScheme.surfaceVariant,
            strokeCap = StrokeCap.Round,
        )
    }
}

private fun compactMoney(amountMinor: Long): String {
    if (PrivacyModeRuntime.active) return maskMoneyText("", privacyActive = true)
    val sign = if (amountMinor < 0) "−" else ""
    val absolute = kotlin.math.abs(amountMinor)
    return sign + when {
        absolute >= 10_000_000 -> "₹${String.format(Locale.US, "%.1f", absolute / 10_000_000.0)}L"
        absolute >= 100_000 -> "₹${String.format(Locale.US, "%.1f", absolute / 100_000.0)}K"
        else -> "₹${absolute / 100}"
    }
}
