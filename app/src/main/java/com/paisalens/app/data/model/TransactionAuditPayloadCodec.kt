package com.paisalens.app.data.model

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.util.Base64

internal data class TransactionAuditPayload(
    val record: TransactionRecord,
    val rawMessageCipher: ByteArray?,
    val createdAt: Long,
    val smsSources: List<TransactionAuditSmsSource> = emptyList(),
)

internal data class TransactionAuditSmsSource(
    val sourceMessageId: String,
    val receivedAt: Long,
)

internal object TransactionAuditPayloadCodec {
    fun encode(payload: TransactionAuditPayload): String = encode(payload, includeExtension = true)

    private fun encode(payload: TransactionAuditPayload, includeExtension: Boolean): String {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { data ->
            val item = payload.record
            data.writeLong(item.id)
            data.writeUTF(item.sourceMessageId)
            data.writeLong(item.amountMinor)
            data.writeUTF(item.merchant)
            data.writeNullableString(item.accountHint)
            data.writeUTF(item.category.name)
            data.writeUTF(item.type.name)
            data.writeLong(item.occurredAt)
            data.writeUTF(item.source.name)
            data.writeUTF(item.sender)
            data.writeNullableString(item.note)
            data.writeNullableLong(item.accountId)
            data.writeNullableLong(item.customCategoryId)
            data.writeInt(item.tags.size)
            item.tags.forEach(data::writeUTF)
            data.writeUTF(item.reviewStatus.name)
            data.writeNullableString(item.reviewReason)
            data.writeNullableLong(item.originalAmountMinor)
            data.writeNullableString(item.originalCurrency)
            data.writeNullableDouble(item.exchangeRate)
            data.writeNullableBytes(payload.rawMessageCipher)
            data.writeLong(payload.createdAt)
            if (includeExtension) {
                // Keep extensions trailing so audit payloads written by older releases remain
                // readable. These fields are required to faithfully restore a consolidated SMS
                // transaction after a delete/undo cycle.
                data.writeInt(item.duplicateCount.coerceAtLeast(1))
                data.writeNullableString(item.dedupeFingerprint)
                require(payload.smsSources.size <= MAX_SMS_SOURCES) {
                    "Audit payload contains too many SMS sources"
                }
                data.writeInt(payload.smsSources.size)
                payload.smsSources.forEach { source ->
                    data.writeUTF(source.sourceMessageId)
                    data.writeLong(source.receivedAt)
                }
            }
        }
        return Base64.getEncoder().encodeToString(bytes.toByteArray())
    }

    fun decode(value: String): TransactionAuditPayload = decodeWithMetadata(value).payload

    fun hasExtension(value: String): Boolean = decodeWithMetadata(value).hasExtension

    /**
     * Audit conflict detection historically compared the encoded payload verbatim. Once new
     * trailing fields were added, that would make every still-valid legacy audit event appear
     * stale. A legacy expected payload therefore compares only the state it was capable of
     * recording; extended payloads continue to compare every field strictly.
     */
    fun equivalentForUndo(expectedValue: String, currentValue: String): Boolean {
        val expected = decodeWithMetadata(expectedValue)
        val current = decodeWithMetadata(currentValue)
        if (!legacyFieldsEqual(expected.payload, current.payload)) return false
        if (expected.hasExtension) return extendedFieldsEqual(expected.payload, current.payload)
        // A legacy payload cannot prove whether extra SMS alerts were attached after the event.
        // Only accept the unchanged single-alert shape; otherwise undo could erase newer data.
        return current.payload.record.duplicateCount == 1 &&
            current.payload.smsSources.size <= 1 &&
            current.payload.smsSources.all {
                it.sourceMessageId == current.payload.record.sourceMessageId
            }
    }

