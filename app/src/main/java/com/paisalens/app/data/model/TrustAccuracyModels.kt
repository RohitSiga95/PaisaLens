package com.paisalens.app.data.model

import java.time.Instant
import java.time.YearMonth
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import kotlin.math.abs

enum class ReconciliationStatus {
    DRAFT,
    REVIEW_REQUIRED,
    BALANCED,
    RECONCILED,
}

data class MonthlyReconciliation(
    val id: Long = 0,
    val accountId: Long,
    val year: Int,
    val month: Int,
    val openingBalanceMinor: Long? = null,
    val closingBalanceMinor: Long? = null,
    val statementTransactionCount: Int = 0,
    val matchedTransactionCount: Int = 0,
    val unmatchedStatementCount: Int = 0,
    val unmatchedAppCount: Int = 0,
    val status: ReconciliationStatus = ReconciliationStatus.DRAFT,
    val notes: String? = null,
    val reconciledAt: Long? = null,
    val updatedAt: Long = System.currentTimeMillis(),
) {
    val period: YearMonth get() = YearMonth.of(year, month)
}

data class ReconciliationMetrics(
    val accountId: Long,
    val period: YearMonth,
    val appTransactionCount: Int,
    val statementTransactionCount: Int,
    val matchedTransactionCount: Int,
    val unmatchedStatementCount: Int,
    val unmatchedAppCount: Int,
    val incomeMinor: Long,
    val expenseMinor: Long,
    val appNetChangeMinor: Long,
    val expectedClosingBalanceMinor: Long?,
    /** Statement closing balance minus the balance calculated from app transactions. */
    val balanceDifferenceMinor: Long?,
    val matchPercent: Int?,
)

fun calculateReconciliationMetrics(
    reconciliation: MonthlyReconciliation,
    transactions: List<TransactionRecord>,
    zoneId: ZoneId = ZoneId.systemDefault(),
    accountType: AccountType = AccountType.BANK_ACCOUNT,
    transactionLinks: List<TransactionLink> = emptyList(),
): ReconciliationMetrics {
    val period = reconciliation.period
    val periodRows = transactions.filter { transaction ->
        transaction.accountId == reconciliation.accountId &&
            transaction.reviewStatus == ReviewStatus.CONFIRMED &&
            YearMonth.from(Instant.ofEpochMilli(transaction.occurredAt).atZone(zoneId)) == period
    }
    val rows = deduplicateReconciliationTransactions(periodRows, transactionLinks)
    val income = rows.filter { it.type == TransactionType.INCOME || it.type == TransactionType.REFUND }
        .sumOf { it.amountMinor }
    val expense = rows.filter { it.type == TransactionType.EXPENSE }
        .sumOf { it.amountMinor }
    val transfers = rows.filter { it.type == TransactionType.TRANSFER }.sumOf { it.amountMinor }
    val netChange = when (accountType) {
        // Credit-card statement balances represent debt: purchases increase it, while
        // refunds, credits, and card payments reduce it.
        AccountType.CREDIT_CARD -> expense - income - transfers
        else -> income - expense - transfers
    }
    val expectedClosing = reconciliation.openingBalanceMinor?.plus(netChange)
    val difference = reconciliation.closingBalanceMinor?.let { closing ->
        expectedClosing?.let { closing - it }
    }
    val denominator = reconciliation.statementTransactionCount.takeIf { it > 0 }
    val matchPercent = denominator?.let {
        (reconciliation.matchedTransactionCount.coerceIn(0, it) * 100 / it)
    }
    return ReconciliationMetrics(
        accountId = reconciliation.accountId,
        period = period,
        appTransactionCount = rows.size,
        statementTransactionCount = reconciliation.statementTransactionCount,
        matchedTransactionCount = reconciliation.matchedTransactionCount,
        unmatchedStatementCount = reconciliation.unmatchedStatementCount,
        unmatchedAppCount = reconciliation.unmatchedAppCount,
        incomeMinor = income,
        expenseMinor = expense,
        appNetChangeMinor = netChange,
        expectedClosingBalanceMinor = expectedClosing,
        balanceDifferenceMinor = difference,
        matchPercent = matchPercent,
    )
}

