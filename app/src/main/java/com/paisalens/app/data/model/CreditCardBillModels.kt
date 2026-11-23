package com.paisalens.app.data.model

import java.util.Locale

enum class CreditCardBillStatus {
    DUE,
    PAID,
}

enum class CreditCardBillPaymentResult {
    CONFIRMATION_REQUIRED,
    MARKED_PAID,
    ALREADY_PAID,
    NOT_FOUND,
}

/** A credit-card statement summary parsed locally from an SMS alert. */
data class ParsedCreditCardBill(
    val sourceMessageId: String,
    val cardIdentityKey: String,
    val accountHint: String?,
    val institutionName: String,
    val totalDueMinor: Long,
    val minimumDueMinor: Long? = null,
    val dueDateEpochDay: Long,
    val detectedAt: Long,
    val sender: String,
    val rawMessage: String,
) {
    val billKey: String get() = "$cardIdentityKey:$dueDateEpochDay"
}

/** A persisted bill cycle. A new due date creates history instead of replacing an older cycle. */
data class CreditCardBill(
    val id: Long = 0,
    val billKey: String,
    val sourceMessageId: String,
    val accountId: Long? = null,
    val cardIdentityKey: String,
    val accountHint: String? = null,
    val institutionName: String,
    val totalDueMinor: Long,
    val minimumDueMinor: Long? = null,
    val dueDateEpochDay: Long,
    val detectedAt: Long,
    val sender: String,
    val status: CreditCardBillStatus = CreditCardBillStatus.DUE,
    val paidAt: Long? = null,
    /** When the issuer first reported the currently stored due amount for this cycle. */
    val dueAmountObservedAt: Long? = null,
    val matchedPayments: List<CreditCardPayment> = emptyList(),
) {
    val amountDueAsOf: Long get() = dueAmountObservedAt ?: detectedAt

    // A refreshed issuer balance already accounts for payments that preceded it.
    val remainingDueMinor: Long
        get() = if (status == CreditCardBillStatus.PAID) {
            0L
        } else {
            matchedPayments.asSequence()
                .filter { it.receivedAt > amountDueAsOf }
                .fold(totalDueMinor) { due, payment -> (due - payment.amountMinor).coerceAtLeast(0L) }
        }

    val hasOutstandingBalance: Boolean get() = remainingDueMinor > 0
    val isPaid: Boolean get() = status == CreditCardBillStatus.PAID ||
        (totalDueMinor > 0 && !hasOutstandingBalance)
}

/** Latest statement cycle per card. Older cycles remain available through [creditCardBillHistory]. */
fun currentCreditCardBills(bills: List<CreditCardBill>): List<CreditCardBill> = bills
    .filterNot(CreditCardBill::needsAccountAssignment)
    .groupBy(CreditCardBill::creditCardBillGroupKey)
    .values
    .mapNotNull { cycles ->
        cycles.maxWithOrNull(
            compareBy<CreditCardBill> { it.dueDateEpochDay }
                .thenBy { it.detectedAt }
                .thenBy { it.id },
        )
    }
    .sortedWith(compareBy<CreditCardBill> { it.dueDateEpochDay }.thenBy { it.institutionName })

fun totalCurrentCreditCardDueMinor(bills: List<CreditCardBill>): Long = currentCreditCardBills(bills)
    .sumOf(CreditCardBill::remainingDueMinor)

/**
 * Alerts without last-four digits cannot be safely attributed when several cards are possible.
 * They stay visible for explicit assignment but are excluded from current totals until resolved.
 */
val CreditCardBill.needsAccountAssignment: Boolean
    get() = accountId == null && accountHint.isNullOrBlank() && ":unidentified:" in cardIdentityKey

fun unassignedCreditCardBills(bills: List<CreditCardBill>): List<CreditCardBill> = bills
    .filter { it.hasOutstandingBalance && it.needsAccountAssignment }
    .sortedWith(compareBy<CreditCardBill> { it.dueDateEpochDay }.thenByDescending { it.detectedAt })

fun creditCardBillHistory(
    bills: List<CreditCardBill>,
    cardGroupKey: String,
): List<CreditCardBill> = bills
    .filter { it.creditCardBillGroupKey == cardGroupKey }
    .sortedWith(compareByDescending<CreditCardBill> { it.dueDateEpochDay }.thenByDescending { it.detectedAt })

/**
 * Groups pre-account and post-account statement cycles consistently. A known bank + last four is
 * stable before an AccountProfile exists; explicitly assigned no-suffix alerts use the account ID.
 */
val CreditCardBill.creditCardBillGroupKey: String
    get() = when {
        !accountHint.isNullOrBlank() -> {
            val institution = institutionName.trim().lowercase(Locale.ROOT)
                .replace(Regex("[^a-z0-9]+"), "-")
                .trim('-')
            "card-institution:$institution:${accountHint.trim()}"
        }
        accountId != null -> "card-account:$accountId"
        else -> cardIdentityKey
    }
