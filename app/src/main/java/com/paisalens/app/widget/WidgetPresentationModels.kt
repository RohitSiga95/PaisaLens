package com.paisalens.app.widget

import com.paisalens.app.data.model.AccountProfile
import com.paisalens.app.data.model.BillReminder
import com.paisalens.app.data.model.CreditCardBill
import com.paisalens.app.data.model.CreditCardBillStatus
import com.paisalens.app.data.model.DueItem
import com.paisalens.app.data.model.DueStatus
import com.paisalens.app.data.model.ExpenseSplit
import com.paisalens.app.data.model.LoanAccount
import com.paisalens.app.data.model.PaymentCommitment
import com.paisalens.app.data.model.ReviewStatus
import com.paisalens.app.data.model.TransactionLink
import com.paisalens.app.data.model.TransactionRecord
import com.paisalens.app.data.model.TransactionType
import com.paisalens.app.data.model.buildDueItems
import com.paisalens.app.data.model.buildEffectiveExpenseTransactions
import com.paisalens.app.data.model.buildPaymentCommitmentDueItems
import com.paisalens.app.data.model.buildSpendingCategoryTotals
import com.paisalens.app.data.model.currentCreditCardBills
import com.paisalens.app.data.model.detectRecurringPayments
import com.paisalens.app.data.model.normalizedMerchantKey
import com.paisalens.app.data.model.paymentCommitmentIdentityKey
import com.paisalens.app.data.model.recurringPaymentIdentityKey
import com.paisalens.app.data.model.transactionIdsAppliedAsExpenseOffsets
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId

internal enum class WidgetPrivacyState {
    LOCKED,
    AMOUNTS_HIDDEN,
    VISIBLE,
    ;

    val showDetails: Boolean get() = this != LOCKED
    val showAmounts: Boolean get() = this == VISIBLE
}

internal fun widgetPrivacyState(appLockEnabled: Boolean, amountsVisible: Boolean): WidgetPrivacyState = when {
    appLockEnabled -> WidgetPrivacyState.LOCKED
    amountsVisible -> WidgetPrivacyState.VISIBLE
    else -> WidgetPrivacyState.AMOUNTS_HIDDEN
}

internal data class SpendingWidgetRow(
    val stableId: String,
    val label: String,
    val amountMinor: Long,
    val progressBasisPoints: Int,
)

internal data class MonthlySpendingWidgetPresentation(
    val month: YearMonth,
    val spendMinor: Long,
    val transactionCount: Int,
    val reviewCount: Int,
    val categoryRows: List<SpendingWidgetRow>,
)

internal fun buildMonthlySpendingWidgetPresentation(
    transactions: List<TransactionRecord>,
    links: List<TransactionLink>,
    splits: List<ExpenseSplit>,
    month: YearMonth,
    zoneId: ZoneId,
): MonthlySpendingWidgetPresentation {
    val monthExpenses = buildEffectiveExpenseTransactions(transactions, links, splits).filter { transaction ->
        YearMonth.from(Instant.ofEpochMilli(transaction.occurredAt).atZone(zoneId)) == month
    }
    val appliedOffsets = transactionIdsAppliedAsExpenseOffsets(transactions, links)
    val unlinkedRefundMinor = transactions.asSequence()
        .filter { transaction ->
            transaction.type == TransactionType.REFUND &&
                transaction.reviewStatus == ReviewStatus.CONFIRMED &&
                transaction.id !in appliedOffsets &&
                YearMonth.from(Instant.ofEpochMilli(transaction.occurredAt).atZone(zoneId)) == month
        }
        .sumOf(TransactionRecord::amountMinor)
    val categoryTotals = buildSpendingCategoryTotals(monthExpenses)
    val largestCategoryMinor = categoryTotals.maxOfOrNull { it.amountMinor }?.coerceAtLeast(1L) ?: 1L
    return MonthlySpendingWidgetPresentation(
        month = month,
        spendMinor = (monthExpenses.sumOf(TransactionRecord::amountMinor) - unlinkedRefundMinor).coerceAtLeast(0L),
        transactionCount = monthExpenses.size,
        reviewCount = transactions.count { transaction ->
            transaction.reviewStatus == ReviewStatus.NEEDS_REVIEW &&
                YearMonth.from(Instant.ofEpochMilli(transaction.occurredAt).atZone(zoneId)) == month
        },
        categoryRows = categoryTotals.map { total ->
            SpendingWidgetRow(
                stableId = total.key.stableId,
                label = total.key.label,
                amountMinor = total.amountMinor,
                progressBasisPoints = ((total.amountMinor * 10_000L) / largestCategoryMinor)
                    .coerceIn(0L, 10_000L)
                    .toInt(),
            )
        },
    )
}

