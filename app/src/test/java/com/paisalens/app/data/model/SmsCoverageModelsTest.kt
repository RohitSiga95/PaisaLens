package com.paisalens.app.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SmsCoverageModelsTest {
    @Test
    fun duplicateFingerprintIgnoresRoutingPrefixWhitespaceAndCaseButKeepsReferenceIdentity() {
        val first = transaction(
            id = "sms-10",
            sender = "VM-HDFCBK",
            body = "Rs 500.00 debited from A/c 0801 at CAFE. UTR 123456789012",
        )
        val duplicate = transaction(
            id = "received-20",
            sender = "AX-HDFCBK",
            body = "  rs 500.00 DEBITED from a/c 0801 at cafe. utr 123456789012  ",
            timestamp = first.occurredAt + 90_000,
        )
        val separatePurchase = duplicate.copy(
            sourceMessageId = "received-21",
            rawMessage = "Rs 500.00 debited from A/c 0801 at CAFE. UTR ABCDEF987654",
        )

        assertEquals(smsDuplicateFingerprint(first), smsDuplicateFingerprint(duplicate))
        assertTrue(areLikelyDuplicateSms(first, duplicate))
        assertFalse(areLikelyDuplicateSms(first, separatePurchase))
    }

    @Test
    fun duplicateFingerprintRetainsFinancialAndDateDigits() {
        val original = transaction(id = "one", body = "INR 500 debited from A/c 0801 at CAFE on 14-Aug-26")
        val differentAmount = transaction(
            id = "two",
            amountMinor = 60_000,
            body = "INR 600 debited from A/c 0801 at CAFE on 14-Aug-26",
        )
        val differentAccount = transaction(
            id = "three",
            accountHint = "8004",
            body = "INR 500 debited from A/c 8004 at CAFE on 14-Aug-26",
        )
        val differentDate = transaction(
            id = "four",
            body = "INR 500 debited from A/c 0801 at CAFE on 15-Aug-26",
        )

        assertFalse(areLikelyDuplicateSms(original, differentAmount))
        assertFalse(areLikelyDuplicateSms(original, differentAccount))
        assertFalse(areLikelyDuplicateSms(original, differentDate))
    }

    @Test
    fun identicalAlertOutsideWindowIsNotMerged() {
        val first = transaction(id = "one")
        val later = transaction(
            id = "two",
            timestamp = first.occurredAt + SMS_DUPLICATE_WINDOW_MILLIS + 1,
        )

        assertFalse(areLikelyDuplicateSms(first, later))
    }

    @Test
    fun identicalAlertsWithoutAStableReferenceRemainSeparate() {
        val first = transaction(
            id = "one",
            body = "INR 500 debited from A/c 0801 at CAFE",
        )
        val second = first.copy(
            sourceMessageId = "two",
            occurredAt = first.occurredAt + 1_000,
        )

        assertEquals(smsDuplicateFingerprint(first), smsDuplicateFingerprint(second))
        assertFalse(areLikelyDuplicateSms(first, second))
    }

    @Test
    fun exactTimestampNoReferenceCopiesFromReceiverAndInboxAreOneSms() {
        val received = transaction(
            id = "received-body-and-time",
            body = "INR 500 debited from A/c 0801 at CAFE",
        )
        val scanned = received.copy(sourceMessageId = "inbox-row-42")

        assertTrue(areLikelyDuplicateSms(received, scanned))
    }

    @Test
    fun coverageClassifierKeepsLikelyFinancialFailuresButRejectsOtp() {
        assertEquals(
            SmsCoverageReason.MISSING_DIRECTION,
            smsCoverageReasonOrNull("VM-HDFCBK", "Card alert for INR 2,500 at an unsupported format"),
        )
        assertEquals(
            SmsCoverageReason.MISSING_AMOUNT,
            smsCoverageReasonOrNull("VM-SBIINB", "Your account statement is now available"),
        )
        assertNull(
            smsCoverageReasonOrNull(
                "VM-HDFCBK",
                "OTP is 123456 for a transaction of INR 2,500. Do not share.",
            ),
        )
        assertNull(smsCoverageReasonOrNull("FRIEND", "Dinner at 8 tonight?"))
    }

    @Test
    fun coverageRuleIsLiteralNormalizedAndSenderScoped() {
        val rule = SmsCoverageRule(
            name = "  Local co-op alert  ",
            senderKey = "VM-MYBANK",
            requiredPhrases = listOf(" Purchase Alert ", "GREEN   MART", "green mart"),
            merchantName = " Green Mart ",
        ).normalized()

        assertEquals("Local co-op alert", rule.name)
        assertEquals("MYBANK", rule.senderKey)
        assertEquals(listOf("purchase alert", "green mart"), rule.requiredPhrases)
        assertTrue(rule.matches("AX-MYBANK", "Purchase alert received for GREEN MART"))
        assertFalse(rule.matches("AX-OTHER", "Purchase alert received for GREEN MART"))
    }

    private fun transaction(
        id: String,
        sender: String = "VM-HDFCBK",
        body: String = "INR 500 debited from A/c 0801 at CAFE",
        amountMinor: Long = 50_000,
        accountHint: String = "0801",
        timestamp: Long = 1_700_000_000_000,
    ) = ParsedTransaction(
        sourceMessageId = id,
        amountMinor = amountMinor,
        merchant = "Cafe",
        accountHint = accountHint,
        category = ExpenseCategory.FOOD,
        type = TransactionType.EXPENSE,
        occurredAt = timestamp,
        source = TransactionSource.BANK,
        sender = sender,
        rawMessage = body,
    )
}
