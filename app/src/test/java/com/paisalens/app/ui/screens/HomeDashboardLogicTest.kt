package com.paisalens.app.ui.screens

import com.paisalens.app.data.model.AccountProfile
import com.paisalens.app.data.model.AccountType
import com.paisalens.app.data.model.ExpenseCategory
import com.paisalens.app.data.model.TransactionRecord
import com.paisalens.app.data.model.TransactionSource
import com.paisalens.app.data.model.TransactionType
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HomeDashboardLogicTest {
    private val utc = ZoneId.of("UTC")

    @Test
    fun filtersEveryHomeSpendMetricToTheSelectedMonth() {
        val july = transaction(id = 1, amountMinor = 1_250L, date = LocalDate.of(2026, 7, 31))
        val august = transaction(id = 2, amountMinor = 2_500L, date = LocalDate.of(2026, 8, 1))

        val result = transactionsForMonth(
            transactions = listOf(july, august),
            month = YearMonth.of(2026, 7),
            zoneId = utc,
        )

        assertEquals(listOf(july), result)
    }

    @Test
    fun combinesBankProfilesWithTheSameLastFourDigits() {
        val groups = consolidateAvailabilityAccounts(
            accounts = listOf(
                bank(id = 1, name = "HDFC Bank", hint = "XX0801", balanceMinor = 10_000L, fetchedAt = 100L),
                bank(id = 2, name = "HDFC SMS", hint = "0801", balanceMinor = 25_000L, fetchedAt = 200L),
                bank(id = 3, name = "HDFC old profile", hint = "0801", balanceMinor = null, fetchedAt = 300L),
                bank(id = 4, name = "IDFC Bank", hint = "8004", balanceMinor = null, fetchedAt = null),
            ),
            type = AccountType.BANK_ACCOUNT,
        )

        assertEquals(2, groups.size)
        val hdfc = groups.single { it.account.accountHint == "0801" }
        assertEquals(3, hdfc.profileCount)
        assertEquals(setOf(1L, 2L, 3L), hdfc.accountIds)
        assertEquals(25_000L, hdfc.account.balanceMinor)
        assertEquals(200L, hdfc.account.availabilityFetchedAt)
        val idfc = groups.single { it.account.accountHint == "8004" }
        assertNull(idfc.account.balanceMinor)
    }

    @Test
    fun keepsBankAndCardProfilesInSeparateSections() {
        val accounts = listOf(
            bank(id = 1, name = "SBI account", hint = "1234", balanceMinor = 80_000L, fetchedAt = 1L),
            AccountProfile(
                id = 2,
                name = "SBI card",
                type = AccountType.CREDIT_CARD,
                accountHint = "1234",
                institution = "SBI",
                availableCreditMinor = 40_000L,
                availabilityFetchedAt = 2L,
            ),
        )

        assertEquals(1, consolidateAvailabilityAccounts(accounts, AccountType.BANK_ACCOUNT).size)
        assertEquals(1, consolidateAvailabilityAccounts(accounts, AccountType.CREDIT_CARD).size)
    }

    @Test
    fun keepsSameLastFourAccountsSeparateAcrossInstitutionsAndIncompleteIdentities() {
        val accounts = listOf(
            bank(id = 1, name = "HDFC Bank", hint = "1234", balanceMinor = 80_000L, fetchedAt = 1L),
            bank(id = 2, name = "IDFC Bank", hint = "1234", balanceMinor = 70_000L, fetchedAt = 2L),
            AccountProfile(
                id = 3,
                name = "Unknown A",
                type = AccountType.BANK_ACCOUNT,
                accountHint = "1234",
            ),
            AccountProfile(
                id = 4,
                name = "Unknown B",
                type = AccountType.BANK_ACCOUNT,
                accountHint = "1234",
            ),
        )

        val groups = consolidateAvailabilityAccounts(accounts, AccountType.BANK_ACCOUNT)

        assertEquals(4, groups.size)
        assertEquals(setOf(1L, 2L, 3L, 4L), groups.flatMapTo(mutableSetOf()) { it.accountIds })
    }

    @Test
    fun homeClockRefreshCrossesMonthRolloverWithoutReopeningScreen() {
        val beforeMidnight = ZonedDateTime.of(2026, 8, 31, 23, 59, 59, 900_000_000, utc)
        val delayMillis = nextHomeClockRefreshDelayMillis(beforeMidnight)
        val refreshedAt = beforeMidnight.plus(delayMillis, ChronoUnit.MILLIS)

        assertEquals(250L, delayMillis)
        assertEquals(YearMonth.of(2026, 9), YearMonth.from(refreshedAt))
    }

    private fun bank(
        id: Long,
        name: String,
        hint: String,
        balanceMinor: Long?,
        fetchedAt: Long?,
    ) = AccountProfile(
        id = id,
        name = name,
        type = AccountType.BANK_ACCOUNT,
        accountHint = hint,
        institution = name.substringBefore(' '),
        balanceMinor = balanceMinor,
        availabilityFetchedAt = fetchedAt,
    )

    private fun transaction(
        id: Long,
        amountMinor: Long,
        date: LocalDate,
    ) = TransactionRecord(
        id = id,
        sourceMessageId = "test-$id",
        amountMinor = amountMinor,
        merchant = "Merchant $id",
        accountHint = "1234",
        category = ExpenseCategory.FOOD,
        type = TransactionType.EXPENSE,
        occurredAt = date.atStartOfDay(utc).toInstant().toEpochMilli(),
        source = TransactionSource.BANK,
        sender = "TESTBK",
    )
}
