package com.paisalens.app.sms

import org.junit.Assert.assertEquals
import org.junit.Test

class SmsInboxScannerTest {
    @Test
    fun prefersSanePositiveSentTimestamp() {
        val receivedAt = 1_700_000_060_000L
        val sentAt = receivedAt - 60_000L

        assertEquals(sentAt, preferredSmsTimestamp(sentAt, receivedAt))
    }

    @Test
    fun fallsBackToReceivedTimestampWhenSentTimestampIsMissingOrImplausible() {
        val receivedAt = 1_700_000_060_000L

        assertEquals(receivedAt, preferredSmsTimestamp(0L, receivedAt))
        assertEquals(
            receivedAt,
            preferredSmsTimestamp(receivedAt - 8L * 24L * 60L * 60L * 1000L, receivedAt),
        )
    }
}
