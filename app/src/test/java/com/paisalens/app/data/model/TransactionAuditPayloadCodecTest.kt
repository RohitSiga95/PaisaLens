package com.paisalens.app.data.model

import java.util.Base64
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TransactionAuditPayloadCodecTest {
    @Test
    fun legacyPayloadWithoutTrailingExtensionDecodesWithSafeDefaults() {
        val payload = payload(
            record = record(duplicateCount = 7, dedupeFingerprint = "a".repeat(64)),
            smsSources = emptyList(),
        )
        val encodedWithEmptyExtension = TransactionAuditPayloadCodec.encode(
            payload.copy(
                record = payload.record.copy(duplicateCount = 1, dedupeFingerprint = null),
            ),
        )
        // The legacy codec ended immediately after createdAt. With a null fingerprint and no
        // sources, the new trailing extension is int + false boolean + zero count (9 bytes).
        val bytes = Base64.getDecoder().decode(encodedWithEmptyExtension)
        val legacy = Base64.getEncoder().encodeToString(bytes.copyOf(bytes.size - 9))

        val decoded = TransactionAuditPayloadCodec.decode(legacy)

        assertFalse(TransactionAuditPayloadCodec.hasExtension(legacy))
        assertEquals(1, decoded.record.duplicateCount)
        assertNull(decoded.record.dedupeFingerprint)
        assertTrue(decoded.smsSources.isEmpty())
        assertArrayEquals(payload.rawMessageCipher, decoded.rawMessageCipher)
        assertEquals(payload.createdAt, decoded.createdAt)
        assertEquals(payload.record.merchant, decoded.record.merchant)
    }

    @Test
    fun extendedPayloadRoundTripsDuplicateIdentityAndEverySmsSource() {
        val expected = payload(
            record = record(duplicateCount = 3, dedupeFingerprint = "b".repeat(64)),
            smsSources = listOf(
                TransactionAuditSmsSource("received-live", 1_700_000_000_000L),
                TransactionAuditSmsSource("sms-42", 1_700_000_000_123L),
            ),
        )

        val decoded = TransactionAuditPayloadCodec.decode(TransactionAuditPayloadCodec.encode(expected))

        assertTrue(TransactionAuditPayloadCodec.hasExtension(TransactionAuditPayloadCodec.encode(expected)))
        assertEquals(expected.record, decoded.record)
        assertEquals(expected.smsSources, decoded.smsSources)
        assertArrayEquals(expected.rawMessageCipher, decoded.rawMessageCipher)
        assertEquals(expected.createdAt, decoded.createdAt)
    }

    @Test
    fun legacyUndoComparisonAllowsSinglePrimarySourceButRejectsUntrackedDuplicates() {
        val baseline = payload(record = record(duplicateCount = 1, dedupeFingerprint = null))
        val encoded = TransactionAuditPayloadCodec.encode(baseline)
        val bytes = Base64.getDecoder().decode(encoded)
        val legacy = Base64.getEncoder().encodeToString(bytes.copyOf(bytes.size - 9))
        val portableLegacy = requireNotNull(TransactionAuditPayloadCodec.portable(legacy))
        val portableBaseline = baseline.copy(rawMessageCipher = null)
        val unchangedCurrent = TransactionAuditPayloadCodec.encode(
            portableBaseline.copy(
                record = baseline.record.copy(dedupeFingerprint = "c".repeat(64)),
                smsSources = listOf(
                    TransactionAuditSmsSource(
                        baseline.record.sourceMessageId,
                        baseline.record.occurredAt,
                    ),
                ),
            ),
        )
        val duplicatedCurrent = TransactionAuditPayloadCodec.encode(
            portableBaseline.copy(
                record = baseline.record.copy(duplicateCount = 2, dedupeFingerprint = "c".repeat(64)),
                smsSources = listOf(
                    TransactionAuditSmsSource(baseline.record.sourceMessageId, baseline.record.occurredAt),
                    TransactionAuditSmsSource("sms-42", baseline.record.occurredAt),
                ),
            ),
        )

        assertFalse(TransactionAuditPayloadCodec.hasExtension(portableLegacy))
        assertNull(TransactionAuditPayloadCodec.decode(portableLegacy).rawMessageCipher)
        assertTrue(TransactionAuditPayloadCodec.equivalentForUndo(portableLegacy, unchangedCurrent))
        assertFalse(TransactionAuditPayloadCodec.equivalentForUndo(legacy, duplicatedCurrent))
        assertFalse(
            TransactionAuditPayloadCodec.equivalentForUndo(
                legacy,
                TransactionAuditPayloadCodec.encode(
                    baseline.copy(record = baseline.record.copy(merchant = "Changed later")),
                ),
            ),
        )
    }

    private fun payload(
        record: TransactionRecord,
        smsSources: List<TransactionAuditSmsSource> = emptyList(),
    ) = TransactionAuditPayload(
        record = record,
        rawMessageCipher = byteArrayOf(9, 8, 7),
        createdAt = 1_700_000_000_999L,
        smsSources = smsSources,
    )

    private fun record(
        duplicateCount: Int,
        dedupeFingerprint: String?,
    ) = TransactionRecord(
        id = 42,
        sourceMessageId = "received-live",
        amountMinor = 8_000,
        merchant = "Corner Store",
        accountHint = "1234",
        category = ExpenseCategory.SHOPPING,
        type = TransactionType.EXPENSE,
        occurredAt = 1_700_000_000_000L,
        source = TransactionSource.BANK,
        sender = "VK-HDFCBK",
        tags = listOf("daily"),
        duplicateCount = duplicateCount,
        dedupeFingerprint = dedupeFingerprint,
    )
}