fun deduplicateReconciliationTransactions(
    transactions: List<TransactionRecord>,
    transactionLinks: List<TransactionLink> = emptyList(),
): List<TransactionRecord> {
    val recordsById = transactions.associateBy(TransactionRecord::id)
    val explicitlyDuplicateTargets = transactionLinks.mapNotNullTo(mutableSetOf()) { link ->
        if (link.type != TransactionLinkType.TRANSFER && link.type != TransactionLinkType.CARD_PAYMENT) {
            return@mapNotNullTo null
        }
        val source = recordsById[link.sourceTransactionId] ?: return@mapNotNullTo null
        val target = recordsById[link.targetTransactionId] ?: return@mapNotNullTo null
        target.id.takeIf {
            source.accountIdentityId() == target.accountIdentityId() &&
                source.amountMinor == target.amountMinor &&
                transactionFlowDirection(source.type) == transactionFlowDirection(target.type)
        }
    }
    val retained = mutableListOf<TransactionRecord>()
    transactions
        .filter { it.id !in explicitlyDuplicateTargets }
        .sortedWith(compareBy<TransactionRecord> { it.source == TransactionSource.STATEMENT }.thenBy { it.occurredAt })
        .forEach { candidate ->
            val duplicate = retained.any { prior ->
                val mixedSources = (candidate.source == TransactionSource.STATEMENT) xor
                    (prior.source == TransactionSource.STATEMENT)
                val timeDifference = abs(candidate.occurredAt - prior.occurredAt)
                mixedSources &&
                    candidate.accountIdentityId() == prior.accountIdentityId() &&
                    candidate.amountMinor == prior.amountMinor &&
                    transactionFlowDirection(candidate.type) == transactionFlowDirection(prior.type) &&
                    timeDifference <= 2 * 86_400_000L &&
                    merchantsLikelyMatch(candidate.merchant, prior.merchant)
            }
            if (!duplicate) retained += candidate
        }
    return retained.sortedBy(TransactionRecord::occurredAt)
}

private fun transactionFlowDirection(type: TransactionType): Int = when (type) {
    TransactionType.INCOME, TransactionType.REFUND -> 1
    TransactionType.EXPENSE, TransactionType.TRANSFER -> -1
}

private fun merchantsLikelyMatch(first: String, second: String): Boolean {
    val firstKey = normalizedMerchantKey(first)
    val secondKey = normalizedMerchantKey(second)
    if (firstKey.isBlank() || secondKey.isBlank()) return false
    return firstKey == secondKey ||
        (minOf(firstKey.length, secondKey.length) >= 4 &&
            (firstKey.contains(secondKey) || secondKey.contains(firstKey)))
}

fun transactionIdsExcludedFromSpending(links: List<TransactionLink>): Set<Long> = links
    .asSequence()
    .filter { it.type == TransactionLinkType.TRANSFER || it.type == TransactionLinkType.CARD_PAYMENT }
    .flatMap { sequenceOf(it.sourceTransactionId, it.targetTransactionId) }
    .toSet()

fun transactionIdsAppliedAsExpenseOffsets(
    transactions: List<TransactionRecord>,
    links: List<TransactionLink>,
): Set<Long> {
    val recordsById = transactions.associateBy(TransactionRecord::id)
    return links.asSequence()
        .filter {
            it.type == TransactionLinkType.REFUND ||
                it.type == TransactionLinkType.REVERSAL ||
                it.type == TransactionLinkType.REIMBURSEMENT
        }
        .mapNotNull { link ->
            val source = recordsById[link.sourceTransactionId]
            val target = recordsById[link.targetTransactionId]
            val expense = listOfNotNull(source, target).firstOrNull { it.type == TransactionType.EXPENSE }
                ?: return@mapNotNull null
            listOfNotNull(source, target)
                .firstOrNull {
                    it.id != expense.id && (it.type == TransactionType.REFUND || it.type == TransactionType.INCOME)
                }
                ?.id
        }
        .toSet()
}

/**
 * Returns expense-shaped records whose amounts reflect link semantics for analytics.
 * Transfers and card payments are excluded; refunds, reversals, and reimbursements
 * reduce the linked original expense without mutating the stored transactions.
 */
fun buildEffectiveExpenseTransactions(
    transactions: List<TransactionRecord>,
    links: List<TransactionLink>,
    expenseSplits: List<ExpenseSplit> = emptyList(),
): List<TransactionRecord> {
    val recordsById = transactions.associateBy(TransactionRecord::id)
    val excluded = transactionIdsExcludedFromSpending(links)
    val offsetsByExpenseId = mutableMapOf<Long, Long>()
    links.filter {
        it.type == TransactionLinkType.REFUND ||
            it.type == TransactionLinkType.REVERSAL ||
            it.type == TransactionLinkType.REIMBURSEMENT
    }.forEach { link ->
        val source = recordsById[link.sourceTransactionId]
        val target = recordsById[link.targetTransactionId]
        val expense = listOfNotNull(source, target).firstOrNull { it.type == TransactionType.EXPENSE }
            ?: return@forEach
        val offset = listOfNotNull(source, target)
            .firstOrNull { it.id != expense.id && (it.type == TransactionType.REFUND || it.type == TransactionType.INCOME) }
            ?.amountMinor
            ?: return@forEach
        offsetsByExpenseId[expense.id] = (offsetsByExpenseId[expense.id] ?: 0L) + offset
    }
    val appliedReimbursementPairs = links.asSequence()
        .filter { it.type == TransactionLinkType.REIMBURSEMENT }
        .mapNotNull { link ->
            val source = recordsById[link.sourceTransactionId]
            val target = recordsById[link.targetTransactionId]
            val expense = listOfNotNull(source, target).singleOrNull { it.type == TransactionType.EXPENSE }
                ?: return@mapNotNull null
            val incoming = listOfNotNull(source, target).singleOrNull {
                it.type == TransactionType.INCOME || it.type == TransactionType.REFUND
            } ?: return@mapNotNull null
            expense.id to incoming.id
        }
        .toSet()
    expenseSplits.forEach { split ->
        val linkedAmountAppliedByAnalytics = split.linkedIncomingTransactionId?.let { incomingId ->
            if (split.transactionId to incomingId !in appliedReimbursementPairs) return@let 0L
            recordsById[incomingId]
                ?.takeIf { it.type == TransactionType.INCOME || it.type == TransactionType.REFUND }
                ?.amountMinor
                ?: 0L
        } ?: 0L
        val manualReimbursement = (split.reimbursedMinor - linkedAmountAppliedByAnalytics).coerceAtLeast(0)
        offsetsByExpenseId[split.transactionId] =
            (offsetsByExpenseId[split.transactionId] ?: 0L) + manualReimbursement
    }
    return transactions.mapNotNull { transaction ->
        if (
            transaction.type != TransactionType.EXPENSE ||
            transaction.reviewStatus != ReviewStatus.CONFIRMED ||
            transaction.id in excluded
        ) {
            return@mapNotNull null
        }
        val effectiveAmount = (transaction.amountMinor - (offsetsByExpenseId[transaction.id] ?: 0L)).coerceAtLeast(0)
        transaction.copy(amountMinor = effectiveAmount).takeIf { effectiveAmount > 0 }
    }
}

