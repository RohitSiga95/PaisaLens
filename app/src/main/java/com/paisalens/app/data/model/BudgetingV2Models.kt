package com.paisalens.app.data.model

import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import kotlin.math.roundToInt

enum class BudgetCadence {
    MONTHLY,
    ANNUAL,
    IRREGULAR,
}

enum class BudgetPeriodAnchor {
    CALENDAR_MONTH,
    PAYDAY,
}

enum class BudgetRolloverMode {
    NONE,
    /** Only unspent money is carried into the next period. */
    POSITIVE_ONLY,
    /** Both unspent money and overspending are carried, like a true envelope. */
    FULL_BALANCE,
}

enum class BudgetHealth {
    NOT_STARTED,
    ON_TRACK,
    WARNING,
    EXCEEDED,
    ENDED,
}

/**
 * A durable Budgeting 2.0 plan. A null category and customCategoryId represents a
 * whole-spending envelope. When customCategoryId is present it takes precedence over category.
 */
data class AdvancedBudgetPlan(
    val id: Long = 0,
    val name: String,
    val category: ExpenseCategory? = null,
    val customCategoryId: Long? = null,
    val allocationMinor: Long,
    val cadence: BudgetCadence = BudgetCadence.MONTHLY,
    val periodAnchor: BudgetPeriodAnchor = BudgetPeriodAnchor.CALENDAR_MONTH,
    val paydayDay: Int = 1,
    val annualStartMonth: Int = 1,
    val irregularStartEpochDay: Long? = null,
    val irregularEndEpochDay: Long? = null,
    val rolloverMode: BudgetRolloverMode = BudgetRolloverMode.NONE,
    /** 10,000 basis points represents 100%. */
    val warningThresholdBasisPoints: Int = 8_000,
    val startingRolloverMinor: Long = 0,
    val effectiveFromEpochDay: Long,
    val enabled: Boolean = true,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
)

data class BudgetDateRange(
    val start: LocalDate,
    val endInclusive: LocalDate,
) {
    init {
        require(!endInclusive.isBefore(start)) { "Budget period end cannot be before its start" }
    }

    fun contains(date: LocalDate): Boolean = !date.isBefore(start) && !date.isAfter(endInclusive)
}

data class BudgetPeriodResult(
    val planId: Long,
    val planName: String,
    val range: BudgetDateRange,
    val allocationMinor: Long,
    val rolloverInMinor: Long,
    val availableMinor: Long,
    val actualMinor: Long,
    val remainingMinor: Long,
    val plannedToDateMinor: Long,
    /** Positive means spending is ahead of the linear plan; negative means it is below plan. */
    val actualVsPlannedMinor: Long,
    /** 10,000 basis points represents 100%; null when no positive money is available. */
    val utilizationBasisPoints: Int?,
    val health: BudgetHealth,
)

fun validateAdvancedBudgetPlan(plan: AdvancedBudgetPlan) {
    require(plan.name.trim().isNotEmpty()) { "Budget name is required" }
    require(plan.allocationMinor > 0) { "Budget allocation must be positive" }
    require(plan.paydayDay in 1..31) { "Payday must be between 1 and 31" }
    require(plan.annualStartMonth in 1..12) { "Annual start month must be between 1 and 12" }
    require(plan.warningThresholdBasisPoints in 1..10_000) {
        "Warning threshold must be between 0.01% and 100%"
    }
    require(plan.customCategoryId == null || plan.customCategoryId > 0) {
        "Custom category is invalid"
    }
    if (plan.cadence == BudgetCadence.IRREGULAR) {
        val start = requireNotNull(plan.irregularStartEpochDay) {
            "Irregular budget start date is required"
        }
        val end = requireNotNull(plan.irregularEndEpochDay) {
            "Irregular budget end date is required"
        }
        require(end >= start) { "Irregular budget end cannot be before its start" }
    }
}

