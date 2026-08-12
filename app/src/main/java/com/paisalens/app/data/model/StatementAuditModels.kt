package com.paisalens.app.data.model

import java.time.LocalDate

/** User-reviewed values printed in the statement summary. */
data class StatementAuditMetadata(
    val statementId: String,
    val sourceFileName: String? = null,
    val issuer: String? = null,
    val accountId: Long? = null,
    val accountName: String? = null,
    val cardLast4: String? = null,
    val statementDateEpochDay: Long? = null,
    val periodStartEpochDay: Long? = null,
    val periodEndEpochDay: Long? = null,
    val dueDateEpochDay: Long? = null,
    val openingBalanceMinor: Long = 0,
    val totalDueMinor: Long? = null,
    val minimumDueMinor: Long? = null,
    val currency: String = "INR",
) {
    val dueDate: LocalDate? get() = dueDateEpochDay?.let(LocalDate::ofEpochDay)
}

enum class StatementAmountDirection {
    DEBIT,
    CREDIT,
}

enum class StatementLineKind {
    PURCHASE,
    FEE,
    INTEREST,
    GST,
    REFUND,
    PAYMENT,
    OTHER_DEBIT,
    OTHER_CREDIT,
}

data class StatementAuditRow(
    val rowNumber: Int,
    val occurredAt: Long,
    val description: String,
    val amountMinor: Long,
    val direction: StatementAmountDirection,
    val currency: String = "INR",
    val accountId: Long? = null,
    val accountHint: String? = null,
    val sourceReference: String? = null,
)

data class StatementAuditParseResult(
    val rows: List<StatementAuditRow>,
    val skippedRows: Int,
    val warnings: List<String>,
)

data class StatementAuditConfig(
    val dateToleranceDays: Int = 3,
    val amountToleranceMinor: Long = 100,
    val possibleDuplicateDateToleranceDays: Int = 1,
    val possibleDuplicateAmountToleranceMinor: Long = 0,
    val minimumMatchScore: Int = 60,
    val ambiguousScoreDelta: Int = 3,
)

enum class StatementAuditLineStatus {
    MATCHED,
    UNMATCHED,
    POSSIBLE_DUPLICATE,
}

enum class StatementAuditConfidence {
    HIGH,
    MEDIUM,
    LOW,
    NONE,
}

data class StatementAuditCandidate(
    val transactionId: Long,
    val sourceMessageId: String,
    val score: Int,
    val confidence: StatementAuditConfidence,
    val reasons: List<String>,
)

data class StatementAuditLineResult(
    val row: StatementAuditRow,
    val kind: StatementLineKind,
    val status: StatementAuditLineStatus,
    val matchedTransactionId: Long? = null,
    val matchedSourceMessageId: String? = null,
    val score: Int = 0,
    val confidence: StatementAuditConfidence = StatementAuditConfidence.NONE,
    val reasons: List<String> = emptyList(),
    val candidates: List<StatementAuditCandidate> = emptyList(),
)

data class StatementAuditTotals(
    val purchasesMinor: Long,
    val feesMinor: Long,
    val interestMinor: Long,
    val gstMinor: Long,
    val refundsMinor: Long,
    val paymentsMinor: Long,
    val otherDebitsMinor: Long,
    val otherCreditsMinor: Long,
    val totalDebitsMinor: Long,
    val totalCreditsMinor: Long,
    val calculatedClosingBalanceMinor: Long,
    val declaredTotalDueMinor: Long?,
    val totalDueDifferenceMinor: Long?,
    val minimumDueMinor: Long?,
    val dueDateEpochDay: Long?,
)

data class StatementAuditReport(
    val metadata: StatementAuditMetadata,
    val totals: StatementAuditTotals,
    val lines: List<StatementAuditLineResult>,
    val unmatchedExistingTransactions: List<TransactionRecord>,
    val warnings: List<String> = emptyList(),
    val issues: List<StatementAuditIssue> = emptyList(),
) {
    val matchedCount: Int get() = lines.count { it.status == StatementAuditLineStatus.MATCHED }
    val unmatchedCount: Int get() = lines.count { it.status == StatementAuditLineStatus.UNMATCHED }
    val possibleDuplicateCount: Int get() = lines.count { it.status == StatementAuditLineStatus.POSSIBLE_DUPLICATE }
}

enum class StatementAuditIssueSeverity {
    INFO,
    WARNING,
    ERROR,
}

data class StatementAuditIssue(
    val code: String,
    val severity: StatementAuditIssueSeverity,
    val title: String,
    val detail: String,
    val recommendation: String,
    val rowNumbers: List<Int> = emptyList(),
)

enum class StatementInputMode {
    TABLE,
    STRUCTURED_MANUAL_FALLBACK,
}

data class StatementSourceSupport(
    val mode: StatementInputMode,
    val directParsingSupported: Boolean,
    val message: String,
    val requiredManualFields: List<String> = emptyList(),
)