fun calculateEffectiveSpendMinor(
    transactions: List<TransactionRecord>,
    links: List<TransactionLink>,
    expenseSplits: List<ExpenseSplit> = emptyList(),
): Long {
    val adjustedExpenses = buildEffectiveExpenseTransactions(transactions, links, expenseSplits).sumOf { it.amountMinor }
    val appliedOffsets = transactionIdsAppliedAsExpenseOffsets(transactions, links)
    val unlinkedRefunds = transactions.filter {
        it.type == TransactionType.REFUND &&
            it.reviewStatus == ReviewStatus.CONFIRMED &&
            it.id !in appliedOffsets
    }.sumOf { it.amountMinor }
    return (adjustedExpenses - unlinkedRefunds).coerceAtLeast(0)
}

fun cashFlowRelevantTransactions(
    transactions: List<TransactionRecord>,
    links: List<TransactionLink>,
): List<TransactionRecord> {
    val transferIds = links
        .filter { it.type == TransactionLinkType.TRANSFER }
        .flatMapTo(mutableSetOf()) { listOf(it.sourceTransactionId, it.targetTransactionId) }
    val cardPaymentTargetIds = links
        .filter { it.type == TransactionLinkType.CARD_PAYMENT }
        .mapTo(mutableSetOf(), TransactionLink::targetTransactionId)
    val cardPaymentSourceIds = links
        .filter { it.type == TransactionLinkType.CARD_PAYMENT }
        .mapTo(mutableSetOf(), TransactionLink::sourceTransactionId)
    return transactions.mapNotNull { transaction ->
        when (transaction.id) {
            in transferIds, in cardPaymentTargetIds -> null
            in cardPaymentSourceIds -> transaction.copy(type = TransactionType.EXPENSE)
            else -> transaction
        }
    }
}

fun suggestReconciliationStatus(metrics: ReconciliationMetrics): ReconciliationStatus = when {
    metrics.matchedTransactionCount > metrics.appTransactionCount -> ReconciliationStatus.REVIEW_REQUIRED
    metrics.matchedTransactionCount + metrics.unmatchedStatementCount != metrics.statementTransactionCount ->
        ReconciliationStatus.REVIEW_REQUIRED
    metrics.unmatchedStatementCount > 0 || metrics.unmatchedAppCount > 0 -> ReconciliationStatus.REVIEW_REQUIRED
    metrics.balanceDifferenceMinor == null -> ReconciliationStatus.DRAFT
    metrics.balanceDifferenceMinor != 0L -> ReconciliationStatus.REVIEW_REQUIRED
    else -> ReconciliationStatus.BALANCED
}

/**
 * Finds previously balanced/reconciled periods that no longer agree with the
 * current ledger. Callers can persist these rows as REVIEW_REQUIRED so a stale
 * green status is never presented after an import, edit, delete, or link change.
 */
fun findReconciliationsInvalidatedByLedger(
    reconciliations: List<MonthlyReconciliation>,
    transactions: List<TransactionRecord>,
    accountTypesById: Map<Long, AccountType>,
    transactionLinks: List<TransactionLink> = emptyList(),
    zoneId: ZoneId = ZoneId.systemDefault(),
): List<MonthlyReconciliation> = reconciliations.mapNotNull { reconciliation ->
    if (
        reconciliation.status != ReconciliationStatus.BALANCED &&
        reconciliation.status != ReconciliationStatus.RECONCILED
    ) {
        return@mapNotNull null
    }
    val metrics = calculateReconciliationMetrics(
        reconciliation = reconciliation,
        transactions = transactions,
        zoneId = zoneId,
        accountType = accountTypesById[reconciliation.accountId] ?: AccountType.BANK_ACCOUNT,
        transactionLinks = transactionLinks,
    )
    reconciliation.takeIf { suggestReconciliationStatus(metrics) != ReconciliationStatus.BALANCED }
}

