package com.paisalens.app.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AccountMergeModelsTest {
    @Test
    fun requiresNonblankNameAndTwoDistinctAccounts() {
        val accounts = listOf(bankAccount(1), bankAccount(2))

        assertEquals(
            AccountMergeError.NAME_REQUIRED,
            validateAccountMergeSelection(accounts, listOf(1, 2), "  \n ")?.error,
        )
        assertEquals(
            AccountMergeError.AT_LEAST_TWO_REQUIRED,
            validateAccountMergeSelection(accounts, listOf(1, 1), "Household")?.error,
        )
    }

    @Test
    fun reportsMissingAndMixedTypeSelections() {
        val accounts = listOf(bankAccount(1), cardAccount(2))

        val missing = validateAccountMergeSelection(accounts, listOf(1, 99), "Combined")
        assertEquals(AccountMergeError.ACCOUNT_NOT_FOUND, missing?.error)
        assertEquals(setOf(99L), missing?.accountIds)
        assertEquals(
            AccountMergeError.MIXED_ACCOUNT_TYPES,
            validateAccountMergeSelection(accounts, listOf(1, 2), "Combined")?.error,
        )
    }

    @Test
    fun rejectsUnsupportedSameTypeSelection() {
        val accounts = listOf(
            bankAccount(1).copy(type = AccountType.WALLET),
            bankAccount(2).copy(type = AccountType.WALLET),
        )

        assertEquals(
            AccountMergeError.UNSUPPORTED_ACCOUNT_TYPE,
            validateAccountMergeSelection(accounts, listOf(1, 2), "Wallets")?.error,
        )
    }

    @Test
    fun detectsNestedAndRepeatedSameMemberSelections() {
        val accounts = listOf(
            bankAccount(1),
            bankAccount(2).copy(mergedIntoAccountId = 1),
            bankAccount(3).copy(mergedIntoAccountId = 2),
            bankAccount(4),
        )

        assertEquals(1L, canonicalAccountId(accounts.associateBy(AccountProfile::id), 3))
        assertEquals(
            AccountMergeError.ALREADY_MERGED,
            validateAccountMergeSelection(accounts, listOf(1, 3), "Again")?.error,
        )
        assertNull(validateAccountMergeSelection(accounts, listOf(3, 4), "Larger group"))
    }

    @Test
    fun resolvesFuturePhysicalSmsIdentityToMergedRoot() {
        val accounts = listOf(
            bankAccount(3, institution = "HDFC Bank", hint = "1111"),
            bankAccount(8, institution = "IDFC FIRST Bank", hint = "2222")
                .copy(mergedIntoAccountId = 3),
        )

        // Institution-safe SMS matching finds physical account 8 first; only the explicit merge
        // relation then maps it to the user-named logical root.
        assertEquals(3L, canonicalAccountId(accounts.associateBy(AccountProfile::id), 8))
    }

    @Test
    fun consolidatesLatestPhysicalBalancesWithoutDoubleCountingBankAliases() {
        val accounts = listOf(
            bankAccount(1, institution = "HDFC", hint = "1111").copy(
                name = "Household",
                balanceMinor = 100,
                availabilityFetchedAt = 10,
                availabilitySender = "HDFC-old",
            ),
            bankAccount(2, institution = "HDFC Bank", hint = "1111").copy(
                balanceMinor = 120,
                availabilityFetchedAt = 20,
                availabilitySender = "HDFC-new",
                mergedIntoAccountId = 1,
            ),
            bankAccount(3, institution = "IDFC FIRST Bank", hint = "2222").copy(
                balanceMinor = 50,
                availabilityFetchedAt = 30,
                availabilitySender = "IDFC-new",
                mergedIntoAccountId = 1,
            ),
        )

        val consolidated = consolidatedAccountProfiles(accounts).single()

        assertEquals(1L, consolidated.id)
        assertEquals("Household", consolidated.name)
        assertEquals(170L, consolidated.balanceMinor)
        assertEquals(20L, consolidated.availabilityFetchedAt)
        assertNull(consolidated.availabilitySender)
        assertEquals(3, consolidated.mergedMemberCount)
        assertNull(consolidated.institution)
        assertNull(consolidated.accountHint)
    }

    @Test
    fun exposesPartialAggregateWithoutClaimingFreshnessWhenMemberStateIsMissing() {
        val accounts = listOf(
            bankAccount(1).copy(
                name = "Combined",
                balanceMinor = 100,
                availabilityFetchedAt = 10,
                availabilitySender = "BANK-1",
            ),
            bankAccount(2).copy(mergedIntoAccountId = 1),
        )

        val consolidated = consolidatedAccountProfiles(accounts).single()

        assertEquals(100L, consolidated.balanceMinor)
        assertNull(consolidated.availabilityFetchedAt)
        assertNull(consolidated.availabilitySender)
    }

    @Test
    fun canonicalizesEquivalentInstitutionAliases() {
        val accounts = listOf(
            bankAccount(1, institution = "HDFC", hint = "1111").copy(name = "Combined"),
            bankAccount(2, institution = "HDFC Bank", hint = "1111").copy(mergedIntoAccountId = 1),
        )

        assertEquals("HDFC Bank", consolidatedAccountProfiles(accounts).single().institution)
    }

    @Test
    fun creditAvailabilityIsPartialWhenMemberHasLimitOnly() {
        val accounts = listOf(
            cardAccount(1, institution = "HDFC Bank", hint = "1111").copy(
                name = "All cards",
                availableCreditMinor = 40_000,
                creditLimitMinor = 100_000,
                availabilityFetchedAt = 10,
            ),
            cardAccount(2, institution = "ICICI Bank", hint = "2222").copy(
                creditLimitMinor = 200_000,
                availabilityFetchedAt = 20,
                mergedIntoAccountId = 1,
            ),
        )

        val consolidated = consolidatedAccountProfiles(accounts).single()

        assertEquals(40_000L, consolidated.availableCreditMinor)
        assertEquals(300_000L, consolidated.creditLimitMinor)
        assertNull(consolidated.availabilityFetchedAt)
        assertNull(consolidated.availabilitySender)
    }

    @Test
    fun mergedCreditLimitIsUnavailableWhenAnyPhysicalCardHasNoLimit() {
        val accounts = listOf(
            cardAccount(1, institution = "HDFC Bank", hint = "1111").copy(
                name = "All cards",
                availableCreditMinor = 40_000,
                creditLimitMinor = 100_000,
                availabilityFetchedAt = 10,
            ),
            cardAccount(2, institution = "ICICI Bank", hint = "2222").copy(
                availableCreditMinor = 30_000,
                creditLimitMinor = null,
                availabilityFetchedAt = 20,
                mergedIntoAccountId = 1,
            ),
        )

        val consolidated = consolidatedAccountProfiles(accounts).single()

        assertEquals(70_000L, consolidated.availableCreditMinor)
        assertNull(consolidated.creditLimitMinor)
        assertEquals(10L, consolidated.availabilityFetchedAt)
    }

    @Test
    fun mergedCardsRetainSeparateCurrentBillCycles() {
        val accounts = listOf(
            cardAccount(2, institution = "HDFC Bank", hint = "1111"),
            cardAccount(7, institution = "ICICI Bank", hint = "2222").copy(mergedIntoAccountId = 2),
        )
        val byId = accounts.associateBy(AccountProfile::id)
        val bills = listOf(
            cardBill(10, 2, "HDFC Bank", "1111", due = 21_000),
            cardBill(11, 7, "ICICI Bank", "2222", due = 21_000),
        ).map { bill ->
            bill.copy(accountId = bill.accountId?.let { canonicalAccountId(byId, it) })
        }

        assertTrue(bills.all { it.accountId == 2L })
        assertEquals(2, currentCreditCardBills(bills).size)
        assertEquals(setOf("1111", "2222"), currentCreditCardBills(bills).mapTo(mutableSetOf()) { it.accountHint })
    }

    private fun bankAccount(
        id: Long,
        institution: String = "Bank $id",
        hint: String = id.toString().padStart(4, '0'),
    ) = AccountProfile(
        id = id,
        name = "$institution •$hint",
        type = AccountType.BANK_ACCOUNT,
        accountHint = hint,
        institution = institution,
        identityKey = "BANK_ACCOUNT:$institution:$hint",
    )

    private fun cardAccount(
        id: Long,
        institution: String = "Card $id",
        hint: String = id.toString().padStart(4, '0'),
    ) = AccountProfile(
        id = id,
        name = "$institution •$hint",
        type = AccountType.CREDIT_CARD,
        accountHint = hint,
        institution = institution,
        identityKey = "CREDIT_CARD:$institution:$hint",
    )

    private fun cardBill(
        id: Long,
        accountId: Long,
        institution: String,
        hint: String,
        due: Long,
    ) = CreditCardBill(
        id = id,
        billKey = "card:$institution:$hint:$due",
        sourceMessageId = "sms-$id",
        accountId = accountId,
        cardIdentityKey = "card:$institution:$hint",
        accountHint = hint,
        institutionName = institution,
        totalDueMinor = id * 100,
        dueDateEpochDay = due,
        detectedAt = due,
        sender = institution,
    )
}
