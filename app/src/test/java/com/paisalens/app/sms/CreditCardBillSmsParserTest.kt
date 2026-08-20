package com.paisalens.app.sms

import java.time.LocalDate
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CreditCardBillSmsParserTest {
    private val parser = CreditCardBillSmsParser(ZoneOffset.UTC)

    @Test
    fun parsesHdfcTotalMinimumDueAndCardIdentity() {
        val parsed = parser.parse(
            sender = "VM-HDFCCRD",
            body = "HDFC Bank Credit Card ending 1234: Total Amount Due Rs. 12,345.67. " +
                "Minimum Amount Due Rs. 1,234.56. Payment Due Date: 25-Aug-2026.",
            timestamp = LocalDate.of(2026, 8, 5).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
            messageId = "sms-100",
        )

        requireNotNull(parsed)
        assertEquals("sms-100", parsed.sourceMessageId)
        assertEquals("HDFC Bank", parsed.institutionName)
        assertEquals("1234", parsed.accountHint)
        assertEquals("card:hdfc:1234", parsed.cardIdentityKey)
        assertEquals(1_234_567L, parsed.totalDueMinor)
        assertEquals(123_456L, parsed.minimumDueMinor)
        assertEquals(LocalDate.of(2026, 8, 25).toEpochDay(), parsed.dueDateEpochDay)
    }

    @Test
    fun parsesSbiNumericDueDateAndKeepsMissingCardAlertsDistinct() {
        val parsed = parser.parse(
            sender = "JD-SBICRD",
            body = "SBI Card statement balance: INR 8,500.00. Pay by 20/08/2026.",
            timestamp = LocalDate.of(2026, 8, 1).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
        )

        requireNotNull(parsed)
        assertEquals("SBI Card", parsed.institutionName)
        org.junit.Assert.assertTrue(parsed.cardIdentityKey.startsWith("card:sbi_card:unidentified:"))
        assertEquals(850_000L, parsed.totalDueMinor)
        assertEquals(LocalDate.of(2026, 8, 20).toEpochDay(), parsed.dueDateEpochDay)

        val otherCard = parser.parse(
            sender = "JD-SBICRD",
            body = "SBI Card statement balance: INR 9,500.00. Pay by 20/08/2026.",
            timestamp = LocalDate.of(2026, 8, 1).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
        )
        requireNotNull(otherCard)
        org.junit.Assert.assertNotEquals(parsed.cardIdentityKey, otherCard.cardIdentityKey)
    }

    @Test
    fun infersNextYearForYearlessJanuaryDueDateReceivedInDecember() {
        val parsed = parser.parse(
            sender = "AX-AXISCRD",
            body = "Axis card XX9876 total due INR 4,200. Due on 10 Jan.",
            timestamp = LocalDate.of(2026, 12, 21).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
        )

        requireNotNull(parsed)
        assertEquals(LocalDate.of(2027, 1, 10).toEpochDay(), parsed.dueDateEpochDay)
    }

    @Test
    fun keepsYearlessLateReminderDueDateInCurrentYear() {
        val parsed = parser.parse(
            sender = "VM-HDFCCRD",
            body = "HDFC card ending 1234 total amount due INR 4,200. Due on 25 Aug.",
            timestamp = LocalDate.of(2026, 9, 3).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
        )

        requireNotNull(parsed)
        assertEquals(LocalDate.of(2026, 8, 25).toEpochDay(), parsed.dueDateEpochDay)
    }

    @Test
    fun parsesOrdinalYearlessDueDate() {
        val parsed = parser.parse(
            sender = "VM-HDFCCRD",
            body = "HDFC card ending 1234 total amount due INR 4,200. Due on 25th Aug.",
            timestamp = LocalDate.of(2026, 8, 1).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
        )

        requireNotNull(parsed)
        assertEquals(LocalDate.of(2026, 8, 25).toEpochDay(), parsed.dueDateEpochDay)
    }

    @Test
    fun ignoresPaymentConfirmationAndTransactionAlerts() {
        assertNull(
            parser.parse(
                sender = "VM-HDFCCRD",
                body = "Payment received for card 1234. Total amount due Rs 0. Due date 25-Aug-2026.",
                timestamp = 1_700_000_000_000,
            ),
        )
        assertNull(
            parser.parse(
                sender = "VM-HDFCCRD",
                body = "Rs 500 spent on card 1234 at SWIGGY.",
                timestamp = 1_700_000_000_000,
            ),
        )
    }
}
