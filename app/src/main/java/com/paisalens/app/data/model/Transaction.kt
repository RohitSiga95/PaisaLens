package com.paisalens.app.data.model

import java.util.Locale

enum class TransactionType {
    EXPENSE,
    INCOME,
    REFUND,
    TRANSFER,
}

enum class TransactionSource {
    BANK,
    CARD,
    UPI,
    WALLET,
    MANUAL,
}

enum class ExpenseCategory(val label: String) {
    FOOD("Food & dining"),
    GROCERIES("Groceries"),
    SHOPPING("Shopping"),
    TRANSPORT("Transport"),
    BILLS("Bills & utilities"),
    ENTERTAINMENT("Entertainment"),
    HEALTH("Health"),
    EDUCATION("Education"),
    TRAVEL("Travel"),
    CASH("Cash withdrawal"),
    TRANSFER("Transfers"),
    INCOME("Income"),
    OTHER("Other"),
}

data class TransactionRecord(
    val id: Long = 0,
    val sourceMessageId: String,
    val amountMinor: Long,
    val merchant: String,
    val accountHint: String?,
    val category: ExpenseCategory,
    val type: TransactionType,
    val occurredAt: Long,
    val source: TransactionSource,
    val sender: String,
    val note: String? = null,
)

data class ParsedTransaction(
    val sourceMessageId: String,
    val amountMinor: Long,
    val merchant: String,
    val accountHint: String?,
    val category: ExpenseCategory,
    val type: TransactionType,
    val occurredAt: Long,
    val source: TransactionSource,
    val sender: String,
    val rawMessage: String,
)

data class CategoryBudget(
    val category: ExpenseCategory,
    val limitMinor: Long,
)

data class MerchantTransactionGroup(
    val merchant: String,
    val merchantKey: String,
    val transactionCount: Int,
    val totalMinor: Long,
)

fun normalizedMerchantKey(merchant: String): String = merchant
    .trim()
    .lowercase(Locale.ROOT)
    .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
    .trim()

fun findUncategorizedMerchantGroups(
    transactions: List<TransactionRecord>,
    categorizedMerchantKeys: Set<String>,
): List<MerchantTransactionGroup> = transactions
    .asSequence()
    .filter { it.type == TransactionType.EXPENSE && it.category == ExpenseCategory.OTHER }
    .groupBy { normalizedMerchantKey(it.merchant) }
    .filterKeys { it.isNotBlank() && it !in categorizedMerchantKeys }
    .map { (merchantKey, matchingTransactions) ->
        MerchantTransactionGroup(
            merchant = matchingTransactions.first().merchant,
            merchantKey = merchantKey,
            transactionCount = matchingTransactions.size,
            totalMinor = matchingTransactions.sumOf { it.amountMinor },
        )
    }
    .sortedWith(
        compareByDescending<MerchantTransactionGroup> { it.transactionCount }
            .thenByDescending { it.totalMinor },
    )
