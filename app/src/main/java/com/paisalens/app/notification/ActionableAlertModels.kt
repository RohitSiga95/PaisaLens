package com.paisalens.app.notification

import com.paisalens.app.data.model.ActionableAlertCategory
import com.paisalens.app.data.model.ActionableAlertsConfiguration
import com.paisalens.app.data.model.AccountProfile
import com.paisalens.app.data.model.AccountType
import com.paisalens.app.data.model.AttentionAction
import com.paisalens.app.data.model.AttentionItem
import com.paisalens.app.data.model.AttentionKind
import com.paisalens.app.data.model.AttentionPriority
import com.paisalens.app.data.model.BudgetHealth
import com.paisalens.app.data.model.BudgetPeriodResult
import com.paisalens.app.data.model.CashFlowForecast
import com.paisalens.app.data.model.CreditUtilization
import com.paisalens.app.data.model.ExpenseSplit
import com.paisalens.app.data.model.ExpenseSplitStatus
import com.paisalens.app.data.model.ReviewStatus
import com.paisalens.app.data.model.TransactionLink
import com.paisalens.app.data.model.TransactionRecord
import com.paisalens.app.data.model.TransactionType
import com.paisalens.app.data.model.buildEffectiveExpenseTransactions
import com.paisalens.app.data.model.transactionIdsAppliedAsExpenseOffsets
import com.paisalens.app.sms.BankSmsSupport
import java.text.NumberFormat
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale

enum class ActionableAlertPriority {
    CRITICAL,
    HIGH,
    NORMAL,
}

/** Only allow-listed destinations are accepted from notification intents. */
enum class AlertDestination(val storageId: String) {
    HOME("home"),
    ACTIVITY("activity"),
    BILLS("bills"),
    CREDIT_CARD_BILLS("credit_card_bills"),
    BUDGETS("budgets"),
    AUTOPAY("autopay"),
    SAVINGS_GOALS("savings_goals"),
    BACKUP_SETTINGS("backup_settings"),
    CASH_FLOW("cash_flow"),
    SPLIT_EXPENSES("split_expenses"),
    ;

    companion object {
        fun fromStorageId(value: String?): AlertDestination =
            entries.firstOrNull { it.storageId == value } ?: HOME
    }
}

data class ActionableAlertCandidate(
    val stableId: String,
    val category: ActionableAlertCategory,
    val priority: ActionableAlertPriority,
    val title: String,
    val detail: String,
    val destination: AlertDestination,
    val amountMinor: Long? = null,
)

data class ActionableAlertInput(
    val attentionItems: List<AttentionItem> = emptyList(),
    val budgetPeriods: List<BudgetPeriodResult> = emptyList(),
    val creditUtilizations: List<CreditUtilization> = emptyList(),
    val cashFlowForecast: CashFlowForecast? = null,
    val expenseSplits: List<ExpenseSplit> = emptyList(),
)

data class ActionableAlertDeliveryRecord(
    val sentAtMillis: Long,
    val priority: ActionableAlertPriority,
)

data class ActionableAlertNotificationContent(
    val title: String,
    val text: String,
    val lines: List<String>,
    val publicTitle: String = "PaisaLens",
    val publicText: String,
    val priority: ActionableAlertPriority,
    val destination: AlertDestination,
)

/**
 * Matches Home's physical-account identity and chooses the freshest non-null balance for each
 * group. A null result means there is no trustworthy opening balance, so no forecast alert should
 * be generated.
 */
fun consolidatedCashFlowOpeningBalance(accounts: List<AccountProfile>): Long? {
    val balances = accounts
        .filter { it.type == AccountType.BANK_ACCOUNT }
        .groupBy { account ->
            val lastFour = account.accountHint?.filter(Char::isDigit)?.takeLast(4)
            val institution = BankSmsSupport.accountBankKey(account.institution, account.name)
                ?: account.institution
                    ?.lowercase(Locale.ROOT)
                    ?.filter(Char::isLetterOrDigit)
                    ?.takeIf(String::isNotBlank)
            if (lastFour?.length == 4 && institution != null) {
                "institution:$institution:last4:$lastFour"
            } else {
                "account:${account.id}"
            }
        }
        .values
        .mapNotNull { matches ->
            matches
                .filter { it.balanceMinor != null }
                .maxByOrNull { it.availabilityFetchedAt ?: Long.MIN_VALUE }
                ?.balanceMinor
        }
    if (balances.isEmpty()) return null
    return balances.fold(0L) { total, balance ->
        try {
            Math.addExact(total, balance)
        } catch (_: ArithmeticException) {
            if (total >= 0 && balance >= 0) Long.MAX_VALUE else Long.MIN_VALUE
        }
    }
}

