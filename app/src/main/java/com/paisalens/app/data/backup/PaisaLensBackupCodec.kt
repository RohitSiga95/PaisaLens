package com.paisalens.app.data.backup

import com.paisalens.app.data.model.AccountProfile
import com.paisalens.app.data.model.AccountBalanceSnapshot
import com.paisalens.app.data.model.AccountType
import com.paisalens.app.data.model.AdvancedBudgetPlan
import com.paisalens.app.data.model.AuditAction
import com.paisalens.app.data.model.AuditEntityType
import com.paisalens.app.data.model.AuditEvent
import com.paisalens.app.data.model.BackupVerificationMetadata
import com.paisalens.app.data.model.BillReminder
import com.paisalens.app.data.model.BudgetCadence
import com.paisalens.app.data.model.BudgetPeriodAnchor
import com.paisalens.app.data.model.BudgetRolloverMode
import com.paisalens.app.data.model.CategoryBudget
import com.paisalens.app.data.model.CustomCategory
import com.paisalens.app.data.model.CreditCardBill
import com.paisalens.app.data.model.CreditCardBillStatus
import com.paisalens.app.data.model.ExpenseCategory
import com.paisalens.app.data.model.ExpenseSplit
import com.paisalens.app.data.model.ExpenseSplitEntryMode
import com.paisalens.app.data.model.ExpenseSplitStatus
import com.paisalens.app.data.model.LoanAccount
import com.paisalens.app.data.model.NetWorthItem
import com.paisalens.app.data.model.NetWorthKind
import com.paisalens.app.data.model.MerchantAliasRule
import com.paisalens.app.data.model.MerchantCategoryRule
import com.paisalens.app.data.model.MonthlyReconciliation
import com.paisalens.app.data.model.PaisaLensBackupSnapshot
import com.paisalens.app.data.model.PaymentCommitment
import com.paisalens.app.data.model.PaymentCommitmentKind
import com.paisalens.app.data.model.PaymentCommitmentSource
import com.paisalens.app.data.model.PaymentCommitmentStatus
import com.paisalens.app.data.model.PaymentFrequency
import com.paisalens.app.data.model.ReviewStatus
import com.paisalens.app.data.model.ReconciliationStatus
import com.paisalens.app.data.model.SmartCategoryRule
import com.paisalens.app.data.model.SmartRuleMatchType
import com.paisalens.app.data.model.SmsCoverageMessage
import com.paisalens.app.data.model.SmsCoverageRule
import com.paisalens.app.data.model.SavingsContribution
import com.paisalens.app.data.model.SavingsGoal
import com.paisalens.app.data.model.SavingsGoalKind
import com.paisalens.app.data.model.ContributionFrequency
import com.paisalens.app.data.model.TransactionRecord
import com.paisalens.app.data.model.TransactionAuditPayloadCodec
import com.paisalens.app.data.model.TransactionLink
import com.paisalens.app.data.model.TransactionLinkType
import com.paisalens.app.data.model.TransactionSource
import com.paisalens.app.data.model.TransactionType
import com.paisalens.app.data.model.TransactionSmsSource
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.security.SecureRandom
import java.security.MessageDigest
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.CipherOutputStream
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

object PaisaLensBackupCodec {
    fun write(snapshot: PaisaLensBackupSnapshot, passphrase: CharArray, output: OutputStream) {
        writeVersion(snapshot, passphrase, output, FORMAT_VERSION)
    }

    internal fun writeVersionForTesting(
        snapshot: PaisaLensBackupSnapshot,
        passphrase: CharArray,
        output: OutputStream,
        formatVersion: Int,
    ) {
        writeVersion(snapshot, passphrase, output, formatVersion)
    }

    private fun writeVersion(
        snapshot: PaisaLensBackupSnapshot,
        passphrase: CharArray,
        output: OutputStream,
        formatVersion: Int,
    ) {
        require(passphrase.size >= MIN_PASSPHRASE_LENGTH) {
            "Backup passphrase must contain at least $MIN_PASSPHRASE_LENGTH characters"
        }
        require(formatVersion in 1..FORMAT_VERSION) { "Backup format version is invalid" }
        val salt = ByteArray(SALT_BYTES).also(SecureRandom()::nextBytes)
        val iv = ByteArray(IV_BYTES).also(SecureRandom()::nextBytes)
        val snapshotPayload = encodeSnapshot(snapshot, formatVersion)
        val plainPayload = if (formatVersion >= 5) {
            encodeVerifiedPayload(snapshotPayload, snapshot, formatVersion)
        } else {
            snapshotPayload
        }
        try {
            require(plainPayload.size <= MAX_PLAINTEXT_BYTES) { "Backup contains too much data" }
            val cipher = Cipher.getInstance(TRANSFORMATION).apply {
                init(Cipher.ENCRYPT_MODE, deriveKey(passphrase, salt), GCMParameterSpec(TAG_BITS, iv))
                updateAAD(aadForVersion(formatVersion))
            }
            val encryptedSize = cipher.getOutputSize(plainPayload.size)
            require(encryptedSize in 1..MAX_BACKUP_BYTES) { "Backup contains too much data" }
            val header = DataOutputStream(output).apply {
                write(MAGIC)
                writeInt(formatVersion)
                write(salt)
                write(iv)
                writeInt(encryptedSize)
                flush()
            }
            // Stream encryption directly to the destination so a maximum-size backup does
            // not require a third full-size ciphertext array on memory-constrained devices.
            CipherOutputStream(header, cipher).use { encrypted -> encrypted.write(plainPayload) }
        } finally {
            if (snapshotPayload !== plainPayload) snapshotPayload.fill(0)
            plainPayload.fill(0)
        }
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
        require(data.read() == -1) { "Backup contains unexpected trailing data" }
        val plain = try {
            Cipher.getInstance(TRANSFORMATION).run {
                init(Cipher.DECRYPT_MODE, deriveKey(passphrase, salt), GCMParameterSpec(TAG_BITS, iv))
                updateAAD(aadForVersion(formatVersion))
                doFinal(encrypted)
            }
        } catch (_: AEADBadTagException) {
            throw IllegalArgumentException("Incorrect passphrase or damaged backup")
        }
        return try {
            val (snapshotPayload, metadata) = if (formatVersion >= 5) {
                decodeVerifiedPayload(plain, formatVersion)
            } else {
                plain to null
            }
            try {
                decodeSnapshot(snapshotPayload, formatVersion).also { snapshot ->
                    metadata?.let { validateMetadata(it, snapshot) }
                }
            } finally {
                if (snapshotPayload !== plain) snapshotPayload.fill(0)
            }
        } finally {
            plain.fill(0)
        }
    }