internal data class DueBillWidgetRow(
    val stableId: String,
    val title: String,
    val amountMinor: Long,
    val dueDate: LocalDate,
    val status: DueStatus,
)

internal data class DueBillsWidgetPresentation(
    val rows: List<DueBillWidgetRow>,
    val totalDueMinor: Long,
    val overdueCount: Int,
)

internal fun buildDueBillsWidgetPresentation(
    bills: List<BillReminder>,
    transactions: List<TransactionRecord>,
    loans: List<LoanAccount>,
    commitments: List<PaymentCommitment>,
    accounts: List<AccountProfile>,
    today: LocalDate,
    zoneId: ZoneId,
): DueBillsWidgetPresentation {
    val recurringPayments = detectRecurringPayments(transactions)
    val accountIdsByName = accounts.associate { normalizedMerchantKey(it.name) to it.id }
    val reviewedSubscriptionKeys = commitments.mapTo(mutableSetOf(), ::paymentCommitmentIdentityKey)
    val deduplicatedRecurring = recurringPayments.filterNot { recurring ->
        recurringPaymentIdentityKey(recurring, accountIdsByName) in reviewedSubscriptionKeys
    }
    val dueItems = (
        buildDueItems(
            manualBills = bills,
            recurringPayments = deduplicatedRecurring,
            loans = loans,
            today = today,
            zoneId = zoneId,
            horizonDays = 31,
        ) + buildPaymentCommitmentDueItems(
            commitments = commitments,
            today = today,
            horizonDays = 31,
            accountNamesById = accounts.associate { it.id to it.name },
        )
        )
        .distinctBy(DueItem::stableId)
        .sortedWith(compareBy<DueItem> { it.dueDate }.thenBy { it.title.lowercase() })

    return DueBillsWidgetPresentation(
        rows = dueItems.map { item ->
            DueBillWidgetRow(
                stableId = item.stableId,
                title = item.title,
                amountMinor = item.amountMinor,
                dueDate = item.dueDate,
                status = item.status,
            )
        },
        totalDueMinor = dueItems.sumOf(DueItem::amountMinor),
        overdueCount = dueItems.count { it.status == DueStatus.OVERDUE },
    )
}

internal data class CreditCardBillWidgetRow(
    val id: Long,
    val cardName: String,
    val amountMinor: Long,
    val dueDate: LocalDate,
    val overdue: Boolean,
)

internal data class CreditCardBillsWidgetPresentation(
    val rows: List<CreditCardBillWidgetRow>,
    val totalDueMinor: Long,
)

internal fun buildCreditCardBillsWidgetPresentation(
    bills: List<CreditCardBill>,
    accounts: List<AccountProfile>,
    today: LocalDate,
): CreditCardBillsWidgetPresentation {
    val accountNamesById = accounts.associate { it.id to it.name }
    val dueBills = currentCreditCardBills(bills)
        .filter { it.status == CreditCardBillStatus.DUE }
        .sortedWith(compareBy<CreditCardBill> { it.dueDateEpochDay }.thenBy { it.institutionName })
    return CreditCardBillsWidgetPresentation(
        rows = dueBills.map { bill ->
            val accountName = bill.accountId?.let(accountNamesById::get)
            val fallbackName = buildString {
                append(bill.institutionName.ifBlank { "Credit card" })
                bill.accountHint?.filter(Char::isDigit)?.takeLast(4)?.takeIf(String::isNotBlank)?.let { hint ->
                    append(" ••")
                    append(hint)
                }
            }
            val dueDate = LocalDate.ofEpochDay(bill.dueDateEpochDay)
            CreditCardBillWidgetRow(
                id = bill.id,
                cardName = accountName?.takeIf(String::isNotBlank) ?: fallbackName,
                amountMinor = bill.totalDueMinor,
                dueDate = dueDate,
                overdue = dueDate.isBefore(today),
            )
        },
        totalDueMinor = dueBills.sumOf(CreditCardBill::totalDueMinor),
    )
}
