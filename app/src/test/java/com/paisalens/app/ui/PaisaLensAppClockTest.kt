package com.paisalens.app.ui

import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PaisaLensAppClockTest {
    @Test
    fun `review refresh wakes just after local midnight`() {
        val now = ZonedDateTime.of(2026, 8, 20, 23, 59, 30, 0, ZoneId.of("Asia/Kolkata"))

        assertEquals(30_250L, nextReviewDateRefreshDelayMillis(now))
    }

    @Test
    fun `review refresh respects daylight saving day length`() {
        val now = ZonedDateTime.of(2026, 3, 8, 0, 0, 0, 0, ZoneId.of("America/Los_Angeles"))
        val delay = nextReviewDateRefreshDelayMillis(now)

        assertTrue(delay in (22L * 60L * 60L * 1_000L)..(23L * 60L * 60L * 1_000L + 1_000L))
    }
}
