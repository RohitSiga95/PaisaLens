package com.paisalens.app.data.repository

import com.paisalens.app.data.local.PaisaLensDatabase
import com.paisalens.app.data.model.CategoryBudget
import com.paisalens.app.data.model.ExpenseCategory
import com.paisalens.app.data.model.ParsedTransaction
import com.paisalens.app.data.model.TransactionRecord
import com.paisalens.app.data.model.TransactionSource
import com.paisalens.app.data.model.TransactionType
import com.paisalens.app.security.SensitiveDataCipher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.util.UUID

class TransactionRepository(
    private val database: PaisaLensDatabase,
    private val cipher: SensitiveDataCipher,
) {
    private val _transactions = MutableStateFlow<List<TransactionRecord>>(emptyList())
    val transactions: StateFlow<List<TransactionRecord>> = _transactions.asStateFlow()

    private val _budgets = MutableStateFlow<List<CategoryBudget>>(emptyList())
    val budgets: StateFlow<List<CategoryBudget>> = _budgets.asStateFlow()

    private val _categorizedMerchantKeys = MutableStateFlow<Set<String>>(emptySet())
    val categorizedMerchantKeys: StateFlow<Set<String>> = _categorizedMerchantKeys.asStateFlow()

    suspend fun load() = withContext(Dispatchers.IO) {
        refresh()
    }

    suspend fun insertParsed(items: List<ParsedTransaction>): Int = withContext(Dispatchers.IO) {
        val encrypted = items.map { it to cipher.encrypt(it.rawMessage) }
        val count = database.insertAll(encrypted)
        refresh()
        count
    }

    suspend fun addManual(
        amountMinor: Long,
        merchant: String,
        category: ExpenseCategory,
        type: TransactionType,
        note: String?,
        occurredAt: Long = System.currentTimeMillis(),
    ) = withContext(Dispatchers.IO) {
        database.insertManual(
            TransactionRecord(
                sourceMessageId = "manual-" + UUID.randomUUID(),
                amountMinor = amountMinor,
                merchant = merchant.trim().ifBlank { "Manual transaction" },
                accountHint = null,
                category = if (type == TransactionType.INCOME) ExpenseCategory.INCOME else category,
                type = type,
                occurredAt = occurredAt,
                source = TransactionSource.MANUAL,
                sender = "Added manually",
                note = note,
            ),
        )
        if (type == TransactionType.EXPENSE) {
            database.updateMerchantCategory(merchant, category)
        }
        refresh()
    }

    suspend fun updateCategory(
        transaction: TransactionRecord,
        category: ExpenseCategory,
    ): Int = withContext(Dispatchers.IO) {
        val updated = if (transaction.type == TransactionType.EXPENSE) {
            database.updateMerchantCategory(transaction.merchant, category)
        } else {
            database.updateCategory(transaction.id, category)
        }
        refresh()
        updated
    }

    suspend fun updateMerchantCategory(
        merchant: String,
        category: ExpenseCategory,
    ): Int = withContext(Dispatchers.IO) {
        val updated = database.updateMerchantCategory(merchant, category)
        refresh()
        updated
    }

    suspend fun updateNote(id: Long, note: String) = withContext(Dispatchers.IO) {
        database.updateNote(id, note)
        refresh()
    }

    suspend fun deleteTransaction(id: Long) = withContext(Dispatchers.IO) {
        database.deleteTransaction(id)
        refresh()
    }

    suspend fun setBudget(category: ExpenseCategory, limitMinor: Long) = withContext(Dispatchers.IO) {
        database.upsertBudget(category, limitMinor)
        refresh()
    }

    suspend fun clearAll() = withContext(Dispatchers.IO) {
        database.clearAll()
        refresh()
    }

    private fun refresh() {
        _transactions.value = database.getTransactions()
        _budgets.value = database.getBudgets()
        _categorizedMerchantKeys.value = database.getCategorizedMerchantKeys()
    }
}
