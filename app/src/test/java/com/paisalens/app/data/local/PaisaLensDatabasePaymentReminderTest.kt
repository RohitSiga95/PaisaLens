package com.paisalens.app.data.local

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import com.paisalens.app.data.model.AccountMergeResult
import com.paisalens.app.data.model.AccountType
import com.paisalens.app.data.model.BillReminder
import com.paisalens.app.data.model.CreditCardBill
import com.paisalens.app.data.model.PaisaLensBackupSnapshot
import com.paisalens.app.data.model.ParsedCreditCardBill
import com.paisalens.app.data.model.PaymentReminderRule
import com.paisalens.app.data.model.PaymentReminderSettings
import com.paisalens.app.data.model.PaymentReminderTargetType
import com.paisalens.app.data.model.buildPaymentReminderAlerts
import com.paisalens.app.data.model.buildPaymentReminderTargets
import com.paisalens.app.data.model.creditCardBillGroupKey
import com.paisalens.app.data.model.creditCardReminderTargetKey
import com.paisalens.app.data.model.manualBillReminderTargetKey
import com.paisalens.app.data.model.resolvePaymentReminderOccurrences
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
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
class PaisaLensDatabasePaymentReminderTest {
    private lateinit var context: Context
    private lateinit var database: PaisaLensDatabase
    private val zone: ZoneId = ZoneId.systemDefault()

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
    fun `version twelve migrates to reminder schema with safe defaults`() {
        database.writableDatabase
        database.close()
        val legacy = SQLiteDatabase.openDatabase(
            context.getDatabasePath(DATABASE_NAME).absolutePath,
            null,
            SQLiteDatabase.OPEN_READWRITE,
        )
        legacy.execSQL("DROP TABLE payment_reminder_deliveries")
        legacy.execSQL("DROP TABLE payment_reminder_rules")
        legacy.execSQL("DROP TABLE payment_reminder_settings")
        legacy.version = 12
        legacy.close()

        database = PaisaLensDatabase(context)

        assertEquals(13, database.writableDatabase.version)
        assertEquals(PaymentReminderSettings(), database.getPaymentReminderSettings())
        assertTrue(database.getPaymentReminderRules().isEmpty())
        assertTrue(database.getPaymentReminderDeliveryKeys().isEmpty())
    }

    @Test
    fun `one rule per target updates generation and cascades with manual bill`() {
        val billId = database.upsertBill(
            BillReminder(
                title = "Electricity",
                amountMinor = 2_000_00,
                dueDateEpochDay = LocalDate.now(zone).plusDays(3).toEpochDay(),
            ),
        )
        val targetKey = manualBillReminderTargetKey(billId)
        val firstId = database.upsertPaymentReminderRule(
            PaymentReminderRule(
                targetType = PaymentReminderTargetType.MANUAL_BILL,
                targetKey = targetKey,
                generation = 0,
            ),
        )
        val first = database.getPaymentReminderRule(firstId)!!
        val secondId = database.upsertPaymentReminderRule(
            first.copy(
                advanceDays = listOf(5, 0),
            ),
        )
        val second = database.getPaymentReminderRule(secondId)!!

        assertEquals(firstId, secondId)
        assertEquals(1, database.getPaymentReminderRules().size)
        assertTrue(second.generation > first.generation)
        assertEquals(listOf(5, 0), second.advanceDays)
        assertTrue(
            runCatching { database.upsertPaymentReminderRule(first.copy(notes = "stale editor")) }.isFailure,
        )
        assertTrue(
            database.snoozePaymentReminder(
                ruleId = second.id,
                occurrenceEpochDay = LocalDate.now(zone).plusDays(3).toEpochDay(),
                minutes = second.snoozeMinutes.first(),
                expectedGeneration = second.generation,
            ),
        )
        assertFalse(
            database.skipNextPaymentReminder(
                second.id,
                LocalDate.now(zone).plusDays(3).toEpochDay(),
                expectedGeneration = second.generation,
            ),
        )

        database.deleteBill(billId)
        assertNull(database.getPaymentReminderRule(firstId))
    }