/** Uses the same transfer/refund/reimbursement semantics as the Budgeting 2.0 screen. */
fun buildActionableBudgetTransactions(
    transactions: List<TransactionRecord>,
    transactionLinks: List<TransactionLink>,
    expenseSplits: List<ExpenseSplit>,
): List<TransactionRecord> {
    val effectiveExpenses = buildEffectiveExpenseTransactions(
        transactions,
        transactionLinks,
        expenseSplits,
    )
    val appliedOffsets = transactionIdsAppliedAsExpenseOffsets(transactions, transactionLinks)
    return effectiveExpenses + transactions.filter {
        it.type == TransactionType.REFUND &&
            it.reviewStatus == ReviewStatus.CONFIRMED &&
            it.id !in appliedOffsets
    }
}

/** Converts already-local financial state into a concise, deterministic set of reminders. */
fun buildActionableAlertCandidates(
    input: ActionableAlertInput,
    configuration: ActionableAlertsConfiguration,
    nowMillis: Long = System.currentTimeMillis(),
    zoneId: ZoneId = ZoneId.systemDefault(),
): List<ActionableAlertCandidate> {
    val safe = configuration.normalized()
    if (!safe.enabled) return emptyList()
    val candidates = mutableListOf<ActionableAlertCandidate>()

    input.attentionItems.forEach { item ->
        val destination = item.action.toAlertDestination() ?: return@forEach
        val category = when (item.kind) {
            AttentionKind.CREDIT_CARD_BILL_DUE -> ActionableAlertCategory.CARD_BILL_DUE
            AttentionKind.BILL_DUE,
            AttentionKind.PAYMENT_COMMITMENT_DUE,
            -> ActionableAlertCategory.BILL_DUE
            AttentionKind.TRANSACTIONS_TO_REVIEW,
            AttentionKind.STALE_ACCOUNT_BALANCE,
            AttentionKind.SAVINGS_GOAL_BEHIND,
            AttentionKind.BACKUP_DUE,
            -> ActionableAlertCategory.NEEDS_ATTENTION
        }
        if (!safe.isEnabled(category)) return@forEach
        candidates += ActionableAlertCandidate(
            stableId = "attention:${item.stableId}",
            category = category,
            priority = item.priority.toAlertPriority(),
            title = item.title,
            detail = item.detail,
            destination = destination,
            amountMinor = item.amountMinor,
        )
    }

    if (safe.isEnabled(ActionableAlertCategory.BUDGET_THRESHOLD)) {
        input.budgetPeriods
            .filter { result ->
                result.health == BudgetHealth.EXCEEDED ||
                    (result.utilizationBasisPoints ?: 0) >= safe.budgetThresholdBasisPoints
            }
            .forEach { result ->
                val percent = ((result.utilizationBasisPoints ?: 0) / 100f)
                candidates += ActionableAlertCandidate(
                    stableId = "budget:${result.planId}:${result.range.start}",
                    category = ActionableAlertCategory.BUDGET_THRESHOLD,
                    priority = if (result.health == BudgetHealth.EXCEEDED) {
                        ActionableAlertPriority.HIGH
                    } else {
                        ActionableAlertPriority.NORMAL
                    },
                    title = if (result.health == BudgetHealth.EXCEEDED) {
                        "${result.planName} budget exceeded"
                    } else {
                        "${result.planName} budget reached ${formatPercent(percent)}"
                    },
                    detail = if (result.health == BudgetHealth.EXCEEDED) {
                        "Review this budget before the current period ends."
                    } else {
                        "Spending has reached your chosen warning level."
                    },
                    destination = AlertDestination.BUDGETS,
                    amountMinor = result.remainingMinor.takeIf { it < 0 }?.let {
                        if (it == Long.MIN_VALUE) Long.MAX_VALUE else -it
                    },
                )
            }
    }

    if (safe.isEnabled(ActionableAlertCategory.CREDIT_UTILIZATION)) {
        input.creditUtilizations
            .filter { (it.utilizationBasisPoints ?: 0) >= safe.utilizationThresholdBasisPoints }
            .forEach { utilization ->
                val basisPoints = requireNotNull(utilization.utilizationBasisPoints)
                candidates += ActionableAlertCandidate(
                    stableId = "utilization:${utilization.accountId}",
                    category = ActionableAlertCategory.CREDIT_UTILIZATION,
                    priority = if (basisPoints >= 9_000) {
                        ActionableAlertPriority.CRITICAL
                    } else {
                        ActionableAlertPriority.HIGH
                    },
                    title = "Credit utilisation is ${formatPercent(basisPoints / 100f)}",
                    detail = "Card utilisation has reached your chosen alert level.",
                    destination = AlertDestination.CREDIT_CARD_BILLS,
                    amountMinor = utilization.usedMinor,
                )
            }
    }

    if (safe.isEnabled(ActionableAlertCategory.LOW_CASH_FLOW)) {
        input.cashFlowForecast
            ?.takeIf { it.lowestBalanceMinor <= safe.lowBalanceThresholdMinor }
            ?.let { forecast ->
                val firstLowDate = forecast.points.firstOrNull {
                    it.projectedBalanceMinor <= safe.lowBalanceThresholdMinor
                }?.date
                candidates += ActionableAlertCandidate(
                    stableId = "cash-flow:low-balance",
                    category = ActionableAlertCategory.LOW_CASH_FLOW,
                    priority = if (forecast.lowestBalanceMinor < 0L) {
                        ActionableAlertPriority.CRITICAL
                    } else {
                        ActionableAlertPriority.HIGH
                    },
                    title = "Projected balance needs attention",
                    detail = firstLowDate?.let { "The forecast reaches your safety floor on $it." }
                        ?: "The forecast reaches your chosen safety floor.",
                    destination = AlertDestination.CASH_FLOW,
                    amountMinor = forecast.lowestBalanceMinor,
                )
            }
    }

    if (safe.isEnabled(ActionableAlertCategory.OVERDUE_REIMBURSEMENT)) {
        val overdueCutoff = nowMillis - OVERDUE_REIMBURSEMENT_DAYS * MILLIS_PER_DAY
        val overdue = input.expenseSplits.filter {
            it.status != ExpenseSplitStatus.REIMBURSED &&
                it.shareMinor > it.reimbursedMinor &&
                it.createdAt in 1..overdueCutoff
        }
        if (overdue.isNotEmpty()) {
            val oldestCreatedAt = overdue.minOf(ExpenseSplit::createdAt)
            val oldestDate = Instant.ofEpochMilli(oldestCreatedAt).atZone(zoneId).toLocalDate()
            candidates += ActionableAlertCandidate(
                stableId = "reimbursements:overdue",
                category = ActionableAlertCategory.OVERDUE_REIMBURSEMENT,
                priority = if (oldestDate <= LocalDate.now(zoneId).minusDays(30)) {
                    ActionableAlertPriority.HIGH
                } else {
                    ActionableAlertPriority.NORMAL
                },
                title = "${overdue.size} expected reimbursement${if (overdue.size == 1) "" else "s"}",
                detail = "A split expense has been unsettled since $oldestDate.",
                destination = AlertDestination.SPLIT_EXPENSES,
                amountMinor = overdue.sumOf { (it.shareMinor - it.reimbursedMinor).coerceAtLeast(0L) },
            )
        }
    }

    return candidates
        .distinctBy(ActionableAlertCandidate::stableId)
        .sortedWith(
            compareBy<ActionableAlertCandidate> { it.priority.sortOrder }
                .thenBy { it.stableId },
        )
}

