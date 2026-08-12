package com.paisalens.app.data.importer

import com.paisalens.app.data.model.ReviewStatus
import com.paisalens.app.data.model.StatementAmountDirection
import com.paisalens.app.data.model.StatementAuditCandidate
import com.paisalens.app.data.model.StatementAuditConfidence
import com.paisalens.app.data.model.StatementAuditConfig
import com.paisalens.app.data.model.StatementAuditIssue
import com.paisalens.app.data.model.StatementAuditIssueSeverity
import com.paisalens.app.data.model.StatementAuditLineResult
import com.paisalens.app.data.model.StatementAuditLineStatus
import com.paisalens.app.data.model.StatementAuditMetadata
import com.paisalens.app.data.model.StatementAuditReport
import com.paisalens.app.data.model.StatementAuditRow
import com.paisalens.app.data.model.StatementAuditTotals
import com.paisalens.app.data.model.StatementImportRow
import com.paisalens.app.data.model.StatementInputMode
import com.paisalens.app.data.model.StatementLineKind
import com.paisalens.app.data.model.StatementSourceSupport
import com.paisalens.app.data.model.TransactionRecord
import com.paisalens.app.data.model.TransactionSource
import com.paisalens.app.data.model.TransactionType
import com.paisalens.app.data.model.normalizedCurrency
import com.paisalens.app.data.model.normalizedMerchantKey
import java.math.BigInteger
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Local-only reconciliation for a reviewed credit-card statement.
 *
 * PDF layouts are intentionally not guessed here. A PDF uses [sourceSupport]'s structured fallback:
 * the user reviews the printed summary fields and supplies reviewed rows or a CSV/XLSX export.
 */
object CreditCardStatementAuditor {
    fun sourceSupport(fileName: String): StatementSourceSupport {
        val extension = fileName.substringAfterLast('.', "").lowercase(Locale.ROOT)
        return if (extension in setOf("csv", "tsv", "xlsx")) {
            StatementSourceSupport(
                mode = StatementInputMode.TABLE,
                directParsingSupported = true,
                message = "This tabular statement can be parsed and audited entirely on this device.",
            )
        } else {
            val format = if (extension == "pdf") "PDF" else "This file format"
            StatementSourceSupport(
                mode = StatementInputMode.STRUCTURED_MANUAL_FALLBACK,
                directParsingSupported = false,
                message = "$format is not auto-extracted because statement layouts vary. Review the printed summary, enter the fields below, and provide reviewed rows or a CSV/XLSX export; no document leaves the device.",
                requiredManualFields = listOf(
                    "Statement date",
                    "Billing period",
                    "Payment due date",
                    "Total amount due",
                    "Minimum amount due",
                    "Opening balance",
                    "Reviewed transaction rows or CSV/XLSX export",
                ),
            )
        }
    }

    fun auditImported(
        metadata: StatementAuditMetadata,
        importedRows: List<StatementImportRow>,
        existingTransactions: List<TransactionRecord>,
        config: StatementAuditConfig = StatementAuditConfig(),
        zoneId: ZoneId = ZoneId.systemDefault(),
    ): StatementAuditReport = audit(
        metadata = metadata,
        statementRows = StatementAuditRows.fromImported(importedRows, metadata.currency),
        existingTransactions = existingTransactions,
        config = config,
        zoneId = zoneId,
    )

    fun auditTable(
        metadata: StatementAuditMetadata,
        table: List<List<String>>,
        existingTransactions: List<TransactionRecord>,
        config: StatementAuditConfig = StatementAuditConfig(),
        zoneId: ZoneId = ZoneId.systemDefault(),
    ): StatementAuditReport {
        val parsed = StatementAuditTableParser.parse(table, zoneId, metadata.currency)
        val report = audit(metadata, parsed.rows, existingTransactions, config, zoneId)
        return report.copy(warnings = (report.warnings + parsed.warnings).distinct())
    }

