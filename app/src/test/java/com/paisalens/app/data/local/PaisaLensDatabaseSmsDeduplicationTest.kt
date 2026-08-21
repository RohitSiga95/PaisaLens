package com.paisalens.app.data.local

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import com.paisalens.app.data.backup.PaisaLensBackupCodec
import com.paisalens.app.data.model.AuditAction
import com.paisalens.app.data.model.AuditEntityType
import com.paisalens.app.data.model.AuditEvent
import com.paisalens.app.data.model.CreditCardBill
import com.paisalens.app.data.model.ExpenseCategory
import com.paisalens.app.data.model.PaisaLensBackupSnapshot
import com.paisalens.app.data.model.ParsedTransaction
import com.paisalens.app.data.model.TransactionRecord
import com.paisalens.app.data.model.TransactionSource
import com.paisalens.app.data.model.TransactionSmsSource
import com.paisalens.app.data.model.TransactionType
import com.paisalens.app.data.model.TransactionAuditPayload
import com.paisalens.app.data.model.TransactionAuditPayloadCodec
import com.paisalens.app.data.model.TransactionAuditSmsSource
import com.paisalens.app.data.model.smsDuplicateFingerprint
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.Base64
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PaisaLensDatabaseSmsDeduplicationTest {
    private lateinit var context: Context
    private lateinit var database: PaisaLensDatabase

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        context.deleteDatabase(DATABASE_NAME)
        database = PaisaLensDatabase(context)
    }

    @After
    fun tearDown() {
        database.close()
        context.deleteDatabase(DATABASE_NAME)
    }

    @Test
    fun exactTimeInboxCopyCollapsesIntoLiveRowAndRescanIsIdempotent() {
        val live = transaction(LEGACY_LIVE_ID_A, BASE_TIME)
        val inbox = transaction("sms-101", BASE_TIME)

        assertEquals(1, database.insertAll(listOf(live to byteArrayOf(1))).inserted)
        val merged = database.insertAll(listOf(inbox to byteArrayOf(2)))

        assertEquals(0, merged.inserted)
        assertEquals(1, merged.duplicatesMerged)
        assertEquals(1, database.getTransactions().size)
        assertEquals(2, database.getTransactions().single().duplicateCount)
        assertEquals(
            setOf(LEGACY_LIVE_ID_A, "sms-101"),
            database.getTransactionSmsSources().mapTo(mutableSetOf()) { it.sourceMessageId },
        )

        val rescanned = database.insertAll(listOf(inbox to byteArrayOf(2)))

        assertEquals(0, rescanned.duplicatesMerged)
        assertEquals(1, rescanned.ignoredSourceMessages)
        assertEquals(2, database.getTransactions().single().duplicateCount)
    }

    @Test
    fun deleteUndoRestoresDuplicateIdentityAndAllSourcesWithoutCountInflation() {
        val live = transaction(LEGACY_LIVE_ID_A, BASE_TIME)
        val inbox = transaction("sms-141", BASE_TIME)
        database.insertAll(listOf(live to byteArrayOf(1)))
        database.insertAll(listOf(inbox to byteArrayOf(2)))
        val original = database.getTransactions().single()
        val originalSources = database.getTransactionSmsSources()
        assertEquals(2, original.duplicateCount)
        assertEquals(2, originalSources.size)

        database.deleteTransaction(original.id)
        val deleteEvent = database.getAuditEvents().single {
            it.entityType == AuditEntityType.TRANSACTION && it.action == AuditAction.DELETE
        }
        val audited = TransactionAuditPayloadCodec.decode(requireNotNull(deleteEvent.beforePayload))
        assertEquals(original.duplicateCount, audited.record.duplicateCount)
        assertEquals(original.dedupeFingerprint, audited.record.dedupeFingerprint)
        assertEquals(
            originalSources.map { it.sourceMessageId to it.receivedAt },
            audited.smsSources.map { it.sourceMessageId to it.receivedAt },
        )
        assertTrue(database.getTransactions().isEmpty())
        assertTrue(database.getTransactionSmsSources().isEmpty())

        database.undoAuditBatch(deleteEvent.batchId)

        val restored = database.getTransactions().single()
        assertEquals(original.id, restored.id)
        assertEquals(2, restored.duplicateCount)
        assertEquals(original.dedupeFingerprint, restored.dedupeFingerprint)
        assertEquals(
            originalSources.toSet(),
            database.getTransactionSmsSources().toSet(),
        )

        val rescan = database.insertAll(
            listOf(
                live to byteArrayOf(1),
                inbox to byteArrayOf(2),
            ),
        )

        assertEquals(0, rescan.inserted)
        assertEquals(0, rescan.duplicatesMerged)
        assertEquals(2, rescan.ignoredSourceMessages)
        assertEquals(1, database.getTransactions().size)
        assertEquals(2, database.getTransactions().single().duplicateCount)
        assertEquals(originalSources.toSet(), database.getTransactionSmsSources().toSet())
    }

    @Test
    fun legacyInsertUndoRejectsAnSmsDuplicateAttachedAfterTheAudit() {
        val live = transaction(LEGACY_LIVE_ID_A, BASE_TIME)
        database.insertAll(listOf(live to byteArrayOf(1)))
        val original = database.getTransactions().single()
        val createdAt = database.readableDatabase.rawQuery(
            "SELECT created_at FROM transactions WHERE id = ?",
            arrayOf(original.id.toString()),
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            cursor.getLong(0)
        }
        val legacyPayload = legacyTransactionAuditPayload(
            record = original,
            rawMessageCipher = byteArrayOf(1),
            createdAt = createdAt,
        )
        database.writableDatabase.execSQL(
            """
            INSERT INTO audit_events(
                batch_id, batch_label, entity_type, entity_id, action, after_payload, occurred_at
            ) VALUES ('legacy-insert', 'Imported transaction', 'TRANSACTION', ?, 'INSERT', ?, ?)
            """.trimIndent(),
            arrayOf<Any>(original.id.toString(), legacyPayload, BASE_TIME),
        )
        database.insertAll(listOf(transaction("sms-142", BASE_TIME) to byteArrayOf(2)))

        val error = runCatching { database.undoAuditBatch("legacy-insert") }.exceptionOrNull()

        assertTrue(requireNotNull(requireNotNull(error).message).contains("changed later"))
        assertEquals(1, database.getTransactions().size)
        assertEquals(2, database.getTransactions().single().duplicateCount)
        assertEquals(2, database.getTransactionSmsSources().size)
    }

    @Test
    fun legacyUpdateUndoPreservesFingerprintThatTheOldPayloadCouldNotRecord() {
        val live = transaction(LEGACY_LIVE_ID_A, BASE_TIME)
        database.insertAll(listOf(live to byteArrayOf(1)))
        val before = database.getTransactions().single()
        val createdAt = database.readableDatabase.rawQuery(
            "SELECT created_at FROM transactions WHERE id = ?",
            arrayOf(before.id.toString()),
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            cursor.getLong(0)
        }
        val beforePayload = legacyTransactionAuditPayload(
            record = before,
            rawMessageCipher = byteArrayOf(1),
            createdAt = createdAt,
        )
        database.writableDatabase.execSQL(
            "UPDATE transactions SET merchant = 'Changed merchant' WHERE id = ?",
            arrayOf<Any>(before.id),
        )
        val changed = database.getTransactions().single()
        val afterPayload = legacyTransactionAuditPayload(
            record = changed,
            rawMessageCipher = byteArrayOf(1),
            createdAt = createdAt,
        )
        database.writableDatabase.execSQL(
            """
            INSERT INTO audit_events(
                batch_id, batch_label, entity_type, entity_id, action,
                before_payload, after_payload, occurred_at
            ) VALUES ('legacy-update', 'Rename merchant', 'TRANSACTION', ?, 'UPDATE', ?, ?, ?)
            """.trimIndent(),
            arrayOf<Any>(before.id.toString(), beforePayload, afterPayload, BASE_TIME),
        )

        database.undoAuditBatch("legacy-update")

        val restored = database.getTransactions().single()
        assertEquals(before.merchant, restored.merchant)
        assertEquals(before.dedupeFingerprint, restored.dedupeFingerprint)
        assertEquals(1, restored.duplicateCount)
        assertEquals(
            setOf(LEGACY_LIVE_ID_A),
            database.getTransactionSmsSources().mapTo(mutableSetOf()) { it.sourceMessageId },
        )
    }

    @Test
    fun deleteUndoRejectsASecondarySmsSourceClaimedByNewerData() {
        val live = transaction(LEGACY_LIVE_ID_A, BASE_TIME)
        val inbox = transaction("sms-143", BASE_TIME)
        database.insertAll(listOf(live to byteArrayOf(1), inbox to byteArrayOf(2)))
        val original = database.getTransactions().single()
        database.deleteTransaction(original.id)
        val deleteEvent = database.getAuditEvents().single {
            it.entityType == AuditEntityType.TRANSACTION && it.action == AuditAction.DELETE
        }

        val rescanned = database.insertAll(listOf(inbox to byteArrayOf(3)))
        assertEquals(1, rescanned.inserted)
        val newerId = database.getTransactions().single().id

        val error = runCatching { database.undoAuditBatch(deleteEvent.batchId) }.exceptionOrNull()

        assertTrue(requireNotNull(requireNotNull(error).message).contains("newer data"))
        assertEquals(setOf(newerId), database.getTransactions().mapTo(mutableSetOf()) { it.id })
        assertEquals(1, database.getTransactions().single().duplicateCount)
        assertEquals(
            setOf("sms-143"),
            database.getTransactionSmsSources().mapTo(mutableSetOf()) { it.sourceMessageId },
        )
        assertTrue(database.getAuditEvents().none { it.reversesEventId == deleteEvent.id })
    }

    @Test
    fun offsetInboxRowIsRepairedOnlyWhenItsKnownSourceIsRescannedWithDateSent() {
        val live = transaction(LEGACY_LIVE_ID_A, BASE_TIME)
        val firstInboxScan = transaction("sms-151", BASE_TIME + 60_000L)
        database.insertAll(listOf(live to byteArrayOf(1)))

        val firstScan = database.insertAll(listOf(firstInboxScan to byteArrayOf(2)))

        assertEquals(1, firstScan.inserted)
        assertEquals(0, firstScan.duplicatesMerged)
        assertEquals(2, database.getTransactions().size)

        val repair = database.insertAll(
            listOf(firstInboxScan.copy(occurredAt = BASE_TIME) to byteArrayOf(2)),
        )

        assertEquals(0, repair.inserted)
        assertEquals(1, repair.duplicatesMerged)
        assertEquals(0, repair.ignoredSourceMessages)
        assertEquals(1, database.getTransactions().size)
        assertEquals(2, database.getTransactions().single().duplicateCount)
        assertEquals(
            setOf(LEGACY_LIVE_ID_A, "sms-151"),
            database.getTransactionSmsSources().mapTo(mutableSetOf()) { it.sourceMessageId },
        )

        val repeatedRescan = database.insertAll(
            listOf(firstInboxScan.copy(occurredAt = BASE_TIME) to byteArrayOf(2)),
        )
        assertEquals(0, repeatedRescan.duplicatesMerged)
        assertEquals(1, repeatedRescan.ignoredSourceMessages)
        assertEquals(2, database.getTransactions().single().duplicateCount)
    }

    @Test
    fun restoredInboxIdCollisionKeepsOldHistoryAndMergesNewInboxWithItsLiveRow() {
        val oldInbox = transaction(
            "sms-123",
            BASE_TIME - 86_400_000L,
            body = "Rs 45 debited at Old Bakery from account 1234",
        )
        val newLive = transaction(
            LEGACY_LIVE_ID_A,
            BASE_TIME,
            body = BODY,
        )
        val newInbox = newLive.copy(sourceMessageId = "sms-123")
        database.insertAll(listOf(oldInbox to byteArrayOf(1), newLive to byteArrayOf(2)))

        val result = database.insertAll(listOf(newInbox to byteArrayOf(3)))

        val effectiveInboxId = "sms-123-${smsDuplicateFingerprint(newInbox)}-$BASE_TIME"
        val transactions = database.getTransactions()
        val oldTransactionId = transactionIdForSource("sms-123")
        val newTransactionId = transactionIdForSource(LEGACY_LIVE_ID_A)
        assertEquals(0, result.inserted)
        assertEquals(1, result.duplicatesMerged)
        assertEquals(2, transactions.size)
        assertEquals(1, transactions.single { it.id == oldTransactionId }.duplicateCount)
        assertEquals(2, transactions.single { it.id == newTransactionId }.duplicateCount)
        assertEquals(newTransactionId, transactionIdForSource(effectiveInboxId))
        assertEquals(
            setOf("sms-123", LEGACY_LIVE_ID_A, effectiveInboxId),
            database.getTransactionSmsSources().mapTo(mutableSetOf()) { it.sourceMessageId },
        )
    }

    @Test
    fun restoredInboxIdCollisionWithoutLiveRowInsertsOnceAndRescansIdempotently() {
        val oldInbox = transaction(
            "sms-124",
            BASE_TIME - 86_400_000L,
            body = "Rs 45 debited at Old Bakery from account 1234",
        )
        val currentInbox = transaction("sms-124", BASE_TIME)
        database.insertAll(listOf(oldInbox to byteArrayOf(1)))

        val firstScan = database.insertAll(listOf(currentInbox to byteArrayOf(2)))
        val secondScan = database.insertAll(listOf(currentInbox to byteArrayOf(2)))

        val effectiveInboxId = "sms-124-${smsDuplicateFingerprint(currentInbox)}-$BASE_TIME"
        assertEquals(1, firstScan.inserted)
        assertEquals(0, firstScan.duplicatesMerged)
        assertEquals(1, secondScan.ignoredSourceMessages)
        assertEquals(0, secondScan.inserted)
        assertEquals(2, database.getTransactions().size)
        assertTrue(database.getTransactions().all { it.duplicateCount == 1 })
        assertEquals(
            setOf("sms-124", effectiveInboxId),
            database.getTransactionSmsSources().mapTo(mutableSetOf()) { it.sourceMessageId },
        )
    }

    @Test
    fun nullLegacyFingerprintCannotClaimARescannedInboxId() {
        val oldInbox = transaction("sms-125", BASE_TIME - 60_000L)
        database.insertAll(listOf(oldInbox to byteArrayOf(1)))
        val oldTransactionId = transactionIdForSource("sms-125")
        database.writableDatabase.execSQL(
            "UPDATE transactions SET dedupe_fingerprint = NULL WHERE id = ?",
            arrayOf<Any>(oldTransactionId),
        )
        val live = transaction(LEGACY_LIVE_ID_A, BASE_TIME)
        val currentInbox = live.copy(sourceMessageId = "sms-125")
        database.insertAll(listOf(live to byteArrayOf(2)))

        val result = database.insertAll(listOf(currentInbox to byteArrayOf(3)))

        val effectiveInboxId = "sms-125-${smsDuplicateFingerprint(currentInbox)}-$BASE_TIME"
        assertEquals(1, result.duplicatesMerged)
        assertEquals(2, database.getTransactions().size)
        assertTrue(database.getTransactions().any { it.id == oldTransactionId && it.duplicateCount == 1 })
        assertEquals(
            transactionIdForSource(LEGACY_LIVE_ID_A),
            transactionIdForSource(effectiveInboxId),
        )
        assertEquals(oldTransactionId, transactionIdForSource("sms-125"))
    }

    @Test
    fun mismatchedLegacyAliasFingerprintDoesNotAnchorRescanRepair() {
        val live = transaction(LEGACY_LIVE_ID_A, BASE_TIME)
        val offsetInbox = transaction("sms-126", BASE_TIME + 60_000L)
        database.insertAll(listOf(live to byteArrayOf(1), offsetInbox to byteArrayOf(2)))
        val inboxTransactionId = transactionIdForSource("sms-126")
        database.writableDatabase.execSQL(
            "UPDATE transactions SET dedupe_fingerprint = 'legacy-alias-fingerprint' WHERE id = ?",
            arrayOf<Any>(inboxTransactionId),
        )

        val result = database.insertAll(
            listOf(offsetInbox.copy(occurredAt = BASE_TIME) to byteArrayOf(2)),
        )

        val effectiveInboxId = "sms-126-${smsDuplicateFingerprint(offsetInbox)}-$BASE_TIME"
        assertEquals(1, result.duplicatesMerged)
        assertEquals(2, database.getTransactions().size)
        assertTrue(database.getTransactions().any { it.id == inboxTransactionId })
        assertEquals(inboxTransactionId, transactionIdForSource("sms-126"))
        assertEquals(transactionIdForSource(LEGACY_LIVE_ID_A), transactionIdForSource(effectiveInboxId))
    }

    @Test
    fun rescanRepairPrefersAnEditedSurvivorAndSkipsTwoEditedRows() {
        val live = transaction(LEGACY_LIVE_ID_A, BASE_TIME)
        val offsetInbox = transaction("sms-181", BASE_TIME + 60_000L)
        database.insertAll(listOf(live to byteArrayOf(1), offsetInbox to byteArrayOf(2)))
        val inboxId = transactionIdForSource("sms-181")
        insertAuditDependency(database.writableDatabase, inboxId)

        val repaired = database.insertAll(
            listOf(offsetInbox.copy(occurredAt = BASE_TIME) to byteArrayOf(2)),
        )

        assertEquals(1, repaired.duplicatesMerged)
        assertEquals(inboxId, database.getTransactions().single().id)
        assertEquals(2, database.getTransactions().single().duplicateCount)
        assertTrue(database.getTransactionSmsSources().all { it.transactionId == inboxId })

        database.clearAll()
        val secondLive = transaction(LEGACY_LIVE_ID_B, BASE_TIME)
        val secondInbox = transaction("sms-182", BASE_TIME + 60_000L)
        database.insertAll(listOf(secondLive to byteArrayOf(3), secondInbox to byteArrayOf(4)))
        insertAuditDependency(database.writableDatabase, transactionIdForSource(LEGACY_LIVE_ID_B))
        insertAuditDependency(database.writableDatabase, transactionIdForSource("sms-182"))

        val skipped = database.insertAll(
            listOf(secondInbox.copy(occurredAt = BASE_TIME) to byteArrayOf(4)),
        )

        assertEquals(0, skipped.duplicatesMerged)
        assertEquals(1, skipped.ignoredSourceMessages)
        assertEquals(2, database.getTransactions().size)
        assertTrue(database.getTransactions().all { it.duplicateCount == 1 })
    }

    @Test
    fun sameProvenancePurchasesAndAsymmetricCrossSourceOffsetsRemainSeparate() {
        database.insertAll(
            listOf(
                transaction(LEGACY_LIVE_ID_A, BASE_TIME) to byteArrayOf(1),
                transaction(LEGACY_LIVE_ID_B, BASE_TIME) to byteArrayOf(2),
            ),
        )
        assertEquals(2, database.getTransactions().size)

        database.clearAll()
        database.insertAll(listOf(transaction(LEGACY_LIVE_ID_A, BASE_TIME) to byteArrayOf(1)))
        database.insertAll(listOf(transaction("sms-201", BASE_TIME + 60_000L) to byteArrayOf(2)))

        assertEquals(2, database.getTransactions().size)
        assertTrue(database.getTransactions().all { it.duplicateCount == 1 })

        database.clearAll()
        database.insertAll(listOf(transaction("sms-202", BASE_TIME) to byteArrayOf(1)))
        database.insertAll(listOf(transaction(LEGACY_LIVE_ID_A, BASE_TIME + 60_000L) to byteArrayOf(2)))

        assertEquals(2, database.getTransactions().size)
        assertTrue(database.getTransactions().all { it.duplicateCount == 1 })
    }

    @Test
    fun inboxFirstExactTimestampRaceMergesAndTheNextRescanIsIdempotent() {
        val inbox = transaction("sms-251", BASE_TIME)
        val live = transaction("received-aaaaaaaaaaaaaaaaaaaaaaaa-$BASE_TIME", BASE_TIME)
        database.insertAll(listOf(inbox to byteArrayOf(1)))

        val merged = database.insertAll(listOf(live to byteArrayOf(2)))
        val rescanned = database.insertAll(listOf(inbox to byteArrayOf(1)))

        assertEquals(1, merged.duplicatesMerged)
        assertEquals(1, database.getTransactions().size)
        assertEquals(2, database.getTransactions().single().duplicateCount)
        assertEquals(1, rescanned.ignoredSourceMessages)
        assertEquals(0, rescanned.duplicatesMerged)
    }

    @Test
    fun distinctReferencesRemainSeparate() {
        val live = transaction(
            LEGACY_LIVE_ID_A,
            BASE_TIME,
            body = "$BODY Ref ABC123456",
        )
        val inbox = transaction(
            "sms-301",
            BASE_TIME + 60_000L,
            body = "$BODY Ref XYZ123456",
        )

        database.insertAll(listOf(live to byteArrayOf(1), inbox to byteArrayOf(2)))

        assertEquals(2, database.getTransactions().size)
        assertTrue(database.getTransactions().all { it.duplicateCount == 1 })
    }

    @Test
    fun merchantAliasChangeBetweenLiveAlertAndInboxScanDoesNotBreakPairing() {
        val live = transaction(LEGACY_LIVE_ID_A, BASE_TIME)
        val inbox = transaction("sms-351", BASE_TIME)
        database.insertAll(listOf(live to byteArrayOf(1)))
        database.renameMerchant("Corner Store", "Neighbourhood Shop")

        val result = database.insertAll(listOf(inbox to byteArrayOf(2)))

        assertEquals(1, result.duplicatesMerged)
        assertEquals(1, database.getTransactions().size)
        assertEquals("Neighbourhood Shop", database.getTransactions().single().merchant)
        assertEquals(2, database.getTransactions().single().duplicateCount)
    }

    @Test
    fun stableReferenceStillMergesWithinTheWindow() {
        val live = transaction(
            LEGACY_LIVE_ID_A,
            BASE_TIME,
            body = "$BODY Ref ABC123456",
        )
        val inbox = transaction(
            "sms-401",
            BASE_TIME + 60_000L,
            body = "$BODY Ref ABC123456",
        )

        val result = database.insertAll(listOf(live to byteArrayOf(1), inbox to byteArrayOf(2)))

        assertEquals(1, result.inserted)
        assertEquals(1, result.duplicatesMerged)
        assertEquals(1, database.getTransactions().size)
        assertEquals(2, database.getTransactions().single().duplicateCount)
    }

    @Test
    fun versionElevenRepairMergesOnlyExactSafePairsKeepsSourcesAndSkipsUnsafePairs() {
        // Create the full schema first, then make it look like a populated v11 install.
        database.writableDatabase
        database.close()
        context.openOrCreateDatabase(DATABASE_NAME, Context.MODE_PRIVATE, null).use { legacy ->
            insertLegacyTransaction(legacy, 101, LEGACY_LIVE_ID_A, "safe", BASE_TIME, duplicateCount = 2)
            insertLegacyTransaction(legacy, 102, "sms-501", "safe", BASE_TIME, duplicateCount = 3)
            insertSource(legacy, LEGACY_LIVE_ID_A, 101, BASE_TIME)
            insertSource(legacy, "sms-501", 102, BASE_TIME)

            insertLegacyTransaction(legacy, 201, LEGACY_LIVE_ID_B, "unsafe", BASE_TIME, duplicateCount = 1)
            insertLegacyTransaction(legacy, 202, "sms-502", "unsafe", BASE_TIME, duplicateCount = 1)
            insertSource(legacy, LEGACY_LIVE_ID_B, 201, BASE_TIME)
            insertSource(legacy, "sms-502", 202, BASE_TIME)
            insertAuditDependency(legacy, 201)
            insertAuditDependency(legacy, 202)

            // Exactly one dependent row must survive even when it has the greater ID.
            insertLegacyTransaction(legacy, 301, "sms-503", "prefer-dependent", BASE_TIME, duplicateCount = 1)
            insertLegacyTransaction(legacy, 302, "received-cccccccccccccccccccccccc-$BASE_TIME", "prefer-dependent", BASE_TIME, duplicateCount = 1)
            insertSource(legacy, "sms-503", 301, BASE_TIME)
            insertSource(legacy, "received-cccccccccccccccccccccccc-$BASE_TIME", 302, BASE_TIME)
            insertAuditDependency(legacy, 302)

            // An offset pair is ambiguous without raw SMS/DATE_SENT and must survive migration.
            insertLegacyTransaction(legacy, 401, "received-dddddddddddddddddddddddd-$BASE_TIME", "offset", BASE_TIME, 1)
            insertLegacyTransaction(legacy, 402, "sms-504", "offset", BASE_TIME + 60_000L, 1)
            insertSource(legacy, "received-dddddddddddddddddddddddd-$BASE_TIME", 401, BASE_TIME)
            insertSource(legacy, "sms-504", 402, BASE_TIME + 60_000L)
            legacy.version = 11
        }

        database = PaisaLensDatabase(context)

        val transactions = database.getTransactions()
        assertEquals(setOf(101L, 201L, 202L, 302L, 401L, 402L), transactions.mapTo(mutableSetOf()) { it.id })
        assertEquals(5, transactions.single { it.id == 101L }.duplicateCount)
        assertEquals(2, transactions.single { it.id == 302L }.duplicateCount)
        assertEquals(
            setOf(LEGACY_LIVE_ID_A, "sms-501"),
            database.getTransactionSmsSources()
                .filter { it.transactionId == 101L }
                .mapTo(mutableSetOf()) { it.sourceMessageId },
        )
        assertEquals(
            setOf("sms-503", "received-cccccccccccccccccccccccc-$BASE_TIME"),
            database.getTransactionSmsSources()
                .filter { it.transactionId == 302L }
                .mapTo(mutableSetOf()) { it.sourceMessageId },
        )
        assertEquals(1, transactions.single { it.id == 201L }.duplicateCount)
        assertEquals(1, transactions.single { it.id == 202L }.duplicateCount)
        assertEquals(1, transactions.single { it.id == 401L }.duplicateCount)
        assertEquals(1, transactions.single { it.id == 402L }.duplicateCount)
    }

    @Test
    fun versionElevenRepairPairsRepeatedExactMatchesOneToOne() {
        database.writableDatabase
        database.close()
        context.openOrCreateDatabase(DATABASE_NAME, Context.MODE_PRIVATE, null).use { legacy ->
            insertLegacyTransaction(legacy, 401, LEGACY_LIVE_ID_A, "ordered", BASE_TIME, 1)
            insertLegacyTransaction(legacy, 402, LEGACY_LIVE_ID_B, "ordered", BASE_TIME + 10_000L, 1)
            insertLegacyTransaction(legacy, 403, "sms-601", "ordered", BASE_TIME, 1)
            insertLegacyTransaction(legacy, 404, "sms-602", "ordered", BASE_TIME + 10_000L, 1)
            insertSource(legacy, LEGACY_LIVE_ID_A, 401, BASE_TIME)
            insertSource(legacy, LEGACY_LIVE_ID_B, 402, BASE_TIME + 10_000L)
            insertSource(legacy, "sms-601", 403, BASE_TIME)
            insertSource(legacy, "sms-602", 404, BASE_TIME + 10_000L)
            legacy.version = 11
        }

        database = PaisaLensDatabase(context)

        assertEquals(2, database.getTransactions().size)
        assertTrue(database.getTransactions().all { it.duplicateCount == 2 })
        assertEquals(
            setOf(setOf(LEGACY_LIVE_ID_A, "sms-601"), setOf(LEGACY_LIVE_ID_B, "sms-602")),
            database.getTransactionSmsSources()
                .groupBy { it.transactionId }
                .values
                .mapTo(mutableSetOf()) { sources -> sources.mapTo(mutableSetOf()) { it.sourceMessageId } },
        )
    }

    @Test
    fun versionNineEncryptedBackupRestoreRepairsKnownPairsWithoutRawSmsText() {
        val fingerprint = "d".repeat(64)
        val live = backupTransaction(701, LEGACY_LIVE_ID_A, BASE_TIME, fingerprint)
        val inbox = backupTransaction(702, "sms-701", BASE_TIME, fingerprint)
        val snapshot = PaisaLensBackupSnapshot(
            createdAt = BASE_TIME,
            transactions = listOf(live, inbox),
            budgets = emptyList(),
            accounts = emptyList(),
            customCategories = emptyList(),
            merchantRules = emptyList(),
            transactionSmsSources = listOf(
                TransactionSmsSource(live.sourceMessageId, live.id, live.occurredAt),
                TransactionSmsSource(inbox.sourceMessageId, inbox.id, inbox.occurredAt),
            ),
        )
        val encrypted = ByteArrayOutputStream()
        PaisaLensBackupCodec.write(snapshot, "correct horse".toCharArray(), encrypted)
        val decoded = PaisaLensBackupCodec.read(
            "correct horse".toCharArray(),
            ByteArrayInputStream(encrypted.toByteArray()),
        )

        database.restore(decoded)

        assertEquals(1, database.getTransactions().size)
        assertEquals(2, database.getTransactions().single().duplicateCount)
        assertEquals(fingerprint, database.getTransactions().single().dedupeFingerprint)
        assertEquals(
            setOf(LEGACY_LIVE_ID_A, "restored-$BASE_TIME-sms-701"),
            database.getTransactionSmsSources().mapTo(mutableSetOf()) { it.sourceMessageId },
        )
    }

    @Test
    fun versionNineEncryptedBackupRestoreRetainsOffsetPair() {
        val fingerprint = "e".repeat(64)
        val live = backupTransaction(801, LEGACY_LIVE_ID_A, BASE_TIME, fingerprint)
        val inbox = backupTransaction(802, "sms-801", BASE_TIME + 60_000L, fingerprint)
        val snapshot = PaisaLensBackupSnapshot(
            createdAt = BASE_TIME,
            transactions = listOf(live, inbox),
            budgets = emptyList(),
            accounts = emptyList(),
            customCategories = emptyList(),
            merchantRules = emptyList(),
            transactionSmsSources = listOf(
                TransactionSmsSource(live.sourceMessageId, live.id, live.occurredAt),
                TransactionSmsSource(inbox.sourceMessageId, inbox.id, inbox.occurredAt),
            ),
        )
        val encrypted = ByteArrayOutputStream()
        PaisaLensBackupCodec.write(snapshot, "correct horse".toCharArray(), encrypted)
        val decoded = PaisaLensBackupCodec.read(
            "correct horse".toCharArray(),
            ByteArrayInputStream(encrypted.toByteArray()),
        )

        database.restore(decoded)

        assertEquals(setOf(801L, 802L), database.getTransactions().mapTo(mutableSetOf()) { it.id })
        assertTrue(database.getTransactions().all { it.duplicateCount == 1 })
        assertEquals(
            setOf(LEGACY_LIVE_ID_A, "restored-$BASE_TIME-sms-801"),
            database.getTransactionSmsSources().mapTo(mutableSetOf()) { it.sourceMessageId },
        )
    }

    @Test
    fun restoredRawInboxIdCannotClaimSameFingerprintMessageFromCurrentDevice() {
        val backupCreatedAt = BASE_TIME - 172_800_000L
        val currentInbox = transaction("sms-123", BASE_TIME)
        val fingerprint = smsDuplicateFingerprint(currentInbox)
        val restoredOld = backupTransaction(
            id = 901,
            sourceMessageId = "sms-123",
            occurredAt = BASE_TIME - 86_400_000L,
            fingerprint = fingerprint,
        )
        database.restore(
            PaisaLensBackupSnapshot(
                createdAt = backupCreatedAt,
                transactions = listOf(restoredOld),
                budgets = emptyList(),
                accounts = emptyList(),
                customCategories = emptyList(),
                merchantRules = emptyList(),
                transactionSmsSources = listOf(
                    TransactionSmsSource("sms-123", restoredOld.id, restoredOld.occurredAt),
                ),
            ),
        )
        val currentLive = currentInbox.copy(sourceMessageId = LEGACY_LIVE_ID_A)

        database.insertAll(listOf(currentLive to byteArrayOf(1)))
        val result = database.insertAll(listOf(currentInbox to byteArrayOf(2)))

        val restoredSourceId = "restored-$backupCreatedAt-sms-123"
        assertEquals(1, result.duplicatesMerged)
        assertEquals(2, database.getTransactions().size)
        assertTrue(database.getTransactions().any { it.id == restoredOld.id && it.duplicateCount == 1 })
        assertEquals(restoredOld.id, transactionIdForSource(restoredSourceId))
        assertEquals(
            transactionIdForSource(LEGACY_LIVE_ID_A),
            transactionIdForSource("sms-123"),
        )
        assertEquals(
            setOf(restoredSourceId, LEGACY_LIVE_ID_A, "sms-123"),
            database.getTransactionSmsSources().mapTo(mutableSetOf()) { it.sourceMessageId },
        )
    }

    @Test
    fun sameDeviceRestoreThenInboxRescanMergesExactCopyWithoutALiveRow() {
        val inbox = transaction("sms-777", BASE_TIME)
        val restored = backupTransaction(
            id = 921,
            sourceMessageId = inbox.sourceMessageId,
            occurredAt = inbox.occurredAt,
            fingerprint = smsDuplicateFingerprint(inbox),
        )
        database.restore(
            PaisaLensBackupSnapshot(
                createdAt = BASE_TIME,
                transactions = listOf(restored),
                budgets = emptyList(),
                accounts = emptyList(),
                customCategories = emptyList(),
                merchantRules = emptyList(),
                transactionSmsSources = listOf(
                    TransactionSmsSource(inbox.sourceMessageId, restored.id, restored.occurredAt),
                ),
            ),
        )

        val firstRescan = database.insertAll(listOf(inbox to byteArrayOf(1)))
        val secondRescan = database.insertAll(listOf(inbox to byteArrayOf(1)))

        assertEquals(1, firstRescan.duplicatesMerged)
        assertEquals(0, firstRescan.inserted)
        assertEquals(1, secondRescan.ignoredSourceMessages)
        assertEquals(1, database.getTransactions().size)
        assertEquals(2, database.getTransactions().single().duplicateCount)
        assertEquals(
            setOf("restored-$BASE_TIME-sms-777", "sms-777"),
            database.getTransactionSmsSources().mapTo(mutableSetOf()) { it.sourceMessageId },
        )
    }

    @Test
    fun collisionSafeInboxIdIsNamespacedAcrossBackupRestoreAndExactRescan() {
        val oldInbox = transaction(
            "sms-888",
            BASE_TIME - 86_400_000L,
            body = "Rs 45 debited at Old Bakery from account 1234",
        )
        val currentInbox = transaction("sms-888", BASE_TIME)
        database.insertAll(listOf(oldInbox to byteArrayOf(1)))
        database.insertAll(listOf(currentInbox to byteArrayOf(2)))
        val collisionSafeId = "sms-888-${smsDuplicateFingerprint(currentInbox)}-$BASE_TIME"
        assertEquals(2, database.getTransactions().size)
        transactionIdForSource(collisionSafeId)

        val snapshot = database.snapshot()
        database.restore(snapshot)
        val result = database.insertAll(listOf(currentInbox to byteArrayOf(2)))

        val restoredCollisionSafeId = "restored-${snapshot.createdAt}-$collisionSafeId"
        val restoredRawId = "restored-${snapshot.createdAt}-sms-888"
        assertEquals(1, result.duplicatesMerged)
        assertEquals(2, database.getTransactions().size)
        assertEquals(
            transactionIdForSource(restoredCollisionSafeId),
            transactionIdForSource("sms-888"),
        )
        assertEquals(
            2,
            database.getTransactions().single {
                it.id == transactionIdForSource(restoredCollisionSafeId)
            }.duplicateCount,
        )
        assertEquals(
            setOf(restoredRawId, restoredCollisionSafeId, "sms-888"),
            database.getTransactionSmsSources().mapTo(mutableSetOf()) { it.sourceMessageId },
        )
    }

    @Test
    fun restoredTransactionAuditPayloadUsesNamespacedInboxIdAndRemainsUndoable() {
        val transaction = backupTransaction(
            id = 951,
            sourceMessageId = "sms-951",
            occurredAt = BASE_TIME,
            fingerprint = "f".repeat(64),
        )
        val afterPayload = TransactionAuditPayloadCodec.encode(
            TransactionAuditPayload(
                record = transaction,
                rawMessageCipher = null,
                createdAt = BASE_TIME,
                smsSources = listOf(
                    TransactionAuditSmsSource("sms-951", transaction.occurredAt),
                ),
            ),
        )
        database.restore(
            PaisaLensBackupSnapshot(
                createdAt = BASE_TIME,
                transactions = listOf(transaction),
                budgets = emptyList(),
                accounts = emptyList(),
                customCategories = emptyList(),
                merchantRules = emptyList(),
                transactionSmsSources = listOf(
                    TransactionSmsSource("sms-951", transaction.id, transaction.occurredAt),
                ),
                auditEvents = listOf(
                    AuditEvent(
                        id = 1,
                        batchId = "restored-insert",
                        batchLabel = "Imported transaction",
                        entityType = AuditEntityType.TRANSACTION,
                        entityId = transaction.id.toString(),
                        action = AuditAction.INSERT,
                        afterPayload = afterPayload,
                        occurredAt = BASE_TIME,
                    ),
                ),
                creditCardBills = listOf(
                    CreditCardBill(
                        id = 1,
                        billKey = "card-951-cycle",
                        sourceMessageId = "sms-951",
                        cardIdentityKey = "card-951",
                        institutionName = "Example Bank",
                        totalDueMinor = 50_000,
                        dueDateEpochDay = 20_000,
                        detectedAt = BASE_TIME,
                        sender = "VK-EXAMPLE",
                    ),
                ),
            ),
        )

        val restoredPayload = requireNotNull(database.getAuditEvents().single().afterPayload)
        val decodedRestoredPayload = TransactionAuditPayloadCodec.decode(restoredPayload)
        assertEquals(
            "restored-$BASE_TIME-sms-951",
            decodedRestoredPayload.record.sourceMessageId,
        )
        assertEquals(
            listOf("restored-$BASE_TIME-sms-951"),
            decodedRestoredPayload.smsSources.map { it.sourceMessageId },
        )
        assertEquals(
            "restored-$BASE_TIME-sms-951",
            database.getCreditCardBills().single().sourceMessageId,
        )

        val undo = database.undoAuditBatch("restored-insert")

        assertEquals(1, undo.deletedEntities)
        assertTrue(database.getTransactions().isEmpty())
        assertTrue(database.getTransactionSmsSources().isEmpty())
    }

    @Test
    fun legacyDeleteAuditPayloadStaysLegacyAcrossPortableBackupRestoreAndUndo() {
        val transaction = backupTransaction(
            id = 971,
            sourceMessageId = "sms-971",
            occurredAt = BASE_TIME,
            fingerprint = "ignored-by-legacy-format",
        ).copy(dedupeFingerprint = null)
        val legacyPayload = legacyTransactionAuditPayload(transaction)
        val snapshot = PaisaLensBackupSnapshot(
            createdAt = BASE_TIME,
            transactions = emptyList(),
            budgets = emptyList(),
            accounts = emptyList(),
            customCategories = emptyList(),
            merchantRules = emptyList(),
            auditEvents = listOf(
                AuditEvent(
                    id = 1,
                    batchId = "legacy-delete",
                    batchLabel = "Delete transaction",
                    entityType = AuditEntityType.TRANSACTION,
                    entityId = transaction.id.toString(),
                    action = AuditAction.DELETE,
                    beforePayload = legacyPayload,
                    occurredAt = BASE_TIME,
                ),
            ),
        )
        val encrypted = ByteArrayOutputStream()
        PaisaLensBackupCodec.write(snapshot, "audit legacy".toCharArray(), encrypted)
        val decoded = PaisaLensBackupCodec.read(
            "audit legacy".toCharArray(),
            ByteArrayInputStream(encrypted.toByteArray()),
        )

        database.restore(decoded)

        val restoredPayload = requireNotNull(database.getAuditEvents().single().beforePayload)
        assertTrue(!TransactionAuditPayloadCodec.hasExtension(restoredPayload))
        assertEquals(
            "restored-$BASE_TIME-sms-971",
            TransactionAuditPayloadCodec.decode(restoredPayload).record.sourceMessageId,
        )

        database.undoAuditBatch("legacy-delete")

        val restored = database.getTransactions().single()
        assertEquals(transaction.id, restored.id)
        assertEquals(1, restored.duplicateCount)
        assertEquals(null, restored.dedupeFingerprint)
        assertEquals(
            setOf("restored-$BASE_TIME-sms-971"),
            database.getTransactionSmsSources().mapTo(mutableSetOf()) { it.sourceMessageId },
        )
    }

    private fun transaction(
        sourceMessageId: String,
        occurredAt: Long,
        body: String = BODY,
    ) = ParsedTransaction(
        sourceMessageId = sourceMessageId,
        amountMinor = 8_000,
        merchant = "Corner Store",
        accountHint = "1234",
        category = ExpenseCategory.SHOPPING,
        type = TransactionType.EXPENSE,
        occurredAt = occurredAt,
        source = TransactionSource.BANK,
        sender = "VK-HDFCBK",
        rawMessage = body,
    )

    private fun backupTransaction(
        id: Long,
        sourceMessageId: String,
        occurredAt: Long,
        fingerprint: String,
    ) = TransactionRecord(
        id = id,
        sourceMessageId = sourceMessageId,
        amountMinor = 8_000,
        merchant = "Corner Store",
        accountHint = "1234",
        category = ExpenseCategory.SHOPPING,
        type = TransactionType.EXPENSE,
        occurredAt = occurredAt,
        source = TransactionSource.BANK,
        sender = "VK-HDFCBK",
        dedupeFingerprint = fingerprint,
    )

    private fun legacyTransactionAuditPayload(
        record: TransactionRecord,
        rawMessageCipher: ByteArray = byteArrayOf(1, 2, 3),
        createdAt: Long = record.occurredAt,
    ): String {
        val extended = TransactionAuditPayloadCodec.encode(
            TransactionAuditPayload(
                record = record.copy(duplicateCount = 1, dedupeFingerprint = null),
                rawMessageCipher = rawMessageCipher,
                createdAt = createdAt,
            ),
        )
        val bytes = Base64.getDecoder().decode(extended)
        return Base64.getEncoder().encodeToString(bytes.copyOf(bytes.size - 9))
    }

    private fun insertLegacyTransaction(
        db: SQLiteDatabase,
        id: Long,
        sourceMessageId: String,
        fingerprint: String,
        occurredAt: Long,
        duplicateCount: Int,
    ) {
        db.execSQL(
            """
            INSERT INTO transactions(
                id, source_message_id, amount_minor, merchant, account_hint, category, type,
                occurred_at, source, sender, tags, review_status, duplicate_count,
                dedupe_fingerprint, created_at
            ) VALUES (?, ?, 8000, 'Corner Store', '1234', 'SHOPPING', 'EXPENSE', ?, 'BANK',
                'VK-HDFCBK', '', 'CONFIRMED', ?, ?, ?)
            """.trimIndent(),
            arrayOf<Any>(id, sourceMessageId, occurredAt, duplicateCount, fingerprint, occurredAt),
        )
    }

    private fun insertSource(db: SQLiteDatabase, sourceMessageId: String, transactionId: Long, receivedAt: Long) {
        db.execSQL(
            "INSERT INTO transaction_sms_sources(source_message_id, transaction_id, received_at) VALUES (?, ?, ?)",
            arrayOf<Any>(sourceMessageId, transactionId, receivedAt),
        )
    }

    private fun insertAuditDependency(db: SQLiteDatabase, transactionId: Long) {
        db.execSQL(
            """
            INSERT INTO audit_events(
                batch_id, batch_label, entity_type, entity_id, action, occurred_at
            ) VALUES (?, 'Existing edit', 'TRANSACTION', ?, 'UPDATE', ?)
            """.trimIndent(),
            arrayOf<Any>("dependency-$transactionId", transactionId.toString(), BASE_TIME),
        )
    }

    private fun transactionIdForSource(sourceMessageId: String): Long = database
        .getTransactionSmsSources()
        .single { it.sourceMessageId == sourceMessageId }
        .transactionId

    private companion object {
        const val DATABASE_NAME = "paisalens.db"
        const val BASE_TIME = 1_700_000_000_000L
        const val LEGACY_LIVE_ID_A = "aaaaaaaaaaaaaaaaaaaaaaaa"
        const val LEGACY_LIVE_ID_B = "bbbbbbbbbbbbbbbbbbbbbbbb"
        const val BODY = "Rs 80 debited at Corner Store from account 1234"
    }
}
