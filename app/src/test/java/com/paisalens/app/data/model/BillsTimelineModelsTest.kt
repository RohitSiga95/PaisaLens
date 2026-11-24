package com.paisalens.app.data.model

import java.time.LocalDate
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BillsTimelineModelsTest {
    private val monday = LocalDate.of(2026, 11, 23)

    @Test
    fun `calendar week spans month boundaries and includes Sunday`() {
        val sunday = LocalDate.of(2026, 3, 1)
        val week = DashboardWeek.containing(sunday)
        assertEquals(LocalDate.of(2026, 2, 23), week.start)
        assertTrue(sunday in week)
        assertFalse(sunday.plusDays(1) in week)
    }

    @Test
    fun `weekly total counts a tracked SMS bill once and uses the latest statement balance`() {
        val utility = notice(1, 99_900, monday.plusDays(2)).copy(trackedBillId = 14L)
        val electricity = notice(2, 125_000, monday.plusDays(3)).copy(kind = UtilityBillKind.ELECTRICITY)
        val oldCard = card(31, 500_000, monday.minusWeeks(4))
        val latestCard = card(32, 353_100, monday.plusDays(4)).copy(
            matchedPayments = listOf(
                CreditCardPayment("payment", "HDFC Bank", "4821", 70_000, receivedAt = 20L),
            ),
        )
        val result = timeline(
            bills = listOf(BillReminder(id = 14, title = "Airtel Fiber", amountMinor = 99_900, dueDateEpochDay = utility.dueDateEpochDay)),
            notices = listOf(utility, electricity, notice(3, 700, monday.minusDays(5)), notice(4, 100, monday).copy(dismissed = true)),
            cards = listOf(oldCard, latestCard),
        )
        assertEquals(listOf("bill:14:${utility.dueDateEpochDay}", "utility:2", "card:32"), result.upcoming.map(BillTimelineEntry::key))
        assertEquals(508_000L, result.dueThisWeekMinor)
        assertEquals(2, result.upcoming.first().smsCount)
        assertEquals(manualBillReminderTargetKey(14), result.upcoming.first().reminderTargetKey)
        assertEquals(listOf("utility:3"), result.pastSmsNotices.map(BillTimelineEntry::key))
    }

    @Test
    fun `paid entries drop out of due totals and show in history`() {
        val manual = BillReminder(
            id = 1, title = "Internet", amountMinor = 79_900, dueDateEpochDay = monday.plusDays(1).toEpochDay(),
            isActive = false, lastPaidEpochDay = monday.toEpochDay(),
        )
        val paidCard = card(2, 120_000, monday.plusDays(4)).copy(status = CreditCardBillStatus.PAID)
        val openCard = card(3, 250_000, monday.plusWeeks(1))
        val result = timeline(bills = listOf(manual), cards = listOf(paidCard, openCard))
        assertEquals(listOf("card:3"), result.upcoming.map(BillTimelineEntry::key))
        assertEquals(0L, result.dueThisWeekMinor)
        assertEquals(setOf("paid-bill:1", "paid-card:2"), result.paid.mapTo(hashSetOf(), BillTimelineEntry::key))
        assertTrue(result.paid.all { it.reminderTargetKey == null })
    }

    private fun timeline(
        bills: List<BillReminder> = emptyList(),
        notices: List<UtilitySmsBill> = emptyList(),
        cards: List<CreditCardBill> = emptyList(),
    ) = buildBillsTimeline(
        bills, notices, cards, emptyList(), emptyList(), emptyList(), emptyList(), monday, ZoneOffset.UTC,
    )

    private fun notice(id: Long, amount: Long, date: LocalDate) = UtilitySmsBill(
        id = id, kind = UtilityBillKind.BROADBAND, providerKey = "airtel", providerName = "Airtel",
        amountMinor = amount, dueDateEpochDay = date.toEpochDay(), detectedAt = id, noticeKeys = setOf("a", "b"),
    )

    private fun card(id: Long, amount: Long, date: LocalDate) = CreditCardBill(
        id = id, billKey = "card:$id", sourceMessageId = "sms:$id", cardIdentityKey = "card:hdfc:4821",
        accountHint = "4821", institutionName = "HDFC Bank", totalDueMinor = amount, dueDateEpochDay = date.toEpochDay(),
        detectedAt = 10L, sender = "VM-HDFCCRD",
    )
}