    fun audit(
        metadata: StatementAuditMetadata,
        statementRows: List<StatementAuditRow>,
        existingTransactions: List<TransactionRecord>,
        config: StatementAuditConfig = StatementAuditConfig(),
        zoneId: ZoneId = ZoneId.systemDefault(),
    ): StatementAuditReport {
        validateConfig(config)
        val currency = metadata.currency.normalizedCurrency()
        val warnings = linkedSetOf<String>()
        val boundedRows = statementRows.take(StatementTableLimits.MAX_ROWS)
        if (statementRows.size > boundedRows.size) warnings += StatementTableLimits.ROW_LIMIT_WARNING
        val validRows = boundedRows.filter { it.amountMinor > 0 && it.description.isNotBlank() }
        val invalidCount = boundedRows.size - validRows.size
        if (invalidCount > 0) warnings += "$invalidCount invalid audit row${if (invalidCount == 1) " was" else "s were"} ignored."
        val foreignRows = validRows.filter { it.currency.normalizedCurrency() != currency }
        if (foreignRows.isNotEmpty()) {
            warnings += "${foreignRows.size} row${if (foreignRows.size == 1) " uses" else "s use"} another currency and were excluded from statement totals."
        }

        val kinds = validRows.map(::classify)
        val duplicateOf = detectStatementDuplicates(validRows, kinds, config, zoneId)
        val selectedAccountIds = buildSet {
            metadata.accountId?.let(::add)
            validRows.mapNotNullTo(this, StatementAuditRow::accountId)
        }
        val selectedAccountHints = buildSet {
            metadata.cardLast4?.normalizedLast4()?.let(::add)
            validRows.mapNotNullTo(this) { it.accountHint?.normalizedLast4() }
        }
        val selectedCardIdentitySupplied = selectedAccountIds.isNotEmpty() || selectedAccountHints.isNotEmpty()
        val smsTransactions = existingTransactions
            .filter { it.source in SMS_SOURCES && it.amountMinor > 0 }
            .filter { transaction ->
                val selectedAccountMatches =
                    transaction.accountId?.let(selectedAccountIds::contains) == true ||
                        transaction.accountHint?.normalizedLast4()?.let(selectedAccountHints::contains) == true
                selectedAccountMatches || (!selectedCardIdentitySupplied && transaction.source == TransactionSource.CARD)
            }
            .distinctBy { it.id to it.sourceMessageId }
        val smsIndex = SmsCandidateIndex(smsTransactions, zoneId)
        val candidatesByRow = validRows.indices.associateWith { rowIndex ->
            if (validRows[rowIndex].currency.normalizedCurrency() != currency) {
                emptyList()
            } else {
                val query = smsIndex.query(validRows[rowIndex], config)
                if (query.truncated) {
                    warnings += "Some unusually dense date/amount candidate sets were capped; review unmatched rows manually."
                }
                query.transactions.mapNotNull { indexed ->
                    scoreCandidate(
                        row = validRows[rowIndex],
                        kind = kinds[rowIndex],
                        transaction = indexed.transaction,
                        transactionIndex = indexed.transactionIndex,
                        metadata = metadata,
                        config = config,
                        zoneId = zoneId,
                    )
                }.sortedWith(
                    compareByDescending<ScoredCandidate> { it.candidate.score }
                        .thenBy { it.dateDifferenceDays }
                        .thenBy { it.amountDifferenceMinor }
                        .thenBy { it.transaction.id }
                        .thenBy { it.transaction.sourceMessageId },
                )
            }
        }

        val ambiguousRows = validRows.indices.filterTo(mutableSetOf()) { rowIndex ->
            val candidates = candidatesByRow.getValue(rowIndex)
            candidates.size > 1 && candidates[0].candidate.score - candidates[1].candidate.score <= config.ambiguousScoreDelta
        }
        val assignedRows = mutableMapOf<Int, ScoredCandidate>()
        val assignedTransactionIndexes = mutableSetOf<Int>()
        candidatesByRow
            .flatMap { (rowIndex, candidates) -> candidates.map { CandidateEdge(rowIndex, it) } }
            .filter { edge -> edge.rowIndex !in duplicateOf && edge.rowIndex !in ambiguousRows }
            .sortedWith(
                compareByDescending<CandidateEdge> { it.scored.candidate.score }
                    .thenBy { it.scored.dateDifferenceDays }
                    .thenBy { it.scored.amountDifferenceMinor }
                    .thenBy { validRows[it.rowIndex].rowNumber }
                    .thenBy { it.scored.transaction.id }
                    .thenBy { it.scored.transaction.sourceMessageId },
            )
            .forEach { edge ->
                if (edge.rowIndex !in assignedRows && edge.scored.transactionIndex !in assignedTransactionIndexes) {
                    assignedRows[edge.rowIndex] = edge.scored
                    assignedTransactionIndexes += edge.scored.transactionIndex
                }
            }

        val lineResults = validRows.indices.map { rowIndex ->
            val row = validRows[rowIndex]
            val kind = kinds[rowIndex]
            val candidates = candidatesByRow.getValue(rowIndex)
            val publicCandidates = candidates.take(MAX_VISIBLE_CANDIDATES).map { it.candidate }
            when {
                rowIndex in duplicateOf -> {
                    val original = validRows[duplicateOf.getValue(rowIndex)]
                    StatementAuditLineResult(
                        row = row,
                        kind = kind,
                        status = StatementAuditLineStatus.POSSIBLE_DUPLICATE,
                        score = candidates.firstOrNull()?.candidate?.score ?: 0,
                        confidence = candidates.firstOrNull()?.candidate?.confidence ?: StatementAuditConfidence.NONE,
                        reasons = listOf("This resembles statement row ${original.rowNumber}: same direction and amount, a nearby date, and a similar merchant."),
                        candidates = publicCandidates,
                    )
                }
                rowIndex in ambiguousRows -> StatementAuditLineResult(
                    row = row,
                    kind = kind,
                    status = StatementAuditLineStatus.POSSIBLE_DUPLICATE,
                    score = candidates.first().candidate.score,
                    confidence = candidates.first().candidate.confidence,
                    reasons = listOf("More than one SMS transaction is an equally plausible match; choose one before reconciling."),
                    candidates = publicCandidates,
                )
                rowIndex in assignedRows -> {
                    val match = assignedRows.getValue(rowIndex)
                    StatementAuditLineResult(
                        row = row,
                        kind = kind,
                        status = StatementAuditLineStatus.MATCHED,
                        matchedTransactionId = match.transaction.id,
                        matchedSourceMessageId = match.transaction.sourceMessageId,
                        score = match.candidate.score,
                        confidence = match.candidate.confidence,
                        reasons = match.candidate.reasons,
                        candidates = publicCandidates,
                    )
                }
                else -> StatementAuditLineResult(
                    row = row,
                    kind = kind,
                    status = StatementAuditLineStatus.UNMATCHED,
                    reasons = unmatchedReasons(row, kind, candidates, currency),
                    candidates = publicCandidates,
                )
            }
        }

        val usedTransactionIndexes = assignedRows.values.mapTo(mutableSetOf()) { it.transactionIndex }
        val unmatchedExisting = smsTransactions.withIndex()
            .filter { (index, transaction) ->
                index !in usedTransactionIndexes &&
                    transaction.type in setOf(TransactionType.EXPENSE, TransactionType.REFUND) &&
                    transactionInStatementWindow(transaction, metadata, validRows, config, zoneId)
            }
            .map { it.value }
            .sortedWith(compareBy<TransactionRecord> { it.occurredAt }.thenBy { it.id }.thenBy { it.sourceMessageId })
        val totals = calculateTotals(
            metadata,
            validRows.zip(kinds).filter { (row, _) -> row.currency.normalizedCurrency() == currency },
        )
        val issues = buildIssues(metadata, totals, lineResults, unmatchedExisting, currency, config)
        return StatementAuditReport(
            metadata = metadata.copy(currency = currency),
            totals = totals,
            lines = lineResults,
            unmatchedExistingTransactions = unmatchedExisting,
            warnings = warnings.toList(),
            issues = issues,
        )
    }

