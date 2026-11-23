package com.paisalens.app.notification

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import com.paisalens.app.data.local.PaisaLensDatabase
import com.paisalens.app.data.model.AttentionAction
import com.paisalens.app.data.model.AttentionItem
import com.paisalens.app.data.model.AttentionKind
import com.paisalens.app.data.model.AttentionPriority
import com.paisalens.app.data.model.BillReminder
import com.paisalens.app.data.model.CreditCardBill
import com.paisalens.app.data.model.PaymentReminderKind
import com.paisalens.app.data.model.PaymentReminderAlert
import com.paisalens.app.data.model.PaymentReminderDelivery
import com.paisalens.app.data.model.PaymentReminderNotificationStyle
import com.paisalens.app.data.model.PaymentReminderOccurrence
import com.paisalens.app.data.model.PaymentReminderPriority
import com.paisalens.app.data.model.PaymentReminderRule
import com.paisalens.app.data.model.PaymentReminderSettings
import com.paisalens.app.data.model.PaymentReminderStatus
import com.paisalens.app.data.model.PaymentReminderTargetType
import com.paisalens.app.data.model.creditCardBillGroupKey
import com.paisalens.app.data.model.creditCardReminderTargetKey
import com.paisalens.app.data.model.manualBillReminderTargetKey
import java.time.LocalDate
import java.time.ZoneId
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
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
@Config(sdk = [32])
class PaymentReminderSchedulerTest {
    private lateinit var context: Context
    private lateinit var database: PaisaLensDatabase
    private val zone = ZoneId.of("Asia/Kolkata")
    private val today = LocalDate.of(2026, 8, 26)

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        PaymentReminderScheduler.cancel(context)
        PaymentReminderNotifier.cancelAll(context)
        context.deleteDatabase(DATABASE_NAME)
        database = PaisaLensDatabase(context)
    }

    @After
    fun tearDown() {
        PaymentReminderScheduler.cancel(context)
        PaymentReminderNotifier.cancelAll(context)
        database.close()
        context.deleteDatabase(DATABASE_NAME)
    }

    @Test
    fun `scheduler selects future then catch-up and delivered key suppresses repeats`() {
        val rule = rule(overdueRepeatDays = 0)
        val occurrence = occurrence(rule, today)
        val before = today.atTime(8, 0).atZone(zone).toInstant().toEpochMilli()
        val scheduled = today.atTime(9, 0).atZone(zone).toInstant().toEpochMilli()

        val future = selectNextPaymentReminderAlarm(
            listOf(rule), listOf(occurrence), PaymentReminderSettings(), emptySet(),
            before, Long.MIN_VALUE, zone,
        )
        assertEquals(scheduled, future?.alarmAtMillis)
        assertTrue(requireNotNull(future).logicalKey.startsWith("alert:"))
        assertEquals(PaymentReminderDeliveryPreview(scheduled, occurrence.notificationStyle, occurrence.vibrate), future.deliveryPreview)

        val after = scheduled + 60_000L
        val catchUp = selectNextPaymentReminderAlarm(
            listOf(rule), listOf(occurrence), PaymentReminderSettings(), emptySet(),
            after, Long.MIN_VALUE, zone,
        )
        assertEquals(after + 1_000L, catchUp?.alarmAtMillis)
        assertTrue(requireNotNull(catchUp).logicalKey.startsWith("catch-up:"))

        val dueKey = duePaymentReminderAlertGroups(
            PaymentReminderRuntime(listOf(rule), PaymentReminderSettings(), listOf(occurrence), emptySet()),
            after,
            zone,
        ).single().single().stableKey
        val suppressed = selectNextPaymentReminderAlarm(
            listOf(rule), listOf(occurrence), PaymentReminderSettings(), setOf(dueKey),
            after, Long.MIN_VALUE, zone,
        )
        assertTrue(requireNotNull(suppressed).logicalKey.startsWith("maintenance:"))
        assertNull(suppressed.deliveryPreview)
    }

    @Test
    fun `test sends a generic notification on the chosen channel without a delivery record`() {
        val manager = requireNotNull(context.getSystemService(NotificationManager::class.java))
        assertEquals(
            ReminderChannelStatus.AVAILABLE,
            PaymentReminderNotifier.postTest(context, PaymentReminderNotificationStyle.SILENT, vibrate = false),
        )
        val post = manager.activeNotifications.single { it.tag == "payment-reminder-test" }.notification
        assertEquals(PaymentReminderNotifier.CHANNEL_SILENT, post.channelId)
        assertEquals("PaisaLens test reminder", post.extras.getCharSequence(android.app.Notification.EXTRA_TITLE))
        assertTrue(post.actions.isNullOrEmpty())
        assertTrue(database.getPaymentReminderDeliveryKeys().isEmpty())
    }

    @Test
    fun `test refuses to post when the selected channel is blocked`() {
        val manager = requireNotNull(context.getSystemService(NotificationManager::class.java))
        manager.createNotificationChannel(NotificationChannel(
            PaymentReminderNotifier.CHANNEL_PROMINENT_NO_VIBRATE, "Blocked", NotificationManager.IMPORTANCE_NONE,
        ))
        assertEquals(
            ReminderChannelStatus.CHANNEL_BLOCKED,
            PaymentReminderNotifier.postTest(context, PaymentReminderNotificationStyle.PROMINENT, vibrate = false),
        )
        assertTrue(manager.activeNotifications.none { it.tag == "payment-reminder-test" })
    }
    @Test
    fun `editing a rule does not resurrect delivered leads as catch-up`() {
        val firstGeneration = rule(overdueRepeatDays = 0).copy(generation = 1)
        val occurrence = occurrence(firstGeneration, today)
        val after = today.atTime(9, 5).atZone(zone).toInstant().toEpochMilli()
        val deliveredKey = duePaymentReminderAlertGroups(
            PaymentReminderRuntime(
                listOf(firstGeneration),
                PaymentReminderSettings(),
                listOf(occurrence),
                emptySet(),
            ),
            after,
            zone,
        ).single().single().stableKey

        val edited = firstGeneration.copy(generation = 2, notes = "Edited safely")
        val selected = selectNextPaymentReminderAlarm(
            listOf(edited),
            listOf(occurrence),
            PaymentReminderSettings(),
            setOf(deliveredKey),
            after,
            Long.MIN_VALUE,
            zone,
        )

        assertTrue(requireNotNull(selected).logicalKey.startsWith("maintenance:"))
    }

    @Test
    fun `live claim survives refresh after alert becomes catch-up`() {
        val billId = database.upsertBill(
            BillReminder(
                title = "Power",
                amountMinor = 1_000_00,
                dueDateEpochDay = today.toEpochDay(),
            ),
        )
        database.upsertPaymentReminderRule(
            rule(targetKey = manualBillReminderTargetKey(billId), overdueRepeatDays = 0).copy(id = 0),
        )
        val before = today.atTime(8, 0).atZone(zone).toInstant().toEpochMilli()
        PaymentReminderScheduler.sync(context, before)
        val state = scheduleState()
        val token = state.getLong("token", 0L)
        val claimAt = today.atTime(9, 1).atZone(zone).toInstant().toEpochMilli()
        val claim = PaymentReminderScheduler.claimDelivery(context, token, claimAt)
        assertNotNull(claim)

        PaymentReminderScheduler.sync(
            context,
            today.atTime(9, 5).atZone(zone).toInstant().toEpochMilli(),
        )

        assertTrue(PaymentReminderScheduler.isClaimCurrent(context, requireNotNull(claim)))
        assertEquals(token, state.getLong("token", 0L))
    }

    @Test
    fun `timeout reclaim fences the stale worker with a new claim nonce`() {
        val billId = database.upsertBill(
            BillReminder(title = "Gas", amountMinor = 800_00, dueDateEpochDay = today.toEpochDay()),
        )
        database.upsertPaymentReminderRule(rule(targetKey = manualBillReminderTargetKey(billId)).copy(id = 0))
        PaymentReminderScheduler.sync(context, today.atTime(8, 0).atZone(zone).toInstant().toEpochMilli())
        val state = scheduleState()
        val token = state.getLong("token", 0L)
        val claimAt = today.atTime(9, 1).atZone(zone).toInstant().toEpochMilli()
        val first = requireNotNull(PaymentReminderScheduler.claimDelivery(context, token, claimAt))
        state.edit().putLong("claimed_at", claimAt - 16 * 60_000L).commit()

        val recovery = requireNotNull(PaymentReminderScheduler.claimDelivery(context, token, claimAt))

        assertNotEquals(first.claimNonce, recovery.claimNonce)
        assertFalse(PaymentReminderScheduler.isClaimCurrent(context, first))
        assertTrue(PaymentReminderScheduler.isClaimCurrent(context, recovery))
    }

    @Test
    fun `restore scheduling reset fences an in-flight old dataset claim`() {
        val billId = database.upsertBill(
            BillReminder(title = "Internet", amountMinor = 1_200_00, dueDateEpochDay = today.toEpochDay()),
        )
        database.upsertPaymentReminderRule(
            rule(targetKey = manualBillReminderTargetKey(billId)).copy(id = 0),
        )
        val before = today.atTime(8, 0).atZone(zone).toInstant().toEpochMilli()
        PaymentReminderScheduler.sync(context, before)
        val token = scheduleState().getLong("token", 0L)
        val claim = requireNotNull(
            PaymentReminderScheduler.claimDelivery(
                context,
                token,
                today.atTime(9, 1).atZone(zone).toInstant().toEpochMilli(),
            ),
        )

        PaymentReminderScheduler.cancel(context)

        var stalePostInvoked = false
        assertFalse(PaymentReminderScheduler.isClaimCurrent(context, claim))
        assertNull(
            PaymentReminderScheduler.withCurrentClaim(context, claim) {
                stalePostInvoked = true
            },
        )
        assertFalse(stalePostInvoked)
    }

    @Test
    fun `specific vibration channel can be disabled without affecting sibling channel`() {
        val manager = context.getSystemService(NotificationManager::class.java)
        PaymentReminderNotifier.ensureChannels(context)
        manager.deleteNotificationChannel(PaymentReminderNotifier.CHANNEL_STANDARD_VIBRATE)
        manager.createNotificationChannel(
            NotificationChannel(
                PaymentReminderNotifier.CHANNEL_STANDARD_VIBRATE,
                "Disabled test channel",
                NotificationManager.IMPORTANCE_NONE,
            ),
        )

        assertFalse(
            PaymentReminderNotifier.canPost(
                context,
                PaymentReminderNotificationStyle.STANDARD,
                vibrate = true,
            ),
        )
        assertTrue(
            PaymentReminderNotifier.canPost(
                context,
                PaymentReminderNotificationStyle.STANDARD,
                vibrate = false,
            ),
        )
    }

    @Test
    fun `clock rollback invalidates a wall-clock delivery claim`() {
        val token = 42L
        val now = 1_000_000L

        assertTrue(isUnexpiredPaymentReminderClaim(token, token, now - 1_000L, now))
        assertFalse(isUnexpiredPaymentReminderClaim(token, token, now + 1_000L, now))
        assertFalse(
            isUnexpiredPaymentReminderClaim(
                token,
                token,
                now - CLAIM_TIMEOUT_MILLIS,
                now,
            ),
        )
    }

    @Test
    fun `active notification recovery closes notify before delivery row crash gap`() {
        val rule = rule()
        val occurrence = occurrence(rule, today).copy(showAmount = true, title = "Private power bill")
        val alert = PaymentReminderAlert(
            stableKey = "payment-reminder:1:${today.toEpochDay()}:lead:0",
            occurrence = occurrence,
            scheduledAtMillis = occurrence.dueAtMillis,
            advanceDays = 0,
        )

        assertTrue(PaymentReminderNotifier.post(context, alert, rule))
        // Simulate process death before recordPaymentReminderDeliveries: Android still owns the
        // tagged notification, so recovery registers it and must not post/vibrate again.
        assertTrue(
            PaymentReminderNotifier.recoverActiveAlert(
                context,
                paymentReminderNotificationTag(alert, rule.generation),
                rule.id,
            ),
        )
    }

    @Test
    fun `notification privacy presentation never leaks bill details`() {
        val rule = rule()
        val occurrence = occurrence(rule, today).copy(
            title = "Secret HDFC card",
            amountMinor = 98_765_00,
            showAmount = true,
            notes = "Sensitive note",
        )
        val alert = PaymentReminderAlert(
            stableKey = "payment-reminder:1:${today.toEpochDay()}:lead:0",
            occurrence = occurrence,
            scheduledAtMillis = occurrence.dueAtMillis,
            advanceDays = 0,
        )

        val hidden = buildPaymentReminderNotificationContent(alert, sensitiveDetailsVisible = false)
        val hiddenSummary = buildPaymentReminderSummaryContent(listOf(occurrence), sensitiveDetailsVisible = false)
        val concealed = listOf(hidden.title, hidden.text, hiddenSummary.title, hiddenSummary.text) + hiddenSummary.lines
        assertTrue(concealed.none { "Secret" in it || "98765" in it || today.toString() in it || "1 bill" in it })
        assertTrue(hiddenSummary.lines.isEmpty())

        val visible = buildPaymentReminderNotificationContent(alert, sensitiveDetailsVisible = true)
        assertTrue("Secret HDFC card" in visible.text)
        assertTrue(today.toString() in visible.text)
        val usd = buildPaymentReminderNotificationContent(
            alert,
            sensitiveDetailsVisible = true,
            currencyCode = "USD",
        )
        assertTrue("98,765" in usd.text)
        assertFalse("₹" in usd.text)

        assertFalse(paymentReminderSensitiveDetailsVisible(true, false, null, false))
        assertFalse(paymentReminderSensitiveDetailsVisible(false, true, null, true))
        assertFalse(paymentReminderSensitiveDetailsVisible(false, false, true, true))
        assertFalse(paymentReminderSensitiveDetailsVisible(false, true, false, false))
    }

    @Test
    fun `notification reconciliation keeps only current generation and occurrence`() {
        val rule = rule().copy(generation = 7)
        val occurrence = occurrence(rule, today)
        val prefix = activePaymentReminderNotificationPrefixes(listOf(rule), listOf(occurrence)).getValue(rule.id)
        val currentAlert = PaymentReminderAlert(
            "payment-reminder:1:${today.toEpochDay()}:lead:0",
            occurrence,
            occurrence.dueAtMillis,
            advanceDays = 0,
        )
        val current = paymentReminderNotificationTag(currentAlert, 7)
        val staleGeneration = paymentReminderNotificationTag(currentAlert, 6)
        val staleOccurrence = occurrence(rule, today.minusMonths(1))
        val staleCycle = paymentReminderNotificationTag(
            PaymentReminderAlert(
                "payment-reminder:1:${today.minusMonths(1).toEpochDay()}:lead:0",
                staleOccurrence,
                staleOccurrence.dueAtMillis,
                advanceDays = 0,
            ),
            7,
        )

        assertEquals(
            setOf(current),
            retainCurrentPaymentReminderNotificationTags(
                setOf(current, staleGeneration, staleCycle),
                prefix,
            ),
        )
        assertTrue(
            retainCurrentPaymentReminderNotificationTags(setOf(staleGeneration), activePrefix = null).isEmpty(),
        )
    }

    @Test
    fun `notification reconciliation cancels old card cycle and deleted rule tags`() {
        val rule = rule().copy(generation = 7)
        val currentOccurrence = occurrence(rule, today)
        val oldOccurrence = occurrence(rule, today.minusMonths(1))
        val currentAlert = PaymentReminderAlert(
            "payment-reminder:1:${today.toEpochDay()}:lead:0",
            currentOccurrence,
            currentOccurrence.dueAtMillis,
            advanceDays = 0,
        )
        val oldAlert = PaymentReminderAlert(
            "payment-reminder:1:${today.minusMonths(1).toEpochDay()}:lead:0",
            oldOccurrence,
            oldOccurrence.dueAtMillis,
            advanceDays = 0,
        )
        val currentTag = paymentReminderNotificationTag(currentAlert, rule.generation)
        val oldTag = paymentReminderNotificationTag(oldAlert, rule.generation)
        assertTrue(
            PaymentReminderNotifier.post(context, oldAlert, rule),
        )
        assertTrue(
            PaymentReminderNotifier.post(context, currentAlert, rule),
        )

        PaymentReminderNotifier.reconcileActiveNotifications(context, listOf(rule), listOf(currentOccurrence))
        val manager = context.getSystemService(NotificationManager::class.java)
        assertEquals(setOf(currentTag), manager.activeNotifications.mapNotNull { it.tag }.toSet())

        PaymentReminderNotifier.reconcileActiveNotifications(context, emptyList(), emptyList())
        assertTrue(manager.activeNotifications.isEmpty())
    }

    @Test
    fun `later lead replaces visible card while delivery identities remain independent`() {
        val billId = database.upsertBill(
            BillReminder(title = "Power", amountMinor = 1_000_00, dueDateEpochDay = today.toEpochDay()),
        )
        val ruleId = database.upsertPaymentReminderRule(
            rule(targetKey = manualBillReminderTargetKey(billId)).copy(id = 0, generation = 0),
        )
        val rule = requireNotNull(database.getPaymentReminderRule(ruleId))
        val occurrence = occurrence(rule, today)
        val firstStableKey = "payment-reminder:$ruleId:${today.toEpochDay()}:lead:7"
        val latestStableKey = "payment-reminder:$ruleId:${today.toEpochDay()}:lead:3"
        val first = PaymentReminderAlert(
            firstStableKey,
            occurrence,
            occurrence.dueAtMillis - 7 * 24L * 60L * 60L * 1_000L,
            advanceDays = 7,
        )
        val latest = PaymentReminderAlert(
            latestStableKey,
            occurrence,
            occurrence.dueAtMillis - 3 * 24L * 60L * 60L * 1_000L,
            advanceDays = 3,
        )

        assertTrue(PaymentReminderNotifier.post(context, first, rule))
        assertTrue(PaymentReminderNotifier.post(context, latest, rule))

        val manager = context.getSystemService(NotificationManager::class.java)
        val latestTag = paymentReminderNotificationTag(latest, rule.generation)
        assertEquals(setOf(latestTag), manager.activeNotifications.mapNotNull { it.tag }.toSet())
        database.recordPaymentReminderDeliveries(
            listOf(first, latest).map { alert ->
                PaymentReminderDelivery(
                    stableKey = alert.stableKey,
                    ruleId = rule.id,
                    occurrenceEpochDay = occurrence.dueDateEpochDay,
                    scheduledAtMillis = alert.scheduledAtMillis,
                    deliveredAtMillis = occurrence.dueAtMillis,
                )
            },
        )
        assertEquals(setOf(firstStableKey, latestStableKey), database.getPaymentReminderDeliveryKeys(rule.id))

        // A snooze action captured from the older card must still clear the replacement card.
        PaymentReminderNotifier.cancelForRule(context, rule.id)
        assertTrue(manager.activeNotifications.isEmpty())
    }

    @Test
    fun `delivered snooze is consumed after crash before state cleanup`() {
        val until = today.atTime(12, 0).atZone(zone).toInstant().toEpochMilli()
        val rule = rule().copy(
            snoozedOccurrenceEpochDay = today.toEpochDay(),
            snoozedUntilMillis = until,
            generation = 9,
        )
        val occurrence = occurrence(rule, today)
        val key = "payment-reminder:1:${today.toEpochDay()}:snooze:$until"

        assertEquals(
            listOf(rule to occurrence),
            deliveredPaymentReminderSnoozeStates(listOf(rule), listOf(occurrence), setOf(key)),
        )
        assertTrue(deliveredPaymentReminderSnoozeStates(listOf(rule), listOf(occurrence), emptySet()).isEmpty())
    }

    @Test
    fun `custom rules suppress only matching generic bill alerts`() {
        val card = CreditCardBill(
            id = 8,
            billKey = "hdfc:cycle",
            sourceMessageId = "sms-8",
            accountId = 4,
            cardIdentityKey = "hdfc-card",
            institutionName = "HDFC",
            totalDueMinor = 5_000_00,
            dueDateEpochDay = today.toEpochDay(),
            detectedAt = 1,
            sender = "HDFC",
        )
        val manual = attention("bill:7:${today.toEpochDay()}", AttentionKind.BILL_DUE)
        val cardAttention = attention("card-bill:8", AttentionKind.CREDIT_CARD_BILL_DUE)
        val unrelated = attention("bill:9:${today.toEpochDay()}", AttentionKind.BILL_DUE)
        val enabledRules = listOf(
            rule(targetKey = manualBillReminderTargetKey(7)),
            rule(
                targetType = PaymentReminderTargetType.CREDIT_CARD,
                targetKey = creditCardReminderTargetKey(card.creditCardBillGroupKey),
            ),
        )

        assertEquals(
            listOf(unrelated),
            suppressGenericBillAlertsWithCustomRules(
                listOf(manual, cardAttention, unrelated),
                enabledRules,
                listOf(card),
            ),
        )
        assertEquals(
            listOf(manual),
            suppressGenericBillAlertsWithCustomRules(
                listOf(manual),
                listOf(enabledRules.first().copy(enabled = false)),
                emptyList(),
            ),
        )
        assertEquals(
            listOf(manual),
            suppressGenericBillAlertsWithCustomRules(
                listOf(manual),
                listOf(enabledRules.first()),
                emptyList(),
                PaymentReminderSettings(enabled = false),
            ),
        )
    }

    private fun rule(
        targetType: PaymentReminderTargetType = PaymentReminderTargetType.MANUAL_BILL,
        targetKey: String = manualBillReminderTargetKey(1),
        overdueRepeatDays: Int = 1,
    ) = PaymentReminderRule(
        id = 1,
        targetType = targetType,
        targetKey = targetKey,
        zoneId = zone.id,
        alertTimeMinutes = 9 * 60,
        advanceDays = listOf(0),
        overdueRepeatDays = overdueRepeatDays,
        generation = 1,
    )

    private fun occurrence(rule: PaymentReminderRule, due: LocalDate) = PaymentReminderOccurrence(
        ruleId = rule.id,
        targetType = rule.targetType,
        targetKey = rule.targetKey,
        title = "Power",
        kind = PaymentReminderKind.UTILITY,
        amountMinor = 1_000_00,
        dueDateEpochDay = due.toEpochDay(),
        dueAtMillis = due.atTime(9, 0).atZone(zone).toInstant().toEpochMilli(),
        status = PaymentReminderStatus.DUE_TODAY,
        showAmount = false,
        priority = PaymentReminderPriority.HIGH,
        notificationStyle = PaymentReminderNotificationStyle.STANDARD,
        vibrate = true,
    )

    private fun attention(stableId: String, kind: AttentionKind) = AttentionItem(
        stableId = stableId,
        kind = kind,
        priority = AttentionPriority.URGENT,
        title = "Bill due",
        detail = "Due now",
        action = AttentionAction.OPEN_BILLS,
    )

    private fun scheduleState() = context.getSharedPreferences(
        "paisalens.payment_reminder_schedule",
        Context.MODE_PRIVATE,
    )

    private companion object {
        const val DATABASE_NAME = "paisalens.db"
    }
}
