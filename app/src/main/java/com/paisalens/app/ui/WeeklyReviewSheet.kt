package com.paisalens.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DoneAll
import androidx.compose.material.icons.rounded.PriorityHigh
import androidx.compose.material.icons.rounded.TaskAlt
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.paisalens.app.data.model.AttentionItem
import com.paisalens.app.data.model.AttentionPriority
import com.paisalens.app.data.model.BackupReviewHealth
import com.paisalens.app.data.model.WeeklyReviewSummary
import com.paisalens.app.data.model.WeeklyReviewTone
import com.paisalens.app.ui.components.MoneyText
import com.paisalens.app.ui.components.PaisaCard
import com.paisalens.app.ui.components.formatMoney
import java.time.format.DateTimeFormatter

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WeeklyReviewSheet(
    review: WeeklyReviewSummary,
    onAction: (AttentionItem) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxWidth().navigationBarsPadding(),
            contentPadding = PaddingValues(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("Weekly review", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                        Text(
                            "${review.period.start.format(shortDate)} – ${review.period.endInclusive.format(shortDate)} · calculated privately on-device",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    IconButton(onClick = onDismiss, modifier = Modifier.size(48.dp)) {
                        Icon(Icons.Rounded.Close, contentDescription = "Close weekly review")
                    }
                }
            }

            item {
                PaisaCard(Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(18.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        Icon(
                            imageVector = when (review.tone) {
                                WeeklyReviewTone.ALL_CLEAR -> Icons.Rounded.DoneAll
                                WeeklyReviewTone.STEADY -> Icons.Rounded.TaskAlt
                                WeeklyReviewTone.NEEDS_ATTENTION -> Icons.Rounded.PriorityHigh
                            },
                            contentDescription = null,
                            modifier = Modifier.size(32.dp),
                            tint = when (review.tone) {
                                WeeklyReviewTone.ALL_CLEAR -> MaterialTheme.colorScheme.secondary
                                WeeklyReviewTone.STEADY -> MaterialTheme.colorScheme.primary
                                WeeklyReviewTone.NEEDS_ATTENTION -> MaterialTheme.colorScheme.error
                            },
                        )
                        Column(Modifier.weight(1f)) {
                            Text(review.headline, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                            Text(
                                if (review.attention.isClear) {
                                    "No follow-up is waiting. Your private ledger is ready for the week ahead."
                                } else {
                                    "${review.attention.totalActionCount} action${if (review.attention.totalActionCount == 1) "" else "s"} in a short, prioritised queue."
                                },
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }

            item {
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 20.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    item { WeeklyMetric("Spent", formatMoney(review.expenseMinor), "${review.transactionCount} recorded transactions") }
                    item { WeeklyMetric("Net cash flow", signedMoney(review.netCashFlowMinor), "Income and refunds minus spending") }
                    item { WeeklyMetric("Bills due", formatMoney(review.dueBillsTotalMinor), "${review.dueBillCount} in the review window") }
                    item { WeeklyMetric("Goals behind", review.behindGoalCount.toString(), "${review.activeGoalCount} active goals") }
                    item { WeeklyMetric("Backup", backupHealthLabel(review.backupHealth), "Encrypted backup health") }
                }
            }

            item {
                Text(
                    "NEEDS YOUR ATTENTION",
                    modifier = Modifier.padding(horizontal = 20.dp),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (review.attention.isClear) {
                item {
                    Text(
                        "Nothing needs action right now. Reopen this review any time for an updated on-device summary.",
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                items(review.attention.items, key = AttentionItem::stableId) { item ->
                    AttentionActionCard(
                        item = item,
                        onClick = { onAction(item) },
                        modifier = Modifier.padding(horizontal = 20.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun WeeklyMetric(title: String, value: String, supporting: String) {
    PaisaCard(Modifier.width(190.dp).heightIn(min = 112.dp)) {
        Column(Modifier.padding(15.dp)) {
            Text(title, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(5.dp))
            Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(4.dp))
            Text(supporting, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun AttentionActionCard(
    item: AttentionItem,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    PaisaCard(modifier.fillMaxWidth()) {
        Surface(
            onClick = onClick,
            modifier = Modifier.fillMaxWidth().heightIn(min = 72.dp),
            color = Color.Transparent,
        ) {
            Row(
                modifier = Modifier.padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Icon(
                    imageVector = if (item.priority == AttentionPriority.URGENT) {
                        Icons.Rounded.PriorityHigh
                    } else {
                        Icons.Rounded.TaskAlt
                    },
                    contentDescription = null,
                    tint = if (item.priority == AttentionPriority.URGENT) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.primary
                    },
                )
                Column(Modifier.weight(1f)) {
                    Text(item.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    Text(
                        item.detail,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                    item.dueDate?.let {
                        Text(
                            "Due ${it.format(longDate)}",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                item.amountMinor?.let { MoneyText(it, style = MaterialTheme.typography.labelLarge) }
                Icon(Icons.Rounded.ChevronRight, contentDescription = "Open ${item.title}")
            }
        }
    }
}

private fun backupHealthLabel(health: BackupReviewHealth): String = when (health) {
    BackupReviewHealth.READY -> "Ready"
    BackupReviewHealth.DUE -> "Due"
    BackupReviewHealth.UNVERIFIED -> "Verify"
    BackupReviewHealth.NEVER_CREATED -> "Not set"
    BackupReviewHealth.FAILED -> "Check"
}

private fun signedMoney(value: Long): String = when {
    value > 0 -> "+${formatMoney(value)}"
    value < 0 -> "−${formatMoney(-value)}"
    else -> formatMoney(0)
}

private val shortDate: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM")
private val longDate: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM yyyy")
