package com.paisalens.app.data.model

import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SharedFinanceModelsTest {
    private val expense = transaction(1, TransactionType.EXPENSE, 1_000)
    private val refund = transaction(2, TransactionType.REFUND, 200)

    @Test
    fun validatesAllocationAndReimbursementBounds() {
        assertEquals(
            ExpenseSplitValidationIssue.ALLOCATION_EXCEEDS_EXPENSE,
            validateExpenseSplits(
                expense,
                listOf(
                    ExpenseSplit(transactionId = 1, participantName = "A", shareMinor = 600),
                    ExpenseSplit(transactionId = 1, participantName = "B", shareMinor = 500),
                ),
            ).issue,
        )
        assertEquals(
            ExpenseSplitValidationIssue.REIMBURSEMENT_EXCEEDS_SHARE,
            validateExpenseSplits(
                expense,
                listOf(ExpenseSplit(transactionId = 1, participantName = "A", shareMinor = 200, reimbursedMinor = 201)),
            ).issue,
        )
    }

    @Test
    fun permitsManualPlusLinkedReimbursementButRejectsExcessLinkedCredit() {
        val valid = ExpenseSplit(
            transactionId = 1,
            participantName = "A",
            shareMinor = 400,
            reimbursedMinor = 300,
            linkedIncomingTransactionId = 2,
        )
        assertTrue(validateExpenseSplits(expense, listOf(valid), mapOf(1L to expense, 2L to refund)).isValid)

        val largerRefund = refund.copy(amountMinor = 500)
        assertEquals(
            ExpenseSplitValidationIssue.LINKED_TRANSACTION_EXCEEDS_REIMBURSEMENT,
            validateExpenseSplits(expense, listOf(valid), mapOf(1L to expense, 2L to largerRefund)).issue,
        )
    }

    @Test
    fun linkedTransactionMustBeIncomingAndUnique() {
        val outgoing = transaction(3, TransactionType.EXPENSE, 100)
        val linked = ExpenseSplit(1, 1, "A", 200, 100, 3)
        assertEquals(
            ExpenseSplitValidationIssue.LINKED_TRANSACTION_NOT_INCOMING,
            validateExpenseSplits(expense, listOf(linked), mapOf(1L to expense, 3L to outgoing)).issue,
        )
        assertEquals(
            ExpenseSplitValidationIssue.LINKED_TRANSACTION_REUSED,
            validateExpenseSplits(
                expense,
                listOf(
                    ExpenseSplit(1, 1, "A", 200, 200, 2),
                    ExpenseSplit(2, 1, "B", 200, 200, 2),
                ),
                mapOf(1L to expense, 2L to refund),
            ).issue,
        )
    }

    @Test
    fun summarizesOutstandingAndUnallocatedShares() {
        val summary = buildExpenseSplitSummary(
            expense,
            listOf(
                ExpenseSplit(1, 1, "A", 400, 400),
                ExpenseSplit(2, 1, "B", 300, 100),
            ),
        )
        assertEquals(700, summary.allocatedMinor)
        assertEquals(500, summary.reimbursedMinor)
        assertEquals(200, summary.outstandingMinor)
        assertEquals(300, summary.unallocatedMinor)
        assertEquals(1, summary.settledParticipantCount)
    }

    @Test
    fun convertsPercentageSharesToExactMinorAmountsWithoutExceedingExpense() {
        assertEquals(
            listOf(3_333L, 3_333L, 3_334L),
            expenseSplitSharesFromPercentages(10_000L, listOf(3_333, 3_333, 3_334)),
        )
        assertEquals(listOf(2_500L, 2_500L), expenseSplitSharesFromPercentages(10_000L, listOf(2_500, 2_500)))
        assertEquals(2_500, expenseSplitShareBasisPoints(2_500L, 10_000L))
        assertEquals(
            listOf(3_333, 3_333, 3_334),
            expenseSplitBasisPointsFromShares(listOf(3_333L, 3_333L, 3_335L), 10_001L),
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsPercentageSharesAboveOneHundredPercent() {
        expenseSplitSharesFromPercentages(10_000L, listOf(6_000, 4_001))
    }

    @Test
    fun buildsCategorySplitStatsIncludingCustomCategories() {
        val customExpense = expense.copy(
            id = 10,
            category = ExpenseCategory.OTHER,
            customCategoryId = 44,
            customCategoryName = "Pets",
        )
        val groceriesExpense = expense.copy(id = 11, category = ExpenseCategory.GROCERIES)
        val stats = buildExpenseSplitCategoryStats(
            listOf(customExpense, groceriesExpense),
            listOf(
                ExpenseSplit(id = 1, transactionId = 10, participantName = "A", shareMinor = 600, reimbursedMinor = 100),
                ExpenseSplit(id = 2, transactionId = 11, participantName = "B", shareMinor = 400, reimbursedMinor = 400),
            ),
        )

        assertEquals(listOf("Pets", "Groceries"), stats.map(ExpenseSplitCategoryStat::categoryLabel))
        assertEquals(500L, stats.first().outstandingMinor)
        assertEquals(44L, stats.first().customCategoryId)
    }

    @Test
    fun savingsProgressIncludesStartingAmountAndPositiveContributions() {
        val goal = SavingsGoal(
            id = 7,
            name = "Emergency fund",
            targetMinor = 1_000,
            startingSavedMinor = 100,
            targetDateEpochDay = LocalDate.of(2026, 4, 1).toEpochDay(),
        )
        val progress = calculateSavingsGoalProgress(
            goal,
            listOf(
                SavingsContribution(1, 7, 200),
                SavingsContribution(2, 7, -50),
                SavingsContribution(3, 99, 500),
            ),
            LocalDate.of(2026, 1, 15),
        )
        assertEquals(300, progress.currentSavedMinor)
        assertEquals(700, progress.remainingMinor)
        assertEquals(3_000, progress.progressBasisPoints)
        assertEquals(4, progress.monthsRemaining)
        assertEquals(175L, progress.requiredMonthlyMinor)
        assertFalse(progress.isComplete)
    }

    @Test
    fun monthlyNextDueKeepsEndOfMonthAnchor() {
        val commitment = PaymentCommitment(
            name = "Rent",
            amountMinor = 10_000,
            nextDueEpochDay = LocalDate.of(2025, 1, 31).toEpochDay(),
        )
        assertEquals(
            LocalDate.of(2025, 3, 31),
            calculateNextPaymentDue(commitment, LocalDate.of(2025, 2, 28)),
        )
    }

    @Test
    fun currentDueKeepsTodayAndAdvancesPastEndOfMonth() {
        val today = LocalDate.of(2025, 3, 31)
        val dueToday = PaymentCommitment(name = "Rent", amountMinor = 1, nextDueEpochDay = today.toEpochDay())
        assertEquals(today, currentPaymentDueDate(dueToday, today))

        val pastEndOfMonth = dueToday.copy(nextDueEpochDay = LocalDate.of(2025, 1, 31).toEpochDay())
        assertEquals(today, currentPaymentDueDate(pastEndOfMonth, today))
        assertEquals(LocalDate.of(2025, 4, 30), currentPaymentDueDate(pastEndOfMonth, LocalDate.of(2025, 4, 1)))
    }

    @Test
    fun paymentIdentityIncludesAccountAndKind() {
        val base = PaymentCommitment(
            name = "Stream Co",
            amountMinor = 500,
            nextDueEpochDay = 1,
            accountId = 1,
        )
        assertEquals(paymentCommitmentIdentityKey(base), paymentCommitmentIdentityKey(base.copy(name = "STREAM CO")))
        assertFalse(paymentCommitmentIdentityKey(base) == paymentCommitmentIdentityKey(base.copy(accountId = 2)))
        assertFalse(
            paymentCommitmentIdentityKey(base) ==
                paymentCommitmentIdentityKey(base.copy(kind = PaymentCommitmentKind.UPI_AUTOPAY)),
        )
    }

    @Test
    fun commitmentDeduplicationNormalizesIdentityAndKeepsNewestRow() {
        val older = PaymentCommitment(
            id = 1,
            name = "Stream Co",
            merchantKey = "STREAM-CO",
            amountMinor = 500,
            nextDueEpochDay = 1,
            updatedAt = 100,
        )
        val newer = older.copy(
            id = 2,
            name = "Stream Co Premium",
            merchantKey = "stream co",
            amountMinor = 700,
            updatedAt = 200,
        )
        val otherAccount = newer.copy(id = 3, accountId = 9, updatedAt = 150)

        assertEquals(
            listOf(2L, 3L),
            deduplicatedPaymentCommitments(listOf(older, newer, otherAccount)).map(PaymentCommitment::id),
        )
    }

    @Test
    fun recurringSuggestionsStayOnDeviceAndExcludeKnownMerchant() {
        val nextDue = LocalDate.of(2026, 5, 7).atStartOfDay(ZoneId.of("UTC")).toInstant().toEpochMilli()
        val recurring = listOf(
            RecurringPayment("Netflix", null, 499, 30, nextDue - 30L * 86_400_000L, nextDue, 4, "Entertainment"),
            RecurringPayment("Gym", null, 1_500, 30, nextDue - 30L * 86_400_000L, nextDue, 5, "Health"),
        )
        val suggestions = suggestPaymentCommitments(
            recurring,
            listOf(PaymentCommitment(name = "Netflix plan", merchantKey = "netflix", amountMinor = 499, nextDueEpochDay = 1)),
            ZoneId.of("UTC"),
        )
        assertEquals(1, suggestions.size)
        assertEquals("Gym", suggestions.single().name)
        assertEquals(PaymentCommitmentSource.ON_DEVICE_SUGGESTION, suggestions.single().source)
        assertEquals(LocalDate.of(2026, 5, 7).toEpochDay(), suggestions.single().nextDueEpochDay)
    }

    @Test
    fun recurringSuggestionsDedupePerMerchantAndAccount() {
        val nextDue = LocalDate.of(2026, 5, 7).atStartOfDay(ZoneId.of("UTC")).toInstant().toEpochMilli()
        val accounts = listOf(
            AccountProfile(1, "Primary card", AccountType.CREDIT_CARD),
            AccountProfile(2, "Travel card", AccountType.CREDIT_CARD),
        )
        val recurring = listOf(
            RecurringPayment("Stream Co", "Primary card", 499, 30, 1, nextDue, 4, "Entertainment"),
            RecurringPayment("Stream Co", "Primary card", 499, 30, 2, nextDue, 5, "Entertainment"),
            RecurringPayment("Stream Co", "Travel card", 799, 30, 3, nextDue, 4, "Entertainment"),
        )
        val existingPrimary = PaymentCommitment(
            name = "Stream Co",
            merchantKey = "stream co",
            amountMinor = 499,
            nextDueEpochDay = 1,
            accountId = 1,
        )

        val withExisting = suggestPaymentCommitments(recurring, listOf(existingPrimary), ZoneId.of("UTC"), accounts)
        assertEquals(listOf(2L), withExisting.map(PaymentCommitment::accountId))

        val withoutExisting = suggestPaymentCommitments(recurring, emptyList(), ZoneId.of("UTC"), accounts)
        assertEquals(listOf(1L, 2L), withoutExisting.map(PaymentCommitment::accountId))
    }

    private fun transaction(id: Long, type: TransactionType, amount: Long) = TransactionRecord(
        id = id,
        sourceMessageId = "tx-$id",
        amountMinor = amount,
        merchant = "Merchant",
        accountHint = null,
        category = ExpenseCategory.OTHER,
        type = type,
        occurredAt = 1,
        source = TransactionSource.MANUAL,
        sender = "Test",
    )
}
