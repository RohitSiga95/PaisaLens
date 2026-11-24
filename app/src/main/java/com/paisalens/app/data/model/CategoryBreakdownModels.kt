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
    val customById = customCategories.associateBy(CustomCategory::id)
    val transactionTotals = transactions.groupBy { transaction ->
        transaction.spendingCategoryKey().stableId
    }.values.map { records ->
        val key = records.first().spendingCategoryKey()
        val label = key.customCategoryId?.let { customById[it]?.name }
            ?: records.firstNotNullOfOrNull { it.customCategoryName?.trim()?.takeIf(String::isNotBlank) }
        SpendingCategoryTotal(
            key = key.copy(customCategoryName = label),
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

private fun TransactionRecord.spendingCategoryKey(): SpendingCategoryKey =
    SpendingCategoryKey(
        builtIn = category,
        customCategoryId = customCategoryId,
        customCategoryName = customCategoryName?.trim()?.takeIf(String::isNotBlank),
    )
