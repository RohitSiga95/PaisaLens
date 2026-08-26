package com.paisalens.app.notification

import com.paisalens.app.data.model.AttentionItem
import com.paisalens.app.data.model.CreditCardBill
import com.paisalens.app.data.model.PaymentReminderRule
import com.paisalens.app.data.model.PaymentReminderSettings
import com.paisalens.app.data.model.PaymentReminderTargetType
import com.paisalens.app.data.model.creditCardBillGroupKey
import com.paisalens.app.data.model.creditCardReminderTargetKey
import com.paisalens.app.data.model.manualBillReminderTargetKey

/**
 * Keeps Home/weekly attention intact while avoiding a second generic notification for a bill that
 * already has an enabled, customized payment reminder.
 */
internal fun suppressGenericBillAlertsWithCustomRules(
    items: List<AttentionItem>,
    rules: List<PaymentReminderRule>,
    creditCardBills: List<CreditCardBill>,
    settings: PaymentReminderSettings = PaymentReminderSettings(),
): List<AttentionItem> {
    if (!settings.enabled) return items
    val enabledTargets = rules.asSequence().filter(PaymentReminderRule::enabled)
        .map(PaymentReminderRule::targetKey).toSet()
    if (enabledTargets.isEmpty()) return items
    val cardTargetByCycleId = creditCardBills.associate { bill ->
        bill.id to creditCardReminderTargetKey(bill.creditCardBillGroupKey)
    }
    return items.filterNot { item ->
        when {
            item.stableId.startsWith("bill:") -> item.stableId
                .substringAfter("bill:")
                .substringBefore(':')
                .toLongOrNull()
                ?.takeIf { it > 0L }
                ?.let(::manualBillReminderTargetKey) in enabledTargets
            item.stableId.startsWith("card-bill:") -> item.stableId
                .substringAfter("card-bill:")
                .substringBefore(':')
                .toLongOrNull()
                ?.let(cardTargetByCycleId::get) in enabledTargets
            else -> false
        }
    }
}
