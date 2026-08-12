package com.paisalens.app.ui.screens

import com.paisalens.app.data.model.AccountProfile
import com.paisalens.app.data.model.AccountType
import com.paisalens.app.data.model.ExpenseCategory
import com.paisalens.app.data.model.TransactionRecord
import com.paisalens.app.data.model.TransactionSource
import com.paisalens.app.data.model.TransactionType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TransactionsScreenFilterTest {
    @Test
    fun createsVirtualOptionsFromTaggedTransactionsWithoutProfiles() {
        val options = activityAccountFilterOptions(
            accounts = emptyList(),
            transactions = listOf(
                transaction(1, null, "A", TransactionSource.BANK, null, "0801", "HDFC Bank"),
                transaction(2, null, "B", TransactionSource.CARD, null, "9009", "SBI Card"),
            ),
        )

        assertEquals(2, options.size)
        assertTrue(options.any {
            it.type == AccountType.BANK_ACCOUNT && it.lastFour == "0801" && it.label.contains("HDFC Bank")
        })
        assertTrue(options.any {
            it.type == AccountType.CREDIT_CARD && it.lastFour == "9009" && it.label.contains("SBI Card")
        })
        assertTrue(options.all { it.accountIds.isEmpty() })
    }

    @Test
    fun realAccountCoversTaggedOptionWithSameTypeAndLastFour() {
        val options = activityAccountFilterOptions(
            accounts = listOf(account(1, "Salary", AccountType.BANK_ACCOUNT, "HDFC", "0801")),
            transactions = listOf(
                transaction(1, null, "A", TransactionSource.BANK, null, "0801", "HDFC Bank"),
                transaction(2, null, "B", TransactionSource.BANK, null, "0801", "IDFC FIRST Bank"),
            ),
        )

        assertEquals(1, options.size)
        assertEquals(setOf(1L), options.single().accountIds)
        assertEquals("0801", options.single().lastFour)
        assertTrue(options.single().institutionNames.contains("HDFC Bank"))
        assertTrue(options.single().institutionNames.contains("IDFC FIRST Bank"))
    }

    @Test
    fun virtualOptionFiltersItsTaggedTransactions() {
        val rows = listOf(
            transaction(1, null, "A", TransactionSource.BANK, null, "0801", "HDFC Bank"),
            transaction(2, null, "B", TransactionSource.BANK, null, "8004", "HDFC Bank"),
        )
        val options = activityAccountFilterOptions(emptyList(), rows)
        val ending0801 = options.single { it.lastFour == "0801" }

        assertEquals(
            listOf(1L),
            filterActivityTransactions(
                rows, "", TransactionFilter.ALL, setOf(ending0801.key), options,
            ).map(TransactionRecord::id),
        )
    }

    @Test
    fun groupsDuplicateProfilesByTypeAndLastFour() {
        val options = activityAccountFilterOptions(
            listOf(
                account(1, "Salary", AccountType.BANK_ACCOUNT, "HDFC", "XX0801"),
                account(2, "Old sender alias", AccountType.BANK_ACCOUNT, "IDFC FIRST Bank", "0801"),
                account(3, "Rewards card", AccountType.CREDIT_CARD, "HDFC", "0801"),
                account(4, "Joint", AccountType.BANK_ACCOUNT, "HDFC", "8004"),
                account(5, "Cash", AccountType.CASH, null, null),
            ),
        )

        assertEquals(3, options.size)
        val salary = options.single { it.type == AccountType.BANK_ACCOUNT && it.lastFour == "0801" }
        assertEquals(setOf(1L, 2L), salary.accountIds)
        assertTrue(salary.label.contains("Salary"))
        assertTrue(salary.label.contains("HDFC Bank"))
        assertTrue(salary.label.contains("0801"))
    }

    @Test
    fun multipleSelectionsUseOrSemanticsAndStillCombineTypeAndSearchFilters() {
        val options = activityAccountFilterOptions(
            listOf(
                account(1, "Salary", AccountType.BANK_ACCOUNT, "HDFC", "0801"),
                account(2, "Travel card", AccountType.CREDIT_CARD, "IDFC", "8004"),
                account(3, "Bills", AccountType.BANK_ACCOUNT, "SBI", "1234"),
            ),
        )
        val selected = options.filter { it.lastFour in setOf("0801", "8004") }.mapTo(linkedSetOf()) { it.key }
        val rows = listOf(
            transaction(1, 1L, "Cafe", TransactionSource.BANK, AccountType.BANK_ACCOUNT, "0801", "HDFC Bank"),
            transaction(2, 2L, "Hotel", TransactionSource.CARD, AccountType.CREDIT_CARD, "8004", "IDFC FIRST Bank"),
            transaction(3, 3L, "Cafe", TransactionSource.BANK, AccountType.BANK_ACCOUNT, "1234", "State Bank of India"),
        )

        val filtered = filterActivityTransactions(
            transactions = rows,
            query = "Cafe",
            typeFilter = TransactionFilter.EXPENSE,
            selectedAccountKeys = selected,
            accountOptions = options,
        )

        assertEquals(listOf(1L), filtered.map(TransactionRecord::id))
    }

    @Test
    fun taggedUnlinkedSmsMatchesInstitutionAndLastFour() {
        val options = activityAccountFilterOptions(
            listOf(
                account(1, "Daily account", AccountType.BANK_ACCOUNT, "HDFC", "0801"),
                account(2, "Savings account", AccountType.BANK_ACCOUNT, "HDFC", "8004"),
            ),
        )
        val daily = options.single { it.lastFour == "0801" }
        val rows = listOf(
            transaction(1, null, "A", TransactionSource.BANK, null, "0801", "HDFC Bank"),
            transaction(2, null, "B", TransactionSource.BANK, null, "8004", "HDFC Bank"),
            transaction(3, null, "C", TransactionSource.BANK, null, null, "HDFC Bank"),
        )

        val filtered = filterActivityTransactions(
            transactions = rows,
            query = "",
            typeFilter = TransactionFilter.ALL,
            selectedAccountKeys = setOf(daily.key),
            accountOptions = options,
        )

        assertEquals(listOf(1L), filtered.map(TransactionRecord::id))
    }

    @Test
    fun taggedSmsWithoutLastFourMatchesOnlyUnambiguousInstitutionAndType() {
        val singleOption = activityAccountFilterOptions(
            listOf(account(1, "Primary", AccountType.BANK_ACCOUNT, "SBI", "1234")),
        ).single()
        val row = transaction(1, null, "A", TransactionSource.BANK, null, null, "State Bank of India")

        assertEquals(
            listOf(row),
            filterActivityTransactions(
                listOf(row),
                "",
                TransactionFilter.ALL,
                setOf(singleOption.key),
                listOf(singleOption),
            ),
        )
    }

    @Test
    fun linkedAccountIdWinsWithoutUnsafeLastFourFallback() {
        val options = activityAccountFilterOptions(
            listOf(
                account(1, "Salary", AccountType.BANK_ACCOUNT, "HDFC", "0801"),
                account(2, "Travel", AccountType.BANK_ACCOUNT, "IDFC", "8004"),
            ),
        )
        val hdfc = options.single { it.institutionName == "HDFC Bank" }
        val linkedToIdfc = transaction(1, 2L, "A", TransactionSource.BANK, AccountType.BANK_ACCOUNT, "0801", "HDFC Bank")

        assertTrue(
            filterActivityTransactions(
                listOf(linkedToIdfc), "", TransactionFilter.ALL, setOf(hdfc.key), options,
            ).isEmpty(),
        )
    }

    @Test
    fun dropsSelectionsWhoseAccountGroupWasRemoved() {
        val option = activityAccountFilterOptions(
            listOf(account(1, "Primary", AccountType.BANK_ACCOUNT, "SBI", "1234")),
        ).single()

        assertEquals(setOf(option.key), validActivityAccountSelections(setOf(option.key, "missing"), listOf(option)))
        assertTrue(validActivityAccountSelections(setOf(option.key), emptyList()).isEmpty())
        assertFalse(option.accessibilityLabel.isBlank())
    }

    private fun account(
        id: Long,
        name: String,
        type: AccountType,
        institution: String?,
        hint: String?,
    ) = AccountProfile(
        id = id,
        name = name,
        type = type,
        institution = institution,
        accountHint = hint,
    )

    private fun transaction(
        id: Long,
        accountId: Long?,
        merchant: String,
        source: TransactionSource,
        accountType: AccountType?,
        hint: String?,
        institutionName: String?,
    ) = TransactionRecord(
        id = id,
        sourceMessageId = "test-$id",
        amountMinor = 100L,
        merchant = merchant,
        accountHint = hint,
        category = ExpenseCategory.FOOD,
        type = TransactionType.EXPENSE,
        occurredAt = id,
        source = source,
        sender = "TEST",
        accountId = accountId,
        accountName = accountType?.label,
        institutionName = institutionName,
    )
}
