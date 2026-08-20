package com.paisalens.app.data.backup

import java.time.DayOfWeek
import java.time.YearMonth
import java.time.ZonedDateTime
import java.time.temporal.TemporalAdjusters

enum class ScheduledBackupFrequency(val storageId: String) {
    DAILY("daily"),
    WEEKLY("weekly"),
    MONTHLY("monthly");

    companion object {
        fun fromStorageId(value: String?): ScheduledBackupFrequency =
            entries.firstOrNull { it.storageId == value } ?: WEEKLY
    }
}

data class ScheduledBackupConfiguration(
    val enabled: Boolean = false,
    val frequency: ScheduledBackupFrequency = ScheduledBackupFrequency.WEEKLY,
    val hour: Int = DEFAULT_HOUR,
    val weekday: DayOfWeek = DayOfWeek.SUNDAY,
    val monthDay: Int = 1,
    val retentionCount: Int = DEFAULT_RETENTION_COUNT,
    val destinationUri: String? = null,
) {
    val isReady: Boolean
        get() = enabled && !destinationUri.isNullOrBlank()

    fun normalized(): ScheduledBackupConfiguration = copy(
        hour = hour.coerceIn(0, 23),
        monthDay = monthDay.coerceIn(1, 31),
        retentionCount = retentionCount.coerceIn(MIN_RETENTION_COUNT, MAX_RETENTION_COUNT),
        destinationUri = destinationUri?.trim()?.takeIf(String::isNotEmpty),
    )

    companion object {
        const val DEFAULT_HOUR = 2
        const val DEFAULT_RETENTION_COUNT = 5
        const val MIN_RETENTION_COUNT = 1
        const val MAX_RETENTION_COUNT = 30

        fun safeWeekday(value: String?): DayOfWeek = runCatching {
            DayOfWeek.valueOf(value.orEmpty())
        }.getOrDefault(DayOfWeek.SUNDAY)
    }
}

data class ScheduledBackupStatus(
    val lastAttemptAt: Long = 0L,
    val lastSuccessfulAt: Long = 0L,
    val lastVerifiedAt: Long = 0L,
    val lastFailureAt: Long = 0L,
    val lastFailureMessage: String? = null,
    val lastFileName: String? = null,
    val lastWarningMessage: String? = null,
)

data class ScheduledBackupDocument(
    val uri: String,
    val displayName: String,
    val lastModifiedAt: Long,
)

/** Returns only PaisaLens auto-backups that exceed retention; unrelated user files are never touched. */
fun rotatingBackupsToDelete(
    documents: List<ScheduledBackupDocument>,
    retentionCount: Int,
    protectedUri: String? = null,
): List<ScheduledBackupDocument> {
    val keep = retentionCount.coerceIn(
        ScheduledBackupConfiguration.MIN_RETENTION_COUNT,
        ScheduledBackupConfiguration.MAX_RETENTION_COUNT,
    )
    val candidates = documents.filter { it.displayName.isScheduledBackupFileName() }
        .sortedWith(
            compareByDescending<ScheduledBackupDocument> { it.uri == protectedUri }
                .thenByDescending { it.lastModifiedAt }
                .thenByDescending { it.displayName },
        )
    return candidates.drop(keep).filterNot { it.uri == protectedUri }
}

fun nextScheduledBackupAt(
    configuration: ScheduledBackupConfiguration,
    now: ZonedDateTime,
): ZonedDateTime {
    val safe = configuration.normalized()
    return when (safe.frequency) {
        ScheduledBackupFrequency.DAILY -> {
            val today = now.toLocalDate().atTime(safe.hour, 0).atZone(now.zone)
            if (today.isAfter(now)) today else today.plusDays(1)
        }
        ScheduledBackupFrequency.WEEKLY -> {
            val date = now.toLocalDate().with(TemporalAdjusters.nextOrSame(safe.weekday))
            val candidate = date.atTime(safe.hour, 0).atZone(now.zone)
            if (candidate.isAfter(now)) candidate else candidate.plusWeeks(1)
        }
        ScheduledBackupFrequency.MONTHLY -> {
            val month = YearMonth.from(now)
            val candidate = month.atDay(safe.monthDay.coerceAtMost(month.lengthOfMonth()))
                .atTime(safe.hour, 0)
                .atZone(now.zone)
            if (candidate.isAfter(now)) {
                candidate
            } else {
                val nextMonth = month.plusMonths(1)
                nextMonth.atDay(safe.monthDay.coerceAtMost(nextMonth.lengthOfMonth()))
                    .atTime(safe.hour, 0)
                    .atZone(now.zone)
            }
        }
    }
}

internal fun String.isScheduledBackupFileName(): Boolean =
    matches(SCHEDULED_BACKUP_FILE_PATTERN)

internal const val SCHEDULED_BACKUP_FILE_PREFIX = "PaisaLens-auto-"
internal const val SCHEDULED_BACKUP_FILE_SUFFIX = ".plbackup"
private val SCHEDULED_BACKUP_FILE_PATTERN =
    Regex("^PaisaLens-auto-[0-9]{8}-[0-9]{6}\\.plbackup$")
