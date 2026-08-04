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
                raw_message_cipher BLOB,
                created_at INTEGER NOT NULL
            )
            """.trimIndent(),
        )
        db.execSQL("CREATE INDEX index_transactions_date ON transactions(occurred_at DESC)")
        db.execSQL("CREATE INDEX index_transactions_category ON transactions(category)")
        db.execSQL(
            """
            CREATE TABLE budgets (
                category TEXT PRIMARY KEY NOT NULL,
                limit_minor INTEGER NOT NULL CHECK(limit_minor >= 0)
            )
            """.trimIndent(),
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    fun insertAll(items: List<Pair<ParsedTransaction, ByteArray>>): Int {
        if (items.isEmpty()) return 0
        val db = writableDatabase
        var inserted = 0
        db.beginTransaction()
        try {
            items.forEach { (item, encryptedBody) ->
                val values = transactionValues(item, encryptedBody)
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
                )
            }
        }
        return result
    }

    fun updateCategory(id: Long, category: ExpenseCategory) {
        val values = ContentValues().apply { put("category", category.name) }
        writableDatabase.update("transactions", values, "id = ?", arrayOf(id.toString()))
    }

    fun deleteTransaction(id: Long) {
        writableDatabase.delete("transactions", "id = ?", arrayOf(id.toString()))
    }

    fun clearTransactions() {
        writableDatabase.delete("transactions", null, null)
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
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    private fun transactionValues(item: ParsedTransaction, encryptedBody: ByteArray) =
        ContentValues().apply {
            put("source_message_id", item.sourceMessageId)
            put("amount_minor", item.amountMinor)
            put("merchant", item.merchant)
            put("account_hint", item.accountHint)
            put("category", item.category.name)
            put("type", item.type.name)
            put("occurred_at", item.occurredAt)
            put("source", item.source.name)
            put("sender", item.sender)
            put("raw_message_cipher", encryptedBody)
            put("created_at", System.currentTimeMillis())
        }

    private inline fun <reified T : Enum<T>> enumValueOrDefault(value: String?, default: T): T =
        enumValues<T>().firstOrNull { it.name == value } ?: default

    private companion object {
        const val DATABASE_NAME = "paisalens.db"
        const val DATABASE_VERSION = 1
    }
}