    private fun decodeWithMetadata(value: String): DecodedPayload = DataInputStream(
        ByteArrayInputStream(Base64.getDecoder().decode(value)),
    ).use { data ->
        val record = TransactionRecord(
            id = data.readLong(),
            sourceMessageId = data.readUTF(),
            amountMinor = data.readLong(),
            merchant = data.readUTF(),
            accountHint = data.readNullableString(),
            category = data.readEnum(ExpenseCategory.OTHER),
            type = data.readEnum(TransactionType.EXPENSE),
            occurredAt = data.readLong(),
            source = data.readEnum(TransactionSource.BANK),
            sender = data.readUTF(),
            note = data.readNullableString(),
            accountId = data.readNullableLong(),
            customCategoryId = data.readNullableLong(),
            tags = List(data.readCount(20)) { data.readUTF() },
            reviewStatus = data.readEnum(ReviewStatus.CONFIRMED),
            reviewReason = data.readNullableString(),
            originalAmountMinor = data.readNullableLong(),
            originalCurrency = data.readNullableString(),
            exchangeRate = data.readNullableDouble(),
        )
        val rawMessageCipher = data.readNullableBytes()
        val createdAt = data.readLong()
        val hasExtension = data.available() > 0
        val duplicateCount = if (data.available() > 0) data.readInt().coerceAtLeast(1) else 1
        val dedupeFingerprint = if (data.available() > 0) data.readNullableString() else null
        val smsSources = if (data.available() > 0) {
            List(data.readCount(MAX_SMS_SOURCES)) {
                TransactionAuditSmsSource(
                    sourceMessageId = data.readUTF(),
                    receivedAt = data.readLong(),
                )
            }
        } else {
            emptyList()
        }
        require(data.available() == 0) { "Audit payload contains trailing data" }
        DecodedPayload(
            payload = TransactionAuditPayload(
                record = record.copy(
                    duplicateCount = duplicateCount,
                    dedupeFingerprint = dedupeFingerprint,
                ),
                rawMessageCipher = rawMessageCipher,
                createdAt = createdAt,
                smsSources = smsSources,
            ),
            hasExtension = hasExtension,
        )
    }

    fun portable(value: String?): String? = value?.let { encoded ->
        rewritePreservingFormat(encoded) { decoded -> decoded.copy(rawMessageCipher = null) }
    }

    fun rewritePreservingFormat(
        value: String,
        transform: (TransactionAuditPayload) -> TransactionAuditPayload,
    ): String {
        val decoded = decodeWithMetadata(value)
        return encode(transform(decoded.payload), includeExtension = decoded.hasExtension)
    }

    private fun DataOutputStream.writeNullableString(value: String?) {
        writeBoolean(value != null)
        if (value != null) writeUTF(value)
    }

    private fun DataOutputStream.writeNullableLong(value: Long?) {
        writeBoolean(value != null)
        if (value != null) writeLong(value)
    }

    private fun DataOutputStream.writeNullableDouble(value: Double?) {
        writeBoolean(value != null)
        if (value != null) writeDouble(value)
    }

    private fun DataOutputStream.writeNullableBytes(value: ByteArray?) {
        writeBoolean(value != null)
        if (value != null) {
            require(value.size <= MAX_CIPHER_BYTES) { "Encrypted SMS audit value is too large" }
            writeInt(value.size)
            write(value)
        }
    }

    private fun DataInputStream.readNullableString(): String? = if (readBoolean()) readUTF() else null

    private fun DataInputStream.readNullableLong(): Long? = if (readBoolean()) readLong() else null

    private fun DataInputStream.readNullableDouble(): Double? = if (readBoolean()) readDouble() else null

    private fun DataInputStream.readNullableBytes(): ByteArray? {
        if (!readBoolean()) return null
        val size = readCount(MAX_CIPHER_BYTES)
        return ByteArray(size).also(::readFully)
    }

    private fun DataInputStream.readCount(max: Int): Int = readInt().also {
        require(it in 0..max) { "Audit payload count is invalid" }
    }

    private inline fun <reified T : Enum<T>> DataInputStream.readEnum(default: T): T {
        val value = readUTF()
        return enumValues<T>().firstOrNull { it.name == value } ?: default
    }

    private fun legacyFieldsEqual(
        expected: TransactionAuditPayload,
        current: TransactionAuditPayload,
    ): Boolean = expected.record.copy(duplicateCount = 1, dedupeFingerprint = null) ==
        current.record.copy(duplicateCount = 1, dedupeFingerprint = null) &&
        expected.createdAt == current.createdAt &&
        byteArraysEqual(expected.rawMessageCipher, current.rawMessageCipher)

    private fun extendedFieldsEqual(
        expected: TransactionAuditPayload,
        current: TransactionAuditPayload,
    ): Boolean = expected.record.duplicateCount == current.record.duplicateCount &&
        expected.record.dedupeFingerprint == current.record.dedupeFingerprint &&
        expected.smsSources == current.smsSources

    private fun byteArraysEqual(first: ByteArray?, second: ByteArray?): Boolean = when {
        first == null -> second == null
        second == null -> false
        else -> first.contentEquals(second)
    }

    private data class DecodedPayload(
        val payload: TransactionAuditPayload,
        val hasExtension: Boolean,
    )

    private const val MAX_CIPHER_BYTES = 64 * 1024
    private const val MAX_SMS_SOURCES = 10_000
}