    @Test
    fun `assigning card migrates and conflict merges reminder target atomically`() {
        val accountId = database.addAccount("Daily card", AccountType.CREDIT_CARD, null)
        database.upsertCreditCardBills(
            listOf(parsedCardBill("unknown", "card:issuer:unidentified:one", LocalDate.now(zone).plusDays(8))),
        )
        val bill = database.getCreditCardBills().single()
        val oldTarget = creditCardReminderTargetKey(bill.creditCardBillGroupKey)
        val newTarget = creditCardReminderTargetKey("card-account:$accountId")
        database.upsertPaymentReminderRule(
            PaymentReminderRule(
                targetType = PaymentReminderTargetType.CREDIT_CARD,
                targetKey = oldTarget,
                advanceDays = listOf(7, 0),
                showAmount = true,
            ),
        )
        database.upsertPaymentReminderRule(
            PaymentReminderRule(
                targetType = PaymentReminderTargetType.CREDIT_CARD,
                targetKey = newTarget,
                advanceDays = listOf(3, 0),
                enabled = false,
                showAmount = false,
            ),
        )

        assertTrue(database.assignCreditCardBillToAccount(bill.id, accountId))

        val merged = database.getPaymentReminderRules().single()
        assertEquals(newTarget, merged.targetKey)
        assertTrue(merged.enabled)
        assertEquals(listOf(7, 3, 0), merged.advanceDays)
        assertFalse(merged.showAmount)
    }

    @Test
    fun `merged no-hint card backup restores canonical target`() {
        val first = database.addAccount("First", AccountType.CREDIT_CARD, null)
        val second = database.addAccount("Second", AccountType.CREDIT_CARD, null)
        database.upsertCreditCardBills(
            listOf(parsedCardBill("no-hint", "card:issuer:unidentified:restore", LocalDate.now(zone).plusDays(9))),
        )
        val bill = database.getCreditCardBills().single()
        assertTrue(database.assignCreditCardBillToAccount(bill.id, second))
        database.upsertPaymentReminderRule(
            PaymentReminderRule(
                targetType = PaymentReminderTargetType.CREDIT_CARD,
                targetKey = creditCardReminderTargetKey("card-account:$second"),
            ),
        )
        val canonical = (database.mergeAccounts(listOf(first, second), "All cards") as AccountMergeResult.Success)
            .canonicalAccountId
        val snapshot = database.snapshot().let { current ->
            // Simulate a v10 backup captured before merge-time reminder remapping was fixed.
            current.copy(
                paymentReminderRules = current.paymentReminderRules.map { rule ->
                    rule.copy(targetKey = creditCardReminderTargetKey("card-account:$second"))
                },
            )
        }

        database.restore(snapshot)

        val restored = database.getPaymentReminderRules().single()
        assertEquals(creditCardReminderTargetKey("card-account:$canonical"), restored.targetKey)
        assertEquals(canonical, database.getCreditCardBills().single().accountId)
    }

    @Test
    fun `deleting a card account migrates reminder to surviving bill identity`() {
        val accountId = database.addAccount("Temporary card", AccountType.CREDIT_CARD, null)
        val account = database.snapshot().accounts.single { it.id == accountId }
        val dueDay = LocalDate.now(zone).plusDays(6).toEpochDay()
        val bill = CreditCardBill(
            id = 1,
            billKey = "card:issuer:legacy:$dueDay",
            sourceMessageId = "legacy-card-bill",
            accountId = accountId,
            cardIdentityKey = "card:issuer:legacy",
            institutionName = "Issuer",
            totalDueMinor = 4_500_00,
            dueDateEpochDay = dueDay,
            detectedAt = System.currentTimeMillis(),
            sender = "ISSUER",
        )
        database.restore(
            emptySnapshot(System.currentTimeMillis()).copy(
                accounts = listOf(account),
                creditCardBills = listOf(bill),
                paymentReminderRules = listOf(
                    PaymentReminderRule(
                        id = 1,
                        targetType = PaymentReminderTargetType.CREDIT_CARD,
                        targetKey = creditCardReminderTargetKey("card-account:$accountId"),
                        generation = 1,
                        createdAt = 1,
                        updatedAt = 1,
                    ),
                ),
            ),
        )

        database.deleteAccount(accountId)

        assertTrue(database.getAccounts().isEmpty())
        assertEquals(null, database.getCreditCardBills().single().accountId)
        assertEquals(
            creditCardReminderTargetKey("card:issuer:legacy"),
            database.getPaymentReminderRules().single().targetKey,
        )
    }