    fun classify(row: StatementAuditRow): StatementLineKind {
        val description = normalizedMerchantKey(row.description)
        return when (row.direction) {
            StatementAmountDirection.CREDIT -> when {
                description.containsAny(REFUND_TERMS) -> StatementLineKind.REFUND
                description.containsAny(PAYMENT_TERMS) -> StatementLineKind.PAYMENT
                else -> StatementLineKind.OTHER_CREDIT
            }
            StatementAmountDirection.DEBIT -> when {
                description.containsAny(GST_TERMS) -> StatementLineKind.GST
                description.containsAny(INTEREST_TERMS) -> StatementLineKind.INTEREST
                description.containsAny(FEE_TERMS) -> StatementLineKind.FEE
                description.containsAny(OTHER_DEBIT_TERMS) -> StatementLineKind.OTHER_DEBIT
                else -> StatementLineKind.PURCHASE
            }
        }
    }

    private fun validateConfig(config: StatementAuditConfig) {
        require(config.dateToleranceDays in 0..MAX_DATE_TOLERANCE_DAYS) {
            "dateToleranceDays must be between 0 and $MAX_DATE_TOLERANCE_DAYS"
        }
        require(config.amountToleranceMinor in 0..MAX_AMOUNT_TOLERANCE_MINOR) {
            "amountToleranceMinor must be between 0 and $MAX_AMOUNT_TOLERANCE_MINOR"
        }
        require(config.possibleDuplicateDateToleranceDays in 0..MAX_DUPLICATE_DATE_TOLERANCE_DAYS) {
            "possibleDuplicateDateToleranceDays must be between 0 and $MAX_DUPLICATE_DATE_TOLERANCE_DAYS"
        }
        require(config.possibleDuplicateAmountToleranceMinor in 0..MAX_DUPLICATE_AMOUNT_TOLERANCE_MINOR) {
            "possibleDuplicateAmountToleranceMinor must be between 0 and $MAX_DUPLICATE_AMOUNT_TOLERANCE_MINOR"
        }
        require(config.minimumMatchScore in 0..100) { "minimumMatchScore must be between 0 and 100" }
        require(config.ambiguousScoreDelta in 0..100) { "ambiguousScoreDelta must be between 0 and 100" }
    }

