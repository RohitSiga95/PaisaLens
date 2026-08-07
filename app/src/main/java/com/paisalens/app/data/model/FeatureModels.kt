package com.paisalens.app.data.model

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.util.Locale
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.roundToLong

data class MerchantAliasRule(
    val aliasKey: String,
    val aliasName: String,
    val canonicalName: String,
    val updatedAt: Long = System.currentTimeMillis(),
)

data class MerchantCleanupGroup(
    val merchant: String,
    val merchantKey: String,
    val transactionCount: Int,
    val totalMinor: Long,
    val variants: List<String>,
)

data class LoanAccount(
    val id: Long = 0,
    val name: String,
    val lender: String,
    val principalMinor: Long,
    val annualRateBasisPoints: Int,
    val tenureMonths: Int,
    val startDateEpochDay: Long,
    val emiMinor: Long,
    val paidInstallments: Int = 0,
    val accountId: Long? = null,
    val notes: String? = null,
) {
    val remainingInstallments: Int get() = (tenureMonths - paidInstallments).coerceAtLeast(0)
    val nextDueDate: LocalDate
        get() {
            val anchor = LocalDate.ofEpochDay(startDateEpochDay)
            val targetMonth = YearMonth.from(anchor).plusMonths(paidInstallments.toLong())
            return if (anchor.dayOfMonth == YearMonth.from(anchor).lengthOfMonth()) {
                targetMonth.atEndOfMonth()
            } else {
                targetMonth.atDay(anchor.dayOfMonth.coerceAtMost(targetMonth.lengthOfMonth()))
            }
        }
    val estimatedRemainingMinor: Long get() = emiMinor * remainingInstallments
    val progress: Float get() = if (tenureMonths <= 0) 0f else paidInstallments.toFloat() / tenureMonths
}

data class ExchangeRate(
    val baseCurrency: String,
    val quoteCurrency: String,
    val rate: Double,
    val rateDate: String,
    val fetchedAt: Long,
    val provider: String = "Frankfurter",
)

data class MonthlySpendPoint(
    val month: YearMonth,
    val amountMinor: Long,
)

data class NamedSpend(
    val label: String,
    val amountMinor: Long,
    val transactionCount: Int,
)

data class DailySpend(
    val date: LocalDate,
    val amountMinor: Long,
    val transactions: List<TransactionRecord>,
)

data class SpendingAnalytics(
    val currentMonthMinor: Long,
    val previousMonthMinor: Long,
    val monthOverMonthPercent: Double?,
    val projectedMonthMinor: Long,
    val averageDailyMinor: Long,
    val largestExpense: TransactionRecord?,
    val monthlyTrend: List<MonthlySpendPoint>,
    val topMerchants: List<NamedSpend>,
    val categoryBreakdown: List<NamedSpend>,
    val weekdayBreakdown: List<NamedSpend>,
)

enum class InsightKind {
    ANOMALY,
    DUPLICATE,
    SUBSCRIPTION_INCREASE,
    SPENDING_PACE,
    FIXED_COMMITMENTS,
    CONCENTRATION,
}

data class SpendingInsight(
    val kind: InsightKind,
    val title: String,
    val detail: String,
    val amountMinor: Long? = null,
    val transactionId: Long? = null,
    val priority: Int = 0,
)

data class StatementImportRow(
    val rowNumber: Int,
    val transaction: TransactionRecord,
    val warning: String? = null,
)

data class StatementImportPreview(
    val rows: List<StatementImportRow>,
    val skippedRows: Int,
    val warnings: List<String>,
)

data class StatementImportResult(
    val imported: Int,
    val duplicates: Int,
)

fun calculateEmiMinor(
    principalMinor: Long,
    annualRateBasisPoints: Int,
    tenureMonths: Int,
): Long {
    if (principalMinor <= 0 || tenureMonths <= 0) return 0
    val monthlyRate = annualRateBasisPoints / 10_000.0 / 12.0
    if (monthlyRate == 0.0) return (principalMinor.toDouble() / tenureMonths).roundToLong()
    val factor = (1.0 + monthlyRate).pow(tenureMonths)
    return (principalMinor * monthlyRate * factor / (factor - 1.0)).roundToLong()
}

