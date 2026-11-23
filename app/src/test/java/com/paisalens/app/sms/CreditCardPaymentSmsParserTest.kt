package com.paisalens.app.sms

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CreditCardPaymentSmsParserTest {
    @Test
    fun `issuer payment acknowledgement records the amount and physical card`() {
        val receipt = requireNotNull(CreditCardPaymentSmsParser.parse(
            "VM-HDFCBK-S",
            "We have received your payment of Rs. 5,200.25 towards your HDFC Bank Credit Card ending XX1234.",
            timestamp,
        ))
        assertEquals("HDFC Bank", receipt.institutionName)
        assertEquals("1234", receipt.cardLastFour)
        assertEquals(520_025L, receipt.amountMinor)
        assertEquals(timestamp, receipt.receivedAt)
        val delayedWording = requireNotNull(CreditCardPaymentSmsParser.parse(
            "VM-SBICRD",
            "Payment of Rs 2000/- towards your SBI Card account ending 4321 has been credited.",
            timestamp,
        ))
        assertEquals(200_000L, delayedWording.amountMinor)
    }

    @Test
    fun `one payment reference survives timestamp and sender variations`() {
        val body = "Payment of INR 1,000 received for your SBI Card ending 4321. Ref No: 123456789012."
        val original = requireNotNull(CreditCardPaymentSmsParser.parse("VM-SBICRD", body, timestamp))
        val rescan = requireNotNull(CreditCardPaymentSmsParser.parse("AD-SBICRD-S", body, timestamp + 60_000))
        val separate = requireNotNull(CreditCardPaymentSmsParser.parse(
            "VM-SBICRD", body.replace("123456789012", "223456789012"), timestamp,
        ))
        assertEquals(original.key, rescan.key)
        assertNull(original.unreferencedMessageHash)
        assertNotEquals(original.key, separate.key)
    }

    @Test
    fun `purchase refund ambiguous card and pending payment messages are excluded`() {
        val excluded = listOf(
            "INR 500 spent on your Credit Card ending 1234.",
            "Payment of Rs 500 received for Credit Card xx1234 but was reversed.",
            "Your upcoming payment of Rs 500 is scheduled for Credit Card xx1234.",
            "Payment request for INR 500 on your Credit Card xx1234.",
            "Payment of INR 500 was successful using your HDFC Bank Credit Card xx1234.",
            "Payment of INR 500 received for Credit Card xx1234. Credit Card xx4321 has a separate bill.",
            "Payment received for INR 500. Your Credit Card bill is ready.",
        )
        excluded.forEach { body -> assertNull(body, CreditCardPaymentSmsParser.parse("VM-HDFCBK", body, timestamp)) }
        assertNull(CreditCardPaymentSmsParser.parse(
            "+911234567890", "Payment of INR 500 received for Credit Card xx1234.", timestamp,
        ))
    }

    private val timestamp = 1_779_000_000_000L
}