    private fun detectStatementDuplicates(
        rows: List<StatementAuditRow>,
        kinds: List<StatementLineKind>,
        config: StatementAuditConfig,
        zoneId: ZoneId,
    ): Map<Int, Int> {
        val duplicates = mutableMapOf<Int, Int>()
        val originalsByBucket = mutableMapOf<DuplicateBucketKey, ArrayDeque<Int>>()
        val amountBucketWidth = config.possibleDuplicateAmountToleranceMinor + 1
        rows.indices.forEach { candidateIndex ->
            val candidate = rows[candidateIndex]
            val candidateDay = epochDay(candidate.occurredAt, zoneId)
            val candidateAmountBucket = candidate.amountMinor / amountBucketWidth
            val merchantAnchors = duplicateMerchantAnchors(candidate.description)
            var originalIndex: Int? = null
            for (merchantAnchor in merchantAnchors) {
                for (dayOffset in -config.possibleDuplicateDateToleranceDays..config.possibleDuplicateDateToleranceDays) {
                    for (amountOffset in -1L..1L) {
                        val key = DuplicateBucketKey(
                            direction = candidate.direction,
                            kind = kinds[candidateIndex],
                            currency = candidate.currency.normalizedCurrency(),
                            merchantAnchor = merchantAnchor,
                            amountBucket = candidateAmountBucket + amountOffset,
                            epochDay = candidateDay + dayOffset,
                        )
                        originalsByBucket[key].orEmpty().forEach { earlierIndex ->
                            val earlier = rows[earlierIndex]
                            if (
                                abs(candidate.amountMinor - earlier.amountMinor) <= config.possibleDuplicateAmountToleranceMinor &&
                                merchantSimilarity(candidate.description, earlier.description) >= DUPLICATE_MERCHANT_SIMILARITY &&
                                (originalIndex == null || earlierIndex < requireNotNull(originalIndex))
                            ) {
                                originalIndex = earlierIndex
                            }
                        }
                    }
                }
            }
            if (originalIndex != null) {
                duplicates[candidateIndex] = requireNotNull(originalIndex)
            } else {
                merchantAnchors.forEach { merchantAnchor ->
                    val key = DuplicateBucketKey(
                        direction = candidate.direction,
                        kind = kinds[candidateIndex],
                        currency = candidate.currency.normalizedCurrency(),
                        merchantAnchor = merchantAnchor,
                        amountBucket = candidateAmountBucket,
                        epochDay = candidateDay,
                    )
                    val bucket = originalsByBucket.getOrPut(key, ::ArrayDeque)
                    if (bucket.size == MAX_DUPLICATE_BUCKET_ENTRIES) bucket.removeFirst()
                    bucket.addLast(candidateIndex)
                }
            }
        }
        return duplicates
    }

    private data class DuplicateBucketKey(
        val direction: StatementAmountDirection,
        val kind: StatementLineKind,
        val currency: String,
        val merchantAnchor: String,
        val amountBucket: Long,
        val epochDay: Long,
    )

    private fun duplicateMerchantAnchors(description: String): List<String> {
        val normalized = normalizedMerchantKey(description)
        return merchantTokens(normalized)
            .sorted()
            .take(MAX_DUPLICATE_MERCHANT_ANCHORS)
            .takeIf(List<String>::isNotEmpty)
            ?: listOf(normalized.take(32))
    }

    private fun scoreCandidate(
        row: StatementAuditRow,
        kind: StatementLineKind,
        transaction: TransactionRecord,
        transactionIndex: Int,
        metadata: StatementAuditMetadata,
        config: StatementAuditConfig,
        zoneId: ZoneId,
    ): ScoredCandidate? {
        if (!typesCompatible(kind, transaction.type)) return null
        val accountEvidence = accountEvidence(row, metadata, transaction)
        if (accountEvidence.conflicts) return null
        if (accountEvidence.expectedIdentitySupplied && !accountEvidence.positiveMatch) return null
        if (transaction.source != TransactionSource.CARD && !accountEvidence.positiveMatch) return null
        val amountDifference = abs(row.amountMinor - transaction.amountMinor)
        if (amountDifference > config.amountToleranceMinor) return null
        val dateDifference = dateDifferenceDays(row.occurredAt, transaction.occurredAt, zoneId)
        if (dateDifference > config.dateToleranceDays) return null
        val similarity = merchantSimilarity(row.description, transaction.merchant)
        if (!accountEvidence.positiveMatch && similarity < MIN_MERCHANT_EVIDENCE) return null
        val amountScore = if (amountDifference == 0L) {
            45
        } else if (config.amountToleranceMinor == 0L) {
            0
        } else {
            30 + ((config.amountToleranceMinor - amountDifference) * 14 / config.amountToleranceMinor).toInt()
        }
        val dateScore = if (dateDifference == 0L) 30 else (30 - dateDifference.toInt() * 5).coerceAtLeast(10)
        val merchantScore = (similarity * 20).roundToInt()
        val accountScore = if (accountEvidence.positiveMatch) 5 else 0
        val reviewPenalty = if (transaction.reviewStatus == ReviewStatus.NEEDS_REVIEW) 10 else 0
        val score = (amountScore + dateScore + merchantScore + accountScore - reviewPenalty).coerceIn(0, 100)
        if (score < config.minimumMatchScore) return null
        val reasons = buildList {
            add(if (amountDifference == 0L) "Exact amount match." else "Amount differs by ${amountDifference} minor units, within tolerance.")
            add(if (dateDifference == 0L) "Same transaction date." else "Transaction dates are $dateDifference day${if (dateDifference == 1L) "" else "s"} apart.")
            when {
                similarity >= 0.8 -> add("Merchant descriptions closely match.")
                similarity >= 0.35 -> add("Merchant descriptions partially match.")
                else -> add("Merchant descriptions differ; amount and date provide the match.")
            }
            if (accountScore > 0) add("Card/account identity matches.")
            if (transaction.reviewStatus == ReviewStatus.NEEDS_REVIEW) add("The SMS transaction is still in the review inbox.")
        }
        val confidence = confidenceFor(score).let { calculated ->
            if (transaction.reviewStatus == ReviewStatus.NEEDS_REVIEW && calculated == StatementAuditConfidence.HIGH) {
                StatementAuditConfidence.MEDIUM
            } else {
                calculated
            }
        }
        return ScoredCandidate(
            transactionIndex = transactionIndex,
            transaction = transaction,
            candidate = StatementAuditCandidate(
                transactionId = transaction.id,
                sourceMessageId = transaction.sourceMessageId,
                score = score,
                confidence = confidence,
                reasons = reasons,
            ),
            dateDifferenceDays = dateDifference,
            amountDifferenceMinor = amountDifference,
        )
    }

