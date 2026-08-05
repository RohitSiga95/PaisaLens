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
        assertNull(update.balanceMinor)
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