    @Test
    fun `restore watermarks past alerts while preserving future and snooze policy even when paused`() {
        val now = System.currentTimeMillis()
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        val pastLeadBill = BillReminder(
            id = 1,
            title = "Rent",
            amountMinor = 20_000_00,
            dueDateEpochDay = today.plusDays(2).toEpochDay(),
        )
        val expiredSnoozeBill = BillReminder(
            id = 2,
            title = "Gas",
            amountMinor = 900_00,
            dueDateEpochDay = today.plusDays(1).toEpochDay(),
        )
        val futureSnoozeBill = BillReminder(
            id = 3,
            title = "Water",
            amountMinor = 700_00,
            dueDateEpochDay = today.plusDays(1).toEpochDay(),
        )
        val rules = listOf(
            PaymentReminderRule(
                id = 1,
                targetType = PaymentReminderTargetType.MANUAL_BILL,
                targetKey = manualBillReminderTargetKey(1),
                alertTimeMinutes = 9 * 60,
                zoneId = zone.id,
                advanceDays = listOf(7, 0),
                generation = 2,
                createdAt = now - 10_000,
                updatedAt = now - 5_000,
            ),
            PaymentReminderRule(
                id = 2,
                targetType = PaymentReminderTargetType.MANUAL_BILL,
                targetKey = manualBillReminderTargetKey(2),
                zoneId = zone.id,
                snoozedOccurrenceEpochDay = expiredSnoozeBill.dueDateEpochDay,
                snoozedUntilMillis = now - 1_000,
                generation = 2,
                createdAt = now - 10_000,
                updatedAt = now - 5_000,
            ),
            PaymentReminderRule(
                id = 3,
                targetType = PaymentReminderTargetType.MANUAL_BILL,
                targetKey = manualBillReminderTargetKey(3),
                zoneId = zone.id,
                snoozedOccurrenceEpochDay = futureSnoozeBill.dueDateEpochDay,
                snoozedUntilMillis = now + 60 * 60_000L,
                generation = 2,
                createdAt = now - 10_000,
                updatedAt = now - 5_000,
            ),
        )
        database.restore(
            emptySnapshot(now).copy(
                bills = listOf(pastLeadBill, expiredSnoozeBill, futureSnoozeBill),
                paymentReminderRules = rules,
                paymentReminderSettings = PaymentReminderSettings(enabled = false),
            ),
        )

        val restoredRules = database.getPaymentReminderRules().associateBy(PaymentReminderRule::id)
        assertNull(restoredRules.getValue(2).snoozedUntilMillis)
        assertEquals(now + 60 * 60_000L, restoredRules.getValue(3).snoozedUntilMillis)

        val targets = buildPaymentReminderTargets(database.getBills(), emptyList(), today)
        val occurrences = resolvePaymentReminderOccurrences(
            restoredRules.values.toList(),
            targets,
            database.getBills(),
            now,
            zone,
        )
        val firstOccurrence = occurrences.single { it.ruleId == 1L }
        val firstRule = restoredRules.getValue(1)
        val allAlerts = buildPaymentReminderAlerts(
            firstRule,
            firstOccurrence,
            PaymentReminderSettings(enabled = true),
            now - 31L * 24L * 60L * 60L * 1_000L,
            now + 8L * 24L * 60L * 60L * 1_000L,
            zone,
        )
        val delivered = database.getPaymentReminderDeliveryKeys()
        assertTrue(allAlerts.filter { it.scheduledAtMillis <= now }.all { it.stableKey in delivered })
        assertTrue(allAlerts.filter { it.scheduledAtMillis > now }.all { it.stableKey !in delivered })
    }

    private fun parsedCardBill(id: String, identity: String, dueDate: LocalDate) = ParsedCreditCardBill(
        sourceMessageId = id,
        cardIdentityKey = identity,
        accountHint = null,
        institutionName = "Issuer",
        totalDueMinor = 5_000_00,
        dueDateEpochDay = dueDate.toEpochDay(),
        detectedAt = System.currentTimeMillis(),
        sender = "ISSUER",
        rawMessage = "Statement due",
    )

    private fun emptySnapshot(createdAt: Long) = PaisaLensBackupSnapshot(
        createdAt = createdAt,
        transactions = emptyList(),
        budgets = emptyList(),
        accounts = emptyList(),
        customCategories = emptyList(),
        merchantRules = emptyList(),
    )

    private companion object {
        const val DATABASE_NAME = "paisalens.db"
    }
}
