package com.paisalens.app.data.model

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit

enum class AttentionKind {
    TRANSACTIONS_TO_REVIEW,
    STALE_ACCOUNT_BALANCE,
    BILL_DUE,
    CREDIT_CARD_BILL_DUE,
    SAVINGS_GOAL_BEHIND,
    PAYMENT_COMMITMENT_DUE,
    BACKUP_DUE,
}

enum class AttentionPriority {
    URGENT,
    HIGH,
    NORMAL,
}

/** Stable route keys keep the pure planning layer independent of Compose navigation. */
enum class AttentionAction {
    REVIEW_TRANSACTIONS,
    REFRESH_ACCOUNT,
    OPEN_BILLS,
    OPEN_CREDIT_CARD_BILLS,
    OPEN_SAVINGS_GOALS,
    OPEN_PAYMENT_COMMITMENTS,
    OPEN_BACKUP_SETTINGS,
}

data class AttentionItem(
    val stableId: String,
    val kind: AttentionKind,
    val priority: AttentionPriority,
    val title: String,
    val detail: String,
    val action: AttentionAction,
    val count: Int = 1,
    val amountMinor: Long? = null,
    val dueDate: LocalDate? = null,
    val accountId: Long? = null,
)

data class NeedsAttentionSummary(
    val items: List<AttentionItem>,
    val totalActionCount: Int,
    val urgentActionCount: Int,
) {
    val isClear: Boolean get() = items.isEmpty()
}

enum class BackupReviewHealth {
    READY,
    DUE,
    NEVER_CREATED,
    UNVERIFIED,
    FAILED,
}

data class BackupReviewState(
    val lastSuccessfulAt: Long = 0L,
    val lastVerifiedAt: Long = 0L,
    val lastFailureAt: Long = 0L,
    val lastFailureMessage: String? = null,
    val scheduledBackupEnabled: Boolean = false,
)

data class PlanningReviewInput(
    val transactions: List<TransactionRecord>,
    val accounts: List<AccountProfile>,
    val bills: List<BillReminder>,
    val creditCardBills: List<CreditCardBill> = emptyList(),
    val savingsGoals: List<SavingsGoal>,
    val savingsContributions: List<SavingsContribution>,
    val paymentCommitments: List<PaymentCommitment>,
    val transactionLinks: List<TransactionLink> = emptyList(),
    val expenseSplits: List<ExpenseSplit> = emptyList(),
    val backup: BackupReviewState = BackupReviewState(),
)

enum class WeeklyReviewTone {
    ALL_CLEAR,
    STEADY,
    NEEDS_ATTENTION,
}

data class WeeklyReviewSummary(
    val period: BudgetDateRange,
    val transactionCount: Int,
    val expenseMinor: Long,
    val incomeMinor: Long,
    val refundMinor: Long,
    val netCashFlowMinor: Long,
    val needsReviewCount: Int,
    val staleBalanceCount: Int,
    val dueBillCount: Int,
    val dueBillsTotalMinor: Long,
    val activeGoalCount: Int,
    val behindGoalCount: Int,
    val commitmentDueCount: Int,
    val commitmentDueTotalMinor: Long,
    val backupHealth: BackupReviewHealth,
    val attention: NeedsAttentionSummary,
    val tone: WeeklyReviewTone,
    val headline: String,
    val recommendedAction: AttentionItem?,
)