enum class TransactionLinkType {
    TRANSFER,
    REFUND,
    REVERSAL,
    CARD_PAYMENT,
    REIMBURSEMENT,
}

data class TransactionLink(
    val id: Long = 0,
    val sourceTransactionId: Long,
    val targetTransactionId: Long,
    val type: TransactionLinkType,
    val note: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
)

enum class TransactionLinkIssue {
    SELF_LINK,
    MISSING_TRANSACTION,
    DUPLICATE_LINK,
    CONFLICTING_PAIR,
    CREDIT_ALREADY_LINKED,
    TRANSACTION_ALREADY_LINKED,
    INCOMPATIBLE_FLOW,
    SAME_ACCOUNT_TRANSFER,
    WOULD_CREATE_CYCLE,
}

data class TransactionLinkValidation(
    val isValid: Boolean,
    val issue: TransactionLinkIssue? = null,
)

enum class TransactionTypeChangeIssue {
    TRANSACTION_NOT_FOUND,
    LINK_WOULD_BECOME_INVALID,
    SPLIT_WOULD_BECOME_INVALID,
}

data class TransactionTypeChangeValidation(
    val isValid: Boolean,
    val issue: TransactionTypeChangeIssue? = null,
)

/**
 * Protects persisted relationship semantics when a user reclassifies a ledger row.
 * Only links and split groups involving the edited transaction need revalidation.
 */
fun validateTransactionTypeChange(
    transactionId: Long,
    newType: TransactionType,
    transactions: List<TransactionRecord>,
    links: List<TransactionLink>,
    expenseSplits: List<ExpenseSplit> = emptyList(),
): TransactionTypeChangeValidation {
    val existing = transactions.firstOrNull { it.id == transactionId }
        ?: return TransactionTypeChangeValidation(false, TransactionTypeChangeIssue.TRANSACTION_NOT_FOUND)
    val changedTransactions = transactions.map { transaction ->
        if (transaction.id == transactionId) transaction.copy(type = newType) else transaction
    }
    val recordsById = changedTransactions.associateBy(TransactionRecord::id)
    val transactionIds = recordsById.keys
    val affectedLinks = links.filter {
        it.sourceTransactionId == transactionId || it.targetTransactionId == transactionId
    }
    if (affectedLinks.any { link ->
            !validateTransactionLink(link, links, transactionIds, recordsById).isValid
        }
    ) {
        return TransactionTypeChangeValidation(false, TransactionTypeChangeIssue.LINK_WOULD_BECOME_INVALID)
    }
    val affectedExpenseIds = expenseSplits.asSequence()
        .filter { it.transactionId == transactionId || it.linkedIncomingTransactionId == transactionId }
        .map(ExpenseSplit::transactionId)
        .toSet()
    if (affectedExpenseIds.any { expenseId ->
            !validateExpenseSplits(
                recordsById[expenseId],
                expenseSplits.filter { it.transactionId == expenseId },
                recordsById,
            ).isValid
        }
    ) {
        return TransactionTypeChangeValidation(false, TransactionTypeChangeIssue.SPLIT_WOULD_BECOME_INVALID)
    }
    // Keep the lookup above explicit so a future validation extension cannot accidentally
    // report a missing row as a valid no-op.
    check(existing.id == transactionId)
    return TransactionTypeChangeValidation(true)
}

