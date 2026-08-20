package com.paisalens.app.data.parser

import com.paisalens.app.data.model.ExpenseCategory
import com.paisalens.app.data.model.TransactionSource
import com.paisalens.app.data.model.TransactionType
import com.paisalens.app.data.model.ReviewStatus
import com.paisalens.app.data.model.SmsCoverageRule
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
    fun recognizesCreditCardSenderWhenBodyDoesNotSayCard() {
        val result = parser.parse(
            sender = "JD-SBICRD",
            body = "INR 1,299 spent from A/c XX4567 at AMAZON.",
            timestamp = 1_700_000_000_000,
        )

        requireNotNull(result)
        assertEquals(TransactionSource.CARD, result.source)
        assertEquals("4567", result.accountHint)
    }

    @Test
    fun creditCardSenderWinsWhenBodyMentionsUpi() {
        val result = parser.parse(
            sender = "JD-SBICRD",
            body = "INR 899 spent from XX4567 via UPI at AMAZON.",
            timestamp = 1_700_000_000_000,
        )

        requireNotNull(result)
        assertEquals(TransactionSource.CARD, result.source)
    }

    @Test
    fun ordinaryBankSenderRemainsBankSource() {
        val result = parser.parse(
            sender = "VM-IDFCBK",
            body = "INR 500 debited from account XX9012 at CAFE.",
            timestamp = 1_700_000_000_000,
        )

        requireNotNull(result)
        assertEquals(TransactionSource.BANK, result.source)
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

    @Test
    fun literalCoverageRuleParsesPreviouslyUnsupportedAlertForReview() {
        val result = parser.parse(
            sender = "VM-MYBANK",
            body = "Alert for INR 250.00 at GREEN MART using card ending 1234.",
            timestamp = 1_700_000_000_000,
            messageId = "sms-rule-1",
            coverageRules = listOf(
                SmsCoverageRule(
                    id = 7,
                    name = "MyBank purchase",
                    senderKey = "MYBANK",
                    requiredPhrases = listOf("green mart", "using card"),
                    merchantName = "Green Mart",
                    category = ExpenseCategory.GROCERIES,
                    type = TransactionType.EXPENSE,
                    source = TransactionSource.CARD,
                ),
            ),
        )

        requireNotNull(result)
        assertEquals(25_000L, result.amountMinor)
        assertEquals("Green Mart", result.merchant)
        assertEquals("1234", result.accountHint)
        assertEquals(ExpenseCategory.GROCERIES, result.category)
        assertEquals(TransactionSource.CARD, result.source)
        assertEquals(ReviewStatus.NEEDS_REVIEW, result.reviewStatus)
        assertTrue(result.reviewReason.orEmpty().contains("MyBank purchase"))
    }

    @Test
    fun coverageRuleNeverOverridesAuthenticationMessage() {
        val result = parser.parse(
            sender = "VM-MYBANK",
            body = "OTP is 123456 for INR 250 at GREEN MART. Do not share.",
            timestamp = 1_700_000_000_000,
            coverageRules = listOf(
                SmsCoverageRule(
                    name = "Unsafe broad rule",
                    senderKey = "MYBANK",
                    requiredPhrases = listOf("green mart"),
                    merchantName = "Green Mart",
                ),
            ),
        )

        assertNull(result)
    }
}
