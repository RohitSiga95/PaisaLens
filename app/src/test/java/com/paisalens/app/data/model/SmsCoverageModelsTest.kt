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
    fun stableReferenceRecognitionAcceptsExplicitBankReferenceLabels() {
        listOf(
            "UTR 123456789012",
            "RRN: 987654321000",
            "Ref ABC123456",
            "Reference No. ZX90CV1234",
            "UPI Ref No 123456789012",
            "Txn ID: AB12CD3456",
            "Transaction No. 123456789012",
            "Txn: AB12CD3456",
            "Txn 123456789012",
        ).forEach { body ->
            assertTrue("Expected a stable reference in: $body", hasStableSmsTransactionReference(body))
        }
    }

    @Test
    fun stableReferenceRecognitionRejectsTransactionStatusProse() {
        listOf(
            "Txn successful for Rs 80",
            "Txn initiated",
            "Txn failed",
            "Transaction successful for INR 500",
        ).forEach { body ->
            assertFalse("Expected no stable reference in: $body", hasStableSmsTransactionReference(body))
        }
    }

    @Test
    fun noReferenceCopiesFromReceiverAndInboxRequireExactNetworkSentTime() {
        val received = transaction(
            id = "received-body-and-time",
            body = "INR 500 debited from A/c 0801 at CAFE",
        )
        val exactInboxCopy = received.copy(
            sourceMessageId = "sms-42",
        )
        val deliveryTimeOffsetCopy = exactInboxCopy.copy(
            sourceMessageId = "sms-43",
            occurredAt = received.occurredAt + 60_000L,
        )

        assertTrue(areLikelyDuplicateSms(received, exactInboxCopy))
        assertFalse(areLikelyDuplicateSms(received, deliveryTimeOffsetCopy))
    }

    @Test
    fun noReferenceCopiesFromTheSameIngestPathRemainSeparateEvenAtTheExactTime() {
        val first = transaction(
            id = "sms-42",
            body = "INR 500 debited from A/c 0801 at CAFE",
        )
        val second = first.copy(sourceMessageId = "sms-43")

        assertFalse(areLikelyDuplicateSms(first, second))
    }

    @Test
    fun ingestProvenanceRecognizesCurrentLegacyAndInboxSourceIds() {
        assertEquals(
            SmsIngestProvenance.LIVE_RECEIVER,
            smsIngestProvenance("received-0123456789abcdef01234567-1700000000000"),
        )
        assertEquals(
            SmsIngestProvenance.LIVE_RECEIVER,
            smsIngestProvenance("0123456789abcdef01234567"),
        )
        assertEquals(SmsIngestProvenance.INBOX, smsIngestProvenance("sms-42"))
        assertEquals(
            SmsIngestProvenance.INBOX,
            smsIngestProvenance("sms-42-${"a".repeat(64)}-1700000000000"),
        )
        assertEquals(
            SmsIngestProvenance.RESTORED_INBOX,
            smsIngestProvenance("restored-1700000000000-sms-42"),
        )
        assertEquals(
            SmsIngestProvenance.RESTORED_INBOX,
            smsIngestProvenance(
                "restored-1700000000000-sms-42-${"a".repeat(64)}-1699999999000",
            ),
        )
        assertEquals(SmsIngestProvenance.OTHER, smsIngestProvenance("manual-42"))
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