    private fun typesCompatible(kind: StatementLineKind, type: TransactionType): Boolean = when (kind) {
        StatementLineKind.PURCHASE,
        StatementLineKind.FEE,
        StatementLineKind.INTEREST,
        StatementLineKind.GST,
        StatementLineKind.OTHER_DEBIT,
        -> type == TransactionType.EXPENSE
        StatementLineKind.REFUND,
        StatementLineKind.OTHER_CREDIT,
        -> type == TransactionType.REFUND || type == TransactionType.INCOME
        StatementLineKind.PAYMENT -> false
    }

    private data class AccountEvidence(
        val expectedIdentitySupplied: Boolean,
        val positiveMatch: Boolean,
        val conflicts: Boolean,
    )

    private fun accountEvidence(
        row: StatementAuditRow,
        metadata: StatementAuditMetadata,
        transaction: TransactionRecord,
    ): AccountEvidence {
        val expectedIds = listOfNotNull(row.accountId, metadata.accountId).distinct()
        val expectedHints = listOfNotNull(row.accountHint, metadata.cardLast4)
            .map { it.filter(Char::isDigit).takeLast(4) }
            .filter(String::isNotBlank)
            .distinct()
        val actual = transaction.accountHint?.filter(Char::isDigit)?.takeLast(4).orEmpty()
        val idMatch = transaction.accountId != null && transaction.accountId in expectedIds
        val hintMatch = actual.isNotEmpty() && actual in expectedHints
        val idConflict = transaction.accountId != null && expectedIds.isNotEmpty() && transaction.accountId !in expectedIds
        val hintConflict = actual.isNotEmpty() && expectedHints.isNotEmpty() && actual !in expectedHints
        return AccountEvidence(
            expectedIdentitySupplied = expectedIds.isNotEmpty() || expectedHints.isNotEmpty(),
            positiveMatch = idMatch || hintMatch,
            conflicts = idConflict || hintConflict,
        )
    }

    private fun unmatchedReasons(
        row: StatementAuditRow,
        kind: StatementLineKind,
        candidates: List<ScoredCandidate>,
        statementCurrency: String,
    ): List<String> = when {
        row.currency.normalizedCurrency() != statementCurrency -> listOf("The row currency differs from the statement currency and cannot be compared safely.")
        kind == StatementLineKind.PAYMENT -> listOf("Payments reduce the balance but are not matched to purchase SMS messages.")
        candidates.isNotEmpty() -> listOf("A candidate SMS was assigned to a stronger statement match.")
        else -> listOf("No SMS transaction met the configured amount, date, type, and account tolerances.")
    }

    private fun calculateTotals(
        metadata: StatementAuditMetadata,
        classifiedRows: List<Pair<StatementAuditRow, StatementLineKind>>,
    ): StatementAuditTotals {
        fun total(kind: StatementLineKind): Long = classifiedRows.asSequence()
            .filter { it.second == kind }
            .fold(0L) { running, row -> checkedAdd(running, row.first.amountMinor) }
        val purchases = total(StatementLineKind.PURCHASE)
        val fees = total(StatementLineKind.FEE)
        val interest = total(StatementLineKind.INTEREST)
        val gst = total(StatementLineKind.GST)
        val otherDebits = total(StatementLineKind.OTHER_DEBIT)
        val refunds = total(StatementLineKind.REFUND)
        val payments = total(StatementLineKind.PAYMENT)
        val otherCredits = total(StatementLineKind.OTHER_CREDIT)
        val debits = listOf(purchases, fees, interest, gst, otherDebits).fold(0L, ::checkedAdd)
        val credits = listOf(refunds, payments, otherCredits).fold(0L, ::checkedAdd)
        val closing = checkedSubtract(checkedAdd(metadata.openingBalanceMinor, debits), credits)
        return StatementAuditTotals(
            purchasesMinor = purchases,
            feesMinor = fees,
            interestMinor = interest,
            gstMinor = gst,
            refundsMinor = refunds,
            paymentsMinor = payments,
            otherDebitsMinor = otherDebits,
            otherCreditsMinor = otherCredits,
            totalDebitsMinor = debits,
            totalCreditsMinor = credits,
            calculatedClosingBalanceMinor = closing,
            declaredTotalDueMinor = metadata.totalDueMinor,
            totalDueDifferenceMinor = metadata.totalDueMinor?.let { checkedSubtract(it, closing) },
            minimumDueMinor = metadata.minimumDueMinor,
            dueDateEpochDay = metadata.dueDateEpochDay,
        )
    }

