package com.paisalens.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.paisalens.app.data.model.BillsTimelineSnapshot
import com.paisalens.app.ui.components.MoneyText
import com.paisalens.app.ui.components.PaisaCard
import com.paisalens.app.ui.components.timelineIcon
import java.time.format.DateTimeFormatter

@Composable
internal fun UpcomingBillsHomeModule(
    timeline: BillsTimelineSnapshot,
    compact: Boolean,
    onOpenBills: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val dateFormat = remember { DateTimeFormatter.ofPattern("EEE, d MMM") }
    val preview = remember(timeline, compact) { timeline.dueThisWeek.take(if (compact) 2 else 3) }
    PaisaCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(if (compact) 12.dp else 18.dp)) {
            Surface(
                onClick = onOpenBills,
                color = scheme.primaryContainer,
                contentColor = scheme.onPrimaryContainer,
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("Upcoming bills", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Text("Due this week", style = MaterialTheme.typography.labelMedium)
                        MoneyText(timeline.dueThisWeekMinor, color = scheme.onPrimaryContainer)
                    }
                    Icon(Icons.AutoMirrored.Rounded.ArrowForward, contentDescription = "Open bills timeline")
                }
            }
            if (preview.isEmpty()) {
                Text(
                    "No unpaid bills due this week.",
                    modifier = Modifier.padding(top = 10.dp),
                    color = scheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
            } else {
                preview.forEach { entry ->
                    Surface(
                        onClick = onOpenBills,
                        shape = MaterialTheme.shapes.small,
                        color = Color.Transparent,
                    ) {
                        Row(
                            Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(vertical = 6.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Surface(color = scheme.secondaryContainer, shape = CircleShape) {
                                Icon(
                                    entry.timelineIcon(), contentDescription = null,
                                    modifier = Modifier.padding(7.dp).size(17.dp),
                                    tint = scheme.onSecondaryContainer,
                                )
                            }
                            Column(Modifier.weight(1f)) {
                                Text(entry.title, style = MaterialTheme.typography.labelLarge)
                                Text(entry.date.format(dateFormat), style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
                            }
                            MoneyText(entry.amountMinor, style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(0.6f, fill = false))
                        }
                    }
                }
            }
        }
    }
}
