package com.paisalens.app.data.local

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import com.paisalens.app.data.backup.PaisaLensBackupCodec
import com.paisalens.app.data.model.CreditCardPayment
import com.paisalens.app.data.model.ParsedCreditCardBill
import com.paisalens.app.data.model.buildPaymentReminderTargets
import com.paisalens.app.data.model.suggestCreditCardPayments
import com.paisalens.app.data.model.totalCurrentCreditCardDueMinor
import com.paisalens.app.sms.CreditCardPaymentSmsParser
import com.paisalens.app.widget.buildCreditCardBillsWidgetPresentation
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.time.LocalDate
import java.time.ZoneId
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PaisaLensDatabaseCreditCardPaymentTest {
    private lateinit var context: Context
    private lateinit var database: PaisaLensDatabase
    private val due = LocalDate.of(2026, 6, 20)
    private val zone = ZoneId.systemDefault()
    private val issueTime = due.minusDays(10).atStartOfDay(zone).toInstant().toEpochMilli()

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
    fun `stale suggestions are rejected and partial and full payments reach widget and reminders`() {
        database.upsertCreditCardBills(listOf(statement()))
        val partial = payment(2_000, "123456789012")
        val rest = payment(3_000, "223456789012")
        database.insertCreditCardPayments(listOf(partial, rest))
        val initial = suggestions().associateBy { it.payment.key }
        assertEquals(500_000L, totalCurrentCreditCardDueMinor(database.getCreditCardBills()))
        assertTrue(database.confirmCreditCardPayment(initial.getValue(partial.key)))
        assertFalse(database.confirmCreditCardPayment(initial.getValue(rest.key)))

        val partiallyPaid = database.getCreditCardBills()
        assertEquals(300_000L, partiallyPaid.single().remainingDueMinor)
        assertEquals(300_000L, buildCreditCardBillsWidgetPresentation(partiallyPaid, emptyList(), due).totalDueMinor)
        assertEquals(300_000L, buildPaymentReminderTargets(emptyList(), partiallyPaid, due).single().amountMinor)
        assertTrue(database.confirmCreditCardPayment(suggestions().single()))

        val paid = database.getCreditCardBills()
        assertTrue(paid.single().isPaid)
        assertEquals(0L, totalCurrentCreditCardDueMinor(paid))
        assertTrue(buildCreditCardBillsWidgetPresentation(paid, emptyList(), due).rows.isEmpty())
        assertTrue(buildPaymentReminderTargets(emptyList(), paid, due).isEmpty())
        assertTrue(database.removeCreditCardPaymentMatch(rest.key, paid.single().id))
        assertEquals(300_000L, database.getCreditCardBills().single().remainingDueMinor)
    }

    @Test
    fun `same amount notices keep the match but a new issuer due amount is not reduced twice`() {
        database.upsertCreditCardBills(listOf(statement()))
        val receipt = payment(2_000, "123456789012")
        database.insertCreditCardPayments(listOf(receipt))
        assertTrue(database.confirmCreditCardPayment(suggestions().single()))
        val later = receipt.receivedAt + 60_000L
        database.upsertCreditCardBills(listOf(statement(id = "same-amount-notice", reportedAt = later)))
        assertEquals(300_000L, database.getCreditCardBills().single().remainingDueMinor)
        database.upsertCreditCardBills(listOf(statement("revised", later + 60_000L, amount = 300_000L)))
        val revised = database.getCreditCardBills().single()
        assertEquals(300_000L, revised.remainingDueMinor)
        assertEquals(receipt.key, revised.matchedPayments.single().key)
    }

    @Test
    fun `ambiguous old and duplicate receipts do not enter the wrong cycle or count twice`() {
        database.upsertCreditCardBills(listOf(statement()))
        val noRef = payment(2_000)
        val copy = payment(2_000, at = noRef.receivedAt + 60_000L)
        val otherCard = payment(5_000, "421456789012", card = "9876")
        val old = payment(5_000, "321456789012", at = issueTime - 60_000L)
        assertEquals(3, database.insertCreditCardPayments(listOf(noRef, copy, otherCard, old)))
        assertEquals(noRef.key, suggestions().single().payment.key)
        assertTrue(database.dismissCreditCardPayment(noRef.key))
        assertEquals(0, database.insertCreditCardPayments(listOf(noRef, copy)))
        assertTrue(suggestions().isEmpty())
    }

    @Test
    fun `paid and pending matches survive an encrypted backup restore`() {
        database.upsertCreditCardBills(listOf(statement()))
        val partial = payment(2_000, "123456789012")
        val pending = payment(3_000, "223456789012")
        database.insertCreditCardPayments(listOf(partial, pending))
        assertTrue(database.confirmCreditCardPayment(suggestions().first { it.payment.key == partial.key }))
        val pass = "testing backup password"
        val bytes = ByteArrayOutputStream().also {
            PaisaLensBackupCodec.write(database.snapshot(), pass.toCharArray(), it)
        }.toByteArray()
        val metadata = PaisaLensBackupCodec.verify(pass.toCharArray(), ByteArrayInputStream(bytes))
        val restored = PaisaLensBackupCodec.read(pass.toCharArray(), ByteArrayInputStream(bytes))
        assertEquals(2, metadata.creditCardPaymentCount)

        database.restore(restored)
        assertEquals(300_000L, database.getCreditCardBills().single().remainingDueMinor)
        assertEquals(pending.key, suggestions().single().payment.key)
        assertEquals(0, database.insertCreditCardPayments(listOf(partial, pending)))
    }

    @Test
    fun `upgrading version thirteen preserves card cycles and old confirmation status`() {
        database.upsertCreditCardBills(listOf(statement()))
        val id = database.getCreditCardBills().single().id
        database.markCreditCardBillPaid(id, confirmed = true, paidAt = issueTime + 60_000L)
        database.close()
        SQLiteDatabase.openDatabase(
            context.getDatabasePath(DATABASE_NAME).absolutePath, null, SQLiteDatabase.OPEN_READWRITE,
        ).use { legacy ->
            legacy.execSQL("DROP TABLE credit_card_payments")
            legacy.execSQL("ALTER TABLE credit_card_bills DROP COLUMN due_amount_observed_at")
            legacy.version = 13
        }
        database = PaisaLensDatabase(context)
        assertTrue(database.writableDatabase.version >= 14)
        val bill = database.getCreditCardBills().single()
        assertEquals(id, bill.id)
        assertTrue(bill.isPaid)
        assertEquals(issueTime, bill.amountDueAsOf)
        assertTrue(database.getPendingCreditCardPayments().isEmpty())
    }

    private fun statement(
        id: String = "card-statement",
        reportedAt: Long = issueTime,
        amount: Long = 500_000L,
    ) = ParsedCreditCardBill(
        sourceMessageId = id,
        cardIdentityKey = "card:hdfc:1234",
        accountHint = "1234",
        institutionName = "HDFC Bank",
        totalDueMinor = amount,
        dueDateEpochDay = due.toEpochDay(),
        detectedAt = reportedAt,
        sender = "VM-HDFCBK",
        rawMessage = "",
    )

    private fun payment(
        rupees: Int,
        ref: String? = null,
        at: Long = issueTime + 24 * 60 * 60_000L,
        card: String = "1234",
    ): CreditCardPayment = requireNotNull(CreditCardPaymentSmsParser.parse(
        "VM-HDFCBK",
        "Payment of INR $rupees received for Credit Card ending $card." +
            ref?.let { " Ref no: $it." }.orEmpty(),
        at,
    ))

    private fun suggestions() = suggestCreditCardPayments(database.getPendingCreditCardPayments(), database.getCreditCardBills())

    private companion object {
        const val DATABASE_NAME = "paisalens.db"
    }
}
