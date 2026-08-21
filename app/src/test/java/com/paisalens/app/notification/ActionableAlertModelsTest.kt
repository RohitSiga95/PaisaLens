package com.paisalens.app.notification

import com.paisalens.app.data.model.ActionableAlertCategory
import com.paisalens.app.data.model.ActionableAlertsConfiguration
import com.paisalens.app.data.model.AccountProfile
import com.paisalens.app.data.model.AccountType
import com.paisalens.app.data.model.AttentionAction
import com.paisalens.app.data.model.AttentionItem
import com.paisalens.app.data.model.AttentionKind
import com.paisalens.app.data.model.AttentionPriority
import com.paisalens.app.data.model.BudgetDateRange
import com.paisalens.app.data.model.BudgetHealth
import com.paisalens.app.data.model.BudgetPeriodResult
import com.paisalens.app.data.model.CashFlowBaseline
import com.paisalens.app.data.model.CashFlowForecast
import com.paisalens.app.data.model.CashFlowPoint
import com.paisalens.app.data.model.CreditUtilization
import com.paisalens.app.data.model.CreditUtilizationBand
import com.paisalens.app.data.model.ExpenseSplit
import com.paisalens.app.data.model.ExpenseSplitStatus
import com.paisalens.app.data.model.ExpenseCategory
import com.paisalens.app.data.model.TransactionLink
import com.paisalens.app.data.model.TransactionLinkType
import com.paisalens.app.data.model.TransactionRecord
import com.paisalens.app.data.model.TransactionSource
import com.paisalens.app.data.model.TransactionType
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ActionableAlertModelsTest {
    private val today = LocalDate.of(2026, 8, 20)
    private val zone = ZoneId.of("Asia/Kolkata")
    private val nowMillis = today.atTime(9, 0).atZone(zone).toInstant().toEpochMilli()

    @Test
    fun `alerts are opt in private and safely normalized`() {
        val defaults = ActionableAlertsConfiguration()
        val normalized = ActionableAlertsConfiguration(
            evaluationHour = 80,
            dueWindowDays = -4,
            budgetThresholdBasisPoints = 20_000,
            utilizationThresholdBasisPoints = 1,
            lowBalanceThresholdMinor = -1,
            minimumRepeatHours = 1,
        ).normalized()

        assertFalse(defaults.enabled)
        assertFalse(defaults.showAmounts)
        assertTrue(defaults.genericLockScreenText)
        assertEquals(23, normalized.evaluationHour)
        assertEquals(0, normalized.dueWindowDays)
        assertEquals(10_000, normalized.budgetThresholdBasisPoints)
        assertEquals(3_000, normalized.utilizationThresholdBasisPoints)
        assertEquals(0L, normalized.lowBalanceThresholdMinor)
        assertEquals(6, normalized.minimumRepeatHours)
    }

    @Test
    fun `candidate builder prioritizes critical local signals`() {
        val configuration = ActionableAlertsConfiguration(enabled = true)
        val input = ActionableAlertInput(
            attentionItems = listOf(
                AttentionItem(
                    stableId = "card-bill:1",
                    kind = AttentionKind.CREDIT_CARD_BILL_DUE,
                    priority = AttentionPriority.URGENT,
                    title = "Card bill due",
                    detail = "Due today",
                    action = AttentionAction.OPEN_CREDIT_CARD_BILLS,
                    amountMinor = 4_000_00,
                ),
            ),
            budgetPeriods = listOf(
                BudgetPeriodResult(
                    planId = 2,
                    planName = "Food",
                    range = BudgetDateRange(today.withDayOfMonth(1), today.withDayOfMonth(31)),
                    allocationMinor = 10_000_00,
                    rolloverInMinor = 0,
                    availableMinor = 10_000_00,
                    actualMinor = 9_100_00,
                    remainingMinor = 900_00,
                    plannedToDateMinor = 6_000_00,
                    actualVsPlannedMinor = 3_100_00,
                    utilizationBasisPoints = 9_100,
                    health = BudgetHealth.WARNING,
                ),
            ),
            creditUtilizations = listOf(
                CreditUtilization(
                    accountId = 3,
                    creditLimitMinor = 100_000_00,
                    availableCreditMinor = 5_000_00,
                    usedMinor = 95_000_00,
                    utilizationBasisPoints = 9_500,
                    band = CreditUtilizationBand.CRITICAL,
                ),
            ),
            cashFlowForecast = CashFlowForecast(
                openingBalanceMinor = 1_000_00,
                baseline = CashFlowBaseline(90, 0, 0),
                points = listOf(CashFlowPoint(today.plusDays(1), 0, 0, 2_000_00, -1_000_00)),
                lowestBalanceMinor = -1_000_00,
                endingBalanceMinor = -1_000_00,
            ),
            expenseSplits = listOf(
                ExpenseSplit(
                    id = 4,
                    transactionId = 8,
                    participantName = "Friend",
                    shareMinor = 500_00,
                    status = ExpenseSplitStatus.OPEN,
                    createdAt = nowMillis - 15L * 24L * 60L * 60L * 1_000L,
                ),
            ),
        )

        val candidates = buildActionableAlertCandidates(input, configuration, nowMillis, zone)

        assertEquals(ActionableAlertPriority.CRITICAL, candidates.first().priority)
        assertTrue(candidates.any { it.category == ActionableAlertCategory.CARD_BILL_DUE })
        assertTrue(candidates.any { it.category == ActionableAlertCategory.BUDGET_THRESHOLD })
        assertTrue(candidates.any { it.category == ActionableAlertCategory.CREDIT_UTILIZATION })
        assertTrue(candidates.any { it.category == ActionableAlertCategory.LOW_CASH_FLOW })
        assertTrue(candidates.any { it.category == ActionableAlertCategory.OVERDUE_REIMBURSEMENT })
    }

    @Test
    fun `needs attention alerts keep only safely actionable destinations`() {
        val items = listOf(
            AttentionItem(
                stableId = "stale-account",
                kind = AttentionKind.STALE_ACCOUNT_BALANCE,
                priority = AttentionPriority.NORMAL,
                title = "Balance is stale",
                detail = "Refresh it",
                action = AttentionAction.REFRESH_ACCOUNT,
            ),
            AttentionItem(
                stableId = "goal",
                kind = AttentionKind.SAVINGS_GOAL_BEHIND,
                priority = AttentionPriority.NORMAL,
                title = "Goal is behind",
                detail = "Review the plan",
                action = AttentionAction.OPEN_SAVINGS_GOALS,
            ),
            AttentionItem(
                stableId = "backup",
                kind = AttentionKind.BACKUP_DUE,
                priority = AttentionPriority.NORMAL,
                title = "Backup is due",
                detail = "Review backup settings",
                action = AttentionAction.OPEN_BACKUP_SETTINGS,
            ),
        )

        val candidates = buildActionableAlertCandidates(
            input = ActionableAlertInput(attentionItems = items),
            configuration = ActionableAlertsConfiguration(enabled = true),
            nowMillis = nowMillis,
            zoneId = zone,
        )

        assertEquals(
            setOf(AlertDestination.SAVINGS_GOALS, AlertDestination.BACKUP_SETTINGS),
            candidates.mapTo(mutableSetOf(), ActionableAlertCandidate::destination),
        )
        assertFalse(candidates.any { it.stableId.contains("stale-account") })
    }

    @Test
    fun `opening cash consolidates duplicate last-four profiles and uses freshest balance`() {
        val accounts = listOf(
            AccountProfile(
                id = 1,
                name = "Older sender profile",
                institution = "HDFC Bank",
                type = AccountType.BANK_ACCOUNT,
                accountHint = "1234",
                balanceMinor = 10_000_00,
                availabilityFetchedAt = 100,
            ),
            AccountProfile(
                id = 2,
                name = "Renamed account",
                institution = "HDFC Bank",
                type = AccountType.BANK_ACCOUNT,
                accountHint = "XX1234",
                balanceMinor = 12_000_00,
                availabilityFetchedAt = 200,
            ),
            AccountProfile(
                id = 3,
                name = "Second account",
                institution = "IDFC FIRST Bank",
                type = AccountType.BANK_ACCOUNT,
                accountHint = "9999",
                balanceMinor = 2_000_00,
                availabilityFetchedAt = 150,
            ),
            AccountProfile(
                id = 4,
                name = "Card",
                type = AccountType.CREDIT_CARD,
                accountHint = "7777",
                balanceMinor = 99_000_00,
                availabilityFetchedAt = 300,
            ),
        )

        assertEquals(14_000_00L, consolidatedCashFlowOpeningBalance(accounts))
        assertEquals(null, consolidatedCashFlowOpeningBalance(accounts.filter { it.type == AccountType.CREDIT_CARD }))
    }

    @Test
    fun `opening cash keeps different banks with the same last four separate`() {
        val accounts = listOf(
            AccountProfile(
                id = 1,
                name = "HDFC salary",
                institution = "HDFC Bank",
                type = AccountType.BANK_ACCOUNT,
                accountHint = "1234",
                balanceMinor = 10_000_00,
                availabilityFetchedAt = 100,
            ),
            AccountProfile(
                id = 2,
                name = "IDFC savings",
                institution = "IDFC FIRST Bank",
                type = AccountType.BANK_ACCOUNT,
                accountHint = "1234",
                balanceMinor = 12_000_00,
                availabilityFetchedAt = 200,
            ),
        )

        assertEquals(22_000_00L, consolidatedCashFlowOpeningBalance(accounts))
    }

    @Test
    fun `opening cash is unknown when a merged bank balance is incomplete`() {
        val accounts = listOf(
            AccountProfile(
                id = 1,
                name = "Complete account",
                type = AccountType.BANK_ACCOUNT,
                balanceMinor = 10_000_00,
                availabilityFetchedAt = 100,
            ),
            AccountProfile(
                id = 2,
                name = "Partial merged account",
                type = AccountType.BANK_ACCOUNT,
                balanceMinor = 2_000_00,
                availabilityFetchedAt = null,
                mergedMemberCount = 2,
            ),
        )

        assertEquals(null, consolidatedCashFlowOpeningBalance(accounts))
    }

    @Test
    fun `budget alert transactions exclude transfers and net linked and unlinked refunds once`() {
        val purchase = transaction(1, 10_000, TransactionType.EXPENSE)
        val linkedRefund = transaction(2, 4_000, TransactionType.REFUND)
        val unlinkedRefund = transaction(3, 1_000, TransactionType.REFUND)
        val transferDebit = transaction(4, 50_000, TransactionType.EXPENSE)
        val transferCredit = transaction(5, 50_000, TransactionType.INCOME)
        val links = listOf(
            TransactionLink(
                sourceTransactionId = purchase.id,
                targetTransactionId = linkedRefund.id,
                type = TransactionLinkType.REFUND,
            ),
            TransactionLink(
                sourceTransactionId = transferDebit.id,
                targetTransactionId = transferCredit.id,
                type = TransactionLinkType.TRANSFER,
            ),
        )

        val effective = buildActionableBudgetTransactions(
            listOf(purchase, linkedRefund, unlinkedRefund, transferDebit, transferCredit),
            links,
            emptyList(),
        )

        assertEquals(listOf(1L, 3L), effective.map(TransactionRecord::id))
        assertEquals(6_000L, effective.first().amountMinor)
        assertEquals(TransactionType.REFUND, effective.last().type)
    }

    @Test
    fun `delivery suppresses repetition but immediately permits escalation`() {
        val normal = candidate(ActionableAlertPriority.NORMAL)
        val sentAt = nowMillis - 2L * 60L * 60L * 1_000L
        val history = mapOf(
            normal.stableId to ActionableAlertDeliveryRecord(sentAt, ActionableAlertPriority.NORMAL),
        )

        assertTrue(
            selectActionableAlertsForDelivery(listOf(normal), history, nowMillis, 24).isEmpty(),
        )
        assertEquals(
            1,
            selectActionableAlertsForDelivery(
                listOf(normal.copy(priority = ActionableAlertPriority.CRITICAL)),
                history,
                nowMillis,
                24,
            ).size,
        )
    }

    @Test
    fun `never delivered alerts are selected before eligible repeats`() {
        val repeated = (1..5).map { index ->
            candidate(ActionableAlertPriority.HIGH).copy(stableId = "repeat-$index")
        }
        val unseen = candidate(ActionableAlertPriority.NORMAL).copy(stableId = "unseen")
        val history = repeated.associate { item ->
            item.stableId to ActionableAlertDeliveryRecord(
                sentAtMillis = nowMillis - 25L * 60L * 60L * 1_000L,
                priority = item.priority,
            )
        }

        val selected = selectActionableAlertsForDelivery(
            candidates = repeated + unseen,
            deliveryHistory = history,
            nowMillis = nowMillis,
            minimumRepeatHours = 24,
            maximumAlerts = 5,
        )

        assertTrue(unseen in selected)
        assertEquals(4, selected.count { it.stableId.startsWith("repeat-") })
    }

    @Test
    fun `highest priority selected alert remains notification destination`() {
        val escalatedHigh = candidate(ActionableAlertPriority.HIGH).copy(stableId = "escalated")
        val unseenCritical = candidate(ActionableAlertPriority.CRITICAL).copy(stableId = "critical")
        val history = mapOf(
            escalatedHigh.stableId to ActionableAlertDeliveryRecord(
                sentAtMillis = nowMillis - 60_000L,
                priority = ActionableAlertPriority.NORMAL,
            ),
        )

        val selected = selectActionableAlertsForDelivery(
            candidates = listOf(escalatedHigh, unseenCritical),
            deliveryHistory = history,
            nowMillis = nowMillis,
            minimumRepeatHours = 24,
        )

        assertEquals("critical", selected.first().stableId)
    }

    @Test
    fun `clock rollback makes future delivery history eligible again`() {
        val alert = candidate(ActionableAlertPriority.NORMAL)
        val history = mapOf(
            alert.stableId to ActionableAlertDeliveryRecord(
                sentAtMillis = nowMillis + 60L * 60L * 1_000L,
                priority = alert.priority,
            ),
        )

        assertEquals(
            listOf(alert),
            selectActionableAlertsForDelivery(
                candidates = listOf(alert),
                deliveryHistory = history,
                nowMillis = nowMillis,
                minimumRepeatHours = 24,
            ),
        )
    }

    @Test
    fun `notification content hides amounts and public text remains safe by default`() {
        val candidate = candidate(ActionableAlertPriority.HIGH).copy(amountMinor = 98_765)
        val hidden = buildActionableAlertText(
            listOf(candidate),
            ActionableAlertsConfiguration(enabled = true),
        )!!
        val revealed = buildActionableAlertText(
            listOf(candidate),
            ActionableAlertsConfiguration(enabled = true, showAmounts = true),
        )!!

        assertFalse(hidden.text.contains("987"))
        assertFalse(hidden.lines.single().contains("₹"))
        assertFalse(hidden.publicText.contains("987"))
        assertTrue(revealed.text.contains("987"))
        assertEquals("Open PaisaLens to review a money reminder", hidden.publicText)
    }

    @Test
    fun `daily trigger is always in the future`() {
        val now = ZonedDateTime.of(2026, 8, 20, 10, 30, 0, 0, zone)
        val next = nextActionableAlertTriggerAt(
            ActionableAlertsConfiguration(evaluationHour = 9),
            now,
        )

        assertEquals(today.plusDays(1), next.toLocalDate())
        assertEquals(9, next.hour)
        assertTrue(next.isAfter(now))
    }

    private fun candidate(priority: ActionableAlertPriority) = ActionableAlertCandidate(
        stableId = "test",
        category = ActionableAlertCategory.NEEDS_ATTENTION,
        priority = priority,
        title = "Review an item",
        detail = "Open PaisaLens when convenient.",
        destination = AlertDestination.ACTIVITY,
    )

    private fun transaction(id: Long, amountMinor: Long, type: TransactionType) = TransactionRecord(
        id = id,
        sourceMessageId = "test-$id",
        amountMinor = amountMinor,
        merchant = "Test",
        accountHint = "1234",
        category = ExpenseCategory.OTHER,
        type = type,
        occurredAt = nowMillis + id,
        source = TransactionSource.BANK,
        sender = "BANK",
    )
}
