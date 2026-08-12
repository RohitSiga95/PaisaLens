package com.paisalens.app.data.importer

import com.paisalens.app.data.model.ExpenseCategory
import com.paisalens.app.data.model.StatementAmountDirection
import com.paisalens.app.data.model.StatementAuditConfig
import com.paisalens.app.data.model.StatementAuditConfidence
import com.paisalens.app.data.model.StatementAuditLineStatus
import com.paisalens.app.data.model.StatementAuditMetadata
import com.paisalens.app.data.model.StatementAuditRow
import com.paisalens.app.data.model.StatementImportRow
import com.paisalens.app.data.model.StatementInputMode
import com.paisalens.app.data.model.StatementLineKind
import com.paisalens.app.data.model.TransactionRecord
import com.paisalens.app.data.model.TransactionSource
import com.paisalens.app.data.model.TransactionType
import java.time.LocalDate
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CreditCardStatementAuditorTest {
    private val utc = ZoneOffset.UTC

    @Test
    fun calculatesDueBreakdownIncludingFeesInterestGstRefundsAndPayments() {
        val metadata = metadata(
            openingBalanceMinor = 100_000,
            totalDueMinor = 147_700,
            minimumDueMinor = 10_000,
            dueDate = LocalDate.of(2026, 9, 5),
        )
        val rows = listOf(
            row(2, LocalDate.of(2026, 8, 2), "Corner Cafe", 100_000, StatementAmountDirection.DEBIT),
            row(3, LocalDate.of(2026, 8, 3), "Late payment fee", 10_000, StatementAmountDirection.DEBIT),
            row(4, LocalDate.of(2026, 8, 3), "Finance charges", 5_000, StatementAmountDirection.DEBIT),
            row(5, LocalDate.of(2026, 8, 3), "GST on finance charges", 2_700, StatementAmountDirection.DEBIT),
            row(6, LocalDate.of(2026, 8, 5), "Merchant refund", 20_000, StatementAmountDirection.CREDIT),
            row(7, LocalDate.of(2026, 8, 8), "Payment received thank you", 50_000, StatementAmountDirection.CREDIT),
        )

        val report = CreditCardStatementAuditor.audit(metadata, rows, emptyList(), zoneId = utc)

        assertEquals(100_000L, report.totals.purchasesMinor)
        assertEquals(10_000L, report.totals.feesMinor)
        assertEquals(5_000L, report.totals.interestMinor)
        assertEquals(2_700L, report.totals.gstMinor)
        assertEquals(20_000L, report.totals.refundsMinor)
        assertEquals(50_000L, report.totals.paymentsMinor)
        assertEquals(147_700L, report.totals.calculatedClosingBalanceMinor)
        assertEquals(0L, report.totals.totalDueDifferenceMinor)
        assertEquals(10_000L, report.totals.minimumDueMinor)
        assertEquals(LocalDate.of(2026, 9, 5).toEpochDay(), report.totals.dueDateEpochDay)
        assertFalse(report.issues.any { it.code == "total_due_mismatch" })
    }

    @Test
    fun matchesSmsWithinConfiguredPostingDateAndAmountTolerance() {
        val statement = row(
            2,
            LocalDate.of(2026, 8, 3),
            "Amazon Seller Services India",
            125_050,
            StatementAmountDirection.DEBIT,
        )
        val sms = sms(41, LocalDate.of(2026, 8, 1), "Amazon", 125_000, TransactionType.EXPENSE)

        val report = CreditCardStatementAuditor.audit(
            metadata(),
            listOf(statement),
            listOf(sms),
            config = StatementAuditConfig(dateToleranceDays = 3, amountToleranceMinor = 100),
            zoneId = utc,
        )

        val match = report.lines.single()
        assertEquals(StatementAuditLineStatus.MATCHED, match.status)
        assertEquals(41L, match.matchedTransactionId)
        assertTrue(match.reasons.any { it.contains("2 days") })
        assertTrue(match.reasons.any { it.contains("50 minor units") })
    }

    @Test
    fun leavesSmsOutsideDateToleranceUnmatched() {
        val statement = row(2, LocalDate.of(2026, 8, 8), "Cafe", 25_000, StatementAmountDirection.DEBIT)
        val sms = sms(4, LocalDate.of(2026, 8, 4), "Cafe", 25_000, TransactionType.EXPENSE)

        val report = CreditCardStatementAuditor.audit(
            metadata(),
            listOf(statement),
            listOf(sms),
            config = StatementAuditConfig(dateToleranceDays = 3),
            zoneId = utc,
        )

        assertEquals(StatementAuditLineStatus.UNMATCHED, report.lines.single().status)
        assertNull(report.lines.single().matchedTransactionId)
    }

    @Test
    fun flagsRepeatedStatementChargeAsPossibleDuplicateWithoutReusingSms() {
        val rows = listOf(
            row(8, LocalDate.of(2026, 8, 10), "Swiggy Bengaluru", 48_500, StatementAmountDirection.DEBIT),
            row(9, LocalDate.of(2026, 8, 10), "SWIGGY BENGALURU", 48_500, StatementAmountDirection.DEBIT),
        )
        val sms = sms(77, LocalDate.of(2026, 8, 10), "Swiggy", 48_500, TransactionType.EXPENSE)

        val report = CreditCardStatementAuditor.audit(metadata(), rows, listOf(sms), zoneId = utc)

        assertEquals(StatementAuditLineStatus.MATCHED, report.lines[0].status)
        assertEquals(StatementAuditLineStatus.POSSIBLE_DUPLICATE, report.lines[1].status)
        assertNull(report.lines[1].matchedTransactionId)
        assertEquals(1, report.matchedCount)
        assertEquals(1, report.possibleDuplicateCount)
        assertTrue(report.issues.any { it.code == "possible_duplicates" && it.rowNumbers == listOf(9) })
    }

    @Test
    fun findsDuplicateWhenSharedMerchantTokenIsNotAlphabeticallyFirst() {
        val rows = listOf(
            row(8, LocalDate.of(2026, 8, 10), "Zoo", 48_500, StatementAmountDirection.DEBIT),
            row(9, LocalDate.of(2026, 8, 10), "Alpha Zoo", 48_500, StatementAmountDirection.DEBIT),
        )

        val report = CreditCardStatementAuditor.audit(metadata(), rows, emptyList(), zoneId = utc)

        assertEquals(StatementAuditLineStatus.UNMATCHED, report.lines[0].status)
        assertEquals(StatementAuditLineStatus.POSSIBLE_DUPLICATE, report.lines[1].status)
    }

    @Test
    fun matchesRefundOnlyToCreditLikeSmsAndKeepsFeeClassification() {
        val rows = listOf(
            row(2, LocalDate.of(2026, 8, 12), "Amazon purchase refund", 9_999, StatementAmountDirection.CREDIT),
            row(3, LocalDate.of(2026, 8, 12), "Annual membership fee", 49_900, StatementAmountDirection.DEBIT),
        )
        val expenseWithRefundAmount = sms(1, LocalDate.of(2026, 8, 12), "Amazon", 9_999, TransactionType.EXPENSE)
        val actualRefund = sms(2, LocalDate.of(2026, 8, 12), "Amazon refund", 9_999, TransactionType.REFUND)

        val report = CreditCardStatementAuditor.audit(metadata(), rows, listOf(expenseWithRefundAmount, actualRefund), zoneId = utc)

        assertEquals(StatementLineKind.REFUND, report.lines[0].kind)
        assertEquals(2L, report.lines[0].matchedTransactionId)
        assertEquals(StatementLineKind.FEE, report.lines[1].kind)
        assertEquals(49_900L, report.totals.feesMinor)
        assertEquals(9_999L, report.totals.refundsMinor)
    }

    @Test
    fun equallyPlausibleSmsCandidatesRequireExplicitDuplicateReview() {
        val statement = row(2, LocalDate.of(2026, 8, 12), "Metro Market", 10_000, StatementAmountDirection.DEBIT)
        val first = sms(10, LocalDate.of(2026, 8, 12), "Metro Market", 10_000, TransactionType.EXPENSE)
        val second = sms(11, LocalDate.of(2026, 8, 12), "Metro Market", 10_000, TransactionType.EXPENSE)

        val result = CreditCardStatementAuditor.audit(metadata(), listOf(statement), listOf(first, second), zoneId = utc).lines.single()

        assertEquals(StatementAuditLineStatus.POSSIBLE_DUPLICATE, result.status)
        assertEquals(listOf(10L, 11L), result.candidates.map { it.transactionId })
        assertNull(result.matchedTransactionId)
    }

    @Test
    fun parsesTableWithPreambleAndDebitCreditColumns() {
        val table = listOf(
            listOf("Credit card statement", "August 2026"),
            listOf("Transaction Date", "Description", "Debit Amount", "Credit Amount", "Currency"),
            listOf("01/08/2026", "Book Store", "1,250.50", "", "INR"),
            listOf("02/08/2026", "Book refund", "", "250.50", "INR"),
        )

        val parsed = StatementAuditTableParser.parse(table, utc)

        assertEquals(0, parsed.skippedRows)
        assertEquals(2, parsed.rows.size)
        assertEquals(125_050L, parsed.rows[0].amountMinor)
        assertEquals(StatementAmountDirection.DEBIT, parsed.rows[0].direction)
        assertEquals(25_050L, parsed.rows[1].amountMinor)
        assertEquals(StatementAmountDirection.CREDIT, parsed.rows[1].direction)
        assertEquals(3, parsed.rows[0].rowNumber)
    }

    @Test
    fun routesPdfToExplicitStructuredManualFallback() {
        val support = CreditCardStatementAuditor.sourceSupport("card-august.pdf")

        assertEquals(StatementInputMode.STRUCTURED_MANUAL_FALLBACK, support.mode)
        assertFalse(support.directParsingSupported)
        assertTrue(support.requiredManualFields.contains("Total amount due"))
        assertTrue(support.message.contains("no document leaves the device"))
    }

    @Test
    fun auditsRowsProducedByExistingStatementImporter() {
        val importedRefund = StatementImportRow(
            rowNumber = 14,
            transaction = sms(90, LocalDate.of(2026, 8, 14), "Rail ticket refund", 30_000, TransactionType.INCOME)
                .copy(source = TransactionSource.STATEMENT, sourceMessageId = "statement-row-14"),
        )
        val refundSms = sms(91, LocalDate.of(2026, 8, 14), "Rail refund", 30_000, TransactionType.REFUND)

        val report = CreditCardStatementAuditor.auditImported(metadata(), listOf(importedRefund), listOf(refundSms), zoneId = utc)

        assertEquals(StatementLineKind.REFUND, report.lines.single().kind)
        assertEquals(StatementAuditLineStatus.MATCHED, report.lines.single().status)
        assertEquals(91L, report.lines.single().matchedTransactionId)
    }

    @Test
    fun doesNotMatchAnotherCardsSmsWhenStatementIdentifiesSelectedCard() {
        val statement = row(2, LocalDate.of(2026, 8, 15), "Metro Market", 12_500, StatementAmountDirection.DEBIT)
        val otherCard = sms(101, LocalDate.of(2026, 8, 15), "Metro Market", 12_500, TransactionType.EXPENSE)
            .copy(accountId = 10, accountHint = "1111")
        val selectedCard = sms(102, LocalDate.of(2026, 8, 16), "Metro", 12_500, TransactionType.EXPENSE)

        val report = CreditCardStatementAuditor.audit(
            metadata(),
            listOf(statement),
            listOf(otherCard, selectedCard),
            zoneId = utc,
        )

        assertEquals(StatementAuditLineStatus.MATCHED, report.lines.single().status)
        assertEquals(102L, report.lines.single().matchedTransactionId)
        assertFalse(report.lines.single().candidates.any { it.transactionId == 101L })
    }

    @Test
    fun cardSmsWithoutAccountIdentityRequiresMerchantEvidence() {
        val statement = row(2, LocalDate.of(2026, 8, 15), "Metro Market", 12_500, StatementAmountDirection.DEBIT)
            .copy(accountId = null, accountHint = null)
        val unrelated = sms(103, LocalDate.of(2026, 8, 15), "Unrelated Hotel", 12_500, TransactionType.EXPENSE)
            .copy(accountId = null, accountHint = null)

        val report = CreditCardStatementAuditor.audit(
            metadata(accountId = null, cardLast4 = null),
            listOf(statement),
            listOf(unrelated),
            zoneId = utc,
        )

        assertEquals(StatementAuditLineStatus.UNMATCHED, report.lines.single().status)
    }

    @Test
    fun cardSmsWithoutAccountIdentityCanMatchWithMerchantEvidence() {
        val statement = row(2, LocalDate.of(2026, 8, 15), "Metro Market", 12_500, StatementAmountDirection.DEBIT)
            .copy(accountId = null, accountHint = null)
        val matching = sms(108, LocalDate.of(2026, 8, 15), "Metro Market", 12_500, TransactionType.EXPENSE)
            .copy(accountId = null, accountHint = null)

        val result = CreditCardStatementAuditor.audit(
            metadata(accountId = null, cardLast4 = null),
            listOf(statement),
            listOf(matching),
            zoneId = utc,
        ).lines.single()

        assertEquals(StatementAuditLineStatus.MATCHED, result.status)
    }

    @Test
    fun exactSelectedAccountCanMatchEvenWhenMerchantTextDiffers() {
        val statement = row(2, LocalDate.of(2026, 8, 15), "Metro Market", 12_500, StatementAmountDirection.DEBIT)
        val sparseSms = sms(104, LocalDate.of(2026, 8, 15), "Card purchase", 12_500, TransactionType.EXPENSE)

        val result = CreditCardStatementAuditor.audit(
            metadata(),
            listOf(statement),
            listOf(sparseSms),
            zoneId = utc,
        ).lines.single()

        assertEquals(StatementAuditLineStatus.MATCHED, result.status)
        assertTrue(result.reasons.any { it.contains("identity matches") })
    }

    @Test
    fun nonCardSmsRequiresPositiveSelectedAccountMatch() {
        val statement = row(2, LocalDate.of(2026, 8, 15), "Metro Market", 12_500, StatementAmountDirection.DEBIT)
        val unscopedBankSms = sms(105, LocalDate.of(2026, 8, 15), "Metro Market", 12_500, TransactionType.EXPENSE)
            .copy(source = TransactionSource.BANK, accountId = null, accountHint = null)
        val selectedBankSms = unscopedBankSms.copy(id = 106, sourceMessageId = "sms-106", accountId = 9)

        val unscoped = CreditCardStatementAuditor.audit(
            metadata(),
            listOf(statement),
            listOf(unscopedBankSms),
            zoneId = utc,
        ).lines.single()
        val selected = CreditCardStatementAuditor.audit(
            metadata(),
            listOf(statement),
            listOf(selectedBankSms),
            zoneId = utc,
        ).lines.single()

        assertEquals(StatementAuditLineStatus.UNMATCHED, unscoped.status)
        assertEquals(StatementAuditLineStatus.MATCHED, selected.status)
    }

    @Test
    fun capsDenseSmsCandidateSetsAndWarnsForManualReview() {
        val statement = row(2, LocalDate.of(2026, 8, 15), "Metro Market", 12_500, StatementAmountDirection.DEBIT)
        val candidates = (1L..300L).map { id ->
            sms(id, LocalDate.of(2026, 8, 15), "Metro Market", 12_500, TransactionType.EXPENSE)
        }

        val report = CreditCardStatementAuditor.audit(metadata(), listOf(statement), candidates, zoneId = utc)

        assertEquals(StatementAuditLineStatus.POSSIBLE_DUPLICATE, report.lines.single().status)
        assertEquals(3, report.lines.single().candidates.size)
        assertTrue(report.warnings.any { it.contains("candidate sets were capped") })
    }

    @Test
    fun capsAuditRowsAndRejectsOverflowingTotals() {
        val base = row(2, LocalDate.of(2026, 8, 15), "Unique Merchant", 100, StatementAmountDirection.DEBIT)
        val oversized = List(StatementTableLimits.MAX_ROWS + 1) { index ->
            base.copy(rowNumber = index + 2, description = "Merchant $index", amountMinor = index.toLong() + 1)
        }
        val bounded = CreditCardStatementAuditor.audit(metadata(), oversized, emptyList(), zoneId = utc)

        assertEquals(StatementTableLimits.MAX_ROWS, bounded.lines.size)
        assertTrue(bounded.warnings.contains(StatementTableLimits.ROW_LIMIT_WARNING))

        val overflow = listOf(
            base.copy(rowNumber = 2, amountMinor = Long.MAX_VALUE, description = "First"),
            base.copy(rowNumber = 3, amountMinor = Long.MAX_VALUE, description = "Second"),
        )
        val failure = runCatching {
            CreditCardStatementAuditor.audit(metadata(), overflow, emptyList(), zoneId = utc)
        }.exceptionOrNull()
        assertTrue(failure is IllegalArgumentException)
        assertTrue(failure?.message.orEmpty().contains("supported amount range"))
    }

    @Test
    fun reportsLongMinimumDifferenceWithoutOverflowingAbsoluteValue() {
        val report = CreditCardStatementAuditor.audit(
            metadata(totalDueMinor = Long.MIN_VALUE),
            emptyList(),
            emptyList(),
            zoneId = utc,
        )

        val mismatch = report.issues.single { it.code == "total_due_mismatch" }
        assertTrue(mismatch.detail.contains("92233720368547758.08"))
    }

    @Test
    fun reviewInboxCandidateCannotReceiveHighConfidence() {
        val statement = row(2, LocalDate.of(2026, 8, 15), "Metro Market", 12_500, StatementAmountDirection.DEBIT)
        val needsReview = sms(107, LocalDate.of(2026, 8, 15), "Metro Market", 12_500, TransactionType.EXPENSE)
            .copy(reviewStatus = com.paisalens.app.data.model.ReviewStatus.NEEDS_REVIEW)

        val result = CreditCardStatementAuditor.audit(
            metadata(),
            listOf(statement),
            listOf(needsReview),
            zoneId = utc,
        ).lines.single()

        assertEquals(StatementAuditConfidence.MEDIUM, result.confidence)
        assertTrue(result.reasons.any { it.contains("review inbox") })
    }

    private fun metadata(
        openingBalanceMinor: Long = 0,
        totalDueMinor: Long? = null,
        minimumDueMinor: Long? = null,
        dueDate: LocalDate? = null,
        accountId: Long? = 9,
        cardLast4: String? = "4242",
    ) = StatementAuditMetadata(
        statementId = "card-2026-08",
        sourceFileName = "card-august.csv",
        accountId = accountId,
        cardLast4 = cardLast4,
        statementDateEpochDay = LocalDate.of(2026, 8, 20).toEpochDay(),
        periodStartEpochDay = LocalDate.of(2026, 8, 1).toEpochDay(),
        periodEndEpochDay = LocalDate.of(2026, 8, 20).toEpochDay(),
        dueDateEpochDay = dueDate?.toEpochDay(),
        openingBalanceMinor = openingBalanceMinor,
        totalDueMinor = totalDueMinor,
        minimumDueMinor = minimumDueMinor,
    )

    private fun row(
        rowNumber: Int,
        date: LocalDate,
        description: String,
        amountMinor: Long,
        direction: StatementAmountDirection,
    ) = StatementAuditRow(
        rowNumber = rowNumber,
        occurredAt = date.atStartOfDay(utc).toInstant().toEpochMilli(),
        description = description,
        amountMinor = amountMinor,
        direction = direction,
        accountId = 9,
        accountHint = "4242",
    )

    private fun sms(
        id: Long,
        date: LocalDate,
        merchant: String,
        amountMinor: Long,
        type: TransactionType,
    ) = TransactionRecord(
        id = id,
        sourceMessageId = "sms-$id",
        amountMinor = amountMinor,
        merchant = merchant,
        accountHint = "4242",
        category = ExpenseCategory.OTHER,
        type = type,
        occurredAt = date.atStartOfDay(utc).toInstant().toEpochMilli(),
        source = TransactionSource.CARD,
        sender = "HDFCBK",
        accountId = 9,
    )
}
