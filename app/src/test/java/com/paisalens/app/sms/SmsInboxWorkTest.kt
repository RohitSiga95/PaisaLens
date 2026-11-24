package com.paisalens.app.sms

import android.app.Application
import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.provider.Telephony
import com.paisalens.app.data.parser.TransactionSmsParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class SmsInboxWorkTest {
    @Test
    fun unchangedTenThousandMessageInboxReadsOnlyTheOverlapAndParsesNothing() {
        val inbox = CountingSmsInbox((1L..10_000L).map { InboxTestRow(it) })
        val scanner = scannerFor(inbox)
        var initialCount = 0
        val marker = scanner.scan(SmsSyncMode.FULL) { initialCount += it.transactions.size }
        assertEquals(10_000, inbox.bodyReads)
        assertEquals(0, initialCount)
        assertNotNull(marker)

        inbox.bodyReads = 0
        assertEquals(marker, scanner.scan(SmsSyncMode.QUICK, marker) { error("Unchanged rows must not be ingested again") })
        assertEquals(SmsInboxCheckpoint.OVERLAP_ROWS + 1, inbox.bodyReads)
    }

    @Test
    fun quickSyncPicksUpBackdatedMessagesAndOlderRowsCompletedAfterTheFirstScan() {
        val inbox = CountingSmsInbox(listOf(InboxTestRow(1, body = null)) + (2L..80L).map(::bankRow))
        val scanner = scannerFor(inbox)
        val marker = checkNotNull(scanner.scan(SmsSyncMode.QUICK) {})
        assertEquals(setOf(1L), marker.incompleteRowIds)
        inbox.rows = listOf(bankRow(1)) + inbox.rows.drop(1) +
            bankRow(81).copy(receivedAt = 800L, sentAt = 800L)
        val storedIds = mutableListOf<String>()
        val updated = scanner.scan(SmsSyncMode.QUICK, marker) { batch ->
            storedIds += batch.transactions.map { it.sourceMessageId }
        }
        assertEquals(listOf("sms-1", "sms-81"), storedIds)
        assertTrue(checkNotNull(updated).incompleteRowIds.isEmpty())
        assertEquals(81L, updated.anchor.rowId)
    }

    @Test
    fun reusingTheLatestInboxIdTriggersFullRecoveryRatherThanSkippingBackdatedRows() {
        val inbox = CountingSmsInbox((60L..100L).map(::bankRow))
        val scanner = scannerFor(inbox)
        val marker = scanner.scan(SmsSyncMode.QUICK) {}
        inbox.rows = listOf(bankRow(1), bankRow(100).copy(receivedAt = 6_000L, sentAt = 6_000L))
        val recoveredIds = mutableListOf<String>()
        scanner.scan(SmsSyncMode.QUICK, marker) { batch ->
            recoveredIds += batch.transactions.map { it.sourceMessageId }
        }
        assertEquals(listOf("sms-1", "sms-100"), recoveredIds)
    }

    @Test
    fun fullRescanWalksOlderRowsAndDoesNotStopAfterTenThousandMessages() {
        val inbox = CountingSmsInbox((1L..10_257L).map(::bankRow))
        val scanner = scannerFor(inbox)
        var count = 0
        val marker = scanner.scan(SmsSyncMode.QUICK) { count += it.transactions.size }
        assertEquals(10_257, count)
        assertEquals(10_257L, marker?.anchor?.rowId)
        count = 0
        scanner.scan(SmsSyncMode.FULL, marker) { count += it.transactions.size }
        assertEquals(10_257, count)
    }
}

internal fun scannerFor(inbox: CountingSmsInbox): SmsInboxScanner {
    ShadowContentResolver.registerProviderInternal("sms", inbox)
    return SmsInboxScanner(RuntimeEnvironment.getApplication(), TransactionSmsParser(), AccountAvailabilitySmsParser())
}

internal fun bankRow(id: Long) = InboxTestRow(
    id,
    sender = "VM-HDFCBK",
    body = "Rs. 450.00 debited from A/c XX1234 at SWIGGY. Avl bal Rs 8,200.50.",
)

internal data class InboxTestRow(
    val id: Long,
    val sender: String = "VM-TESTIN",
    val body: String? = "A synthetic inbox message.",
    val receivedAt: Long = 1_700_000_000_000L + id * 1_000L,
    val sentAt: Long = receivedAt,
)

internal class CountingSmsInbox(var rows: List<InboxTestRow>) : ContentProvider() {
    var bodyReads = 0
    var failAfterBodyReads: Int? = null

    override fun onCreate(): Boolean = true

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor {
        check(uri == Telephony.Sms.Inbox.CONTENT_URI)
        val selectedRows = when {
            selection == null -> rows
            selection == "_id = ?" -> rows.filter { it.id == checkNotNull(selectionArgs).single().toLong() }
            selection.startsWith("_id >= ?") -> {
                val from = checkNotNull(selectionArgs).first().toLong()
                val retries = selectionArgs.drop(1).map(String::toLong).toSet()
                rows.filter { it.id >= from || it.id in retries }
            }
            else -> error("Unexpected SMS selection: $selection")
        }
        val columns = checkNotNull(projection).toList().toTypedArray()
        val bodyColumn = columns.indexOf(Telephony.Sms.BODY)
        return object : MatrixCursor(columns, selectedRows.size) {
            override fun getString(column: Int): String? {
                if (column == bodyColumn) {
                    bodyReads++
                    check(failAfterBodyReads?.let { bodyReads <= it } != false) { "Test inbox interrupted" }
                }
                return super.getString(column)
            }
        }.apply {
            val sortedRows = if (sortOrder?.contains("DESC") == true) selectedRows.sortedByDescending { it.id } else selectedRows.sortedBy { it.id }
            sortedRows.forEach { row ->
                addRow(columns.map { column ->
                    when (column) {
                        Telephony.Sms._ID -> row.id
                        Telephony.Sms.ADDRESS -> row.sender
                        Telephony.Sms.BODY -> row.body
                        Telephony.Sms.DATE -> row.receivedAt
                        Telephony.Sms.DATE_SENT -> row.sentAt
                        else -> error("Unexpected SMS column: $column")
                    }
                })
            }
        }
    }

    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = error("Read-only test inbox")
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int =
        error("Read-only test inbox")
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int =
        error("Read-only test inbox")
}
