package com.paisalens.app.data.model

import java.util.Locale

/** A stable Home-dashboard identity for either a built-in or user-created category. */
data class SpendingCategoryKey(
    val builtIn: ExpenseCategory,
    val customCategoryId: Long? = null,
    val customCategoryName: String? = null,
) {
    val label: String
        get() = customCategoryName?.takeIf { it.isNotBlank() } ?: builtIn.label

    val stableId: String
        get() = when {
            customCategoryId != null -> "custom:$customCategoryId"
            !customCategoryName.isNullOrBlank() ->
                "custom-name:${customCategoryName.trim().lowercase(Locale.ROOT)}"
            else -> "built-in:${builtIn.name}"
        }

    fun matches(transaction: TransactionRecord): Boolean = when {
        customCategoryId != null -> transaction.customCategoryId == customCategoryId
        !customCategoryName.isNullOrBlank() ->
            transaction.customCategoryId == null &&
                transaction.customCategoryName?.trim()?.equals(customCategoryName.trim(), ignoreCase = true) == true
        else -> transaction.customCategoryId == null &&
            transaction.customCategoryName.isNullOrBlank() &&
            transaction.category == builtIn
    }
}

data class SpendingCategoryTotal(
    val key: SpendingCategoryKey,
    val amountMinor: Long,
)

fun buildSpendingCategoryTotals(
    transactions: List<TransactionRecord>,
    customCategories: List<CustomCategory> = emptyList(),
): List<SpendingCategoryTotal> {
    val transactionTotals = transactions.groupBy { transaction ->
        SpendingCategoryKey(
            builtIn = transaction.category,
            customCategoryId = transaction.customCategoryId,
            customCategoryName = transaction.customCategoryName?.takeIf(String::isNotBlank),
        ).let { key ->
            // A persisted custom id is the durable identity even if older rows carry a stale label.
            if (key.customCategoryId != null) key.copy(customCategoryName = null) else key
        }
    }
    .map { (groupedKey, records) ->
        val currentLabel = records.firstNotNullOfOrNull {
            it.customCategoryName?.takeIf(String::isNotBlank)
        }
        SpendingCategoryTotal(
            key = groupedKey.copy(customCategoryName = currentLabel ?: groupedKey.customCategoryName),
            amountMinor = records.sumOf(TransactionRecord::amountMinor),
        )
    }
    val knownCustomIds = transactionTotals.mapNotNullTo(mutableSetOf()) { it.key.customCategoryId }
    val emptyCustomCategories = customCategories
        .filter { it.id !in knownCustomIds }
        .map { category ->
            SpendingCategoryTotal(
                key = SpendingCategoryKey(
                    builtIn = ExpenseCategory.OTHER,
                    customCategoryId = category.id,
                    customCategoryName = category.name,
                ),
                amountMinor = 0L,
            )
        }
    return (transactionTotals + emptyCustomCategories).sortedWith(
        compareByDescending<SpendingCategoryTotal> { it.amountMinor }
            .thenBy { it.key.label.lowercase(Locale.ROOT) },
    )
}