/** Suppresses daily repetition and permits immediate delivery only when an item escalates. */
fun selectActionableAlertsForDelivery(
    candidates: List<ActionableAlertCandidate>,
    deliveryHistory: Map<String, ActionableAlertDeliveryRecord>,
    nowMillis: Long,
    minimumRepeatHours: Int,
    maximumAlerts: Int = 5,
): List<ActionableAlertCandidate> {
    if (maximumAlerts <= 0) return emptyList()
    val repeatMillis = minimumRepeatHours.coerceIn(6, 168) * 60L * 60L * 1_000L
    val eligible = candidates.mapNotNull { candidate ->
        val previous = deliveryHistory[candidate.stableId]
        val escalated = previous != null && candidate.priority.sortOrder < previous.priority.sortOrder
        val clockMovedBackward = previous != null && previous.sentAtMillis > nowMillis
        val eligible = previous == null || escalated || clockMovedBackward ||
            nowMillis - previous.sentAtMillis >= repeatMillis
        if (!eligible) null else DeliveryCandidate(
            candidate = candidate,
            deliveryOrder = when {
                escalated -> 0
                previous == null -> 1
                else -> 2
            },
        )
    }
    val top = eligible.minWithOrNull(
        compareBy<DeliveryCandidate> { it.candidate.priority.sortOrder }
            .thenBy(DeliveryCandidate::deliveryOrder)
            .thenBy { it.candidate.stableId },
    ) ?: return emptyList()
    return (listOf(top) + eligible.filterNot { it === top }
        .sortedWith(
            compareBy<DeliveryCandidate>(DeliveryCandidate::deliveryOrder)
                .thenBy { it.candidate.priority.sortOrder }
                .thenBy { it.candidate.stableId },
        )
        .take(maximumAlerts - 1))
        .sortedWith(
            compareBy<DeliveryCandidate> { it.candidate.priority.sortOrder }
                .thenBy(DeliveryCandidate::deliveryOrder)
                .thenBy { it.candidate.stableId },
        )
        .map(DeliveryCandidate::candidate)
}