    private fun checkedAdd(first: Long, second: Long): Long = try {
        Math.addExact(first, second)
    } catch (_: ArithmeticException) {
        throw IllegalArgumentException("Statement totals exceed the supported amount range")
    }

    private fun checkedSubtract(first: Long, second: Long): Long = try {
        Math.subtractExact(first, second)
    } catch (_: ArithmeticException) {
        throw IllegalArgumentException("Statement totals exceed the supported amount range")
    }

    private fun buildIssues(
        metadata: StatementAuditMetadata,
        totals: StatementAuditTotals,
        lines: List<StatementAuditLineResult>,
        unmatchedExisting: List<TransactionRecord>,
        currency: String,
        config: StatementAuditConfig,
    ): List<StatementAuditIssue> = buildList {
        if (metadata.statementId.isBlank()) {
            add(issue("missing_statement_id", StatementAuditIssueSeverity.WARNING, "Statement needs a label", "No statement identifier was supplied.", "Add the month or statement reference so this audit can be identified later."))
        }
        if (metadata.dueDateEpochDay != null && metadata.statementDateEpochDay != null && metadata.dueDateEpochDay < metadata.statementDateEpochDay) {
            add(issue("invalid_due_date", StatementAuditIssueSeverity.ERROR, "Due date is before statement date", "The reviewed statement dates are inconsistent.", "Check the statement date and payment due date against the original statement."))
        }
        if (metadata.periodStartEpochDay != null && metadata.periodEndEpochDay != null && metadata.periodStartEpochDay > metadata.periodEndEpochDay) {
            add(issue("invalid_billing_period", StatementAuditIssueSeverity.ERROR, "Billing period is reversed", "The billing-period start is after its end.", "Check both billing-period dates against the original statement."))
        }
        if (metadata.minimumDueMinor != null && metadata.totalDueMinor != null && metadata.minimumDueMinor > metadata.totalDueMinor) {
            add(issue("minimum_above_total", StatementAuditIssueSeverity.ERROR, "Minimum due exceeds total due", "The entered minimum amount due is greater than the total amount due.", "Recheck both summary amounts before relying on this audit."))
        }
        if (metadata.totalDueMinor != null && metadata.totalDueMinor < 0) {
            add(issue("negative_total_due", StatementAuditIssueSeverity.ERROR, "Total due is negative", "A payment amount due cannot be negative.", "Enter zero for a credit balance, or recheck the printed total due."))
        }
        if (metadata.minimumDueMinor != null && metadata.minimumDueMinor < 0) {
            add(issue("negative_minimum_due", StatementAuditIssueSeverity.ERROR, "Minimum due is negative", "A minimum payment cannot be negative.", "Recheck the minimum amount due printed on the statement."))
        }
        if (metadata.dueDateEpochDay == null) {
            add(issue("missing_due_date", StatementAuditIssueSeverity.INFO, "Payment due date not entered", "This audit cannot surface the statement deadline.", "Enter the payment due date printed in the statement summary."))
        }
        if (metadata.minimumDueMinor == null) {
            add(issue("missing_minimum_due", StatementAuditIssueSeverity.INFO, "Minimum due not entered", "The minimum payment cannot be compared with the total balance.", "Enter the minimum amount due printed in the statement summary."))
        }
        val difference = totals.totalDueDifferenceMinor
        val absoluteDifference = difference?.let { BigInteger.valueOf(it).abs() }
        if (absoluteDifference != null && absoluteDifference > BigInteger.valueOf(config.amountToleranceMinor)) {
            add(
                issue(
                    "total_due_mismatch",
                    StatementAuditIssueSeverity.ERROR,
                    "Statement total does not reconcile",
                    "Declared total due and the calculated closing balance differ by ${formatMinor(absoluteDifference, currency)}.",
                    "Check the opening balance, payments, credits, skipped rows, and foreign-currency conversions.",
                ),
            )
        } else if (metadata.totalDueMinor == null) {
            add(issue("missing_total_due", StatementAuditIssueSeverity.INFO, "Total due not entered", "The calculated closing balance is available, but it cannot be compared with the printed total due.", "Enter the statement's total amount due to complete reconciliation."))
        }
        val possibleDuplicates = lines.filter { it.status == StatementAuditLineStatus.POSSIBLE_DUPLICATE }
        if (possibleDuplicates.isNotEmpty()) {
            add(
                issue(
                    "possible_duplicates",
                    StatementAuditIssueSeverity.WARNING,
                    "Possible duplicate transactions",
                    "${possibleDuplicates.size} statement row${if (possibleDuplicates.size == 1) " needs" else "s need"} review.",
                    "Compare the candidate SMS records and keep only genuine separate charges.",
                    possibleDuplicates.map { it.row.rowNumber },
                ),
            )
        }
        val unmatched = lines.filter {
            it.status == StatementAuditLineStatus.UNMATCHED &&
                it.kind != StatementLineKind.PAYMENT &&
                it.row.currency.normalizedCurrency() == currency
        }
        if (unmatched.isNotEmpty()) {
            add(
                issue(
                    "unmatched_statement_rows",
                    StatementAuditIssueSeverity.WARNING,
                    "Statement charges missing from SMS history",
                    "${unmatched.size} statement row${if (unmatched.size == 1) " has" else "s have"} no safe SMS match.",
                    "Review these rows and add or link any missing transaction manually.",
                    unmatched.map { it.row.rowNumber },
                ),
            )
        }
        if (unmatchedExisting.isNotEmpty()) {
            add(
                issue(
                    "unmatched_sms_transactions",
                    StatementAuditIssueSeverity.INFO,
                    "SMS transactions absent from the statement",
                    "${unmatchedExisting.size} SMS transaction${if (unmatchedExisting.size == 1) " falls" else "s fall"} in the statement window without a statement match.",
                    "Check whether these charges are pending, reversed, on another card, or outside the billing cut-off.",
                ),
            )
        }
    }