fun validateTransactionLink(
    candidate: TransactionLink,
    existingLinks: List<TransactionLink>,
    transactionIds: Set<Long>,
    transactionsById: Map<Long, TransactionRecord> = emptyMap(),
): TransactionLinkValidation {
    if (candidate.sourceTransactionId == candidate.targetTransactionId) {
        return TransactionLinkValidation(false, TransactionLinkIssue.SELF_LINK)
    }
    if (candidate.sourceTransactionId !in transactionIds || candidate.targetTransactionId !in transactionIds) {
        return TransactionLinkValidation(false, TransactionLinkIssue.MISSING_TRANSACTION)
    }
    val otherLinks = existingLinks.filter { it.id != candidate.id }
    if (otherLinks.any {
            it.sourceTransactionId == candidate.sourceTransactionId &&
                it.targetTransactionId == candidate.targetTransactionId &&
                it.type == candidate.type
        }
    ) {
        return TransactionLinkValidation(false, TransactionLinkIssue.DUPLICATE_LINK)
    }
    if (otherLinks.any {
            setOf(it.sourceTransactionId, it.targetTransactionId) ==
                setOf(candidate.sourceTransactionId, candidate.targetTransactionId)
        }
    ) {
        return TransactionLinkValidation(false, TransactionLinkIssue.CONFLICTING_PAIR)
    }
    val source = transactionsById[candidate.sourceTransactionId]
    val target = transactionsById[candidate.targetTransactionId]
    if (source != null && target != null) {
        val isOffsetType = candidate.type == TransactionLinkType.REFUND ||
            candidate.type == TransactionLinkType.REVERSAL ||
            candidate.type == TransactionLinkType.REIMBURSEMENT
        if (isOffsetType) {
            val expenseCount = listOf(source, target).count { it.type == TransactionType.EXPENSE }
            val credit = listOf(source, target).singleOrNull {
                it.type == TransactionType.REFUND || it.type == TransactionType.INCOME
            }
            if (expenseCount != 1 || credit == null) {
                return TransactionLinkValidation(false, TransactionLinkIssue.INCOMPATIBLE_FLOW)
            }
            val existingCreditIds = existingLinks
                .filter { it.id != candidate.id }
                .flatMap { link ->
                    listOfNotNull(
                        transactionsById[link.sourceTransactionId],
                        transactionsById[link.targetTransactionId],
                    ).filter { it.type == TransactionType.REFUND || it.type == TransactionType.INCOME }
                }
                .mapTo(mutableSetOf(), TransactionRecord::id)
            if (credit.id in existingCreditIds) {
                return TransactionLinkValidation(false, TransactionLinkIssue.CREDIT_ALREADY_LINKED)
            }
            val expense = listOf(source, target).single { it.type == TransactionType.EXPENSE }
            if (otherLinks.any { link ->
                    (link.sourceTransactionId == expense.id || link.targetTransactionId == expense.id) &&
                        (link.type == TransactionLinkType.TRANSFER || link.type == TransactionLinkType.CARD_PAYMENT)
                }
            ) {
                return TransactionLinkValidation(false, TransactionLinkIssue.TRANSACTION_ALREADY_LINKED)
            }
        } else {
            val compatibleOpposingFlow = transactionFlowDirection(source.type) < 0 &&
                transactionFlowDirection(target.type) > 0
            val bothExplicitTransfers = source.type == TransactionType.TRANSFER && target.type == TransactionType.TRANSFER
            if (!compatibleOpposingFlow && !bothExplicitTransfers) {
                return TransactionLinkValidation(false, TransactionLinkIssue.INCOMPATIBLE_FLOW)
            }
            if (
                source.accountIdentityId() != null &&
                source.accountIdentityId() == target.accountIdentityId()
            ) {
                return TransactionLinkValidation(false, TransactionLinkIssue.SAME_ACCOUNT_TRANSFER)
            }
            if (otherLinks.any { link ->
                    link.sourceTransactionId == source.id || link.targetTransactionId == source.id ||
                        link.sourceTransactionId == target.id || link.targetTransactionId == target.id
                }
            ) {
                return TransactionLinkValidation(false, TransactionLinkIssue.TRANSACTION_ALREADY_LINKED)
            }
        }
    }
    val outgoing = otherLinks.groupBy(TransactionLink::sourceTransactionId)
    val pending = ArrayDeque<Long>().apply { add(candidate.targetTransactionId) }
    val seen = mutableSetOf<Long>()
    while (pending.isNotEmpty()) {
        val current = pending.removeFirst()
        if (!seen.add(current)) continue
        if (current == candidate.sourceTransactionId) {
            return TransactionLinkValidation(false, TransactionLinkIssue.WOULD_CREATE_CYCLE)
        }
        outgoing[current].orEmpty().forEach { pending.add(it.targetTransactionId) }
    }
    return TransactionLinkValidation(true)
}

data class TransactionLinkSuggestion(
    val sourceTransactionId: Long,
    val targetTransactionId: Long,
    val type: TransactionLinkType,
    /** Integer confidence from 0 to 100. */
    val confidence: Int,
    val reason: String,
)