private data class DeliveryCandidate(
    val candidate: ActionableAlertCandidate,
    val deliveryOrder: Int,
)

fun buildActionableAlertText(
    candidates: List<ActionableAlertCandidate>,
    configuration: ActionableAlertsConfiguration,
): ActionableAlertNotificationContent? {
    val top = candidates.firstOrNull() ?: return null
    val lines = candidates.map { candidate ->
        buildString {
            append(candidate.title)
            if (configuration.showAmounts) candidate.amountMinor?.let {
                append(" · ")
                append(formatRupeesForAlert(it))
            }
        }
    }
    return ActionableAlertNotificationContent(
        title = top.title,
        text = buildString {
            append(top.detail)
            if (configuration.showAmounts) top.amountMinor?.let {
                append(" · ")
                append(formatRupeesForAlert(it))
            }
            if (candidates.size > 1) append(" · ${candidates.size - 1} more")
        },
        lines = lines,
        publicText = if (configuration.genericLockScreenText) {
            "Open PaisaLens to review a money reminder"
        } else {
            top.category.publicSafeText
        },
        priority = top.priority,
        destination = top.destination,
    )
}

private fun AttentionPriority.toAlertPriority(): ActionableAlertPriority = when (this) {
    AttentionPriority.URGENT -> ActionableAlertPriority.CRITICAL
    AttentionPriority.HIGH -> ActionableAlertPriority.HIGH
    AttentionPriority.NORMAL -> ActionableAlertPriority.NORMAL
}

private fun AttentionAction.toAlertDestination(): AlertDestination? = when (this) {
    AttentionAction.REVIEW_TRANSACTIONS -> AlertDestination.ACTIVITY
    AttentionAction.OPEN_BILLS,
    -> AlertDestination.BILLS
    AttentionAction.OPEN_PAYMENT_COMMITMENTS -> AlertDestination.AUTOPAY
    AttentionAction.OPEN_CREDIT_CARD_BILLS -> AlertDestination.CREDIT_CARD_BILLS
    AttentionAction.OPEN_SAVINGS_GOALS -> AlertDestination.SAVINGS_GOALS
    AttentionAction.OPEN_BACKUP_SETTINGS -> AlertDestination.BACKUP_SETTINGS
    AttentionAction.REFRESH_ACCOUNT -> null
}

private val ActionableAlertPriority.sortOrder: Int
    get() = when (this) {
        ActionableAlertPriority.CRITICAL -> 0
        ActionableAlertPriority.HIGH -> 1
        ActionableAlertPriority.NORMAL -> 2
    }

private val ActionableAlertCategory.publicSafeText: String
    get() = when (this) {
        ActionableAlertCategory.CARD_BILL_DUE -> "A card-bill reminder needs attention"
        ActionableAlertCategory.BILL_DUE -> "An upcoming payment needs attention"
        ActionableAlertCategory.BUDGET_THRESHOLD -> "A budget reminder is ready"
        ActionableAlertCategory.CREDIT_UTILIZATION -> "A credit reminder needs attention"
        ActionableAlertCategory.LOW_CASH_FLOW -> "A cash-flow reminder needs attention"
        ActionableAlertCategory.OVERDUE_REIMBURSEMENT -> "An expected reimbursement needs attention"
        ActionableAlertCategory.NEEDS_ATTENTION -> "A private review reminder is ready"
    }

private fun formatPercent(value: Float): String = if (value % 1f == 0f) {
    "${value.toInt()}%"
} else {
    "${"%.1f".format(Locale.US, value)}%"
}

private fun formatRupeesForAlert(amountMinor: Long): String =
    NumberFormat.getCurrencyInstance(Locale.forLanguageTag("en-IN")).apply {
        minimumFractionDigits = 0
        maximumFractionDigits = if (amountMinor % 100L == 0L) 0 else 2
    }.format(amountMinor / 100.0)

private const val OVERDUE_REIMBURSEMENT_DAYS = 14L
private const val MILLIS_PER_DAY = 24L * 60L * 60L * 1_000L