fun buildNeedsAttentionSummary(
    input: PlanningReviewInput,
    today: LocalDate,
    zoneId: ZoneId,
    staleBalanceAfterDays: Int = 7,
    upcomingDueDays: Int = 7,
    backupDueAfterDays: Int = 30,
): NeedsAttentionSummary {
    require(staleBalanceAfterDays >= 1) { "Balance freshness must be at least one day" }
    require(upcomingDueDays >= 0) { "Upcoming due window cannot be negative" }
    require(backupDueAfterDays >= 1) { "Backup freshness must be at least one day" }

    val items = mutableListOf<AttentionItem>()
    val endOfToday = today.plusDays(1).atStartOfDay(zoneId).toInstant().toEpochMilli()
    val reviewTransactions = input.transactions.filter {
        it.reviewStatus == ReviewStatus.NEEDS_REVIEW && it.occurredAt < endOfToday
    }
    if (reviewTransactions.isNotEmpty()) {
        items += AttentionItem(
            stableId = "transactions:review",
            kind = AttentionKind.TRANSACTIONS_TO_REVIEW,
            priority = AttentionPriority.HIGH,
            title = "Review ${reviewTransactions.size} transaction${if (reviewTransactions.size == 1) "" else "s"}",
            detail = "Confirm the merchant, category, or amount when you are ready.",
            action = AttentionAction.REVIEW_TRANSACTIONS,
            count = reviewTransactions.size,
            amountMinor = reviewTransactions.sumMoney(TransactionRecord::amountMinor),
        )
    }

    val staleCutoff = today.minusDays(staleBalanceAfterDays.toLong())
        .atStartOfDay(zoneId)
        .toInstant()
        .toEpochMilli()
    consolidateWeeklyReviewAccounts(input.accounts)
        .asSequence()
        .filter { (it.latestAvailabilityFetchedAt ?: 0L) < staleCutoff }
        .sortedBy { it.account.name.lowercase() }
        .forEach { consolidated ->
            val account = consolidated.account
            val neverFetched = consolidated.latestAvailabilityFetchedAt == null
            items += AttentionItem(
                stableId = "account:${consolidated.key}:stale",
                kind = AttentionKind.STALE_ACCOUNT_BALANCE,
                priority = AttentionPriority.NORMAL,
                title = "Refresh ${account.name}",
                detail = if (neverFetched) {
                    "No balance has been captured for this account yet."
                } else {
                    "Its balance is more than $staleBalanceAfterDays days old."
                },
                action = AttentionAction.REFRESH_ACCOUNT,
                accountId = account.id,
            )
        }

    val billDueItems = buildDueItems(
        manualBills = input.bills,
        recurringPayments = emptyList(),
        loans = emptyList(),
        today = today,
        zoneId = zoneId,
        horizonDays = upcomingDueDays + 1,
    )
    billDueItems.forEach { due ->
        val overdue = due.dueDate.isBefore(today)
        items += AttentionItem(
            stableId = due.stableId,
            kind = AttentionKind.BILL_DUE,
            priority = if (overdue) AttentionPriority.URGENT else AttentionPriority.HIGH,
            title = due.title,
            detail = when {
                overdue -> "This bill is overdue. Mark it paid when it is settled."
                due.dueDate == today -> "Due today."
                else -> "Due in ${ChronoUnit.DAYS.between(today, due.dueDate)} days."
            },
            action = AttentionAction.OPEN_BILLS,
            amountMinor = due.amountMinor,
            dueDate = due.dueDate,
            accountId = due.accountId,
        )
    }

    val cardBillWindowEnd = today.plusDays(upcomingDueDays.toLong())
    currentCreditCardBills(input.creditCardBills)
        .asSequence()
        .filter { it.status == CreditCardBillStatus.DUE }
        .filter { LocalDate.ofEpochDay(it.dueDateEpochDay) <= cardBillWindowEnd }
        .forEach { bill ->
            val dueDate = LocalDate.ofEpochDay(bill.dueDateEpochDay)
            val overdue = dueDate.isBefore(today)
            items += AttentionItem(
                stableId = "card-bill:${bill.id}",
                kind = AttentionKind.CREDIT_CARD_BILL_DUE,
                priority = if (overdue) AttentionPriority.URGENT else AttentionPriority.HIGH,
                title = "${bill.institutionName} card bill",
                detail = when {
                    overdue -> "This card bill is overdue. Confirm it as paid after settlement."
                    dueDate == today -> "This card bill is due today."
                    else -> "Due in ${ChronoUnit.DAYS.between(today, dueDate)} days."
                },
                action = AttentionAction.OPEN_CREDIT_CARD_BILLS,
                amountMinor = bill.totalDueMinor,
                dueDate = dueDate,
                accountId = bill.accountId,
            )
        }

    val activeGoals = input.savingsGoals.filter { it.isActive }
    activeGoals.forEach { goal ->
        val progress = calculateSavingsGoalProgress(goal, input.savingsContributions, today)
        if (!progress.isComplete && isSavingsGoalBehind(goal, progress, today, zoneId)) {
            val target = goal.targetDateEpochDay?.let(LocalDate::ofEpochDay)
            items += AttentionItem(
                stableId = "goal:${goal.id}:behind",
                kind = AttentionKind.SAVINGS_GOAL_BEHIND,
                priority = if (target != null && target.isBefore(today)) {
                    AttentionPriority.HIGH
                } else {
                    AttentionPriority.NORMAL
                },
                title = "Check ${goal.name}",
                detail = progress.requiredMonthlyMinor?.let {
                    "A regular monthly contribution would keep the goal moving."
                } ?: "This goal is behind its planned pace.",
                action = AttentionAction.OPEN_SAVINGS_GOALS,
                amountMinor = progress.remainingMinor,
                dueDate = target,
            )
        }
    }

    val commitmentDueItems = buildPaymentCommitmentDueItems(
        commitments = deduplicatedPaymentCommitments(input.paymentCommitments),
        today = today,
        horizonDays = upcomingDueDays + 1,
        accountNamesById = input.accounts.associate { it.id to it.name },
    )
    commitmentDueItems.forEach { due ->
        items += AttentionItem(
            stableId = due.stableId,
            kind = AttentionKind.PAYMENT_COMMITMENT_DUE,
            priority = if (due.dueDate <= today) AttentionPriority.HIGH else AttentionPriority.NORMAL,
            title = due.title,
            detail = if (due.dueDate == today) {
                "This scheduled payment is due today."
            } else {
                "Scheduled for ${due.dueDate}."
            },
            action = AttentionAction.OPEN_PAYMENT_COMMITMENTS,
            amountMinor = due.amountMinor,
            dueDate = due.dueDate,
            accountId = due.accountId,
        )
    }

    backupAttention(input.backup, today, zoneId, backupDueAfterDays)?.let(items::add)

    val sorted = items.sortedWith(
        compareBy<AttentionItem> { it.priority.sortOrder }
            .thenBy { it.dueDate ?: LocalDate.MAX }
            .thenBy { it.stableId },
    )
    return NeedsAttentionSummary(
        items = sorted,
        totalActionCount = sorted.sumOf { it.count.coerceAtLeast(1) },
        urgentActionCount = sorted.filter { it.priority == AttentionPriority.URGENT }
            .sumOf { it.count.coerceAtLeast(1) },
    )
}

