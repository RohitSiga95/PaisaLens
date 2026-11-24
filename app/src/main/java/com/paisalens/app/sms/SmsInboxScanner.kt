package com.paisalens.app.sms

import android.content.Context
import android.database.Cursor
import android.provider.Telephony
import com.paisalens.app.data.model.AccountAvailabilityUpdate
import com.paisalens.app.data.model.CreditCardPayment
import com.paisalens.app.data.model.ParsedTransaction
import com.paisalens.app.data.model.ParsedCreditCardBill
import com.paisalens.app.data.model.SmsCoverageCandidate
import com.paisalens.app.data.model.SmsCoverageRule
import com.paisalens.app.data.model.TransactionType
import com.paisalens.app.data.model.UtilitySmsBill
import com.paisalens.app.data.model.smsCoverageReasonOrNull
import com.paisalens.app.data.parser.TransactionSmsParser
import java.security.MessageDigest

internal class SmsInboxScanner(
    private val context: Context,
    private val parser: TransactionSmsParser,
    private val availabilityParser: AccountAvailabilitySmsParser,
    private val creditCardBillParser: CreditCardBillSmsParser = CreditCardBillSmsParser(),
) {
    /**
     * Quickly revisit a small overlap and all newer IDs, ignoring content already processed.
     * In recovery or Full mode, walk the entire inbox without a cap on the number of messages.
     * The caller must persist the returned bookmark only after every batch has been stored.
     */
    fun scan(
        mode: SmsSyncMode,
        checkpoint: SmsInboxCheckpoint? = null,
        coverageRules: List<SmsCoverageRule> = emptyList(),
        checkCancelled: () -> Unit = {},
        consumeBatch: (SmsScanBatch) -> Unit,
    ): SmsInboxCheckpoint? {
        val verified = checkpoint?.takeIf { mode == SmsSyncMode.QUICK && hasSameAnchor(it.anchor) }
        val first = scanRange(verified, coverageRules, checkCancelled, consumeBatch)
        if (first.anchorVerified) return first.checkpoint
        // The anchor may have been replaced between the point check and the range query.
        // A full walk is safe even if a prior batch already committed.
        return scanRange(null, coverageRules, checkCancelled, consumeBatch).checkpoint
    }

    private fun hasSameAnchor(anchor: SmsInboxMarker): Boolean =
        query("$ID = ?", arrayOf(anchor.rowId.toString())).use { cursor ->
            cursor.moveToFirst() && InboxColumns(cursor).read(cursor).marker() == anchor
        }

    private fun scanRange(
        checkpoint: SmsInboxCheckpoint?,
        coverageRules: List<SmsCoverageRule>,
        checkCancelled: () -> Unit,
        consumeBatch: (SmsScanBatch) -> Unit,
    ): RangeResult {
        val recent = ArrayDeque<InboxRow>(SmsInboxCheckpoint.OVERLAP_ROWS)
        val known = checkpoint?.recentRows.orEmpty().associateBy(SmsInboxMarker::rowId)
        val incomplete = mutableSetOf<Long>()
        val retryIds = checkpoint?.incompleteRowIds.orEmpty()
        var tooManyIncomplete = false
        var anchorVerified = checkpoint == null
        var batch = ScanBatchBuilder()
        var rowsInBatch = 0
        var lastId = 0L

        val selection = checkpoint?.let {
            "$ID >= ?" + if (retryIds.isEmpty()) "" else " OR $ID IN (${retryIds.joinToString(",") { "?" }})"
        }
        val args = checkpoint?.let {
            (listOf(it.recentRows.first().rowId) + retryIds).map(Long::toString).toTypedArray()
        }
        query(selection, args).use { cursor ->
            val columns = InboxColumns(cursor)
            while (cursor.moveToNext()) {
                checkCancelled()
                val row = columns.read(cursor)
                check(row.id > lastId) { "Android returned inbox rows out of order" }
                lastId = row.id
                val alreadySeen = known[row.id]?.let { it == row.marker() } == true
                if (row.id == checkpoint?.anchor?.rowId) anchorVerified = alreadySeen
                if (row.body.isNullOrBlank()) {
                    if (incomplete.size < SmsInboxCheckpoint.MAX_INCOMPLETE_ROWS) incomplete.add(row.id)
                    else tooManyIncomplete = true
                } else if (!alreadySeen || row.id in retryIds) {
                    parseRow(row, coverageRules, batch)
                }
                if (recent.size == SmsInboxCheckpoint.OVERLAP_ROWS) recent.removeFirst()
                recent.addLast(row)
                rowsInBatch++
                if (rowsInBatch >= SCAN_BATCH_ROWS) {
                    batch.build()?.let(consumeBatch)
                    batch = ScanBatchBuilder()
                    rowsInBatch = 0
                }
            }
        }
        batch.build()?.let(consumeBatch)
        val next = if (recent.isNotEmpty() && !tooManyIncomplete) {
            SmsInboxCheckpoint(recent.map(InboxRow::marker), incomplete.toSet())
        } else null
        return RangeResult(next, anchorVerified)
    }

    private fun parseRow(row: InboxRow, coverageRules: List<SmsCoverageRule>, batch: ScanBatchBuilder) {
        val body = checkNotNull(row.body)
        val timestamp = preferredSmsTimestamp(row.sentAt, row.receivedAt)
        val sourceMessageId = "sms-" + row.id
        val utilityBill = UtilityBillSmsParser.parse(row.sender, body, timestamp)
        if (utilityBill != null) {
            batch.utilityBills.add(utilityBill)
            return
        }
        val cardPayment = CreditCardPaymentSmsParser.parse(row.sender, body, timestamp)
        val transaction = parser.parse(
            sender = row.sender,
            body = body,
            timestamp = timestamp,
            messageId = sourceMessageId,
            coverageRules = coverageRules,
        )?.takeIf { cardPayment == null || it.type == TransactionType.TRANSFER }
        val availability = availabilityParser.parse(row.sender, body, timestamp)
        val cardBill = if (cardPayment == null) creditCardBillParser.parse(row.sender, body, timestamp, sourceMessageId) else null
        transaction?.let(batch.transactions::add)
        availability?.let(batch.availability::add)
        cardBill?.let(batch.cardBills::add)
        cardPayment?.let(batch.cardPayments::add)
        if (transaction == null && availability == null && cardBill == null && cardPayment == null) {
            smsCoverageReasonOrNull(row.sender, body)?.let { reason ->
                batch.coverage.add(SmsCoverageCandidate(sourceMessageId, row.sender.ifBlank { "Unknown sender" }, body, timestamp, reason))
            }
        }
    }

    private fun query(selection: String?, args: Array<String>?): Cursor = checkNotNull(
        context.contentResolver.query(Telephony.Sms.Inbox.CONTENT_URI, PROJECTION, selection, args, "$ID ASC"),
    ) { "Android's SMS inbox is unavailable" }

    private data class RangeResult(val checkpoint: SmsInboxCheckpoint?, val anchorVerified: Boolean)

    private class InboxColumns(cursor: Cursor) {
        private val id = cursor.getColumnIndexOrThrow(ID)
        private val sender = cursor.getColumnIndexOrThrow(Telephony.Sms.ADDRESS)
        private val body = cursor.getColumnIndexOrThrow(Telephony.Sms.BODY)
        private val date = cursor.getColumnIndexOrThrow(Telephony.Sms.DATE)
        private val sent = cursor.getColumnIndexOrThrow(Telephony.Sms.DATE_SENT)

        fun read(cursor: Cursor) = InboxRow(
            cursor.getLong(id),
            cursor.getString(sender).orEmpty(),
            cursor.getString(body),
            cursor.getLong(date),
            cursor.getLong(sent),
        )
    }

    private class InboxRow(val id: Long, val sender: String, val body: String?, val receivedAt: Long, val sentAt: Long) {
        private var fingerprint: String? = null

        fun marker(): SmsInboxMarker {
            val value = fingerprint ?: MessageDigest.getInstance("SHA-256")
                .digest("$receivedAt:$sentAt:${sender.length}:$sender:${body?.length ?: -1}:${body.orEmpty()}".toByteArray(Charsets.UTF_8))
                .joinToString("") { byte -> HEX[(byte.toInt() and 255) shr 4].toString() + HEX[byte.toInt() and 15] }
                .also { fingerprint = it }
            return SmsInboxMarker(id, value)
        }
    }

    private class ScanBatchBuilder {
        val transactions = mutableListOf<ParsedTransaction>()
        val availability = mutableListOf<AccountAvailabilityUpdate>()
        val coverage = mutableListOf<SmsCoverageCandidate>()
        val cardBills = mutableListOf<ParsedCreditCardBill>()
        val cardPayments = mutableListOf<CreditCardPayment>()
        val utilityBills = mutableListOf<UtilitySmsBill>()

        fun build(): SmsScanBatch? = if (
            transactions.isEmpty() && availability.isEmpty() && coverage.isEmpty() &&
            cardBills.isEmpty() && cardPayments.isEmpty() && utilityBills.isEmpty()
        ) null else SmsScanBatch(transactions, availability, coverage, cardBills, cardPayments, utilityBills)
    }

    private companion object {
        const val ID = Telephony.Sms._ID
        const val SCAN_BATCH_ROWS = 256
        const val HEX = "0123456789abcdef"
        val PROJECTION = arrayOf(ID, Telephony.Sms.ADDRESS, Telephony.Sms.BODY, Telephony.Sms.DATE, Telephony.Sms.DATE_SENT)
    }
}

