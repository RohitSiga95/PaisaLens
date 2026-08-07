package com.paisalens.app.data.backup

import com.paisalens.app.data.model.AccountProfile
import com.paisalens.app.data.model.AccountBalanceSnapshot
import com.paisalens.app.data.model.AccountType
import com.paisalens.app.data.model.BillReminder
import com.paisalens.app.data.model.CategoryBudget
import com.paisalens.app.data.model.CustomCategory
import com.paisalens.app.data.model.ExpenseCategory
import com.paisalens.app.data.model.LoanAccount
import com.paisalens.app.data.model.NetWorthItem
import com.paisalens.app.data.model.NetWorthKind
import com.paisalens.app.data.model.MerchantAliasRule
import com.paisalens.app.data.model.MerchantCategoryRule
import com.paisalens.app.data.model.PaisaLensBackupSnapshot
import com.paisalens.app.data.model.ReviewStatus
import com.paisalens.app.data.model.SmartCategoryRule
import com.paisalens.app.data.model.SmartRuleMatchType
import com.paisalens.app.data.model.TransactionRecord
import com.paisalens.app.data.model.TransactionSource
import com.paisalens.app.data.model.TransactionType
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class PaisaLensBackupCodecTest {
    @Test
    fun roundTripsPortableEncryptedBackup() {
        val snapshot = sampleSnapshot()
        val output = ByteArrayOutputStream()

        PaisaLensBackupCodec.write(snapshot, "correct horse".toCharArray(), output)
        val restored = PaisaLensBackupCodec.read(
            "correct horse".toCharArray(),
            ByteArrayInputStream(output.toByteArray()),
        )

        assertEquals(snapshot, restored)
    }

    @Test
    fun rejectsIncorrectPassphrase() {
        val output = ByteArrayOutputStream()
        PaisaLensBackupCodec.write(sampleSnapshot(), "correct horse".toCharArray(), output)

        assertThrows(IllegalArgumentException::class.java) {
            PaisaLensBackupCodec.read("wrong password".toCharArray(), ByteArrayInputStream(output.toByteArray()))
        }
    }

    private fun sampleSnapshot() = PaisaLensBackupSnapshot(
        createdAt = 123456789L,
        accounts = listOf(
            AccountProfile(
                id = 1,
                name = "Daily card",
                type = AccountType.CREDIT_CARD,
                accountHint = "4321",
                institution = "Bank",
                availableCreditMinor = 3_500_000,
                creditLimitMinor = 5_000_000,
                availabilityFetchedAt = 123456789L,
                availabilitySender = "BANK",
            ),
        ),
        customCategories = listOf(CustomCategory(2, "Pet care", "#21D19F")),
        budgets = listOf(CategoryBudget(ExpenseCategory.FOOD, 500000)),
        merchantRules = listOf(
            MerchantCategoryRule("happy paws", "Happy Paws", ExpenseCategory.OTHER, 2),
        ),
        merchantAliases = listOf(MerchantAliasRule("amzn", "AMZN", "Amazon", 123456789L)),
        loans = listOf(
            LoanAccount(3, "Car loan", "Bank", 50000000, 850, 60, 20000, 1026000, 7, 1),
        ),
        balanceHistory = listOf(
            AccountBalanceSnapshot(4, 1, null, 3_500_000, 5_000_000, 123456789L, "BANK"),
        ),
        bills = listOf(
            BillReminder(5, "Electricity", 225_000, 21_000, 1, 1, "Autopay", true, null),
        ),
        netWorthItems = listOf(
            NetWorthItem(6, "Mutual funds", NetWorthKind.ASSET, 12_500_000, "Investments", 123456789L),
        ),
        smartCategoryRules = listOf(
            SmartCategoryRule(
                id = 7,
                name = "Food delivery",
                merchantPattern = "swiggy",
                matchType = SmartRuleMatchType.CONTAINS,
                minAmountMinor = 10_000,
                maxAmountMinor = 500_000,
                accountId = 1,
                category = ExpenseCategory.FOOD,
                customCategoryId = null,
                enabled = true,
                priority = 10,
                updatedAt = 123456789L,
            ),
        ),
        transactions = listOf(
            TransactionRecord(
                id = 9,
                sourceMessageId = "sms-9",
                amountMinor = 125000,
                merchant = "Happy Paws",
                accountHint = "4321",
                category = ExpenseCategory.OTHER,
                type = TransactionType.EXPENSE,
                occurredAt = 987654321L,
                source = TransactionSource.CARD,
                sender = "VK-BANK",
                note = "Annual vaccination",
                accountId = 1,
                customCategoryId = 2,
                tags = listOf("Pet", "Health"),
                reviewStatus = ReviewStatus.CONFIRMED,
                originalAmountMinor = 1_500,
                originalCurrency = "USD",
                exchangeRate = 83.3333,
            ),
        ),
    )
}