    private fun issue(
        code: String,
        severity: StatementAuditIssueSeverity,
        title: String,
        detail: String,
        recommendation: String,
        rowNumbers: List<Int> = emptyList(),
    ) = StatementAuditIssue(code, severity, title, detail, recommendation, rowNumbers)

    private fun transactionInStatementWindow(
        transaction: TransactionRecord,
        metadata: StatementAuditMetadata,
        rows: List<StatementAuditRow>,
        config: StatementAuditConfig,
        zoneId: ZoneId,
    ): Boolean {
        val transactionDay = Instant.ofEpochMilli(transaction.occurredAt).atZone(zoneId).toLocalDate().toEpochDay()
        val first = metadata.periodStartEpochDay ?: rows.minOfOrNull { Instant.ofEpochMilli(it.occurredAt).atZone(zoneId).toLocalDate().toEpochDay() }
        val last = metadata.periodEndEpochDay ?: rows.maxOfOrNull { Instant.ofEpochMilli(it.occurredAt).atZone(zoneId).toLocalDate().toEpochDay() }
        if (first == null || last == null) return false
        return transactionDay in (first - config.dateToleranceDays)..(last + config.dateToleranceDays)
    }

    private fun merchantSimilarity(first: String, second: String): Double {
        val firstNormalized = normalizedMerchantKey(first)
        val secondNormalized = normalizedMerchantKey(second)
        if (firstNormalized.isBlank() || secondNormalized.isBlank()) return 0.0
        if (firstNormalized == secondNormalized) return 1.0
        if (firstNormalized.contains(secondNormalized) || secondNormalized.contains(firstNormalized)) return 0.88
        val firstTokens = merchantTokens(firstNormalized)
        val secondTokens = merchantTokens(secondNormalized)
        if (firstTokens.isEmpty() || secondTokens.isEmpty()) return 0.0
        val intersection = firstTokens.intersect(secondTokens).size.toDouble()
        val union = firstTokens.union(secondTokens).size.toDouble()
        return if (union == 0.0) 0.0 else intersection / union
    }

    private fun merchantTokens(value: String): Set<String> = value.split(' ')
        .asSequence()
        .filter { it.length > 1 && it !in MERCHANT_STOP_WORDS && !it.all(Char::isDigit) }
        .toSet()

    private fun dateDifferenceDays(first: Long, second: Long, zoneId: ZoneId): Long {
        val firstDate = Instant.ofEpochMilli(first).atZone(zoneId).toLocalDate()
        val secondDate = Instant.ofEpochMilli(second).atZone(zoneId).toLocalDate()
        return abs(ChronoUnit.DAYS.between(firstDate, secondDate))
    }

    private fun confidenceFor(score: Int): StatementAuditConfidence = when {
        score >= 85 -> StatementAuditConfidence.HIGH
        score >= 70 -> StatementAuditConfidence.MEDIUM
        score > 0 -> StatementAuditConfidence.LOW
        else -> StatementAuditConfidence.NONE
    }

    private fun String.containsAny(terms: Set<String>): Boolean = terms.any { term ->
        this == term || startsWith("$term ") || endsWith(" $term") || contains(" $term ")
    }

    private fun formatMinor(amountMinor: BigInteger, currency: String): String {
        val (major, minor) = amountMinor.divideAndRemainder(BigInteger.valueOf(100))
        return "$currency $major.${minor.toString().padStart(2, '0')}"
    }

    private data class IndexedSmsTransaction(
        val transactionIndex: Int,
        val transaction: TransactionRecord,
    )

    private data class CandidateQuery(
        val transactions: List<IndexedSmsTransaction>,
        val truncated: Boolean,
    )

