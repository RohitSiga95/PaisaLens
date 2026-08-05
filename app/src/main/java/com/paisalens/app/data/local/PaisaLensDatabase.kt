package com.paisalens.app.data.local

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.paisalens.app.data.model.CategoryBudget
import com.paisalens.app.data.model.ExpenseCategory
import com.paisalens.app.data.model.ParsedTransaction
import com.paisalens.app.data.model.TransactionRecord
import com.paisalens.app.data.model.TransactionSource
import com.paisalens.app.data.model.TransactionType
import com.paisalens.app.data.model.normalizedMerchantKey

class PaisaLensDatabase(context: Context) :
    SQLiteOpenHelper(context, DATABASE_NAME, null, DATABASE_VERSION) {

    override fun onConfigure(db: SQLiteDatabase) {
        super.onConfigure(db)
        db.setForeignKeyConstraintsEnabled(true)
        db.enableWriteAheadLogging()
    }

    override fun onCreate(db: SQLiteDatabase) {
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
                raw_message_cipher BLOB,
                created_at INTEGER NOT NULL
            )
            """.trimIndent(),
        )
        db.execSQL("CREATE INDEX index_transactions_date ON transactions(occurred_at DESC)")
        db.execSQL("CREATE INDEX index_transactions_category ON transactions(category)")
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
    }

    fun insertAll(items: List<Pair<ParsedTransaction, ByteArray>>): Int {
        if (items.isEmpty()) return 0
        val db = writableDatabase
        val merchantCategories = getMerchantCategories(db)
        var inserted = 0
        db.beginTransaction()
        try {
            items.forEach { (item, encryptedBody) ->
                val merchantCategory = if (item.type == TransactionType.EXPENSE) {
                    merchantCategories[normalizedMerchantKey(item.merchant)]
                } else {
                    null
                }
                val values = transactionValues(item, encryptedBody, merchantCategory)
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
        val values = ContentValues().apply {
            put("source_message_id", record.sourceMessageId)
            put("amount_minor", record.amountMinor)
            put("merchant", record.merchant)
            put("account_hint", record.accountHint)
            put("category", record.category.name)
            put("type", record.type.name)
            put("occurred_at", record.occurredAt)
            put("source", record.source.name)
            put("sender", record.sender)
            put("note", record.note?.trim()?.takeIf(String::isNotBlank))
            putNull("raw_message_cipher")
            put("created_at", System.currentTimeMillis())
        }
        return writableDatabase.insertOrThrow("transactions", null, values)
    }

    fun getTransactions(): List<TransactionRecord> {
        val result = mutableListOf<TransactionRecord>()
        readableDatabase.query(
            "transactions",
            arrayOf(
                "id",
                "source_message_id",
                "amount_minor",
                "merchant",
                "account_hint",
                "category",
                "type",
                "occurred_at",
                "source",
                "sender",
                "note",
            ),
            null,
            null,
            null,
            null,
            "occurred_at DESC, id DESC",
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
                )
            }
        }
        return result
    }

    fun updateCategory(id: Long, category: ExpenseCategory): Int {
        val values = ContentValues().apply { put("category", category.name) }
        return writableDatabase.update("transactions", values, "id = ?", arrayOf(id.toString()))
    }

    fun updateMerchantCategory(merchant: String, category: ExpenseCategory): Int {
        val merchantKey = normalizedMerchantKey(merchant)
        if (merchantKey.isBlank()) return 0

        val db = writableDatabase
        var updated = 0
        db.beginTransaction()
        try {
            val mapping = ContentValues().apply {
                put("merchant_key", merchantKey)
                put("merchant_name", merchant.trim())
                put("category", category.name)
                put("updated_at", System.currentTimeMillis())
            }
            db.insertWithOnConflict(
                "merchant_categories",
                null,
                mapping,
                SQLiteDatabase.CONFLICT_REPLACE,
            )

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
                        TransactionType.EXPENSE &&
                        normalizedMerchantKey(cursor.getString(1)) == merchantKey
                    ) {
                        matchingIds += cursor.getLong(0)
                    }
                }
            }

            val values = ContentValues().apply { put("category", category.name) }
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

    fun getCategorizedMerchantKeys(): Set<String> =
        getMerchantCategories(readableDatabase).keys

    fun deleteTransaction(id: Long) {
        writableDatabase.delete("transactions", "id = ?", arrayOf(id.toString()))
    }

    fun clearTransactions() {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.delete("transactions", null, null)
            db.delete("merchant_categories", null, null)
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

    fun clearAll() {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.delete("transactions", null, null)
            db.delete("budgets", null, null)
            db.delete("merchant_categories", null, null)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    private fun transactionValues(
        item: ParsedTransaction,
        encryptedBody: ByteArray,
        merchantCategory: ExpenseCategory?,
    ) =
        ContentValues().apply {
            put("source_message_id", item.sourceMessageId)
            put("amount_minor", item.amountMinor)
            put("merchant", item.merchant)
            put("account_hint", item.accountHint)
            put("category", (merchantCategory ?: item.category).name)
            put("type", item.type.name)
            put("occurred_at", item.occurredAt)
            put("source", item.source.name)
            put("sender", item.sender)
            putNull("note")
            put("raw_message_cipher", encryptedBody)
            put("created_at", System.currentTimeMillis())
        }

    private inline fun <reified T : Enum<T>> enumValueOrDefault(value: String?, default: T): T =
        enumValues<T>().firstOrNull { it.name == value } ?: default

    private fun createMerchantCategoriesTable(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS merchant_categories (
                merchant_key TEXT PRIMARY KEY NOT NULL,
                merchant_name TEXT NOT NULL,
                category TEXT NOT NULL,
                updated_at INTEGER NOT NULL
            )
            """.trimIndent(),
        )
    }

    private fun getMerchantCategories(db: SQLiteDatabase): Map<String, ExpenseCategory> {
        val result = linkedMapOf<String, ExpenseCategory>()
        db.query(
            "merchant_categories",
            arrayOf("merchant_key", "category"),
            null,
            null,
            null,
            null,
            null,
        ).use { cursor ->
            while (cursor.moveToNext()) {
                result[cursor.getString(0)] =
                    enumValueOrDefault(cursor.getString(1), ExpenseCategory.OTHER)
            }
        }
        return result
    }

    private companion object {
        const val DATABASE_NAME = "paisalens.db"
        const val DATABASE_VERSION = 2
    }
}
