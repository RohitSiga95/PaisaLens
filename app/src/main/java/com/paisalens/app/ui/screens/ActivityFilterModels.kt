package com.paisalens.app.ui.screens

import com.paisalens.app.data.model.CustomCategory
import com.paisalens.app.data.model.ExpenseCategory
import com.paisalens.app.data.model.ReviewStatus
import com.paisalens.app.data.model.TransactionRecord
import com.paisalens.app.data.model.TransactionSource
import com.paisalens.app.data.model.TransactionType
import com.paisalens.app.data.model.categoryLabel
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeParseException
import java.util.Locale

internal enum class ActivityDateRange(val label: String) {
    ANY_TIME("Any time"),
    TODAY("Today"),
    LAST_7_DAYS("Last 7 days"),
    LAST_30_DAYS("Last 30 days"),
    THIS_MONTH("This month"),
    CUSTOM("Custom dates"),
}

/** A stable, persistence-friendly snapshot of every Activity filter. */
internal data class ActivityFilterState(
    val query: String = "",
    val typeFilter: TransactionFilter = TransactionFilter.ALL,
    val selectedAccountKeys: Set<String> = emptySet(),
    val dateRange: ActivityDateRange = ActivityDateRange.ANY_TIME,
    val customStartEpochDay: Long? = null,
    val customEndEpochDay: Long? = null,
    val categoryKey: String? = null,
    val minimumAmountMinor: Long? = null,
    val maximumAmountMinor: Long? = null,
    val source: TransactionSource? = null,
    val institution: String? = null,
    val tag: String? = null,
    val duplicateOnly: Boolean = false,
    val reviewStatus: ReviewStatus? = null,
) {
    fun normalized(): ActivityFilterState {
        val lower = minimumAmountMinor?.coerceAtLeast(0L)
        val upper = maximumAmountMinor?.coerceAtLeast(0L)
        return copy(
            query = query.trim().take(80),
            selectedAccountKeys = selectedAccountKeys
                .filter(String::isNotBlank)
                .take(100)
                .toCollection(linkedSetOf()),
            customStartEpochDay = customStartEpochDay?.takeIf { dateRange == ActivityDateRange.CUSTOM },
            customEndEpochDay = customEndEpochDay?.takeIf { dateRange == ActivityDateRange.CUSTOM },
            categoryKey = categoryKey?.trim()?.takeIf(String::isNotBlank),
            minimumAmountMinor = lower,
            maximumAmountMinor = upper,
            institution = institution?.trim()?.take(80)?.takeIf(String::isNotBlank),
            tag = tag?.trim()?.take(24)?.takeIf(String::isNotBlank),
        )
    }

    fun resetAdvanced(): ActivityFilterState = copy(
        dateRange = ActivityDateRange.ANY_TIME,
        customStartEpochDay = null,
        customEndEpochDay = null,
        categoryKey = null,
        minimumAmountMinor = null,
        maximumAmountMinor = null,
        source = null,
        institution = null,
        tag = null,
        duplicateOnly = false,
        reviewStatus = null,
    )

    fun resetAll(): ActivityFilterState = ActivityFilterState()

    fun activeFilterCount(includeQuery: Boolean = false): Int = listOf(
        includeQuery && query.isNotBlank(),
        typeFilter != TransactionFilter.ALL,
        selectedAccountKeys.isNotEmpty(),
        dateRange != ActivityDateRange.ANY_TIME,
        categoryKey != null,
        minimumAmountMinor != null || maximumAmountMinor != null,
        source != null,
        institution != null,
        tag != null,
        duplicateOnly,
        reviewStatus != null,
    ).count { it }
}

internal data class ActivitySavedView(
    val id: String,
    val name: String,
    val filters: ActivityFilterState,
    val createdAt: Long,
)

internal data class ActivityNamedFilterOption(
    val key: String,
    val label: String,
)

