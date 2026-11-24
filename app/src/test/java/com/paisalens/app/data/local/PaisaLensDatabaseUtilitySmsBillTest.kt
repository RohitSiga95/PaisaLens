package com.paisalens.app.data.local

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import com.paisalens.app.data.backup.PaisaLensBackupCodec
import com.paisalens.app.data.model.BillReminder
import com.paisalens.app.data.model.UtilitySmsBill
import com.paisalens.app.data.model.buildPaymentReminderTargets
import com.paisalens.app.data.model.manualBillReminderTargetKey
import com.paisalens.app.sms.UtilityBillSmsParser
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.time.Instant
import java.time.LocalDate
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PaisaLensDatabaseUtilitySmsBillTest {
    private lateinit var context: Context
    private lateinit var database: PaisaLensDatabase
    private val received = Instant.parse("2026-11-04T08:00:00Z").toEpochMilli()
    private val today = LocalDate.of(2026, 11, 4)

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        context.deleteDatabase("paisalens.db")
        database = PaisaLensDatabase(context)
    }

    @After
    fun tearDown() {
        database.close()
        context.deleteDatabase("paisalens.db")
    }

    @Test
    fun mergesRepeatsKeepsSeparateCyclesAndLetsOnlyAReviewedBillBecomeAReminder() {
        val original = notice()
        val repeat = notice(prefix = "Reminder: ", offset = 10_000)
        val nextCycle = notice(day = "19 Dec", month = "Dec")
        val otherAccount = notice(account = "44335566")
        assertEquals(3, database.ingestUtilitySmsBills(listOf(repeat, original, nextCycle, otherAccount)).changedBills)
        assertEquals(0, database.ingestUtilitySmsBills(listOf(repeat, original)).changedBills)
        assertTrue(database.getTransactions().isEmpty())
        assertTrue(database.getBills().isEmpty())

        val suggestion = database.getUtilitySmsBills().single { it.noticeKeys.size == 2 }
        val id = requireNotNull(database.trackUtilitySmsBill(suggestion))
        assertEquals(id, database.trackUtilitySmsBill(suggestion))
        assertEquals(manualBillReminderTargetKey(id), buildPaymentReminderTargets(
            database.getBills(), emptyList(), today,
        ).single().targetKey)
        assertEquals(0, database.getBills().single().recurrenceMonths)
        assertEquals(3, database.getUtilitySmsBills().size)
    }

    @Test
    fun keepsLatestNoticeAndUpdatesAnActiveBillWithoutUndoingEditsOrPaidState() {
        database.ingestUtilitySmsBills(listOf(notice()))
        val first = database.getUtilitySmsBills().single()
        val revision = notice(day = "22 Nov", amount = 870, offset = 60_000)
        database.ingestUtilitySmsBills(listOf(revision))
        assertNull(database.trackUtilitySmsBill(first))
        val id = requireNotNull(database.trackUtilitySmsBill(database.getUtilitySmsBills().single()))
        val updated = notice(day = "23 Nov", amount = 880, offset = 80_000)
        val oldRepeat = notice(prefix = "Reminder: ", offset = 10_000)
        assertEquals(setOf(id), database.ingestUtilitySmsBills(listOf(updated, oldRepeat)).changedTrackedIds)
        val newBill = database.getBills().single()
        assertEquals(88_000L, newBill.amountMinor)
        assertEquals(LocalDate.of(2026, 11, 23).toEpochDay(), newBill.dueDateEpochDay)

        database.upsertBill(newBill.copy(amountMinor = 50_000))
        database.ingestUtilitySmsBills(listOf(notice(day = "24 Nov", amount = 890, offset = 90_000)))
        val edited = database.getBills().single()
        assertEquals(50_000L, edited.amountMinor)
        assertEquals(LocalDate.of(2026, 11, 24).toEpochDay(), edited.dueDateEpochDay)

        database.upsertBill(edited.copy(isActive = false, lastPaidEpochDay = today.toEpochDay()))
        database.ingestUtilitySmsBills(listOf(notice(day = "26 Nov", amount = 900, offset = 100_000)))
        assertFalse(database.getBills().single().isActive)
        assertEquals(edited.dueDateEpochDay, database.getBills().single().dueDateEpochDay)
    }

    @Test
    fun onlyAnActualChangeToATrackedDueFencesReminderDelivery() {
        val first = notice()
        var fences = 0
        database.ingestUtilitySmsBills(listOf(first)) { fences++ }
        database.ingestUtilitySmsBills(listOf(first)) { fences++ }
        assertEquals(0, fences)
        val billId = requireNotNull(database.trackUtilitySmsBill(database.getUtilitySmsBills().single()))

        val repeat = notice(prefix = "Reminder: ", offset = 1_000)
        database.ingestUtilitySmsBills(listOf(repeat)) { fences++ }
        assertEquals(0, fences)
        val correction = notice(day = "21 Nov", amount = 875, offset = 2_000)
        val result = database.ingestUtilitySmsBills(listOf(correction)) { fences++ }
        assertEquals(1, fences)
        assertEquals(setOf(billId), result.changedTrackedIds)
    }

    @Test
    fun persistsLinkedBillsAndDismissalsThroughEncryptedBackupAndInboxRescan() {
        val first = notice()
        val second = notice(account = "99553322")
        val third = notice(account = "11332244")
        database.ingestUtilitySmsBills(listOf(first, second, third))
        val suggestions = database.getUtilitySmsBills().associateBy(UtilitySmsBill::accountLastFour)
        val trackedId = requireNotNull(database.trackUtilitySmsBill(suggestions.getValue("5566")))
        val removedId = requireNotNull(database.trackUtilitySmsBill(suggestions.getValue("2244")))
        assertTrue(database.dismissUtilitySmsBill(suggestions.getValue("3322").id))
        database.deleteBill(removedId)
        val snapshot = database.snapshot()
        val passphrase = "test utility backup passphrase".toCharArray()
        val encrypted = ByteArrayOutputStream().also { PaisaLensBackupCodec.write(snapshot, passphrase, it) }.toByteArray()
        assertEquals(3, PaisaLensBackupCodec.verify(passphrase, ByteArrayInputStream(encrypted)).utilitySmsBillCount)
        val restored = PaisaLensBackupCodec.read(passphrase, ByteArrayInputStream(encrypted))
        assertEquals(snapshot.utilitySmsBills, restored.utilitySmsBills)
        database.restore(restored)

        assertEquals(listOf(trackedId), database.getBills().map(BillReminder::id))
        assertEquals(0, database.ingestUtilitySmsBills(listOf(first, second, third)).changedBills)
        database.ingestUtilitySmsBills(listOf(notice(account = "99553322", prefix = "Reminder: ", offset = 1_000)))
        assertEquals(2, database.getUtilitySmsBills().count(UtilitySmsBill::dismissed))
    }

    @Test
    fun migratesVersionFourteenWithoutLosingAnExistingManualBill() {
        val manualId = database.upsertBill(BillReminder(
            title = "Water", amountMinor = 2_500, dueDateEpochDay = today.toEpochDay(),
        ))
        database.close()
        SQLiteDatabase.openDatabase(
            context.getDatabasePath("paisalens.db").absolutePath, null, SQLiteDatabase.OPEN_READWRITE,
        ).use { legacy ->
            legacy.execSQL("DROP TABLE utility_sms_bills")
            legacy.version = 14
        }
        database = PaisaLensDatabase(context)
        assertTrue(database.writableDatabase.version >= 15)
        assertEquals(manualId, database.getBills().single().id)
        assertEquals(1, database.ingestUtilitySmsBills(listOf(notice())).changedBills)
    }

    private fun notice(
        account: String = "33445566",
        day: String = "19 Nov",
        amount: Int = 850,
        month: String = "Nov",
        offset: Long = 0,
        prefix: String = "",
    ) = requireNotNull(UtilityBillSmsParser.parse(
        "VK-BESCOM",
        prefix + "Electricity bill for consumer $account. Billing period: $month 2026. Amount due Rs. $amount. Due on $day.",
        received + offset,
    ))
}
