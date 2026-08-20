package com.paisalens.app.data.local

import android.content.Context
import com.paisalens.app.data.backup.PaisaLensBackupCodec
import com.paisalens.app.data.model.AccountMergeError
import com.paisalens.app.data.model.AccountMergeResult
import com.paisalens.app.data.model.AccountProfile
import com.paisalens.app.data.model.AccountType
import com.paisalens.app.data.model.AttentionKind
import com.paisalens.app.data.model.AuditAction
import com.paisalens.app.data.model.AuditEntityType
import com.paisalens.app.data.model.CreditCardBill
import com.paisalens.app.data.model.CreditCardBillStatus
import com.paisalens.app.data.model.ExpenseCategory
import com.paisalens.app.data.model.HomeTimelineSource
import com.paisalens.app.data.model.MonthlyReconciliation
import com.paisalens.app.data.model.ParsedCreditCardBill
import com.paisalens.app.data.model.ParsedTransaction
import com.paisalens.app.data.model.PaymentCommitment
import com.paisalens.app.data.model.PaymentCommitmentKind
import com.paisalens.app.data.model.PlanningReviewInput
import com.paisalens.app.data.model.ReconciliationStatus
import com.paisalens.app.data.model.SmartCategoryRule
import com.paisalens.app.data.model.SmartRuleMatchType
import com.paisalens.app.data.model.TransactionSource
import com.paisalens.app.data.model.TransactionType
import com.paisalens.app.data.model.buildHomeMoneyTimeline
import com.paisalens.app.data.model.buildNeedsAttentionSummary
import com.paisalens.app.data.model.currentCreditCardBills
import com.paisalens.app.data.model.deduplicatedPaymentCommitments
import com.paisalens.app.data.model.detectRecurringPayments
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.time.LocalDate
import java.time.ZoneOffset
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PaisaLensDatabaseAccountMergeTest {
    private lateinit var context: Context
    private lateinit var database: PaisaLensDatabase

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        context.deleteDatabase(DATABASE_NAME)
        database = PaisaLensDatabase(context)
    }

    @After
    fun tearDown() {
        database.close()
        context.deleteDatabase(DATABASE_NAME)
    }

    @Test
    fun mergeMapsExistingAndFutureSmsToCanonicalNameAndKeepsCardCyclesSeparate() {
        database.insertAll(
            listOf(
                cardTransaction("sms-hdfc-before", "1111", "VK-HDFCCRD", 1_000) to byteArrayOf(1),
                cardTransaction("sms-icici-before", "2222", "AX-ICICICRD", 2_000) to byteArrayOf(2),
            ),
        )
        val originalAccounts = database.getAccounts()
        val originalIds = originalAccounts.map(AccountProfile::id).toSet()
        val hdfcId = originalAccounts.single { it.institution == "HDFC Bank" }.id
        val iciciId = originalAccounts.single { it.institution == "ICICI Bank" }.id
        // This legacy rule belongs to the physical child before the accounts are merged.
        database.upsertSmartCategoryRule(
            accountRule("ICICI shared merchant", iciciId, ExpenseCategory.TRAVEL),
        )

        val result = database.mergeAccounts(originalIds, "Household cards")

        val success = result as AccountMergeResult.Success
        assertEquals(originalIds.minOrNull(), success.canonicalAccountId)
        assertEquals(2, success.memberCount)
        val canonical = database.getAccounts().single()
        assertEquals("Household cards", canonical.name)
        assertEquals(2, canonical.mergedMemberCount)
        assertTrue(database.getTransactions().all { it.accountId == canonical.id })
        assertTrue(database.getTransactions().all { it.accountName == "Household cards" })

        database.insertAll(
            listOf(
                cardTransaction(
                    "sms-hdfc-after",
                    "1111",
                    "VK-HDFCCRD",
                    3_000,
                    merchant = "Shared Merchant",
                ) to byteArrayOf(3),
                cardTransaction(
                    "sms-icici-after",
                    "2222",
                    "AX-ICICICRD",
                    4_000,
                    merchant = "Shared Merchant",
                ) to byteArrayOf(4),
            ),
        )
        val future = database.getTransactions().single { it.sourceMessageId == "sms-icici-after" }
        assertEquals(canonical.id, future.accountId)
        assertEquals("Household cards", future.accountName)
        assertEquals(ExpenseCategory.TRAVEL, future.category)
        assertEquals(
            ExpenseCategory.TRAVEL,
            database.getTransactions().single { it.sourceMessageId == "sms-hdfc-after" }.category,
        )
        val rawFutureAccountIds = database.snapshot().transactions
            .filter { it.sourceMessageId in setOf("sms-hdfc-after", "sms-icici-after") }
            .associate { it.sourceMessageId to it.accountId }
        assertEquals(hdfcId, rawFutureAccountIds["sms-hdfc-after"])
        assertEquals(iciciId, rawFutureAccountIds["sms-icici-after"])

        // A rule created against the visible root evaluates identically for every member and wins
        // deterministically by priority in ingestion, history apply, and the UI's root-based preview.
        database.upsertSmartCategoryRule(
            accountRule("All cards shared merchant", canonical.id, ExpenseCategory.FOOD, priority = 20),
            applyToHistory = true,
        )
        assertTrue(
            database.getTransactions()
                .filter { it.merchant == "Shared Merchant" }
                .all { it.category == ExpenseCategory.FOOD },
        )
        database.insertAll(
            listOf(
                cardTransaction(
                    "sms-hdfc-root-rule",
                    "1111",
                    "VK-HDFCCRD",
                    5_000,
                    merchant = "Shared Merchant",
                ) to byteArrayOf(5),
                cardTransaction(
                    "sms-icici-root-rule",
                    "2222",
                    "AX-ICICICRD",
                    6_000,
                    merchant = "Shared Merchant",
                ) to byteArrayOf(6),
            ),
        )
        assertTrue(
            database.getTransactions()
                .filter { it.sourceMessageId.endsWith("root-rule") }
                .all { it.category == ExpenseCategory.FOOD },
        )

        val dueDate = 21_500L
        assertEquals(
            2,
            database.upsertCreditCardBills(
                listOf(
                    parsedCardBill("bill-hdfc", "card:hdfc:1111", "1111", "HDFC Bank", "VK-HDFCCRD", dueDate),
                    parsedCardBill("bill-icici", "card:icici:2222", "2222", "ICICI Bank", "AX-ICICICRD", dueDate),
                ),
            ),
        )
        val currentBills = currentCreditCardBills(database.getCreditCardBills())
        assertEquals(2, currentBills.size)
        assertTrue(currentBills.all { it.accountId == canonical.id })
        assertEquals(setOf("1111", "2222"), currentBills.mapTo(mutableSetOf()) { it.accountHint })
        assertTrue(currentBills.all { it.status == CreditCardBillStatus.DUE })
    }

    @Test
    fun mergeIsAtomicAndFlattensNestedGroupsWhileRejectingSameMemberRepeat() {
        val first = database.addAccount("Salary", AccountType.BANK_ACCOUNT, "1111")
        val second = database.addAccount("Bills", AccountType.BANK_ACCOUNT, "2222")
        val third = database.addAccount("Savings", AccountType.BANK_ACCOUNT, "3333")
        val card = database.addAccount("Card", AccountType.CREDIT_CARD, "4444")

        val mixed = database.mergeAccounts(listOf(first, card), "Invalid")
        assertEquals(AccountMergeError.MIXED_ACCOUNT_TYPES, (mixed as AccountMergeResult.Failure).error)
        assertEquals(4, database.getAccounts().size)

        assertTrue(database.mergeAccounts(listOf(second, third), "Secondary") is AccountMergeResult.Success)
        val reconciliationId = database.upsertMonthlyReconciliation(
            reconciliation(third, id = 0, closing = 20_000),
        )
        val blocked = database.mergeAccounts(listOf(first, third), "Must not apply") as AccountMergeResult.Failure
        assertEquals(AccountMergeError.HAS_RECONCILIATIONS, blocked.error)
        assertEquals(setOf(third), blocked.accountIds)
        assertTrue(blocked.message.contains("Remove that history first"))
        assertEquals(3, database.getAccounts().size)
        assertEquals("Salary", database.getAccounts().single { it.id == first }.name)
        assertEquals("Secondary", database.getAccounts().single { it.id == second }.name)
        assertEquals(second, database.snapshot().accounts.single { it.id == third }.mergedIntoAccountId)
        assertEquals(1, database.getMonthlyReconciliations().size)

        database.deleteMonthlyReconciliation(reconciliationId)
        val expanded = database.mergeAccounts(listOf(first, third), "All bank money") as AccountMergeResult.Success
        assertEquals(first, expanded.canonicalAccountId)
        assertEquals(3, expanded.memberCount)
        assertEquals(2, database.getAccounts().size)

        val repeated = database.mergeAccounts(listOf(first, second), "Again") as AccountMergeResult.Failure
        assertEquals(AccountMergeError.ALREADY_MERGED, repeated.error)
        assertEquals("All bank money", database.getAccounts().single { it.type == AccountType.BANK_ACCOUNT }.name)
    }

    @Test
    fun assigningDistinctNoHintBillCyclesToMergedCardsDoesNotCollapseThem() {
        val first = database.addAccount("First card", AccountType.CREDIT_CARD, "1111")
        val second = database.addAccount("Second card", AccountType.CREDIT_CARD, "2222")
        val canonicalId = (database.mergeAccounts(listOf(first, second), "All cards") as AccountMergeResult.Success)
            .canonicalAccountId
        val dueDate = 21_600L
        database.upsertCreditCardBills(
            listOf(
                parsedCardBill("unknown-a", "card:issuer-a:unidentified:a", null, "Issuer A", "NOTICE-A", dueDate),
                parsedCardBill("unknown-b", "card:issuer-b:unidentified:b", null, "Issuer B", "NOTICE-B", dueDate),
            ),
        )
        val unassigned = database.getCreditCardBills()
        assertEquals(2, unassigned.size)
        assertTrue(unassigned.all { it.accountId == null })

        unassigned.forEach { assertTrue(database.assignCreditCardBillToAccount(it.id, canonicalId)) }

        val assigned = database.getCreditCardBills()
        assertEquals(2, assigned.size)
        assertTrue(assigned.all { it.accountId == canonicalId })
        assertEquals(2, assigned.map(CreditCardBill::billKey).distinct().size)
    }

    @Test
    fun sameBankMergedCardsKeepPhysicalRecurrencesAndNoHintBillAtGroupLevel() {
        val day = 86_400_000L
        database.insertAll(
            listOf(
                cardTransaction("hdfc-1111-a", "1111", "VK-HDFCCRD", 10 * day, "Shared subscription") to byteArrayOf(1),
                cardTransaction("hdfc-1111-b", "1111", "VK-HDFCCRD", 40 * day, "Shared subscription") to byteArrayOf(2),
                cardTransaction("hdfc-2222-a", "2222", "VK-HDFCCRD", 15 * day, "Shared subscription") to byteArrayOf(3),
                cardTransaction("hdfc-2222-b", "2222", "VK-HDFCCRD", 45 * day, "Shared subscription") to byteArrayOf(4),
            ),
        )
        val physicalIds = database.getAccounts().mapTo(mutableSetOf(), AccountProfile::id)
        val canonicalId = (database.mergeAccounts(physicalIds, "All HDFC cards") as AccountMergeResult.Success)
            .canonicalAccountId

        val transactions = database.getTransactions()
        assertTrue(transactions.all { it.accountId == canonicalId })
        assertEquals(physicalIds, transactions.mapNotNullTo(mutableSetOf()) { it.physicalAccountId })
        val recurring = detectRecurringPayments(transactions, now = 70 * day)
        assertEquals(2, recurring.size)
        assertEquals(physicalIds, recurring.mapNotNullTo(mutableSetOf()) { it.physicalAccountId })
        val suggestedPhysicalId = physicalIds.max()
        database.upsertPaymentCommitment(
            commitment(canonicalId, "Physical suggestion").copy(
                merchantKey = "physical suggestion",
                physicalAccountId = suggestedPhysicalId,
            ),
        )
        val savedSuggestion = database.getPaymentCommitments().single()
        assertEquals(canonicalId, savedSuggestion.accountId)
        assertEquals(suggestedPhysicalId, savedSuggestion.physicalAccountId)
        assertEquals(suggestedPhysicalId, database.snapshot().paymentCommitments.single().accountId)

        val dueDate = 21_650L
        assertEquals(
            1,
            database.upsertCreditCardBills(
                listOf(
                    parsedCardBill(
                        id = "hdfc-no-last4",
                        identity = "card:hdfc:unidentified:ambiguous",
                        hint = null,
                        institution = "HDFC Bank",
                        sender = "VK-HDFCCRD",
                        dueDate = dueDate,
                    ),
                ),
            ),
        )
        val bill = database.getCreditCardBills().single()
        assertEquals(canonicalId, bill.accountId)
        assertNull(bill.accountHint)
        assertEquals("card-account:$canonicalId", bill.cardIdentityKey)
        assertEquals("card-account:$canonicalId:$dueDate", bill.billKey)
    }

    @Test
    fun backupRoundTripRetainsMergeAliasesAndDistinctPhysicalCommitments() {
        val first = database.addAccount("Primary", AccountType.BANK_ACCOUNT, "1111")
        val second = database.addAccount("Secondary", AccountType.BANK_ACCOUNT, "2222")
        database.upsertPaymentCommitment(commitment(first, "First mandate"))
        database.upsertPaymentCommitment(commitment(second, "Second mandate"))
        val canonicalId = (database.mergeAccounts(listOf(first, second), "Combined") as AccountMergeResult.Success)
            .canonicalAccountId

        val output = ByteArrayOutputStream()
        PaisaLensBackupCodec.write(database.snapshot(), "correct horse".toCharArray(), output)
        val restoredSnapshot = PaisaLensBackupCodec.read(
            "correct horse".toCharArray(),
            ByteArrayInputStream(output.toByteArray()),
        )
        assertEquals(
            setOf(first, second),
            restoredSnapshot.paymentCommitments.mapNotNullTo(mutableSetOf(), PaymentCommitment::accountId),
        )
        assertEquals(
            canonicalId,
            restoredSnapshot.accounts.single { it.id == second }.mergedIntoAccountId,
        )
        database.restore(restoredSnapshot)

        val account = database.getAccounts().single()
        assertEquals(canonicalId, account.id)
        assertEquals("Combined", account.name)
        assertEquals(2, account.mergedMemberCount)
        val commitments = database.getPaymentCommitments()
        assertEquals(2, commitments.size)
        assertTrue(commitments.all { it.accountId == canonicalId })
        assertEquals(setOf(first, second), commitments.mapNotNullTo(mutableSetOf(), PaymentCommitment::physicalAccountId))
        assertEquals(2, deduplicatedPaymentCommitments(commitments).size)

        val dueDate = LocalDate.ofEpochDay(21_700)
        val timeline = buildHomeMoneyTimeline(
            transactions = emptyList(),
            accounts = listOf(account),
            manualBills = emptyList(),
            recurringPayments = emptyList(),
            loans = emptyList(),
            creditCardBills = emptyList(),
            paymentCommitments = commitments,
            today = dueDate,
            zoneId = ZoneOffset.UTC,
        )
        assertEquals(2, timeline.items.count { it.source == HomeTimelineSource.PAYMENT_COMMITMENT })
        assertEquals(2_000L, timeline.outgoingMinor)
        val attention = buildNeedsAttentionSummary(
            input = PlanningReviewInput(
                transactions = emptyList(),
                accounts = emptyList(),
                bills = emptyList(),
                savingsGoals = emptyList(),
                savingsContributions = emptyList(),
                paymentCommitments = commitments,
            ),
            today = dueDate,
            zoneId = ZoneOffset.UTC,
        )
        assertEquals(2, attention.items.count { it.kind == AttentionKind.PAYMENT_COMMITMENT_DUE })
        assertEquals(
            2_000L,
            attention.items.filter { it.kind == AttentionKind.PAYMENT_COMMITMENT_DUE }
                .sumOf { it.amountMinor ?: 0L },
        )
        val childCommitment = commitments.maxBy(PaymentCommitment::id)
        database.upsertPaymentCommitment(childCommitment.copy(notes = "Edited after merge"))
        assertEquals(2, database.getPaymentCommitments().size)
        assertEquals(
            "Edited after merge",
            database.getPaymentCommitments().single { it.id == childCommitment.id }.notes,
        )

        database.insertAll(
            listOf(
                bankTransaction("sms-after-restore", "2222", "AX-HDFCBK", 500) to byteArrayOf(4),
            ),
        )
        val future = database.getTransactions().single()
        assertEquals(canonicalId, future.accountId)
        assertEquals("Combined", future.accountName)
    }

    @Test
    fun deletingMergedCanonicalIsRejectedWithoutChangingTheGroup() {
        val first = database.addAccount("Primary", AccountType.BANK_ACCOUNT, "1111")
        val second = database.addAccount("Secondary", AccountType.BANK_ACCOUNT, "2222")
        val canonicalId = (database.mergeAccounts(listOf(first, second), "Combined") as AccountMergeResult.Success)
            .canonicalAccountId

        val error = runCatching { database.deleteAccount(canonicalId) }.exceptionOrNull()

        assertTrue(error is IllegalArgumentException)
        assertEquals("Merged accounts cannot be deleted", error?.message)
        assertEquals("Combined", database.getAccounts().single().name)
    }

    @Test
    fun reconciliationDeleteUndoCannotRestoreAHiddenMergedMember() {
        val first = database.addAccount("Primary", AccountType.BANK_ACCOUNT, "1111")
        val second = database.addAccount("Secondary", AccountType.BANK_ACCOUNT, "2222")
        val reconciliationId = database.upsertMonthlyReconciliation(
            reconciliation(second, id = 0, closing = 20_000),
        )
        database.deleteMonthlyReconciliation(reconciliationId)
        val deleteBatchId = database.getAuditEvents().single {
            it.entityType == AuditEntityType.MONTHLY_RECONCILIATION &&
                it.entityId == reconciliationId.toString() &&
                it.action == AuditAction.DELETE
        }.batchId
        assertTrue(database.mergeAccounts(listOf(first, second), "Combined") is AccountMergeResult.Success)

        val error = runCatching { database.undoAuditBatch(deleteBatchId) }.exceptionOrNull()

        assertTrue(error is IllegalArgumentException)
        assertTrue(error?.message.orEmpty().contains("hidden member of a merged account"))
        assertTrue(database.getMonthlyReconciliations().isEmpty())
        assertEquals("Combined", database.getAccounts().single().name)
    }

    @Test
    fun reconciliationDeleteUndoCannotCollideWithNewRootPeriod() {
        val first = database.addAccount("Primary", AccountType.BANK_ACCOUNT, "1111")
        val second = database.addAccount("Secondary", AccountType.BANK_ACCOUNT, "2222")
        val deletedId = database.upsertMonthlyReconciliation(
            reconciliation(first, id = 0, closing = 10_000),
        )
        database.deleteMonthlyReconciliation(deletedId)
        val deleteBatchId = database.getAuditEvents().single {
            it.entityType == AuditEntityType.MONTHLY_RECONCILIATION &&
                it.entityId == deletedId.toString() &&
                it.action == AuditAction.DELETE
        }.batchId
        val canonicalId = (database.mergeAccounts(listOf(first, second), "Combined") as AccountMergeResult.Success)
            .canonicalAccountId
        val currentId = database.upsertMonthlyReconciliation(
            reconciliation(canonicalId, id = 0, closing = 30_000),
        )

        val error = runCatching { database.undoAuditBatch(deleteBatchId) }.exceptionOrNull()

        assertTrue(error is IllegalArgumentException)
        assertTrue(error?.message.orEmpty().contains("already has a monthly reconciliation for this period"))
        assertEquals(listOf(currentId), database.getMonthlyReconciliations().map(MonthlyReconciliation::id))
    }

    @Test
    fun backupRestoreRejectsHiddenMemberReconciliationAndLogicalPeriodCollisionAtomically() {
        val first = database.addAccount("Primary", AccountType.BANK_ACCOUNT, "1111")
        val second = database.addAccount("Secondary", AccountType.BANK_ACCOUNT, "2222")
        val canonicalId = (database.mergeAccounts(listOf(first, second), "Combined") as AccountMergeResult.Success)
            .canonicalAccountId
        val baseline = database.snapshot()

        val hiddenMemberError = runCatching {
            database.restore(
                baseline.copy(
                    reconciliations = listOf(reconciliation(second, id = 90, closing = 20_000)),
                ),
            )
        }.exceptionOrNull()
        assertTrue(hiddenMemberError is IllegalArgumentException)
        assertTrue(hiddenMemberError?.message.orEmpty().contains("hidden member of a merged account"))
        assertEquals("Combined", database.getAccounts().single().name)
        assertTrue(database.getMonthlyReconciliations().isEmpty())

        val logicalPeriodError = runCatching {
            database.restore(
                baseline.copy(
                    reconciliations = listOf(
                        reconciliation(canonicalId, id = 91, closing = 10_000),
                        reconciliation(canonicalId, id = 92, closing = 20_000),
                    ),
                ),
            )
        }.exceptionOrNull()
        assertTrue(logicalPeriodError is IllegalArgumentException)
        assertTrue(logicalPeriodError?.message.orEmpty().contains("logical account already has"))
        assertEquals("Combined", database.getAccounts().single().name)
        assertTrue(database.getMonthlyReconciliations().isEmpty())
    }

    @Test
    fun migratesVersionTenAccountsWithoutLosingExistingLedgerData() {
        database.close()
        context.deleteDatabase(DATABASE_NAME)
        context.openOrCreateDatabase(DATABASE_NAME, Context.MODE_PRIVATE, null).use { legacy ->
            legacy.execSQL(
                """
                CREATE TABLE accounts (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    identity_key TEXT NOT NULL UNIQUE,
                    name TEXT NOT NULL,
                    type TEXT NOT NULL,
                    account_hint TEXT,
                    institution TEXT,
                    balance_minor INTEGER,
                    available_credit_minor INTEGER,
                    credit_limit_minor INTEGER,
                    availability_fetched_at INTEGER,
                    availability_sender TEXT,
                    created_at INTEGER NOT NULL
                )
                """.trimIndent(),
            )
            legacy.execSQL(
                "CREATE TABLE custom_categories (id INTEGER PRIMARY KEY, name TEXT, color_hex TEXT, created_at INTEGER)",
            )
            legacy.execSQL(
                """
                CREATE TABLE transactions (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    source_message_id TEXT NOT NULL UNIQUE,
                    amount_minor INTEGER NOT NULL,
                    merchant TEXT NOT NULL,
                    account_hint TEXT,
                    category TEXT NOT NULL,
                    type TEXT NOT NULL,
                    occurred_at INTEGER NOT NULL,
                    source TEXT NOT NULL,
                    sender TEXT NOT NULL,
                    note TEXT,
                    account_id INTEGER,
                    custom_category_id INTEGER,
                    tags TEXT NOT NULL DEFAULT '',
                    review_status TEXT NOT NULL DEFAULT 'CONFIRMED',
                    review_reason TEXT,
                    original_amount_minor INTEGER,
                    original_currency TEXT,
                    exchange_rate REAL,
                    raw_message_cipher BLOB,
                    duplicate_count INTEGER NOT NULL DEFAULT 1,
                    dedupe_fingerprint TEXT,
                    created_at INTEGER NOT NULL
                )
                """.trimIndent(),
            )
            legacy.execSQL(
                "INSERT INTO accounts (id, identity_key, name, type, account_hint, institution, created_at) " +
                    "VALUES (7, 'BANK_ACCOUNT:hdfc:1234', 'Legacy salary', 'BANK_ACCOUNT', '1234', 'HDFC Bank', 1)",
            )
            legacy.execSQL(
                "INSERT INTO transactions (id, source_message_id, amount_minor, merchant, account_hint, " +
                    "category, type, occurred_at, source, sender, account_id, created_at) " +
                    "VALUES (9, 'legacy-sms', 5000, 'Legacy merchant', '1234', 'OTHER', 'EXPENSE', 2, " +
                    "'BANK', 'VK-HDFCBK', 7, 2)",
            )
            legacy.version = 10
        }

        database = PaisaLensDatabase(context)

        assertEquals("Legacy salary", database.getAccounts().single().name)
        val transaction = database.getTransactions().single()
        assertEquals(7L, transaction.accountId)
        assertEquals("Legacy salary", transaction.accountName)
        val columns = database.readableDatabase.rawQuery("PRAGMA table_info(accounts)", null).use { cursor ->
            buildSet {
                val nameIndex = cursor.getColumnIndexOrThrow("name")
                while (cursor.moveToNext()) add(cursor.getString(nameIndex))
            }
        }
        assertTrue("merged_into_account_id" in columns)
        val indexes = database.readableDatabase.rawQuery("PRAGMA index_list(accounts)", null).use { cursor ->
            buildSet {
                val nameIndex = cursor.getColumnIndexOrThrow("name")
                while (cursor.moveToNext()) add(cursor.getString(nameIndex))
            }
        }
        assertTrue("index_accounts_merge_parent" in indexes)
    }

    private fun cardTransaction(
        id: String,
        hint: String,
        sender: String,
        occurredAt: Long,
        merchant: String = "Merchant $id",
    ) = parsedTransaction(id, hint, sender, occurredAt, TransactionSource.CARD, merchant)

    private fun bankTransaction(
        id: String,
        hint: String,
        sender: String,
        occurredAt: Long,
    ) = parsedTransaction(id, hint, sender, occurredAt, TransactionSource.BANK, "Merchant $id")

    private fun parsedTransaction(
        id: String,
        hint: String,
        sender: String,
        occurredAt: Long,
        source: TransactionSource,
        merchant: String,
    ) = ParsedTransaction(
        sourceMessageId = id,
        amountMinor = 1_000,
        merchant = merchant,
        accountHint = hint,
        category = ExpenseCategory.OTHER,
        type = TransactionType.EXPENSE,
        occurredAt = occurredAt,
        source = source,
        sender = sender,
        rawMessage = "$id account $hint",
    )

    private fun accountRule(
        name: String,
        accountId: Long,
        category: ExpenseCategory,
        priority: Int = 10,
    ) = SmartCategoryRule(
        name = name,
        merchantPattern = "Shared Merchant",
        matchType = SmartRuleMatchType.EXACT,
        accountId = accountId,
        category = category,
        enabled = true,
        priority = priority,
        updatedAt = 0,
    )

    private fun parsedCardBill(
        id: String,
        identity: String,
        hint: String?,
        institution: String,
        sender: String,
        dueDate: Long,
    ) = ParsedCreditCardBill(
        sourceMessageId = id,
        cardIdentityKey = identity,
        accountHint = hint,
        institutionName = institution,
        totalDueMinor = 10_000,
        dueDateEpochDay = dueDate,
        detectedAt = dueDate,
        sender = sender,
        rawMessage = "$institution card $hint due",
    )

    private fun reconciliation(accountId: Long, id: Long, closing: Long) = MonthlyReconciliation(
        id = id,
        accountId = accountId,
        year = 2026,
        month = 8,
        closingBalanceMinor = closing,
        statementTransactionCount = 1,
        matchedTransactionCount = 1,
        status = ReconciliationStatus.RECONCILED,
        reconciledAt = 1_000,
        updatedAt = 1_000,
    )

    private fun commitment(accountId: Long, name: String) = PaymentCommitment(
        name = name,
        merchantKey = "shared mandate",
        kind = PaymentCommitmentKind.UPI_AUTOPAY,
        amountMinor = 1_000,
        nextDueEpochDay = 21_700,
        accountId = accountId,
        createdAt = 1_000,
        updatedAt = 1_000,
    )

    private companion object {
        const val DATABASE_NAME = "paisalens.db"
    }
}