internal fun activityCategoryOptions(
    customCategories: List<CustomCategory>,
    transactions: List<TransactionRecord>,
): List<ActivityNamedFilterOption> = buildList {
    ExpenseCategory.entries.forEach { category ->
        add(ActivityNamedFilterOption(builtInActivityCategoryKey(category), category.label))
    }
    val knownCustom = customCategories
        .filter { it.id > 0L && it.name.isNotBlank() }
        .associate { customActivityCategoryKey(it.id) to it.name.trim() }
    val observedCustom = transactions
        .filter { it.customCategoryId != null && !it.customCategoryName.isNullOrBlank() }
        .associate { customActivityCategoryKey(it.customCategoryId!!) to it.customCategoryName!!.trim() }
    (knownCustom + observedCustom)
        .entries
        .sortedBy { it.value.lowercase(Locale.ROOT) }
        .forEach { (key, label) -> add(ActivityNamedFilterOption(key, label)) }
}.distinctBy(ActivityNamedFilterOption::key)

internal fun activityInstitutionOptions(transactions: List<TransactionRecord>): List<String> = transactions
    .mapNotNull { transaction ->
        transaction.institutionName?.trim()?.takeIf(String::isNotBlank)
            ?: transaction.accountName?.trim()?.takeIf(String::isNotBlank)
    }
    .distinctBy(::normalizedActivityFilterValue)
    .sortedWith(String.CASE_INSENSITIVE_ORDER)

internal fun activityTagOptions(transactions: List<TransactionRecord>): List<String> = transactions
    .flatMap(TransactionRecord::tags)
    .map(String::trim)
    .filter(String::isNotBlank)
    .distinctBy { it.lowercase(Locale.ROOT) }
    .sortedWith(String.CASE_INSENSITIVE_ORDER)

internal fun transactionMatchesActivityFilters(
    transaction: TransactionRecord,
    filters: ActivityFilterState,
    nowMillis: Long = System.currentTimeMillis(),
    zoneId: ZoneId = ZoneId.systemDefault(),
): Boolean {
    val normalized = filters.normalized()
    val queryMatch = normalized.query.isBlank() ||
        transaction.merchant.contains(normalized.query, ignoreCase = true) ||
        transaction.categoryLabel().contains(normalized.query, ignoreCase = true) ||
        transaction.sender.contains(normalized.query, ignoreCase = true) ||
        transaction.note?.contains(normalized.query, ignoreCase = true) == true ||
        transaction.accountName?.contains(normalized.query, ignoreCase = true) == true ||
        transaction.institutionName?.contains(normalized.query, ignoreCase = true) == true ||
        transaction.tags.any { it.contains(normalized.query, ignoreCase = true) }
    val typeMatch = when (normalized.typeFilter) {
        TransactionFilter.ALL -> true
        TransactionFilter.EXPENSE -> transaction.type == TransactionType.EXPENSE
        TransactionFilter.INCOME -> transaction.type == TransactionType.INCOME
        TransactionFilter.REFUND -> transaction.type == TransactionType.REFUND
        TransactionFilter.TRANSFER -> transaction.type == TransactionType.TRANSFER
        TransactionFilter.REVIEW -> transaction.reviewStatus == ReviewStatus.NEEDS_REVIEW
        TransactionFilter.UNCATEGORIZED -> transaction.type == TransactionType.EXPENSE &&
            transaction.category == ExpenseCategory.OTHER &&
            transaction.customCategoryId == null
        TransactionFilter.UNASSIGNED -> transaction.accountId == null
    }
    val dateMatch = transactionMatchesActivityDate(transaction.occurredAt, normalized, nowMillis, zoneId)
    val categoryMatch = normalized.categoryKey == null ||
        transaction.activityCategoryKey() == normalized.categoryKey
    val amountMatch = (normalized.minimumAmountMinor == null ||
        transaction.amountMinor >= normalized.minimumAmountMinor) &&
        (normalized.maximumAmountMinor == null ||
            transaction.amountMinor <= normalized.maximumAmountMinor)
    val sourceMatch = normalized.source == null || transaction.source == normalized.source
    val institutionMatch = normalized.institution == null || listOfNotNull(
        transaction.institutionName,
        transaction.accountName,
        transaction.sender,
    ).any { normalizedActivityFilterValue(it) == normalizedActivityFilterValue(normalized.institution) }
    val tagMatch = normalized.tag == null || transaction.tags.any {
        it.equals(normalized.tag, ignoreCase = true)
    }
    val duplicateMatch = !normalized.duplicateOnly || transaction.duplicateCount > 1
    val reviewMatch = normalized.reviewStatus == null || transaction.reviewStatus == normalized.reviewStatus
    return queryMatch && typeMatch && dateMatch && categoryMatch && amountMatch && sourceMatch &&
        institutionMatch && tagMatch && duplicateMatch && reviewMatch
}

