package com.paisalens.app.data.model

import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test

class FinancialPlanningModelsTest {
    private val utc: ZoneId = ZoneOffset.UTC

    @Test
    fun userEnteredUpiBalanceAcceptsOnlySafeBankAccountValues() {
        val now = 1_800_000_000_000L

        validateUserEnteredUpiBalance(
            accountId = 7,
            accountType = AccountType.BANK_ACCOUNT,
            balanceMinor = -MAX_USER_ENTERED_BALANCE_MINOR,
            recordedAt = now,
            now = now,
        )
        validateUserEnteredUpiBalance(
            accountId = 7,
            accountType = AccountType.BANK_ACCOUNT,
            balanceMinor = MAX_USER_ENTERED_BALANCE_MINOR,
            recordedAt = now,
            now = now,
        )

        assertThrows(IllegalArgumentException::class.java) {
            validateUserEnteredUpiBalance(7, AccountType.CREDIT_CARD, 10_000, now, now)
        }
        assertThrows(IllegalArgumentException::class.java) {
            validateUserEnteredUpiBalance(
                7,
                AccountType.BANK_ACCOUNT,
                -MAX_USER_ENTERED_BALANCE_MINOR - 1,
                now,
                now,
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            validateUserEnteredUpiBalance(
                7,
                AccountType.BANK_ACCOUNT,
                MAX_USER_ENTERED_BALANCE_MINOR + 1,
                now,
                now,
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            validateUserEnteredUpiBalance(0, AccountType.BANK_ACCOUNT, 10_000, now, now)
        }
        assertThrows(IllegalArgumentException::class.java) {
            validateUserEnteredUpiBalance(7, AccountType.BANK_ACCOUNT, 10_000, now + 300_001, now)
        }
        assertEquals(
            "User entered after UPI check",
            balanceSourceDisplayName(USER_ENTERED_UPI_BALANCE_SOURCE),
        )
        val phonePeSource = encodeUserEnteredUpiBalanceSource(" User   entered after PhonePe check ")
        assertEquals("USER_ENTERED_UPI|PhonePe", phonePeSource)
        assertEquals("User entered after PhonePe check", balanceSourceDisplayName(phonePeSource))
        assertEquals(
            USER_ENTERED_UPI_BALANCE_SOURCE,
            encodeUserEnteredUpiBalanceSource("Untrusted sender label"),
        )
        assertEquals(
            "USER_ENTERED_UPI|UnsafeApp",
            encodeUserEnteredUpiBalanceSource("User entered after Unsafe|App<> check"),
        )
        assertEquals("VM-HDFCBK", balanceSourceDisplayName("VM-HDFCBK"))
    }

    @Test
    fun dailyBalanceHistoryKeepsLatestSnapshotPerAccountAndDay() {
        val first = instant(LocalDate.of(2026, 8, 1), 8)
        val latest = instant(LocalDate.of(2026, 8, 1), 18)
        val creditOnly = instant(LocalDate.of(2026, 8, 1), 20)
        val history = buildDailyBalanceHistory(
            snapshots = listOf(
                AccountBalanceSnapshot(1, 7, 100_000, null, null, first, "Bank"),
                AccountBalanceSnapshot(2, 7, 125_000, null, null, latest, "Bank"),
                AccountBalanceSnapshot(4, 7, null, 50_000, 100_000, creditOnly, "Bank"),
                AccountBalanceSnapshot(3, 8, 90_000, null, null, first, "Bank"),
            ),
            zoneId = utc,
            accountId = 7,
        )

        assertEquals(1, history.size)
        assertEquals(125_000L, history.single().balanceMinor)
        assertEquals(50_000L, history.single().availableCreditMinor)
        assertEquals(creditOnly, history.single().recordedAt)
    }

    @Test
    fun creditUtilizationClampsAvailabilityAndUsesClearBands() {
        assertEquals(
            CreditUtilizationBand.HEALTHY,
            calculateCreditUtilization(1, 80_000, 100_000).band,
        )
        assertEquals(
            CreditUtilizationBand.MODERATE,
            calculateCreditUtilization(1, 60_000, 100_000).band,
        )
        assertEquals(
            CreditUtilizationBand.HIGH,
            calculateCreditUtilization(1, 40_000, 100_000).band,
        )
        val critical = calculateCreditUtilization(1, -500, 100_000)
        assertEquals(CreditUtilizationBand.CRITICAL, critical.band)
        assertEquals(100_000L, critical.usedMinor)
        assertEquals(10_000, critical.utilizationBasisPoints)
        val overAvailable = calculateCreditUtilization(1, 120_000, 100_000)
        assertEquals(0L, overAvailable.usedMinor)
        assertEquals(0, overAvailable.utilizationBasisPoints)
        assertEquals(CreditUtilizationBand.HEALTHY, overAvailable.band)
        assertEquals(CreditUtilizationBand.MODERATE, calculateCreditUtilization(1, 70_000, 100_000).band)
        assertEquals(CreditUtilizationBand.HIGH, calculateCreditUtilization(1, 50_000, 100_000).band)
        assertEquals(CreditUtilizationBand.CRITICAL, calculateCreditUtilization(1, 25_000, 100_000).band)
        assertNull(calculateCreditUtilization(1, 50_000, null).utilizationBasisPoints)
    }

    @Test
    fun currentAccountCreditLimitOverridesStaleHistory() {
        val account = AccountProfile(
            id = 11,
            name = "Current card",
            type = AccountType.CREDIT_CARD,
            availableCreditMinor = 70_000,
            creditLimitMinor = 100_000,
        )
        val stale = AccountBalanceSnapshot(
            id = 12,
            accountId = account.id,
            balanceMinor = null,
            availableCreditMinor = 70_000,
            creditLimitMinor = 200_000,
            recordedAt = 1,
        )

        val utilization = buildCreditUtilizations(listOf(account), listOf(stale)).single()

        assertEquals(100_000L, utilization.creditLimitMinor)
        assertEquals(30_000L, utilization.usedMinor)
        assertEquals(3_000, utilization.utilizationBasisPoints)
    }

    @Test
    fun mergedCardWithPartialAvailabilityDoesNotProduceUtilization() {
        val account = AccountProfile(
            id = 21,
            name = "Combined cards",
            type = AccountType.CREDIT_CARD,
            availableCreditMinor = 70_000,
            creditLimitMinor = 200_000,
            availabilityFetchedAt = null,
            mergedMemberCount = 2,
        )
        val oneMemberSnapshot = AccountBalanceSnapshot(
            id = 22,
            accountId = account.id,
            balanceMinor = null,
            availableCreditMinor = 70_000,
            creditLimitMinor = 100_000,
            recordedAt = 1,
        )

        val utilization = buildCreditUtilizations(listOf(account), listOf(oneMemberSnapshot)).single()

        assertNull(utilization.availableCreditMinor)
        assertNull(utilization.utilizationBasisPoints)
        assertEquals(CreditUtilizationBand.UNKNOWN, utilization.band)
    }

    @Test
    fun dueCentreCombinesManualRecurringAndLoanItems() {
        val today = LocalDate.of(2026, 8, 7)
        val items = buildDueItems(
            manualBills = listOf(
                BillReminder(
                    id = 1,
                    title = "Electricity",
                    amountMinor = 120_000,
                    dueDateEpochDay = LocalDate.of(2026, 8, 6).toEpochDay(),
                ),
                BillReminder(
                    id = 2,
                    title = "Internet",
                    amountMinor = 80_000,
                    dueDateEpochDay = LocalDate.of(2026, 7, 1).toEpochDay(),
                    recurrenceMonths = 1,
                    lastPaidEpochDay = LocalDate.of(2026, 7, 1).toEpochDay(),
                ),
            ),
            recurringPayments = listOf(
                RecurringPayment(
                    merchant = "Music",
                    accountName = "Card",
                    typicalAmountMinor = 49_900,
                    intervalDays = 30,
                    lastPaidAt = instant(LocalDate.of(2026, 7, 8)),
                    nextDueAt = instant(LocalDate.of(2026, 8, 8)),
                    occurrences = 4,
                    categoryLabel = "Entertainment",
                ),
            ),
            loans = listOf(
                LoanAccount(
                    id = 3,
                    name = "Car loan",
                    lender = "Bank",
                    principalMinor = 1_000_000,
                    annualRateBasisPoints = 0,
                    tenureMonths = 12,
                    startDateEpochDay = LocalDate.of(2026, 6, 15).toEpochDay(),
                    emiMinor = 100_000,
                    paidInstallments = 2,
                ),
            ),
            today = today,
            zoneId = utc,
        )

        assertEquals(4, items.size)
        assertEquals(DueStatus.OVERDUE, items.first { it.title == "Electricity" }.status)
        assertEquals(LocalDate.of(2026, 8, 1), items.first { it.title == "Internet" }.dueDate)
        assertEquals(DueStatus.DUE_SOON, items.first { it.title == "Music" }.status)
        assertEquals(DueStatus.UPCOMING, items.first { it.title == "Car loan" }.status)
    }

    @Test
    fun cashFlowForecastUsesHistoricalBaselineAndScheduledBills() {
        val asOf = LocalDate.of(2026, 1, 11)
        val due = DueItem(
            stableId = "bill:1",
            source = DueItemSource.MANUAL_BILL,
            title = "Rent",
            amountMinor = 15_000,
            dueDate = asOf,
            status = DueStatus.DUE_TODAY,
        )
        val forecast = buildCashFlowForecast(
            openingBalanceMinor = 50_000,
            transactions = listOf(
                transaction(1, "Salary", 100_000, LocalDate.of(2026, 1, 1), TransactionType.INCOME),
                transaction(2, "Rent", 30_000, LocalDate.of(2026, 1, 2)),
                transaction(3, "Cafe", 20_000, LocalDate.of(2026, 1, 3)),
            ),
            dueItems = listOf(due),
            asOf = asOf,
            zoneId = utc,
            horizonDays = 2,
            lookbackDays = 10,
        )

        assertEquals(10_000L, forecast.baseline.averageDailyIncomeMinor)
        assertEquals(2_000L, forecast.baseline.averageDailyFlexibleExpenseMinor)
        assertEquals(43_000L, forecast.points.first().projectedBalanceMinor)
        assertEquals(51_000L, forecast.endingBalanceMinor)
    }

    @Test
    fun dueHorizonIsExclusiveAndOverdueBillsAreChargedOnForecastDayZero() {
        val today = LocalDate.of(2026, 8, 7)
        val bills = listOf(
            BillReminder(1, "Overdue", 10_000, today.minusDays(2).toEpochDay()),
            BillReminder(2, "Last included", 20_000, today.plusDays(2).toEpochDay()),
            BillReminder(3, "Exclusive boundary", 40_000, today.plusDays(3).toEpochDay()),
        )
        val dueItems = buildDueItems(
            bills,
            emptyList(),
            emptyList(),
            today,
            utc,
            horizonDays = 3,
        )

        assertEquals(listOf("Overdue", "Last included"), dueItems.map { it.title })

        val forecast = buildCashFlowForecast(
            openingBalanceMinor = 100_000,
            transactions = emptyList(),
            dueItems = dueItems,
            asOf = today,
            zoneId = utc,
            horizonDays = 3,
        )

        assertEquals(10_000L, forecast.points[0].scheduledExpenseMinor)
        assertEquals(20_000L, forecast.points[2].scheduledExpenseMinor)
        assertEquals(70_000L, forecast.endingBalanceMinor)
    }

    @Test
    fun repeatingDuesExpandOnlyWhenForecastRequestsOccurrences() {
        val today = LocalDate.of(2026, 8, 7)
        val bill = BillReminder(
            id = 4,
            title = "Rent",
            amountMinor = 100_000,
            dueDateEpochDay = today.toEpochDay(),
            recurrenceMonths = 1,
        )

        val centre = buildDueItems(listOf(bill), emptyList(), emptyList(), today, utc, horizonDays = 65)
        val forecast = buildDueItems(
            listOf(bill),
            emptyList(),
            emptyList(),
            today,
            utc,
            horizonDays = 65,
            includeRepeatingOccurrences = true,
        )

        assertEquals(1, centre.size)
        assertEquals(
            listOf(today, today.plusMonths(1), today.plusMonths(2)),
            forecast.map { it.dueDate },
        )
    }

    @Test
    fun repeatingBillsAndLoansPreserveMonthEndAnchor() {
        val januaryEnd = LocalDate.of(2026, 1, 31)
        val bill = BillReminder(
            id = 8,
            title = "Month-end bill",
            amountMinor = 20_000,
            dueDateEpochDay = januaryEnd.toEpochDay(),
            recurrenceMonths = 1,
        )
        val loan = LoanAccount(
            id = 9,
            name = "Month-end loan",
            lender = "Bank",
            principalMinor = 300_000,
            annualRateBasisPoints = 0,
            tenureMonths = 3,
            startDateEpochDay = januaryEnd.toEpochDay(),
            emiMinor = 100_000,
        )

        val dueItems = buildDueItems(
            listOf(bill),
            emptyList(),
            listOf(loan),
            januaryEnd,
            utc,
            horizonDays = 60,
            includeRepeatingOccurrences = true,
        )

        val expected = listOf(
            LocalDate.of(2026, 1, 31),
            LocalDate.of(2026, 2, 28),
            LocalDate.of(2026, 3, 31),
        )
        assertEquals(expected, dueItems.filter { it.source == DueItemSource.MANUAL_BILL }.map { it.dueDate })
        assertEquals(expected, dueItems.filter { it.source == DueItemSource.LOAN_EMI }.map { it.dueDate })
    }

    @Test
    fun netWorthConsolidatesDuplicateAccountsCardsLoansAndManualItems() {
        val accounts = listOf(
            AccountProfile(
                id = 1,
                name = "Bank old",
                type = AccountType.BANK_ACCOUNT,
                accountHint = "0801",
                institution = "HDFC",
                balanceMinor = 200_000,
                availabilityFetchedAt = 1,
            ),
            AccountProfile(
                id = 2,
                name = "Bank current",
                type = AccountType.BANK_ACCOUNT,
                accountHint = "0801",
                institution = "HDFC Bank",
                balanceMinor = 250_000,
                availabilityFetchedAt = 2,
            ),
            AccountProfile(
                id = 3,
                name = "Card",
                type = AccountType.CREDIT_CARD,
                availableCreditMinor = 70_000,
                creditLimitMinor = 100_000,
            ),
        )
        val loan = LoanAccount(
            id = 4,
            name = "Loan",
            lender = "Bank",
            principalMinor = 120_000,
            annualRateBasisPoints = 0,
            tenureMonths = 12,
            startDateEpochDay = LocalDate.of(2026, 1, 1).toEpochDay(),
            emiMinor = 10_000,
            paidInstallments = 2,
        )
        val manual = NetWorthItem(5, "Cash investment", NetWorthKind.ASSET, 50_000, "Investment", 3)

        val summary = buildNetWorthSummary(
            accounts,
            listOf(loan),
            listOf(manual),
            creditLimitsByAccountId = mapOf(3L to 200_000L),
        )

        assertEquals(300_000L, summary.assetsMinor)
        assertEquals(130_000L, summary.liabilitiesMinor)
        assertEquals(170_000L, summary.netWorthMinor)
        assertEquals(4, summary.items.size)
    }

    @Test
    fun netWorthExcludesIncompleteMergedBankBalance() {
        val summary = buildNetWorthSummary(
            accounts = listOf(
                AccountProfile(
                    id = 20,
                    name = "Combined banks",
                    type = AccountType.BANK_ACCOUNT,
                    balanceMinor = 250_000,
                    availabilityFetchedAt = null,
                    mergedMemberCount = 2,
                ),
            ),
            loans = emptyList(),
            manualItems = emptyList(),
        )

        assertEquals(0L, summary.assetsMinor)
        assertTrue(summary.items.isEmpty())
    }

    @Test
    fun netWorthExcludesMergedCardWithIncompleteAvailabilityOrLimit() {
        val summary = buildNetWorthSummary(
            accounts = listOf(
                AccountProfile(
                    id = 30,
                    name = "Partial availability",
                    type = AccountType.CREDIT_CARD,
                    accountHint = "1111",
                    availableCreditMinor = 40_000,
                    creditLimitMinor = 100_000,
                    availabilityFetchedAt = null,
                    mergedMemberCount = 2,
                ),
                AccountProfile(
                    id = 31,
                    name = "Missing member limit",
                    type = AccountType.CREDIT_CARD,
                    accountHint = "2222",
                    availableCreditMinor = 30_000,
                    creditLimitMinor = null,
                    availabilityFetchedAt = 100,
                    mergedMemberCount = 2,
                ),
            ),
            loans = emptyList(),
            manualItems = emptyList(),
            creditLimitsByAccountId = mapOf(31L to 200_000L),
        )

        assertEquals(0L, summary.liabilitiesMinor)
        assertTrue(summary.items.isEmpty())
    }

    @Test
    fun netWorthKeepsDifferentBanksWithSameLastFourSeparate() {
        val summary = buildNetWorthSummary(
            accounts = listOf(
                AccountProfile(
                    id = 40,
                    name = "HDFC salary",
                    type = AccountType.BANK_ACCOUNT,
                    accountHint = "1234",
                    institution = "HDFC Bank",
                    balanceMinor = 100_000,
                    availabilityFetchedAt = 100,
                ),
                AccountProfile(
                    id = 41,
                    name = "IDFC savings",
                    type = AccountType.BANK_ACCOUNT,
                    accountHint = "1234",
                    institution = "IDFC FIRST Bank",
                    balanceMinor = 200_000,
                    availabilityFetchedAt = 200,
                ),
            ),
            loans = emptyList(),
            manualItems = emptyList(),
        )

        assertEquals(300_000L, summary.assetsMinor)
        assertEquals(2, summary.items.size)
    }

    @Test
    fun smartRulesMatchSafelyPreviewAndRespectPriority() {
        val row = transaction(9, "AMAZON Marketplace", 70_000, LocalDate.of(2026, 8, 1))
            .copy(accountId = 4)
        val broad = SmartCategoryRule(
            id = 1,
            name = "Shopping",
            merchantPattern = "amazon",
            matchType = SmartRuleMatchType.CONTAINS,
            accountId = 4,
            category = ExpenseCategory.SHOPPING,
            priority = 1,
            updatedAt = 1,
        )
        val specific = broad.copy(
            id = 2,
            name = "Large Amazon",
            minAmountMinor = 50_000,
            category = ExpenseCategory.OTHER,
            priority = 5,
        )

        val preview = previewSmartCategoryRule(specific, listOf(row))

        assertTrue(specific.matches(row))
        assertEquals(1, preview.matchedCount)
        assertEquals(70_000L, preview.totalAmountMinor)
        assertEquals(specific, findMatchingSmartCategoryRule(row, listOf(broad, specific)))
        assertFalse(specific.copy(merchantPattern = "[").copy(matchType = SmartRuleMatchType.REGEX).matches(row))
    }

    @Test
    fun whatIfSimulationComparesMonthlyScenarioWithBaseline() {
        val simulation = simulateWhatIfMonthly(
            openingBalanceMinor = 100_000,
            monthlyIncomeMinor = 50_000,
            monthlyFixedExpenseMinor = 20_000,
            monthlyFlexibleExpenseMinor = 10_000,
            scenario = WhatIfScenario(
                name = "Spend less",
                extraMonthlyIncomeMinor = 5_000,
                flexibleExpenseReductionBasisPoints = 1_000,
                oneTimeExpenseMinor = 10_000,
                oneTimeExpenseMonth = 2,
            ),
            months = 3,
        )

        assertEquals(160_000L, simulation.baselineEndingMinor)
        assertEquals(168_000L, simulation.scenarioEndingMinor)
        assertEquals(8_000L, simulation.improvementMinor)
        assertEquals(listOf(6_000L, 2_000L, 8_000L), simulation.points.map { it.improvementMinor })
    }

    @Test
    fun paymentCommitmentDuesAdvanceStaleAnchorAndRepeatWithinHorizon() {
        val today = LocalDate.of(2026, 3, 1)
        val commitment = PaymentCommitment(
            id = 7,
            name = "Streaming",
            amountMinor = 499_00,
            nextDueEpochDay = LocalDate.of(2026, 1, 31).toEpochDay(),
            accountId = 4,
        )

        val due = buildPaymentCommitmentDueItems(
            commitments = listOf(commitment),
            today = today,
            horizonDays = 70,
            includeRepeatingOccurrences = true,
            accountNamesById = mapOf(4L to "Primary card"),
        )

        assertEquals(listOf(LocalDate.of(2026, 3, 31), LocalDate.of(2026, 4, 30)), due.map { it.dueDate })
        assertTrue(due.all { it.source == DueItemSource.PAYMENT_COMMITMENT })
        assertTrue(due.all { it.accountName == "Primary card" })
    }

    private fun instant(date: LocalDate, hour: Int = 0): Long =
        date.atTime(hour, 0).atZone(utc).toInstant().toEpochMilli()

    private fun transaction(
        id: Long,
        merchant: String,
        amountMinor: Long,
        date: LocalDate,
        type: TransactionType = TransactionType.EXPENSE,
    ) = TransactionRecord(
        id = id,
        sourceMessageId = "planning-$id",
        amountMinor = amountMinor,
        merchant = merchant,
        accountHint = null,
        category = ExpenseCategory.OTHER,
        type = type,
        occurredAt = instant(date),
        source = TransactionSource.MANUAL,
        sender = "Test",
    )
}
