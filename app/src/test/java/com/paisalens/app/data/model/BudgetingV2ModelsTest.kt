package com.paisalens.app.data.model

import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class BudgetingV2ModelsTest {
    private val utc: ZoneId = ZoneOffset.UTC

    @Test
    fun calendarAndPaydayPeriodsUseRealCalendarBoundaries() {
        val calendar = plan(effective = LocalDate.of(2026, 1, 1))
        assertEquals(
            BudgetDateRange(LocalDate.of(2026, 2, 1), LocalDate.of(2026, 2, 28)),
            budgetDateRangeFor(calendar, LocalDate.of(2026, 2, 14)),
        )

        val payday = calendar.copy(
            periodAnchor = BudgetPeriodAnchor.PAYDAY,
            paydayDay = 31,
        )
        assertEquals(
            BudgetDateRange(LocalDate.of(2026, 1, 31), LocalDate.of(2026, 2, 27)),
            budgetDateRangeFor(payday, LocalDate.of(2026, 2, 15)),
        )
        assertEquals(
            BudgetDateRange(LocalDate.of(2026, 2, 28), LocalDate.of(2026, 3, 30)),
            budgetDateRangeFor(payday, LocalDate.of(2026, 2, 28)),
        )
    }

    @Test
    fun positiveRolloverCarriesOnlyUnspentMoney() {
        val plan = plan(
            allocationMinor = 10_000,
            effective = LocalDate.of(2026, 1, 1),
            rolloverMode = BudgetRolloverMode.POSITIVE_ONLY,
        )
        val results = calculateBudgetPeriods(
            plan,
            transactions = listOf(
                transaction(1, 4_000, LocalDate.of(2026, 1, 10)),
                transaction(2, 3_000, LocalDate.of(2026, 2, 5)),
            ),
            throughDate = LocalDate.of(2026, 2, 10),
            zoneId = utc,
        )

        assertEquals(2, results.size)
        assertEquals(6_000L, results[0].remainingMinor)
        assertEquals(6_000L, results[1].rolloverInMinor)
        assertEquals(16_000L, results[1].availableMinor)
        assertEquals(13_000L, results[1].remainingMinor)
    }

    @Test
    fun fullEnvelopeCarriesOverspendingWhilePositiveModeDoesNot() {
        val base = plan(
            allocationMinor = 10_000,
            effective = LocalDate.of(2026, 1, 1),
            rolloverMode = BudgetRolloverMode.FULL_BALANCE,
        )
        val transactions = listOf(transaction(1, 12_000, LocalDate.of(2026, 1, 10)))
        val full = calculateBudgetPeriods(base, transactions, LocalDate.of(2026, 2, 2), utc)
        val positiveOnly = calculateBudgetPeriods(
            base.copy(rolloverMode = BudgetRolloverMode.POSITIVE_ONLY),
            transactions,
            LocalDate.of(2026, 2, 2),
            utc,
        )

        assertEquals(BudgetHealth.EXCEEDED, full.first().health)
        assertEquals(-2_000L, full.last().rolloverInMinor)
        assertEquals(8_000L, full.last().availableMinor)
        assertEquals(0L, positiveOnly.last().rolloverInMinor)
        assertEquals(10_000L, positiveOnly.last().availableMinor)
    }

    @Test
    fun annualAndIrregularBudgetsSupportLongAndOneOffPlans() {
        val annual = plan(effective = LocalDate.of(2025, 4, 1)).copy(
            cadence = BudgetCadence.ANNUAL,
            annualStartMonth = 4,
        )
        assertEquals(
            BudgetDateRange(LocalDate.of(2025, 4, 1), LocalDate.of(2026, 3, 31)),
            budgetDateRangeFor(annual, LocalDate.of(2026, 2, 1)),
        )

        val start = LocalDate.of(2026, 6, 10)
        val end = LocalDate.of(2026, 8, 20)
        val irregular = plan(effective = start).copy(
            cadence = BudgetCadence.IRREGULAR,
            irregularStartEpochDay = start.toEpochDay(),
            irregularEndEpochDay = end.toEpochDay(),
        )
        assertEquals(BudgetDateRange(start, end), budgetDateRangeFor(irregular, LocalDate.of(2026, 7, 1)))
        assertNull(budgetDateRangeFor(irregular, LocalDate.of(2026, 9, 1)))
        val result = evaluateBudgetPlan(
            irregular,
            listOf(transaction(99, 150_000, LocalDate.of(2026, 7, 1))),
            LocalDate.of(2026, 9, 1),
            utc,
        )
        assertEquals(BudgetHealth.ENDED, result?.health)
        assertEquals(150_000L, result?.actualMinor)
    }

    @Test
    fun customCategoryRefundsAndWarningsFeedPlannedVersusActual() {
        val plan = plan(
            allocationMinor = 10_000,
            effective = LocalDate.of(2026, 8, 1),
        ).copy(
            category = ExpenseCategory.OTHER,
            customCategoryId = 77,
            warningThresholdBasisPoints = 5_000,
        )
        val result = evaluateBudgetPlan(
            plan,
            transactions = listOf(
                transaction(1, 7_000, LocalDate.of(2026, 8, 4), customCategoryId = 77),
                transaction(2, 1_000, LocalDate.of(2026, 8, 5), TransactionType.REFUND, 77),
                transaction(3, 9_000, LocalDate.of(2026, 8, 5), customCategoryId = 88),
            ),
            throughDate = LocalDate.of(2026, 8, 10),
            zoneId = utc,
        )

        requireNotNull(result)
        assertEquals(6_000L, result.actualMinor)
        assertEquals(6_000, result.utilizationBasisPoints)
        assertEquals(BudgetHealth.WARNING, result.health)
        assertEquals(3_225L, result.plannedToDateMinor)
        assertEquals(2_775L, result.actualVsPlannedMinor)
    }

    @Test
    fun disabledFutureAndInvalidPlansAreSafe() {
        val future = plan(effective = LocalDate.of(2027, 1, 1))
        assertEquals(emptyList<BudgetPeriodResult>(), calculateBudgetPeriods(future, emptyList(), LocalDate.of(2026, 1, 1), utc))
        assertEquals(emptyList<BudgetPeriodResult>(), calculateBudgetPeriods(future.copy(enabled = false), emptyList(), LocalDate.of(2028, 1, 1), utc))
        assertThrows(IllegalArgumentException::class.java) {
            validateAdvancedBudgetPlan(future.copy(allocationMinor = 0))
        }
        assertThrows(IllegalArgumentException::class.java) {
            validateAdvancedBudgetPlan(
                future.copy(
                    cadence = BudgetCadence.IRREGULAR,
                    irregularStartEpochDay = 10,
                    irregularEndEpochDay = 9,
                ),
            )
        }
    }

    private fun plan(
        allocationMinor: Long = 100_000,
        effective: LocalDate,
        rolloverMode: BudgetRolloverMode = BudgetRolloverMode.NONE,
    ) = AdvancedBudgetPlan(
        id = 5,
        name = "Food envelope",
        category = ExpenseCategory.FOOD,
        allocationMinor = allocationMinor,
        rolloverMode = rolloverMode,
        effectiveFromEpochDay = effective.toEpochDay(),
        createdAt = 1,
        updatedAt = 1,
    )

    private fun transaction(
        id: Long,
        amountMinor: Long,
        date: LocalDate,
        type: TransactionType = TransactionType.EXPENSE,
        customCategoryId: Long? = null,
    ) = TransactionRecord(
        id = id,
        sourceMessageId = "sms-$id",
        amountMinor = amountMinor,
        merchant = "Merchant $id",
        accountHint = null,
        category = if (customCategoryId == null) ExpenseCategory.FOOD else ExpenseCategory.OTHER,
        type = type,
        occurredAt = date.atStartOfDay(utc).toInstant().toEpochMilli(),
        source = TransactionSource.BANK,
        sender = "BANK",
        customCategoryId = customCategoryId,
    )
}
