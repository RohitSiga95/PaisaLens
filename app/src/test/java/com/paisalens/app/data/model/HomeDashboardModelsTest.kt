package com.paisalens.app.data.model

import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeDashboardModelsTest {
    private val zoneId: ZoneId = ZoneId.of("Asia/Kolkata")
    private val now: ZonedDateTime = ZonedDateTime.of(2026, 8, 20, 12, 0, 0, 0, zoneId)

    @Test
    fun `financial pulse subtracts scheduled obligations and explicit safety buffer`() {
        val snapshot = buildHomeDashboardSnapshot(
            transactions = emptyList(),
            effectiveExpenseTransactions = listOf(expense(100_000, now.toLocalDate())),
            accounts = listOf(
                AccountProfile(
                    id = 1,
                    name = "Daily account",
                    type = AccountType.BANK_ACCOUNT,
                    balanceMinor = 1_000_000,
                    availabilityFetchedAt = now.minusHours(2).toInstant().toEpochMilli(),
                ),
            ),
            legacyBudgets = emptyList(),
            advancedBudgets = emptyList(),
            manualBills = listOf(
                BillReminder(
                    id = 4,
                    title = "Electricity",
                    amountMinor = 200_000,
                    dueDateEpochDay = now.toLocalDate().plusDays(3).toEpochDay(),
                ),
            ),
            recurringPayments = emptyList(),
            loans = emptyList(),
            creditCardBills = emptyList(),
            paymentCommitments = emptyList(),
            savingsGoals = emptyList(),
            savingsContributions = emptyList(),
            now = now,
        )

        assertEquals(1_000_000L, snapshot.pulse.availableCashMinor)
        assertEquals(200_000L, snapshot.pulse.upcomingObligationsMinor)
        assertEquals(50_000L, snapshot.pulse.safetyBufferMinor)
        assertEquals(750_000L, snapshot.pulse.safeToSpendMinor)
        assertEquals(100_000L, snapshot.pulse.monthlySpendMinor)
        assertEquals(HomeBalanceFreshness.FRESH, snapshot.pulse.balanceFreshness)
        assertEquals(HomePulseConfidence.COMPLETE, snapshot.pulse.confidence)
    }

    @Test
    fun `financial pulse consolidates duplicate physical account profiles by last four`() {
        val snapshot = buildHomeDashboardSnapshot(
            transactions = emptyList(),
            effectiveExpenseTransactions = emptyList(),
            accounts = listOf(
                AccountProfile(
                    id = 1,
                    name = "Old sender profile",
                    type = AccountType.BANK_ACCOUNT,
                    accountHint = "••4432",
                    institution = "HDFC",
                    balanceMinor = 900_000,
                    availabilityFetchedAt = now.minusDays(2).toInstant().toEpochMilli(),
                ),
                AccountProfile(
                    id = 2,
                    name = "Renamed account",
                    type = AccountType.BANK_ACCOUNT,
                    accountHint = "4432",
                    institution = "HDFC Bank",
                    identityKey = "new-bank-parser-key",
                    balanceMinor = 1_000_000,
                    availabilityFetchedAt = now.minusHours(1).toInstant().toEpochMilli(),
                ),
            ),
            legacyBudgets = emptyList(),
            advancedBudgets = emptyList(),
            manualBills = emptyList(),
            recurringPayments = emptyList(),
            loans = emptyList(),
            creditCardBills = emptyList(),
            paymentCommitments = emptyList(),
            savingsGoals = emptyList(),
            savingsContributions = emptyList(),
            now = now,
        )

        assertEquals(1, snapshot.pulse.totalBalanceCount)
        assertEquals(1, snapshot.pulse.availableBalanceCount)
        assertEquals(1_000_000L, snapshot.pulse.availableCashMinor)
    }

    @Test
    fun `financial pulse treats an undated saved balance as partial confidence`() {
        val snapshot = buildHomeDashboardSnapshot(
            transactions = emptyList(),
            effectiveExpenseTransactions = emptyList(),
            accounts = listOf(
                AccountProfile(
                    id = 5,
                    name = "Cash account",
                    type = AccountType.BANK_ACCOUNT,
                    balanceMinor = 1_000_000,
                    availabilityFetchedAt = null,
                ),
            ),
            legacyBudgets = emptyList(),
            advancedBudgets = emptyList(),
            manualBills = emptyList(),
            recurringPayments = emptyList(),
            loans = emptyList(),
            creditCardBills = emptyList(),
            paymentCommitments = emptyList(),
            savingsGoals = emptyList(),
            savingsContributions = emptyList(),
            now = now,
        )

        assertEquals(HomeBalanceFreshness.UNAVAILABLE, snapshot.pulse.balanceFreshness)
        assertEquals(HomePulseConfidence.PARTIAL, snapshot.pulse.confidence)
        assertEquals(1_000_000L, snapshot.pulse.availableCashMinor)
    }

    @Test
    fun `financial pulse keeps same last four accounts separate across institutions and incomplete identities`() {
        val snapshot = buildHomeDashboardSnapshot(
            transactions = emptyList(),
            effectiveExpenseTransactions = emptyList(),
            accounts = listOf(
                AccountProfile(
                    id = 1,
                    name = "HDFC account",
                    type = AccountType.BANK_ACCOUNT,
                    accountHint = "4432",
                    institution = "HDFC",
                    balanceMinor = 600_000,
                    availabilityFetchedAt = now.toInstant().toEpochMilli(),
                ),
                AccountProfile(
                    id = 2,
                    name = "IDFC account",
                    type = AccountType.BANK_ACCOUNT,
                    accountHint = "4432",
                    institution = "IDFC",
                    balanceMinor = 400_000,
                    availabilityFetchedAt = now.toInstant().toEpochMilli(),
                ),
                AccountProfile(
                    id = 3,
                    name = "Unidentified A",
                    type = AccountType.BANK_ACCOUNT,
                    accountHint = "4432",
                    balanceMinor = 100_000,
                    availabilityFetchedAt = now.toInstant().toEpochMilli(),
                ),
                AccountProfile(
                    id = 4,
                    name = "Unidentified B",
                    type = AccountType.BANK_ACCOUNT,
                    accountHint = "4432",
                    balanceMinor = 50_000,
                    availabilityFetchedAt = now.toInstant().toEpochMilli(),
                ),
            ),
            legacyBudgets = emptyList(),
            advancedBudgets = emptyList(),
            manualBills = emptyList(),
            recurringPayments = emptyList(),
            loans = emptyList(),
            creditCardBills = emptyList(),
            paymentCommitments = emptyList(),
            savingsGoals = emptyList(),
            savingsContributions = emptyList(),
            now = now,
        )

        assertEquals(4, snapshot.pulse.totalBalanceCount)
        assertEquals(1_150_000L, snapshot.pulse.availableCashMinor)
    }

    @Test
    fun `timeline forecasts recurring income without adding duplicate reviewed commitment`() {
        val dueDate = now.toLocalDate().plusDays(5)
        val transactions = listOf(
            income(3_000_000, LocalDate.of(2026, 6, 27)),
            income(3_100_000, LocalDate.of(2026, 7, 28)),
        )
        val account = AccountProfile(id = 9, name = "Primary", type = AccountType.BANK_ACCOUNT)
        val timeline = buildHomeMoneyTimeline(
            transactions = transactions,
            accounts = listOf(account),
            manualBills = emptyList(),
            recurringPayments = listOf(
                RecurringPayment(
                    merchant = "Music Plus",
                    accountName = account.name,
                    typicalAmountMinor = 49_900,
                    intervalDays = 30,
                    lastPaidAt = dueDate.minusDays(30).atStartOfDay(zoneId).toInstant().toEpochMilli(),
                    nextDueAt = dueDate.atStartOfDay(zoneId).toInstant().toEpochMilli(),
                    occurrences = 3,
                    categoryLabel = "Entertainment",
                ),
            ),
            loans = emptyList(),
            creditCardBills = emptyList(),
            paymentCommitments = listOf(
                PaymentCommitment(
                    id = 7,
                    name = "Music Plus",
                    amountMinor = 49_900,
                    nextDueEpochDay = dueDate.toEpochDay(),
                    accountId = account.id,
                ),
            ),
            today = now.toLocalDate(),
            zoneId = zoneId,
        )

        assertEquals(1, timeline.items.count { it.title == "Music Plus" })
        val expectedIncome = timeline.items.single { it.source == HomeTimelineSource.EXPECTED_INCOME }
        assertTrue(expectedIncome.isEstimate)
        assertTrue(expectedIncome.isIncoming)
        assertEquals(LocalDate.of(2026, 8, 28), expectedIncome.date)
        assertEquals(3_100_000L, expectedIncome.amountMinor)
    }

    @Test
    fun `budget pace compares spending against elapsed Budgeting 2 point 0 plan`() {
        val plan = AdvancedBudgetPlan(
            id = 11,
            name = "Monthly plan",
            allocationMinor = 1_000_000,
            effectiveFromEpochDay = LocalDate.of(2026, 8, 1).toEpochDay(),
        )
        val pace = buildHomeBudgetPace(
            advancedBudgets = listOf(plan),
            legacyBudgets = emptyList(),
            effectiveExpenseTransactions = listOf(expense(700_000, now.toLocalDate())),
            today = now.toLocalDate(),
            zoneId = zoneId,
        )

        assertNotNull(pace)
        requireNotNull(pace)
        assertTrue(pace.usesAdvancedPlans)
        assertEquals(7_000, pace.spentBasisPoints)
        assertEquals(300_000L, pace.remainingMinor)
        assertTrue(pace.actualVsPlannedMinor > 0)
        assertTrue(pace.periodElapsedBasisPoints in 6_400..6_500)
    }

    @Test
    fun `legacy budget pace counts only categories with a positive budget`() {
        val pace = buildHomeBudgetPace(
            advancedBudgets = emptyList(),
            legacyBudgets = listOf(
                CategoryBudget(ExpenseCategory.FOOD, 500_000),
                CategoryBudget(ExpenseCategory.TRAVEL, 0),
            ),
            effectiveExpenseTransactions = listOf(
                expense(100_000, now.toLocalDate()).copy(category = ExpenseCategory.FOOD),
                expense(900_000, now.toLocalDate()).copy(category = ExpenseCategory.TRAVEL),
                expense(700_000, now.toLocalDate()).copy(category = ExpenseCategory.OTHER),
            ),
            today = now.toLocalDate(),
            zoneId = zoneId,
        )

        assertNotNull(pace)
        requireNotNull(pace)
        assertEquals(1, pace.planCount)
        assertEquals(500_000L, pace.availableMinor)
        assertEquals(100_000L, pace.spentMinor)
        assertEquals(2_000, pace.spentBasisPoints)
    }

    @Test
    fun `home budget pace subtracts confirmed unlinked refunds`() {
        val expense = expense(500_000, now.toLocalDate()).copy(id = 21)
        val refund = expense.copy(
            id = 22,
            sourceMessageId = "refund-22",
            amountMinor = 100_000,
            merchant = "Merchant refund",
            type = TransactionType.REFUND,
        )
        val snapshot = buildHomeDashboardSnapshot(
            transactions = listOf(expense, refund),
            effectiveExpenseTransactions = listOf(expense),
            transactionLinks = emptyList(),
            accounts = emptyList(),
            legacyBudgets = listOf(CategoryBudget(ExpenseCategory.OTHER, 1_000_000)),
            advancedBudgets = emptyList(),
            manualBills = emptyList(),
            recurringPayments = emptyList(),
            loans = emptyList(),
            creditCardBills = emptyList(),
            paymentCommitments = emptyList(),
            savingsGoals = emptyList(),
            savingsContributions = emptyList(),
            now = now,
        )

        assertEquals(400_000L, requireNotNull(snapshot.budgetPace).spentMinor)
        assertEquals(4_000, snapshot.budgetPace?.spentBasisPoints)
        assertEquals(400_000L, snapshot.pulse.monthlySpendMinor)
    }

    @Test
    fun `card health combines statement due with utilisation`() {
        val dueDate = now.toLocalDate().plusDays(6)
        val health = buildHomeCardHealth(
            accounts = listOf(
                AccountProfile(
                    id = 3,
                    name = "HDFC Millennia",
                    type = AccountType.CREDIT_CARD,
                    accountHint = "4432",
                    institution = "HDFC",
                    availableCreditMinor = 200_000,
                    creditLimitMinor = 1_000_000,
                ),
            ),
            creditCardBills = listOf(
                CreditCardBill(
                    id = 8,
                    billKey = "hdfc:4432:${dueDate.toEpochDay()}",
                    sourceMessageId = "bill-8",
                    accountId = null,
                    cardIdentityKey = "hdfc:4432",
                    accountHint = "4432",
                    institutionName = "HDFC",
                    totalDueMinor = 300_000,
                    minimumDueMinor = 25_000,
                    dueDateEpochDay = dueDate.toEpochDay(),
                    detectedAt = now.toInstant().toEpochMilli(),
                    sender = "HDFCBK",
                ),
            ),
        )

        assertEquals(300_000L, health.totalDueMinor)
        assertEquals(200_000L, health.totalAvailableCreditMinor)
        assertEquals(8_000, health.highestUtilizationBasisPoints)
        assertEquals(1, health.highUtilizationCount)
        assertEquals(dueDate, health.nextDueDate)
        assertEquals(1, health.cards.size)
        assertEquals("HDFC Millennia", health.cards.single().name)
        assertEquals(CreditUtilizationBand.CRITICAL, health.cards.single().utilizationBand)
    }

    @Test
    fun `card health combines availability and limit from duplicate physical card profiles`() {
        val health = buildHomeCardHealth(
            accounts = listOf(
                AccountProfile(
                    id = 31,
                    name = "HDFC card availability",
                    type = AccountType.CREDIT_CARD,
                    accountHint = "4432",
                    institution = "HDFC",
                    availableCreditMinor = 200_000,
                    availabilityFetchedAt = now.toInstant().toEpochMilli(),
                ),
                AccountProfile(
                    id = 32,
                    name = "HDFC card limit",
                    type = AccountType.CREDIT_CARD,
                    accountHint = "••4432",
                    institution = "HDFC Bank",
                    creditLimitMinor = 1_000_000,
                    availabilityFetchedAt = now.minusDays(1).toInstant().toEpochMilli(),
                ),
            ),
            creditCardBills = emptyList(),
        )

        assertEquals(1, health.cards.size)
        with(health.cards.single()) {
            assertEquals(200_000L, availableCreditMinor)
            assertEquals(1_000_000L, creditLimitMinor)
            assertEquals(8_000, utilizationBasisPoints)
            assertEquals(CreditUtilizationBand.CRITICAL, utilizationBand)
        }
    }

    @Test
    fun `stale recurring income does not shorten the safe to spend obligation horizon`() {
        val dueDate = now.toLocalDate().plusDays(10)
        val snapshot = buildHomeDashboardSnapshot(
            transactions = listOf(
                income(3_000_000, LocalDate.of(2026, 1, 27)),
                income(3_100_000, LocalDate.of(2026, 2, 27)),
            ),
            effectiveExpenseTransactions = emptyList(),
            accounts = listOf(
                AccountProfile(
                    id = 41,
                    name = "Primary",
                    type = AccountType.BANK_ACCOUNT,
                    balanceMinor = 1_000_000,
                    availabilityFetchedAt = now.toInstant().toEpochMilli(),
                ),
            ),
            legacyBudgets = emptyList(),
            advancedBudgets = emptyList(),
            manualBills = listOf(
                BillReminder(
                    title = "Insurance",
                    amountMinor = 250_000,
                    dueDateEpochDay = dueDate.toEpochDay(),
                ),
            ),
            recurringPayments = emptyList(),
            loans = emptyList(),
            creditCardBills = emptyList(),
            paymentCommitments = emptyList(),
            savingsGoals = emptyList(),
            savingsContributions = emptyList(),
            now = now,
        )

        assertTrue(snapshot.timeline.items.none { it.source == HomeTimelineSource.EXPECTED_INCOME })
        assertEquals(now.toLocalDate().plusDays(13), snapshot.pulse.throughDate)
        assertEquals(250_000L, snapshot.pulse.upcomingObligationsMinor)
    }

    private fun expense(amountMinor: Long, date: LocalDate) = TransactionRecord(
        sourceMessageId = "expense-$amountMinor-$date",
        amountMinor = amountMinor,
        merchant = "Merchant",
        accountHint = null,
        category = ExpenseCategory.OTHER,
        type = TransactionType.EXPENSE,
        occurredAt = date.atTime(12, 0).atZone(zoneId).toInstant().toEpochMilli(),
        source = TransactionSource.MANUAL,
        sender = "Manual",
    )

    private fun income(amountMinor: Long, date: LocalDate) = TransactionRecord(
        sourceMessageId = "income-$amountMinor-$date",
        amountMinor = amountMinor,
        merchant = "Employer salary",
        accountHint = null,
        category = ExpenseCategory.INCOME,
        type = TransactionType.INCOME,
        occurredAt = date.atTime(12, 0).atZone(zoneId).toInstant().toEpochMilli(),
        source = TransactionSource.BANK,
        sender = "BANK",
    )
}
