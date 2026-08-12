package com.paisalens.app.sms

import com.paisalens.app.data.model.AccountProfile
import com.paisalens.app.data.model.AccountType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AccountAvailabilitySmsParserTest {
    private val parser = AccountAvailabilitySmsParser()

    @Test
    fun extractsBankBalanceFromTransactionAlert() {
        val update = parser.parse(
            sender = "VK-HDFCBK",
            body = "Rs. 450.00 debited from A/c XX1234 at SWIGGY. Avl bal Rs 8,200.50.",
            timestamp = 1_700_000_000_000,
        )

        requireNotNull(update)
        assertEquals(AccountType.BANK_ACCOUNT, update.accountType)
        assertEquals("1234", update.accountHint)
        assertEquals(820_050L, update.balanceMinor)
        assertNull(update.availableCreditMinor)
    }

    @Test
    fun extractsAvailableCreditFromCardAlert() {
        val update = parser.parse(
            sender = "JD-SBICRD",
            body = "Card XX9876 purchase approved. Available credit limit is INR 42,350.00.",
            timestamp = 1_700_000_000_100,
        )

        requireNotNull(update)
        assertEquals(AccountType.CREDIT_CARD, update.accountType)
        assertEquals("9876", update.accountHint)
        assertEquals(4_235_000L, update.availableCreditMinor)
        assertNull(update.creditLimitMinor)
        assertNull(update.balanceMinor)
    }

    @Test
    fun extractsAvailableAndTotalCreditLimitsFromCardAlert() {
        val update = parser.parse(
            sender = "JD-SBICRD",
            body = "Card XX9876: available credit INR 42,350. Your total credit limit is INR 1,00,000.",
            timestamp = 1_700_000_000_200,
        )

        requireNotNull(update)
        assertEquals(AccountType.CREDIT_CARD, update.accountType)
        assertEquals("9876", update.accountHint)
        assertEquals(4_235_000L, update.availableCreditMinor)
        assertEquals(10_000_000L, update.creditLimitMinor)
        assertNull(update.balanceMinor)
    }

    @Test
    fun extractsHdfcDailyAvailableBalanceWithAccountAndTimestampInBetween() {
        val update = parser.parse(
            sender = "VM-HDFCBK-S",
            body = "Available Balance in A/C No. XX0801 as on 05-Aug-2026 07:30 is INR 1,23,456.78. HDFC Bank",
            timestamp = 1_775_555_800_000,
        )

        requireNotNull(update)
        assertEquals("hdfc", update.bankKey)
        assertEquals("HDFC Bank", update.institutionName)
        assertEquals(AccountType.BANK_ACCOUNT, update.accountType)
        assertEquals("0801", update.accountHint)
        assertEquals(12_345_678L, update.balanceMinor)
        assertNull(update.availableCreditMinor)
    }

    @Test
    fun extractsAbbreviatedHdfcDailyBalanceForAnotherAccount() {
        val update = parser.parse(
            sender = "VM-HDFCBK-S",
            body = "Dear Customer, Avl Bal. for account XX8004 as of today is Rs. 9,876.00 - HDFC Bank",
            timestamp = 1_775_555_900_000,
        )

        requireNotNull(update)
        assertEquals("hdfc", update.bankKey)
        assertEquals("8004", update.accountHint)
        assertEquals(987_600L, update.balanceMinor)
    }

    @Test
    fun ignoresMessagesWithoutAvailability() {
        assertNull(
            parser.parse(
                sender = "VK-HDFCBK",
                body = "Your monthly statement is ready.",
                timestamp = 1_700_000_000_000,
            ),
        )
    }

    @Test
    fun buildsVerifiedBankAndCardCommands() {
        val sbiBank = BankSmsSupport.commandFor(
            AccountProfile(name = "SBI Savings", type = AccountType.BANK_ACCOUNT, institution = "SBI"),
        )
        val sbiCard = BankSmsSupport.commandFor(
            AccountProfile(name = "SBI Card", type = AccountType.CREDIT_CARD, accountHint = "9876"),
        )

        requireNotNull(sbiBank)
        requireNotNull(sbiCard)
        assertEquals("919223766666", sbiBank.destination)
        assertEquals("BAL", sbiBank.message)
        assertEquals("5676791", sbiCard.destination)
        assertEquals("AVAIL 9876", sbiCard.message)
    }
}
