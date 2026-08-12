package com.paisalens.app.data.local

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteConstraintException
import android.database.sqlite.SQLiteOpenHelper
import com.paisalens.app.data.model.AccountAvailabilityUpdate
import com.paisalens.app.data.model.AccountBalanceSnapshot
import com.paisalens.app.data.model.AccountBalanceWriteResult
import com.paisalens.app.data.model.AccountProfile
import com.paisalens.app.data.model.AccountType
import com.paisalens.app.data.model.AuditAction
import com.paisalens.app.data.model.AuditEntityType
import com.paisalens.app.data.model.AuditEntityKey
import com.paisalens.app.data.model.AuditEvent
import com.paisalens.app.data.model.AuditUndoResult
import com.paisalens.app.data.model.CategoryBudget
import com.paisalens.app.data.model.CategorySelection
import com.paisalens.app.data.model.BillReminder
import com.paisalens.app.data.model.CustomCategory
import com.paisalens.app.data.model.ExpenseCategory
import com.paisalens.app.data.model.ExchangeRate
import com.paisalens.app.data.model.ExpenseSplit
import com.paisalens.app.data.model.ExpenseSplitStatus
import com.paisalens.app.data.model.LoanAccount
import com.paisalens.app.data.model.NetWorthItem
import com.paisalens.app.data.model.NetWorthKind
import com.paisalens.app.data.model.MerchantAliasRule
import com.paisalens.app.data.model.MerchantCategoryRule
import com.paisalens.app.data.model.MonthlyReconciliation
import com.paisalens.app.data.model.PaisaLensBackupSnapshot
import com.paisalens.app.data.model.PaymentCommitment
import com.paisalens.app.data.model.PaymentCommitmentKind
import com.paisalens.app.data.model.PaymentCommitmentSource
import com.paisalens.app.data.model.PaymentCommitmentStatus
import com.paisalens.app.data.model.PaymentFrequency
import com.paisalens.app.data.model.ParsedTransaction
import com.paisalens.app.data.model.ReviewStatus
import com.paisalens.app.data.model.ReconciliationStatus
import com.paisalens.app.data.model.TransactionRecord
import com.paisalens.app.data.model.TransactionAuditPayload
import com.paisalens.app.data.model.TransactionAuditPayloadCodec
import com.paisalens.app.data.model.TransactionLink
import com.paisalens.app.data.model.TransactionLinkType
import com.paisalens.app.data.model.TransactionSource
import com.paisalens.app.data.model.TransactionType
import com.paisalens.app.data.model.normalizedMerchantKey
import com.paisalens.app.data.model.orderAuditEventsForUndo
import com.paisalens.app.data.model.StatementImportResult
import com.paisalens.app.data.model.SmartCategoryRule
import com.paisalens.app.data.model.SmartRuleMatchType
import com.paisalens.app.data.model.SavingsContribution
import com.paisalens.app.data.model.SavingsGoal
import com.paisalens.app.data.model.SavingsGoalKind
import com.paisalens.app.data.model.ContributionFrequency
import com.paisalens.app.data.model.deduplicatedPaymentCommitments
import com.paisalens.app.data.model.findMatchingSmartCategoryRule
import com.paisalens.app.data.model.findAuditUndoConflict
import com.paisalens.app.data.model.findAuditUndoLedgerConflict
import com.paisalens.app.data.model.isAuditBatchEligibleForUndo
import com.paisalens.app.data.model.validateTransactionLink
import com.paisalens.app.data.model.validateTransactionTypeChange
import com.paisalens.app.data.model.expenseSplitStatus
import com.paisalens.app.data.model.validateExpenseSplits
import com.paisalens.app.data.model.encodeUserEnteredUpiBalanceSource
import com.paisalens.app.data.model.validateUserEnteredUpiBalance
import com.paisalens.app.sms.BankSmsSupport
import java.util.Locale
import java.util.UUID
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.util.Base64

