package com.paisalens.app.sms

import android.app.Application
import android.content.Context
import com.paisalens.app.data.local.PaisaLensDatabase
import com.paisalens.app.data.local.SmsInboxProgressStore
import com.paisalens.app.data.model.SmsCoverageCandidate
import com.paisalens.app.data.model.SmsCoverageReason
import com.paisalens.app.data.repository.TransactionRepository
import com.paisalens.app.security.SensitiveDataCipher
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class SmsInboxIngestTest {
    private lateinit var context: Context
    private lateinit var database: PaisaLensDatabase
    private lateinit var repository: TransactionRepository
    private lateinit var progress: SmsInboxProgressStore

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        context.deleteDatabase("paisalens.db")
        database = PaisaLensDatabase(context)
        repository = TransactionRepository(context, database, SensitiveDataCipher())
        progress = SmsInboxProgressStore(context)
        progress.write(null)
    }

    @After
    fun tearDown() {
        database.close()
        context.deleteDatabase("paisalens.db")
    }

    @Test
    fun interruptedQuickSyncReplaysCommittedBatchesWithoutRepeatingBalanceHistory(): Unit = runBlocking {
        val inbox = CountingSmsInbox(listOf(balanceRow(1)))
        val scanner = scannerFor(inbox)
        assertEquals(1, repository.syncSmsInbox(scanner, SmsSyncMode.QUICK).balanceSnapshotsAdded)
        val saved = checkNotNull(progress.read())
        inbox.rows = (1L..300L).map(::balanceRow)
        inbox.bodyReads = 0
        inbox.failAfterBodyReads = 260

        val interrupted = runCatching { repository.syncSmsInbox(scanner, SmsSyncMode.QUICK) }
        assertTrue(interrupted.isFailure)
        assertEquals(saved, progress.read())
        assertEquals(256, repository.balanceHistory.value.size)

        inbox.failAfterBodyReads = null
        assertEquals(44, repository.syncSmsInbox(scanner, SmsSyncMode.QUICK).balanceSnapshotsAdded)
        assertEquals(300, database.getBalanceHistory().size)
        assertEquals(300, repository.balanceHistory.value.size)
        assertEquals(300L, progress.read()?.anchor?.rowId)
        assertEquals(0, repository.syncSmsInbox(scanner, SmsSyncMode.QUICK).balanceSnapshotsAdded)
    }

    @Test
    fun interruptedFullRescanInvalidatesTheOldBookmarkSoQuickSyncRecoversEarlierRows(): Unit = runBlocking {
        val inbox = CountingSmsInbox((60L..100L).map(::balanceRow))
        val scanner = scannerFor(inbox)
        repository.syncSmsInbox(scanner, SmsSyncMode.QUICK)
        assertNotNull(progress.read())
        inbox.rows = listOf(balanceRow(1)) + inbox.rows
        inbox.bodyReads = 0
        inbox.failAfterBodyReads = 1
        assertTrue(runCatching { repository.syncSmsInbox(scanner, SmsSyncMode.FULL) }.isFailure)
        assertNull(progress.read())

        inbox.failAfterBodyReads = null
        val recovered = repository.syncSmsInbox(scanner, SmsSyncMode.QUICK)
        assertEquals(0, recovered.updatedAccounts)
        assertEquals(1, recovered.balanceSnapshotsAdded)
        assertEquals(42, repository.balanceHistory.value.size)
        assertEquals(100_000L, repository.accounts.value.single().balanceMinor)
    }

    @Test
    fun erasingLedgerAlsoErasesTheInboxBookmark(): Unit = runBlocking {
        val scanner = scannerFor(CountingSmsInbox(listOf(balanceRow(1))))
        repository.syncSmsInbox(scanner, SmsSyncMode.QUICK)
        assertNotNull(progress.read())
        repository.clearAll()
        assertNull(progress.read())
        assertTrue(repository.balanceHistory.value.isEmpty())
        assertEquals(1, repository.syncSmsInbox(scanner, SmsSyncMode.QUICK).balanceSnapshotsAdded)
    }

    @Test
    fun repeatingInboxAndLiveCopiesKeepOneTransactionAndDoNotEncryptKnownCopies() {
        val scanner = scannerFor(CountingSmsInbox(listOf(bankRow(1))))
        val initial = mutableListOf<SmsScanBatch>()
        val marker = scanner.scan(SmsSyncMode.QUICK) { initial += it }
        val live = initial.single().transactions.single().copy(sourceMessageId = "received-synthetic-1")
        database.insertAll(listOf(live to byteArrayOf(42)))
        var encrypted = 0
        fun ingest(batch: SmsScanBatch) {
            database.insertSmsTransactions(batch.transactions) {
                encrypted++
                byteArrayOf(2)
            }
        }

        initial.forEach(::ingest)
        scanner.scan(SmsSyncMode.FULL, marker, consumeBatch = ::ingest)
        scanner.scan(SmsSyncMode.QUICK, marker, consumeBatch = ::ingest)

        assertEquals(0, encrypted)
        assertEquals(2, database.getTransactions().single().duplicateCount)
        assertEquals(setOf("sms-1", "received-synthetic-1"), database.getTransactionSmsSources().map { it.sourceMessageId }.toSet())
    }

    @Test
    fun rescanningAfterAParserRuleChangeKeepsTheOriginalTransactionIncludingReusedIds() {
        val scanner = scannerFor(CountingSmsInbox(listOf(bankRow(1))))
        val batches = mutableListOf<SmsScanBatch>()
        scanner.scan(SmsSyncMode.FULL) { batches += it }
        val original = batches.single().transactions.single()
        fun ingest(item: com.paisalens.app.data.model.ParsedTransaction) = database.insertSmsTransactions(
            listOf(item),
            matchesEncryptedBody = { old, text -> old.toString(Charsets.UTF_8) == text },
            encrypt = { it.toByteArray(Charsets.UTF_8) },
        )
        assertEquals(1, ingest(original).inserted)
        assertEquals(0, ingest(original.copy(merchant = "Different interpretation")).inserted)
        val replacement = original.copy(rawMessage = "A different payment", occurredAt = original.occurredAt + 1)
        assertEquals(1, ingest(replacement).inserted)
        assertEquals(0, ingest(replacement.copy(merchant = "Another interpretation")).inserted)
        assertEquals(2, database.getTransactions().size)
        assertTrue(database.getTransactions().all { it.duplicateCount == 1 })
    }

    @Test
    fun aReusedInboxIdDoesNotHideAnotherUnsupportedAlertAndRescanDoesNotDuplicateEither() {
        val original = SmsCoverageCandidate(
            "sms-9", "VM-HDFCBK", "Bank alert first format.", 1_700_000_000_000L,
            SmsCoverageReason.UNSUPPORTED_FORMAT,
        )
        val replacement = original.copy(body = "Bank alert different format.")
        assertEquals(2, database.insertSmsCoverage(listOf(original to byteArrayOf(1), replacement to byteArrayOf(2))))
        assertEquals(0, database.insertSmsCoverageCandidates(listOf(original, replacement)) { error("Known copies") })
        assertEquals(2, database.getEncryptedSmsCoverageMessages().map { it.sourceMessageId }.toSet().size)
    }

    private fun balanceRow(id: Long): InboxTestRow = InboxTestRow(
        id, sender = "VM-HDFCBK", body = "Available balance for A/c XX1234 is INR 1,000.",
    )
}
