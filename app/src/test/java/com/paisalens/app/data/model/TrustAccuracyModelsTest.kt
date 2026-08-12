package com.paisalens.app.data.model

import java.time.LocalDate
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TrustAccuracyModelsTest {
    @Test
    fun reconciliationCalculatesBalanceDifferenceAndSuggestedStatus() {
        val july = LocalDate.of(2026, 7, 10).atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli()
        val reconciliation = MonthlyReconciliation(
            accountId = 7,
            year = 2026,
            month = 7,
            openingBalanceMinor = 1_000_000,
            closingBalanceMinor = 925_000,
            statementTransactionCount = 2,
            matchedTransactionCount = 2,
        )
        val metrics = calculateReconciliationMetrics(
            reconciliation,
            listOf(
                transaction(1, 7, 125_000, TransactionType.EXPENSE, july),
                transaction(2, 7, 50_000, TransactionType.INCOME, july + 1_000),
                transaction(3, 8, 999_000, TransactionType.EXPENSE, july),
            ),
            ZoneOffset.UTC,
        )

        assertEquals(2, metrics.appTransactionCount)
        assertEquals(-75_000L, metrics.appNetChangeMinor)
        assertEquals(925_000L, metrics.expectedClosingBalanceMinor)
        assertEquals(0L, metrics.balanceDifferenceMinor)
        assertEquals(100, metrics.matchPercent)
        assertEquals(ReconciliationStatus.BALANCED, suggestReconciliationStatus(metrics))
    }

    @Test
    fun creditCardReconciliationTreatsPurchasesAsDebtAndPaymentAsReduction() {
        val date = LocalDate.of(2026, 7, 10).atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli()
        val reconciliation = MonthlyReconciliation(
            accountId = 4,
            year = 2026,
            month = 7,
            openingBalanceMinor = 100_000,
            closingBalanceMinor = 125_000,
        )
        val metrics = calculateReconciliationMetrics(
            reconciliation = reconciliation,
            transactions = listOf(
                transaction(1, 4, 75_000, TransactionType.EXPENSE, date),
                transaction(2, 4, 50_000, TransactionType.TRANSFER, date + 1_000),
            ),
            zoneId = ZoneOffset.UTC,
            accountType = AccountType.CREDIT_CARD,
        )

        assertEquals(25_000L, metrics.appNetChangeMinor)
        assertEquals(125_000L, metrics.expectedClosingBalanceMinor)
    }

    @Test
    fun reconciliationDeduplicatesStatementAndSmsCopies() {
        val date = LocalDate.of(2026, 7, 10).atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli()
        val sms = transaction(1, 4, 75_000, TransactionType.EXPENSE, date, "ACME STORE")
        val statement = transaction(2, 4, 75_000, TransactionType.EXPENSE, date, "Acme Store purchase")
            .copy(source = TransactionSource.STATEMENT)

        val rows = deduplicateReconciliationTransactions(listOf(statement, sms))

        assertEquals(listOf(1L), rows.map(TransactionRecord::id))
    }

    @Test
    fun laterLedgerChangeInvalidatesAReconciledMonth() {
        val date = LocalDate.of(2026, 7, 10).atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli()
        val reconciliation = MonthlyReconciliation(
            id = 5,
            accountId = 4,
            year = 2026,
            month = 7,
            openingBalanceMinor = 1_000_000,
            closingBalanceMinor = 900_000,
            statementTransactionCount = 1,
            matchedTransactionCount = 1,
            status = ReconciliationStatus.RECONCILED,
            reconciledAt = date,
        )
        val originalExpense = transaction(1, 4, 100_000, TransactionType.EXPENSE, date)

        assertTrue(
            findReconciliationsInvalidatedByLedger(
                listOf(reconciliation),
                listOf(originalExpense),
                mapOf(4L to AccountType.BANK_ACCOUNT),
                zoneId = ZoneOffset.UTC,
            ).isEmpty(),
        )

        val invalidated = findReconciliationsInvalidatedByLedger(
            listOf(reconciliation),
            listOf(originalExpense, transaction(2, 4, 10_000, TransactionType.EXPENSE, date + 1_000)),
            mapOf(4L to AccountType.BANK_ACCOUNT),
            zoneId = ZoneOffset.UTC,
        )

        assertEquals(listOf(5L), invalidated.map(MonthlyReconciliation::id))
    }

    @Test
    fun linkSemanticsExcludeTransfersAndNetRefundsFromEffectiveSpend() {
        val now = 1_000L
        val purchase = transaction(1, 1, 10_000, TransactionType.EXPENSE, now)
        val refund = transaction(2, 1, 4_000, TransactionType.REFUND, now + 1)
        val cardPayment = transaction(3, 1, 50_000, TransactionType.EXPENSE, now + 2)
        val effective = buildEffectiveExpenseTransactions(
            transactions = listOf(purchase, refund, cardPayment),
            links = listOf(
                TransactionLink(1, 1, 2, TransactionLinkType.REFUND),
                TransactionLink(2, 3, 2, TransactionLinkType.CARD_PAYMENT),
            ),
        )

        assertEquals(listOf(1L), effective.map(TransactionRecord::id))
        assertEquals(6_000L, effective.single().amountMinor)
        assertEquals(6_000L, calculateEffectiveSpendMinor(listOf(purchase, refund, cardPayment), listOf(
            TransactionLink(1, 1, 2, TransactionLinkType.REFUND),
            TransactionLink(2, 3, 2, TransactionLinkType.CARD_PAYMENT),
        )))
        val legacyRefund = transaction(4, 1, 1_000, TransactionType.REFUND, now + 3)
        assertEquals(
            5_000L,
            calculateEffectiveSpendMinor(
                listOf(purchase, refund, cardPayment, legacyRefund),
                listOf(
                    TransactionLink(1, 1, 2, TransactionLinkType.REFUND),
                    TransactionLink(2, 3, 2, TransactionLinkType.CARD_PAYMENT),
                ),
            ),
        )
    }

    @Test
    fun splitReimbursementsOffsetManualAmountWithoutDoubleSubtractingLinkedCredit() {
        val purchase = transaction(1, 1, 1_000, TransactionType.EXPENSE, 1_000)
        val linkedIncome = transaction(2, 1, 200, TransactionType.INCOME, 1_001)
        val link = TransactionLink(
            sourceTransactionId = purchase.id,
            targetTransactionId = linkedIncome.id,
            type = TransactionLinkType.REIMBURSEMENT,
        )
        val split = ExpenseSplit(
            transactionId = purchase.id,
            participantName = "Riya",
            shareMinor = 500,
            reimbursedMinor = 300,
            linkedIncomingTransactionId = linkedIncome.id,
        )

        val effective = buildEffectiveExpenseTransactions(
            listOf(purchase, linkedIncome),
            listOf(link),
            listOf(split),
        )

        // ₹200 comes from the linked credit and only the remaining ₹100 is the manual delta.
        assertEquals(700L, effective.single().amountMinor)
        assertEquals(700L, calculateEffectiveSpendMinor(listOf(purchase, linkedIncome), listOf(link), listOf(split)))
    }

    @Test
    fun fullyManualSplitReimbursementReducesEffectiveSpend() {
        val purchase = transaction(1, 1, 1_000, TransactionType.EXPENSE, 1_000)
        val split = ExpenseSplit(
            transactionId = purchase.id,
            participantName = "Riya",
            shareMinor = 400,
            reimbursedMinor = 250,
        )

        assertEquals(750L, calculateEffectiveSpendMinor(listOf(purchase), emptyList(), listOf(split)))
    }

    @Test
    fun typeChangeCannotInvalidateExpenseSplitOrLinkedIncoming() {
        val expense = transaction(1, 1, 1_000, TransactionType.EXPENSE, 1_000)
        val income = transaction(2, 1, 200, TransactionType.INCOME, 1_001)
        val split = ExpenseSplit(
            transactionId = expense.id,
            participantName = "Riya",
            shareMinor = 300,
            reimbursedMinor = 200,
            linkedIncomingTransactionId = income.id,
        )
        val link = TransactionLink(
            sourceTransactionId = expense.id,
            targetTransactionId = income.id,
            type = TransactionLinkType.REIMBURSEMENT,
        )

        assertEquals(
            TransactionTypeChangeIssue.SPLIT_WOULD_BECOME_INVALID,
            validateTransactionTypeChange(expense.id, TransactionType.INCOME, listOf(expense, income), emptyList(), listOf(split)).issue,
        )
        assertEquals(
            TransactionTypeChangeIssue.LINK_WOULD_BECOME_INVALID,
            validateTransactionTypeChange(income.id, TransactionType.EXPENSE, listOf(expense, income), listOf(link), listOf(split)).issue,
        )
        assertTrue(
            validateTransactionTypeChange(
                expense.id,
                TransactionType.EXPENSE,
                listOf(expense, income),
                listOf(link),
                listOf(split),
            ).isValid,
        )
    }

    @Test
    fun linkValidationRejectsSelfDuplicateMissingAndCycle() {
        val ids = setOf(1L, 2L, 3L)
        val existing = listOf(
            TransactionLink(1, 1, 2, TransactionLinkType.TRANSFER),
            TransactionLink(2, 2, 3, TransactionLinkType.TRANSFER),
        )

        assertEquals(
            TransactionLinkIssue.SELF_LINK,
            validateTransactionLink(TransactionLink(sourceTransactionId = 1, targetTransactionId = 1, type = TransactionLinkType.TRANSFER), existing, ids).issue,
        )
        assertEquals(
            TransactionLinkIssue.DUPLICATE_LINK,
            validateTransactionLink(TransactionLink(sourceTransactionId = 1, targetTransactionId = 2, type = TransactionLinkType.TRANSFER), existing, ids).issue,
        )
        assertEquals(
            TransactionLinkIssue.MISSING_TRANSACTION,
            validateTransactionLink(TransactionLink(sourceTransactionId = 1, targetTransactionId = 9, type = TransactionLinkType.TRANSFER), existing, ids).issue,
        )
        assertEquals(
            TransactionLinkIssue.WOULD_CREATE_CYCLE,
            validateTransactionLink(TransactionLink(sourceTransactionId = 3, targetTransactionId = 1, type = TransactionLinkType.TRANSFER), existing, ids).issue,
        )
    }

    @Test
    fun suggestionsUseAmountDateAccountAndExcludeExistingLinks() {
        val date = LocalDate.of(2026, 7, 4).atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli()
        val debit = transaction(1, 10, 50_000, TransactionType.EXPENSE, date, "Transfer to savings")
        val credit = transaction(2, 11, 50_000, TransactionType.INCOME, date + 86_400_000, "Transfer received")

        val suggestions = suggestTransactionLinks(listOf(debit, credit), emptyList(), ZoneOffset.UTC)

        assertEquals(1, suggestions.size)
        assertEquals(TransactionLinkType.TRANSFER, suggestions.single().type)
        assertEquals(100, suggestions.single().confidence)
        assertTrue(
            suggestTransactionLinks(
                listOf(debit, credit),
                listOf(TransactionLink(1, 1, 2, TransactionLinkType.TRANSFER)),
                ZoneOffset.UTC,
            ).isEmpty(),
        )
    }

    @Test
    fun nearMatchSuggestionUsesTruthfulDifferenceReason() {
        val date = LocalDate.of(2026, 7, 4).atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli()
        val suggestion = suggestTransactionLinks(
            listOf(
                transaction(1, 10, 50_000, TransactionType.EXPENSE, date, "Self transfer"),
                transaction(2, 11, 50_050, TransactionType.INCOME, date, "Self transfer received"),
            ),
            emptyList(),
            ZoneOffset.UTC,
        ).single()

        assertTrue(suggestion.reason.contains("50 minor units"))
        assertEquals(80, suggestion.confidence)
    }

    @Test
    fun linkValidationRejectsSharedCreditAndConflictingPairTypes() {
        val records = listOf(
            transaction(1, 1, 10_000, TransactionType.EXPENSE, 1_000),
            transaction(2, 1, 10_000, TransactionType.EXPENSE, 2_000),
            transaction(3, 1, 10_000, TransactionType.REFUND, 3_000),
        ).associateBy(TransactionRecord::id)
        val existing = listOf(TransactionLink(1, 1, 3, TransactionLinkType.REFUND))

        assertEquals(
            TransactionLinkIssue.CREDIT_ALREADY_LINKED,
            validateTransactionLink(
                TransactionLink(sourceTransactionId = 2, targetTransactionId = 3, type = TransactionLinkType.REFUND),
                existing,
                records.keys,
                records,
            ).issue,
        )
        assertEquals(
            TransactionLinkIssue.CONFLICTING_PAIR,
            validateTransactionLink(
                TransactionLink(sourceTransactionId = 3, targetTransactionId = 1, type = TransactionLinkType.REVERSAL),
                existing,
                records.keys,
                records,
            ).issue,
        )
    }

    @Test
    fun offsetCannotReuseEndpointsFromTransferOrCardPayment() {
        val records = listOf(
            transaction(1, 1, 10_000, TransactionType.EXPENSE, 1_000),
            transaction(2, 2, 10_000, TransactionType.INCOME, 2_000),
            transaction(3, 1, 10_000, TransactionType.EXPENSE, 3_000),
            transaction(4, 2, 10_000, TransactionType.REFUND, 4_000),
        ).associateBy(TransactionRecord::id)

        assertEquals(
            TransactionLinkIssue.CREDIT_ALREADY_LINKED,
            validateTransactionLink(
                TransactionLink(sourceTransactionId = 3, targetTransactionId = 2, type = TransactionLinkType.REFUND),
                listOf(TransactionLink(1, 1, 2, TransactionLinkType.CARD_PAYMENT)),
                records.keys,
                records,
            ).issue,
        )
        assertEquals(
            TransactionLinkIssue.TRANSACTION_ALREADY_LINKED,
            validateTransactionLink(
                TransactionLink(sourceTransactionId = 1, targetTransactionId = 4, type = TransactionLinkType.REFUND),
                listOf(TransactionLink(1, 1, 2, TransactionLinkType.CARD_PAYMENT)),
                records.keys,
                records,
            ).issue,
        )
    }

    @Test
    fun transferAndCardPaymentRequireOutgoingToIncomingOrientation() {
        val records = listOf(
            transaction(1, 1, 10_000, TransactionType.EXPENSE, 1_000),
            transaction(2, 2, 10_000, TransactionType.INCOME, 2_000),
        ).associateBy(TransactionRecord::id)

        assertTrue(
            validateTransactionLink(
                TransactionLink(sourceTransactionId = 1, targetTransactionId = 2, type = TransactionLinkType.CARD_PAYMENT),
                emptyList(),
                records.keys,
                records,
            ).isValid,
        )
        assertEquals(
            TransactionLinkIssue.INCOMPATIBLE_FLOW,
            validateTransactionLink(
                TransactionLink(sourceTransactionId = 2, targetTransactionId = 1, type = TransactionLinkType.CARD_PAYMENT),
                emptyList(),
                records.keys,
                records,
            ).issue,
        )
    }

    @Test
    fun unrelatedTransferAndPurchaseDoNotProduceSuggestion() {
        val date = LocalDate.of(2026, 7, 4).atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli()
        val rows = listOf(
            transaction(1, 10, 50_000, TransactionType.TRANSFER, date, "Move funds"),
            transaction(2, 11, 50_000, TransactionType.EXPENSE, date, "Grocery store"),
        )

        assertTrue(suggestTransactionLinks(rows, emptyList(), ZoneOffset.UTC).isEmpty())
    }

    @Test
    fun cashFlowKeepsOutgoingCardPaymentButRemovesCardCreditAndOwnTransfers() {
        val rows = listOf(
            transaction(1, 10, 50_000, TransactionType.TRANSFER, 1_000, "Card payment"),
            transaction(2, 11, 50_000, TransactionType.TRANSFER, 2_000, "Payment received"),
            transaction(3, 10, 20_000, TransactionType.TRANSFER, 3_000, "Savings transfer"),
            transaction(4, 12, 20_000, TransactionType.TRANSFER, 4_000, "Savings received"),
        )
        val relevant = cashFlowRelevantTransactions(
            rows,
            listOf(
                TransactionLink(1, 1, 2, TransactionLinkType.CARD_PAYMENT),
                TransactionLink(2, 3, 4, TransactionLinkType.TRANSFER),
            ),
        )

        assertEquals(listOf(1L), relevant.map(TransactionRecord::id))
        assertEquals(TransactionType.EXPENSE, relevant.single().type)
    }

    @Test
    fun undoOrderRestoresDeletedTransactionBeforeCascadedLink() {
        val linkDelete = AuditEvent(
            id = 10,
            batchId = "delete-1",
            batchLabel = "Delete transaction",
            entityType = AuditEntityType.TRANSACTION_LINK,
            entityId = "4",
            action = AuditAction.DELETE,
            beforePayload = "link",
        )
        val transactionDelete = AuditEvent(
            id = 11,
            batchId = "delete-1",
            batchLabel = "Delete transaction",
            entityType = AuditEntityType.TRANSACTION,
            entityId = "2",
            action = AuditAction.DELETE,
            beforePayload = "transaction",
        )

        assertEquals(
            listOf(AuditEntityType.TRANSACTION, AuditEntityType.TRANSACTION_LINK),
            orderAuditEventsForUndo(listOf(linkDelete, transactionDelete)).map(AuditEvent::entityType),
        )
    }

    @Test
    fun auditBatchBecomesNonUndoableAfterCompensatingEvent() {
        val original = AuditEvent(
            id = 1,
            batchId = "edit-1",
            batchLabel = "Edit",
            entityType = AuditEntityType.TRANSACTION,
            entityId = "1",
            action = AuditAction.UPDATE,
            beforePayload = "before",
            afterPayload = "after",
        )
        val undo = original.copy(
            id = 2,
            batchId = "undo-1",
            batchLabel = "Undo: Edit",
            beforePayload = "after",
            afterPayload = "before",
            reversesEventId = 1,
        )
        val summaries = buildAuditBatchSummaries(listOf(original, undo)).associateBy { it.batchId }

        assertFalse(requireNotNull(summaries["edit-1"]).canUndo)
        assertTrue(requireNotNull(summaries["undo-1"]).isUndo)
    }

    @Test
    fun automaticReconciliationInvalidationIsInformationalOnly() {
        val event = AuditEvent(
            id = 1,
            batchId = "${SYSTEM_RECONCILIATION_INVALIDATION_BATCH_PREFIX}test",
            batchLabel = "Reconciliation needs review",
            entityType = AuditEntityType.MONTHLY_RECONCILIATION,
            entityId = "5",
            action = AuditAction.UPDATE,
            beforePayload = "balanced",
            afterPayload = "review",
        )

        assertFalse(buildAuditBatchSummaries(listOf(event)).single().canUndo)
        assertFalse(isAuditBatchEligibleForUndo(event.batchId))
    }

    @Test
    fun staleUndoAndNewerLinkDependencyAreRejected() {
        val update = AuditEvent(
            id = 1,
            batchId = "edit",
            batchLabel = "Edit",
            entityType = AuditEntityType.TRANSACTION,
            entityId = "7",
            action = AuditAction.UPDATE,
            beforePayload = "before",
            afterPayload = "after",
        )
        assertTrue(
            requireNotNull(
                findAuditUndoConflict(
                    listOf(update),
                    mapOf(AuditEntityKey(AuditEntityType.TRANSACTION, "7") to "newer"),
                ),
            ).contains("changed later"),
        )
        val insert = update.copy(action = AuditAction.INSERT, beforePayload = null)
        assertTrue(
            requireNotNull(
                findAuditUndoConflict(
                    listOf(insert),
                    mapOf(AuditEntityKey(AuditEntityType.TRANSACTION, "7") to "after"),
                    insertedTransactionIdsWithNewerLinks = setOf(7),
                ),
            ).contains("newer transaction links"),
        )
    }

    @Test
    fun undoRejectsInsertedTransactionsAndLinksBackedByExpenseSplits() {
        val transactionInsert = AuditEvent(
            id = 1,
            batchId = "import",
            batchLabel = "Import",
            entityType = AuditEntityType.TRANSACTION,
            entityId = "7",
            action = AuditAction.INSERT,
            afterPayload = "transaction",
        )
        assertTrue(
            requireNotNull(
                findAuditUndoConflict(
                    events = listOf(transactionInsert),
                    currentPayloads = mapOf(AuditEntityKey(AuditEntityType.TRANSACTION, "7") to "transaction"),
                    insertedTransactionIdsWithExpenseSplits = setOf(7),
                ),
            ).contains("expense split"),
        )

        val linkInsert = transactionInsert.copy(
            entityType = AuditEntityType.TRANSACTION_LINK,
            entityId = "12",
            afterPayload = "link",
        )
        assertTrue(
            requireNotNull(
                findAuditUndoConflict(
                    events = listOf(linkInsert),
                    currentPayloads = mapOf(AuditEntityKey(AuditEntityType.TRANSACTION_LINK, "12") to "link"),
                    insertedTransactionLinkIdsWithExpenseSplits = setOf(12),
                ),
            ).contains("expense split"),
        )
    }

    @Test
    fun prospectiveUndoRejectsIncompatibleSplitTypeAndMissingBackingLink() {
        val changedToIncome = transaction(1, 1, 1_000, TransactionType.INCOME, 1_000)
        val incoming = transaction(2, 2, 300, TransactionType.INCOME, 1_001)
        val split = ExpenseSplit(
            id = 9,
            transactionId = changedToIncome.id,
            participantName = "Riya",
            shareMinor = 300,
            reimbursedMinor = 300,
            linkedIncomingTransactionId = incoming.id,
        )

        assertTrue(
            requireNotNull(findAuditUndoLedgerConflict(listOf(changedToIncome, incoming), emptyList(), listOf(split)))
                .contains("expense splits"),
        )

        val expense = changedToIncome.copy(type = TransactionType.EXPENSE)
        assertTrue(
            requireNotNull(findAuditUndoLedgerConflict(listOf(expense, incoming), emptyList(), listOf(split)))
                .contains("lose its reimbursement link"),
        )
    }

    @Test
    fun prospectiveUndoRejectsRestoredCreditLinkThatConflictsWithNewerPair() {
        val oldExpense = transaction(1, 1, 500, TransactionType.EXPENSE, 1_000)
        val newerExpense = transaction(2, 2, 500, TransactionType.EXPENSE, 1_001)
        val incoming = transaction(3, 3, 500, TransactionType.REFUND, 1_002)
        val restored = TransactionLink(10, oldExpense.id, incoming.id, TransactionLinkType.REIMBURSEMENT)
        val newer = TransactionLink(11, newerExpense.id, incoming.id, TransactionLinkType.REIMBURSEMENT)

        assertTrue(
            requireNotNull(
                findAuditUndoLedgerConflict(
                    transactions = listOf(oldExpense, newerExpense, incoming),
                    links = listOf(restored, newer),
                    expenseSplits = emptyList(),
                ),
            ).contains("transaction link"),
        )
    }

    @Test
    fun incompleteStatementCountsCannotBeSuggestedAsBalanced() {
        val metrics = ReconciliationMetrics(
            accountId = 1,
            period = java.time.YearMonth.of(2026, 7),
            appTransactionCount = 0,
            statementTransactionCount = 10,
            matchedTransactionCount = 0,
            unmatchedStatementCount = 0,
            unmatchedAppCount = 0,
            incomeMinor = 0,
            expenseMinor = 0,
            appNetChangeMinor = 0,
            expectedClosingBalanceMinor = 100,
            balanceDifferenceMinor = 0,
            matchPercent = 0,
        )

        assertEquals(ReconciliationStatus.REVIEW_REQUIRED, suggestReconciliationStatus(metrics))
    }

    @Test
    fun matchedStatementRowsCannotExceedAvailableAppTransactions() {
        val metrics = ReconciliationMetrics(
            accountId = 1,
            period = java.time.YearMonth.of(2026, 7),
            appTransactionCount = 0,
            statementTransactionCount = 1,
            matchedTransactionCount = 1,
            unmatchedStatementCount = 0,
            unmatchedAppCount = 0,
            incomeMinor = 0,
            expenseMinor = 0,
            appNetChangeMinor = 0,
            expectedClosingBalanceMinor = 100,
            balanceDifferenceMinor = 0,
            matchPercent = 100,
        )

        assertEquals(ReconciliationStatus.REVIEW_REQUIRED, suggestReconciliationStatus(metrics))
    }

    @Test
    fun dataHealthFindsReviewUncategorizedStaleAndReconciliationIssues() {
        val now = 2_000_000_000_000L
        val summary = buildDataHealthSummary(
            transactions = listOf(
                transaction(1, 1, 100, TransactionType.EXPENSE, now).copy(
                    category = ExpenseCategory.OTHER,
                    reviewStatus = ReviewStatus.NEEDS_REVIEW,
                ),
                transaction(2, 1, 100, TransactionType.TRANSFER, now),
            ),
            accounts = listOf(
                AccountProfile(
                    id = 1,
                    name = "Bank",
                    type = AccountType.BANK_ACCOUNT,
                    balanceMinor = 100,
                    availabilityFetchedAt = now - 10 * 86_400_000L,
                ),
            ),
            reconciliations = listOf(
                MonthlyReconciliation(
                    id = 1,
                    accountId = 1,
                    year = 2026,
                    month = 7,
                    status = ReconciliationStatus.REVIEW_REQUIRED,
                ),
            ),
            transactionLinks = emptyList(),
            now = now,
        )

        assertEquals(setOf("review", "uncategorized", "stale-balances", "reconciliation", "unlinked-transfers"), summary.findings.map { it.key }.toSet())
        assertTrue(summary.score < 100)
    }

    private fun transaction(
        id: Long,
        accountId: Long,
        amountMinor: Long,
        type: TransactionType,
        occurredAt: Long,
        merchant: String = "Merchant",
    ) = TransactionRecord(
        id = id,
        sourceMessageId = "test-$id",
        amountMinor = amountMinor,
        merchant = merchant,
        accountHint = null,
        category = if (type == TransactionType.INCOME) ExpenseCategory.INCOME else ExpenseCategory.OTHER,
        type = type,
        occurredAt = occurredAt,
        source = TransactionSource.MANUAL,
        sender = "Test",
        accountId = accountId,
    )
}