fun suggestTransactionLinks(
    transactions: List<TransactionRecord>,
    existingLinks: List<TransactionLink>,
    zoneId: ZoneId = ZoneId.systemDefault(),
): List<TransactionLinkSuggestion> {
    val candidates = transactions
        .filter { it.id > 0 && it.reviewStatus == ReviewStatus.CONFIRMED && it.amountMinor > 0 }
        .sortedBy { it.occurredAt }
        .takeLast(MAX_LINK_SUGGESTION_CANDIDATES)
    val candidatesById = candidates.associateBy(TransactionRecord::id)
    val linkedPairs = existingLinks.flatMap {
        listOf(it.sourceTransactionId to it.targetTransactionId, it.targetTransactionId to it.sourceTransactionId)
    }.toSet()
    val ids = candidates.mapTo(mutableSetOf(), TransactionRecord::id)
    val suggestions = mutableListOf<TransactionLinkSuggestion>()
    for (sourceIndex in candidates.indices) {
        val source = candidates[sourceIndex]
        for (targetIndex in sourceIndex + 1 until candidates.size) {
            val target = candidates[targetIndex]
            if (target.occurredAt - source.occurredAt > 15L * 86_400_000L) break
            val days = abs(
                ChronoUnit.DAYS.between(
                    Instant.ofEpochMilli(source.occurredAt).atZone(zoneId).toLocalDate(),
                    Instant.ofEpochMilli(target.occurredAt).atZone(zoneId).toLocalDate(),
                ).toInt(),
            )
            if (days > 14 || source.id to target.id in linkedPairs) continue
            val amountDelta = abs(source.amountMinor - target.amountMinor)
            // Keep automatic suggestions conservative: at most 0.1%, capped at 100 currency units.
            val amountTolerance = minOf(10_000L, maxOf(100L, source.amountMinor / 1_000))
            if (amountDelta > amountTolerance) continue
            val oppositeFlow = source.type == TransactionType.EXPENSE &&
                (target.type == TransactionType.INCOME || target.type == TransactionType.REFUND)
            val reverseOppositeFlow = target.type == TransactionType.EXPENSE &&
                (source.type == TransactionType.INCOME || source.type == TransactionType.REFUND)
            val bothTransfers = source.type == TransactionType.TRANSFER && target.type == TransactionType.TRANSFER
            if (!oppositeFlow && !reverseOppositeFlow && !bothTransfers) continue
            val outgoing = if (reverseOppositeFlow) target else source
            val incoming = if (reverseOppositeFlow) source else target
            val text = "${source.merchant} ${target.merchant} ${source.note.orEmpty()} ${target.note.orEmpty()}".lowercase()
            val linkType = when {
                "reversal" in text || "reversed" in text -> TransactionLinkType.REVERSAL
                "refund" in text || incoming.type == TransactionType.REFUND -> TransactionLinkType.REFUND
                "card payment" in text || "credit card" in text || "cc payment" in text -> TransactionLinkType.CARD_PAYMENT
                "reimburse" in text -> TransactionLinkType.REIMBURSEMENT
                else -> TransactionLinkType.TRANSFER
            }
            val differentAccounts = outgoing.accountIdentityId() != null && incoming.accountIdentityId() != null &&
                outgoing.accountIdentityId() != incoming.accountIdentityId()
            val transferEvidence = listOf("transfer", "self", "card payment", "credit card", "cc payment")
                .any(text::contains)
            if (
                (linkType == TransactionLinkType.TRANSFER || linkType == TransactionLinkType.CARD_PAYMENT) &&
                (!differentAccounts || (!bothTransfers && !transferEvidence))
            ) {
                continue
            }
            var confidence = 60
            if (amountDelta == 0L) confidence += 20
            if (days <= 2) confidence += 10
            if (differentAccounts) confidence += 10
            val candidate = TransactionLink(
                sourceTransactionId = outgoing.id,
                targetTransactionId = incoming.id,
                type = linkType,
            )
            if (validateTransactionLink(candidate, existingLinks, ids, candidatesById).isValid) {
                val reasonParts = buildList {
                    add(
                        if (amountDelta == 0L) {
                            "Amounts match exactly"
                        } else {
                            "Amounts are within ${amountDelta} minor units"
                        },
                    )
                    add(if (days == 0) "on the same day" else "within $days day${if (days == 1) "" else "s"}")
                    if (differentAccounts) add("across different accounts")
                }
                suggestions += TransactionLinkSuggestion(
                    sourceTransactionId = outgoing.id,
                    targetTransactionId = incoming.id,
                    type = linkType,
                    confidence = confidence.coerceAtMost(100),
                    reason = reasonParts.joinToString(" "),
                )
            }
        }
    }
    return suggestions
        .sortedWith(compareByDescending<TransactionLinkSuggestion> { it.confidence }.thenBy { it.sourceTransactionId })
        .distinctBy { it.sourceTransactionId to it.targetTransactionId }
}

private const val MAX_LINK_SUGGESTION_CANDIDATES = 5_000

enum class AuditEntityType {
    TRANSACTION,
    TRANSACTION_LINK,
    MONTHLY_RECONCILIATION,
}

enum class AuditAction {
    INSERT,
    UPDATE,
    DELETE,
}

data class AuditEvent(
    val id: Long = 0,
    val batchId: String,
    val batchLabel: String,
    val entityType: AuditEntityType,
    val entityId: String,
    val action: AuditAction,
    val beforePayload: String? = null,
    val afterPayload: String? = null,
    val occurredAt: Long = System.currentTimeMillis(),
    val reversesEventId: Long? = null,
)

data class AuditBatchSummary(
    val batchId: String,
    val label: String,
    val occurredAt: Long,
    val eventCount: Int,
    val canUndo: Boolean,
    val isUndo: Boolean,
)

fun buildAuditBatchSummaries(events: List<AuditEvent>): List<AuditBatchSummary> {
    val reversedEventIds = events.mapNotNull(AuditEvent::reversesEventId).toSet()
    return events.groupBy(AuditEvent::batchId).map { (batchId, rows) ->
        val ordered = rows.sortedBy(AuditEvent::id)
        val originals = ordered.filter { it.reversesEventId == null }
        AuditBatchSummary(
            batchId = batchId,
            label = ordered.last().batchLabel,
            occurredAt = ordered.maxOf(AuditEvent::occurredAt),
            eventCount = ordered.size,
            canUndo = isAuditBatchEligibleForUndo(batchId) &&
                originals.isNotEmpty() && originals.none { it.id in reversedEventIds },
            isUndo = originals.isEmpty(),
        )
    }.sortedByDescending(AuditBatchSummary::occurredAt)
}

/** System-maintained ledger integrity events are informational and cannot be undone. */
fun isAuditBatchEligibleForUndo(batchId: String): Boolean =
    !batchId.startsWith(SYSTEM_RECONCILIATION_INVALIDATION_BATCH_PREFIX)

const val SYSTEM_RECONCILIATION_INVALIDATION_BATCH_PREFIX = "system-reconciliation-invalidated-"

