package com.paisalens.app.data.model

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