internal fun parseActivityDate(value: String): Long? = try {
    LocalDate.parse(value.trim()).toEpochDay()
} catch (_: DateTimeParseException) {
    null
}

internal fun formatActivityDate(epochDay: Long?): String = epochDay
    ?.let(LocalDate::ofEpochDay)
    ?.toString()
    .orEmpty()

internal fun parseActivityAmountMinor(value: String): Long? {
    val clean = value.trim().replace(",", "")
    if (clean.isBlank()) return null
    val rupees = clean.toBigDecimalOrNull() ?: return null
    if (rupees.signum() < 0) return null
    return runCatching {
        rupees.multiply(BigDecimal(100)).setScale(0, RoundingMode.HALF_UP).longValueExact()
    }.getOrNull()
}

internal fun formatActivityAmountMinor(amountMinor: Long?): String = amountMinor
    ?.let { BigDecimal(it).divide(BigDecimal(100)).stripTrailingZeros().toPlainString() }
    .orEmpty()

internal fun activityEpochDay(
    nowMillis: Long,
    zoneId: ZoneId,
): Long = Instant.ofEpochMilli(nowMillis).atZone(zoneId).toLocalDate().toEpochDay()

internal data class ActivityDateClock(
    val epochDay: Long,
    val zoneId: ZoneId,
)

internal fun activityDateClock(
    nowMillis: Long = System.currentTimeMillis(),
    zoneId: ZoneId = ZoneId.systemDefault(),
): ActivityDateClock = ActivityDateClock(
    epochDay = activityEpochDay(nowMillis, zoneId),
    zoneId = zoneId,
)

internal fun activityStartOfDayMillis(
    epochDay: Long,
    zoneId: ZoneId,
): Long = LocalDate.ofEpochDay(epochDay).atStartOfDay(zoneId).toInstant().toEpochMilli()

internal fun millisUntilNextActivityDay(
    nowMillis: Long,
    zoneId: ZoneId,
): Long {
    val now = Instant.ofEpochMilli(nowMillis).atZone(zoneId)
    val nextDayStart = now.toLocalDate().plusDays(1).atStartOfDay(zoneId).toInstant().toEpochMilli()
    return (nextDayStart - nowMillis).coerceAtLeast(1L)
}

private fun transactionMatchesActivityDate(
    occurredAt: Long,
    filters: ActivityFilterState,
    nowMillis: Long,
    zoneId: ZoneId,
): Boolean {
    if (filters.dateRange == ActivityDateRange.ANY_TIME) return true
    val today = Instant.ofEpochMilli(nowMillis).atZone(zoneId).toLocalDate()
    val transactionDate = Instant.ofEpochMilli(occurredAt).atZone(zoneId).toLocalDate()
    val start = when (filters.dateRange) {
        ActivityDateRange.ANY_TIME -> null
        ActivityDateRange.TODAY -> today
        ActivityDateRange.LAST_7_DAYS -> today.minusDays(6)
        ActivityDateRange.LAST_30_DAYS -> today.minusDays(29)
        ActivityDateRange.THIS_MONTH -> today.withDayOfMonth(1)
        ActivityDateRange.CUSTOM -> filters.customStartEpochDay?.let(LocalDate::ofEpochDay)
    }
    val end = when (filters.dateRange) {
        ActivityDateRange.CUSTOM -> filters.customEndEpochDay?.let(LocalDate::ofEpochDay)
        else -> today
    }
    return (start == null || !transactionDate.isBefore(start)) &&
        (end == null || !transactionDate.isAfter(end))
}

private fun TransactionRecord.activityCategoryKey(): String = customCategoryId
    ?.let(::customActivityCategoryKey)
    ?: builtInActivityCategoryKey(category)

private fun builtInActivityCategoryKey(category: ExpenseCategory): String = "built-in:${category.name}"

private fun customActivityCategoryKey(id: Long): String = "custom:$id"

private fun normalizedActivityFilterValue(value: String): String = value
    .trim()
    .lowercase(Locale.ROOT)
    .replace(Regex("[^a-z0-9]+"), "")