/** Orders compensating changes so foreign-key parents are restored before their links. */
fun orderAuditEventsForUndo(events: List<AuditEvent>): List<AuditEvent> = events.sortedWith(
    compareBy<AuditEvent> { event ->
        when (event.action) {
            AuditAction.DELETE -> when (event.entityType) {
                AuditEntityType.TRANSACTION -> 0
                AuditEntityType.MONTHLY_RECONCILIATION -> 1
                AuditEntityType.TRANSACTION_LINK -> 2
            }
            AuditAction.INSERT -> when (event.entityType) {
                AuditEntityType.TRANSACTION_LINK -> 0
                AuditEntityType.MONTHLY_RECONCILIATION -> 1
                AuditEntityType.TRANSACTION -> 2
            }
            AuditAction.UPDATE -> 1
        }
    }.thenByDescending(AuditEvent::id),
)

data class AuditEntityKey(
    val entityType: AuditEntityType,
    val entityId: String,
)

fun findAuditUndoConflict(
    events: List<AuditEvent>,
    currentPayloads: Map<AuditEntityKey, String?>,
    insertedTransactionIdsWithNewerLinks: Set<Long> = emptySet(),
    insertedTransactionIdsWithExpenseSplits: Set<Long> = emptySet(),
    insertedTransactionLinkIdsWithExpenseSplits: Set<Long> = emptySet(),
): String? {
    events.forEach { event ->
        val current = currentPayloads[AuditEntityKey(event.entityType, event.entityId)]
        when (event.action) {
            AuditAction.INSERT, AuditAction.UPDATE -> if (current != event.afterPayload) {
                return "${event.entityType.name.lowercase()} ${event.entityId} changed later"
            }
            AuditAction.DELETE -> if (current != null) {
                return "${event.entityType.name.lowercase()} ${event.entityId} was recreated"
            }
        }
    }
    val insertedTransactions = events
        .filter { it.action == AuditAction.INSERT && it.entityType == AuditEntityType.TRANSACTION }
        .mapTo(mutableSetOf()) { it.entityId.toLong() }
    val newerLinkBlocked = insertedTransactions intersect insertedTransactionIdsWithNewerLinks
    newerLinkBlocked.firstOrNull()?.let { return "transaction $it has newer transaction links" }
    val splitBlocked = insertedTransactions intersect insertedTransactionIdsWithExpenseSplits
    splitBlocked.firstOrNull()?.let { return "transaction $it is used by an expense split" }
    val insertedLinks = events
        .filter { it.action == AuditAction.INSERT && it.entityType == AuditEntityType.TRANSACTION_LINK }
        .mapTo(mutableSetOf()) { it.entityId.toLong() }
    val splitLinkBlocked = insertedLinks intersect insertedTransactionLinkIdsWithExpenseSplits
    return splitLinkBlocked.firstOrNull()?.let { "transaction link $it is used by an expense split" }
}

/**
 * Validates the complete ledger state that would exist after an audit undo. This catches
 * relationship conflicts that payload staleness alone cannot see, such as restoring a credit
 * link after that credit has since been paired with another expense.
 */
fun findAuditUndoLedgerConflict(
    transactions: List<TransactionRecord>,
    links: List<TransactionLink>,
    expenseSplits: List<ExpenseSplit>,
): String? {
    val transactionsById = transactions.associateBy(TransactionRecord::id)
    expenseSplits.groupBy(ExpenseSplit::transactionId).forEach { (transactionId, splits) ->
        val validation = validateExpenseSplits(transactionsById[transactionId], splits, transactionsById)
        if (!validation.isValid) {
            return "expense splits for transaction $transactionId would become invalid (${validation.issue})"
        }
    }
    val transactionIds = transactionsById.keys
    links.forEach { link ->
        val validation = validateTransactionLink(link, links, transactionIds, transactionsById)
        if (!validation.isValid) {
            return "transaction link ${link.id} would become invalid (${validation.issue})"
        }
    }
    expenseSplits.forEach { split ->
        val incomingId = split.linkedIncomingTransactionId ?: return@forEach
        val hasBackingLink = links.any { link ->
            link.type == TransactionLinkType.REIMBURSEMENT &&
                setOf(link.sourceTransactionId, link.targetTransactionId) ==
                setOf(split.transactionId, incomingId)
        }
        if (!hasBackingLink) {
            return "expense split ${split.id} would lose its reimbursement link"
        }
    }
    return null
}

data class AuditUndoResult(
    val originalBatchId: String,
    val undoBatchId: String,
    val insertedEntities: Int,
    val updatedEntities: Int,
    val deletedEntities: Int,
)

