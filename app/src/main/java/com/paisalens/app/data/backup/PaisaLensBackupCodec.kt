package com.paisalens.app.data.backup

import com.paisalens.app.data.model.AccountProfile
import com.paisalens.app.data.model.AccountType
import com.paisalens.app.data.model.CategoryBudget
import com.paisalens.app.data.model.CustomCategory
import com.paisalens.app.data.model.ExpenseCategory
import com.paisalens.app.data.model.LoanAccount
import com.paisalens.app.data.model.MerchantAliasRule
import com.paisalens.app.data.model.MerchantCategoryRule
import com.paisalens.app.data.model.PaisaLensBackupSnapshot
import com.paisalens.app.data.model.ReviewStatus
import com.paisalens.app.data.model.TransactionRecord
import com.paisalens.app.data.model.TransactionSource
import com.paisalens.app.data.model.TransactionType
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.security.SecureRandom
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

object PaisaLensBackupCodec {
    fun write(snapshot: PaisaLensBackupSnapshot, passphrase: CharArray, output: OutputStream) {
        require(passphrase.size >= MIN_PASSPHRASE_LENGTH) {
            "Backup passphrase must contain at least $MIN_PASSPHRASE_LENGTH characters"
        }
        val salt = ByteArray(SALT_BYTES).also(SecureRandom()::nextBytes)
        val iv = ByteArray(IV_BYTES).also(SecureRandom()::nextBytes)
        val plainPayload = encodeSnapshot(snapshot)
        val cipher = Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.ENCRYPT_MODE, deriveKey(passphrase, salt), GCMParameterSpec(TAG_BITS, iv))
            updateAAD(MAGIC)
        }
        val encrypted = cipher.doFinal(plainPayload)
        DataOutputStream(output).use { data ->
            data.write(MAGIC)
            data.writeInt(FORMAT_VERSION)
            data.write(salt)
            data.write(iv)
            data.writeInt(encrypted.size)
            data.write(encrypted)
        }
        plainPayload.fill(0)
    }

    fun read(passphrase: CharArray, input: InputStream): PaisaLensBackupSnapshot {
        require(passphrase.size >= MIN_PASSPHRASE_LENGTH) {
            "Backup passphrase must contain at least $MIN_PASSPHRASE_LENGTH characters"
        }
        val data = DataInputStream(input)
        val magic = ByteArray(MAGIC.size).also(data::readFully)
        require(magic.contentEquals(MAGIC)) { "This is not a PaisaLens backup" }
        val formatVersion = data.readInt()
        require(formatVersion in 1..FORMAT_VERSION) { "This backup version is not supported" }
        val salt = ByteArray(SALT_BYTES).also(data::readFully)
        val iv = ByteArray(IV_BYTES).also(data::readFully)
        val encryptedSize = data.readInt()
        require(encryptedSize in 1..MAX_BACKUP_BYTES) { "Backup file size is invalid" }
        val encrypted = ByteArray(encryptedSize).also(data::readFully)
        val plain = try {
            Cipher.getInstance(TRANSFORMATION).run {
                init(Cipher.DECRYPT_MODE, deriveKey(passphrase, salt), GCMParameterSpec(TAG_BITS, iv))
                updateAAD(MAGIC)
                doFinal(encrypted)
            }
        } catch (_: AEADBadTagException) {
            throw IllegalArgumentException("Incorrect passphrase or damaged backup")
        }
        return try {
            decodeSnapshot(plain, formatVersion)
        } finally {
            plain.fill(0)
        }
    }

    private fun encodeSnapshot(snapshot: PaisaLensBackupSnapshot): ByteArray {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { data ->
            data.writeLong(snapshot.createdAt)
            data.writeInt(snapshot.accounts.size)
            snapshot.accounts.forEach { account ->
                data.writeLong(account.id)
                data.writeUTF(account.name)
                data.writeUTF(account.type.name)
                data.writeNullable(account.accountHint)
                data.writeNullable(account.institution)
                data.writeNullableLong(account.balanceMinor)
                data.writeNullableLong(account.availableCreditMinor)
                data.writeNullableLong(account.availabilityFetchedAt)
                data.writeNullable(account.availabilitySender)
            }
            data.writeInt(snapshot.customCategories.size)
            snapshot.customCategories.forEach { category ->
                data.writeLong(category.id)
                data.writeUTF(category.name)
                data.writeUTF(category.colorHex)
            }
            data.writeInt(snapshot.budgets.size)
            snapshot.budgets.forEach { budget ->
                data.writeUTF(budget.category.name)
                data.writeLong(budget.limitMinor)
            }
            data.writeInt(snapshot.merchantRules.size)
            snapshot.merchantRules.forEach { rule ->
                data.writeUTF(rule.merchantKey)
                data.writeUTF(rule.merchantName)
                data.writeUTF(rule.category.name)
                data.writeNullableLong(rule.customCategoryId)
            }
            data.writeInt(snapshot.merchantAliases.size)
            snapshot.merchantAliases.forEach { rule ->
                data.writeUTF(rule.aliasKey)
                data.writeUTF(rule.aliasName)
                data.writeUTF(rule.canonicalName)
                data.writeLong(rule.updatedAt)
            }
            data.writeInt(snapshot.transactions.size)
            snapshot.transactions.forEach { transaction ->
                data.writeLong(transaction.id)
                data.writeUTF(transaction.sourceMessageId)
                data.writeLong(transaction.amountMinor)
                data.writeUTF(transaction.merchant)
                data.writeNullable(transaction.accountHint)
                data.writeUTF(transaction.category.name)
                data.writeUTF(transaction.type.name)
                data.writeLong(transaction.occurredAt)
                data.writeUTF(transaction.source.name)
                data.writeUTF(transaction.sender)
                data.writeNullable(transaction.note)
                data.writeNullableLong(transaction.accountId)
                data.writeNullableLong(transaction.customCategoryId)
                data.writeInt(transaction.tags.size)
                transaction.tags.forEach(data::writeUTF)
                data.writeUTF(transaction.reviewStatus.name)
                data.writeNullable(transaction.reviewReason)
                data.writeNullableLong(transaction.originalAmountMinor)
                data.writeNullable(transaction.originalCurrency)
                data.writeNullableDouble(transaction.exchangeRate)
            }
            data.writeInt(snapshot.loans.size)
            snapshot.loans.forEach { loan ->
                data.writeLong(loan.id)
                data.writeUTF(loan.name)
                data.writeUTF(loan.lender)
                data.writeLong(loan.principalMinor)
                data.writeInt(loan.annualRateBasisPoints)
                data.writeInt(loan.tenureMonths)
                data.writeLong(loan.startDateEpochDay)
                data.writeLong(loan.emiMinor)
                data.writeInt(loan.paidInstallments)
                data.writeNullableLong(loan.accountId)
                data.writeNullable(loan.notes)
            }
        }
        return bytes.toByteArray()
    }

    private fun decodeSnapshot(payload: ByteArray, formatVersion: Int): PaisaLensBackupSnapshot {
        DataInputStream(ByteArrayInputStream(payload)).use { data ->
            val createdAt = data.readLong()
            val accounts = List(data.readSafeCount()) {
                AccountProfile(
                    id = data.readLong(),
                    name = data.readUTF(),
                    type = data.readEnum(AccountType.OTHER),
                    accountHint = data.readNullable(),
                    institution = data.readNullable(),
                    balanceMinor = if (formatVersion >= 3) data.readNullableLong() else null,
                    availableCreditMinor = if (formatVersion >= 3) data.readNullableLong() else null,
                    availabilityFetchedAt = if (formatVersion >= 3) data.readNullableLong() else null,
                    availabilitySender = if (formatVersion >= 3) data.readNullable() else null,
                )
            }
            val customCategories = List(data.readSafeCount()) {
                CustomCategory(
                    id = data.readLong(),
                    name = data.readUTF(),
                    colorHex = data.readUTF(),
                )
            }
            val budgets = List(data.readSafeCount()) {
                CategoryBudget(
                    category = data.readEnum(ExpenseCategory.OTHER),
                    limitMinor = data.readLong(),
                )
            }
            val merchantRules = List(data.readSafeCount()) {
                MerchantCategoryRule(
                    merchantKey = data.readUTF(),
                    merchantName = data.readUTF(),
                    category = data.readEnum(ExpenseCategory.OTHER),
                    customCategoryId = data.readNullableLong(),
                )
            }
            val merchantAliases = if (formatVersion >= 2) {
                List(data.readSafeCount()) {
                    MerchantAliasRule(
                        aliasKey = data.readUTF(),
                        aliasName = data.readUTF(),
                        canonicalName = data.readUTF(),
                        updatedAt = data.readLong(),
                    )
                }
            } else {
                emptyList()
            }
            val transactions = List(data.readSafeCount()) {
                TransactionRecord(
                    id = data.readLong(),
                    sourceMessageId = data.readUTF(),
                    amountMinor = data.readLong(),
                    merchant = data.readUTF(),
                    accountHint = data.readNullable(),
                    category = data.readEnum(ExpenseCategory.OTHER),
                    type = data.readEnum(TransactionType.EXPENSE),
                    occurredAt = data.readLong(),
                    source = data.readEnum(TransactionSource.BANK),
                    sender = data.readUTF(),
                    note = data.readNullable(),
                    accountId = data.readNullableLong(),
                    customCategoryId = data.readNullableLong(),
                    tags = List(data.readSafeCount(max = 20)) { data.readUTF() },
                    reviewStatus = data.readEnum(ReviewStatus.CONFIRMED),
                    reviewReason = data.readNullable(),
                    originalAmountMinor = if (formatVersion >= 2) data.readNullableLong() else null,
                    originalCurrency = if (formatVersion >= 2) data.readNullable() else null,
                    exchangeRate = if (formatVersion >= 2) data.readNullableDouble() else null,
                )
            }
            val loans = if (formatVersion >= 2) {
                List(data.readSafeCount()) {
                    LoanAccount(
                        id = data.readLong(),
                        name = data.readUTF(),
                        lender = data.readUTF(),
                        principalMinor = data.readLong(),
                        annualRateBasisPoints = data.readInt(),
                        tenureMonths = data.readInt(),
                        startDateEpochDay = data.readLong(),
                        emiMinor = data.readLong(),
                        paidInstallments = data.readInt(),
                        accountId = data.readNullableLong(),
                        notes = data.readNullable(),
                    )
                }
            } else {
                emptyList()
            }
            require(data.available() == 0) { "Backup contains unexpected trailing data" }
            return PaisaLensBackupSnapshot(
                createdAt = createdAt,
                transactions = transactions,
                budgets = budgets,
                accounts = accounts,
                customCategories = customCategories,
                merchantRules = merchantRules,
                merchantAliases = merchantAliases,
                loans = loans,
            )
        }
    }

    private fun deriveKey(passphrase: CharArray, salt: ByteArray): SecretKeySpec {
        val spec = PBEKeySpec(passphrase, salt, PBKDF2_ITERATIONS, KEY_BITS)
        return try {
            val encoded = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
            SecretKeySpec(encoded, "AES")
        } finally {
            spec.clearPassword()
        }
    }

    private fun DataOutputStream.writeNullable(value: String?) {
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

    private fun DataInputStream.readNullable(): String? = if (readBoolean()) readUTF() else null

    private fun DataInputStream.readNullableLong(): Long? = if (readBoolean()) readLong() else null

    private fun DataInputStream.readNullableDouble(): Double? = if (readBoolean()) readDouble() else null

    private fun DataInputStream.readSafeCount(max: Int = MAX_RECORDS): Int =
        readInt().also { require(it in 0..max) { "Backup record count is invalid" } }

    private inline fun <reified T : Enum<T>> DataInputStream.readEnum(default: T): T {
        val value = readUTF()
        return enumValues<T>().firstOrNull { it.name == value } ?: default
    }

    private const val FORMAT_VERSION = 3
    private const val MIN_PASSPHRASE_LENGTH = 8
    private const val SALT_BYTES = 16
    private const val IV_BYTES = 12
    private const val TAG_BITS = 128
    private const val KEY_BITS = 256
    private const val PBKDF2_ITERATIONS = 180_000
    private const val MAX_BACKUP_BYTES = 64 * 1024 * 1024
    private const val MAX_RECORDS = 200_000
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private val MAGIC = byteArrayOf(0x50, 0x4C, 0x42, 0x4B)
}