fun buildMerchantCleanupGroups(transactions: List<TransactionRecord>): List<MerchantCleanupGroup> = transactions
    .filter { it.merchant.isNotBlank() }
    .groupBy { normalizedMerchantKey(it.merchant) }
    .map { (key, matches) ->
        MerchantCleanupGroup(
            merchant = matches.groupingBy { it.merchant.trim() }.eachCount().maxBy { it.value }.key,
            merchantKey = key,
            transactionCount = matches.size,
            totalMinor = matches.filter { it.type == TransactionType.EXPENSE }.sumOf { it.amountMinor },
            variants = matches.map { it.merchant.trim() }.distinct().sorted(),
        )
    }
    .sortedWith(compareByDescending<MerchantCleanupGroup> { it.transactionCount }.thenBy { it.merchant.lowercase() })

fun buildSpendingAnalytics(
    transactions: List<TransactionRecord>,
    today: LocalDate = LocalDate.now(),
    zoneId: ZoneId = ZoneId.systemDefault(),
): SpendingAnalytics {
    val expenses = transactions.filter {
        it.type == TransactionType.EXPENSE && it.reviewStatus == ReviewStatus.CONFIRMED
    }
    fun dateOf(record: TransactionRecord) = Instant.ofEpochMilli(record.occurredAt).atZone(zoneId).toLocalDate()
    val currentMonth = YearMonth.from(today)
    val previousMonth = currentMonth.minusMonths(1)
    val current = expenses.filter { YearMonth.from(dateOf(it)) == currentMonth }
    val previous = expenses.filter { YearMonth.from(dateOf(it)) == previousMonth }
    val currentTotal = current.sumOf { it.amountMinor }
    val previousTotal = previous.sumOf { it.amountMinor }
    val elapsedDays = today.dayOfMonth.coerceAtLeast(1)
    val projected = (currentTotal.toDouble() / elapsedDays * currentMonth.lengthOfMonth()).roundToLong()
    val monthlyTrend = (5 downTo 0).map { offset ->
        val month = currentMonth.minusMonths(offset.toLong())
        MonthlySpendPoint(
            month,
            expenses.filter { YearMonth.from(dateOf(it)) == month }.sumOf { it.amountMinor },
        )
    }
    fun namedSpend(groups: Map<String, List<TransactionRecord>>, limit: Int = Int.MAX_VALUE) = groups
        .map { (label, rows) -> NamedSpend(label, rows.sumOf { it.amountMinor }, rows.size) }
        .sortedByDescending { it.amountMinor }
        .take(limit)
    return SpendingAnalytics(
        currentMonthMinor = currentTotal,
        previousMonthMinor = previousTotal,
        monthOverMonthPercent = previousTotal.takeIf { it > 0 }?.let {
            (currentTotal - previousTotal) * 100.0 / it
        },
        projectedMonthMinor = projected,
        averageDailyMinor = if (current.isEmpty()) 0 else currentTotal / elapsedDays,
        largestExpense = current.maxByOrNull { it.amountMinor },
        monthlyTrend = monthlyTrend,
        topMerchants = namedSpend(current.groupBy { it.merchant }, 6),
        categoryBreakdown = namedSpend(current.groupBy { it.categoryLabel() }, 8),
        weekdayBreakdown = DayOfWeek.entries.map { day ->
            val rows = current.filter { dateOf(it).dayOfWeek == day }
            NamedSpend(day.name.lowercase().replaceFirstChar(Char::titlecase), rows.sumOf { it.amountMinor }, rows.size)
        },
    )
}

fun buildCalendarSpend(
    transactions: List<TransactionRecord>,
    month: YearMonth,
    zoneId: ZoneId = ZoneId.systemDefault(),
): Map<LocalDate, DailySpend> = transactions
    .filter { it.type == TransactionType.EXPENSE && it.reviewStatus == ReviewStatus.CONFIRMED }
    .groupBy { Instant.ofEpochMilli(it.occurredAt).atZone(zoneId).toLocalDate() }
    .filterKeys { YearMonth.from(it) == month }
    .mapValues { (date, rows) -> DailySpend(date, rows.sumOf { it.amountMinor }, rows.sortedByDescending { it.occurredAt }) }