data class BackupVerificationMetadata(
    val formatVersion: Int,
    val createdAt: Long,
    val transactionCount: Int,
    val accountCount: Int,
    val reconciliationCount: Int,
    val transactionLinkCount: Int,
    val auditEventCount: Int,
    val budgetCount: Int,
    val customCategoryCount: Int,
    val merchantRuleCount: Int,
    val merchantAliasCount: Int,
    val loanCount: Int,
    val balanceHistoryCount: Int,
    val billCount: Int,
    val netWorthItemCount: Int,
    val smartCategoryRuleCount: Int,
    val expenseSplitCount: Int = 0,
    val savingsGoalCount: Int = 0,
    val savingsContributionCount: Int = 0,
    val paymentCommitmentCount: Int = 0,
    val transactionSmsSourceCount: Int = 0,
    val smsCoverageMessageCount: Int = 0,
    val smsCoverageRuleCount: Int = 0,
    val advancedBudgetCount: Int = 0,
    val creditCardBillCount: Int = 0,
    val contentSha256: String,
) {
    val totalRecordCount: Int
        get() = transactionCount + accountCount + reconciliationCount + transactionLinkCount + auditEventCount +
            budgetCount + customCategoryCount + merchantRuleCount + merchantAliasCount + loanCount +
            balanceHistoryCount + billCount + netWorthItemCount + smartCategoryRuleCount +
            expenseSplitCount + savingsGoalCount + savingsContributionCount + paymentCommitmentCount +
            transactionSmsSourceCount + smsCoverageMessageCount + smsCoverageRuleCount + advancedBudgetCount +
            creditCardBillCount
}

enum class DataHealthSeverity {
    INFO,
    WARNING,
    ACTION_REQUIRED,
}

data class DataHealthFinding(
    val key: String,
    val title: String,
    val detail: String,
    val count: Int,
    val severity: DataHealthSeverity,
)

data class DataHealthSummary(
    val score: Int,
    val findings: List<DataHealthFinding>,
)

fun buildDataHealthSummary(
    transactions: List<TransactionRecord>,
    accounts: List<AccountProfile>,
    reconciliations: List<MonthlyReconciliation>,
    transactionLinks: List<TransactionLink>,
    expenseSplits: List<ExpenseSplit> = emptyList(),
    now: Long = System.currentTimeMillis(),
    staleBalanceDays: Int = 7,
): DataHealthSummary {
    val findings = mutableListOf<DataHealthFinding>()
    val reviewCount = transactions.count { it.reviewStatus == ReviewStatus.NEEDS_REVIEW }
    if (reviewCount > 0) findings += DataHealthFinding(
        "review", "Transactions need review", "$reviewCount imported or parsed transactions need confirmation.",
        reviewCount, DataHealthSeverity.ACTION_REQUIRED,
    )
    val uncategorized = transactions.count {
        it.type == TransactionType.EXPENSE && it.category == ExpenseCategory.OTHER && it.customCategoryId == null
    }
    if (uncategorized > 0) findings += DataHealthFinding(
        "uncategorized", "Uncategorized spending", "$uncategorized expenses are still categorized as Other.",
        uncategorized, DataHealthSeverity.WARNING,
    )
    val staleCutoff = now - staleBalanceDays.coerceAtLeast(1) * 86_400_000L
    val staleAccounts = accounts.count {
        (it.balanceMinor != null || it.availableCreditMinor != null) &&
            (it.availabilityFetchedAt == null || it.availabilityFetchedAt < staleCutoff)
    }
    if (staleAccounts > 0) findings += DataHealthFinding(
        "stale-balances", "Stale account balances", "$staleAccounts account balances are older than $staleBalanceDays days.",
        staleAccounts, DataHealthSeverity.WARNING,
    )
    val reconciliationIssues = reconciliations.count { it.status == ReconciliationStatus.REVIEW_REQUIRED }
    if (reconciliationIssues > 0) findings += DataHealthFinding(
        "reconciliation", "Reconciliation differences", "$reconciliationIssues monthly reconciliations have differences.",
        reconciliationIssues, DataHealthSeverity.ACTION_REQUIRED,
    )
    val linkedIds = transactionLinks.flatMap { listOf(it.sourceTransactionId, it.targetTransactionId) }.toSet()
    val unlinkedTransfers = transactions.count { it.type == TransactionType.TRANSFER && it.id !in linkedIds }
    if (unlinkedTransfers > 0) findings += DataHealthFinding(
        "unlinked-transfers", "Unlinked transfers", "$unlinkedTransfers transfers may be counted without a matching entry.",
        unlinkedTransfers, DataHealthSeverity.INFO,
    )
    val recordsById = transactions.associateBy(TransactionRecord::id)
    val invalidSplitGroups = expenseSplits.groupBy(ExpenseSplit::transactionId).count { (transactionId, splits) ->
        !validateExpenseSplits(recordsById[transactionId], splits, recordsById).isValid
    }
    if (invalidSplitGroups > 0) findings += DataHealthFinding(
        "invalid-expense-splits",
        "Expense splits need review",
        "$invalidSplitGroups split expenses contain an invalid allocation or reimbursement link.",
        invalidSplitGroups,
        DataHealthSeverity.ACTION_REQUIRED,
    )
    val penalty = findings.sumOf { finding ->
        when (finding.severity) {
            DataHealthSeverity.INFO -> 4
            DataHealthSeverity.WARNING -> 10
            DataHealthSeverity.ACTION_REQUIRED -> 18
        }
    }
    return DataHealthSummary(score = (100 - penalty).coerceIn(0, 100), findings = findings)
}
