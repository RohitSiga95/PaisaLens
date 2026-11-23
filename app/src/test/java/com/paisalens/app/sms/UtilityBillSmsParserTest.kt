package com.paisalens.app.sms

import com.paisalens.app.data.model.UtilityBillKind
import java.time.Instant
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UtilityBillSmsParserTest {
    private val received = Instant.parse("2026-11-04T08:00:00Z").toEpochMilli()

    @Test
    fun readsElectricityTotalRatherThanLateFeeAndMasksTheConsumerId() {
        val bill = requireNotNull(UtilityBillSmsParser.parse(
            "VM-BESCOM-S",
            "BESCOM electricity bill for Consumer No. 0000123456. Total amount due: Rs. 1,234.50. " +
                "Due date: 20 November 2026. Late fee: Rs. 100.",
            received,
        ))
        assertEquals(UtilityBillKind.ELECTRICITY, bill.kind)
        assertEquals("BESCOM · Electricity · ••3456", bill.title)
        assertEquals(123_450L, bill.amountMinor)
        assertEquals(LocalDate.of(2026, 11, 20).toEpochDay(), bill.dueDateEpochDay)
        assertEquals(64, bill.accountKey?.length)
    }

    @Test
    fun readsBroadbandBillWithAnIndianNumericDateAndKeepsServiceAccountOverContactNumber() {
        val bill = requireNotNull(UtilityBillSmsParser.parse(
            "AX-AIRTEL",
            "Airtel Broadband bill of Rs. 799.00 for Service Account ID 43215678. " +
                "Contact mobile number 9000001234. Please pay by 24/11/26.",
            received,
        ))
        assertEquals(UtilityBillKind.BROADBAND, bill.kind)
        assertEquals("5678", bill.accountLastFour)
        assertEquals(79_900L, bill.amountMinor)
        assertEquals(LocalDate.of(2026, 11, 24).toEpochDay(), bill.dueDateEpochDay)
    }

    @Test
    fun readsPostpaidAmountAndRelativeOrTextualDueDatesInIndia() {
        val body = "Postpaid bill for mobile number XXXXXX9101. Amount due 499.00. Pay Rs. 499.00 by "
        val byDecember = requireNotNull(UtilityBillSmsParser.parse("JD-JIOINF-T", body + "15 Dec.", received))
        assertEquals(UtilityBillKind.MOBILE_POSTPAID, byDecember.kind)
        assertEquals("9101", byDecember.accountLastFour)
        assertEquals(49_900L, byDecember.amountMinor)
        assertEquals(LocalDate.of(2026, 12, 15).toEpochDay(), byDecember.dueDateEpochDay)

        val atMidnightInIndia = Instant.parse("2026-11-04T19:00:00Z").toEpochMilli()
        val tomorrow = requireNotNull(UtilityBillSmsParser.parse("AX-JIOINF", body + "tomorrow.", atMidnightInIndia))
        assertEquals(LocalDate.of(2026, 11, 6).toEpochDay(), tomorrow.dueDateEpochDay)
    }

    @Test
    fun fingerprintsIgnoreTransportPrefixButNotBillingCycle() {
        val body = "Electricity bill for consumer 33445566. Amount due Rs. 980. Due on "
        val bill = requireNotNull(UtilityBillSmsParser.parse("AX-BESCOM-S", body + "19/11", received))
        val copy = requireNotNull(UtilityBillSmsParser.parse("VM-BESCBL-T", body + "19/11", received + 60_000))
        val later = requireNotNull(UtilityBillSmsParser.parse("AX-BESCOM", body + "19/12", received))
        assertEquals(bill.noticeKeys, copy.noticeKeys)
        assertEquals(bill.accountKey, later.accountKey)
        assertNotEquals(bill.noticeKeys, later.noticeKeys)
    }

    @Test
    fun ignoresReceiptsPrepaidOtpsCardBillsAndAmbiguousOrMissingFields() {
        listOf(
            "Thank you for your payment of Rs. 540 for the broadband bill due on 19 Nov.",
            "Prepaid plan recharge. Broadband bill amount Rs. 299, due on 19 Nov.",
            "OTP for your mobile postpaid bill. Amount due Rs. 540. Due on 19 Nov.",
            "Credit card bill. Amount due Rs. 540. Due on 19 Nov.",
            "Electricity bill, amount due Rs. 550.00. As of 16 Nov.",
            "Electricity bill, amount due Rs. 550. Due date 31 Nov.",
            "Electricity bill, due on 19 Nov.",
            "Electricity bill, amount due Rs. 550 and amount due Rs. 570. Due on 19 Nov.",
        ).forEach { message -> assertNull(message, UtilityBillSmsParser.parse("AX-BESCOM", message, received)) }
        assertNull(UtilityBillSmsParser.parse(
            "+919001234567", "Electricity bill, amount due Rs. 550. Due on 19 Nov.", received,
        ))
    }
}
