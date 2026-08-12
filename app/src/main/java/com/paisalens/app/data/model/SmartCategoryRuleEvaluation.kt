package com.paisalens.app.data.model

/**
 * Builds the preview for the state the rule will have after it is saved.
 * Exact merchant mappings are intentionally evaluated before smart rules.
 */
fun previewSmartCategoryRuleApplication(
    rule: SmartCategoryRule,
    transactions: List<TransactionRecord>,
    savedRules: List<SmartCategoryRule>,
    exactMerchantKeys: Set<String>,
): SmartRulePreview {
    // Saving a rule refreshes updatedAt, so it wins ties at the same priority.
    val pendingRule = rule.copy(
        merchantPattern = rule.merchantPattern.trim().replace(Regex("\\s+"), " ").take(64),
        minAmountMinor = rule.minAmountMinor?.coerceAtLeast(0),
        maxAmountMinor = rule.maxAmountMinor?.coerceAtLeast(0),
        priority = rule.priority.coerceIn(-10_000, 10_000),
        updatedAt = Long.MAX_VALUE,
    )
    val rulesAfterSave = savedRules
        .filterNot { rule.id != 0L && it.id == rule.id }
        .plus(pendingRule)
    val matches = transactions.filter { transaction ->
        normalizedMerchantKey(transaction.merchant) !in exactMerchantKeys &&
            findMatchingSmartCategoryRule(transaction, rulesAfterSave) === pendingRule
    }
    return SmartRulePreview(
        rule = rule,
        matchedTransactionIds = matches.map { it.id },
        matchedCount = matches.size,
        totalAmountMinor = matches.sumOf { it.amountMinor },
    )
}