fun buildOnDeviceInsights(
    transactions: List<TransactionRecord>,
    recurringPayments: List<RecurringPayment>,
    today: LocalDate = LocalDate.now(),
    zoneId: ZoneId = ZoneId.systemDefault(),
): List<SpendingInsight> {
    val expenses = transactions
        .filter { it.type == TransactionType.EXPENSE && it.reviewStatus == ReviewStatus.CONFIRMED }
        .sortedBy { it.occurredAt }
    if (expenses.isEmpty()) return emptyList()
    val insights = mutableListOf<SpendingInsight>()

    expenses.groupBy { normalizedMerchantKey(it.merchant) }.values.forEach { rows ->
        if (rows.size >= 3) {
            val historical = rows.dropLast(1).map { it.amountMinor }.sorted()
            val median = historical[historical.size / 2]
            val latest = rows.last()
            if (latest.amountMinor >= 50_000 && latest.amountMinor > median * 2) {
                insights += SpendingInsight(
                    InsightKind.ANOMALY,
                    "Unusually high ${latest.merchant} expense",
                    "This is more than twice your typical amount at this merchant.",
                    latest.amountMinor,
                    latest.id,
                    3,
                )
            }
            if (rows.size >= 3) {
                val previous = rows[rows.lastIndex - 1].amountMinor
                if (latest.amountMinor > previous * 1.1 && abs(latest.occurredAt - rows[rows.lastIndex - 1].occurredAt) > 20L * 24 * 60 * 60 * 1000) {
                    insights += SpendingInsight(
                        InsightKind.SUBSCRIPTION_INCREASE,
                        "${latest.merchant} may have increased",
                        "The latest charge is over 10% higher than the previous one.",
                        latest.amountMinor - previous,
                        latest.id,
                        2,
                    )
                }
            }
        }
    }

    expenses.zipWithNext().forEach { (first, second) ->
        if (
            normalizedMerchantKey(first.merchant) == normalizedMerchantKey(second.merchant) &&
            first.amountMinor == second.amountMinor &&
            second.occurredAt - first.occurredAt in 0..(15 * 60 * 1000L)
        ) {
            insights += SpendingInsight(
                InsightKind.DUPLICATE,
                "Possible duplicate charge",
                "Two matching ${second.merchant} expenses occurred within 15 minutes.",
                second.amountMinor,
                second.id,
                4,
            )
        }
    }

    val analytics = buildSpendingAnalytics(expenses, today, zoneId)
    if (analytics.previousMonthMinor > 0 && analytics.projectedMonthMinor > analytics.previousMonthMinor * 1.15) {
        insights += SpendingInsight(
            InsightKind.SPENDING_PACE,
            "Spending pace is higher",
            "At this pace, the month may finish above last month.",
            analytics.projectedMonthMinor,
            priority = 2,
        )
    }
    val monthlyRecurring = recurringPayments.sumOf {
        if (it.intervalDays <= 9) it.typicalAmountMinor * 52 / 12 else it.typicalAmountMinor
    }
    val averageMonthly = analytics.monthlyTrend.map { it.amountMinor }.filter { it > 0 }.average().takeIf { !it.isNaN() } ?: 0.0
    if (averageMonthly > 0 && monthlyRecurring > averageMonthly * 0.35) {
        insights += SpendingInsight(
            InsightKind.FIXED_COMMITMENTS,
            "Fixed commitments are significant",
            "Recurring payments represent about ${(monthlyRecurring * 100 / averageMonthly).roundToLong()}% of typical monthly spending.",
            monthlyRecurring,
            priority = 1,
        )
    }
    analytics.topMerchants.firstOrNull()?.let { top ->
        if (analytics.currentMonthMinor > 0 && top.amountMinor > analytics.currentMonthMinor * 0.4) {
            insights += SpendingInsight(
                InsightKind.CONCENTRATION,
                "Spending is concentrated at ${top.label}",
                "This merchant accounts for ${top.amountMinor * 100 / analytics.currentMonthMinor}% of this month's expenses.",
                top.amountMinor,
                priority = 1,
            )
        }
    }
    return insights.distinctBy { it.kind to it.transactionId }.sortedByDescending { it.priority }.take(8)
}

fun String.normalizedCurrency(): String = trim().uppercase(Locale.ROOT).takeIf { it.matches(Regex("[A-Z]{3}")) } ?: "INR"
