package com.paisalens.app.data.model

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** An issuer payment acknowledgement. The stored keys are hashes, and the SMS text stays in the inbox. */
data class CreditCardPayment(
    val key: String,
    val institutionName: String,
    val cardLastFour: String,
    val amountMinor: Long,
    val receivedAt: Long,
    val unreferencedMessageHash: String? = null,
    val billId: Long? = null,
    val dismissed: Boolean = false,
)

data class CreditCardPaymentSuggestion(
    val payment: CreditCardPayment,
    val bill: CreditCardBill,
) {
    val remainingAfterMatch: Long
        get() = (bill.remainingDueMinor - payment.amountMinor).coerceAtLeast(0)
}

/**
 * Use the physical card and most recent cycle. A payment sent before the current amount was
 * reported may already be included in that balance, or belong to an older statement.
 */
fun suggestCreditCardPayments(
    payments: List<CreditCardPayment>,
    bills: List<CreditCardBill>,
    zoneId: ZoneId = ZoneId.systemDefault(),
): List<CreditCardPaymentSuggestion> {
    val currentBills = currentCreditCardBills(bills).filter(CreditCardBill::hasOutstandingBalance)
    return payments.asSequence()
        .filter { it.billId == null && !it.dismissed }
        .mapNotNull { payment ->
            val paymentDay = Instant.ofEpochMilli(payment.receivedAt).atZone(zoneId).toLocalDate()
            val bill = currentBills.singleOrNull { bill ->
                val dueDay = LocalDate.ofEpochDay(bill.dueDateEpochDay)
                bill.accountHint == payment.cardLastFour &&
                    bill.institutionName.equals(payment.institutionName, ignoreCase = true) &&
                    payment.receivedAt > bill.amountDueAsOf &&
                    paymentDay in dueDay.minusDays(45)..dueDay.plusDays(30)
            } ?: return@mapNotNull null
            CreditCardPaymentSuggestion(payment, bill)
        }
        .sortedByDescending { it.payment.receivedAt }
        .toList()
}