    fun verify(passphrase: CharArray, input: InputStream): BackupVerificationMetadata {
        val bytes = input.readBounded(MAX_BACKUP_BYTES + MAX_HEADER_BYTES)
        val snapshot = read(passphrase, ByteArrayInputStream(bytes))
        val formatVersion = DataInputStream(ByteArrayInputStream(bytes)).use { data ->
            val magic = ByteArray(MAGIC.size).also(data::readFully)
            require(magic.contentEquals(MAGIC)) { "This is not a PaisaLens backup" }
            data.readInt()
        }
        // read() authenticates/decrypts the file and validates embedded v5 metadata. The
        // returned digest is a stable checksum of the logical snapshot for legacy backups.
        val payload = encodeSnapshot(snapshot, formatVersion)
        return buildMetadata(snapshot, payload, formatVersion).also { payload.fill(0) }
    }

    private fun encodeSnapshot(snapshot: PaisaLensBackupSnapshot, formatVersion: Int): ByteArray {
        val bytes = BoundedByteArrayOutputStream(MAX_PLAINTEXT_BYTES)
        DataOutputStream(bytes).use { data ->
            data.writeLong(snapshot.createdAt)
            data.writeInt(snapshot.accounts.size)
            snapshot.accounts.forEach { account ->
                data.writeLong(account.id)
                data.writeUTF(account.name)
                data.writeUTF(account.type.name)
                data.writeNullable(account.accountHint)
                data.writeNullable(account.institution)
                if (formatVersion >= 3) {
                    data.writeNullableLong(account.balanceMinor)
                    data.writeNullableLong(account.availableCreditMinor)
                    if (formatVersion >= 4) data.writeNullableLong(account.creditLimitMinor)
                    data.writeNullableLong(account.availabilityFetchedAt)
                    data.writeNullable(account.availabilitySender)
                    if (formatVersion >= 5) data.writeNullable(account.identityKey)
                    if (formatVersion >= 8) {
                        data.writeNullableLong(account.mergedIntoAccountId)
                        data.writeInt(account.mergedMemberCount.coerceAtLeast(1))
                    }
                }
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
            if (formatVersion >= 2) {
                data.writeInt(snapshot.merchantAliases.size)
                snapshot.merchantAliases.forEach { rule ->
                    data.writeUTF(rule.aliasKey)
                    data.writeUTF(rule.aliasName)
                    data.writeUTF(rule.canonicalName)
                    data.writeLong(rule.updatedAt)
                }
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
                if (formatVersion >= 2) {
                    data.writeNullableLong(transaction.originalAmountMinor)
                    data.writeNullable(transaction.originalCurrency)
                    data.writeNullableDouble(transaction.exchangeRate)
                }
                if (formatVersion >= 7) data.writeInt(transaction.duplicateCount.coerceAtLeast(1))
                if (formatVersion >= 9) data.writeNullable(transaction.dedupeFingerprint)
            }
            if (formatVersion >= 2) {
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
            if (formatVersion >= 4) {
                data.writeInt(snapshot.balanceHistory.size)
                snapshot.balanceHistory.forEach { point ->
                data.writeLong(point.id)
                data.writeLong(point.accountId)
                data.writeNullableLong(point.balanceMinor)
                data.writeNullableLong(point.availableCreditMinor)
                data.writeNullableLong(point.creditLimitMinor)
                data.writeLong(point.recordedAt)
                data.writeNullable(point.sender)
                }
                data.writeInt(snapshot.bills.size)
                snapshot.bills.forEach { bill ->
                data.writeLong(bill.id)
                data.writeUTF(bill.title)
                data.writeLong(bill.amountMinor)
                data.writeLong(bill.dueDateEpochDay)
                data.writeInt(bill.recurrenceMonths)
                data.writeNullableLong(bill.accountId)
                data.writeNullable(bill.notes)
                data.writeBoolean(bill.isActive)
                data.writeNullableLong(bill.lastPaidEpochDay)
                }
                data.writeInt(snapshot.netWorthItems.size)
                snapshot.netWorthItems.forEach { item ->
                data.writeLong(item.id)
                data.writeUTF(item.name)
                data.writeUTF(item.kind.name)
                data.writeLong(item.valueMinor)
                data.writeUTF(item.category)
                data.writeLong(item.updatedAt)
                }
                data.writeInt(snapshot.smartCategoryRules.size)
                snapshot.smartCategoryRules.forEach { rule ->
                data.writeLong(rule.id)
                data.writeUTF(rule.name)
                data.writeUTF(rule.merchantPattern)
                data.writeUTF(rule.matchType.name)
                data.writeNullableLong(rule.minAmountMinor)
                data.writeNullableLong(rule.maxAmountMinor)
                data.writeNullableLong(rule.accountId)
                data.writeUTF(rule.category.name)
                data.writeNullableLong(rule.customCategoryId)
                data.writeBoolean(rule.enabled)
                data.writeInt(rule.priority)
                    data.writeLong(rule.updatedAt)
                }
            }
            if (formatVersion >= 5) {
                data.writeInt(snapshot.reconciliations.size)
                snapshot.reconciliations.forEach { reconciliation ->
                data.writeLong(reconciliation.id)
                data.writeLong(reconciliation.accountId)
                data.writeInt(reconciliation.year)
                data.writeInt(reconciliation.month)
                data.writeNullableLong(reconciliation.openingBalanceMinor)
                data.writeNullableLong(reconciliation.closingBalanceMinor)
                data.writeInt(reconciliation.statementTransactionCount)
                data.writeInt(reconciliation.matchedTransactionCount)
                data.writeInt(reconciliation.unmatchedStatementCount)
                data.writeInt(reconciliation.unmatchedAppCount)
                data.writeUTF(reconciliation.status.name)
                data.writeNullable(reconciliation.notes)
                data.writeNullableLong(reconciliation.reconciledAt)
                data.writeLong(reconciliation.updatedAt)
                }
                data.writeInt(snapshot.transactionLinks.size)
                snapshot.transactionLinks.forEach { link ->
                data.writeLong(link.id)
                data.writeLong(link.sourceTransactionId)
                data.writeLong(link.targetTransactionId)
                data.writeUTF(link.type.name)
                data.writeNullable(link.note)
                data.writeLong(link.createdAt)
                }
            data.writeInt(snapshot.auditEvents.size)
            snapshot.auditEvents.forEach { event ->
                data.writeLong(event.id)
                data.writeUTF(event.batchId)
                data.writeUTF(event.batchLabel)
                data.writeUTF(event.entityType.name)
                data.writeUTF(event.entityId)
                data.writeUTF(event.action.name)
                data.writeNullable(
                    if (event.entityType == AuditEntityType.TRANSACTION) {
                        TransactionAuditPayloadCodec.portable(event.beforePayload)
                    } else {
                        event.beforePayload
                    },
                )
                data.writeNullable(
                    if (event.entityType == AuditEntityType.TRANSACTION) {
                        TransactionAuditPayloadCodec.portable(event.afterPayload)
                    } else {
                        event.afterPayload
                    },
                )
                data.writeLong(event.occurredAt)
                    data.writeNullableLong(event.reversesEventId)
                }
            }
            if (formatVersion >= 6) {
                data.writeInt(snapshot.expenseSplits.size)
                snapshot.expenseSplits.forEach { split ->
                    data.writeLong(split.id)
                    data.writeLong(split.transactionId)
                    data.writeUTF(split.participantName)
                    data.writeLong(split.shareMinor)
                    data.writeLong(split.reimbursedMinor)
                    data.writeNullableLong(split.linkedIncomingTransactionId)
                    data.writeNullable(split.note)
                    data.writeUTF(split.status.name)
                    data.writeLong(split.createdAt)
                    data.writeLong(split.updatedAt)
                    if (formatVersion >= 7) {
                        data.writeUTF(split.entryMode.name)
                        data.writeNullableInt(split.shareBasisPoints)
                    }
                }
                data.writeInt(snapshot.savingsGoals.size)
                snapshot.savingsGoals.forEach { goal ->
                    data.writeLong(goal.id)
                    data.writeUTF(goal.name)
                    data.writeLong(goal.targetMinor)
                    data.writeLong(goal.startingSavedMinor)
                    data.writeNullableLong(goal.targetDateEpochDay)
                    data.writeNullableLong(goal.linkedAccountId)
                    data.writeUTF(goal.kind.name)
                    data.writeUTF(goal.contributionFrequency.name)
                    data.writeNullable(goal.notes)
                    data.writeUTF(goal.colorHex)
                    data.writeBoolean(goal.isActive)
                    data.writeLong(goal.createdAt)
                    data.writeLong(goal.updatedAt)
                }
                data.writeInt(snapshot.savingsContributions.size)
                snapshot.savingsContributions.forEach { contribution ->
                    data.writeLong(contribution.id)
                    data.writeLong(contribution.goalId)
                    data.writeLong(contribution.amountMinor)
                    data.writeLong(contribution.contributedAt)
                    data.writeNullable(contribution.note)
                    data.writeNullableLong(contribution.linkedTransactionId)
                }
                data.writeInt(snapshot.paymentCommitments.size)
                snapshot.paymentCommitments.forEach { commitment ->
                    data.writeLong(commitment.id)
                    data.writeUTF(commitment.name)
                    data.writeUTF(commitment.merchantKey)
                    data.writeUTF(commitment.kind.name)
                    data.writeUTF(commitment.frequency.name)
                    data.writeNullableLong(commitment.customIntervalDays?.toLong())
                    data.writeLong(commitment.amountMinor)
                    data.writeNullableLong(commitment.maxMandateMinor)
                    data.writeLong(commitment.nextDueEpochDay)
                    data.writeNullableLong(commitment.accountId)
                    data.writeNullable(commitment.upiHandle)
                    data.writeUTF(commitment.status.name)
                    data.writeUTF(commitment.source.name)
                    data.writeNullable(commitment.categoryLabel)
                    data.writeNullable(commitment.notes)
                    data.writeLong(commitment.createdAt)
                    data.writeLong(commitment.updatedAt)
                }
            }
            if (formatVersion >= 7) {
                data.writeInt(snapshot.transactionSmsSources.size)
                snapshot.transactionSmsSources.forEach { source ->
                    data.writeUTF(source.sourceMessageId)
                    data.writeLong(source.transactionId)
                    data.writeLong(source.receivedAt)
                }
                // Coverage Centre messages deliberately remain device-local. In particular,
                // unresolved raw SMS text must never enter even an encrypted export/backup.
                // Keep a zero-sized slot in v7 so the remainder of the format stays stable.
                data.writeInt(0)
                data.writeInt(snapshot.smsCoverageRules.size)
                snapshot.smsCoverageRules.forEach { rule ->
                    data.writeLong(rule.id)
                    data.writeUTF(rule.name)
                    data.writeUTF(rule.senderKey)
                    data.writeInt(rule.requiredPhrases.size)
                    rule.requiredPhrases.forEach(data::writeUTF)
                    data.writeUTF(rule.merchantName)
                    data.writeUTF(rule.category.name)
                    data.writeUTF(rule.type.name)
                    data.writeUTF(rule.source.name)
                    data.writeBoolean(rule.enabled)
                    data.writeLong(rule.createdAt)
                    data.writeLong(rule.updatedAt)
                }
                data.writeInt(snapshot.advancedBudgets.size)
                snapshot.advancedBudgets.forEach { plan ->
                    data.writeLong(plan.id)
                    data.writeUTF(plan.name)
                    data.writeNullable(plan.category?.name)
                    data.writeNullableLong(plan.customCategoryId)
                    data.writeLong(plan.allocationMinor)
                    data.writeUTF(plan.cadence.name)
                    data.writeUTF(plan.periodAnchor.name)
                    data.writeInt(plan.paydayDay)
                    data.writeInt(plan.annualStartMonth)
                    data.writeNullableLong(plan.irregularStartEpochDay)
                    data.writeNullableLong(plan.irregularEndEpochDay)
                    data.writeUTF(plan.rolloverMode.name)
                    data.writeInt(plan.warningThresholdBasisPoints)
                    data.writeLong(plan.startingRolloverMinor)
                    data.writeLong(plan.effectiveFromEpochDay)
                    data.writeBoolean(plan.enabled)
                    data.writeLong(plan.createdAt)
                    data.writeLong(plan.updatedAt)
                }
                data.writeInt(snapshot.creditCardBills.size)
                snapshot.creditCardBills.forEach { bill ->
                    data.writeLong(bill.id)
                    data.writeUTF(bill.billKey)
                    data.writeUTF(bill.sourceMessageId)
                    data.writeNullableLong(bill.accountId)
                    data.writeUTF(bill.cardIdentityKey)
                    data.writeNullable(bill.accountHint)
                    data.writeUTF(bill.institutionName)
                    data.writeLong(bill.totalDueMinor)
                    data.writeNullableLong(bill.minimumDueMinor)
                    data.writeLong(bill.dueDateEpochDay)
                    data.writeLong(bill.detectedAt)
                    data.writeUTF(bill.sender)
                    data.writeUTF(bill.status.name)
                    data.writeNullableLong(bill.paidAt)
                }
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
                    creditLimitMinor = if (formatVersion >= 4) data.readNullableLong() else null,
                    availabilityFetchedAt = if (formatVersion >= 3) data.readNullableLong() else null,
                    availabilitySender = if (formatVersion >= 3) data.readNullable() else null,
                    identityKey = if (formatVersion >= 5) data.readNullable() else null,
                    mergedIntoAccountId = if (formatVersion >= 8) data.readNullableLong() else null,
                    mergedMemberCount = if (formatVersion >= 8) data.readSafeCount().coerceAtLeast(1) else 1,
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
                    duplicateCount = if (formatVersion >= 7) data.readInt().coerceAtLeast(1) else 1,
                    dedupeFingerprint = if (formatVersion >= 9) data.readNullable() else null,
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
            val balanceHistory = if (formatVersion >= 4) {
                List(data.readSafeCount()) {
                    AccountBalanceSnapshot(
                        id = data.readLong(),
                        accountId = data.readLong(),
                        balanceMinor = data.readNullableLong(),
                        availableCreditMinor = data.readNullableLong(),
                        creditLimitMinor = data.readNullableLong(),
                        recordedAt = data.readLong(),
                        sender = data.readNullable(),
                    )
                }
            } else {
                emptyList()
            }
            val bills = if (formatVersion >= 4) {
                List(data.readSafeCount()) {
                    BillReminder(
                        id = data.readLong(),
                        title = data.readUTF(),
                        amountMinor = data.readLong(),
                        dueDateEpochDay = data.readLong(),
                        recurrenceMonths = data.readInt(),
                        accountId = data.readNullableLong(),
                        notes = data.readNullable(),
                        isActive = data.readBoolean(),
                        lastPaidEpochDay = data.readNullableLong(),
                    )
                }
            } else {
                emptyList()
            }
            val netWorthItems = if (formatVersion >= 4) {
                List(data.readSafeCount()) {
                    NetWorthItem(
                        id = data.readLong(),
                        name = data.readUTF(),
                        kind = data.readEnum(NetWorthKind.ASSET),
                        valueMinor = data.readLong(),
                        category = data.readUTF(),
                        updatedAt = data.readLong(),
                    )
                }
            } else {
                emptyList()
            }
            val smartCategoryRules = if (formatVersion >= 4) {
                List(data.readSafeCount()) {
                    SmartCategoryRule(
                        id = data.readLong(),
                        name = data.readUTF(),
                        merchantPattern = data.readUTF(),
                        matchType = data.readEnum(SmartRuleMatchType.CONTAINS),
                        minAmountMinor = data.readNullableLong(),
                        maxAmountMinor = data.readNullableLong(),
                        accountId = data.readNullableLong(),
                        category = data.readEnum(ExpenseCategory.OTHER),
                        customCategoryId = data.readNullableLong(),
                        enabled = data.readBoolean(),
                        priority = data.readInt(),
                        updatedAt = data.readLong(),
                    )
                }
            } else {
                emptyList()
            }
            val reconciliations = if (formatVersion >= 5) {
                List(data.readSafeCount()) {
                    MonthlyReconciliation(
                        id = data.readLong(),
                        accountId = data.readLong(),
                        year = data.readInt(),
                        month = data.readInt(),
                        openingBalanceMinor = data.readNullableLong(),
                        closingBalanceMinor = data.readNullableLong(),
                        statementTransactionCount = data.readInt(),
                        matchedTransactionCount = data.readInt(),
                        unmatchedStatementCount = data.readInt(),
                        unmatchedAppCount = data.readInt(),
                        status = data.readEnum(ReconciliationStatus.DRAFT),
                        notes = data.readNullable(),
                        reconciledAt = data.readNullableLong(),
                        updatedAt = data.readLong(),
                    )
                }
            } else {
                emptyList()
            }
            val transactionLinks = if (formatVersion >= 5) {
                List(data.readSafeCount()) {
                    TransactionLink(
                        id = data.readLong(),
                        sourceTransactionId = data.readLong(),
                        targetTransactionId = data.readLong(),
                        type = data.readEnum(TransactionLinkType.TRANSFER),
                        note = data.readNullable(),
                        createdAt = data.readLong(),
                    )
                }
            } else {
                emptyList()
            }
            val auditEvents = if (formatVersion >= 5) {
                List(data.readSafeCount()) {
                    AuditEvent(
                        id = data.readLong(),
                        batchId = data.readUTF(),
                        batchLabel = data.readUTF(),
                        entityType = data.readEnum(AuditEntityType.TRANSACTION),
                        entityId = data.readUTF(),
                        action = data.readEnum(AuditAction.UPDATE),
                        beforePayload = data.readNullable(),
                        afterPayload = data.readNullable(),
                        occurredAt = data.readLong(),
                        reversesEventId = data.readNullableLong(),
                    )
                }
            } else {
                emptyList()
            }
            val expenseSplits = if (formatVersion >= 6) {
                List(data.readSafeCount()) {
                    ExpenseSplit(
                        id = data.readLong(),
                        transactionId = data.readLong(),
                        participantName = data.readUTF(),
                        shareMinor = data.readLong(),
                        reimbursedMinor = data.readLong(),
                        linkedIncomingTransactionId = data.readNullableLong(),
                        note = data.readNullable(),
                        status = data.readEnum(ExpenseSplitStatus.OPEN),
                        createdAt = data.readLong(),
                        updatedAt = data.readLong(),
                        entryMode = if (formatVersion >= 7) {
                            data.readEnum(ExpenseSplitEntryMode.AMOUNT)
                        } else {
                            ExpenseSplitEntryMode.AMOUNT
                        },
                        shareBasisPoints = if (formatVersion >= 7) data.readNullableInt() else null,
                    )
                }
            } else {
                emptyList()
            }
            val savingsGoals = if (formatVersion >= 6) {
                List(data.readSafeCount()) {
                    SavingsGoal(
                        id = data.readLong(),
                        name = data.readUTF(),
                        targetMinor = data.readLong(),
                        startingSavedMinor = data.readLong(),
                        targetDateEpochDay = data.readNullableLong(),
                        linkedAccountId = data.readNullableLong(),
                        kind = data.readEnum(SavingsGoalKind.SAVINGS_GOAL),
                        contributionFrequency = data.readEnum(ContributionFrequency.MONTHLY),
                        notes = data.readNullable(),
                        colorHex = data.readUTF(),
                        isActive = data.readBoolean(),
                        createdAt = data.readLong(),
                        updatedAt = data.readLong(),
                    )
                }
            } else {
                emptyList()
            }
            val savingsContributions = if (formatVersion >= 6) {
                List(data.readSafeCount()) {
                    SavingsContribution(
                        id = data.readLong(),
                        goalId = data.readLong(),
                        amountMinor = data.readLong(),
                        contributedAt = data.readLong(),
                        note = data.readNullable(),
                        linkedTransactionId = data.readNullableLong(),
                    )
                }
            } else {
                emptyList()
            }
            val paymentCommitments = if (formatVersion >= 6) {
                List(data.readSafeCount()) {
                    PaymentCommitment(
                        id = data.readLong(),
                        name = data.readUTF(),
                        merchantKey = data.readUTF(),
                        kind = data.readEnum(PaymentCommitmentKind.SUBSCRIPTION),
                        frequency = data.readEnum(PaymentFrequency.MONTHLY),
                        customIntervalDays = data.readNullableLong()?.toInt(),
                        amountMinor = data.readLong(),
                        maxMandateMinor = data.readNullableLong(),
                        nextDueEpochDay = data.readLong(),
                        accountId = data.readNullableLong(),
                        upiHandle = data.readNullable(),
                        status = data.readEnum(PaymentCommitmentStatus.ACTIVE),
                        source = data.readEnum(PaymentCommitmentSource.MANUAL),
                        categoryLabel = data.readNullable(),
                        notes = data.readNullable(),
                        createdAt = data.readLong(),
                        updatedAt = data.readLong(),
                    )
                }
            } else {
                emptyList()
            }
            val transactionSmsSources = if (formatVersion >= 7) {
                List(data.readSafeCount()) {
                    TransactionSmsSource(
                        sourceMessageId = data.readUTF(),
                        transactionId = data.readLong(),
                        receivedAt = data.readLong(),
                    )
                }
            } else {
                emptyList()
            }
            val smsCoverageMessages: List<SmsCoverageMessage> = if (formatVersion >= 7) {
                require(data.readSafeCount() == 0) {
                    "Backup contains private unresolved SMS text"
                }
                emptyList()
            } else {
                emptyList()
            }
            val smsCoverageRules = if (formatVersion >= 7) {
                List(data.readSafeCount()) {
                    SmsCoverageRule(
                        id = data.readLong(),
                        name = data.readUTF(),
                        senderKey = data.readUTF(),
                        requiredPhrases = List(data.readSafeCount(max = 6)) { data.readUTF() },
                        merchantName = data.readUTF(),
                        category = data.readEnum(ExpenseCategory.OTHER),
                        type = data.readEnum(TransactionType.EXPENSE),
                        source = data.readEnum(TransactionSource.BANK),
                        enabled = data.readBoolean(),
                        createdAt = data.readLong(),
                        updatedAt = data.readLong(),
                    )
                }
            } else {
                emptyList()
            }
            val advancedBudgets = if (formatVersion >= 7) {
                List(data.readSafeCount()) {
                    AdvancedBudgetPlan(
                        id = data.readLong(),
                        name = data.readUTF(),
                        category = data.readNullable()?.let { value ->
                            enumValues<ExpenseCategory>().firstOrNull { it.name == value } ?: ExpenseCategory.OTHER
                        },
                        customCategoryId = data.readNullableLong(),
                        allocationMinor = data.readLong(),
                        cadence = data.readEnum(BudgetCadence.MONTHLY),
                        periodAnchor = data.readEnum(BudgetPeriodAnchor.CALENDAR_MONTH),
                        paydayDay = data.readInt(),
                        annualStartMonth = data.readInt(),
                        irregularStartEpochDay = data.readNullableLong(),
                        irregularEndEpochDay = data.readNullableLong(),
                        rolloverMode = data.readEnum(BudgetRolloverMode.NONE),
                        warningThresholdBasisPoints = data.readInt(),
                        startingRolloverMinor = data.readLong(),
                        effectiveFromEpochDay = data.readLong(),
                        enabled = data.readBoolean(),
                        createdAt = data.readLong(),
                        updatedAt = data.readLong(),
                    )
                }
            } else {
                emptyList()
            }
            val creditCardBills = if (formatVersion >= 7) {
                List(data.readSafeCount()) {
                    CreditCardBill(
                        id = data.readLong(),
                        billKey = data.readUTF(),
                        sourceMessageId = data.readUTF(),
                        accountId = data.readNullableLong(),
                        cardIdentityKey = data.readUTF(),
                        accountHint = data.readNullable(),
                        institutionName = data.readUTF(),
                        totalDueMinor = data.readLong(),
                        minimumDueMinor = data.readNullableLong(),
                        dueDateEpochDay = data.readLong(),
                        detectedAt = data.readLong(),
                        sender = data.readUTF(),
                        status = data.readEnum(CreditCardBillStatus.DUE),
                        paidAt = data.readNullableLong(),
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
                balanceHistory = balanceHistory,
                bills = bills,
                netWorthItems = netWorthItems,
                smartCategoryRules = smartCategoryRules,
                reconciliations = reconciliations,
                transactionLinks = transactionLinks,
                auditEvents = auditEvents,
                expenseSplits = expenseSplits,
                savingsGoals = savingsGoals,
                savingsContributions = savingsContributions,
                paymentCommitments = paymentCommitments,
                transactionSmsSources = transactionSmsSources,
                smsCoverageMessages = smsCoverageMessages,
                smsCoverageRules = smsCoverageRules,
                advancedBudgets = advancedBudgets,
                creditCardBills = creditCardBills,
            )
        }
    }

    private fun encodeVerifiedPayload(
        snapshotPayload: ByteArray,
        snapshot: PaisaLensBackupSnapshot,
        formatVersion: Int,
    ): ByteArray {
        val metadata = buildMetadata(snapshot, snapshotPayload, formatVersion)
        val bytes = BoundedByteArrayOutputStream(MAX_PLAINTEXT_BYTES)
        DataOutputStream(bytes).use { data ->
            data.writeInt(snapshotPayload.size)
            data.write(snapshotPayload)
            data.writeInt(metadata.formatVersion)
            data.writeLong(metadata.createdAt)
            data.writeInt(metadata.transactionCount)
            data.writeInt(metadata.accountCount)
            data.writeInt(metadata.reconciliationCount)
            data.writeInt(metadata.transactionLinkCount)
            data.writeInt(metadata.auditEventCount)
            data.writeInt(metadata.budgetCount)
            data.writeInt(metadata.customCategoryCount)
            data.writeInt(metadata.merchantRuleCount)
            data.writeInt(metadata.merchantAliasCount)
            data.writeInt(metadata.loanCount)
            data.writeInt(metadata.balanceHistoryCount)
            data.writeInt(metadata.billCount)
            data.writeInt(metadata.netWorthItemCount)
            data.writeInt(metadata.smartCategoryRuleCount)
            if (formatVersion >= 6) {
                data.writeInt(metadata.expenseSplitCount)
                data.writeInt(metadata.savingsGoalCount)
                data.writeInt(metadata.savingsContributionCount)
                data.writeInt(metadata.paymentCommitmentCount)
            }
            if (formatVersion >= 7) {
                data.writeInt(metadata.transactionSmsSourceCount)
                data.writeInt(metadata.smsCoverageMessageCount)
                data.writeInt(metadata.smsCoverageRuleCount)
                data.writeInt(metadata.advancedBudgetCount)
                data.writeInt(metadata.creditCardBillCount)
            }
            data.writeUTF(metadata.contentSha256)
        }
        return bytes.toByteArray()
    }

    private fun decodeVerifiedPayload(
        payload: ByteArray,
        expectedFormatVersion: Int,
    ): Pair<ByteArray, BackupVerificationMetadata> = DataInputStream(ByteArrayInputStream(payload)).use { data ->
        val snapshotSize = data.readInt()
        require(snapshotSize in 1..MAX_BACKUP_BYTES && snapshotSize <= data.available()) {
            "Backup payload size is invalid"
        }
        val snapshotPayload = ByteArray(snapshotSize).also(data::readFully)
        val metadata = BackupVerificationMetadata(
            formatVersion = data.readInt(),
            createdAt = data.readLong(),
            transactionCount = data.readSafeCount(),
            accountCount = data.readSafeCount(),
            reconciliationCount = data.readSafeCount(),
            transactionLinkCount = data.readSafeCount(),
            auditEventCount = data.readSafeCount(),
            budgetCount = data.readSafeCount(),
            customCategoryCount = data.readSafeCount(),
            merchantRuleCount = data.readSafeCount(),
            merchantAliasCount = data.readSafeCount(),
            loanCount = data.readSafeCount(),
            balanceHistoryCount = data.readSafeCount(),
            billCount = data.readSafeCount(),
            netWorthItemCount = data.readSafeCount(),
            smartCategoryRuleCount = data.readSafeCount(),
            expenseSplitCount = if (expectedFormatVersion >= 6) data.readSafeCount() else 0,
            savingsGoalCount = if (expectedFormatVersion >= 6) data.readSafeCount() else 0,
            savingsContributionCount = if (expectedFormatVersion >= 6) data.readSafeCount() else 0,
            paymentCommitmentCount = if (expectedFormatVersion >= 6) data.readSafeCount() else 0,
            transactionSmsSourceCount = if (expectedFormatVersion >= 7) data.readSafeCount() else 0,
            smsCoverageMessageCount = if (expectedFormatVersion >= 7) data.readSafeCount() else 0,
            smsCoverageRuleCount = if (expectedFormatVersion >= 7) data.readSafeCount() else 0,
            advancedBudgetCount = if (expectedFormatVersion >= 7) data.readSafeCount() else 0,
            creditCardBillCount = if (expectedFormatVersion >= 7) data.readSafeCount() else 0,
            contentSha256 = data.readUTF(),
        )
        require(data.available() == 0) { "Backup contains unexpected verification data" }
        require(metadata.formatVersion == expectedFormatVersion) { "Backup format metadata does not match its header" }
        val digest = sha256(snapshotPayload)
        require(
            MessageDigest.isEqual(
                digest.toByteArray(Charsets.US_ASCII),
                metadata.contentSha256.toByteArray(Charsets.US_ASCII),
            ),
        ) { "Backup content verification failed" }
        snapshotPayload to metadata
    }

    private fun validateMetadata(
        metadata: BackupVerificationMetadata,
        snapshot: PaisaLensBackupSnapshot,
    ) {
        require(metadata.createdAt == snapshot.createdAt) { "Backup creation date verification failed" }
        require(metadata.transactionCount == snapshot.transactions.size) { "Backup transaction count verification failed" }
        require(metadata.accountCount == snapshot.accounts.size) { "Backup account count verification failed" }
        require(metadata.reconciliationCount == snapshot.reconciliations.size) {
            "Backup reconciliation count verification failed"
        }
        require(metadata.transactionLinkCount == snapshot.transactionLinks.size) {
            "Backup transaction-link count verification failed"
        }
        require(metadata.auditEventCount == snapshot.auditEvents.size) { "Backup audit count verification failed" }
        require(metadata.budgetCount == snapshot.budgets.size) { "Backup budget count verification failed" }
        require(metadata.customCategoryCount == snapshot.customCategories.size) {
            "Backup custom-category count verification failed"
        }
        require(metadata.merchantRuleCount == snapshot.merchantRules.size) { "Backup merchant-rule count verification failed" }
        require(metadata.merchantAliasCount == snapshot.merchantAliases.size) { "Backup merchant-alias count verification failed" }
        require(metadata.loanCount == snapshot.loans.size) { "Backup loan count verification failed" }
        require(metadata.balanceHistoryCount == snapshot.balanceHistory.size) { "Backup balance-history count verification failed" }
        require(metadata.billCount == snapshot.bills.size) { "Backup bill count verification failed" }
        require(metadata.netWorthItemCount == snapshot.netWorthItems.size) { "Backup net-worth count verification failed" }
        require(metadata.smartCategoryRuleCount == snapshot.smartCategoryRules.size) {
            "Backup smart-category-rule count verification failed"
        }
        require(metadata.expenseSplitCount == snapshot.expenseSplits.size) { "Backup expense-split count verification failed" }
        require(metadata.savingsGoalCount == snapshot.savingsGoals.size) { "Backup savings-goal count verification failed" }
        require(metadata.savingsContributionCount == snapshot.savingsContributions.size) {
            "Backup savings-contribution count verification failed"
        }
        require(metadata.paymentCommitmentCount == snapshot.paymentCommitments.size) {
            "Backup payment-commitment count verification failed"
        }
        require(metadata.transactionSmsSourceCount == snapshot.transactionSmsSources.size) {
            "Backup transaction-SMS-source count verification failed"
        }
        require(metadata.smsCoverageMessageCount == snapshot.smsCoverageMessages.size) {
            "Backup SMS-coverage-message count verification failed"
        }
        require(metadata.smsCoverageRuleCount == snapshot.smsCoverageRules.size) {
            "Backup SMS-coverage-rule count verification failed"
        }
        require(metadata.advancedBudgetCount == snapshot.advancedBudgets.size) {
            "Backup advanced-budget count verification failed"
        }
        require(metadata.creditCardBillCount == snapshot.creditCardBills.size) {
            "Backup credit-card-bill count verification failed"
        }
    }

    private fun buildMetadata(
        snapshot: PaisaLensBackupSnapshot,
        snapshotPayload: ByteArray,
        formatVersion: Int,
    ): BackupVerificationMetadata = BackupVerificationMetadata(
        formatVersion = formatVersion,
        createdAt = snapshot.createdAt,
        transactionCount = snapshot.transactions.size,
        accountCount = snapshot.accounts.size,
        reconciliationCount = snapshot.reconciliations.size,
        transactionLinkCount = snapshot.transactionLinks.size,
        auditEventCount = snapshot.auditEvents.size,
        budgetCount = snapshot.budgets.size,
        customCategoryCount = snapshot.customCategories.size,
        merchantRuleCount = snapshot.merchantRules.size,
        merchantAliasCount = snapshot.merchantAliases.size,
        loanCount = snapshot.loans.size,
        balanceHistoryCount = snapshot.balanceHistory.size,
        billCount = snapshot.bills.size,
        netWorthItemCount = snapshot.netWorthItems.size,
        smartCategoryRuleCount = snapshot.smartCategoryRules.size,
        expenseSplitCount = if (formatVersion >= 6) snapshot.expenseSplits.size else 0,
        savingsGoalCount = if (formatVersion >= 6) snapshot.savingsGoals.size else 0,
        savingsContributionCount = if (formatVersion >= 6) snapshot.savingsContributions.size else 0,
        paymentCommitmentCount = if (formatVersion >= 6) snapshot.paymentCommitments.size else 0,
        transactionSmsSourceCount = if (formatVersion >= 7) snapshot.transactionSmsSources.size else 0,
        // Raw unresolved SMS text is intentionally outside the backup privacy contract.
        smsCoverageMessageCount = 0,
        smsCoverageRuleCount = if (formatVersion >= 7) snapshot.smsCoverageRules.size else 0,
        advancedBudgetCount = if (formatVersion >= 7) snapshot.advancedBudgets.size else 0,
        creditCardBillCount = if (formatVersion >= 7) snapshot.creditCardBills.size else 0,
        contentSha256 = sha256(snapshotPayload),
    )

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { byte -> (byte.toInt() and 0xff).toString(16).padStart(2, '0') }

    private fun aadForVersion(formatVersion: Int): ByteArray = if (formatVersion >= 5) {
        MAGIC + byteArrayOf(
            (formatVersion ushr 24).toByte(),
            (formatVersion ushr 16).toByte(),
            (formatVersion ushr 8).toByte(),
            formatVersion.toByte(),
        )
    } else {
        MAGIC
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

    private fun DataOutputStream.writeNullableInt(value: Int?) {
        writeBoolean(value != null)
        if (value != null) writeInt(value)
    }

    private fun DataOutputStream.writeNullableDouble(value: Double?) {
        writeBoolean(value != null)
        if (value != null) writeDouble(value)
    }

    private fun DataInputStream.readNullable(): String? = if (readBoolean()) readUTF() else null

    private fun DataInputStream.readNullableLong(): Long? = if (readBoolean()) readLong() else null

    private fun DataInputStream.readNullableInt(): Int? = if (readBoolean()) readInt() else null

    private fun DataInputStream.readNullableDouble(): Double? = if (readBoolean()) readDouble() else null

    private fun DataInputStream.readSafeCount(max: Int = MAX_RECORDS): Int =
        readInt().also { require(it in 0..max) { "Backup record count is invalid" } }

    private inline fun <reified T : Enum<T>> DataInputStream.readEnum(default: T): T {
        val value = readUTF()
        return enumValues<T>().firstOrNull { it.name == value } ?: default
    }

    private fun InputStream.readBounded(maxBytes: Int): ByteArray {
        val output = ByteArrayOutputStream(minOf(maxBytes, 64 * 1024))
        val buffer = ByteArray(16 * 1024)
        var total = 0
        while (true) {
            val count = read(buffer)
            if (count == -1) break
            total += count
            require(total <= maxBytes) { "Backup file is too large" }
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }

    private class BoundedByteArrayOutputStream(private val maxBytes: Int) : ByteArrayOutputStream() {
        override fun write(value: Int) {
            require(count < maxBytes) { "Backup contains too much data" }
            super.write(value)
        }

        override fun write(buffer: ByteArray, offset: Int, length: Int) {
            require(length >= 0 && count <= maxBytes - length) { "Backup contains too much data" }
            super.write(buffer, offset, length)
        }
    }

    private const val FORMAT_VERSION = 9
    private const val MIN_PASSPHRASE_LENGTH = 8
    private const val SALT_BYTES = 16
    private const val IV_BYTES = 12
    private const val TAG_BITS = 128
    private const val TAG_BYTES = TAG_BITS / 8
    private const val KEY_BITS = 256
    private const val PBKDF2_ITERATIONS = 180_000
    private const val MAX_BACKUP_BYTES = 64 * 1024 * 1024
    private const val MAX_PLAINTEXT_BYTES = MAX_BACKUP_BYTES - TAG_BYTES
    private const val MAX_HEADER_BYTES = 64
    private const val MAX_RECORDS = 200_000
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private val MAGIC = byteArrayOf(0x50, 0x4C, 0x42, 0x4B)
}
