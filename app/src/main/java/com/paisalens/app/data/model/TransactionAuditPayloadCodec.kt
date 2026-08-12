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
)

internal object TransactionAuditPayloadCodec {
    fun encode(payload: TransactionAuditPayload): String {
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
        }
        return Base64.getEncoder().encodeToString(bytes.toByteArray())
    }

    fun decode(value: String): TransactionAuditPayload = DataInputStream(
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
        TransactionAuditPayload(
            record = record,
            rawMessageCipher = data.readNullableBytes(),
            createdAt = data.readLong(),
        ).also { require(data.available() == 0) { "Audit payload contains trailing data" } }
    }

    fun portable(value: String?): String? = value?.let { encoded ->
        val decoded = decode(encoded)
        encode(decoded.copy(rawMessageCipher = null))
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

    private const val MAX_CIPHER_BYTES = 64 * 1024
}