/** Returns the logical period containing [date], independent of the plan's effective date. */
fun budgetDateRangeFor(plan: AdvancedBudgetPlan, date: LocalDate): BudgetDateRange? {
    validateAdvancedBudgetPlan(plan)
    return when (plan.cadence) {
        BudgetCadence.MONTHLY -> when (plan.periodAnchor) {
            BudgetPeriodAnchor.CALENDAR_MONTH -> YearMonth.from(date).let {
                BudgetDateRange(it.atDay(1), it.atEndOfMonth())
            }
            BudgetPeriodAnchor.PAYDAY -> {
                val month = YearMonth.from(date)
                val thisMonthPayday = month.atClampedDay(plan.paydayDay)
                val start = if (date.isBefore(thisMonthPayday)) {
                    month.minusMonths(1).atClampedDay(plan.paydayDay)
                } else {
                    thisMonthPayday
                }
                val next = YearMonth.from(start).plusMonths(1).atClampedDay(plan.paydayDay)
                BudgetDateRange(start, next.minusDays(1))
            }
        }
        BudgetCadence.ANNUAL -> {
            val startYear = if (date.monthValue >= plan.annualStartMonth) date.year else date.year - 1
            val start = LocalDate.of(startYear, plan.annualStartMonth, 1)
            BudgetDateRange(start, start.plusYears(1).minusDays(1))
        }
        BudgetCadence.IRREGULAR -> {
            val start = LocalDate.ofEpochDay(requireNotNull(plan.irregularStartEpochDay))
            val end = LocalDate.ofEpochDay(requireNotNull(plan.irregularEndEpochDay))
            BudgetDateRange(start, end).takeIf { it.contains(date) }
        }
    }
}

/**
 * Evaluates every period from the plan's effective date through [throughDate]. This makes
 * rollover deterministic: callers do not need to persist a mutable carry balance.
 */
fun calculateBudgetPeriods(
    plan: AdvancedBudgetPlan,
    transactions: List<TransactionRecord>,
    throughDate: LocalDate,
    zoneId: ZoneId,
): List<BudgetPeriodResult> {
    validateAdvancedBudgetPlan(plan)
    if (!plan.enabled) return emptyList()
    val effective = LocalDate.ofEpochDay(plan.effectiveFromEpochDay)
    if (throughDate.isBefore(effective)) return emptyList()

    val ranges = buildBudgetRanges(plan, effective, throughDate)
    var rollover = plan.startingRolloverMinor
    return ranges.map { range ->
        val available = safeMoneyAdd(plan.allocationMinor, rollover)
        val actual = actualBudgetSpend(
            plan = plan,
            transactions = transactions,
            start = maxOf(range.start, effective),
            endInclusive = minOf(range.endInclusive, throughDate),
            zoneId = zoneId,
        )
        val remaining = safeMoneySubtract(available, actual)
        val planned = plannedSpendToDate(available, range, effective, throughDate)
        val utilization = if (available > 0) {
            ((actual.toDouble() / available.toDouble()) * 10_000.0)
                .roundToInt()
                .coerceAtLeast(0)
        } else {
            null
        }
        val periodEnded = throughDate.isAfter(range.endInclusive)
        val health = when {
            periodEnded && plan.cadence == BudgetCadence.IRREGULAR -> BudgetHealth.ENDED
            actual > available -> BudgetHealth.EXCEEDED
            utilization != null && utilization >= plan.warningThresholdBasisPoints -> BudgetHealth.WARNING
            else -> BudgetHealth.ON_TRACK
        }
        BudgetPeriodResult(
            planId = plan.id,
            planName = plan.name,
            range = range,
            allocationMinor = plan.allocationMinor,
            rolloverInMinor = rollover,
            availableMinor = available,
            actualMinor = actual,
            remainingMinor = remaining,
            plannedToDateMinor = planned,
            actualVsPlannedMinor = safeMoneySubtract(actual, planned),
            utilizationBasisPoints = utilization,
            health = health,
        ).also {
            rollover = when (plan.rolloverMode) {
                BudgetRolloverMode.NONE -> 0L
                BudgetRolloverMode.POSITIVE_ONLY -> remaining.coerceAtLeast(0)
                BudgetRolloverMode.FULL_BALANCE -> remaining
            }
        }
    }
}