private data class WeeklyReviewAccountGroup(
    val key: String,
    val account: AccountProfile,
    val latestAvailabilityFetchedAt: Long?,
)

/**
 * Sender-specific profiles can describe the same physical account. Home consolidates those
 * profiles by account type and last four digits, so Weekly Review must use the same identity or
 * it can ask the user to refresh the same account more than once.
 */
private fun consolidateWeeklyReviewAccounts(
    accounts: List<AccountProfile>,
): List<WeeklyReviewAccountGroup> = accounts
    .filter { it.type == AccountType.BANK_ACCOUNT || it.type == AccountType.CREDIT_CARD }
    .groupBy(::weeklyReviewAccountKey)
    .map { (key, matches) ->
        val representative = matches.sortedWith(
            compareByDescending<AccountProfile> { it.availabilityFetchedAt ?: Long.MIN_VALUE }
                .thenByDescending(::hasWeeklyReviewAvailability)
                .thenBy(AccountProfile::id),
        ).first()
        WeeklyReviewAccountGroup(
            key = key,
            account = representative,
            latestAvailabilityFetchedAt = matches.mapNotNull(AccountProfile::availabilityFetchedAt)
                .maxOrNull(),
        )
    }

private fun weeklyReviewAccountKey(account: AccountProfile): String {
    val lastFour = account.accountHint
        ?.filter(Char::isDigit)
        ?.takeLast(4)
        ?.takeIf { it.length == 4 }
    return if (lastFour != null) {
        "${account.type.name}:last4:$lastFour"
    } else {
        "${account.type.name}:account:${account.id}"
    }
}

private fun hasWeeklyReviewAvailability(account: AccountProfile): Boolean = when (account.type) {
    AccountType.BANK_ACCOUNT -> account.balanceMinor != null
    AccountType.CREDIT_CARD -> account.availableCreditMinor != null
    else -> account.balanceMinor != null || account.availableCreditMinor != null
}

