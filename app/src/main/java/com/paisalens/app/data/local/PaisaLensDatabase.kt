package com.paisalens.app.data.local

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.paisalens.app.data.model.AccountProfile
import com.paisalens.app.data.model.AccountType
import com.paisalens.app.data.model.CategoryBudget
import com.paisalens.app.data.model.CategorySelection
import com.paisalens.app.data.model.CustomCategory
import com.paisalens.app.data.model.ExpenseCategory
import com.paisalens.app.data.model.ExchangeRate
import com.paisalens.app.data.model.LoanAccount
import com.paisalens.app.data.model.MerchantAliasRule
import com.paisalens.app.data.model.MerchantCategoryRule
import com.paisalens.app.data.model.PaisaLensBackupSnapshot
import com.paisalens.app.data.model.ParsedTransaction
import com.paisalens.app.data.model.ReviewStatus
import com.paisalens.app.data.model.TransactionRecord
import com.paisalens.app.data.model.TransactionSource
import com.paisalens.app.data.model.TransactionType
import com.paisalens.app.data.model.normalizedMerchantKey
import com.paisalens.app.data.model.StatementImportResult
import java.util.Locale
import java.util.UUID

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
            db.execSQL("ALTER TABLE merchant_categories ADD COLUMN custom_category_id INTEGER")
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
    }

    fun insertAll(items: List<Pair<ParsedTransaction, ByteArray>>): Int {
        if (items.isEmpty()) return 0
        val db = writableDatabase
        val merchantCategories = getMerchantCategoryMap(db)
        val merchantAliases = getMerchantAliasMap(db)
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
                val values = transactionValues(item, encryptedBody, accountId, merchantRule, canonicalMerchant)
                if (db.insertWithOnConflict("transactions", null, values, SQLiteDatabase.CONFLICT_IGNORE) != -1L) {
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
        return writableDatabase.insertOrThrow("transactions", null, values)
    }

    fun insertImported(records: List<TransactionRecord>): StatementImportResult {
        if (records.isEmpty()) return StatementImportResult(0, 0)
        val db = writableDatabase
        val merchantCategories = getMerchantCategoryMap(db)
        val merchantAliases = getMerchantAliasMap(db)
        var inserted = 0
        db.beginTransaction()
        try {
            records.forEach { original ->
                val canonical = merchantAliases[normalizedMerchantKey(original.merchant)]?.canonicalName
                    ?: original.merchant
                val rule = merchantCategories[normalizedMerchantKey(canonical)]
                val record = original.copy(
                    merchant = canonical,
                    category = rule?.category ?: original.category,
                    customCategoryId = rule?.customCategoryId ?: original.customCategoryId,
                    reviewStatus = if (rule != null) ReviewStatus.CONFIRMED else original.reviewStatus,
                    reviewReason = if (rule != null) null else original.reviewReason,
                )
                if (db.insertWithOnConflict("transactions", null, transactionValues(record), SQLiteDatabase.CONFLICT_IGNORE) != -1L) {
                    inserted += 1
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
        return writableDatabase.update("transactions", values, "id = ?", arrayOf(id.toString()))
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

            val values = categoryValues(selection)
            matchingIds.forEach { id ->
                updated += db.update("transactions", values, "id = ?", arrayOf(id.toString()))
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
        writableDatabase.update("transactions", values, "id = ?", arrayOf(id.toString()))
    }

    fun updateTags(id: Long, tags: List<String>) {
        val values = ContentValues().apply { put("tags", encodeTags(tags)) }
        writableDatabase.update("transactions", values, "id = ?", arrayOf(id.toString()))
    }

    fun updateReviewStatus(id: Long, status: ReviewStatus) {
        val values = ContentValues().apply {
            put("review_status", status.name)
            if (status == ReviewStatus.CONFIRMED) putNull("review_reason")
        }
        writableDatabase.update("transactions", values, "id = ?", arrayOf(id.toString()))
    }

    fun updateAccount(id: Long, accountId: Long?) {
        val values = ContentValues().apply { putNullableLong("account_id", accountId) }
        writableDatabase.update("transactions", values, "id = ?", arrayOf(id.toString()))
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
        writableDatabase.update("transactions", values, "id = ?", arrayOf(id.toString()))
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
            db.query("transactions", arrayOf("id", "merchant"), null, null, null, null, null).use { cursor ->
                while (cursor.moveToNext()) {
                    if (normalizedMerchantKey(cursor.getString(1)) == aliasKey) matches += cursor.getLong(0)
                }
            }
            matches.forEach { id ->
                updated += db.update(
                    "transactions",
                    ContentValues().apply { put("merchant", cleanCanonical) },
                    "id = ?",
                    arrayOf(id.toString()),
                )
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
        writableDatabase.delete("transactions", "id = ?", arrayOf(id.toString()))
    }

    fun getAccounts(): List<AccountProfile> {
        val result = mutableListOf<AccountProfile>()
        readableDatabase.query(
            "accounts",
            arrayOf("id", "name", "type", "account_hint", "institution"),
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
        }
        writableDatabase.update("accounts", values, "id = ?", arrayOf(account.id.toString()))
    }

    fun deleteAccount(id: Long) {
        writableDatabase.delete("accounts", "id = ?", arrayOf(id.toString()))
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
                        put("identity_key", "backup:${account.id}:${UUID.randomUUID()}")
                        put("name", account.name)
                        put("type", account.type.name)
                        putNullableText("account_hint", account.accountHint)
                        putNullableText("institution", account.institution)
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
            snapshot.transactions.forEach { transaction ->
                db.insertOrThrow("transactions", null, transactionValues(transaction, includeId = true))
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
        canonicalMerchant: String,
    ) = ContentValues().apply {
        put("source_message_id", item.sourceMessageId)
        put("amount_minor", item.amountMinor)
        put("merchant", canonicalMerchant)
        putNullableText("account_hint", item.accountHint)
        put("category", (merchantRule?.category ?: item.category).name)
        put("type", item.type.name)
        put("occurred_at", item.occurredAt)
        put("source", item.source.name)
        put("sender", item.sender)
        putNull("note")
        putNullableLong("account_id", accountId)
        putNullableLong("custom_category_id", merchantRule?.customCategoryId)
        put("tags", "")
        put("review_status", if (merchantRule != null) ReviewStatus.CONFIRMED.name else item.reviewStatus.name)
        putNullableText("review_reason", if (merchantRule != null) null else item.reviewReason)
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
        val accountType = when (source) {
            TransactionSource.CARD -> AccountType.CREDIT_CARD
            TransactionSource.WALLET -> AccountType.WALLET
            TransactionSource.BANK, TransactionSource.UPI -> AccountType.BANK_ACCOUNT
            TransactionSource.MANUAL, TransactionSource.STATEMENT -> AccountType.OTHER
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

    private fun clearAllTables(db: SQLiteDatabase) {
        db.delete("transactions", null, null)
        db.delete("budgets", null, null)
        db.delete("merchant_categories", null, null)
        db.delete("merchant_aliases", null, null)
        db.delete("loans", null, null)
        db.delete("custom_categories", null, null)
        db.delete("accounts", null, null)
        db.delete("exchange_rates", null, null)
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

    private fun android.database.Cursor.doubleOrNull(index: Int): Double? =
        if (isNull(index)) null else getDouble(index)

    private inline fun <reified T : Enum<T>> enumValueOrDefault(value: String?, default: T): T =
        enumValues<T>().firstOrNull { it.name == value } ?: default

    private companion object {
        const val DATABASE_NAME = "paisalens.db"
        const val DATABASE_VERSION = 4
        const val TAG_SEPARATOR = "\u001F"
    }
}
