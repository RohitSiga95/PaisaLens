package com.paisalens.app.data.parser

import com.paisalens.app.data.model.ExpenseCategory
import com.paisalens.app.data.model.TransactionSource
import com.paisalens.app.data.model.TransactionType
import com.paisalens.app.data.model.ReviewStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TransactionSmsParserTest {
    private val parser = TransactionSmsParser()

    @Test
    fun parsesBankDebitAndExtractsAccountHint() {
        val result = parser.parse(
            sender = "VK-HDFCBK",
            body = "Rs. 450.00 debited from A/c XX1234 at SWIGGY on 04-Aug-26. Avl bal Rs 8,200.",
            timestamp = 1_700_000_000_000,
            messageId = "sms-1",
        )

        requireNotNull(result)
        assertEquals(45_000, result.amountMinor)
        assertEquals("Swiggy", result.merchant)
        assertEquals("1234", result.accountHint)
        assertEquals(ExpenseCategory.FOOD, result.category)
        assertEquals(TransactionType.EXPENSE, result.type)
        assertEquals(TransactionSource.BANK, result.source)
    }

    @Test
    fun parsesUpiPayment() {
        val result = parser.parse(
            sender = "AX-ICICIB",
            body = "You paid ₹299 to ZOMATO via UPI. Ref 7238199281.",
            timestamp = 1_700_000_000_000,
        )

        requireNotNull(result)
        assertEquals(29_900, result.amountMinor)
        assertEquals("Zomato", result.merchant)
        assertEquals(ExpenseCategory.FOOD, result.category)
        assertEquals(TransactionSource.UPI, result.source)
    }

    @Test
    fun parsesIncomingCredit() {
        val result = parser.parse(
            sender = "VM-SBIINB",
            body = "INR 25,000.00 credited to your account XX8891 by ACME PAYROLL.",
            timestamp = 1_700_000_000_000,
        )

        requireNotNull(result)
        assertEquals(2_500_000, result.amountMinor)
        assertEquals(TransactionType.INCOME, result.type)
        assertEquals(ExpenseCategory.INCOME, result.category)
        assertTrue(result.merchant.startsWith("Acme Payroll"))
    }

    @Test
    fun detectsRefundBeforeCredit() {
        val result = parser.parse(
            sender = "AD-HDFCBK",
            body = "Refund of Rs 899.50 from AMAZON has been credited to card XX4567.",
            timestamp = 1_700_000_000_000,
        )

        requireNotNull(result)
        assertEquals(89_950, result.amountMinor)
        assertEquals(TransactionType.REFUND, result.type)
        assertEquals(ExpenseCategory.SHOPPING, result.category)
    }

    @Test
    fun excludesOtpEvenWhenAmountIsPresent() {
        val result = parser.parse(
            sender = "VK-BANK",
            body = "OTP is 123456 for transaction of INR 4,999 at MERCHANT. Do not share.",
            timestamp = 1_700_000_000_000,
        )

        assertNull(result)
    }

    @Test
    fun treatsCreditCardBillPaymentAsTransfer() {
        val result = parser.parse(
            sender = "JM-CARDBK",
            body = "Payment received towards your credit card bill: INR 12,500. Thank you.",
            timestamp = 1_700_000_000_000,
        )

        requireNotNull(result)
        assertEquals(TransactionType.TRANSFER, result.type)
        assertEquals(ExpenseCategory.TRANSFER, result.category)
    }

    @Test
    fun ignoresDueReminder() {
        val result = parser.parse(
            sender = "JM-CARDBK",
            body = "Payment due: total amount due INR 8,450 by 10-Aug-26.",
            timestamp = 1_700_000_000_000,
        )

        assertNull(result)
    }

    @Test
    fun createsStableIdForSameInput() {
        val first = parser.parse(
            sender = "VK-BANK",
            body = "Rs 100 debited at METRO.",
            timestamp = 1_700_000_000_000,
        )
        val second = parser.parse(
            sender = "VK-BANK",
            body = "Rs 100 debited at METRO.",
            timestamp = 1_700_000_000_000,
        )

        assertEquals(first?.sourceMessageId, second?.sourceMessageId)
    }

    @Test
    fun sendsUncertainMerchantAndCategoryToReviewInbox() {
        val result = parser.parse(
            sender = "VK-UNKNOWN",
            body = "Rs 1,250 debited from your account.",
            timestamp = 1_700_000_000_000,
        )

        requireNotNull(result)
        assertEquals(ReviewStatus.NEEDS_REVIEW, result.reviewStatus)
        assertTrue(result.reviewReason.orEmpty().contains("Merchant"))
        assertTrue(result.reviewReason.orEmpty().contains("category"))
    }

    @Test
    fun treatsExplicitSelfTransferAsTransfer() {
        val result = parser.parse(
            sender = "VK-BANK",
            body = "Self transfer of INR 5,000 between your accounts XX1234 and XX9876 completed.",
            timestamp = 1_700_000_000_000,
        )

        requireNotNull(result)
        assertEquals(TransactionType.TRANSFER, result.type)
        assertEquals(ExpenseCategory.TRANSFER, result.category)
    }
}