fun buildWeeklyReview(
    input: PlanningReviewInput,
    today: LocalDate,
    zoneId: ZoneId,
    staleBalanceAfterDays: Int = 7,
    upcomingDueDays: Int = 7,
    backupDueAfterDays: Int = 30,
): WeeklyReviewSummary {
    val period = BudgetDateRange(today.minusDays(6), today)
    val weeklyTransactions = input.transactions.filter { transaction ->
        val date = Instant.ofEpochMilli(transaction.occurredAt).atZone(zoneId).toLocalDate()
        period.contains(date)
    }
    val effectiveWeeklyExpenses = buildEffectiveExpenseTransactions(
        input.transactions,
        input.transactionLinks,
        input.expenseSplits,
    ).filter { transaction ->
        val date = Instant.ofEpochMilli(transaction.occurredAt).atZone(zoneId).toLocalDate()
        period.contains(date)
    }
    val appliedOffsetIds = transactionIdsAppliedAsExpenseOffsets(
        input.transactions,
        input.transactionLinks,
    )
    val income = weeklyTransactions.filter {
        it.type == TransactionType.INCOME &&
            it.reviewStatus == ReviewStatus.CONFIRMED &&
            it.id !in appliedOffsetIds
    }
        .sumMoney(TransactionRecord::amountMinor)
    val refund = weeklyTransactions.filter { it.type == TransactionType.REFUND }
        .sumMoney(TransactionRecord::amountMinor)
    val unlinkedRefund = weeklyTransactions.filter {
        it.type == TransactionType.REFUND &&
            it.reviewStatus == ReviewStatus.CONFIRMED &&
            it.id !in appliedOffsetIds
    }.sumMoney(TransactionRecord::amountMinor)
    val expense = safeReviewSubtract(
        effectiveWeeklyExpenses.sumMoney(TransactionRecord::amountMinor),
        unlinkedRefund,
    ).coerceAtLeast(0L)
    val attention = buildNeedsAttentionSummary(
        input,
        today,
        zoneId,
        staleBalanceAfterDays,
        upcomingDueDays,
        backupDueAfterDays,
    )
    val dueBills = attention.items.filter {
        it.kind == AttentionKind.BILL_DUE || it.kind == AttentionKind.CREDIT_CARD_BILL_DUE
    }
    val commitments = attention.items.filter { it.kind == AttentionKind.PAYMENT_COMMITMENT_DUE }
    val behindGoals = attention.items.count { it.kind == AttentionKind.SAVINGS_GOAL_BEHIND }
    val needsReview = attention.items
        .firstOrNull { it.kind == AttentionKind.TRANSACTIONS_TO_REVIEW }
        ?.count
        ?: 0
    val urgentOrHigh = attention.items.count {
        it.priority == AttentionPriority.URGENT || it.priority == AttentionPriority.HIGH
    }
    val tone = when {
        attention.isClear -> WeeklyReviewTone.ALL_CLEAR
        urgentOrHigh == 0 -> WeeklyReviewTone.STEADY
        else -> WeeklyReviewTone.NEEDS_ATTENTION
    }
    val headline = when (tone) {
        WeeklyReviewTone.ALL_CLEAR -> "Everything is up to date"
        WeeklyReviewTone.STEADY -> "A few small check-ins for this week"
        WeeklyReviewTone.NEEDS_ATTENTION -> "A short review will bring everything up to date"
    }
    return WeeklyReviewSummary(
        period = period,
        transactionCount = weeklyTransactions.size,
        expenseMinor = expense,
        incomeMinor = income,
        refundMinor = refund,
        netCashFlowMinor = safeReviewSubtract(income, expense),
        needsReviewCount = needsReview,
        staleBalanceCount = attention.items.count { it.kind == AttentionKind.STALE_ACCOUNT_BALANCE },
        dueBillCount = dueBills.size,
        dueBillsTotalMinor = dueBills.mapNotNull(AttentionItem::amountMinor).safeSum(),
        activeGoalCount = input.savingsGoals.count { it.isActive },
        behindGoalCount = behindGoals,
        commitmentDueCount = commitments.size,
        commitmentDueTotalMinor = commitments.mapNotNull(AttentionItem::amountMinor).safeSum(),
        backupHealth = backupReviewHealth(input.backup, today, zoneId, backupDueAfterDays),
        attention = attention,
        tone = tone,
        headline = headline,
        recommendedAction = attention.items.firstOrNull(),
    )
}

fun backupReviewHealth(
    backup: BackupReviewState,
    today: LocalDate,
    zoneId: ZoneId,
    dueAfterDays: Int = 30,
): BackupReviewHealth {
    require(dueAfterDays >= 1) { "Backup freshness must be at least one day" }
    if (backup.lastFailureAt > backup.lastSuccessfulAt) return BackupReviewHealth.FAILED
    if (backup.lastSuccessfulAt <= 0L) return BackupReviewHealth.NEVER_CREATED
    if (backup.lastVerifiedAt < backup.lastSuccessfulAt) return BackupReviewHealth.UNVERIFIED
    val lastSuccessDate = Instant.ofEpochMilli(backup.lastSuccessfulAt).atZone(zoneId).toLocalDate()
    return if (ChronoUnit.DAYS.between(lastSuccessDate, today) >= dueAfterDays) {
        BackupReviewHealth.DUE
    } else {
        BackupReviewHealth.READY
    }
}

