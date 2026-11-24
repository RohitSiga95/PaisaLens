package com.paisalens.app.data.model

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters

data class DashboardWeek(val start: LocalDate) {
    val endInclusive: LocalDate get() = start.plusDays(6)

    operator fun contains(date: LocalDate): Boolean = date in start..endInclusive

    companion object {
        fun containing(date: LocalDate): DashboardWeek =
            DashboardWeek(date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)))
    }
}

enum class BillTimelineSource(val label: String) {
    MANUAL("Bill"),
    UTILITY_SMS("Utility SMS"),
    CREDIT_CARD("Credit card"),
    RECURRING("Recurring payment"),
    COMMITMENT("AutoPay"),
    LOAN("Loan EMI"),
}

data class BillTimelineEntry(
    val key: String,
    val title: String,
    val amountMinor: Long,
    val date: LocalDate,
    val source: BillTimelineSource,
    val status: DueStatus? = null,
    val accountName: String? = null,
    val notes: String? = null,
    val manualBillId: Long? = null,
    val utilityBillId: Long? = null,
    val utilityKind: UtilityBillKind? = null,
    val utilityAccountLastFour: String? = null,
    val creditCardBillId: Long? = null,
    val reminderTargetKey: String? = null,
    val smsCount: Int = 0,
    val historyDateLabel: String? = null,
)

data class BillsTimelineSnapshot(
    val week: DashboardWeek,
    val upcoming: List<BillTimelineEntry>,
    val paid: List<BillTimelineEntry>,
    val pastSmsNotices: List<BillTimelineEntry>,
) {
    val dueThisWeek: List<BillTimelineEntry> = upcoming.filter { it.date in week }
    val dueThisWeekMinor: Long = dueThisWeek.sumOf(BillTimelineEntry::amountMinor)
}

