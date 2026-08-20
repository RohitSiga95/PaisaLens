package com.paisalens.app.data.model

import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WeeklyReviewModelsTest {
    private val utc: ZoneId = ZoneOffset.UTC
    private val today = LocalDate.of(2026, 8, 14)

    @Test
    fun attentionCombinesReviewBalancesBillsGoalsCommitmentsAndBackup() {
        val goalCreated = today.minusDays(100).atStartOfDay(utc).toInstant().toEpochMilli()
        val input = PlanningReviewInput(
            transactions = listOf(
                transaction(1, 1_000, today.minusDays(1), review = ReviewStatus.NEEDS_REVIEW),
                transaction(2, 2_000, today.minusDays(2), review = ReviewStatus.NEEDS_REVIEW),
            ),
            accounts = listOf(
                AccountProfile(1, "Daily bank", AccountType.BANK_ACCOUNT, availabilityFetchedAt = null),
                AccountProfile(
                    2,
                    "Fresh card",
                    AccountType.CREDIT_CARD,
                    availabilityFetchedAt = today.minusDays(1).atStartOfDay(utc).toInstant().toEpochMilli(),
                ),
            ),
            bills = listOf(
                BillReminder(1, "Electricity", 5_000, today.minusDays(1).toEpochDay()),
            ),
            savingsGoals = listOf(
                SavingsGoal(
                    id = 9,
                    name = "Emergency fund",
                    targetMinor = 100_000,
                    targetDateEpochDay = today.plusDays(100).toEpochDay(),
                    createdAt = goalCreated,
                    updatedAt = goalCreated,
                ),
            ),
            savingsContributions = emptyList(),
            paymentCommitments = listOf(
                PaymentCommitment(
                    id = 4,
                    name = "Music",
                    amountMinor = 999,
                    nextDueEpochDay = today.plusDays(2).toEpochDay(),
                ),
            ),
            backup = BackupReviewState(
                lastSuccessfulAt = today.minusDays(2).atStartOfDay(utc).toInstant().toEpochMilli(),
                lastFailureAt = today.minusDays(1).atStartOfDay(utc).toInstant().toEpochMilli(),
                lastFailureMessage = "Folder permission was removed",
                scheduledBackupEnabled = true,
            ),
        )

        val summary = buildNeedsAttentionSummary(input, today, utc)

        assertEquals(6, summary.items.size)
        assertEquals(7, summary.totalActionCount)
        assertEquals(1, summary.urgentActionCount)
        assertEquals(AttentionKind.BILL_DUE, summary.items.first().kind)
        assertEquals(2, summary.items.first { it.kind == AttentionKind.TRANSACTIONS_TO_REVIEW }.count)
        assertEquals(BackupReviewHealth.FAILED, backupReviewHealth(input.backup, today, utc))
        assertTrue(summary.items.any { it.kind == AttentionKind.SAVINGS_GOAL_BEHIND })
        assertTrue(summary.items.any { it.kind == AttentionKind.PAYMENT_COMMITMENT_DUE })
    }

    @Test
    fun weeklyReviewUsesSevenCalendarDaysAndCalmHeadline() {
        val input = PlanningReviewInput(
            transactions = listOf(
                transaction(1, 10_000, today, type = TransactionType.INCOME),
                transaction(2, 4_000, today.minusDays(6)),
                transaction(3, 3_000, today.minusDays(7)),
                transaction(4, 1_000, today.minusDays(1), type = TransactionType.REFUND),
            ),
            accounts = emptyList(),
            bills = emptyList(),
            savingsGoals = emptyList(),
            savingsContributions = emptyList(),
            paymentCommitments = emptyList(),
            backup = BackupReviewState(
                lastSuccessfulAt = today.minusDays(2).atStartOfDay(utc).toInstant().toEpochMilli(),
                lastVerifiedAt = today.minusDays(2).atStartOfDay(utc).toInstant().toEpochMilli(),
            ),
        )

        val review = buildWeeklyReview(input, today, utc)

        assertEquals(BudgetDateRange(today.minusDays(6), today), review.period)
        assertEquals(3, review.transactionCount)
        assertEquals(3_000L, review.expenseMinor)
        assertEquals(10_000L, review.incomeMinor)
        assertEquals(1_000L, review.refundMinor)
        assertEquals(7_000L, review.netCashFlowMinor)
        assertEquals(WeeklyReviewTone.ALL_CLEAR, review.tone)
        assertEquals("Everything is up to date", review.headline)
        assertNull(review.recommendedAction)
    }

    @Test
    fun weeklyReviewUsesLinkAndSplitAwareNetSpending() {
        val expense = transaction(1, 10_000, today)
        val reimbursement = transaction(2, 4_000, today, type = TransactionType.INCOME)
        val input = PlanningReviewInput(
            transactions = listOf(expense, reimbursement),
            accounts = emptyList(),
            bills = emptyList(),
            savingsGoals = emptyList(),
            savingsContributions = emptyList(),
            paymentCommitments = emptyList(),
            transactionLinks = listOf(
                TransactionLink(
                    id = 3,
                    sourceTransactionId = expense.id,
                    targetTransactionId = reimbursement.id,
                    type = TransactionLinkType.REIMBURSEMENT,
                ),
            ),
            expenseSplits = listOf(
                ExpenseSplit(
                    id = 4,
                    transactionId = expense.id,
                    participantName = "Asha",
                    shareMinor = 5_000,
                    reimbursedMinor = 5_000,
                    linkedIncomingTransactionId = reimbursement.id,
                    status = ExpenseSplitStatus.REIMBURSED,
                ),
            ),
            backup = today.atStartOfDay(utc).toInstant().toEpochMilli().let {
                BackupReviewState(lastSuccessfulAt = it, lastVerifiedAt = it)
            },
        )

        val review = buildWeeklyReview(input, today, utc)

        assertEquals(5_000L, review.expenseMinor)
        assertEquals(0L, review.incomeMinor)
        assertEquals(-5_000L, review.netCashFlowMinor)
    }

    @Test
    fun backupAgeBoundaryAndFailurePrecedenceAreExplicit() {
        val atBoundary = today.minusDays(30).atStartOfDay(utc).toInstant().toEpochMilli()
        assertEquals(
            BackupReviewHealth.DUE,
            backupReviewHealth(
                BackupReviewState(lastSuccessfulAt = atBoundary, lastVerifiedAt = atBoundary),
                today,
                utc,
            ),
        )
        assertEquals(
            BackupReviewHealth.NEVER_CREATED,
            backupReviewHealth(BackupReviewState(), today, utc),
        )
        assertEquals(
            BackupReviewHealth.FAILED,
            backupReviewHealth(
                BackupReviewState(lastSuccessfulAt = atBoundary, lastFailureAt = atBoundary + 1),
                today,
                utc,
            ),
        )
        assertEquals(
            BackupReviewHealth.UNVERIFIED,
            backupReviewHealth(
                BackupReviewState(lastSuccessfulAt = atBoundary, lastVerifiedAt = 0),
                today,
                utc,
            ),
        )
    }

    @Test
    fun latestUnpaidCardCycleAppearsInAttentionAndRoutesToCardBills() {
        val input = PlanningReviewInput(
            transactions = emptyList(),
            accounts = emptyList(),
            bills = emptyList(),
            creditCardBills = listOf(
                CreditCardBill(
                    id = 7,
                    billKey = "hdfc:0801:${today.plusDays(2).toEpochDay()}",
                    sourceMessageId = "sms-card-7",
                    cardIdentityKey = "card:hdfc:0801",
                    accountHint = "0801",
                    institutionName = "HDFC Bank",
                    totalDueMinor = 42_500,
                    dueDateEpochDay = today.plusDays(2).toEpochDay(),
                    detectedAt = today.atStartOfDay(utc).toInstant().toEpochMilli(),
                    sender = "HDFCBK",
                ),
            ),
            savingsGoals = emptyList(),
            savingsContributions = emptyList(),
            paymentCommitments = emptyList(),
            backup = today.atStartOfDay(utc).toInstant().toEpochMilli().let {
                BackupReviewState(lastSuccessfulAt = it, lastVerifiedAt = it)
            },
        )

        val summary = buildNeedsAttentionSummary(input, today, utc)
        val item = summary.items.single()

        assertEquals(AttentionKind.CREDIT_CARD_BILL_DUE, item.kind)
        assertEquals(AttentionAction.OPEN_CREDIT_CARD_BILLS, item.action)
        assertEquals(42_500L, item.amountMinor)
    }

    @Test
    fun futureReviewTransactionsAreNotActionableYet() {
        val future = transaction(1, 1_000, today.plusDays(1), review = ReviewStatus.NEEDS_REVIEW)
        val summary = buildNeedsAttentionSummary(
            PlanningReviewInput(
                transactions = listOf(future),
                accounts = emptyList(),
                bills = emptyList(),
                savingsGoals = emptyList(),
                savingsContributions = emptyList(),
                paymentCommitments = emptyList(),
                backup = today.atStartOfDay(utc).toInstant().toEpochMilli().let { backedUpAt ->
                    BackupReviewState(
                        lastSuccessfulAt = backedUpAt,
                        lastVerifiedAt = backedUpAt,
                    )
                },
            ),
            today,
            utc,
        )

        assertTrue(summary.isClear)
        assertFalse(summary.items.any { it.kind == AttentionKind.TRANSACTIONS_TO_REVIEW })
    }

    @Test
    fun duplicateProfilesForOnePhysicalAccountProduceOneStaleBalanceAction() {
        val summary = buildNeedsAttentionSummary(
            quietInput(
                accounts = listOf(
                    AccountProfile(
                        id = 21,
                        name = "Old HDFC label",
                        type = AccountType.BANK_ACCOUNT,
                        accountHint = "XX-4321",
                        balanceMinor = 12_000,
                        availabilityFetchedAt = timestamp(today.minusDays(20)),
                    ),
                    AccountProfile(
                        id = 22,
                        name = "Salary account",
                        type = AccountType.BANK_ACCOUNT,
                        accountHint = "a/c 4321",
                        balanceMinor = 15_000,
                        availabilityFetchedAt = timestamp(today.minusDays(9)),
                    ),
                ),
            ),
            today,
            utc,
        )

        val stale = summary.items.single { it.kind == AttentionKind.STALE_ACCOUNT_BALANCE }
        assertEquals(22L, stale.accountId)
        assertEquals("Refresh Salary account", stale.title)
        assertEquals("account:BANK_ACCOUNT:last4:4321:stale", stale.stableId)
        assertEquals(
            1,
            buildWeeklyReview(
                quietInput(
                    accounts = listOf(
                        AccountProfile(
                            id = 21,
                            name = "Old HDFC label",
                            type = AccountType.BANK_ACCOUNT,
                            accountHint = "4321",
                            availabilityFetchedAt = timestamp(today.minusDays(20)),
                        ),
                        AccountProfile(
                            id = 22,
                            name = "Salary account",
                            type = AccountType.BANK_ACCOUNT,
                            accountHint = "4321",
                            availabilityFetchedAt = timestamp(today.minusDays(9)),
                        ),
                    ),
                ),
                today,
                utc,
            ).staleBalanceCount,
        )
    }

    @Test
    fun freshDuplicateProfileMakesThePhysicalAccountFresh() {
        val summary = buildNeedsAttentionSummary(
            quietInput(
                accounts = listOf(
                    AccountProfile(
                        id = 31,
                        name = "SBI sender A",
                        type = AccountType.BANK_ACCOUNT,
                        accountHint = "1234",
                        availabilityFetchedAt = timestamp(today.minusDays(30)),
                    ),
                    AccountProfile(
                        id = 32,
                        name = "SBI savings",
                        type = AccountType.BANK_ACCOUNT,
                        accountHint = "xx1234",
                        availabilityFetchedAt = timestamp(today.minusDays(1)),
                    ),
                ),
            ),
            today,
            utc,
        )

        assertFalse(summary.items.any { it.kind == AttentionKind.STALE_ACCOUNT_BALANCE })
    }

    @Test
    fun sameLastFourAcrossBankAndCardRemainSeparateAccounts() {
        val summary = buildNeedsAttentionSummary(
            quietInput(
                accounts = listOf(
                    AccountProfile(
                        id = 41,
                        name = "Bank 9876",
                        type = AccountType.BANK_ACCOUNT,
                        accountHint = "9876",
                    ),
                    AccountProfile(
                        id = 42,
                        name = "Card 9876",
                        type = AccountType.CREDIT_CARD,
                        accountHint = "9876",
                    ),
                ),
            ),
            today,
            utc,
        )

        val stale = summary.items.filter { it.kind == AttentionKind.STALE_ACCOUNT_BALANCE }
        assertEquals(2, stale.size)
        assertEquals(setOf(41L, 42L), stale.mapNotNull(AttentionItem::accountId).toSet())
    }

    private fun quietInput(accounts: List<AccountProfile>): PlanningReviewInput {
        val backedUpAt = timestamp(today)
        return PlanningReviewInput(
            transactions = emptyList(),
            accounts = accounts,
            bills = emptyList(),
            savingsGoals = emptyList(),
            savingsContributions = emptyList(),
            paymentCommitments = emptyList(),
            backup = BackupReviewState(
                lastSuccessfulAt = backedUpAt,
                lastVerifiedAt = backedUpAt,
            ),
        )
    }

    private fun timestamp(date: LocalDate): Long =
        date.atStartOfDay(utc).toInstant().toEpochMilli()

    private fun transaction(
        id: Long,
        amountMinor: Long,
        date: LocalDate,
        type: TransactionType = TransactionType.EXPENSE,
        review: ReviewStatus = ReviewStatus.CONFIRMED,
    ) = TransactionRecord(
        id = id,
        sourceMessageId = "sms-$id",
        amountMinor = amountMinor,
        merchant = "Merchant $id",
        accountHint = null,
        category = ExpenseCategory.FOOD,
        type = type,
        occurredAt = date.atStartOfDay(utc).toInstant().toEpochMilli(),
        source = TransactionSource.BANK,
        sender = "BANK",
        reviewStatus = review,
    )
}