fun evaluateBudgetPlan(
    plan: AdvancedBudgetPlan,
    transactions: List<TransactionRecord>,
    throughDate: LocalDate,
    zoneId: ZoneId,
): BudgetPeriodResult? = calculateBudgetPeriods(plan, transactions, throughDate, zoneId).lastOrNull()

private fun buildBudgetRanges(
    plan: AdvancedBudgetPlan,
    effective: LocalDate,
    throughDate: LocalDate,
): List<BudgetDateRange> {
    if (plan.cadence == BudgetCadence.IRREGULAR) {
        val range = BudgetDateRange(
            LocalDate.ofEpochDay(requireNotNull(plan.irregularStartEpochDay)),
            LocalDate.ofEpochDay(requireNotNull(plan.irregularEndEpochDay)),
        )
        return if (throughDate.isBefore(range.start) || effective.isAfter(range.endInclusive)) {
            emptyList()
        } else {
            listOf(range)
        }
    }

    val ranges = mutableListOf<BudgetDateRange>()
    var range = requireNotNull(budgetDateRangeFor(plan, effective))
    repeat(MAX_GENERATED_PERIODS) {
        ranges += range
        if (!range.endInclusive.isBefore(throughDate)) return ranges
        range = requireNotNull(budgetDateRangeFor(plan, range.endInclusive.plusDays(1)))
    }
    error("Budget history exceeds $MAX_GENERATED_PERIODS periods")
}

private fun actualBudgetSpend(
    plan: AdvancedBudgetPlan,
    transactions: List<TransactionRecord>,
    start: LocalDate,
    endInclusive: LocalDate,
    zoneId: ZoneId,
): Long {
    if (endInclusive.isBefore(start)) return 0L
    var total = 0L
    transactions.asSequence()
        .filter { transaction ->
            when {
                plan.customCategoryId != null -> transaction.customCategoryId == plan.customCategoryId
                plan.category != null -> transaction.category == plan.category
                else -> true
            }
        }
        .filter { it.type == TransactionType.EXPENSE || it.type == TransactionType.REFUND }
        .filter { transaction ->
            val date = Instant.ofEpochMilli(transaction.occurredAt).atZone(zoneId).toLocalDate()
            !date.isBefore(start) && !date.isAfter(endInclusive)
        }
        .forEach { transaction ->
            total = if (transaction.type == TransactionType.REFUND) {
                safeMoneySubtract(total, transaction.amountMinor.coerceAtLeast(0))
            } else {
                safeMoneyAdd(total, transaction.amountMinor.coerceAtLeast(0))
            }
        }
    return total.coerceAtLeast(0)
}

private fun plannedSpendToDate(
    availableMinor: Long,
    range: BudgetDateRange,
    effective: LocalDate,
    throughDate: LocalDate,
): Long {
    if (availableMinor <= 0 || throughDate.isBefore(range.start)) return 0L
    val effectiveStart = maxOf(range.start, effective)
    val effectiveEnd = range.endInclusive
    if (effectiveEnd.isBefore(effectiveStart)) return 0L
    val lastIncluded = minOf(throughDate, effectiveEnd)
    val totalDays = ChronoUnit.DAYS.between(effectiveStart, effectiveEnd) + 1L
    val elapsedDays = (ChronoUnit.DAYS.between(effectiveStart, lastIncluded) + 1L).coerceIn(0L, totalDays)
    return ((availableMinor.toDouble() * elapsedDays.toDouble()) / totalDays.toDouble())
        .toLong()
        .coerceIn(0L, availableMinor)
}

private fun YearMonth.atClampedDay(day: Int): LocalDate = atDay(day.coerceAtMost(lengthOfMonth()))

private fun safeMoneyAdd(left: Long, right: Long): Long = try {
    Math.addExact(left, right)
} catch (_: ArithmeticException) {
    if (left >= 0 && right >= 0) Long.MAX_VALUE else Long.MIN_VALUE
}

private fun safeMoneySubtract(left: Long, right: Long): Long = try {
    Math.subtractExact(left, right)
} catch (_: ArithmeticException) {
    if (left >= 0 && right < 0) Long.MAX_VALUE else Long.MIN_VALUE
}

private const val MAX_GENERATED_PERIODS = 600