/** A view of the existing bill sources; no extra copies of detected bills are persisted. */
fun buildBillsTimeline(
    bills: List<BillReminder>,
    utilitySmsBills: List<UtilitySmsBill>,
    creditCardBills: List<CreditCardBill>,
    recurringPayments: List<RecurringPayment>,
    paymentCommitments: List<PaymentCommitment>,
    loans: List<LoanAccount>,
    accounts: List<AccountProfile>,
    today: LocalDate,
    zoneId: ZoneId,
): BillsTimelineSnapshot {
    val names = accounts.associate { it.id to it.name }
    val idsByName = accounts.associate { normalizedMerchantKey(it.name) to it.id }
    val commitmentKeys = paymentCommitments.mapTo(hashSetOf(), ::paymentCommitmentIdentityKey)
    val recurring = recurringPayments.filterNot { recurringPaymentIdentityKey(it, idsByName) in commitmentKeys }
    val trackedUtilities = utilitySmsBills
        .filter { it.trackedBillId != null }
        .associateBy(UtilitySmsBill::trackedBillId)
    val dues = buildDueItems(bills, recurring, loans, today, zoneId, horizonDays = 3_650) +
        buildPaymentCommitmentDueItems(
            commitments = paymentCommitments,
            today = today,
            horizonDays = 3_650,
            accountNamesById = names,
        )
    val tracked = dues.map { item ->
        val manualId = item.stableId.substringAfter(':').substringBefore(':').toLongOrNull()
            ?.takeIf { it > 0 && item.source == DueItemSource.MANUAL_BILL }
        val utility = trackedUtilities[manualId]
        BillTimelineEntry(
            key = item.stableId,
            title = utility?.providerName?.takeIf { item.title == utility.title } ?: item.title,
            amountMinor = item.amountMinor,
            date = item.dueDate,
            source = when (item.source) {
                DueItemSource.MANUAL_BILL ->
                    if (utility != null) BillTimelineSource.UTILITY_SMS else BillTimelineSource.MANUAL
                DueItemSource.RECURRING_PAYMENT -> BillTimelineSource.RECURRING
                DueItemSource.PAYMENT_COMMITMENT -> BillTimelineSource.COMMITMENT
                DueItemSource.LOAN_EMI -> BillTimelineSource.LOAN
            },
            status = item.status,
            accountName = item.accountId?.let(names::get) ?: item.accountName,
            notes = item.notes,
            manualBillId = manualId,
            utilityKind = utility?.kind,
            utilityAccountLastFour = utility?.accountLastFour,
            smsCount = utility?.noticeKeys?.size ?: 0,
            reminderTargetKey = manualId?.let(::manualBillReminderTargetKey),
        )
    }
    val cardDues = currentCreditCardBills(creditCardBills)
        .filter(CreditCardBill::hasOutstandingBalance)
        .map { bill ->
            val due = LocalDate.ofEpochDay(bill.dueDateEpochDay)
            BillTimelineEntry(
                key = "card:${bill.id}",
                title = bill.timelineTitle(names),
                amountMinor = bill.remainingDueMinor,
                date = due,
                source = BillTimelineSource.CREDIT_CARD,
                status = classifyDueDate(due, today),
                creditCardBillId = bill.id,
                reminderTargetKey = creditCardReminderTargetKey(bill.creditCardBillGroupKey),
            )
        }
    val (newNotices, pastNotices) = utilitySmsBills.asSequence()
        .filter { it.trackedBillId == null && !it.dismissed && it.amountMinor > 0 }
        .map { bill ->
            val due = LocalDate.ofEpochDay(bill.dueDateEpochDay)
            BillTimelineEntry(
                key = "utility:${bill.id}",
                title = bill.providerName,
                amountMinor = bill.amountMinor,
                date = due,
                source = BillTimelineSource.UTILITY_SMS,
                status = classifyDueDate(due, today),
                utilityBillId = bill.id,
                utilityKind = bill.kind,
                utilityAccountLastFour = bill.accountLastFour,
                smsCount = bill.noticeKeys.size,
            )
        }
        .partition { it.date >= today }
    val paidManual = bills.mapNotNull { bill ->
        val paid = bill.lastPaidEpochDay?.let(LocalDate::ofEpochDay) ?: return@mapNotNull null
        val utility = trackedUtilities[bill.id]
        BillTimelineEntry(
            key = "paid-bill:${bill.id}",
            title = utility?.providerName?.takeIf { bill.title == utility.title } ?: bill.title,
            amountMinor = bill.amountMinor,
            date = paid,
            source = if (utility != null) BillTimelineSource.UTILITY_SMS else BillTimelineSource.MANUAL,
            utilityKind = utility?.kind,
            utilityAccountLastFour = utility?.accountLastFour,
            historyDateLabel = if (bill.recurrenceMonths > 0) "Last settled cycle" else "Paid on",
        )
    }
    val paidCards = creditCardBills.filter(CreditCardBill::isPaid).map { bill ->
        val paidDate = (bill.paidAt ?: bill.matchedPayments.maxOfOrNull { it.receivedAt })?.let {
            Instant.ofEpochMilli(it).atZone(zoneId).toLocalDate()
        }
        BillTimelineEntry(
            key = "paid-card:${bill.id}",
            title = bill.timelineTitle(names),
            amountMinor = bill.totalDueMinor,
            date = paidDate ?: LocalDate.ofEpochDay(bill.dueDateEpochDay),
            source = BillTimelineSource.CREDIT_CARD,
            creditCardBillId = bill.id,
            historyDateLabel = if (paidDate != null) "Paid on" else "Was due on",
        )
    }
    return BillsTimelineSnapshot(
        week = DashboardWeek.containing(today),
        upcoming = (tracked + cardDues + newNotices)
            .sortedWith(compareBy<BillTimelineEntry> { it.date }.thenBy { it.title }.thenBy { it.key }),
        paid = (paidManual + paidCards)
            .sortedWith(compareByDescending<BillTimelineEntry> { it.date }.thenBy { it.title }),
        pastSmsNotices = pastNotices.sortedByDescending(BillTimelineEntry::date),
    )
}

private fun CreditCardBill.timelineTitle(accountNames: Map<Long, String>): String =
    accountId?.let(accountNames::get)?.takeIf(String::isNotBlank)
        ?: listOfNotNull(institutionName, accountHint?.takeIf(String::isNotBlank)?.let { "••$it" })
            .joinToString(" ")