    private class SmsCandidateIndex(
        transactions: List<TransactionRecord>,
        private val zoneId: ZoneId,
    ) {
        private val byDay = transactions.withIndex()
            .groupBy { indexed -> epochDay(indexed.value.occurredAt, zoneId) }
            .mapValues { (_, values) ->
                values.map { IndexedSmsTransaction(it.index, it.value) }
                    .sortedWith(compareBy<IndexedSmsTransaction> { it.transaction.amountMinor }
                        .thenBy { it.transaction.id }
                        .thenBy { it.transaction.sourceMessageId })
            }

        fun query(row: StatementAuditRow, config: StatementAuditConfig): CandidateQuery {
            val rowDay = epochDay(row.occurredAt, zoneId)
            val minimumAmount = (row.amountMinor - config.amountToleranceMinor).coerceAtLeast(1)
            val maximumAmount = if (row.amountMinor > Long.MAX_VALUE - config.amountToleranceMinor) {
                Long.MAX_VALUE
            } else {
                row.amountMinor + config.amountToleranceMinor
            }
            val matches = mutableListOf<IndexedSmsTransaction>()
            var truncated = false
            val offsets = centeredOffsets(config.dateToleranceDays)
            for ((offsetIndex, offset) in offsets.withIndex()) {
                val bucket = byDay[rowDay + offset] ?: continue
                val start = bucket.lowerBound(minimumAmount)
                val end = bucket.upperBound(maximumAmount)
                var right = bucket.lowerBound(row.amountMinor).coerceIn(start, end)
                var left = right - 1
                while ((left >= start || right < end) && matches.size < MAX_CANDIDATES_PER_ROW) {
                    val useLeft = when {
                        left < start -> false
                        right >= end -> true
                        else -> row.amountMinor - bucket[left].transaction.amountMinor <=
                            bucket[right].transaction.amountMinor - row.amountMinor
                    }
                    matches += if (useLeft) bucket[left--] else bucket[right++]
                }
                if (left >= start || right < end) truncated = true
                if (matches.size == MAX_CANDIDATES_PER_ROW) {
                    if (offsetIndex < offsets.lastIndex) truncated = true
                    break
                }
            }
            return CandidateQuery(matches, truncated)
        }

        private fun List<IndexedSmsTransaction>.lowerBound(amountMinor: Long): Int {
            var low = 0
            var high = size
            while (low < high) {
                val middle = (low + high) ushr 1
                if (this[middle].transaction.amountMinor < amountMinor) low = middle + 1 else high = middle
            }
            return low
        }

        private fun List<IndexedSmsTransaction>.upperBound(amountMinor: Long): Int {
            var low = 0
            var high = size
            while (low < high) {
                val middle = (low + high) ushr 1
                if (this[middle].transaction.amountMinor <= amountMinor) low = middle + 1 else high = middle
            }
            return low
        }
    }

    private fun centeredOffsets(tolerance: Int): List<Int> = buildList(tolerance * 2 + 1) {
        add(0)
        for (distance in 1..tolerance) {
            add(-distance)
            add(distance)
        }
    }

    private fun epochDay(timestamp: Long, zoneId: ZoneId): Long =
        Instant.ofEpochMilli(timestamp).atZone(zoneId).toLocalDate().toEpochDay()

    private fun String.normalizedLast4(): String? = filter(Char::isDigit).takeLast(4).takeIf { it.length == 4 }

    private data class ScoredCandidate(
        val transactionIndex: Int,
        val transaction: TransactionRecord,
        val candidate: StatementAuditCandidate,
        val dateDifferenceDays: Long,
        val amountDifferenceMinor: Long,
    )

    private data class CandidateEdge(
        val rowIndex: Int,
        val scored: ScoredCandidate,
    )

    private val SMS_SOURCES = setOf(TransactionSource.BANK, TransactionSource.CARD, TransactionSource.UPI, TransactionSource.WALLET)
    private val REFUND_TERMS = setOf("refund", "reversal", "reversed", "chargeback", "cashback", "credit voucher")
    private val PAYMENT_TERMS = setOf("payment", "payment received", "autopay", "auto pay", "neft payment", "imps payment")
    private val GST_TERMS = setOf("gst", "cgst", "sgst", "igst", "goods and services tax")
    private val INTEREST_TERMS = setOf("interest", "finance charge", "finance charges", "interest charge", "interest charges")
    private val FEE_TERMS = setOf(
        "fee",
        "fees",
        "late fee",
        "annual fee",
        "renewal fee",
        "processing fee",
        "overlimit fee",
        "over limit fee",
        "cash advance fee",
        "fuel surcharge",
        "surcharge",
    )
    private val OTHER_DEBIT_TERMS = setOf("cash advance", "cash withdrawal", "balance transfer")
    private val MERCHANT_STOP_WORDS = setOf("pos", "upi", "txn", "transaction", "purchase", "debit", "credit", "card", "india", "ref")
    private const val DUPLICATE_MERCHANT_SIMILARITY = 0.8
    private const val MIN_MERCHANT_EVIDENCE = 0.2
    private const val MAX_VISIBLE_CANDIDATES = 3
    private const val MAX_CANDIDATES_PER_ROW = 256
    private const val MAX_DUPLICATE_BUCKET_ENTRIES = 16
    private const val MAX_DUPLICATE_MERCHANT_ANCHORS = 8
    private const val MAX_DATE_TOLERANCE_DAYS = 31
    private const val MAX_DUPLICATE_DATE_TOLERANCE_DAYS = 7
    private const val MAX_AMOUNT_TOLERANCE_MINOR = 1_000_000L
    private const val MAX_DUPLICATE_AMOUNT_TOLERANCE_MINOR = 100_000L
}
