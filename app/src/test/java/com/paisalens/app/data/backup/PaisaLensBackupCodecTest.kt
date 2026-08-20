package com.paisalens.app.data.backup

import com.paisalens.app.data.model.AccountProfile
import com.paisalens.app.data.model.AccountBalanceSnapshot
import com.paisalens.app.data.model.AccountType
import com.paisalens.app.data.model.AdvancedBudgetPlan
import com.paisalens.app.data.model.AuditAction
import com.paisalens.app.data.model.AuditEntityType
import com.paisalens.app.data.model.AuditEvent
import com.paisalens.app.data.model.BillReminder
import com.paisalens.app.data.model.CategoryBudget
import com.paisalens.app.data.model.CustomCategory
import com.paisalens.app.data.model.CreditCardBill
import com.paisalens.app.data.model.CreditCardBillStatus
import com.paisalens.app.data.model.ExpenseCategory
import com.paisalens.app.data.model.ExpenseSplit
import com.paisalens.app.data.model.ExpenseSplitEntryMode
import com.paisalens.app.data.model.LoanAccount
import com.paisalens.app.data.model.NetWorthItem
import com.paisalens.app.data.model.NetWorthKind
import com.paisalens.app.data.model.MerchantAliasRule
import com.paisalens.app.data.model.MerchantCategoryRule
import com.paisalens.app.data.model.MonthlyReconciliation
import com.paisalens.app.data.model.PaisaLensBackupSnapshot
import com.paisalens.app.data.model.PaymentCommitment
import com.paisalens.app.data.model.PaymentCommitmentKind
import com.paisalens.app.data.model.ReviewStatus
import com.paisalens.app.data.model.ReconciliationStatus
import com.paisalens.app.data.model.SmartCategoryRule
import com.paisalens.app.data.model.SmartRuleMatchType
import com.paisalens.app.data.model.SavingsContribution
import com.paisalens.app.data.model.SavingsGoal
import com.paisalens.app.data.model.SmsCoverageMessage
import com.paisalens.app.data.model.SmsCoverageReason
import com.paisalens.app.data.model.SmsCoverageRule
import com.paisalens.app.data.model.TransactionRecord
import com.paisalens.app.data.model.TransactionAuditPayload
import com.paisalens.app.data.model.TransactionAuditPayloadCodec
import com.paisalens.app.data.model.TransactionLink
import com.paisalens.app.data.model.TransactionLinkType
import com.paisalens.app.data.model.TransactionSource
import com.paisalens.app.data.model.TransactionType
import com.paisalens.app.data.model.TransactionSmsSource
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.EOFException
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

        val expected = snapshot.copy(
            smsCoverageMessages = emptyList(),
            auditEvents = snapshot.auditEvents.map { event ->
                event.copy(
                    beforePayload = TransactionAuditPayloadCodec.portable(event.beforePayload),
                    afterPayload = TransactionAuditPayloadCodec.portable(event.afterPayload),
                )
            },
        )
        assertEquals(expected, restored)
        val portableAudit = TransactionAuditPayloadCodec.decode(requireNotNull(restored.auditEvents.single().beforePayload))
        assertEquals(null, portableAudit.rawMessageCipher)
        assertEquals(111L, portableAudit.createdAt)
    }

    @Test
    fun rejectsIncorrectPassphrase() {
        val output = ByteArrayOutputStream()
        PaisaLensBackupCodec.write(sampleSnapshot(), "correct horse".toCharArray(), output)

        assertThrows(IllegalArgumentException::class.java) {
            PaisaLensBackupCodec.read("wrong password".toCharArray(), ByteArrayInputStream(output.toByteArray()))
        }
    }

    @Test
    fun verifiesAuthenticatedMetadataAndCounts() {
        val snapshot = sampleSnapshot()
        val output = ByteArrayOutputStream()
        PaisaLensBackupCodec.write(snapshot, "correct horse".toCharArray(), output)

        val metadata = PaisaLensBackupCodec.verify(
            "correct horse".toCharArray(),
            ByteArrayInputStream(output.toByteArray()),
        )

        assertEquals(8, metadata.formatVersion)
        assertEquals(snapshot.createdAt, metadata.createdAt)
        assertEquals(snapshot.transactions.size, metadata.transactionCount)
        assertEquals(snapshot.accounts.size, metadata.accountCount)
        assertEquals(snapshot.reconciliations.size, metadata.reconciliationCount)
        assertEquals(snapshot.transactionLinks.size, metadata.transactionLinkCount)
        assertEquals(snapshot.auditEvents.size, metadata.auditEventCount)
        assertEquals(23, metadata.totalRecordCount)
        assertEquals(1, metadata.expenseSplitCount)
        assertEquals(1, metadata.savingsGoalCount)
        assertEquals(1, metadata.savingsContributionCount)
        assertEquals(1, metadata.paymentCommitmentCount)
        assertEquals(1, metadata.transactionSmsSourceCount)
        assertEquals(0, metadata.smsCoverageMessageCount)
        assertEquals(1, metadata.smsCoverageRuleCount)
        assertEquals(1, metadata.advancedBudgetCount)
        assertEquals(1, metadata.creditCardBillCount)
        assertEquals(64, metadata.contentSha256.length)
    }

    @Test
    fun excludesUnresolvedSmsTextFromBackups() {
        val snapshot = sampleSnapshot()
        val output = ByteArrayOutputStream()

        PaisaLensBackupCodec.write(snapshot, "correct horse".toCharArray(), output)
        val restored = PaisaLensBackupCodec.read(
            "correct horse".toCharArray(),
            ByteArrayInputStream(output.toByteArray()),
        )

        assertEquals(emptyList<SmsCoverageMessage>(), restored.smsCoverageMessages)
        assertEquals(snapshot.smsCoverageRules, restored.smsCoverageRules)
    }

    @Test
    fun roundTripsMergedPhysicalAccountIdentity() {
        val root = sampleSnapshot()
        val merged = root.copy(
            accounts = root.accounts + AccountProfile(
                id = 21,
                name = "Travel card",
                type = AccountType.CREDIT_CARD,
                accountHint = "9876",
                institution = "Second Bank",
                identityKey = "CARD:second-bank:9876",
                mergedIntoAccountId = 1,
            ),
        )
        val output = ByteArrayOutputStream()

        PaisaLensBackupCodec.write(merged, "correct horse".toCharArray(), output)
        val restored = PaisaLensBackupCodec.read(
            "correct horse".toCharArray(),
            ByteArrayInputStream(output.toByteArray()),
        )

        assertEquals(1L, restored.accounts.single { it.id == 21L }.mergedIntoAccountId)
        assertEquals(merged.accounts, restored.accounts)
    }

    @Test
    fun readsVersionFourBackupWithoutTrustAccuracyCollections() {
        val snapshot = sampleSnapshot()
        val output = ByteArrayOutputStream()
        PaisaLensBackupCodec.writeVersionForTesting(
            snapshot,
            "correct horse".toCharArray(),
            output,
            formatVersion = 4,
        )

        val restored = PaisaLensBackupCodec.read(
            "correct horse".toCharArray(),
            ByteArrayInputStream(output.toByteArray()),
        )

        assertEquals(
            snapshot.copy(
                accounts = snapshot.accounts.map { it.copy(identityKey = null) },
                reconciliations = emptyList(),
                transactionLinks = emptyList(),
                auditEvents = emptyList(),
                expenseSplits = emptyList(),
                savingsGoals = emptyList(),
                savingsContributions = emptyList(),
                paymentCommitments = emptyList(),
                transactionSmsSources = emptyList(),
                smsCoverageMessages = emptyList(),
                smsCoverageRules = emptyList(),
                advancedBudgets = emptyList(),
                creditCardBills = emptyList(),
            ),
            restored,
        )
    }

    @Test
    fun readsVersionFiveBackupWithoutSharedFinanceCollections() {
        val snapshot = sampleSnapshot()
        val output = ByteArrayOutputStream()
        PaisaLensBackupCodec.writeVersionForTesting(
            snapshot,
            "correct horse".toCharArray(),
            output,
            formatVersion = 5,
        )

        val restored = PaisaLensBackupCodec.read(
            "correct horse".toCharArray(),
            ByteArrayInputStream(output.toByteArray()),
        )

        assertEquals(
            snapshot.copy(
                expenseSplits = emptyList(),
                savingsGoals = emptyList(),
                savingsContributions = emptyList(),
                paymentCommitments = emptyList(),
                transactionSmsSources = emptyList(),
                smsCoverageMessages = emptyList(),
                smsCoverageRules = emptyList(),
                advancedBudgets = emptyList(),
                creditCardBills = emptyList(),
                auditEvents = snapshot.auditEvents.map { event ->
                    event.copy(
                        beforePayload = TransactionAuditPayloadCodec.portable(event.beforePayload),
                        afterPayload = TransactionAuditPayloadCodec.portable(event.afterPayload),
                    )
                },
            ),
            restored,
        )
    }

    @Test
    fun readsVersionSixBackupWithoutV7Collections() {
        val snapshot = sampleSnapshot()
        val output = ByteArrayOutputStream()
        PaisaLensBackupCodec.writeVersionForTesting(
            snapshot,
            "correct horse".toCharArray(),
            output,
            formatVersion = 6,
        )

        val restored = PaisaLensBackupCodec.read(
            "correct horse".toCharArray(),
            ByteArrayInputStream(output.toByteArray()),
        )

        assertEquals(
            snapshot.copy(
                transactionSmsSources = emptyList(),
                smsCoverageMessages = emptyList(),
                smsCoverageRules = emptyList(),
                advancedBudgets = emptyList(),
                creditCardBills = emptyList(),
                expenseSplits = snapshot.expenseSplits.map { split ->
                    split.copy(
                        entryMode = ExpenseSplitEntryMode.AMOUNT,
                        shareBasisPoints = null,
                    )
                },
                auditEvents = snapshot.auditEvents.map { event ->
                    event.copy(
                        beforePayload = TransactionAuditPayloadCodec.portable(event.beforePayload),
                        afterPayload = TransactionAuditPayloadCodec.portable(event.afterPayload),
                    )
                },
            ),
            restored,
        )
    }

    @Test
    fun readsVersionSevenCollectionsWithoutConsumingV8MergeFields() {
        val original = sampleSnapshot()
        val snapshot = original.copy(
            accounts = original.accounts.map {
                it.copy(mergedIntoAccountId = 99, mergedMemberCount = 2)
            },
        )
        val output = ByteArrayOutputStream()
        PaisaLensBackupCodec.writeVersionForTesting(
            snapshot,
            "correct horse".toCharArray(),
            output,
            formatVersion = 7,
        )

        val restored = PaisaLensBackupCodec.read(
            "correct horse".toCharArray(),
            ByteArrayInputStream(output.toByteArray()),
        )

        assertEquals(
            snapshot.copy(
                accounts = snapshot.accounts.map {
                    it.copy(mergedIntoAccountId = null, mergedMemberCount = 1)
                },
                smsCoverageMessages = emptyList(),
                auditEvents = snapshot.auditEvents.map { event ->
                    event.copy(
                        beforePayload = TransactionAuditPayloadCodec.portable(event.beforePayload),
                        afterPayload = TransactionAuditPayloadCodec.portable(event.afterPayload),
                    )
                },
            ),
            restored,
        )
    }

    @Test
    fun formatHeaderIsAuthenticatedForVersionFive() {
        val output = ByteArrayOutputStream()
        PaisaLensBackupCodec.write(sampleSnapshot(), "correct horse".toCharArray(), output)
        val tampered = output.toByteArray().also { it[7] = 4 }

        assertThrows(IllegalArgumentException::class.java) {
            PaisaLensBackupCodec.read("correct horse".toCharArray(), ByteArrayInputStream(tampered))
        }
    }

    @Test
    fun rejectsTrailingBytesAfterEncryptedPayload() {
        val output = ByteArrayOutputStream()
        PaisaLensBackupCodec.write(sampleSnapshot(), "correct horse".toCharArray(), output)
        val tampered = output.toByteArray() + byteArrayOf(0x42)

        assertThrows(IllegalArgumentException::class.java) {
            PaisaLensBackupCodec.verify("correct horse".toCharArray(), ByteArrayInputStream(tampered))
        }
    }

    @Test
    fun rejectsTruncatedEncryptedPayload() {
        val output = ByteArrayOutputStream()
        PaisaLensBackupCodec.write(sampleSnapshot(), "correct horse".toCharArray(), output)
        val bytes = output.toByteArray()
        val truncated = bytes.copyOf(bytes.size - 1)

        assertThrows(EOFException::class.java) {
            PaisaLensBackupCodec.read("correct horse".toCharArray(), ByteArrayInputStream(truncated))
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
                identityKey = "CARD:bank:4321",
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
        reconciliations = listOf(
            MonthlyReconciliation(
                id = 8,
                accountId = 1,
                year = 2026,
                month = 7,
                openingBalanceMinor = 4_000_000,
                closingBalanceMinor = 3_500_000,
                statementTransactionCount = 1,
                matchedTransactionCount = 1,
                status = ReconciliationStatus.RECONCILED,
                reconciledAt = 123456789L,
                updatedAt = 123456789L,
            ),
        ),
        transactionLinks = listOf(
            TransactionLink(10, 9, 11, TransactionLinkType.REFUND, "Matched refund", 123456789L),
        ),
        auditEvents = listOf(
            AuditEvent(
                id = 12,
                batchId = "batch-1",
                batchLabel = "Update category",
                entityType = AuditEntityType.TRANSACTION,
                entityId = "9",
                action = AuditAction.UPDATE,
                beforePayload = transactionAuditPayload("LOCAL_KEYSTORE_CIPHER".toByteArray()),
                afterPayload = transactionAuditPayload("LOCAL_KEYSTORE_CIPHER".toByteArray()),
                occurredAt = 123456789L,
            ),
        ),
        expenseSplits = listOf(
            ExpenseSplit(
                id = 13,
                transactionId = 9,
                participantName = "Riya",
                shareMinor = 50_000,
                reimbursedMinor = 20_000,
                note = "Lunch split",
                createdAt = 123456789L,
                updatedAt = 123456789L,
                entryMode = ExpenseSplitEntryMode.PERCENTAGE,
                shareBasisPoints = 5_000,
            ),
        ),
        savingsGoals = listOf(
            SavingsGoal(14, "Emergency fund", 5_000_000, 1_000_000, 21_000, 1, createdAt = 123456789L, updatedAt = 123456789L),
        ),
        savingsContributions = listOf(
            SavingsContribution(15, 14, 250_000, 123456789L, "Monthly saving", null),
        ),
        paymentCommitments = listOf(
            PaymentCommitment(
                id = 16,
                name = "Music plan",
                merchantKey = "music plan",
                kind = PaymentCommitmentKind.UPI_AUTOPAY,
                amountMinor = 99_00,
                maxMandateMinor = 199_00,
                nextDueEpochDay = 21_000,
                upiHandle = "music@upi",
                createdAt = 123456789L,
                updatedAt = 123456789L,
            ),
        ),
        transactionSmsSources = listOf(
            TransactionSmsSource("sms-9", 9, 987654321L),
        ),
        smsCoverageMessages = listOf(
            SmsCoverageMessage(
                id = 17,
                sourceMessageId = "sms-coverage-17",
                sender = "VK-BANK",
                body = "INR 500 was processed using a new alert format",
                receivedAt = 123456789L,
                reason = SmsCoverageReason.MISSING_DIRECTION,
                createdAt = 123456789L,
                updatedAt = 123456789L,
            ),
        ),
        smsCoverageRules = listOf(
            SmsCoverageRule(
                id = 18,
                name = "New bank purchase",
                senderKey = "BANK",
                requiredPhrases = listOf("was processed"),
                merchantName = "Bank purchase",
                category = ExpenseCategory.SHOPPING,
                createdAt = 123456789L,
                updatedAt = 123456789L,
            ),
        ),
        advancedBudgets = listOf(
            AdvancedBudgetPlan(
                id = 19,
                name = "Monthly food",
                category = ExpenseCategory.FOOD,
                allocationMinor = 500_000,
                effectiveFromEpochDay = 21_000,
                createdAt = 123456789L,
                updatedAt = 123456789L,
            ),
        ),
        creditCardBills = listOf(
            CreditCardBill(
                id = 20,
                billKey = "card:bank:4321:21031",
                sourceMessageId = "sms-bill-20",
                accountId = 1,
                cardIdentityKey = "card:bank:4321",
                accountHint = "4321",
                institutionName = "Bank",
                totalDueMinor = 250_000,
                minimumDueMinor = 25_000,
                dueDateEpochDay = 21_031,
                detectedAt = 123456789L,
                sender = "VK-BANK",
                status = CreditCardBillStatus.DUE,
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
            TransactionRecord(
                id = 11,
                sourceMessageId = "sms-11",
                amountMinor = 125000,
                merchant = "Happy Paws refund",
                accountHint = "4321",
                category = ExpenseCategory.OTHER,
                type = TransactionType.REFUND,
                occurredAt = 987654999L,
                source = TransactionSource.CARD,
                sender = "VK-BANK",
                accountId = 1,
            ),
        ),
    )

    private fun transactionAuditPayload(rawCipher: ByteArray?): String = TransactionAuditPayloadCodec.encode(
        TransactionAuditPayload(
            record = TransactionRecord(
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
            rawMessageCipher = rawCipher,
            createdAt = 111L,
        ),
    )
}
