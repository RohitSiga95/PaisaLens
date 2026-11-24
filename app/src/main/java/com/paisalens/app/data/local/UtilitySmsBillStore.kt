package com.paisalens.app.data.local

import android.content.ContentValues
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import com.paisalens.app.data.model.BillReminder
import com.paisalens.app.data.model.UtilityBillKind
import com.paisalens.app.data.model.UtilitySmsBill
import com.paisalens.app.data.model.findUtilitySmsBillMatch
import com.paisalens.app.data.model.withNotice

internal data class UtilitySmsIngestResult(
    val changedBills: Int = 0,
    val changedTrackedIds: Set<Long> = emptySet(),
)

internal object UtilitySmsBillStore {
    fun createTable(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS utility_sms_bills (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                kind TEXT NOT NULL,
                provider_key TEXT NOT NULL,
                provider_name TEXT NOT NULL,
                account_key TEXT,
                account_last_four TEXT,
                reference_key TEXT,
                billing_period TEXT,
                amount_minor INTEGER NOT NULL CHECK(amount_minor > 0),
                due_date_epoch_day INTEGER NOT NULL,
                detected_at INTEGER NOT NULL,
                notice_keys TEXT NOT NULL,
                earlier_due_dates TEXT NOT NULL DEFAULT '',
                tracked_bill_id INTEGER UNIQUE REFERENCES bills(id) ON DELETE SET NULL,
                dismissed INTEGER NOT NULL DEFAULT 0 CHECK(dismissed IN (0, 1))
            )
            """.trimIndent(),
        )
    }

    fun readAll(db: SQLiteDatabase): List<UtilitySmsBill> = db.query(
        TABLE, columns, null, null, null, null, "due_date_epoch_day DESC, detected_at DESC, id DESC",
    ).use { cursor ->
        buildList {
            while (cursor.moveToNext()) add(cursor.readUtilityBill())
        }
    }

    private fun read(db: SQLiteDatabase, id: Long): UtilitySmsBill? = db.query(
        TABLE, columns, "id = ?", arrayOf(id.toString()), null, null, null, "1",
    ).use { cursor -> if (cursor.moveToFirst()) cursor.readUtilityBill() else null }

    fun ingest(
        db: SQLiteDatabase,
        notices: List<UtilitySmsBill>,
        beforeTrackedChange: () -> Unit = {},
    ): UtilitySmsIngestResult {
        if (notices.isEmpty()) return UtilitySmsIngestResult()
        db.beginTransaction()
        try {
            val stored = readAll(db).associateByTo(linkedMapOf(), UtilitySmsBill::id)
            val seen = stored.values.flatMapTo(hashSetOf(), UtilitySmsBill::noticeKeys)
            val changed = mutableSetOf<Long>()
            val tracked = mutableSetOf<Long>()
            notices.sortedBy(UtilitySmsBill::detectedAt).forEach { notice ->
                require(notice.id == 0L && notice.trackedBillId == null && !notice.dismissed)
                requireValid(notice)
                if (notice.noticeKeys.any(seen::contains)) return@forEach
                val match = findUtilitySmsBillMatch(notice, stored.values.toList())
                val saved = if (match == null) {
                    notice.copy(id = db.insertOrThrow(TABLE, null, values(notice)))
                } else {
                    val updated = match.withNotice(notice)
                    db.update(TABLE, values(updated), "id = ?", arrayOf(match.id.toString()))
                    if (updateTrackedBill(db, match, updated, beforeTrackedChange)) tracked.add(checkNotNull(match.trackedBillId))
                    updated
                }
                stored[saved.id] = saved
                changed.add(saved.id)
                seen.addAll(notice.noticeKeys)
            }
            db.setTransactionSuccessful()
            return UtilitySmsIngestResult(changed.size, tracked)
        } finally {
            db.endTransaction()
        }
    }

    /**
     * Refresh amount/date independently, and only while each still matches the last SMS value.
     * An edit or paid state made in the ordinary bill flow is preserved.
     */
    private fun updateTrackedBill(
        db: SQLiteDatabase,
        old: UtilitySmsBill,
        updated: UtilitySmsBill,
        beforeTrackedChange: () -> Unit,
    ): Boolean {
        val id = old.trackedBillId ?: return false
        if (old.amountMinor == updated.amountMinor && old.dueDateEpochDay == updated.dueDateEpochDay) return false
        val current = db.query(
            "bills", arrayOf("amount_minor", "due_date_epoch_day"),
            "id = ? AND is_active = 1", arrayOf(id.toString()), null, null, null, "1",
        ).use { cursor ->
            if (cursor.moveToFirst()) cursor.getLong(0) to cursor.getLong(1) else null
        } ?: return false
        val values = ContentValues()
        if (current.first == old.amountMinor && old.amountMinor != updated.amountMinor) {
            values.put("amount_minor", updated.amountMinor)
        }
        if (current.second == old.dueDateEpochDay && old.dueDateEpochDay != updated.dueDateEpochDay) {
            values.put("due_date_epoch_day", updated.dueDateEpochDay)
        }
        if (values.size() == 0) return false
        beforeTrackedChange()
        values.put("updated_at", System.currentTimeMillis())
        return db.update("bills", values, "id = ?", arrayOf(id.toString())) == 1
    }

    fun track(
        db: SQLiteDatabase,
        expected: UtilitySmsBill,
        insertBill: (BillReminder) -> Long,
    ): Long? {
        db.beginTransaction()
        try {
            val current = read(db, expected.id) ?: return null
            if (current.dismissed || current.amountMinor != expected.amountMinor ||
                current.dueDateEpochDay != expected.dueDateEpochDay
            ) return null
            current.trackedBillId?.let { return it }
            val billId = insertBill(
                BillReminder(
                    title = current.title,
                    amountMinor = current.amountMinor,
                    dueDateEpochDay = current.dueDateEpochDay,
                ),
            )
            check(db.update(
                TABLE, ContentValues().apply { put("tracked_bill_id", billId) },
                "id = ? AND tracked_bill_id IS NULL AND dismissed = 0", arrayOf(current.id.toString()),
            ) == 1) { "The utility bill changed before it could be added" }
            db.setTransactionSuccessful()
            return billId
        } finally {
            db.endTransaction()
        }
    }

    fun dismiss(db: SQLiteDatabase, id: Long): Boolean = db.update(
        TABLE, ContentValues().apply { put("dismissed", 1) },
        "id = ? AND tracked_bill_id IS NULL AND dismissed = 0", arrayOf(id.toString()),
    ) == 1

    fun dismissDeletedBill(db: SQLiteDatabase, trackedBillId: Long) {
        db.update(
            TABLE, ContentValues().apply {
                putNull("tracked_bill_id")
                put("dismissed", 1)
            }, "tracked_bill_id = ?", arrayOf(trackedBillId.toString()),
        )
    }

    fun restore(db: SQLiteDatabase, bills: List<UtilitySmsBill>, validBillIds: Set<Long>) {
        val notices = hashSetOf<String>()
        bills.forEach { bill ->
            requireValid(bill)
            require(bill.id > 0L && bill.noticeKeys.all(notices::add) &&
                (bill.trackedBillId == null || (!bill.dismissed && bill.trackedBillId in validBillIds))
            ) { "Backup contains an invalid utility bill link" }
            db.insertOrThrow(TABLE, null, values(bill).apply { put("id", bill.id) })
        }
    }

    fun clear(db: SQLiteDatabase) {
        db.delete(TABLE, null, null)
    }

    private fun requireValid(bill: UtilitySmsBill) {
        require(
            providerKey.matches(bill.providerKey) && bill.providerName.isNotBlank() && bill.providerName.length <= 64 &&
                (bill.accountKey?.let(hash::matches) ?: true) &&
                (bill.accountLastFour?.let(lastFour::matches) ?: true) &&
                (bill.referenceKey?.let(hash::matches) ?: true) &&
                (bill.billingPeriod?.let(period::matches) ?: true) &&
                bill.amountMinor > 0L && bill.detectedAt > 0L &&
                bill.dueDateEpochDay in validEpochDays && bill.earlierDueDates.all { it in validEpochDays } &&
                bill.noticeKeys.isNotEmpty() && bill.noticeKeys.all(hash::matches),
        ) { "Invalid structured utility bill" }
    }

    private fun values(bill: UtilitySmsBill) = ContentValues().apply {
        put("kind", bill.kind.name)
        put("provider_key", bill.providerKey)
        put("provider_name", bill.providerName)
        put("account_key", bill.accountKey)
        put("account_last_four", bill.accountLastFour)
        put("reference_key", bill.referenceKey)
        put("billing_period", bill.billingPeriod)
        put("amount_minor", bill.amountMinor)
        put("due_date_epoch_day", bill.dueDateEpochDay)
        put("detected_at", bill.detectedAt)
        put("notice_keys", bill.noticeKeys.sorted().joinToString(","))
        put("earlier_due_dates", bill.earlierDueDates.sorted().joinToString(","))
        put("tracked_bill_id", bill.trackedBillId)
        put("dismissed", if (bill.dismissed) 1 else 0)
    }

    private fun Cursor.readUtilityBill() = UtilitySmsBill(
        id = getLong(0),
        kind = UtilityBillKind.valueOf(getString(1)),
        providerKey = getString(2),
        providerName = getString(3),
        accountKey = getString(4),
        accountLastFour = getString(5),
        referenceKey = getString(6),
        billingPeriod = getString(7),
        amountMinor = getLong(8),
        dueDateEpochDay = getLong(9),
        detectedAt = getLong(10),
        noticeKeys = getString(11).split(',').filter(String::isNotBlank).toSet(),
        earlierDueDates = getString(12).split(',').mapNotNull(String::toLongOrNull).toSet(),
        trackedBillId = if (isNull(13)) null else getLong(13),
        dismissed = getInt(14) != 0,
    )

    private const val TABLE = "utility_sms_bills"
    private val columns = arrayOf(
        "id", "kind", "provider_key", "provider_name", "account_key", "account_last_four", "reference_key",
        "billing_period", "amount_minor", "due_date_epoch_day", "detected_at", "notice_keys", "earlier_due_dates",
        "tracked_bill_id", "dismissed",
    )
    private val hash = Regex("[a-f0-9]{64}")
    private val lastFour = Regex("[0-9]{4}")
    private val providerKey = Regex("[a-z0-9-]{2,32}")
    private val period = Regex("[0-9]{4}-(?:0[1-9]|1[0-2])")
    private val validEpochDays = 1L..365_241L
}