/**
 * The live receiver timestamps an SMS at its network sent time. Inbox rows also expose that
 * value, but some providers leave it unset or return a wildly inconsistent value. Keeping the
 * two ingestion paths on the same sane timestamp prevents one physical SMS becoming two ledger
 * entries while retaining the provider's received time as a safe fallback.
 */
internal fun preferredSmsTimestamp(sentAt: Long, receivedAt: Long): Long {
    if (sentAt <= 0L) return receivedAt
    if (receivedAt <= 0L) return sentAt
    val delta = if (sentAt >= receivedAt) sentAt - receivedAt else receivedAt - sentAt
    return if (delta <= MAX_SANE_SMS_TIMESTAMP_DELTA_MILLIS) sentAt else receivedAt
}

private const val MAX_SANE_SMS_TIMESTAMP_DELTA_MILLIS = 7L * 24L * 60L * 60L * 1000L

data class SmsScanBatch(
    val transactions: List<ParsedTransaction>,
    val availabilityUpdates: List<AccountAvailabilityUpdate>,
    val coverageCandidates: List<SmsCoverageCandidate> = emptyList(),
    val creditCardBills: List<ParsedCreditCardBill> = emptyList(),
    val creditCardPayments: List<CreditCardPayment> = emptyList(),
    val utilityBills: List<UtilitySmsBill> = emptyList(),
)
