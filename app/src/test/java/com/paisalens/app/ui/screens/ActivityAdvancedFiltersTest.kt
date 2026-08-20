package com.paisalens.app.ui.screens

import com.paisalens.app.data.model.CustomCategory
import com.paisalens.app.data.model.ExpenseCategory
import com.paisalens.app.data.model.ReviewStatus
import com.paisalens.app.data.model.TransactionRecord
import com.paisalens.app.data.model.TransactionSource
import com.paisalens.app.data.model.TransactionType
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ActivityAdvancedFiltersTest {
    @Test
    fun advancedDimensionsCombineWithAndSemantics() {
        val occurredAt = LocalDate.of(2026, 8, 18).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        val row = transaction(
            occurredAt = occurredAt,
            amountMinor = 12_500,
            category = ExpenseCategory.OTHER,
            customCategoryId = 41,
            customCategoryName = "Coffee",
            source = TransactionSource.CARD,
            institution = "HDFC Bank",
            tags = listOf("work"),
            duplicateCount = 2,
            reviewStatus = ReviewStatus.NEEDS_REVIEW,
        )
        val matching = ActivityFilterState(
            query = "latte",
            typeFilter = TransactionFilter.EXPENSE,
            dateRange = ActivityDateRange.CUSTOM,
            customStartEpochDay = LocalDate.of(2026, 8, 17).toEpochDay(),
            customEndEpochDay = LocalDate.of(2026, 8, 19).toEpochDay(),
            categoryKey = "custom:41",
            minimumAmountMinor = 10_000,
            maximumAmountMinor = 13_000,
            source = TransactionSource.CARD,
            institution = "HDFC Bank",
            tag = "WORK",
            duplicateOnly = true,
            reviewStatus = ReviewStatus.NEEDS_REVIEW,
        )

        assertTrue(transactionMatchesActivityFilters(row, matching, occurredAt, ZoneOffset.UTC))
        assertFalse(transactionMatchesActivityFilters(row, matching.copy(tag = "personal"), occurredAt, ZoneOffset.UTC))
        assertFalse(transactionMatchesActivityFilters(row, matching.copy(source = TransactionSource.BANK), occurredAt, ZoneOffset.UTC))
        assertFalse(transactionMatchesActivityFilters(row, matching.copy(maximumAmountMinor = 12_499), occurredAt, ZoneOffset.UTC))
        assertFalse(
            transactionMatchesActivityFilters(
                row.copy(duplicateCount = 1),
                matching,
                occurredAt,
                ZoneOffset.UTC,
            ),
        )
    }

    @Test
    fun relativeDateRangesAreInclusiveAndUseProvidedZone() {
        val now = LocalDate.of(2026, 8, 20).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        val nextDay = LocalDate.of(2026, 8, 21).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        val firstIncluded = transaction(
            occurredAt = LocalDate.of(2026, 8, 14).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
        )
        val excluded = transaction(
            occurredAt = LocalDate.of(2026, 8, 13).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
        )
        val filters = ActivityFilterState(dateRange = ActivityDateRange.LAST_7_DAYS)

        assertTrue(transactionMatchesActivityFilters(firstIncluded, filters, now, ZoneOffset.UTC))
        assertFalse(transactionMatchesActivityFilters(excluded, filters, now, ZoneOffset.UTC))
        assertEquals(
            listOf(firstIncluded),
            filterActivityTransactions(
                transactions = listOf(firstIncluded),
                filters = filters,
                accountOptions = emptyList(),
                nowMillis = now,
                zoneId = ZoneOffset.UTC,
            ),
        )
        assertTrue(
            filterActivityTransactions(
                transactions = listOf(firstIncluded),
                filters = filters,
                accountOptions = emptyList(),
                nowMillis = nextDay,
                zoneId = ZoneOffset.UTC,
            ).isEmpty(),
        )

        val todayOnly = ActivityFilterState(dateRange = ActivityDateRange.TODAY)
        val todayRow = transaction(occurredAt = now)
        assertTrue(transactionMatchesActivityFilters(todayRow, todayOnly, now, ZoneOffset.UTC))
        assertFalse(transactionMatchesActivityFilters(todayRow, todayOnly, nextDay, ZoneOffset.UTC))
    }

    @Test
    fun nextDayDelayAndStartOfDayRespectLocalDstBoundary() {
        val zone = ZoneId.of("America/Los_Angeles")
        val springForwardDay = LocalDate.of(2026, 3, 8)
        val dayStart = springForwardDay.atStartOfDay(zone).toInstant().toEpochMilli()

        assertEquals(23L * 60L * 60L * 1_000L, millisUntilNextActivityDay(dayStart, zone))
        val nextEpochDay = springForwardDay.plusDays(1).toEpochDay()
        assertEquals(
            nextEpochDay,
            activityEpochDay(activityStartOfDayMillis(nextEpochDay, zone), zone),
        )
    }

    @Test
    fun activityClockRefreshesLocalDayAndZoneAfterTimezoneChange() {
        val instant = LocalDate.of(2026, 8, 20)
            .atTime(20, 0)
            .atZone(ZoneId.of("America/Los_Angeles"))
            .toInstant()
            .toEpochMilli()

        val losAngeles = activityDateClock(instant, ZoneId.of("America/Los_Angeles"))
        val kolkata = activityDateClock(instant, ZoneId.of("Asia/Kolkata"))

        assertEquals(ZoneId.of("America/Los_Angeles"), losAngeles.zoneId)
        assertEquals(ZoneId.of("Asia/Kolkata"), kolkata.zoneId)
        assertEquals(losAngeles.epochDay + 1, kolkata.epochDay)
    }

    @Test
    fun amountAndDateInputsParseWithoutFloatingPointLoss() {
        assertEquals(12_345L, parseActivityAmountMinor("123.45"))
        assertEquals(123_450L, parseActivityAmountMinor("1,234.5"))
        assertNull(parseActivityAmountMinor("-1"))
        assertNull(parseActivityAmountMinor("abc"))
        assertEquals(LocalDate.of(2026, 8, 20).toEpochDay(), parseActivityDate("2026-08-20"))
        assertNull(parseActivityDate("20/08/2026"))
    }

    @Test
    fun customCategoriesIncludeConfiguredAndObservedEntries() {
        val options = activityCategoryOptions(
            customCategories = listOf(CustomCategory(id = 41, name = "Coffee")),
            transactions = listOf(
                transaction(customCategoryId = 72, customCategoryName = "Pet care"),
            ),
        )

        assertTrue(options.any { it.key == "custom:41" && it.label == "Coffee" })
        assertTrue(options.any { it.key == "custom:72" && it.label == "Pet care" })
        assertTrue(options.any { it.key == "built-in:${ExpenseCategory.FOOD.name}" })
    }

    @Test
    fun savedViewCodecRoundTripsEveryFilterAndRejectsCorruption() {
        val view = ActivitySavedView(
            id = "view-1",
            name = "Work card",
            createdAt = 1234,
            filters = ActivityFilterState(
                query = "cafe",
                typeFilter = TransactionFilter.EXPENSE,
                selectedAccountKeys = linkedSetOf("card:1", "bank:2"),
                dateRange = ActivityDateRange.THIS_MONTH,
                categoryKey = "custom:41",
                minimumAmountMinor = 500,
                maximumAmountMinor = 50_000,
                source = TransactionSource.CARD,
                institution = "HDFC Bank",
                tag = "work",
                duplicateOnly = true,
                reviewStatus = ReviewStatus.CONFIRMED,
            ),
        )

        assertEquals(listOf(view), ActivitySavedViewCodec.decode(ActivitySavedViewCodec.encode(listOf(view))))
        assertTrue(ActivitySavedViewCodec.decode("not-valid-base64").isEmpty())
    }

    private fun transaction(
        occurredAt: Long = 1,
        amountMinor: Long = 100,
        category: ExpenseCategory = ExpenseCategory.FOOD,
        customCategoryId: Long? = null,
        customCategoryName: String? = null,
        source: TransactionSource = TransactionSource.BANK,
        institution: String? = null,
        tags: List<String> = emptyList(),
        duplicateCount: Int = 1,
        reviewStatus: ReviewStatus = ReviewStatus.CONFIRMED,
    ) = TransactionRecord(
        id = occurredAt,
        sourceMessageId = "test-$occurredAt",
        amountMinor = amountMinor,
        merchant = "Morning Latte",
        accountHint = "1234",
        category = category,
        type = TransactionType.EXPENSE,
        occurredAt = occurredAt,
        source = source,
        sender = "HDFCBK",
        accountName = "Work card",
        customCategoryId = customCategoryId,
        customCategoryName = customCategoryName,
        tags = tags,
        reviewStatus = reviewStatus,
        institutionName = institution,
        duplicateCount = duplicateCount,
    )
}
