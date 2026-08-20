package com.paisalens.app.data.backup

import java.time.DayOfWeek
import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScheduledBackupModelsTest {
    private val zone = ZoneId.of("Asia/Kolkata")

    @Test
    fun configurationNormalizesUntrustedPreferenceValues() {
        val safe = ScheduledBackupConfiguration(
            enabled = true,
            hour = 99,
            monthDay = 0,
            retentionCount = 500,
            destinationUri = "  content://documents/tree/backups  ",
        ).normalized()

        assertEquals(23, safe.hour)
        assertEquals(1, safe.monthDay)
        assertEquals(30, safe.retentionCount)
        assertEquals("content://documents/tree/backups", safe.destinationUri)
        assertTrue(safe.isReady)
        assertFalse(ScheduledBackupConfiguration(enabled = true).isReady)
    }

    @Test
    fun dailyAndWeeklySchedulingAlwaysMoveForward() {
        val mondayAtThree = ZonedDateTime.of(2026, 8, 10, 3, 0, 0, 0, zone)
        val daily = ScheduledBackupConfiguration(
            enabled = true,
            frequency = ScheduledBackupFrequency.DAILY,
            hour = 3,
            destinationUri = "content://backup",
        )
        assertEquals(mondayAtThree.plusDays(1), nextScheduledBackupAt(daily, mondayAtThree))

        val weekly = daily.copy(
            frequency = ScheduledBackupFrequency.WEEKLY,
            weekday = DayOfWeek.MONDAY,
        )
        assertEquals(mondayAtThree.plusWeeks(1), nextScheduledBackupAt(weekly, mondayAtThree))
        assertEquals(
            mondayAtThree.plusWeeks(1),
            nextScheduledBackupAt(weekly, mondayAtThree.plusHours(1)),
        )
    }

    @Test
    fun monthlySchedulingClampsToEndOfShortMonths() {
        val configuration = ScheduledBackupConfiguration(
            enabled = true,
            frequency = ScheduledBackupFrequency.MONTHLY,
            hour = 2,
            monthDay = 31,
            destinationUri = "content://backup",
        )

        assertEquals(
            ZonedDateTime.of(2026, 2, 28, 2, 0, 0, 0, zone),
            nextScheduledBackupAt(
                configuration,
                ZonedDateTime.of(2026, 2, 1, 12, 0, 0, 0, zone),
            ),
        )
        assertEquals(
            ZonedDateTime.of(2026, 3, 31, 2, 0, 0, 0, zone),
            nextScheduledBackupAt(
                configuration,
                ZonedDateTime.of(2026, 2, 28, 3, 0, 0, 0, zone),
            ),
        )
    }

    @Test
    fun rotationTouchesOnlyOldPaisaLensFilesAndProtectsNewCopy() {
        val documents = listOf(
            ScheduledBackupDocument("new", "PaisaLens-auto-20260814-020000.plbackup", 400),
            ScheduledBackupDocument("second", "PaisaLens-auto-20260813-020000.plbackup", 300),
            ScheduledBackupDocument("old", "PaisaLens-auto-20260812-020000.plbackup", 200),
            ScheduledBackupDocument("older", "PaisaLens-auto-20260811-020000.plbackup", 100),
            ScheduledBackupDocument("user", "tax-return.pdf", 1),
        )

        assertEquals(
            listOf("old", "older"),
            rotatingBackupsToDelete(documents, retentionCount = 2, protectedUri = "new").map { it.uri },
        )
        val oddlyTimestampedCurrent = documents.first().copy(lastModifiedAt = 0)
        val withProviderDelay = listOf(oddlyTimestampedCurrent) + documents.drop(1)
        assertFalse(
            rotatingBackupsToDelete(withProviderDelay, retentionCount = 1, protectedUri = "new")
                .any { it.uri == "new" },
        )
    }
}
