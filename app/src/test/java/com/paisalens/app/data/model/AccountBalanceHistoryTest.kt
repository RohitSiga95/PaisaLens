package com.paisalens.app.data.model

import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AccountBalanceHistoryTest {
    private val zone = ZoneId.of("America/Los_Angeles")
    private val today = LocalDate.of(2026, 11, 3)
    private val single = listOf(AccountBalanceSourceGroup(setOf(1L), "Everyday"))

    @Test
    fun `calendar dates keep the final reading of the day and mark days without an update`() {
        val ending = dailyAccountBalances(
            listOf(
                snapshot(1, 1, today.minusDays(2), 10_000, hour = 1),
                snapshot(2, 1, today.minusDays(2), 12_500, hour = 23),
                snapshot(3, 1, today, -1_250),
                snapshot(4, 1, today.plusDays(1), 80_000),
            ),
            AccountType.BANK_ACCOUNT, single, today, 4, zone,
        )
        assertEquals(listOf(today.minusDays(3), today.minusDays(2), today.minusDays(1), today), ending.map { it.date })
        assertEquals(listOf(null, 12_500L, 12_500L, -1_250L), ending.map { it.amountMinor })
        assertEquals(listOf(false, true, false, true), ending.map { it.allReadingsFromThisDay })
        assertEquals(ending[1].oldestReadingAt, ending[2].oldestReadingAt)
    }

    @Test
    fun `a saved reading before the requested window anchors a carried balance`() {
        val recorded = snapshot(1, 1, today.minusDays(35), 8_000)
        val dates = dailyAccountBalances(listOf(recorded), AccountType.BANK_ACCOUNT, single, today, 30, zone)
        assertEquals(30, dates.size)
        assertEquals(today.minusDays(29), dates.first().date)
        assertTrue(dates.all { it.amountMinor == 8_000L && !it.allReadingsFromThisDay })
        assertEquals(recorded.recordedAt, dates.last().oldestReadingAt)
    }

    @Test
    fun `merged sources are summed only after each has a balance`() {
        val readings = listOf(
            snapshot(1, 1, today.minusDays(2), 10_000),
            snapshot(2, 1, today.minusDays(1), 35_000).copy(physicalAccountId = 2L),
            snapshot(3, 1, today, 11_000),
        )
        val sources = single + AccountBalanceSourceGroup(setOf(2L), "Savings")
        val dates = dailyAccountBalances(readings, AccountType.BANK_ACCOUNT, sources, today, 3, zone)
        assertNull(dates.first().amountMinor)
        assertEquals(listOf(null, 45_000L, 46_000L), dates.map { it.amountMinor })
        assertFalse(dates.last().allReadingsFromThisDay)
        val savings = dailyAccountBalances(readings, AccountType.BANK_ACCOUNT, sources.drop(1), today, 3, zone)
        assertEquals(listOf(null, 35_000L, 35_000L), savings.map { it.amountMinor })
    }

    @Test
    fun `credit charts use available credit and bank SMS aliases never double count`() {
        val credit = snapshot(1, 1, today, 100).copy(availableCreditMinor = 200L)
        assertEquals(200L, dailyAccountBalances(listOf(credit), AccountType.CREDIT_CARD, single, today, 7, zone).last().amountMinor)
        val sms = snapshot(2, 1, today, 12_000).copy(sender = "HDFCBK")
        val manualAlias = snapshot(3, 1, today, 24_000, hour = 15)
            .copy(physicalAccountId = 2L, sender = USER_ENTERED_UPI_BALANCE_SOURCE)
        val aliases = listOf(AccountBalanceSourceGroup(setOf(1L, 2L), "Everyday", preferSmsBalance = true))
        assertEquals(12_000L, dailyAccountBalances(listOf(sms, manualAlias), AccountType.BANK_ACCOUNT, aliases, today, 7, zone)
            .last().amountMinor)
    }

    private fun snapshot(id: Long, accountId: Long, day: LocalDate, amount: Long, hour: Int = 12) =
        AccountBalanceSnapshot(
            id = id,
            accountId = accountId,
            balanceMinor = amount,
            availableCreditMinor = null,
            creditLimitMinor = null,
            recordedAt = day.atTime(hour, 0).atZone(zone).toInstant().toEpochMilli(),
        )
}
