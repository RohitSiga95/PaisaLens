package com.paisalens.app.data.model

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** One physical account, possibly represented by multiple stored SMS aliases. */
data class AccountBalanceSourceGroup(
    val accountIds: Set<Long>,
    val label: String,
    val preferSmsBalance: Boolean = false,
)

data class AccountBalanceDay(
    val date: LocalDate,
    val amountMinor: Long?,
    val allReadingsFromThisDay: Boolean = false,
    val oldestReadingAt: Long? = null,
)

/**
 * One end-of-day value per calendar day, with an explicit distinction between a saved reading
 * and a carried-forward value. A merged total is available only when every member has a reading.
 * Neither unobserved balances nor transactions are used to invent earlier balances.
 */
fun dailyAccountBalances(
    history: List<AccountBalanceSnapshot>,
    accountType: AccountType,
    sources: List<AccountBalanceSourceGroup>,
    endingOn: LocalDate,
    days: Int,
    zoneId: ZoneId,
): List<AccountBalanceDay> {
    require(days in 1..90)
    val firstDay = endingOn.minusDays(days - 1L)
    val endExclusive = endingOn.plusDays(1).atStartOfDay(zoneId).toInstant().toEpochMilli()
    val startMillis = firstDay.atStartOfDay(zoneId).toInstant().toEpochMilli()
    val sourceById = buildMap {
        sources.forEachIndexed { index, source -> source.accountIds.forEach { put(it, index) } }
    }
    data class Observation(val sourceIndex: Int, val snapshot: AccountBalanceSnapshot, val amount: Long)
    val observations = history.asSequence()
        .filter { it.recordedAt in 1 until endExclusive }
        .mapNotNull { snapshot ->
            val source = sourceById[snapshot.physicalAccountId ?: snapshot.accountId] ?: return@mapNotNull null
            val amount = if (accountType == AccountType.CREDIT_CARD) snapshot.availableCreditMinor else snapshot.balanceMinor
            amount?.let { Observation(source, snapshot, it) }
        }
        .sortedWith(compareBy<Observation> { it.snapshot.recordedAt }.thenBy { it.snapshot.id })
        .toList()
    val newest = arrayOfNulls<Observation>(sources.size)
    val newestSms = arrayOfNulls<Observation>(sources.size)
    var offset = 0
    return List(days) { index ->
        val day = firstDay.plusDays(index.toLong())
        val dayStart = if (index == 0) startMillis else day.atStartOfDay(zoneId).toInstant().toEpochMilli()
        val nextDayStart = day.plusDays(1).atStartOfDay(zoneId).toInstant().toEpochMilli()
        while (offset < observations.size && observations[offset].snapshot.recordedAt < nextDayStart) {
            val reading = observations[offset++]
            newest[reading.sourceIndex] = reading
            if (sources[reading.sourceIndex].preferSmsBalance && isSmsBalanceSource(reading.snapshot.sender)) {
                newestSms[reading.sourceIndex] = reading
            }
        }
        val readings = sources.indices.mapNotNull { source -> newestSms[source] ?: newest[source] }
        if (sources.isEmpty() || readings.size != sources.size) {
            AccountBalanceDay(day, amountMinor = null)
        } else {
            val amount = runCatching { readings.fold(0L) { sum, reading -> Math.addExact(sum, reading.amount) } }
                .getOrNull()
            val oldest = readings.minOf { it.snapshot.recordedAt }
            AccountBalanceDay(
                date = day,
                amountMinor = amount,
                allReadingsFromThisDay = amount != null && oldest >= dayStart,
                oldestReadingAt = oldest.takeIf { amount != null },
            )
        }
    }
}

internal fun AccountProfile.currentBalanceReading(): Long? =
    if (type == AccountType.CREDIT_CARD) availableCreditMinor else balanceMinor

internal fun AccountProfile.currentBalanceReadingDate(zoneId: ZoneId): LocalDate? =
    availabilityFetchedAt?.takeIf { it > 0 }?.let { Instant.ofEpochMilli(it).atZone(zoneId).toLocalDate() }