private fun backupAttention(
    backup: BackupReviewState,
    today: LocalDate,
    zoneId: ZoneId,
    dueAfterDays: Int,
): AttentionItem? = when (backupReviewHealth(backup, today, zoneId, dueAfterDays)) {
    BackupReviewHealth.READY -> null
    BackupReviewHealth.NEVER_CREATED -> AttentionItem(
        stableId = "backup:never-created",
        kind = AttentionKind.BACKUP_DUE,
        priority = AttentionPriority.NORMAL,
        title = "Create your first encrypted backup",
        detail = "Choose an Android folder so your ledger has a recoverable encrypted copy.",
        action = AttentionAction.OPEN_BACKUP_SETTINGS,
    )
    BackupReviewHealth.DUE -> AttentionItem(
        stableId = "backup:due",
        kind = AttentionKind.BACKUP_DUE,
        priority = AttentionPriority.NORMAL,
        title = "Refresh your encrypted backup",
        detail = "The latest successful backup is more than $dueAfterDays days old.",
        action = AttentionAction.OPEN_BACKUP_SETTINGS,
    )
    BackupReviewHealth.UNVERIFIED -> AttentionItem(
        stableId = "backup:unverified",
        kind = AttentionKind.BACKUP_DUE,
        priority = AttentionPriority.NORMAL,
        title = "Verify your latest encrypted backup",
        detail = "A quick verification confirms that the backup can be opened safely.",
        action = AttentionAction.OPEN_BACKUP_SETTINGS,
    )
    BackupReviewHealth.FAILED -> AttentionItem(
        stableId = "backup:failed",
        kind = AttentionKind.BACKUP_DUE,
        priority = AttentionPriority.HIGH,
        title = "Backup needs a quick check",
        detail = backup.lastFailureMessage?.take(120)
            ?: "The latest scheduled backup could not be completed.",
        action = AttentionAction.OPEN_BACKUP_SETTINGS,
    )
}

private fun isSavingsGoalBehind(
    goal: SavingsGoal,
    progress: SavingsGoalProgress,
    today: LocalDate,
    zoneId: ZoneId,
): Boolean {
    val target = goal.targetDateEpochDay?.let(LocalDate::ofEpochDay) ?: return false
    if (progress.isComplete) return false
    if (!target.isAfter(today)) return true
    val created = Instant.ofEpochMilli(goal.createdAt).atZone(zoneId).toLocalDate()
    if (!today.isAfter(created) || !target.isAfter(created)) return false
    val totalDays = ChronoUnit.DAYS.between(created, target).coerceAtLeast(1L)
    val elapsedDays = ChronoUnit.DAYS.between(created, today).coerceIn(0L, totalDays)
    val expectedBasisPoints = ((elapsedDays.toDouble() / totalDays.toDouble()) * 10_000).toInt()
    return progress.progressBasisPoints + GOAL_PACE_GRACE_BASIS_POINTS < expectedBasisPoints
}

private fun List<TransactionRecord>.sumMoney(selector: (TransactionRecord) -> Long): Long =
    asSequence().map(selector).toList().safeSum()

private fun List<Long>.safeSum(): Long = fold(0L, ::safeReviewAdd)

private fun safeReviewAdd(left: Long, right: Long): Long = try {
    Math.addExact(left, right)
} catch (_: ArithmeticException) {
    if (left >= 0 && right >= 0) Long.MAX_VALUE else Long.MIN_VALUE
}

private fun safeReviewSubtract(left: Long, right: Long): Long = try {
    Math.subtractExact(left, right)
} catch (_: ArithmeticException) {
    if (left >= 0 && right < 0) Long.MAX_VALUE else Long.MIN_VALUE
}

private val AttentionPriority.sortOrder: Int
    get() = when (this) {
        AttentionPriority.URGENT -> 0
        AttentionPriority.HIGH -> 1
        AttentionPriority.NORMAL -> 2
    }

private const val GOAL_PACE_GRACE_BASIS_POINTS = 500