class PaisaLensDatabase(context: Context) :
    SQLiteOpenHelper(context, DATABASE_NAME, null, DATABASE_VERSION) {

    override fun onConfigure(db: SQLiteDatabase) {
        super.onConfigure(db)
        db.setForeignKeyConstraintsEnabled(true)
        db.enableWriteAheadLogging()
    }

    override fun onCreate(db: SQLiteDatabase) {
        createAccountsTable(db)
        createCustomCategoriesTable(db)
        createMerchantAliasesTable(db)
        createLoansTable(db)
        createExchangeRatesTable(db)
        createFinancialPlanningTables(db)
        db.execSQL(
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
                account_id INTEGER REFERENCES accounts(id) ON DELETE SET NULL,
                custom_category_id INTEGER REFERENCES custom_categories(id) ON DELETE SET NULL,
                tags TEXT NOT NULL DEFAULT '',
                review_status TEXT NOT NULL DEFAULT 'CONFIRMED',
                review_reason TEXT,
                original_amount_minor INTEGER,
                original_currency TEXT,
                exchange_rate REAL,
                raw_message_cipher BLOB,
                created_at INTEGER NOT NULL
            )
            """.trimIndent(),
        )
        createTrustAccuracyTables(db)
        createSharedFinanceTables(db)
        createTransactionIndexes(db)
        createMerchantCategoriesTable(db)
        db.execSQL(
            """
            CREATE TABLE budgets (
                category TEXT PRIMARY KEY NOT NULL,
                limit_minor INTEGER NOT NULL CHECK(limit_minor >= 0)
            )
            """.trimIndent(),
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            db.execSQL("ALTER TABLE transactions ADD COLUMN note TEXT")
            createMerchantCategoriesTable(db)
        }
        if (oldVersion < 3) {
            createAccountsTable(db)
            createCustomCategoriesTable(db)
            db.execSQL("ALTER TABLE transactions ADD COLUMN account_id INTEGER REFERENCES accounts(id) ON DELETE SET NULL")
            db.execSQL("ALTER TABLE transactions ADD COLUMN custom_category_id INTEGER REFERENCES custom_categories(id) ON DELETE SET NULL")
            db.execSQL("ALTER TABLE transactions ADD COLUMN tags TEXT NOT NULL DEFAULT ''")
            db.execSQL("ALTER TABLE transactions ADD COLUMN review_status TEXT NOT NULL DEFAULT 'CONFIRMED'")
            db.execSQL("ALTER TABLE transactions ADD COLUMN review_reason TEXT")
            if (!hasColumn(db, "merchant_categories", "custom_category_id")) {
                db.execSQL("ALTER TABLE merchant_categories ADD COLUMN custom_category_id INTEGER")
            }
            createTransactionIndexes(db)
            backfillAccounts(db)
        }
        if (oldVersion < 4) {
            createMerchantAliasesTable(db)
            createLoansTable(db)
            createExchangeRatesTable(db)
            db.execSQL("ALTER TABLE transactions ADD COLUMN original_amount_minor INTEGER")
            db.execSQL("ALTER TABLE transactions ADD COLUMN original_currency TEXT")
            db.execSQL("ALTER TABLE transactions ADD COLUMN exchange_rate REAL")
        }
        if (oldVersion in 3..<5) {
            db.execSQL("ALTER TABLE accounts ADD COLUMN balance_minor INTEGER")
            db.execSQL("ALTER TABLE accounts ADD COLUMN available_credit_minor INTEGER")
            db.execSQL("ALTER TABLE accounts ADD COLUMN availability_fetched_at INTEGER")
            db.execSQL("ALTER TABLE accounts ADD COLUMN availability_sender TEXT")
        }
        if (oldVersion < 6) {
            if (oldVersion in 3..<6) {
                db.execSQL("ALTER TABLE accounts ADD COLUMN credit_limit_minor INTEGER")
            }
            createFinancialPlanningTables(db)
            backfillBalanceHistory(db)
        }
        if (oldVersion < 7) {
            createTrustAccuracyTables(db)
        }
        if (oldVersion < 8) {
            createSharedFinanceTables(db)
        }
        if (oldVersion < 9) {
            deduplicatePaymentCommitments(db)
            createPaymentCommitmentUniqueIndex(db)
        }
    }

    fun insertAll(items: List<Pair<ParsedTransaction, ByteArray>>): Int {
        if (items.isEmpty()) return 0
        val db = writableDatabase
        val merchantCategories = getMerchantCategoryMap(db)
        val merchantAliases = getMerchantAliasMap(db)
        val smartRules = getSmartCategoryRules(db)
        var inserted = 0
        db.beginTransaction()
        try {
            items.forEach { (item, encryptedBody) ->
                val canonicalMerchant = merchantAliases[normalizedMerchantKey(item.merchant)]?.canonicalName
                    ?: item.merchant
                val merchantRule = if (item.type == TransactionType.EXPENSE) {
                    merchantCategories[normalizedMerchantKey(canonicalMerchant)]
                } else {
                    null
                }
                val accountId = resolveAccountId(
                    db = db,
                    accountHint = item.accountHint,
                    source = item.source,
                    sender = item.sender,
                )
                val smartRule = if (merchantRule == null && item.type == TransactionType.EXPENSE) {
                    findMatchingSmartCategoryRule(
                        TransactionRecord(
                            sourceMessageId = item.sourceMessageId,
                            amountMinor = item.amountMinor,
                            merchant = canonicalMerchant,
                            accountHint = item.accountHint,
                            category = item.category,
                            type = item.type,
                            occurredAt = item.occurredAt,
                            source = item.source,
                            sender = item.sender,
                            accountId = accountId,
                        ),
                        smartRules,
                    )
                } else {
                    null
                }
                val values = transactionValues(
                    item,
                    encryptedBody,
                    accountId,
                    merchantRule,
                    smartRule,
                    canonicalMerchant,
                )
                val rowId = db.insertWithOnConflict("transactions", null, values, SQLiteDatabase.CONFLICT_IGNORE)
                if (rowId != -1L) {
                    inserted += 1
                }
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        return inserted
    }

    fun insertManual(record: TransactionRecord): Long {
        val canonical = getMerchantAliasMap(readableDatabase)[normalizedMerchantKey(record.merchant)]?.canonicalName
            ?: record.merchant
        val values = transactionValues(record.copy(merchant = canonical))
        val db = writableDatabase
        db.beginTransaction()
        return try {
            val id = db.insertOrThrow("transactions", null, values)
            insertAuditEvent(
                db,
                newAuditBatchId("manual"),
                "Add manual transaction",
                AuditEntityType.TRANSACTION,
                id.toString(),
                AuditAction.INSERT,
                null,
                requireNotNull(transactionAuditPayload(db, id)),
            )
            db.setTransactionSuccessful()
            id
        } finally {
            db.endTransaction()
        }
    }

    fun insertImported(records: List<TransactionRecord>): StatementImportResult {
        if (records.isEmpty()) return StatementImportResult(0, 0)
        val db = writableDatabase
        val merchantCategories = getMerchantCategoryMap(db)
        val merchantAliases = getMerchantAliasMap(db)
        val smartRules = getSmartCategoryRules(db)
        val auditBatchId = newAuditBatchId("statement-import")
        var inserted = 0
        db.beginTransaction()
        try {
            records.forEach { original ->
                val canonical = merchantAliases[normalizedMerchantKey(original.merchant)]?.canonicalName
                    ?: original.merchant
                val rule = merchantCategories[normalizedMerchantKey(canonical)]
                val smartRule = if (
                    rule == null && original.type == TransactionType.EXPENSE &&
                    original.category == ExpenseCategory.OTHER && original.customCategoryId == null
                ) {
                    findMatchingSmartCategoryRule(original.copy(merchant = canonical), smartRules)
                } else {
                    null
                }
                val record = original.copy(
                    merchant = canonical,
                    category = rule?.category ?: smartRule?.category ?: original.category,
                    customCategoryId = rule?.customCategoryId ?: smartRule?.customCategoryId ?: original.customCategoryId,
                    reviewStatus = if (rule != null || smartRule != null) ReviewStatus.CONFIRMED else original.reviewStatus,
                    reviewReason = if (rule != null || smartRule != null) null else original.reviewReason,
                )
                val rowId = db.insertWithOnConflict(
                    "transactions",
                    null,
                    transactionValues(record),
                    SQLiteDatabase.CONFLICT_IGNORE,
                )
                if (rowId != -1L) {
                    inserted += 1
                    insertAuditEvent(
                        db,
                        auditBatchId,
                        "Import statement transactions",
                        AuditEntityType.TRANSACTION,
                        rowId.toString(),
                        AuditAction.INSERT,
                        null,
                        requireNotNull(transactionAuditPayload(db, rowId)),
                    )
                }
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        return StatementImportResult(inserted, records.size - inserted)
    }

    fun getTransactions(): List<TransactionRecord> {
        val result = mutableListOf<TransactionRecord>()
        readableDatabase.rawQuery(
            """
            SELECT
                t.id, t.source_message_id, t.amount_minor, t.merchant, t.account_hint,
                t.category, t.type, t.occurred_at, t.source, t.sender, t.note,
                t.account_id, a.name, t.custom_category_id, c.name, t.tags,
                t.review_status, t.review_reason, t.original_amount_minor,
                t.original_currency, t.exchange_rate
            FROM transactions t
            LEFT JOIN accounts a ON a.id = t.account_id
            LEFT JOIN custom_categories c ON c.id = t.custom_category_id
            ORDER BY t.occurred_at DESC, t.id DESC
            """.trimIndent(),
            null,
        ).use { cursor ->
            while (cursor.moveToNext()) {
                result += TransactionRecord(
                    id = cursor.getLong(0),
                    sourceMessageId = cursor.getString(1),
                    amountMinor = cursor.getLong(2),
                    merchant = cursor.getString(3),
                    accountHint = cursor.getString(4),
                    category = enumValueOrDefault(cursor.getString(5), ExpenseCategory.OTHER),
                    type = enumValueOrDefault(cursor.getString(6), TransactionType.EXPENSE),
                    occurredAt = cursor.getLong(7),
                    source = enumValueOrDefault(cursor.getString(8), TransactionSource.BANK),
                    sender = cursor.getString(9),
                    note = cursor.getString(10),
                    accountId = cursor.longOrNull(11),
                    accountName = cursor.getString(12),
                    customCategoryId = cursor.longOrNull(13),
                    customCategoryName = cursor.getString(14),
                    tags = decodeTags(cursor.getString(15)),
                    reviewStatus = enumValueOrDefault(cursor.getString(16), ReviewStatus.CONFIRMED),
                    reviewReason = cursor.getString(17),
                    originalAmountMinor = cursor.longOrNull(18),
                    originalCurrency = cursor.getString(19),
                    exchangeRate = cursor.doubleOrNull(20),
                )
            }
        }
        return result
    }

    fun updateCategory(id: Long, selection: CategorySelection): Int {
        val values = categoryValues(selection)
        return auditTransactionUpdate(id, "Update transaction category", values)
    }

    fun updateMerchantCategory(merchant: String, selection: CategorySelection): Int {
        val cleanMerchant = getMerchantAliasMap(readableDatabase)[normalizedMerchantKey(merchant)]?.canonicalName
            ?: merchant.trim()
        val merchantKey = normalizedMerchantKey(cleanMerchant)
        if (merchantKey.isBlank()) return 0

        val db = writableDatabase
        var updated = 0
        db.beginTransaction()
        try {
            val mapping = ContentValues().apply {
                put("merchant_key", merchantKey)
                put("merchant_name", cleanMerchant)
                put("category", selection.builtIn.name)
                putNullableLong("custom_category_id", selection.customCategoryId)
                put("updated_at", System.currentTimeMillis())
            }
            db.insertWithOnConflict("merchant_categories", null, mapping, SQLiteDatabase.CONFLICT_REPLACE)

            val matchingIds = mutableListOf<Long>()
            db.query(
                "transactions",
                arrayOf("id", "merchant", "type"),
                null,
                null,
                null,
                null,
                null,
            ).use { cursor ->
                while (cursor.moveToNext()) {
                    if (
                        enumValueOrDefault(cursor.getString(2), TransactionType.EXPENSE) ==
                        TransactionType.EXPENSE && normalizedMerchantKey(cursor.getString(1)) == merchantKey
                    ) {
                        matchingIds += cursor.getLong(0)
                    }
                }
            }

            val beforePayloadById = matchingIds.associateWith { id ->
                requireNotNull(transactionAuditPayload(db, id))
            }
            val values = categoryValues(selection)
            val batchId = newAuditBatchId("merchant-category")
            matchingIds.forEach { id ->
                val count = db.update("transactions", values, "id = ?", arrayOf(id.toString()))
                updated += count
                if (count > 0) {
                    insertAuditEvent(
                        db,
                        batchId,
                        "Categorize ${cleanMerchant.take(48)} transactions",
                        AuditEntityType.TRANSACTION,
                        id.toString(),
                        AuditAction.UPDATE,
                        beforePayloadById.getValue(id),
                        requireNotNull(transactionAuditPayload(db, id)),
                    )
                }
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        return updated
    }

    fun updateNote(id: Long, note: String) {
        val values = ContentValues().apply {
            val trimmed = note.trim().take(160)
            if (trimmed.isBlank()) putNull("note") else put("note", trimmed)
        }
        auditTransactionUpdate(id, "Update transaction note", values)
    }

    fun updateTags(id: Long, tags: List<String>) {
        val values = ContentValues().apply { put("tags", encodeTags(tags)) }
        auditTransactionUpdate(id, "Update transaction tags", values)
    }

    fun updateReviewStatus(id: Long, status: ReviewStatus) {
        val values = ContentValues().apply {
            put("review_status", status.name)
            if (status == ReviewStatus.CONFIRMED) putNull("review_reason")
        }
        auditTransactionUpdate(id, "Update transaction review status", values)
    }

    fun updateAccount(id: Long, accountId: Long?) {
        val values = ContentValues().apply { putNullableLong("account_id", accountId) }
        auditTransactionUpdate(id, "Update transaction account", values)
    }

    fun updateTransactionType(id: Long, type: TransactionType) {
        val values = ContentValues().apply {
            put("type", type.name)
            when (type) {
                TransactionType.INCOME -> {
                    put("category", ExpenseCategory.INCOME.name)
                    putNull("custom_category_id")
                }
                TransactionType.TRANSFER -> {
                    put("category", ExpenseCategory.TRANSFER.name)
                    putNull("custom_category_id")
                }
                TransactionType.EXPENSE, TransactionType.REFUND -> Unit
            }
        }
        auditTransactionUpdate(id, "Update transaction type", values) { db ->
            val validation = validateTransactionTypeChange(
                transactionId = id,
                newType = type,
                transactions = getTransactions(),
                links = getTransactionLinks(db),
                expenseSplits = getExpenseSplits(db),
            )
            require(validation.isValid) {
                "Transaction type would invalidate a financial link: ${validation.issue}"
            }
        }
    }

    fun getCategorizedMerchantKeys(): Set<String> = getMerchantCategoryMap(readableDatabase).keys

    fun getMerchantRules(): List<MerchantCategoryRule> = getMerchantCategoryMap(readableDatabase).values.toList()

    fun getMerchantAliases(): List<MerchantAliasRule> = getMerchantAliasMap(readableDatabase).values
        .sortedBy { it.aliasName.lowercase(Locale.ROOT) }

    fun renameMerchant(aliasName: String, canonicalName: String): Int {
        val aliasKey = normalizedMerchantKey(aliasName)
        val cleanCanonical = canonicalName.trim().replace(Regex("\\s+"), " ").take(64)
        require(aliasKey.isNotBlank() && cleanCanonical.isNotBlank()) { "Merchant names cannot be empty" }
        val db = writableDatabase
        var updated = 0
        db.beginTransaction()
        try {
            db.insertWithOnConflict(
                "merchant_aliases",
                null,
                ContentValues().apply {
                    put("alias_key", aliasKey)
                    put("alias_name", aliasName.trim().take(64))
                    put("canonical_name", cleanCanonical)
                    put("updated_at", System.currentTimeMillis())
                },
                SQLiteDatabase.CONFLICT_REPLACE,
            )
            val chainedAliases = mutableListOf<String>()
            db.query("merchant_aliases", arrayOf("alias_key", "canonical_name"), null, null, null, null, null).use { cursor ->
                while (cursor.moveToNext()) {
                    if (cursor.getString(0) != aliasKey && normalizedMerchantKey(cursor.getString(1)) == aliasKey) {
                        chainedAliases += cursor.getString(0)
                    }
                }
            }
            chainedAliases.forEach { chainedKey ->
                db.update(
                    "merchant_aliases",
                    ContentValues().apply { put("canonical_name", cleanCanonical); put("updated_at", System.currentTimeMillis()) },
                    "alias_key = ?",
                    arrayOf(chainedKey),
                )
            }
            val categoryRules = getMerchantCategoryMap(db)
            val canonicalKey = normalizedMerchantKey(cleanCanonical)
            if (canonicalKey !in categoryRules) {
                categoryRules[aliasKey]?.let { rule ->
                    db.insertWithOnConflict(
                        "merchant_categories",
                        null,
                        ContentValues().apply {
                            put("merchant_key", canonicalKey)
                            put("merchant_name", cleanCanonical)
                            put("category", rule.category.name)
                            putNullableLong("custom_category_id", rule.customCategoryId)
                            put("updated_at", System.currentTimeMillis())
                        },
                        SQLiteDatabase.CONFLICT_REPLACE,
                    )
                }
            }
            val matches = mutableListOf<Long>()
            val beforePayloadById = getTransactions()
                .filter { normalizedMerchantKey(it.merchant) == aliasKey }
                .associate { it.id to requireNotNull(transactionAuditPayload(db, it.id)) }
            db.query("transactions", arrayOf("id", "merchant"), null, null, null, null, null).use { cursor ->
                while (cursor.moveToNext()) {
                    if (normalizedMerchantKey(cursor.getString(1)) == aliasKey) matches += cursor.getLong(0)
                }
            }
            val auditBatchId = newAuditBatchId("merchant-rename")
            matches.forEach { id ->
                val count = db.update(
                    "transactions",
                    ContentValues().apply { put("merchant", cleanCanonical) },
                    "id = ?",
                    arrayOf(id.toString()),
                )
                updated += count
                if (count > 0) {
                    beforePayloadById[id]?.let { beforePayload ->
                        insertAuditEvent(
                            db,
                            auditBatchId,
                            "Rename merchant to ${cleanCanonical.take(48)}",
                            AuditEntityType.TRANSACTION,
                            id.toString(),
                            AuditAction.UPDATE,
                            beforePayload,
                            requireNotNull(transactionAuditPayload(db, id)),
                        )
                    }
                }
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        return updated
    }

    fun deleteMerchantAlias(aliasKey: String) {
        writableDatabase.delete("merchant_aliases", "alias_key = ?", arrayOf(aliasKey))
    }

    fun deleteTransaction(id: Long) {
        val db = writableDatabase
        val beforePayload = transactionAuditPayload(db, id) ?: return
        db.beginTransaction()
        try {
            val relatedSplitCount = db.rawQuery(
                """
                SELECT COUNT(*) FROM expense_splits
                WHERE transaction_id = ? OR linked_incoming_transaction_id = ?
                """.trimIndent(),
                arrayOf(id.toString(), id.toString()),
            ).use { cursor -> cursor.moveToFirst(); cursor.getInt(0) }
            require(relatedSplitCount == 0) {
                "Remove this transaction from its expense splits before deleting it"
            }
            val batchId = newAuditBatchId("transaction-delete")
            val linked = getTransactionLinks(db).filter {
                it.sourceTransactionId == id || it.targetTransactionId == id
            }
            linked.forEach { link ->
                insertAuditEvent(
                    db,
                    batchId,
                    "Delete transaction",
                    AuditEntityType.TRANSACTION_LINK,
                    link.id.toString(),
                    AuditAction.DELETE,
                    encodeTransactionLinkAuditPayload(link),
                    null,
                )
            }
            db.delete("transactions", "id = ?", arrayOf(id.toString()))
            insertAuditEvent(
                db,
                batchId,
                "Delete transaction",
                AuditEntityType.TRANSACTION,
                id.toString(),
                AuditAction.DELETE,
                beforePayload,
                null,
            )
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun getAccounts(): List<AccountProfile> {
        val result = mutableListOf<AccountProfile>()
        readableDatabase.query(
            "accounts",
            arrayOf(
                "id", "name", "type", "account_hint", "institution", "balance_minor",
                "available_credit_minor", "credit_limit_minor", "availability_fetched_at", "availability_sender",
                "identity_key",
            ),
            null,
            null,
            null,
            null,
            "name COLLATE NOCASE ASC",
        ).use { cursor ->
            while (cursor.moveToNext()) {
                result += AccountProfile(
                    id = cursor.getLong(0),
                    name = cursor.getString(1),
                    type = enumValueOrDefault(cursor.getString(2), AccountType.OTHER),
                    accountHint = cursor.getString(3),
                    institution = cursor.getString(4),
                    balanceMinor = cursor.longOrNull(5),
                    availableCreditMinor = cursor.longOrNull(6),
                    creditLimitMinor = cursor.longOrNull(7),
                    availabilityFetchedAt = cursor.longOrNull(8),
                    availabilitySender = cursor.getString(9),
                    identityKey = cursor.getString(10),
                )
            }
        }
        return result
    }

    fun addAccount(name: String, type: AccountType, accountHint: String?): Long {
        val cleanHint = accountHint?.filter(Char::isDigit)?.takeLast(4)?.takeIf(String::isNotBlank)
        val values = ContentValues().apply {
            put("identity_key", "manual:${UUID.randomUUID()}")
            put("name", name.trim().take(48))
            put("type", type.name)
            put("account_hint", cleanHint)
            putNull("institution")
            put("created_at", System.currentTimeMillis())
        }
        return writableDatabase.insertOrThrow("accounts", null, values)
    }

    fun updateAccountProfile(account: AccountProfile) {
        val values = ContentValues().apply {
            put("name", account.name.trim().take(48))
            put("type", account.type.name)
            val cleanHint = account.accountHint?.filter(Char::isDigit)?.takeLast(4)?.takeIf(String::isNotBlank)
            if (cleanHint == null) putNull("account_hint") else put("account_hint", cleanHint)
            putNullableLong("credit_limit_minor", account.creditLimitMinor?.coerceAtLeast(0))
        }
        writableDatabase.update("accounts", values, "id = ?", arrayOf(account.id.toString()))
    }

    fun applyAccountAvailability(updates: List<AccountAvailabilityUpdate>): Int {
        if (updates.isEmpty()) return 0
        val db = writableDatabase
        var changed = 0
        db.beginTransaction()
        try {
            updates.sortedBy { it.fetchedAt }.forEach { update ->
                val accountId = findAvailabilityAccountId(db, update) ?: createAvailabilityAccount(db, update)
                if (accountId != null) {
                    insertBalanceSnapshot(db, accountId, update)
                    if (applyAccountAvailability(db, accountId, update)) changed += 1
                }
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        return changed
    }

    fun recordUserEnteredUpiBalance(
        accountId: Long,
        balanceMinor: Long,
        recordedAt: Long = System.currentTimeMillis(),
        sourceLabel: String? = null,
    ): AccountBalanceWriteResult {
        val db = writableDatabase
        db.beginTransaction()
        try {
            val accountType = db.query(
                "accounts",
                arrayOf("type"),
                "id = ?",
                arrayOf(accountId.toString()),
                null,
                null,
                null,
                "1",
            ).use { cursor ->
                if (cursor.moveToFirst()) {
                    enumValueOrDefault(cursor.getString(0), AccountType.OTHER)
                } else {
                    null
                }
            }
            requireNotNull(accountType) { "Bank account not found" }
            validateUserEnteredUpiBalance(
                accountId = accountId,
                accountType = accountType,
                balanceMinor = balanceMinor,
                recordedAt = recordedAt,
            )
            val encodedSource = encodeUserEnteredUpiBalanceSource(sourceLabel)

            val snapshotId = db.insertWithOnConflict(
                "balance_history",
                null,
                ContentValues().apply {
                    put("account_id", accountId)
                    put("balance_minor", balanceMinor)
                    putNull("available_credit_minor")
                    putNull("credit_limit_minor")
                    put("recorded_at", recordedAt)
                    put("sender", encodedSource)
                },
                SQLiteDatabase.CONFLICT_IGNORE,
            )
            val currentUpdated = db.update(
                "accounts",
                ContentValues().apply {
                    put("balance_minor", balanceMinor)
                    put("availability_fetched_at", recordedAt)
                    put("availability_sender", encodedSource)
                },
                "id = ? AND type = ? AND (availability_fetched_at IS NULL OR availability_fetched_at < ?)",
                arrayOf(accountId.toString(), AccountType.BANK_ACCOUNT.name, recordedAt.toString()),
            ) > 0
            db.setTransactionSuccessful()
            return AccountBalanceWriteResult(
                snapshotRecorded = snapshotId != -1L,
                currentBalanceUpdated = currentUpdated,
            )
        } finally {
            db.endTransaction()
        }
    }

    fun deleteAccount(id: Long) {
        writableDatabase.delete("accounts", "id = ?", arrayOf(id.toString()))
    }

    fun getBalanceHistory(accountId: Long? = null): List<AccountBalanceSnapshot> {
        val result = mutableListOf<AccountBalanceSnapshot>()
        readableDatabase.query(
            "balance_history",
            arrayOf(
                "id", "account_id", "balance_minor", "available_credit_minor",
                "credit_limit_minor", "recorded_at", "sender",
            ),
            accountId?.let { "account_id = ?" },
            accountId?.let { arrayOf(it.toString()) },
            null,
            null,
            "recorded_at DESC, id DESC",
        ).use { cursor ->
            while (cursor.moveToNext()) {
                result += AccountBalanceSnapshot(
                    id = cursor.getLong(0),
                    accountId = cursor.getLong(1),
                    balanceMinor = cursor.longOrNull(2),
                    availableCreditMinor = cursor.longOrNull(3),
                    creditLimitMinor = cursor.longOrNull(4),
                    recordedAt = cursor.getLong(5),
                    sender = cursor.getString(6),
                )
            }
        }
        return result
    }

    fun getExpenseSplits(transactionId: Long? = null): List<ExpenseSplit> =
        getExpenseSplits(readableDatabase, transactionId)

    private fun getExpenseSplits(db: SQLiteDatabase, transactionId: Long? = null): List<ExpenseSplit> {
        val result = mutableListOf<ExpenseSplit>()
        db.query(
            "expense_splits",
            arrayOf(
                "id", "transaction_id", "participant_name", "share_minor", "reimbursed_minor",
                "linked_incoming_transaction_id", "note", "status", "created_at", "updated_at",
            ),
            transactionId?.let { "transaction_id = ?" },
            transactionId?.let { arrayOf(it.toString()) },
            null,
            null,
            "transaction_id ASC, participant_name COLLATE NOCASE ASC, id ASC",
        ).use { cursor ->
            while (cursor.moveToNext()) {
                result += ExpenseSplit(
                    id = cursor.getLong(0),
                    transactionId = cursor.getLong(1),
                    participantName = cursor.getString(2),
                    shareMinor = cursor.getLong(3),
                    reimbursedMinor = cursor.getLong(4),
                    linkedIncomingTransactionId = cursor.longOrNull(5),
                    note = cursor.getString(6),
                    status = enumValueOrDefault(cursor.getString(7), ExpenseSplitStatus.OPEN),
                    createdAt = cursor.getLong(8),
                    updatedAt = cursor.getLong(9),
                )
            }
        }
        return result
    }

    fun upsertExpenseSplit(split: ExpenseSplit): Long {
        val db = writableDatabase
        db.beginTransaction()
        return try {
            val transactions = getTransactions()
            val transaction = transactions.firstOrNull { it.id == split.transactionId }
            val clean = split.copy(
                participantName = split.participantName.trim().replace(Regex("\\s+"), " ").take(48),
                note = split.note?.trim()?.replace(Regex("\\s+"), " ")?.take(240)?.takeIf(String::isNotBlank),
                status = expenseSplitStatus(split.shareMinor, split.reimbursedMinor),
                createdAt = split.createdAt.takeIf { it > 0 } ?: System.currentTimeMillis(),
                updatedAt = System.currentTimeMillis(),
            )
            val siblingSplits = getExpenseSplits(db, split.transactionId).filter { it.id != split.id }
            val validation = validateExpenseSplits(transaction, siblingSplits + clean, transactions.associateBy(TransactionRecord::id))
            require(validation.isValid) { "Expense split is invalid: ${validation.issue}" }
            clean.linkedIncomingTransactionId?.let { incomingId ->
                val duplicate = db.rawQuery(
                    "SELECT id FROM expense_splits WHERE linked_incoming_transaction_id = ? AND id != ? LIMIT 1",
                    arrayOf(incomingId.toString(), split.id.toString()),
                ).use { it.moveToFirst() }
                require(!duplicate) { "Incoming reimbursement is already assigned to another split" }
            }
            val oldLink = split.id.takeIf { it > 0 }?.let { findOwnedSplitLink(db, it) }
            val baseValues = expenseSplitValues(clean).apply {
                putNull("transaction_link_id")
                put("owns_transaction_link", 0)
                putNull("linked_incoming_transaction_id")
            }
            val id = if (split.id == 0L) {
                db.insertOrThrow("expense_splits", null, baseValues)
            } else {
                db.update("expense_splits", baseValues, "id = ?", arrayOf(split.id.toString()))
                split.id
            }
            if (oldLink != null && oldLink.targetTransactionId != clean.linkedIncomingTransactionId) {
                deleteOwnedSplitLink(db, id, oldLink)
            }
            clean.linkedIncomingTransactionId?.let { incomingId ->
                val currentOwned = oldLink?.takeIf { it.targetTransactionId == incomingId }
                val exactExisting = currentOwned ?: findExactReimbursementLink(db, clean.transactionId, incomingId)
                val (linkId, ownsLink) = if (exactExisting != null) {
                    exactExisting.id to (currentOwned != null)
                } else {
                    val candidate = TransactionLink(
                        sourceTransactionId = clean.transactionId,
                        targetTransactionId = incomingId,
                        type = TransactionLinkType.REIMBURSEMENT,
                        note = splitLinkNote(id),
                        createdAt = clean.updatedAt,
                    )
                    val linkValidation = validateTransactionLink(
                        candidate,
                        getTransactionLinks(db),
                        transactions.mapTo(mutableSetOf(), TransactionRecord::id),
                        transactions.associateBy(TransactionRecord::id),
                    )
                    require(linkValidation.isValid) { "Reimbursement link is invalid: ${linkValidation.issue}" }
                    db.insertOrThrow("transaction_links", null, transactionLinkValues(candidate)) to true
                }
                db.update(
                    "expense_splits",
                    ContentValues().apply {
                        put("linked_incoming_transaction_id", incomingId)
                        put("transaction_link_id", linkId)
                        put("owns_transaction_link", if (ownsLink) 1 else 0)
                    },
                    "id = ?",
                    arrayOf(id.toString()),
                )
            }
            db.setTransactionSuccessful()
            id
        } finally {
            db.endTransaction()
        }
    }

    /**
     * Applies a split-editor save as one ledger operation. Validation runs against the
     * complete post-edit allocation before any row or analytics link is mutated.
     */
    fun replaceExpenseSplits(
        transactionId: Long,
        splits: List<ExpenseSplit>,
        deletedIds: Set<Long> = emptySet(),
    ): List<Long> {
        require(transactionId > 0) { "Expense transaction is required" }
        require(splits.all { it.transactionId == transactionId }) { "Every split must belong to the edited transaction" }
        require(splits.filter { it.id > 0 }.map(ExpenseSplit::id).distinct().size == splits.count { it.id > 0 }) {
            "An expense split cannot appear more than once"
        }
        val db = writableDatabase
        db.beginTransaction()
        return try {
            val transactions = getTransactions()
            val transaction = transactions.firstOrNull { it.id == transactionId }
            val existing = getExpenseSplits(db, transactionId)
            val existingIds = existing.mapTo(mutableSetOf(), ExpenseSplit::id)
            require(deletedIds.all { it in existingIds }) { "A deleted split does not belong to this expense" }
            require(splits.none { it.id > 0 && it.id !in existingIds }) { "A saved split does not belong to this expense" }
            require(splits.none { it.id in deletedIds }) { "A split cannot be saved and deleted together" }
            val now = System.currentTimeMillis()
            val cleanUpserts = splits.map { split ->
                split.copy(
                    participantName = split.participantName.trim().replace(Regex("\\s+"), " ").take(48),
                    note = split.note?.trim()?.replace(Regex("\\s+"), " ")?.take(240)?.takeIf(String::isNotBlank),
                    status = expenseSplitStatus(split.shareMinor, split.reimbursedMinor),
                    createdAt = split.createdAt.takeIf { it > 0 } ?: now,
                    updatedAt = now,
                )
            }
            val upsertIds = cleanUpserts.filter { it.id > 0 }.mapTo(mutableSetOf(), ExpenseSplit::id)
            val finalSplits = existing.filter { it.id !in deletedIds && it.id !in upsertIds } + cleanUpserts
            val validation = validateExpenseSplits(transaction, finalSplits, transactions.associateBy(TransactionRecord::id))
            require(validation.isValid) { "Expense split set is invalid: ${validation.issue}" }
            val linkedIncomingIds = finalSplits.mapNotNull(ExpenseSplit::linkedIncomingTransactionId)
            require(linkedIncomingIds.size == linkedIncomingIds.distinct().size) {
                "Incoming reimbursement is assigned to more than one split"
            }
            linkedIncomingIds.forEach { incomingId ->
                val conflict = db.rawQuery(
                    "SELECT id FROM expense_splits WHERE linked_incoming_transaction_id = ? AND transaction_id != ? LIMIT 1",
                    arrayOf(incomingId.toString(), transactionId.toString()),
                ).use { it.moveToFirst() }
                require(!conflict) { "Incoming reimbursement is already assigned to another split" }
            }

            deletedIds.forEach { id ->
                findOwnedSplitLink(db, id)?.let { deleteOwnedSplitLink(db, id, it) }
                db.delete("expense_splits", "id = ? AND transaction_id = ?", arrayOf(id.toString(), transactionId.toString()))
            }

            // Stage every edited row without a reimbursement reference first. This makes
            // reallocating one incoming credit between participants safe regardless of UI order.
            val staged = mutableListOf<Triple<ExpenseSplit, Long, SplitOwnedLink?>>()
            cleanUpserts.forEach { split ->
                val oldLink = split.id.takeIf { it > 0 }?.let { findOwnedSplitLink(db, it) }
                val baseValues = expenseSplitValues(split).apply {
                    putNull("transaction_link_id")
                    put("owns_transaction_link", 0)
                    putNull("linked_incoming_transaction_id")
                }
                val id = if (split.id == 0L) {
                    db.insertOrThrow("expense_splits", null, baseValues)
                } else {
                    val updated = db.update(
                        "expense_splits",
                        baseValues,
                        "id = ? AND transaction_id = ?",
                        arrayOf(split.id.toString(), transactionId.toString()),
                    )
                    require(updated == 1) { "Expense split changed before it could be saved" }
                    split.id
                }
                staged += Triple(split, id, oldLink)
            }
            staged.forEach { (split, id, oldLink) ->
                if (oldLink != null && oldLink.targetTransactionId != split.linkedIncomingTransactionId) {
                    deleteOwnedSplitLink(db, id, oldLink)
                }
            }
            staged.forEach { (split, id, oldLink) ->
                split.linkedIncomingTransactionId?.let { incomingId ->
                    val currentOwned = oldLink?.takeIf { it.targetTransactionId == incomingId }
                    val exactExisting = currentOwned ?: findExactReimbursementLink(db, transactionId, incomingId)
                    val (linkId, ownsLink) = if (exactExisting != null) {
                        exactExisting.id to (currentOwned != null)
                    } else {
                        val candidate = TransactionLink(
                            sourceTransactionId = transactionId,
                            targetTransactionId = incomingId,
                            type = TransactionLinkType.REIMBURSEMENT,
                            note = splitLinkNote(id),
                            createdAt = now,
                        )
                        val linkValidation = validateTransactionLink(
                            candidate,
                            getTransactionLinks(db),
                            transactions.mapTo(mutableSetOf(), TransactionRecord::id),
                            transactions.associateBy(TransactionRecord::id),
                        )
                        require(linkValidation.isValid) { "Reimbursement link is invalid: ${linkValidation.issue}" }
                        db.insertOrThrow("transaction_links", null, transactionLinkValues(candidate)) to true
                    }
                    db.update(
                        "expense_splits",
                        ContentValues().apply {
                            put("linked_incoming_transaction_id", incomingId)
                            put("transaction_link_id", linkId)
                            put("owns_transaction_link", if (ownsLink) 1 else 0)
                        },
                        "id = ?",
                        arrayOf(id.toString()),
                    )
                }
            }
            db.setTransactionSuccessful()
            staged.map { it.second }
        } finally {
            db.endTransaction()
        }
    }

    fun deleteExpenseSplit(id: Long) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            findOwnedSplitLink(db, id)?.let { deleteOwnedSplitLink(db, id, it) }
            db.delete("expense_splits", "id = ?", arrayOf(id.toString()))
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    private data class SplitOwnedLink(val id: Long, val sourceTransactionId: Long, val targetTransactionId: Long, val note: String?)

    private data class AuditSplitDependency(
        val id: Long,
        val transactionId: Long,
        val linkedIncomingTransactionId: Long?,
        val transactionLinkId: Long?,
    ) {
        fun dependsOn(link: TransactionLink): Boolean =
            transactionLinkId == link.id || linkedIncomingTransactionId?.let { incomingId ->
                link.type == TransactionLinkType.REIMBURSEMENT &&
                    setOf(link.sourceTransactionId, link.targetTransactionId) == setOf(transactionId, incomingId)
            } == true
    }

    private fun getAuditSplitDependencies(db: SQLiteDatabase): List<AuditSplitDependency> {
        val result = mutableListOf<AuditSplitDependency>()
        db.query(
            "expense_splits",
            arrayOf("id", "transaction_id", "linked_incoming_transaction_id", "transaction_link_id"),
            null,
            null,
            null,
            null,
            "id ASC",
        ).use { cursor ->
            while (cursor.moveToNext()) {
                result += AuditSplitDependency(
                    id = cursor.getLong(0),
                    transactionId = cursor.getLong(1),
                    linkedIncomingTransactionId = cursor.longOrNull(2),
                    transactionLinkId = cursor.longOrNull(3),
                )
            }
        }
        return result
    }

    private fun findOwnedSplitLink(db: SQLiteDatabase, splitId: Long): SplitOwnedLink? = db.rawQuery(
        """
        SELECT l.id, l.source_transaction_id, l.target_transaction_id, l.note
        FROM expense_splits s JOIN transaction_links l ON l.id = s.transaction_link_id
        WHERE s.id = ? AND s.owns_transaction_link = 1
        """.trimIndent(),
        arrayOf(splitId.toString()),
    ).use { cursor ->
        if (!cursor.moveToFirst()) null else SplitOwnedLink(cursor.getLong(0), cursor.getLong(1), cursor.getLong(2), cursor.getString(3))
    }

    private fun findExactReimbursementLink(db: SQLiteDatabase, expenseId: Long, incomingId: Long): SplitOwnedLink? = db.rawQuery(
        """
        SELECT id, source_transaction_id, target_transaction_id, note FROM transaction_links
        WHERE ((source_transaction_id = ? AND target_transaction_id = ?)
            OR (source_transaction_id = ? AND target_transaction_id = ?))
            AND link_type = ? LIMIT 1
        """.trimIndent(),
        arrayOf(
            expenseId.toString(), incomingId.toString(),
            incomingId.toString(), expenseId.toString(),
            TransactionLinkType.REIMBURSEMENT.name,
        ),
    ).use { cursor ->
        if (!cursor.moveToFirst()) null else SplitOwnedLink(cursor.getLong(0), cursor.getLong(1), cursor.getLong(2), cursor.getString(3))
    }

    private fun deleteOwnedSplitLink(db: SQLiteDatabase, splitId: Long, link: SplitOwnedLink) {
        if (link.note == splitLinkNote(splitId)) {
            db.delete("transaction_links", "id = ? AND note = ?", arrayOf(link.id.toString(), splitLinkNote(splitId)))
        }
    }

    private fun splitLinkNote(splitId: Long): String = "expense-split:$splitId"

    private fun expenseSplitValues(split: ExpenseSplit, includeId: Boolean = false) = ContentValues().apply {
        if (includeId) put("id", split.id)
        put("transaction_id", split.transactionId)
        put("participant_name", split.participantName)
        put("share_minor", split.shareMinor)
        put("reimbursed_minor", split.reimbursedMinor)
        putNullableLong("linked_incoming_transaction_id", split.linkedIncomingTransactionId)
        putNullableText("note", split.note)
        put("status", expenseSplitStatus(split.shareMinor, split.reimbursedMinor).name)
        put("created_at", split.createdAt)
        put("updated_at", split.updatedAt)
    }

    fun getSavingsGoals(): List<SavingsGoal> {
        val result = mutableListOf<SavingsGoal>()
        readableDatabase.query(
            "savings_goals",
            arrayOf(
                "id", "name", "target_minor", "starting_saved_minor", "target_date_epoch_day",
                "linked_account_id", "kind", "contribution_frequency", "notes", "color_hex",
                "is_active", "created_at", "updated_at",
            ),
            null, null, null, null,
            "is_active DESC, target_date_epoch_day IS NULL, target_date_epoch_day ASC, name COLLATE NOCASE ASC",
        ).use { cursor ->
            while (cursor.moveToNext()) {
                result += SavingsGoal(
                    id = cursor.getLong(0),
                    name = cursor.getString(1),
                    targetMinor = cursor.getLong(2),
                    startingSavedMinor = cursor.getLong(3),
                    targetDateEpochDay = cursor.longOrNull(4),
                    linkedAccountId = cursor.longOrNull(5),
                    kind = enumValueOrDefault(cursor.getString(6), SavingsGoalKind.SAVINGS_GOAL),
                    contributionFrequency = enumValueOrDefault(cursor.getString(7), ContributionFrequency.MONTHLY),
                    notes = cursor.getString(8),
                    colorHex = cursor.getString(9),
                    isActive = cursor.getInt(10) != 0,
                    createdAt = cursor.getLong(11),
                    updatedAt = cursor.getLong(12),
                )
            }
        }
        return result
    }

    fun upsertSavingsGoal(goal: SavingsGoal): Long {
        require(goal.name.isNotBlank()) { "Savings goal name cannot be empty" }
        require(goal.targetMinor > 0) { "Savings goal target must be positive" }
        require(goal.startingSavedMinor >= 0) { "Starting savings cannot be negative" }
        val now = System.currentTimeMillis()
        val clean = goal.copy(
            name = goal.name.trim().replace(Regex("\\s+"), " ").take(64),
            notes = goal.notes?.trim()?.replace(Regex("\\s+"), " ")?.take(240)?.takeIf(String::isNotBlank),
            colorHex = normalizeColor(goal.colorHex),
            createdAt = goal.createdAt.takeIf { it > 0 } ?: now,
            updatedAt = now,
        )
        val values = savingsGoalValues(clean)
        return if (goal.id == 0L) {
            writableDatabase.insertOrThrow("savings_goals", null, values)
        } else {
            writableDatabase.update("savings_goals", values, "id = ?", arrayOf(goal.id.toString()))
            goal.id
        }
    }

    fun deleteSavingsGoal(id: Long) {
        writableDatabase.delete("savings_goals", "id = ?", arrayOf(id.toString()))
    }

    fun getSavingsContributions(goalId: Long? = null): List<SavingsContribution> {
        val result = mutableListOf<SavingsContribution>()
        readableDatabase.query(
            "savings_contributions",
            arrayOf("id", "goal_id", "amount_minor", "contributed_at", "note", "linked_transaction_id"),
            goalId?.let { "goal_id = ?" },
            goalId?.let { arrayOf(it.toString()) },
            null, null,
            "contributed_at DESC, id DESC",
        ).use { cursor ->
            while (cursor.moveToNext()) {
                result += SavingsContribution(
                    id = cursor.getLong(0),
                    goalId = cursor.getLong(1),
                    amountMinor = cursor.getLong(2),
                    contributedAt = cursor.getLong(3),
                    note = cursor.getString(4),
                    linkedTransactionId = cursor.longOrNull(5),
                )
            }
        }
        return result
    }

    fun upsertSavingsContribution(contribution: SavingsContribution): Long {
        require(contribution.amountMinor > 0) { "Savings contribution must be positive" }
        require(getSavingsGoals().any { it.id == contribution.goalId }) { "Savings goal does not exist" }
        val clean = contribution.copy(
            contributedAt = contribution.contributedAt.takeIf { it > 0 } ?: System.currentTimeMillis(),
            note = contribution.note?.trim()?.replace(Regex("\\s+"), " ")?.take(160)?.takeIf(String::isNotBlank),
        )
        val values = savingsContributionValues(clean)
        return if (contribution.id == 0L) {
            writableDatabase.insertOrThrow("savings_contributions", null, values)
        } else {
            writableDatabase.update("savings_contributions", values, "id = ?", arrayOf(contribution.id.toString()))
            contribution.id
        }
    }

    fun deleteSavingsContribution(id: Long) {
        writableDatabase.delete("savings_contributions", "id = ?", arrayOf(id.toString()))
    }

    private fun savingsGoalValues(goal: SavingsGoal, includeId: Boolean = false) = ContentValues().apply {
        if (includeId) put("id", goal.id)
        put("name", goal.name)
        put("target_minor", goal.targetMinor)
        put("starting_saved_minor", goal.startingSavedMinor)
        putNullableLong("target_date_epoch_day", goal.targetDateEpochDay)
        putNullableLong("linked_account_id", goal.linkedAccountId)
        put("kind", goal.kind.name)
        put("contribution_frequency", goal.contributionFrequency.name)
        putNullableText("notes", goal.notes)
        put("color_hex", normalizeColor(goal.colorHex))
        put("is_active", if (goal.isActive) 1 else 0)
        put("created_at", goal.createdAt)
        put("updated_at", goal.updatedAt)
    }

    private fun savingsContributionValues(contribution: SavingsContribution, includeId: Boolean = false) = ContentValues().apply {
        if (includeId) put("id", contribution.id)
        put("goal_id", contribution.goalId)
        put("amount_minor", contribution.amountMinor)
        put("contributed_at", contribution.contributedAt)
        putNullableText("note", contribution.note)
        putNullableLong("linked_transaction_id", contribution.linkedTransactionId)
    }

    fun getPaymentCommitments(): List<PaymentCommitment> = getPaymentCommitments(readableDatabase)

    private fun getPaymentCommitments(db: SQLiteDatabase): List<PaymentCommitment> {
        val result = mutableListOf<PaymentCommitment>()
        db.query(
            "payment_commitments",
            arrayOf(
                "id", "name", "merchant_key", "kind", "frequency", "custom_interval_days",
                "amount_minor", "max_mandate_minor", "next_due_epoch_day", "account_id", "upi_handle",
                "status", "source", "category_label", "notes", "created_at", "updated_at",
            ),
            null, null, null, null,
            "status ASC, next_due_epoch_day ASC, name COLLATE NOCASE ASC",
        ).use { cursor ->
            while (cursor.moveToNext()) {
                result += PaymentCommitment(
                    id = cursor.getLong(0),
                    name = cursor.getString(1),
                    merchantKey = cursor.getString(2),
                    kind = enumValueOrDefault(cursor.getString(3), PaymentCommitmentKind.SUBSCRIPTION),
                    frequency = enumValueOrDefault(cursor.getString(4), PaymentFrequency.MONTHLY),
                    customIntervalDays = cursor.intOrNull(5),
                    amountMinor = cursor.getLong(6),
                    maxMandateMinor = cursor.longOrNull(7),
                    nextDueEpochDay = cursor.getLong(8),
                    accountId = cursor.longOrNull(9),
                    upiHandle = cursor.getString(10),
                    status = enumValueOrDefault(cursor.getString(11), PaymentCommitmentStatus.ACTIVE),
                    source = enumValueOrDefault(cursor.getString(12), PaymentCommitmentSource.MANUAL),
                    categoryLabel = cursor.getString(13),
                    notes = cursor.getString(14),
                    createdAt = cursor.getLong(15),
                    updatedAt = cursor.getLong(16),
                )
            }
        }
        return result
    }

    fun upsertPaymentCommitment(commitment: PaymentCommitment): Long {
        require(commitment.name.isNotBlank()) { "Payment name cannot be empty" }
        require(commitment.amountMinor >= 0) { "Payment amount cannot be negative" }
        require(commitment.maxMandateMinor == null || commitment.maxMandateMinor >= commitment.amountMinor) {
            "Mandate maximum cannot be less than the expected amount"
        }
        require(commitment.frequency != PaymentFrequency.CUSTOM || (commitment.customIntervalDays ?: 0) > 0) {
            "Custom payment frequency requires a positive interval"
        }
        val now = System.currentTimeMillis()
        val cleanName = commitment.name.trim().replace(Regex("\\s+"), " ").take(64)
        val clean = commitment.copy(
            name = cleanName,
            merchantKey = normalizedMerchantKey(commitment.merchantKey.ifBlank { cleanName }),
            customIntervalDays = commitment.customIntervalDays?.coerceIn(1, 3_650),
            upiHandle = commitment.upiHandle?.trim()?.lowercase(Locale.ROOT)?.take(80)?.takeIf(String::isNotBlank),
            categoryLabel = commitment.categoryLabel?.trim()?.take(48)?.takeIf(String::isNotBlank),
            notes = commitment.notes?.trim()?.replace(Regex("\\s+"), " ")?.take(240)?.takeIf(String::isNotBlank),
            createdAt = commitment.createdAt.takeIf { it > 0 } ?: now,
            updatedAt = now,
        )
        val values = paymentCommitmentValues(clean)
        val db = writableDatabase
        db.beginTransaction()
        return try {
            val id = if (commitment.id == 0L) {
                db.insertOrThrow("payment_commitments", null, values)
            } else {
                val updated = db.update("payment_commitments", values, "id = ?", arrayOf(commitment.id.toString()))
                require(updated == 1) { "Payment commitment is unavailable" }
                commitment.id
            }
            db.setTransactionSuccessful()
            id
        } catch (error: SQLiteConstraintException) {
            if (error.message.orEmpty().contains("UNIQUE", ignoreCase = true)) {
                throw IllegalArgumentException(
                    "A ${clean.kind.name.lowercase().replace('_', ' ')} already exists for this merchant and account",
                    error,
                )
            }
            throw error
        } finally {
            db.endTransaction()
        }
    }

    fun deletePaymentCommitment(id: Long) {
        writableDatabase.delete("payment_commitments", "id = ?", arrayOf(id.toString()))
    }

    private fun paymentCommitmentValues(commitment: PaymentCommitment, includeId: Boolean = false) = ContentValues().apply {
        if (includeId) put("id", commitment.id)
        put("name", commitment.name)
        put("merchant_key", commitment.merchantKey)
        put("kind", commitment.kind.name)
        put("frequency", commitment.frequency.name)
        if (commitment.frequency == PaymentFrequency.CUSTOM) putNullableLong("custom_interval_days", commitment.customIntervalDays?.toLong())
        else putNull("custom_interval_days")
        put("amount_minor", commitment.amountMinor)
        putNullableLong("max_mandate_minor", commitment.maxMandateMinor)
        put("next_due_epoch_day", commitment.nextDueEpochDay)
        putNullableLong("account_id", commitment.accountId)
        putNullableText("upi_handle", commitment.upiHandle)
        put("status", commitment.status.name)
        put("source", commitment.source.name)
        putNullableText("category_label", commitment.categoryLabel)
        putNullableText("notes", commitment.notes)
        put("created_at", commitment.createdAt)
        put("updated_at", commitment.updatedAt)
    }

    fun getBills(): List<BillReminder> {
        val result = mutableListOf<BillReminder>()
        readableDatabase.query(
            "bills",
            arrayOf(
                "id", "title", "amount_minor", "due_date_epoch_day", "recurrence_months",
                "account_id", "notes", "is_active", "last_paid_epoch_day",
            ),
            null,
            null,
            null,
            null,
            "is_active DESC, due_date_epoch_day ASC, title COLLATE NOCASE ASC",
        ).use { cursor ->
            while (cursor.moveToNext()) {
                result += BillReminder(
                    id = cursor.getLong(0),
                    title = cursor.getString(1),
                    amountMinor = cursor.getLong(2),
                    dueDateEpochDay = cursor.getLong(3),
                    recurrenceMonths = cursor.getInt(4),
                    accountId = cursor.longOrNull(5),
                    notes = cursor.getString(6),
                    isActive = cursor.getInt(7) != 0,
                    lastPaidEpochDay = cursor.longOrNull(8),
                )
            }
        }
        return result
    }

    fun upsertBill(bill: BillReminder): Long {
        require(bill.title.isNotBlank()) { "Bill title cannot be empty" }
        val values = ContentValues().apply {
            put("title", bill.title.trim().replace(Regex("\\s+"), " ").take(64))
            put("amount_minor", bill.amountMinor.coerceAtLeast(0))
            put("due_date_epoch_day", bill.dueDateEpochDay)
            put("recurrence_months", bill.recurrenceMonths.coerceIn(0, 120))
            putNullableLong("account_id", bill.accountId)
            putNullableText("notes", bill.notes?.trim()?.take(240)?.takeIf(String::isNotBlank))
            put("is_active", if (bill.isActive) 1 else 0)
            putNullableLong("last_paid_epoch_day", bill.lastPaidEpochDay)
            put("updated_at", System.currentTimeMillis())
        }
        return if (bill.id == 0L) {
            writableDatabase.insertOrThrow("bills", null, values)
        } else {
            writableDatabase.update("bills", values, "id = ?", arrayOf(bill.id.toString()))
            bill.id
        }
    }

    fun deleteBill(id: Long) {
        writableDatabase.delete("bills", "id = ?", arrayOf(id.toString()))
    }

    fun getNetWorthItems(): List<NetWorthItem> {
        val result = mutableListOf<NetWorthItem>()
        readableDatabase.query(
            "net_worth_items",
            arrayOf("id", "name", "kind", "value_minor", "category", "updated_at"),
            null,
            null,
            null,
            null,
            "kind ASC, value_minor DESC, name COLLATE NOCASE ASC",
        ).use { cursor ->
            while (cursor.moveToNext()) {
                result += NetWorthItem(
                    id = cursor.getLong(0),
                    name = cursor.getString(1),
                    kind = enumValueOrDefault(cursor.getString(2), NetWorthKind.ASSET),
                    valueMinor = cursor.getLong(3),
                    category = cursor.getString(4),
                    updatedAt = cursor.getLong(5),
                )
            }
        }
        return result
    }

    fun upsertNetWorthItem(item: NetWorthItem): Long {
        require(item.name.isNotBlank()) { "Net-worth item name cannot be empty" }
        val values = ContentValues().apply {
            put("name", item.name.trim().replace(Regex("\\s+"), " ").take(64))
            put("kind", item.kind.name)
            put("value_minor", item.valueMinor.coerceAtLeast(0))
            put("category", item.category.trim().replace(Regex("\\s+"), " ").take(48))
            put("updated_at", System.currentTimeMillis())
        }
        return if (item.id == 0L) {
            writableDatabase.insertOrThrow("net_worth_items", null, values)
        } else {
            writableDatabase.update("net_worth_items", values, "id = ?", arrayOf(item.id.toString()))
            item.id
        }
    }

    fun deleteNetWorthItem(id: Long) {
        writableDatabase.delete("net_worth_items", "id = ?", arrayOf(id.toString()))
    }

    fun getSmartCategoryRules(): List<SmartCategoryRule> = getSmartCategoryRules(readableDatabase)

    private fun getSmartCategoryRules(db: SQLiteDatabase): List<SmartCategoryRule> {
        val result = mutableListOf<SmartCategoryRule>()
        db.query(
            "smart_category_rules",
            arrayOf(
                "id", "name", "merchant_pattern", "match_type", "min_amount_minor",
                "max_amount_minor", "account_id", "category", "custom_category_id",
                "enabled", "priority", "updated_at",
            ),
            null,
            null,
            null,
            null,
            "priority DESC, updated_at DESC, id DESC",
        ).use { cursor ->
            while (cursor.moveToNext()) {
                result += SmartCategoryRule(
                    id = cursor.getLong(0),
                    name = cursor.getString(1),
                    merchantPattern = cursor.getString(2),
                    matchType = enumValueOrDefault(cursor.getString(3), SmartRuleMatchType.CONTAINS),
                    minAmountMinor = cursor.longOrNull(4),
                    maxAmountMinor = cursor.longOrNull(5),
                    accountId = cursor.longOrNull(6),
                    category = enumValueOrDefault(cursor.getString(7), ExpenseCategory.OTHER),
                    customCategoryId = cursor.longOrNull(8),
                    enabled = cursor.getInt(9) != 0,
                    priority = cursor.getInt(10),
                    updatedAt = cursor.getLong(11),
                )
            }
        }
        return result
    }

    fun upsertSmartCategoryRule(rule: SmartCategoryRule, applyToHistory: Boolean = false): Long {
        val cleanPattern = rule.merchantPattern.trim().replace(Regex("\\s+"), " ").take(64)
        require(cleanPattern.isNotBlank()) { "Merchant pattern cannot be empty" }
        require(rule.minAmountMinor == null || rule.minAmountMinor >= 0) { "Minimum amount cannot be negative" }
        require(rule.maxAmountMinor == null || rule.maxAmountMinor >= 0) { "Maximum amount cannot be negative" }
        require(
            rule.minAmountMinor == null || rule.maxAmountMinor == null ||
                rule.minAmountMinor <= rule.maxAmountMinor
        ) { "Minimum amount cannot exceed maximum amount" }
        val db = writableDatabase
        val latestRuleUpdate = getSmartCategoryRules(db).maxOfOrNull { it.updatedAt } ?: Long.MIN_VALUE
        val now = maxOf(
            System.currentTimeMillis(),
            if (latestRuleUpdate == Long.MAX_VALUE) Long.MAX_VALUE else latestRuleUpdate + 1,
        )
        val values = ContentValues().apply {
            put("name", rule.name.trim().replace(Regex("\\s+"), " ").take(64).ifBlank { cleanPattern })
            put("merchant_pattern", cleanPattern)
            put("match_type", rule.matchType.name)
            putNullableLong("min_amount_minor", rule.minAmountMinor?.coerceAtLeast(0))
            putNullableLong("max_amount_minor", rule.maxAmountMinor?.coerceAtLeast(0))
            putNullableLong("account_id", rule.accountId)
            put("category", rule.category.name)
            putNullableLong("custom_category_id", rule.customCategoryId)
            put("enabled", if (rule.enabled) 1 else 0)
            put("priority", rule.priority.coerceIn(-10_000, 10_000))
            put("updated_at", now)
        }
        db.beginTransaction()
        return try {
            val id = if (rule.id == 0L) {
                db.insertOrThrow("smart_category_rules", null, values)
            } else {
                db.update("smart_category_rules", values, "id = ?", arrayOf(rule.id.toString()))
                rule.id
            }
            if (applyToHistory && rule.enabled) {
                applySmartCategoryRuleToHistory(db, rule.copy(id = id, merchantPattern = cleanPattern, updatedAt = now))
            }
            db.setTransactionSuccessful()
            id
        } finally {
            db.endTransaction()
        }
    }

    fun deleteSmartCategoryRule(id: Long) {
        writableDatabase.delete("smart_category_rules", "id = ?", arrayOf(id.toString()))
    }

    fun getMonthlyReconciliations(): List<MonthlyReconciliation> {
        val result = mutableListOf<MonthlyReconciliation>()
        readableDatabase.query(
            "monthly_reconciliations",
            arrayOf(
                "id", "account_id", "year", "month", "opening_balance_minor", "closing_balance_minor",
                "statement_transaction_count", "matched_transaction_count", "unmatched_statement_count",
                "unmatched_app_count", "status", "notes", "reconciled_at", "updated_at",
            ),
            null,
            null,
            null,
            null,
            "year DESC, month DESC, account_id ASC",
        ).use { cursor ->
            while (cursor.moveToNext()) {
                result += MonthlyReconciliation(
                    id = cursor.getLong(0),
                    accountId = cursor.getLong(1),
                    year = cursor.getInt(2),
                    month = cursor.getInt(3),
                    openingBalanceMinor = cursor.longOrNull(4),
                    closingBalanceMinor = cursor.longOrNull(5),
                    statementTransactionCount = cursor.getInt(6),
                    matchedTransactionCount = cursor.getInt(7),
                    unmatchedStatementCount = cursor.getInt(8),
                    unmatchedAppCount = cursor.getInt(9),
                    status = enumValueOrDefault(cursor.getString(10), ReconciliationStatus.DRAFT),
                    notes = cursor.getString(11),
                    reconciledAt = cursor.longOrNull(12),
                    updatedAt = cursor.getLong(13),
                )
            }
        }
        return result
    }

    fun upsertMonthlyReconciliation(reconciliation: MonthlyReconciliation): Long {
        validateReconciliation(reconciliation)
        val db = writableDatabase
        db.beginTransaction()
        return try {
            val existing = reconciliation.id.takeIf { it > 0 }?.let { findReconciliation(db, it) }
                ?: findReconciliation(db, reconciliation.accountId, reconciliation.year, reconciliation.month)
            val now = System.currentTimeMillis()
            val values = reconciliationValues(reconciliation.copy(updatedAt = now))
            val id = if (existing == null) {
                db.insertOrThrow("monthly_reconciliations", null, values)
            } else {
                db.update("monthly_reconciliations", values, "id = ?", arrayOf(existing.id.toString()))
                existing.id
            }
            val after = findReconciliation(db, id) ?: error("Saved reconciliation is unavailable")
            insertAuditEvent(
                db = db,
                batchId = newAuditBatchId("reconciliation"),
                batchLabel = if (existing == null) "Add monthly reconciliation" else "Update monthly reconciliation",
                entityType = AuditEntityType.MONTHLY_RECONCILIATION,
                entityId = id.toString(),
                action = if (existing == null) AuditAction.INSERT else AuditAction.UPDATE,
                beforePayload = existing?.let(::encodeReconciliationAuditPayload),
                afterPayload = encodeReconciliationAuditPayload(after),
            )
            db.setTransactionSuccessful()
            id
        } finally {
            db.endTransaction()
        }
    }

    /**
     * Conservatively demotes stale successful reconciliations after the ledger changes.
     * The repository computes which periods are invalid using the same account-aware
     * metrics shown to the user; this method persists one auditable batch atomically.
     */
    fun markReconciliationsReviewRequired(ids: Set<Long>): Int {
        val cleanIds = ids.filterTo(linkedSetOf()) { it > 0 }
        if (cleanIds.isEmpty()) return 0
        val db = writableDatabase
        val batchId = newAuditBatchId("system-reconciliation-invalidated")
        val now = System.currentTimeMillis()
        var updated = 0
        db.beginTransaction()
        return try {
            cleanIds.forEach { id ->
                val before = findReconciliation(db, id) ?: return@forEach
                if (
                    before.status != ReconciliationStatus.BALANCED &&
                    before.status != ReconciliationStatus.RECONCILED
                ) {
                    return@forEach
                }
                val after = before.copy(
                    status = ReconciliationStatus.REVIEW_REQUIRED,
                    reconciledAt = null,
                    updatedAt = now,
                )
                val count = db.update(
                    "monthly_reconciliations",
                    reconciliationValues(after),
                    "id = ?",
                    arrayOf(id.toString()),
                )
                if (count > 0) {
                    updated += count
                    insertAuditEvent(
                        db = db,
                        batchId = batchId,
                        batchLabel = "Reconciliation needs review",
                        entityType = AuditEntityType.MONTHLY_RECONCILIATION,
                        entityId = id.toString(),
                        action = AuditAction.UPDATE,
                        beforePayload = encodeReconciliationAuditPayload(before),
                        afterPayload = encodeReconciliationAuditPayload(after),
                        occurredAt = now,
                    )
                }
            }
            db.setTransactionSuccessful()
            updated
        } finally {
            db.endTransaction()
        }
    }

    fun deleteMonthlyReconciliation(id: Long) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            val before = findReconciliation(db, id) ?: return
            db.delete("monthly_reconciliations", "id = ?", arrayOf(id.toString()))
            insertAuditEvent(
                db,
                newAuditBatchId("reconciliation-delete"),
                "Delete monthly reconciliation",
                AuditEntityType.MONTHLY_RECONCILIATION,
                id.toString(),
                AuditAction.DELETE,
                encodeReconciliationAuditPayload(before),
                null,
            )
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun getTransactionLinks(): List<TransactionLink> = getTransactionLinks(readableDatabase)

    private fun getTransactionLinks(db: SQLiteDatabase): List<TransactionLink> {
        val result = mutableListOf<TransactionLink>()
        db.query(
            "transaction_links",
            arrayOf("id", "source_transaction_id", "target_transaction_id", "link_type", "note", "created_at"),
            null,
            null,
            null,
            null,
            "created_at DESC, id DESC",
        ).use { cursor ->
            while (cursor.moveToNext()) {
                result += TransactionLink(
                    id = cursor.getLong(0),
                    sourceTransactionId = cursor.getLong(1),
                    targetTransactionId = cursor.getLong(2),
                    type = enumValueOrDefault(cursor.getString(3), TransactionLinkType.TRANSFER),
                    note = cursor.getString(4),
                    createdAt = cursor.getLong(5),
                )
            }
        }
        return result
    }

    fun createTransactionLink(link: TransactionLink): Long {
        val db = writableDatabase
        val transactionRecords = getTransactions()
        val transactionIds = transactionRecords.mapTo(mutableSetOf(), TransactionRecord::id)
        val validation = validateTransactionLink(
            link,
            getTransactionLinks(db),
            transactionIds,
            transactionRecords.associateBy(TransactionRecord::id),
        )
        require(validation.isValid) { "Transaction link is invalid: ${validation.issue}" }
        db.beginTransaction()
        return try {
            val clean = link.copy(
                id = 0,
                note = link.note?.trim()?.replace(Regex("\\s+"), " ")?.take(160)?.takeIf(String::isNotBlank),
                createdAt = link.createdAt.takeIf { it > 0 } ?: System.currentTimeMillis(),
            )
            val id = db.insertOrThrow("transaction_links", null, transactionLinkValues(clean))
            val saved = clean.copy(id = id)
            insertAuditEvent(
                db,
                newAuditBatchId("link"),
                "Link transactions",
                AuditEntityType.TRANSACTION_LINK,
                id.toString(),
                AuditAction.INSERT,
                null,
                encodeTransactionLinkAuditPayload(saved),
            )
            db.setTransactionSuccessful()
            id
        } finally {
            db.endTransaction()
        }
    }

    fun deleteTransactionLink(id: Long) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            val before = findTransactionLink(db, id) ?: return
            val backsExpenseSplit = db.rawQuery(
                "SELECT 1 FROM expense_splits WHERE transaction_link_id = ? LIMIT 1",
                arrayOf(id.toString()),
            ).use { it.moveToFirst() }
            require(!backsExpenseSplit) {
                "This reimbursement link is managed by an expense split; update or delete the split instead"
            }
            db.delete("transaction_links", "id = ?", arrayOf(id.toString()))
            insertAuditEvent(
                db,
                newAuditBatchId("link-delete"),
                "Unlink transactions",
                AuditEntityType.TRANSACTION_LINK,
                id.toString(),
                AuditAction.DELETE,
                encodeTransactionLinkAuditPayload(before),
                null,
            )
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun getAuditEvents(limit: Int = 500): List<AuditEvent> {
        val result = mutableListOf<AuditEvent>()
        readableDatabase.query(
            "audit_events",
            arrayOf(
                "id", "batch_id", "batch_label", "entity_type", "entity_id", "action",
                "before_payload", "after_payload", "occurred_at", "reverses_event_id",
            ),
            null,
            null,
            null,
            null,
            "occurred_at DESC, id DESC",
            limit.coerceIn(1, 200_000).toString(),
        ).use { cursor ->
            while (cursor.moveToNext()) {
                result += AuditEvent(
                    id = cursor.getLong(0),
                    batchId = cursor.getString(1),
                    batchLabel = cursor.getString(2),
                    entityType = enumValueOrDefault(cursor.getString(3), AuditEntityType.TRANSACTION),
                    entityId = cursor.getString(4),
                    action = enumValueOrDefault(cursor.getString(5), AuditAction.UPDATE),
                    beforePayload = cursor.getString(6),
                    afterPayload = cursor.getString(7),
                    occurredAt = cursor.getLong(8),
                    reversesEventId = cursor.longOrNull(9),
                )
            }
        }
        return result
    }

    fun undoAuditBatch(batchId: String): AuditUndoResult {
        require(batchId.isNotBlank()) { "Audit batch ID cannot be empty" }
        require(isAuditBatchEligibleForUndo(batchId)) {
            "System-maintained audit events cannot be undone"
        }
        val db = writableDatabase
        val events = getAuditEventsForBatch(db, batchId).filter { it.reversesEventId == null }
        require(events.isNotEmpty()) { "Audit batch was not found or cannot be undone" }
        val eventIds = events.map { it.id }
        val placeholders = eventIds.joinToString(",") { "?" }
        val alreadyReversed = db.rawQuery(
            "SELECT 1 FROM audit_events WHERE reverses_event_id IN ($placeholders) LIMIT 1",
            eventIds.map(Long::toString).toTypedArray(),
        ).use { it.moveToFirst() }
        require(!alreadyReversed) { "This audit batch has already been undone" }
        val undoBatchId = newAuditBatchId("undo")
        var inserted = 0
        var updated = 0
        var deleted = 0
        db.beginTransaction()
        try {
            validateAuditBatchUndo(db, events)
            orderAuditEventsForUndo(events).forEach { event ->
                val reverseAction = when (event.action) {
                    AuditAction.INSERT -> {
                        deleteAuditEntity(db, event.entityType, event.entityId)
                        deleted += 1
                        AuditAction.DELETE
                    }
                    AuditAction.UPDATE -> {
                        restoreAuditEntity(db, event.entityType, requireNotNull(event.beforePayload), replace = true)
                        updated += 1
                        AuditAction.UPDATE
                    }
                    AuditAction.DELETE -> {
                        restoreAuditEntity(db, event.entityType, requireNotNull(event.beforePayload), replace = false)
                        inserted += 1
                        AuditAction.INSERT
                    }
                }
                insertAuditEvent(
                    db = db,
                    batchId = undoBatchId,
                    batchLabel = "Undo: ${event.batchLabel}",
                    entityType = event.entityType,
                    entityId = event.entityId,
                    action = reverseAction,
                    beforePayload = event.afterPayload,
                    afterPayload = event.beforePayload,
                    reversesEventId = event.id,
                )
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        return AuditUndoResult(batchId, undoBatchId, inserted, updated, deleted)
    }

    private fun validateAuditBatchUndo(db: SQLiteDatabase, events: List<AuditEvent>) {
        val currentPayloads = events.associate { event ->
            AuditEntityKey(event.entityType, event.entityId) to
                currentAuditPayload(db, event.entityType, event.entityId)
        }
        val splitDependencies = getAuditSplitDependencies(db)
        val insertedTransactionIds = events
            .filter { it.action == AuditAction.INSERT && it.entityType == AuditEntityType.TRANSACTION }
            .mapTo(mutableSetOf()) { it.entityId.toLong() }
        val linksDeletedByUndo = events
            .filter { it.action == AuditAction.INSERT && it.entityType == AuditEntityType.TRANSACTION_LINK }
            .mapTo(mutableSetOf()) { it.entityId.toLong() }
        val currentLinks = getTransactionLinks(db)
        val newerDependencies = currentLinks.filter { link ->
            link.id !in linksDeletedByUndo &&
                (link.sourceTransactionId in insertedTransactionIds || link.targetTransactionId in insertedTransactionIds)
        }
        val transactionIdsWithNewerLinks = newerDependencies.flatMap { link ->
            listOf(link.sourceTransactionId, link.targetTransactionId)
        }.filterTo(mutableSetOf()) { it in insertedTransactionIds }
        val transactionIdsWithSplits = splitDependencies.flatMapTo(mutableSetOf()) { dependency ->
            listOfNotNull(dependency.transactionId, dependency.linkedIncomingTransactionId)
        }
        val insertedLinks = events
            .filter { it.action == AuditAction.INSERT && it.entityType == AuditEntityType.TRANSACTION_LINK }
            .mapNotNull { event -> event.afterPayload?.let(::decodeTransactionLinkAuditPayload) }
        val linkIdsWithSplits = insertedLinks.filterTo(mutableSetOf()) { link ->
            splitDependencies.any { dependency -> dependency.dependsOn(link) }
        }.mapTo(mutableSetOf(), TransactionLink::id)
        val conflict = findAuditUndoConflict(
            events = events,
            currentPayloads = currentPayloads,
            insertedTransactionIdsWithNewerLinks = transactionIdsWithNewerLinks,
            insertedTransactionIdsWithExpenseSplits = transactionIdsWithSplits,
            insertedTransactionLinkIdsWithExpenseSplits = linkIdsWithSplits,
        )
        require(conflict == null) { "This change cannot be undone because $conflict" }

        val targetTransactions = getTransactions().associateByTo(mutableMapOf(), TransactionRecord::id)
        val targetLinks = currentLinks.associateByTo(mutableMapOf(), TransactionLink::id)
        orderAuditEventsForUndo(events).forEach { event ->
            when (event.entityType) {
                AuditEntityType.TRANSACTION -> when (event.action) {
                    AuditAction.INSERT -> targetTransactions.remove(event.entityId.toLong())
                    AuditAction.UPDATE, AuditAction.DELETE -> {
                        val restored = decodeTransactionAuditPayload(requireNotNull(event.beforePayload)).record
                        targetTransactions[restored.id] = restored
                    }
                }
                AuditEntityType.TRANSACTION_LINK -> when (event.action) {
                    AuditAction.INSERT -> targetLinks.remove(event.entityId.toLong())
                    AuditAction.UPDATE, AuditAction.DELETE -> {
                        val restored = decodeTransactionLinkAuditPayload(requireNotNull(event.beforePayload))
                        targetLinks[restored.id] = restored
                    }
                }
                AuditEntityType.MONTHLY_RECONCILIATION -> Unit
            }
        }
        val ledgerConflict = findAuditUndoLedgerConflict(
            transactions = targetTransactions.values.toList(),
            links = targetLinks.values.toList(),
            expenseSplits = getExpenseSplits(db),
        )
        require(ledgerConflict == null) { "This change cannot be undone because $ledgerConflict" }
        splitDependencies.forEach { dependency ->
            val linkId = dependency.transactionLinkId ?: return@forEach
            val targetLink = targetLinks[linkId]
            require(targetLink != null && dependency.dependsOn(targetLink)) {
                "This change cannot be undone because expense split ${dependency.id} would lose its reimbursement link"
            }
        }
    }

    private fun currentAuditPayload(
        db: SQLiteDatabase,
        entityType: AuditEntityType,
        entityId: String,
    ): String? = when (entityType) {
        AuditEntityType.TRANSACTION -> transactionAuditPayload(db, entityId.toLong())
        AuditEntityType.TRANSACTION_LINK -> findTransactionLink(db, entityId.toLong())
            ?.let(::encodeTransactionLinkAuditPayload)
        AuditEntityType.MONTHLY_RECONCILIATION -> findReconciliation(db, entityId.toLong())
            ?.let(::encodeReconciliationAuditPayload)
    }

    private fun applySmartCategoryRuleToHistory(db: SQLiteDatabase, rule: SmartCategoryRule): Int {
        val exactMerchantRules = getMerchantCategoryMap(db)
        val allSmartRules = getSmartCategoryRules(db)
        val matchingIds = mutableListOf<Long>()
        db.query(
            "transactions",
            arrayOf("id", "merchant", "amount_minor", "account_id", "type"),
            "type = ?",
            arrayOf(TransactionType.EXPENSE.name),
            null,
            null,
            null,
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val merchant = cursor.getString(1)
                if (
                    normalizedMerchantKey(merchant) !in exactMerchantRules &&
                    findMatchingSmartCategoryRule(
                        TransactionRecord(
                            sourceMessageId = "history-${cursor.getLong(0)}",
                            amountMinor = cursor.getLong(2),
                            merchant = merchant,
                            accountHint = null,
                            category = ExpenseCategory.OTHER,
                            type = TransactionType.EXPENSE,
                            occurredAt = 0,
                            source = TransactionSource.BANK,
                            sender = "",
                            accountId = cursor.longOrNull(3),
                        ),
                        allSmartRules,
                    )?.id == rule.id
                ) {
                    matchingIds += cursor.getLong(0)
                }
            }
        }
        val values = ContentValues().apply {
            put("category", rule.category.name)
            putNullableLong("custom_category_id", rule.customCategoryId)
            put("review_status", ReviewStatus.CONFIRMED.name)
            putNull("review_reason")
        }
        val beforePayloadById = matchingIds.associateWith { id ->
            requireNotNull(transactionAuditPayload(db, id))
        }
        val batchId = newAuditBatchId("smart-rule-history")
        var updated = 0
        matchingIds.forEach { id ->
            val count = db.update("transactions", values, "id = ?", arrayOf(id.toString()))
            updated += count
            if (count > 0) {
                insertAuditEvent(
                    db,
                    batchId,
                    "Apply smart rule ${rule.name.take(48)}",
                    AuditEntityType.TRANSACTION,
                    id.toString(),
                    AuditAction.UPDATE,
                    beforePayloadById.getValue(id),
                    requireNotNull(transactionAuditPayload(db, id)),
                )
            }
        }
        return updated
    }


    fun getLoans(): List<LoanAccount> {
        val result = mutableListOf<LoanAccount>()
        readableDatabase.query(
            "loans",
            arrayOf(
                "id", "name", "lender", "principal_minor", "annual_rate_bps", "tenure_months",
                "start_date_epoch_day", "emi_minor", "paid_installments", "account_id", "notes",
            ),
            null,
            null,
            null,
            null,
            "start_date_epoch_day DESC, name COLLATE NOCASE ASC",
        ).use { cursor ->
            while (cursor.moveToNext()) {
                result += LoanAccount(
                    id = cursor.getLong(0),
                    name = cursor.getString(1),
                    lender = cursor.getString(2),
                    principalMinor = cursor.getLong(3),
                    annualRateBasisPoints = cursor.getInt(4),
                    tenureMonths = cursor.getInt(5),
                    startDateEpochDay = cursor.getLong(6),
                    emiMinor = cursor.getLong(7),
                    paidInstallments = cursor.getInt(8),
                    accountId = cursor.longOrNull(9),
                    notes = cursor.getString(10),
                )
            }
        }
        return result
    }

    fun upsertLoan(loan: LoanAccount): Long {
        val values = ContentValues().apply {
            put("name", loan.name.trim().take(48))
            put("lender", loan.lender.trim().take(48))
            put("principal_minor", loan.principalMinor.coerceAtLeast(0))
            put("annual_rate_bps", loan.annualRateBasisPoints.coerceIn(0, 10_000))
            put("tenure_months", loan.tenureMonths.coerceIn(1, 600))
            put("start_date_epoch_day", loan.startDateEpochDay)
            put("emi_minor", loan.emiMinor.coerceAtLeast(0))
            put("paid_installments", loan.paidInstallments.coerceIn(0, loan.tenureMonths))
            putNullableLong("account_id", loan.accountId)
            putNullableText("notes", loan.notes?.trim()?.take(160)?.takeIf(String::isNotBlank))
            put("updated_at", System.currentTimeMillis())
        }
        return if (loan.id == 0L) {
            writableDatabase.insertOrThrow("loans", null, values)
        } else {
            writableDatabase.update("loans", values, "id = ?", arrayOf(loan.id.toString()))
            loan.id
        }
    }

    fun deleteLoan(id: Long) {
        writableDatabase.delete("loans", "id = ?", arrayOf(id.toString()))
    }

    fun upsertExchangeRate(rate: ExchangeRate) {
        writableDatabase.insertWithOnConflict(
            "exchange_rates",
            null,
            ContentValues().apply {
                put("pair_key", "${rate.baseCurrency}:${rate.quoteCurrency}")
                put("base_currency", rate.baseCurrency)
                put("quote_currency", rate.quoteCurrency)
                put("rate", rate.rate)
                put("rate_date", rate.rateDate)
                put("fetched_at", rate.fetchedAt)
                put("provider", rate.provider)
            },
            SQLiteDatabase.CONFLICT_REPLACE,
        )
    }

    fun getExchangeRates(): List<ExchangeRate> {
        val result = mutableListOf<ExchangeRate>()
        readableDatabase.query(
            "exchange_rates",
            arrayOf("base_currency", "quote_currency", "rate", "rate_date", "fetched_at", "provider"),
            null,
            null,
            null,
            null,
            "quote_currency ASC",
        ).use { cursor ->
            while (cursor.moveToNext()) {
                result += ExchangeRate(
                    baseCurrency = cursor.getString(0),
                    quoteCurrency = cursor.getString(1),
                    rate = cursor.getDouble(2),
                    rateDate = cursor.getString(3),
                    fetchedAt = cursor.getLong(4),
                    provider = cursor.getString(5),
                )
            }
        }
        return result
    }

    fun getCustomCategories(): List<CustomCategory> {
        val result = mutableListOf<CustomCategory>()
        readableDatabase.query(
            "custom_categories",
            arrayOf("id", "name", "color_hex"),
            null,
            null,
            null,
            null,
            "name COLLATE NOCASE ASC",
        ).use { cursor ->
            while (cursor.moveToNext()) {
                result += CustomCategory(cursor.getLong(0), cursor.getString(1), cursor.getString(2))
            }
        }
        return result
    }

    fun addCustomCategory(name: String, colorHex: String): Long {
        val values = ContentValues().apply {
            put("name", name.trim().take(32))
            put("color_hex", normalizeColor(colorHex))
            put("created_at", System.currentTimeMillis())
        }
        return writableDatabase.insertOrThrow("custom_categories", null, values)
    }

    fun updateCustomCategory(category: CustomCategory) {
        val values = ContentValues().apply {
            put("name", category.name.trim().take(32))
            put("color_hex", normalizeColor(category.colorHex))
        }
        writableDatabase.update("custom_categories", values, "id = ?", arrayOf(category.id.toString()))
    }

    fun deleteCustomCategory(id: Long) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.delete("merchant_categories", "custom_category_id = ?", arrayOf(id.toString()))
            db.delete("custom_categories", "id = ?", arrayOf(id.toString()))
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun getBudgets(): List<CategoryBudget> {
        val result = mutableListOf<CategoryBudget>()
        readableDatabase.query(
            "budgets",
            arrayOf("category", "limit_minor"),
            null,
            null,
            null,
            null,
            "category ASC",
        ).use { cursor ->
            while (cursor.moveToNext()) {
                result += CategoryBudget(
                    category = enumValueOrDefault(cursor.getString(0), ExpenseCategory.OTHER),
                    limitMinor = cursor.getLong(1),
                )
            }
        }
        return result
    }

    fun upsertBudget(category: ExpenseCategory, limitMinor: Long) {
        if (limitMinor <= 0) {
            writableDatabase.delete("budgets", "category = ?", arrayOf(category.name))
            return
        }
        val values = ContentValues().apply {
            put("category", category.name)
            put("limit_minor", limitMinor)
        }
        writableDatabase.insertWithOnConflict("budgets", null, values, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun snapshot(): PaisaLensBackupSnapshot = PaisaLensBackupSnapshot(
        createdAt = System.currentTimeMillis(),
        transactions = getTransactions(),
        budgets = getBudgets(),
        accounts = getAccounts(),
        customCategories = getCustomCategories(),
        merchantRules = getMerchantRules(),
        merchantAliases = getMerchantAliases(),
        loans = getLoans(),
        balanceHistory = getBalanceHistory(),
        bills = getBills(),
        netWorthItems = getNetWorthItems(),
        smartCategoryRules = getSmartCategoryRules(),
        reconciliations = getMonthlyReconciliations(),
        transactionLinks = getTransactionLinks(),
        auditEvents = getAuditEvents(200_000),
        expenseSplits = getExpenseSplits(),
        savingsGoals = getSavingsGoals(),
        savingsContributions = getSavingsContributions(),
        paymentCommitments = getPaymentCommitments(),
    )

    fun restore(snapshot: PaisaLensBackupSnapshot) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            clearAllTables(db)
            snapshot.accounts.forEach { account ->
                db.insertOrThrow(
                    "accounts",
                    null,
                    ContentValues().apply {
                        put("id", account.id)
                        put(
                            "identity_key",
                            account.identityKey ?: restoredAccountIdentityKey(account),
                        )
                        put("name", account.name)
                        put("type", account.type.name)
                        putNullableText("account_hint", account.accountHint)
                        putNullableText("institution", account.institution)
                        putNullableLong("balance_minor", account.balanceMinor)
                        putNullableLong("available_credit_minor", account.availableCreditMinor)
                        putNullableLong("credit_limit_minor", account.creditLimitMinor)
                        putNullableLong("availability_fetched_at", account.availabilityFetchedAt)
                        putNullableText("availability_sender", account.availabilitySender)
                        put("created_at", snapshot.createdAt)
                    },
                )
            }
            snapshot.customCategories.forEach { category ->
                db.insertOrThrow(
                    "custom_categories",
                    null,
                    ContentValues().apply {
                        put("id", category.id)
                        put("name", category.name)
                        put("color_hex", normalizeColor(category.colorHex))
                        put("created_at", snapshot.createdAt)
                    },
                )
            }
            snapshot.merchantRules.forEach { rule ->
                db.insertOrThrow(
                    "merchant_categories",
                    null,
                    ContentValues().apply {
                        put("merchant_key", rule.merchantKey)
                        put("merchant_name", rule.merchantName)
                        put("category", rule.category.name)
                        putNullableLong("custom_category_id", rule.customCategoryId)
                        put("updated_at", snapshot.createdAt)
                    },
                )
            }
            snapshot.merchantAliases.forEach { rule ->
                db.insertOrThrow(
                    "merchant_aliases",
                    null,
                    ContentValues().apply {
                        put("alias_key", rule.aliasKey)
                        put("alias_name", rule.aliasName)
                        put("canonical_name", rule.canonicalName)
                        put("updated_at", rule.updatedAt)
                    },
                )
            }
            snapshot.smartCategoryRules.forEach { rule ->
                db.insertOrThrow(
                    "smart_category_rules",
                    null,
                    ContentValues().apply {
                        put("id", rule.id)
                        put("name", rule.name)
                        put("merchant_pattern", rule.merchantPattern)
                        put("match_type", rule.matchType.name)
                        putNullableLong("min_amount_minor", rule.minAmountMinor)
                        putNullableLong("max_amount_minor", rule.maxAmountMinor)
                        putNullableLong("account_id", rule.accountId)
                        put("category", rule.category.name)
                        putNullableLong("custom_category_id", rule.customCategoryId)
                        put("enabled", if (rule.enabled) 1 else 0)
                        put("priority", rule.priority)
                        put("updated_at", rule.updatedAt)
                    },
                )
            }
            snapshot.budgets.forEach { budget ->
                db.insertOrThrow(
                    "budgets",
                    null,
                    ContentValues().apply {
                        put("category", budget.category.name)
                        put("limit_minor", budget.limitMinor)
                    },
                )
            }
            val auditedCreatedAtByTransactionId = snapshot.auditEvents
                .asSequence()
                .filter { it.entityType == AuditEntityType.TRANSACTION }
                .flatMap { sequenceOf(it.afterPayload, it.beforePayload) }
                .filterNotNull()
                .mapNotNull { payload ->
                    runCatching { TransactionAuditPayloadCodec.decode(payload) }.getOrNull()
                }
                .associate { it.record.id to it.createdAt }
            snapshot.transactions.forEach { transaction ->
                db.insertOrThrow(
                    "transactions",
                    null,
                    transactionValues(transaction, includeId = true).apply {
                        put("created_at", auditedCreatedAtByTransactionId[transaction.id] ?: snapshot.createdAt)
                    },
                )
            }
            snapshot.loans.forEach { loan ->
                db.insertOrThrow(
                    "loans",
                    null,
                    ContentValues().apply {
                        put("id", loan.id)
                        put("name", loan.name)
                        put("lender", loan.lender)
                        put("principal_minor", loan.principalMinor)
                        put("annual_rate_bps", loan.annualRateBasisPoints)
                        put("tenure_months", loan.tenureMonths)
                        put("start_date_epoch_day", loan.startDateEpochDay)
                        put("emi_minor", loan.emiMinor)
                        put("paid_installments", loan.paidInstallments)
                        putNullableLong("account_id", loan.accountId)
                        putNullableText("notes", loan.notes)
                        put("updated_at", snapshot.createdAt)
                    },
                )
            }
            snapshot.balanceHistory.forEach { point ->
                db.insertOrThrow(
                    "balance_history",
                    null,
                    ContentValues().apply {
                        put("id", point.id)
                        put("account_id", point.accountId)
                        putNullableLong("balance_minor", point.balanceMinor)
                        putNullableLong("available_credit_minor", point.availableCreditMinor)
                        putNullableLong("credit_limit_minor", point.creditLimitMinor)
                        put("recorded_at", point.recordedAt)
                        put("sender", point.sender?.take(64)?.ifBlank { "Balance history" } ?: "Balance history")
                    },
                )
            }
            snapshot.bills.forEach { bill ->
                db.insertOrThrow(
                    "bills",
                    null,
                    ContentValues().apply {
                        put("id", bill.id)
                        put("title", bill.title)
                        put("amount_minor", bill.amountMinor)
                        put("due_date_epoch_day", bill.dueDateEpochDay)
                        put("recurrence_months", bill.recurrenceMonths)
                        putNullableLong("account_id", bill.accountId)
                        putNullableText("notes", bill.notes)
                        put("is_active", if (bill.isActive) 1 else 0)
                        putNullableLong("last_paid_epoch_day", bill.lastPaidEpochDay)
                        put("updated_at", snapshot.createdAt)
                    },
                )
            }
            snapshot.netWorthItems.forEach { item ->
                db.insertOrThrow(
                    "net_worth_items",
                    null,
                    ContentValues().apply {
                        put("id", item.id)
                        put("name", item.name)
                        put("kind", item.kind.name)
                        put("value_minor", item.valueMinor)
                        put("category", item.category)
                        put("updated_at", item.updatedAt)
                    },
                )
            }
            snapshot.savingsGoals.forEach { goal ->
                require(goal.name.isNotBlank() && goal.targetMinor > 0 && goal.startingSavedMinor >= 0) {
                    "Backup contains an invalid savings goal"
                }
                db.insertOrThrow(
                    "savings_goals",
                    null,
                    savingsGoalValues(goal.copy(colorHex = normalizeColor(goal.colorHex)), includeId = true),
                )
            }
            snapshot.savingsContributions.forEach { contribution ->
                require(contribution.amountMinor > 0) { "Backup contains an invalid savings contribution" }
                db.insertOrThrow(
                    "savings_contributions",
                    null,
                    savingsContributionValues(contribution, includeId = true),
                )
            }
            snapshot.paymentCommitments.forEach { commitment ->
                require(commitment.name.isNotBlank() && commitment.amountMinor >= 0) {
                    "Backup contains an invalid payment commitment"
                }
                require(commitment.maxMandateMinor == null || commitment.maxMandateMinor >= commitment.amountMinor) {
                    "Backup contains a mandate below its expected payment"
                }
                db.insertOrThrow(
                    "payment_commitments",
                    null,
                    paymentCommitmentValues(commitment, includeId = true),
                )
            }
            snapshot.reconciliations.forEach { reconciliation ->
                validateReconciliation(reconciliation)
                db.insertOrThrow(
                    "monthly_reconciliations",
                    null,
                    reconciliationValues(reconciliation, includeId = true),
                )
            }
            val restoredLinks = mutableListOf<TransactionLink>()
            val restoredTransactionIds = snapshot.transactions.mapTo(mutableSetOf(), TransactionRecord::id)
            val restoredTransactionsById = snapshot.transactions.associateBy(TransactionRecord::id)
            snapshot.transactionLinks.forEach { link ->
                val validation = validateTransactionLink(
                    link,
                    restoredLinks,
                    restoredTransactionIds,
                    restoredTransactionsById,
                )
                require(validation.isValid) { "Backup contains an invalid transaction link: ${validation.issue}" }
                db.insertOrThrow(
                    "transaction_links",
                    null,
                    transactionLinkValues(link, includeId = true),
                )
                restoredLinks += link
            }
            val restoredSplits = mutableListOf<ExpenseSplit>()
            snapshot.expenseSplits.sortedBy(ExpenseSplit::id).forEach { split ->
                val transaction = restoredTransactionsById[split.transactionId]
                val validation = validateExpenseSplits(
                    transaction,
                    restoredSplits.filter { it.transactionId == split.transactionId } + split,
                    restoredTransactionsById,
                )
                require(validation.isValid) { "Backup contains an invalid expense split: ${validation.issue}" }
                require(restoredSplits.none {
                    split.linkedIncomingTransactionId != null &&
                        it.linkedIncomingTransactionId == split.linkedIncomingTransactionId
                }) { "Backup reuses an incoming reimbursement across expense splits" }
                val values = expenseSplitValues(
                    split.copy(status = expenseSplitStatus(split.shareMinor, split.reimbursedMinor)),
                    includeId = true,
                )
                split.linkedIncomingTransactionId?.let { incomingId ->
                    var exact = findExactReimbursementLink(db, split.transactionId, incomingId)
                    if (exact == null) {
                        val candidate = TransactionLink(
                            sourceTransactionId = split.transactionId,
                            targetTransactionId = incomingId,
                            type = TransactionLinkType.REIMBURSEMENT,
                            note = splitLinkNote(split.id),
                            createdAt = split.updatedAt,
                        )
                        val linkValidation = validateTransactionLink(
                            candidate,
                            restoredLinks,
                            restoredTransactionIds,
                            restoredTransactionsById,
                        )
                        require(linkValidation.isValid) { "Backup reimbursement link is invalid: ${linkValidation.issue}" }
                        val linkId = db.insertOrThrow("transaction_links", null, transactionLinkValues(candidate))
                        exact = SplitOwnedLink(linkId, split.transactionId, incomingId, candidate.note)
                        restoredLinks += candidate.copy(id = linkId)
                    }
                    values.put("transaction_link_id", exact.id)
                    values.put("owns_transaction_link", if (exact.note == splitLinkNote(split.id)) 1 else 0)
                }
                db.insertOrThrow("expense_splits", null, values)
                restoredSplits += split
            }
            snapshot.auditEvents.sortedBy(AuditEvent::id).forEach { event ->
                insertAuditEvent(
                    db = db,
                    batchId = event.batchId,
                    batchLabel = event.batchLabel,
                    entityType = event.entityType,
                    entityId = event.entityId,
                    action = event.action,
                    beforePayload = event.beforePayload,
                    afterPayload = event.afterPayload,
                    occurredAt = event.occurredAt,
                    reversesEventId = event.reversesEventId,
                    explicitId = event.id,
                )
            }
            if (snapshot.balanceHistory.isEmpty()) backfillBalanceHistory(db)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun clearAll() {
        val db = writableDatabase
        db.beginTransaction()
        try {
            clearAllTables(db)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    private fun transactionValues(
        item: ParsedTransaction,
        encryptedBody: ByteArray,
        accountId: Long?,
        merchantRule: MerchantCategoryRule?,
        smartRule: SmartCategoryRule?,
        canonicalMerchant: String,
    ) = ContentValues().apply {
        put("source_message_id", item.sourceMessageId)
        put("amount_minor", item.amountMinor)
        put("merchant", canonicalMerchant)
        putNullableText("account_hint", item.accountHint)
        put("category", (merchantRule?.category ?: smartRule?.category ?: item.category).name)
        put("type", item.type.name)
        put("occurred_at", item.occurredAt)
        put("source", item.source.name)
        put("sender", item.sender)
        putNull("note")
        putNullableLong("account_id", accountId)
        putNullableLong("custom_category_id", merchantRule?.customCategoryId ?: smartRule?.customCategoryId)
        put("tags", "")
        put("review_status", if (merchantRule != null || smartRule != null) ReviewStatus.CONFIRMED.name else item.reviewStatus.name)
        putNullableText("review_reason", if (merchantRule != null || smartRule != null) null else item.reviewReason)
        putNull("original_amount_minor")
        putNull("original_currency")
        putNull("exchange_rate")
        put("raw_message_cipher", encryptedBody)
        put("created_at", System.currentTimeMillis())
    }

    private fun transactionValues(record: TransactionRecord, includeId: Boolean = false) = ContentValues().apply {
        if (includeId) put("id", record.id)
        put("source_message_id", record.sourceMessageId)
        put("amount_minor", record.amountMinor)
        put("merchant", record.merchant)
        putNullableText("account_hint", record.accountHint)
        put("category", record.category.name)
        put("type", record.type.name)
        put("occurred_at", record.occurredAt)
        put("source", record.source.name)
        put("sender", record.sender)
        putNullableText("note", record.note?.trim()?.takeIf(String::isNotBlank))
        putNullableLong("account_id", record.accountId)
        putNullableLong("custom_category_id", record.customCategoryId)
        put("tags", encodeTags(record.tags))
        put("review_status", record.reviewStatus.name)
        putNullableText("review_reason", record.reviewReason)
        putNullableLong("original_amount_minor", record.originalAmountMinor)
        putNullableText("original_currency", record.originalCurrency)
        if (record.exchangeRate == null) putNull("exchange_rate") else put("exchange_rate", record.exchangeRate)
        putNull("raw_message_cipher")
        put("created_at", System.currentTimeMillis())
    }

    private fun categoryValues(selection: CategorySelection) = ContentValues().apply {
        put("category", selection.builtIn.name)
        putNullableLong("custom_category_id", selection.customCategoryId)
        put("review_status", ReviewStatus.CONFIRMED.name)
        putNull("review_reason")
    }

    private fun createAccountsTable(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS accounts (
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
    }

    private fun createCustomCategoriesTable(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS custom_categories (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                name TEXT NOT NULL UNIQUE COLLATE NOCASE,
                color_hex TEXT NOT NULL,
                created_at INTEGER NOT NULL
            )
            """.trimIndent(),
        )
    }

    private fun createMerchantAliasesTable(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS merchant_aliases (
                alias_key TEXT PRIMARY KEY NOT NULL,
                alias_name TEXT NOT NULL,
                canonical_name TEXT NOT NULL,
                updated_at INTEGER NOT NULL
            )
            """.trimIndent(),
        )
    }

    private fun createLoansTable(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS loans (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                name TEXT NOT NULL,
                lender TEXT NOT NULL,
                principal_minor INTEGER NOT NULL,
                annual_rate_bps INTEGER NOT NULL,
                tenure_months INTEGER NOT NULL,
                start_date_epoch_day INTEGER NOT NULL,
                emi_minor INTEGER NOT NULL,
                paid_installments INTEGER NOT NULL DEFAULT 0,
                account_id INTEGER REFERENCES accounts(id) ON DELETE SET NULL,
                notes TEXT,
                updated_at INTEGER NOT NULL
            )
            """.trimIndent(),
        )
    }

    private fun createExchangeRatesTable(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS exchange_rates (
                pair_key TEXT PRIMARY KEY NOT NULL,
                base_currency TEXT NOT NULL,
                quote_currency TEXT NOT NULL,
                rate REAL NOT NULL,
                rate_date TEXT NOT NULL,
                fetched_at INTEGER NOT NULL,
                provider TEXT NOT NULL
            )
            """.trimIndent(),
        )
    }

    private fun createFinancialPlanningTables(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS balance_history (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                account_id INTEGER NOT NULL REFERENCES accounts(id) ON DELETE CASCADE,
                balance_minor INTEGER,
                available_credit_minor INTEGER,
                credit_limit_minor INTEGER,
                recorded_at INTEGER NOT NULL,
                sender TEXT NOT NULL,
                UNIQUE(account_id, recorded_at, sender)
            )
            """.trimIndent(),
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS index_balance_history_account_date ON balance_history(account_id, recorded_at DESC)")
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS bills (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                title TEXT NOT NULL,
                amount_minor INTEGER NOT NULL CHECK(amount_minor >= 0),
                due_date_epoch_day INTEGER NOT NULL,
                recurrence_months INTEGER NOT NULL DEFAULT 0 CHECK(recurrence_months >= 0),
                account_id INTEGER REFERENCES accounts(id) ON DELETE SET NULL,
                notes TEXT,
                is_active INTEGER NOT NULL DEFAULT 1,
                last_paid_epoch_day INTEGER,
                updated_at INTEGER NOT NULL
            )
            """.trimIndent(),
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS index_bills_due_date ON bills(is_active, due_date_epoch_day)")
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS net_worth_items (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                name TEXT NOT NULL,
                kind TEXT NOT NULL,
                value_minor INTEGER NOT NULL CHECK(value_minor >= 0),
                category TEXT NOT NULL,
                updated_at INTEGER NOT NULL
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS smart_category_rules (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                name TEXT NOT NULL,
                merchant_pattern TEXT NOT NULL,
                match_type TEXT NOT NULL,
                min_amount_minor INTEGER,
                max_amount_minor INTEGER,
                account_id INTEGER REFERENCES accounts(id) ON DELETE SET NULL,
                category TEXT NOT NULL,
                custom_category_id INTEGER REFERENCES custom_categories(id) ON DELETE SET NULL,
                enabled INTEGER NOT NULL DEFAULT 1,
                priority INTEGER NOT NULL DEFAULT 0,
                updated_at INTEGER NOT NULL
            )
            """.trimIndent(),
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS index_smart_rules_priority ON smart_category_rules(enabled, priority DESC, updated_at DESC)")
    }

    private fun createTrustAccuracyTables(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS monthly_reconciliations (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                account_id INTEGER NOT NULL REFERENCES accounts(id) ON DELETE CASCADE,
                year INTEGER NOT NULL,
                month INTEGER NOT NULL CHECK(month BETWEEN 1 AND 12),
                opening_balance_minor INTEGER,
                closing_balance_minor INTEGER,
                statement_transaction_count INTEGER NOT NULL DEFAULT 0 CHECK(statement_transaction_count >= 0),
                matched_transaction_count INTEGER NOT NULL DEFAULT 0 CHECK(matched_transaction_count >= 0),
                unmatched_statement_count INTEGER NOT NULL DEFAULT 0 CHECK(unmatched_statement_count >= 0),
                unmatched_app_count INTEGER NOT NULL DEFAULT 0 CHECK(unmatched_app_count >= 0),
                status TEXT NOT NULL,
                notes TEXT,
                reconciled_at INTEGER,
                updated_at INTEGER NOT NULL,
                UNIQUE(account_id, year, month)
            )
            """.trimIndent(),
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS index_reconciliations_period ON monthly_reconciliations(year DESC, month DESC, account_id)",
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS transaction_links (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                source_transaction_id INTEGER NOT NULL REFERENCES transactions(id) ON DELETE CASCADE,
                target_transaction_id INTEGER NOT NULL REFERENCES transactions(id) ON DELETE CASCADE,
                link_type TEXT NOT NULL,
                note TEXT,
                created_at INTEGER NOT NULL,
                CHECK(source_transaction_id != target_transaction_id),
                UNIQUE(source_transaction_id, target_transaction_id, link_type)
            )
            """.trimIndent(),
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS index_transaction_links_source ON transaction_links(source_transaction_id)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_transaction_links_target ON transaction_links(target_transaction_id)")
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS audit_events (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                batch_id TEXT NOT NULL,
                batch_label TEXT NOT NULL,
                entity_type TEXT NOT NULL,
                entity_id TEXT NOT NULL,
                action TEXT NOT NULL,
                before_payload TEXT,
                after_payload TEXT,
                occurred_at INTEGER NOT NULL,
                reverses_event_id INTEGER UNIQUE REFERENCES audit_events(id)
            )
            """.trimIndent(),
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS index_audit_events_batch ON audit_events(batch_id, id)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_audit_events_time ON audit_events(occurred_at DESC, id DESC)")
    }

    private fun createSharedFinanceTables(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS expense_splits (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                transaction_id INTEGER NOT NULL REFERENCES transactions(id) ON DELETE CASCADE,
                participant_name TEXT NOT NULL,
                share_minor INTEGER NOT NULL CHECK(share_minor > 0),
                reimbursed_minor INTEGER NOT NULL DEFAULT 0 CHECK(reimbursed_minor >= 0 AND reimbursed_minor <= share_minor),
                linked_incoming_transaction_id INTEGER UNIQUE REFERENCES transactions(id) ON DELETE SET NULL,
                transaction_link_id INTEGER UNIQUE REFERENCES transaction_links(id) ON DELETE SET NULL,
                owns_transaction_link INTEGER NOT NULL DEFAULT 0,
                note TEXT,
                status TEXT NOT NULL,
                created_at INTEGER NOT NULL,
                updated_at INTEGER NOT NULL,
                CHECK(transaction_id != linked_incoming_transaction_id)
            )
            """.trimIndent(),
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS index_expense_splits_transaction ON expense_splits(transaction_id, id)")
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS savings_goals (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                name TEXT NOT NULL,
                target_minor INTEGER NOT NULL CHECK(target_minor > 0),
                starting_saved_minor INTEGER NOT NULL DEFAULT 0 CHECK(starting_saved_minor >= 0),
                target_date_epoch_day INTEGER,
                linked_account_id INTEGER REFERENCES accounts(id) ON DELETE SET NULL,
                kind TEXT NOT NULL,
                contribution_frequency TEXT NOT NULL,
                notes TEXT,
                color_hex TEXT NOT NULL,
                is_active INTEGER NOT NULL DEFAULT 1,
                created_at INTEGER NOT NULL,
                updated_at INTEGER NOT NULL
            )
            """.trimIndent(),
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS index_savings_goals_active_date ON savings_goals(is_active DESC, target_date_epoch_day)")
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS savings_contributions (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                goal_id INTEGER NOT NULL REFERENCES savings_goals(id) ON DELETE CASCADE,
                amount_minor INTEGER NOT NULL CHECK(amount_minor > 0),
                contributed_at INTEGER NOT NULL,
                note TEXT,
                linked_transaction_id INTEGER REFERENCES transactions(id) ON DELETE SET NULL
            )
            """.trimIndent(),
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS index_savings_contributions_goal_date ON savings_contributions(goal_id, contributed_at DESC)")
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS payment_commitments (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                name TEXT NOT NULL,
                merchant_key TEXT NOT NULL,
                kind TEXT NOT NULL,
                frequency TEXT NOT NULL,
                custom_interval_days INTEGER,
                amount_minor INTEGER NOT NULL CHECK(amount_minor >= 0),
                max_mandate_minor INTEGER CHECK(max_mandate_minor IS NULL OR max_mandate_minor >= 0),
                next_due_epoch_day INTEGER NOT NULL,
                account_id INTEGER REFERENCES accounts(id) ON DELETE SET NULL,
                upi_handle TEXT,
                status TEXT NOT NULL,
                source TEXT NOT NULL,
                category_label TEXT,
                notes TEXT,
                created_at INTEGER NOT NULL,
                updated_at INTEGER NOT NULL,
                CHECK(custom_interval_days IS NULL OR custom_interval_days > 0),
                CHECK(max_mandate_minor IS NULL OR max_mandate_minor >= amount_minor)
            )
            """.trimIndent(),
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS index_payment_commitments_status_due ON payment_commitments(status, next_due_epoch_day)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_payment_commitments_merchant ON payment_commitments(merchant_key)")
        createPaymentCommitmentUniqueIndex(db)
    }

    private fun createPaymentCommitmentUniqueIndex(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE UNIQUE INDEX IF NOT EXISTS index_payment_commitments_identity " +
                "ON payment_commitments(merchant_key, kind, COALESCE(account_id, -1))",
        )
    }

    private fun deduplicatePaymentCommitments(db: SQLiteDatabase) {
        val existing = getPaymentCommitments(db)
        val retained = deduplicatedPaymentCommitments(existing)
        val retainedIds = retained.mapTo(mutableSetOf(), PaymentCommitment::id)
        existing.asSequence().filter { it.id !in retainedIds }.forEach { duplicate ->
            db.delete("payment_commitments", "id = ?", arrayOf(duplicate.id.toString()))
        }
        retained.forEach { commitment ->
            val normalizedKey = normalizedMerchantKey(commitment.merchantKey.ifBlank { commitment.name })
            db.update(
                "payment_commitments",
                ContentValues().apply { put("merchant_key", normalizedKey) },
                "id = ?",
                arrayOf(commitment.id.toString()),
            )
        }
    }

    private fun createTransactionIndexes(db: SQLiteDatabase) {
        db.execSQL("CREATE INDEX IF NOT EXISTS index_transactions_date ON transactions(occurred_at DESC)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_transactions_category ON transactions(category)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_transactions_account ON transactions(account_id)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_transactions_review ON transactions(review_status)")
    }

    private fun createMerchantCategoriesTable(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS merchant_categories (
                merchant_key TEXT PRIMARY KEY NOT NULL,
                merchant_name TEXT NOT NULL,
                category TEXT NOT NULL,
                custom_category_id INTEGER,
                updated_at INTEGER NOT NULL
            )
            """.trimIndent(),
        )
    }

    private fun getMerchantCategoryMap(db: SQLiteDatabase): Map<String, MerchantCategoryRule> {
        val result = linkedMapOf<String, MerchantCategoryRule>()
        db.query(
            "merchant_categories",
            arrayOf("merchant_key", "merchant_name", "category", "custom_category_id"),
            null,
            null,
            null,
            null,
            null,
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val rule = MerchantCategoryRule(
                    merchantKey = cursor.getString(0),
                    merchantName = cursor.getString(1),
                    category = enumValueOrDefault(cursor.getString(2), ExpenseCategory.OTHER),
                    customCategoryId = cursor.longOrNull(3),
                )
                result[rule.merchantKey] = rule
            }
        }
        return result
    }

    private fun getMerchantAliasMap(db: SQLiteDatabase): Map<String, MerchantAliasRule> {
        val result = linkedMapOf<String, MerchantAliasRule>()
        db.query(
            "merchant_aliases",
            arrayOf("alias_key", "alias_name", "canonical_name", "updated_at"),
            null,
            null,
            null,
            null,
            null,
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val rule = MerchantAliasRule(
                    aliasKey = cursor.getString(0),
                    aliasName = cursor.getString(1),
                    canonicalName = cursor.getString(2),
                    updatedAt = cursor.getLong(3),
                )
                result[rule.aliasKey] = rule
            }
        }
        return result
    }

    private fun resolveAccountId(
        db: SQLiteDatabase,
        accountHint: String?,
        source: TransactionSource,
        sender: String,
    ): Long? {
        val hint = accountHint?.filter(Char::isDigit)?.takeLast(4)?.takeIf(String::isNotBlank) ?: return null
        val institution = sender
            .replace(Regex("(?i)^(?:AD|AX|BZ|JD|JM|VK|VM|TM|CP|BP|HP|QP)-"), "")
            .replace(Regex("[^A-Za-z0-9 ]"), " ")
            .trim()
            .ifBlank { source.name.lowercase(Locale.ROOT).replaceFirstChar(Char::titlecase) }
        val accountType = when (source) {
            TransactionSource.CARD -> AccountType.CREDIT_CARD
            TransactionSource.WALLET -> AccountType.WALLET
            TransactionSource.BANK, TransactionSource.UPI -> AccountType.BANK_ACCOUNT
            TransactionSource.MANUAL, TransactionSource.STATEMENT -> AccountType.OTHER
        }
        val identityKey = "${source.name}:${institution.lowercase(Locale.ROOT)}:$hint"
        db.query(
            "accounts",
            arrayOf("id"),
            "identity_key = ?",
            arrayOf(identityKey),
            null,
            null,
            null,
            "1",
        ).use { cursor ->
            if (cursor.moveToFirst()) return cursor.getLong(0)
        }
        data class AccountCandidate(val id: Long, val institution: String?)
        val sameHintAndType = mutableListOf<AccountCandidate>()
        db.query(
            "accounts",
            arrayOf("id", "institution"),
            "type = ? AND account_hint = ?",
            arrayOf(accountType.name, hint),
            null,
            null,
            null,
        ).use { cursor ->
            while (cursor.moveToNext()) {
                sameHintAndType += AccountCandidate(cursor.getLong(0), cursor.getString(1))
            }
        }
        val institutionKey = BankSmsSupport.bankKey(institution)
        val compatible = sameHintAndType.firstOrNull {
            institutionKey != null && BankSmsSupport.bankKey(it.institution.orEmpty()) == institutionKey
        } ?: sameHintAndType.singleOrNull()
        compatible?.let { candidate ->
            db.update(
                "accounts",
                ContentValues().apply { put("identity_key", identityKey) },
                "id = ?",
                arrayOf(candidate.id.toString()),
            )
            return candidate.id
        }
        return db.insertOrThrow(
            "accounts",
            null,
            ContentValues().apply {
                put("identity_key", identityKey)
                put("name", "$institution •$hint")
                put("type", accountType.name)
                put("account_hint", hint)
                put("institution", institution)
                put("created_at", System.currentTimeMillis())
            },
        )
    }

    private fun findAvailabilityAccountId(
        db: SQLiteDatabase,
        update: AccountAvailabilityUpdate,
    ): Long? {
        data class Candidate(
            val id: Long,
            val type: AccountType,
            val hint: String?,
            val bankKey: String?,
        )

        val candidates = mutableListOf<Candidate>()
        db.query(
            "accounts",
            arrayOf("id", "type", "account_hint", "institution", "name"),
            null,
            null,
            null,
            null,
            null,
        ).use { cursor ->
            while (cursor.moveToNext()) {
                candidates += Candidate(
                    id = cursor.getLong(0),
                    type = enumValueOrDefault(cursor.getString(1), AccountType.OTHER),
                    hint = cursor.getString(2),
                    bankKey = BankSmsSupport.bankKey("${cursor.getString(3).orEmpty()} ${cursor.getString(4).orEmpty()}"),
                )
            }
        }
        val sameType = candidates.filter { it.type == update.accountType }
        update.accountHint?.let { hint ->
            sameType.firstOrNull { it.hint == hint && it.bankKey == update.bankKey }?.let { return it.id }
            sameType.firstOrNull { it.hint == hint }?.let { return it.id }
        }
        return sameType.filter { it.bankKey == update.bankKey }.singleOrNull()?.id
    }

    private fun createAvailabilityAccount(
        db: SQLiteDatabase,
        update: AccountAvailabilityUpdate,
    ): Long? {
        val hint = update.accountHint ?: return null
        val identityKey = "availability:${update.bankKey}:$hint:${update.accountType.name}"
        val id = db.insertWithOnConflict(
            "accounts",
            null,
            ContentValues().apply {
                put("identity_key", identityKey)
                put("name", "${update.institutionName} •$hint")
                put("type", update.accountType.name)
                put("account_hint", hint)
                put("institution", update.institutionName)
                put("created_at", update.fetchedAt)
            },
            SQLiteDatabase.CONFLICT_IGNORE,
        )
        if (id != -1L) return id
        db.query(
            "accounts",
            arrayOf("id"),
            "identity_key = ?",
            arrayOf(identityKey),
            null,
            null,
            null,
            "1",
        ).use { cursor -> if (cursor.moveToFirst()) return cursor.getLong(0) }
        return null
    }

    private fun applyAccountAvailability(
        db: SQLiteDatabase,
        accountId: Long,
        update: AccountAvailabilityUpdate,
    ): Boolean {
        val previousFetchedAt = db.query(
            "accounts",
            arrayOf("availability_fetched_at"),
            "id = ?",
            arrayOf(accountId.toString()),
            null,
            null,
            null,
            "1",
        ).use { cursor -> if (cursor.moveToFirst()) cursor.longOrNull(0) else null }
        if (previousFetchedAt != null && previousFetchedAt >= update.fetchedAt) return false

        val values = ContentValues().apply {
            update.balanceMinor?.let { put("balance_minor", it) }
            update.availableCreditMinor?.let { put("available_credit_minor", it) }
            update.creditLimitMinor?.let { put("credit_limit_minor", it) }
            put("availability_fetched_at", update.fetchedAt)
            put("availability_sender", update.sender.take(64))
        }
        return db.update("accounts", values, "id = ?", arrayOf(accountId.toString())) > 0
    }

    private fun insertBalanceSnapshot(
        db: SQLiteDatabase,
        accountId: Long,
        update: AccountAvailabilityUpdate,
    ) {
        db.insertWithOnConflict(
            "balance_history",
            null,
            ContentValues().apply {
                put("account_id", accountId)
                putNullableLong("balance_minor", update.balanceMinor)
                putNullableLong("available_credit_minor", update.availableCreditMinor)
                putNullableLong("credit_limit_minor", update.creditLimitMinor)
                put("recorded_at", update.fetchedAt)
                put("sender", update.sender.take(64).ifBlank { "Balance alert" })
            },
            SQLiteDatabase.CONFLICT_IGNORE,
        )
    }

    private fun backfillBalanceHistory(db: SQLiteDatabase) {
        db.execSQL(
            """
            INSERT OR IGNORE INTO balance_history (
                account_id, balance_minor, available_credit_minor, credit_limit_minor,
                recorded_at, sender
            )
            SELECT
                id, balance_minor, available_credit_minor, credit_limit_minor,
                availability_fetched_at, COALESCE(availability_sender, 'Saved account balance')
            FROM accounts
            WHERE availability_fetched_at IS NOT NULL
              AND (balance_minor IS NOT NULL OR available_credit_minor IS NOT NULL OR credit_limit_minor IS NOT NULL)
            """.trimIndent(),
        )
    }

    private fun backfillAccounts(db: SQLiteDatabase) {
        data class Candidate(
            val id: Long,
            val hint: String,
            val source: TransactionSource,
            val sender: String,
        )
        val candidates = mutableListOf<Candidate>()
        db.query(
            "transactions",
            arrayOf("id", "account_hint", "source", "sender"),
            "account_hint IS NOT NULL AND account_hint != ''",
            null,
            null,
            null,
            null,
        ).use { cursor ->
            while (cursor.moveToNext()) {
                candidates += Candidate(
                    id = cursor.getLong(0),
                    hint = cursor.getString(1),
                    source = enumValueOrDefault(cursor.getString(2), TransactionSource.BANK),
                    sender = cursor.getString(3),
                )
            }
        }
        candidates.forEach { candidate ->
            val accountId = resolveAccountId(db, candidate.hint, candidate.source, candidate.sender)
            db.update(
                "transactions",
                ContentValues().apply { putNullableLong("account_id", accountId) },
                "id = ?",
                arrayOf(candidate.id.toString()),
            )
        }
    }

    private fun findReconciliation(db: SQLiteDatabase, id: Long): MonthlyReconciliation? =
        queryReconciliation(db, "id = ?", arrayOf(id.toString()))

    private fun validateReconciliation(reconciliation: MonthlyReconciliation) {
        require(reconciliation.year in 2000..3000) { "Reconciliation year is invalid" }
        require(reconciliation.month in 1..12) { "Reconciliation month is invalid" }
        require(reconciliation.statementTransactionCount >= 0) { "Statement count cannot be negative" }
        require(reconciliation.matchedTransactionCount >= 0) { "Matched count cannot be negative" }
        require(reconciliation.unmatchedStatementCount >= 0) { "Unmatched count cannot be negative" }
        require(reconciliation.unmatchedAppCount >= 0) { "Unmatched count cannot be negative" }
        require(
            reconciliation.matchedTransactionCount + reconciliation.unmatchedStatementCount ==
                reconciliation.statementTransactionCount,
        ) { "Matched plus statement-only counts must equal statement count" }
    }

    private fun auditTransactionUpdate(
        id: Long,
        label: String,
        values: ContentValues,
        validate: (SQLiteDatabase) -> Unit = {},
    ): Int {
        val db = writableDatabase
        val beforePayload = transactionAuditPayload(db, id) ?: return 0
        db.beginTransaction()
        return try {
            validate(db)
            val updated = db.update("transactions", values, "id = ?", arrayOf(id.toString()))
            if (updated > 0) {
                insertAuditEvent(
                    db,
                    newAuditBatchId("transaction-update"),
                    label,
                    AuditEntityType.TRANSACTION,
                    id.toString(),
                    AuditAction.UPDATE,
                    beforePayload,
                    requireNotNull(transactionAuditPayload(db, id)),
                )
            }
            db.setTransactionSuccessful()
            updated
        } finally {
            db.endTransaction()
        }
    }

    private fun findReconciliation(
        db: SQLiteDatabase,
        accountId: Long,
        year: Int,
        month: Int,
    ): MonthlyReconciliation? = queryReconciliation(
        db,
        "account_id = ? AND year = ? AND month = ?",
        arrayOf(accountId.toString(), year.toString(), month.toString()),
    )

    private fun queryReconciliation(
        db: SQLiteDatabase,
        selection: String,
        args: Array<String>,
    ): MonthlyReconciliation? = db.query(
        "monthly_reconciliations",
        arrayOf(
            "id", "account_id", "year", "month", "opening_balance_minor", "closing_balance_minor",
            "statement_transaction_count", "matched_transaction_count", "unmatched_statement_count",
            "unmatched_app_count", "status", "notes", "reconciled_at", "updated_at",
        ),
        selection,
        args,
        null,
        null,
        null,
        "1",
    ).use { cursor ->
        if (!cursor.moveToFirst()) return@use null
        MonthlyReconciliation(
            id = cursor.getLong(0),
            accountId = cursor.getLong(1),
            year = cursor.getInt(2),
            month = cursor.getInt(3),
            openingBalanceMinor = cursor.longOrNull(4),
            closingBalanceMinor = cursor.longOrNull(5),
            statementTransactionCount = cursor.getInt(6),
            matchedTransactionCount = cursor.getInt(7),
            unmatchedStatementCount = cursor.getInt(8),
            unmatchedAppCount = cursor.getInt(9),
            status = enumValueOrDefault(cursor.getString(10), ReconciliationStatus.DRAFT),
            notes = cursor.getString(11),
            reconciledAt = cursor.longOrNull(12),
            updatedAt = cursor.getLong(13),
        )
    }

    private fun reconciliationValues(item: MonthlyReconciliation, includeId: Boolean = false) = ContentValues().apply {
        if (includeId) put("id", item.id)
        put("account_id", item.accountId)
        put("year", item.year)
        put("month", item.month)
        putNullableLong("opening_balance_minor", item.openingBalanceMinor)
        putNullableLong("closing_balance_minor", item.closingBalanceMinor)
        put("statement_transaction_count", item.statementTransactionCount.coerceAtLeast(0))
        put("matched_transaction_count", item.matchedTransactionCount.coerceAtLeast(0))
        put("unmatched_statement_count", item.unmatchedStatementCount.coerceAtLeast(0))
        put("unmatched_app_count", item.unmatchedAppCount.coerceAtLeast(0))
        put("status", item.status.name)
        putNullableText("notes", item.notes?.trim()?.replace(Regex("\\s+"), " ")?.take(240)?.takeIf(String::isNotBlank))
        putNullableLong("reconciled_at", item.reconciledAt)
        put("updated_at", item.updatedAt)
    }

    private fun findTransactionLink(db: SQLiteDatabase, id: Long): TransactionLink? = db.query(
        "transaction_links",
        arrayOf("id", "source_transaction_id", "target_transaction_id", "link_type", "note", "created_at"),
        "id = ?",
        arrayOf(id.toString()),
        null,
        null,
        null,
        "1",
    ).use { cursor ->
        if (!cursor.moveToFirst()) return@use null
        TransactionLink(
            id = cursor.getLong(0),
            sourceTransactionId = cursor.getLong(1),
            targetTransactionId = cursor.getLong(2),
            type = enumValueOrDefault(cursor.getString(3), TransactionLinkType.TRANSFER),
            note = cursor.getString(4),
            createdAt = cursor.getLong(5),
        )
    }

    private fun transactionLinkValues(link: TransactionLink, includeId: Boolean = false) = ContentValues().apply {
        if (includeId) put("id", link.id)
        put("source_transaction_id", link.sourceTransactionId)
        put("target_transaction_id", link.targetTransactionId)
        put("link_type", link.type.name)
        putNullableText("note", link.note)
        put("created_at", link.createdAt)
    }

    private fun insertAuditEvent(
        db: SQLiteDatabase,
        batchId: String,
        batchLabel: String,
        entityType: AuditEntityType,
        entityId: String,
        action: AuditAction,
        beforePayload: String?,
        afterPayload: String?,
        occurredAt: Long = System.currentTimeMillis(),
        reversesEventId: Long? = null,
        explicitId: Long? = null,
    ): Long = db.insertOrThrow(
        "audit_events",
        null,
        ContentValues().apply {
            explicitId?.let { put("id", it) }
            put("batch_id", batchId)
            put("batch_label", batchLabel.take(96))
            put("entity_type", entityType.name)
            put("entity_id", entityId.take(80))
            put("action", action.name)
            putNullableText("before_payload", beforePayload)
            putNullableText("after_payload", afterPayload)
            put("occurred_at", occurredAt)
            putNullableLong("reverses_event_id", reversesEventId)
        },
    )

    private fun getAuditEventsForBatch(db: SQLiteDatabase, batchId: String): List<AuditEvent> {
        val result = mutableListOf<AuditEvent>()
        db.query(
            "audit_events",
            arrayOf(
                "id", "batch_id", "batch_label", "entity_type", "entity_id", "action",
                "before_payload", "after_payload", "occurred_at", "reverses_event_id",
            ),
            "batch_id = ?",
            arrayOf(batchId),
            null,
            null,
            "id ASC",
        ).use { cursor ->
            while (cursor.moveToNext()) {
                result += AuditEvent(
                    id = cursor.getLong(0),
                    batchId = cursor.getString(1),
                    batchLabel = cursor.getString(2),
                    entityType = enumValueOrDefault(cursor.getString(3), AuditEntityType.TRANSACTION),
                    entityId = cursor.getString(4),
                    action = enumValueOrDefault(cursor.getString(5), AuditAction.UPDATE),
                    beforePayload = cursor.getString(6),
                    afterPayload = cursor.getString(7),
                    occurredAt = cursor.getLong(8),
                    reversesEventId = cursor.longOrNull(9),
                )
            }
        }
        return result
    }

    private fun deleteAuditEntity(db: SQLiteDatabase, entityType: AuditEntityType, entityId: String) {
        when (entityType) {
            AuditEntityType.TRANSACTION -> {
                val transactionId = entityId.toLong()
                require(getAuditSplitDependencies(db).none {
                    it.transactionId == transactionId || it.linkedIncomingTransactionId == transactionId
                }) { "Transaction $transactionId is used by an expense split" }
                val hasLinks = db.rawQuery(
                    "SELECT 1 FROM transaction_links WHERE source_transaction_id = ? OR target_transaction_id = ? LIMIT 1",
                    arrayOf(entityId, entityId),
                ).use { it.moveToFirst() }
                require(!hasLinks) { "Transaction $transactionId has transaction links" }
            }
            AuditEntityType.TRANSACTION_LINK -> {
                val link = findTransactionLink(db, entityId.toLong())
                require(link == null || getAuditSplitDependencies(db).none { it.dependsOn(link) }) {
                    "Transaction link $entityId is used by an expense split"
                }
            }
            AuditEntityType.MONTHLY_RECONCILIATION -> Unit
        }
        val table = when (entityType) {
            AuditEntityType.TRANSACTION -> "transactions"
            AuditEntityType.TRANSACTION_LINK -> "transaction_links"
            AuditEntityType.MONTHLY_RECONCILIATION -> "monthly_reconciliations"
        }
        db.delete(table, "id = ?", arrayOf(entityId))
    }

    private fun restoreAuditEntity(
        db: SQLiteDatabase,
        entityType: AuditEntityType,
        payload: String,
        replace: Boolean,
    ) {
        when (entityType) {
            AuditEntityType.TRANSACTION -> {
                val item = decodeTransactionAuditPayload(payload)
                if (replace) {
                    val validation = validateTransactionTypeChange(
                        transactionId = item.record.id,
                        newType = item.record.type,
                        transactions = getTransactions(),
                        links = getTransactionLinks(db),
                        expenseSplits = getExpenseSplits(db),
                    )
                    require(validation.isValid) {
                        "Transaction ${item.record.id} is used by an incompatible financial relationship (${validation.issue})"
                    }
                    check(
                        db.update(
                            "transactions",
                            transactionAuditRestoreValues(item.record),
                            "id = ?",
                            arrayOf(item.record.id.toString()),
                        ) == 1,
                    ) {
                        "Transaction ${item.record.id} is unavailable for undo"
                    }
                } else {
                    db.insertOrThrow(
                        "transactions",
                        null,
                        transactionValues(item.record, includeId = true).apply {
                            if (item.rawMessageCipher == null) {
                                putNull("raw_message_cipher")
                            } else {
                                put("raw_message_cipher", item.rawMessageCipher)
                            }
                            put("created_at", item.createdAt)
                        },
                    )
                }
            }
            AuditEntityType.TRANSACTION_LINK -> {
                val item = decodeTransactionLinkAuditPayload(payload)
                val transactions = getTransactions()
                val candidateLinks = getTransactionLinks(db).filter { it.id != item.id } + item
                val validation = validateTransactionLink(
                    candidate = item,
                    existingLinks = candidateLinks,
                    transactionIds = transactions.mapTo(mutableSetOf(), TransactionRecord::id),
                    transactionsById = transactions.associateBy(TransactionRecord::id),
                )
                require(validation.isValid) {
                    "Transaction link ${item.id} conflicts with newer ledger relationships (${validation.issue})"
                }
                if (replace) {
                    check(db.update("transaction_links", transactionLinkValues(item), "id = ?", arrayOf(item.id.toString())) == 1) {
                        "Transaction link ${item.id} is unavailable for undo"
                    }
                } else {
                    db.insertOrThrow("transaction_links", null, transactionLinkValues(item, includeId = true))
                }
            }
            AuditEntityType.MONTHLY_RECONCILIATION -> {
                val item = decodeReconciliationAuditPayload(payload)
                if (replace) {
                    check(
                        db.update(
                            "monthly_reconciliations",
                            reconciliationValues(item),
                            "id = ?",
                            arrayOf(item.id.toString()),
                        ) == 1,
                    ) { "Monthly reconciliation ${item.id} is unavailable for undo" }
                } else {
                    db.insertOrThrow(
                        "monthly_reconciliations",
                        null,
                        reconciliationValues(item, includeId = true),
                    )
                }
            }
        }
    }

    private fun transactionAuditPayload(db: SQLiteDatabase, id: Long): String? {
        val record = getTransactions().firstOrNull { it.id == id } ?: return null
        val storage = db.query(
            "transactions",
            arrayOf("raw_message_cipher", "created_at"),
            "id = ?",
            arrayOf(id.toString()),
            null,
            null,
            null,
            "1",
        ).use { cursor ->
            if (!cursor.moveToFirst()) return@use null
            (if (cursor.isNull(0)) null else cursor.getBlob(0)) to cursor.getLong(1)
        } ?: return null
        return TransactionAuditPayloadCodec.encode(
            TransactionAuditPayload(
                record = record,
                rawMessageCipher = storage.first,
                createdAt = storage.second,
            ),
        )
    }

    /** Restores editable transaction fields while retaining encrypted source text and creation metadata. */
    private fun transactionAuditRestoreValues(record: TransactionRecord) = ContentValues().apply {
        put("source_message_id", record.sourceMessageId)
        put("amount_minor", record.amountMinor)
        put("merchant", record.merchant)
        putNullableText("account_hint", record.accountHint)
        put("category", record.category.name)
        put("type", record.type.name)
        put("occurred_at", record.occurredAt)
        put("source", record.source.name)
        put("sender", record.sender)
        putNullableText("note", record.note)
        putNullableLong("account_id", record.accountId)
        putNullableLong("custom_category_id", record.customCategoryId)
        put("tags", encodeTags(record.tags))
        put("review_status", record.reviewStatus.name)
        putNullableText("review_reason", record.reviewReason)
        putNullableLong("original_amount_minor", record.originalAmountMinor)
        putNullableText("original_currency", record.originalCurrency)
        if (record.exchangeRate == null) putNull("exchange_rate") else put("exchange_rate", record.exchangeRate)
    }

    private fun decodeTransactionAuditPayload(payload: String): TransactionAuditPayload =
        TransactionAuditPayloadCodec.decode(payload)

    private fun encodeTransactionLinkAuditPayload(item: TransactionLink): String = encodeAuditPayload { data ->
        data.writeLong(item.id)
        data.writeLong(item.sourceTransactionId)
        data.writeLong(item.targetTransactionId)
        data.writeUTF(item.type.name)
        data.writeNullableString(item.note)
        data.writeLong(item.createdAt)
    }

    private fun decodeTransactionLinkAuditPayload(payload: String): TransactionLink = decodeAuditPayload(payload) { data ->
        TransactionLink(
            id = data.readLong(),
            sourceTransactionId = data.readLong(),
            targetTransactionId = data.readLong(),
            type = data.readEnumValue(TransactionLinkType.TRANSFER),
            note = data.readNullableString(),
            createdAt = data.readLong(),
        )
    }

    private fun encodeReconciliationAuditPayload(item: MonthlyReconciliation): String = encodeAuditPayload { data ->
        data.writeLong(item.id)
        data.writeLong(item.accountId)
        data.writeInt(item.year)
        data.writeInt(item.month)
        data.writeNullableLongValue(item.openingBalanceMinor)
        data.writeNullableLongValue(item.closingBalanceMinor)
        data.writeInt(item.statementTransactionCount)
        data.writeInt(item.matchedTransactionCount)
        data.writeInt(item.unmatchedStatementCount)
        data.writeInt(item.unmatchedAppCount)
        data.writeUTF(item.status.name)
        data.writeNullableString(item.notes)
        data.writeNullableLongValue(item.reconciledAt)
        data.writeLong(item.updatedAt)
    }

    private fun decodeReconciliationAuditPayload(payload: String): MonthlyReconciliation = decodeAuditPayload(payload) { data ->
        MonthlyReconciliation(
            id = data.readLong(),
            accountId = data.readLong(),
            year = data.readInt(),
            month = data.readInt(),
            openingBalanceMinor = data.readNullableLongValue(),
            closingBalanceMinor = data.readNullableLongValue(),
            statementTransactionCount = data.readInt(),
            matchedTransactionCount = data.readInt(),
            unmatchedStatementCount = data.readInt(),
            unmatchedAppCount = data.readInt(),
            status = data.readEnumValue(ReconciliationStatus.DRAFT),
            notes = data.readNullableString(),
            reconciledAt = data.readNullableLongValue(),
            updatedAt = data.readLong(),
        )
    }

    private fun encodeAuditPayload(write: (DataOutputStream) -> Unit): String {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use(write)
        return Base64.getEncoder().encodeToString(bytes.toByteArray())
    }

    private fun <T> decodeAuditPayload(payload: String, read: (DataInputStream) -> T): T =
        DataInputStream(ByteArrayInputStream(Base64.getDecoder().decode(payload))).use(read)

    private fun DataOutputStream.writeNullableString(value: String?) {
        writeBoolean(value != null)
        if (value != null) writeUTF(value)
    }

    private fun DataOutputStream.writeNullableLongValue(value: Long?) {
        writeBoolean(value != null)
        if (value != null) writeLong(value)
    }

    private fun DataOutputStream.writeNullableDoubleValue(value: Double?) {
        writeBoolean(value != null)
        if (value != null) writeDouble(value)
    }

    private fun DataInputStream.readNullableString(): String? = if (readBoolean()) readUTF() else null

    private fun DataInputStream.readNullableLongValue(): Long? = if (readBoolean()) readLong() else null

    private fun DataInputStream.readNullableDoubleValue(): Double? = if (readBoolean()) readDouble() else null

    private inline fun <reified T : Enum<T>> DataInputStream.readEnumValue(default: T): T {
        val value = readUTF()
        return enumValues<T>().firstOrNull { it.name == value } ?: default
    }

    private fun newAuditBatchId(prefix: String): String = "$prefix-${UUID.randomUUID()}"

    private fun clearAllTables(db: SQLiteDatabase) {
        db.delete("expense_splits", null, null)
        db.delete("savings_contributions", null, null)
        db.delete("savings_goals", null, null)
        db.delete("payment_commitments", null, null)
        db.delete("transaction_links", null, null)
        db.delete("monthly_reconciliations", null, null)
        db.delete("audit_events", null, null)
        db.delete("transactions", null, null)
        db.delete("budgets", null, null)
        db.delete("merchant_categories", null, null)
        db.delete("merchant_aliases", null, null)
        db.delete("loans", null, null)
        db.delete("balance_history", null, null)
        db.delete("bills", null, null)
        db.delete("net_worth_items", null, null)
        db.delete("smart_category_rules", null, null)
        db.delete("custom_categories", null, null)
        db.delete("accounts", null, null)
        db.delete("exchange_rates", null, null)
    }

    private fun restoredAccountIdentityKey(account: AccountProfile): String = buildString {
        append("restored:")
        append(account.id)
        append(':')
        append(account.type.name.lowercase(Locale.ROOT))
        append(':')
        append(account.institution?.lowercase(Locale.ROOT)?.replace(Regex("[^a-z0-9]+"), "-").orEmpty())
        append(':')
        append(account.accountHint.orEmpty())
    }

    private fun hasColumn(db: SQLiteDatabase, table: String, column: String): Boolean {
        require(table.matches(Regex("[a-z_]+")))
        db.rawQuery("PRAGMA table_info($table)", null).use { cursor ->
            val nameIndex = cursor.getColumnIndex("name")
            while (cursor.moveToNext()) {
                if (nameIndex >= 0 && cursor.getString(nameIndex) == column) return true
            }
        }
        return false
    }

    private fun encodeTags(tags: List<String>): String = tags
        .map { it.trim().replace(TAG_SEPARATOR, " ").take(24) }
        .filter(String::isNotBlank)
        .distinctBy { it.lowercase(Locale.ROOT) }
        .take(6)
        .joinToString(TAG_SEPARATOR)

    private fun decodeTags(value: String?): List<String> = value
        ?.split(TAG_SEPARATOR)
        ?.map(String::trim)
        ?.filter(String::isNotBlank)
        .orEmpty()

    private fun normalizeColor(value: String): String =
        value.trim().uppercase(Locale.ROOT).takeIf { it.matches(Regex("#[0-9A-F]{6}")) } ?: "#7784FF"

    private fun ContentValues.putNullableLong(key: String, value: Long?) {
        if (value == null) putNull(key) else put(key, value)
    }

    private fun ContentValues.putNullableText(key: String, value: String?) {
        if (value == null) putNull(key) else put(key, value)
    }

    private fun android.database.Cursor.longOrNull(index: Int): Long? =
        if (isNull(index)) null else getLong(index)

    private fun android.database.Cursor.intOrNull(index: Int): Int? =
        if (isNull(index)) null else getInt(index)

    private fun android.database.Cursor.doubleOrNull(index: Int): Double? =
        if (isNull(index)) null else getDouble(index)

    private inline fun <reified T : Enum<T>> enumValueOrDefault(value: String?, default: T): T =
        enumValues<T>().firstOrNull { it.name == value } ?: default

    private companion object {
        const val DATABASE_NAME = "paisalens.db"
        const val DATABASE_VERSION = 9
        const val TAG_SEPARATOR = "\u001F"
    }
}
