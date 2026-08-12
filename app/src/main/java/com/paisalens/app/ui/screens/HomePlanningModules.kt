package com.paisalens.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.paisalens.app.data.model.PaymentCommitment
import com.paisalens.app.data.model.PaymentCommitmentKind
import com.paisalens.app.data.model.PaymentCommitmentStatus
import com.paisalens.app.data.model.ContributionFrequency
import com.paisalens.app.data.model.SavingsContribution
import com.paisalens.app.data.model.SavingsGoal
import com.paisalens.app.data.model.calculateSavingsGoalProgress
import com.paisalens.app.data.model.currentPaymentDueDate
import com.paisalens.app.ui.components.PaisaCard
import com.paisalens.app.ui.components.SectionHeader
import com.paisalens.app.ui.components.formatMoney
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

@Composable
internal fun SavingsGoalsHomeModule(
    goals: List<SavingsGoal>,
    contributions: List<SavingsContribution>,
    onOpen: () -> Unit,
) {
    val activeGoals = remember(goals, contributions) {
        goals.filter(SavingsGoal::isActive)
            .sortedWith(
                compareBy<SavingsGoal> {
                    calculateSavingsGoalProgress(it, contributions).isComplete
                }.thenBy { it.targetDateEpochDay ?: Long.MAX_VALUE },
            )
            .take(3)
    }
    Column {
        SectionHeader("Savings goals", action = "Manage", onAction = onOpen)
        Spacer(Modifier.height(8.dp))
        PaisaCard(Modifier.fillMaxWidth()) {
            if (activeGoals.isEmpty()) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("No active goals yet", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Create a savings goal or sinking fund to track progress here.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                activeGoals.forEachIndexed { index, goal ->
                    val progress = calculateSavingsGoalProgress(goal, contributions)
                    Column(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 14.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(goal.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                                Text(
                                    "${formatMoney(progress.currentSavedMinor)} of ${formatMoney(goal.targetMinor)}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Text(
                                "${progress.progressBasisPoints / 100}%",
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                        Spacer(Modifier.height(9.dp))
                        LinearProgressIndicator(
                            progress = { progress.progressBasisPoints / 10_000f },
                            modifier = Modifier.fillMaxWidth().height(8.dp),
                            color = MaterialTheme.colorScheme.primary,
                            trackColor = MaterialTheme.colorScheme.surfaceVariant,
                            strokeCap = StrokeCap.Round,
                        )
                        progress.requiredMonthlyMinor?.takeIf {
                            it > 0 && goal.contributionFrequency == ContributionFrequency.MONTHLY
                        }?.let { required ->
                            Text(
                                "Save ${formatMoney(required)} per month to stay on pace",
                                modifier = Modifier.padding(top = 7.dp),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    if (index != activeGoals.lastIndex) {
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    }
                }
            }
        }
    }
}

@Composable
internal fun UpcomingCommitmentsHomeModule(
    commitments: List<PaymentCommitment>,
    onOpen: () -> Unit,
) {
    val today = LocalDate.now()
    val upcoming = remember(commitments, today) {
        commitments.filter { it.status == PaymentCommitmentStatus.ACTIVE }
            .map { it to currentPaymentDueDate(it, today) }
            .sortedBy { it.second }
            .take(3)
    }
    val dateFormatter = remember { DateTimeFormatter.ofPattern("d MMM", Locale.getDefault()) }
    Column {
        SectionHeader("Upcoming commitments", action = "Manage", onAction = onOpen)
        Spacer(Modifier.height(8.dp))
        PaisaCard(Modifier.fillMaxWidth()) {
            if (upcoming.isEmpty()) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("No active commitments", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Add subscriptions or UPI AutoPay mandates, or review locally detected suggestions.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                upcoming.forEachIndexed { index, (commitment, dueDate) ->
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 14.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(commitment.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                            Text(
                                "${commitment.kind.homeLabel()} · ${dueDate.format(dateFormatter)}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Text(
                            formatMoney(commitment.amountMinor),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                    if (index != upcoming.lastIndex) {
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    }
                }
            }
        }
    }
}

private fun PaymentCommitmentKind.homeLabel(): String = when (this) {
    PaymentCommitmentKind.SUBSCRIPTION -> "Subscription"
    PaymentCommitmentKind.UPI_AUTOPAY -> "UPI AutoPay"
}
