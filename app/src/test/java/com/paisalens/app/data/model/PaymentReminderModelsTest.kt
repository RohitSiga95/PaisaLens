package com.paisalens.app.data.model

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PaymentReminderModelsTest {
    private val zone = ZoneId.of("Asia/Kolkata")
    private val today = LocalDate.of(2026, 8, 26)

    @Test
    fun `rule normalization is bounded private and target typed`() {
        val rule = PaymentReminderRule(
            targetType = PaymentReminderTargetType.MANUAL_BILL,
            targetKey = manualBillReminderTargetKey(7),
            alertTimeMinutes = 9_999,
            zoneId = "not/a-zone",
            advanceDays = listOf(-1, 7, 7, 4_000, 0),
            overdueRepeatDays = 99,
            snoozeMinutes = listOf(0, 60, 60, 99_999),
            showAmount = false,
            colorHex = "bad",
        ).normalized(zone)

        assertEquals(1_439, rule.alertTimeMinutes)
        assertEquals(zone.id, rule.zoneId)
        assertEquals(listOf(7, 0), rule.advanceDays)
        assertEquals(30, rule.overdueRepeatDays)
        assertEquals(listOf(60), rule.snoozeMinutes)
        assertFalse(rule.showAmount)
        assertEquals(DEFAULT_PAYMENT_REMINDER_COLOR, rule.colorHex)
        assertTrue(creditCardReminderTargetKey("card-account:9").startsWith("credit-card:"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `rule rejects a target key from another bill type`() {
        PaymentReminderRule(
            targetType = PaymentReminderTargetType.CREDIT_CARD,
            targetKey = manualBillReminderTargetKey(1),
        ).normalized()
    }

    @Test
    fun `manual bill recurrence preserves end of month anchor`() {
        val bill = BillReminder(
            id = 3,
            title = "Electricity",
            amountMinor = 1_200_00,
            dueDateEpochDay = LocalDate.of(2026, 1, 31).toEpochDay(),
            recurrenceMonths = 1,
            lastPaidEpochDay = LocalDate.of(2026, 1, 31).toEpochDay(),
        )

        val target = buildPaymentReminderTargets(
            manualBills = listOf(bill),
            creditCardBills = emptyList(),
            today = LocalDate.of(2026, 2, 1),
        ).single()

        assertEquals(LocalDate.of(2026, 2, 28).toEpochDay(), target.dueDateEpochDay)
        assertEquals(manualBillReminderTargetKey(3), target.targetKey)
    }

    @Test
    fun `credit card target follows stable group across statement cycles`() {
        val older = cardBill(1, LocalDate.of(2026, 7, 20), 1_000_00)
        val current = cardBill(2, LocalDate.of(2026, 8, 20), 2_000_00)

        val target = buildPaymentReminderTargets(emptyList(), listOf(older, current), today).single()

        assertEquals(creditCardReminderTargetKey(current.creditCardBillGroupKey), target.targetKey)
        assertEquals(current.totalDueMinor, target.amountMinor)
        assertEquals(current.dueDateEpochDay, target.dueDateEpochDay)
    }

    @Test
    fun `credit card target uses the canonical user renamed account`() {
        val card = cardBill(3, today.plusDays(4), 4_200_00)

        val target = buildPaymentReminderTargets(
            emptyList(),
            listOf(card),
            today,
            accountNamesById = mapOf(5L to "Family rewards card"),
        ).single()

        assertEquals("Family rewards card", target.title)
        assertEquals("Family rewards card", target.accountName)
    }

    @Test
    fun `skip next advances one recurring manual cycle but waits for next card statement`() {
        val bill = BillReminder(
            id = 4,
            title = "Water",
            amountMinor = 800_00,
            dueDateEpochDay = LocalDate.of(2026, 8, 31).toEpochDay(),
            recurrenceMonths = 1,
        )
        val manualTarget = buildPaymentReminderTargets(listOf(bill), emptyList(), today).single()
        val manualRule = rule(
            id = 10,
            targetType = PaymentReminderTargetType.MANUAL_BILL,
            targetKey = manualTarget.targetKey,
        ).copy(skippedOccurrenceEpochDay = manualTarget.dueDateEpochDay)
        val manualOccurrence = resolvePaymentReminderOccurrences(
            listOf(manualRule),
            listOf(manualTarget),
            listOf(bill),
            today.atStartOfDay(zone).toInstant().toEpochMilli(),
            zone,
        ).single()
        assertEquals(LocalDate.of(2026, 9, 30).toEpochDay(), manualOccurrence.dueDateEpochDay)
        assertEquals(PaymentReminderStatus.UPCOMING, manualOccurrence.status)
        val secondSkip = resolvePaymentReminderOccurrences(
            listOf(manualRule.copy(skippedOccurrenceEpochDay = manualOccurrence.dueDateEpochDay)),
            listOf(manualTarget),
            listOf(bill),
            today.atStartOfDay(zone).toInstant().toEpochMilli(),
            zone,
        ).single()
        assertEquals(LocalDate.of(2026, 10, 31).toEpochDay(), secondSkip.dueDateEpochDay)

        val card = cardBill(11, LocalDate.of(2026, 8, 30), 5_000_00)
        val cardTarget = buildPaymentReminderTargets(emptyList(), listOf(card), today).single()
        val cardRule = rule(
            id = 11,
            targetType = PaymentReminderTargetType.CREDIT_CARD,
            targetKey = cardTarget.targetKey,
        ).copy(skippedOccurrenceEpochDay = cardTarget.dueDateEpochDay)
        val cardOccurrence = resolvePaymentReminderOccurrences(
            listOf(cardRule),
            listOf(cardTarget),
            emptyList(),
            today.atStartOfDay(zone).toInstant().toEpochMilli(),
            zone,
        ).single()
        assertEquals(PaymentReminderStatus.SKIPPED, cardOccurrence.status)
        assertTrue(
            buildPaymentReminderAlerts(
                cardRule,
                cardOccurrence,
                PaymentReminderSettings(),
                today.atStartOfDay(zone).toInstant().toEpochMilli(),
                today.plusDays(30).atStartOfDay(zone).toInstant().toEpochMilli(),
                zone,
            ).isEmpty(),
        )
    }

    @Test
    fun `multiple advance days produce independent stable alerts`() {
        val due = today.plusDays(10)
        val rule = rule(id = 20).copy(advanceDays = listOf(7, 3, 1, 0, 3), generation = 8)
        val occurrence = occurrence(rule, due)
        val from = today.atStartOfDay(zone).toInstant().toEpochMilli()
        val through = due.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()

        val alerts = buildPaymentReminderAlerts(rule, occurrence, PaymentReminderSettings(), from, through, zone)

        assertEquals(listOf(7, 3, 1, 0), alerts.map { it.advanceDays })
        assertEquals(4, alerts.map { it.stableKey }.distinct().size)
        val editedKeys = buildPaymentReminderAlerts(
            rule.copy(generation = 9),
            occurrence,
            PaymentReminderSettings(),
            from,
            through,
            zone,
        ).map { it.stableKey }
        assertEquals(alerts.map { it.stableKey }, editedKeys)
    }

    @Test
    fun `active snooze suppresses ordinary and overdue alerts`() {
        val now = today.atTime(12, 0).atZone(zone).toInstant().toEpochMilli()
        val snoozedUntil = now + 60 * 60_000L
        val rule = rule(id = 21).copy(
            advanceDays = listOf(7, 1, 0),
            snoozedOccurrenceEpochDay = today.minusDays(1).toEpochDay(),
            snoozedUntilMillis = snoozedUntil,
        )
        val occurrence = occurrence(rule, today.minusDays(1))

        val alerts = buildPaymentReminderAlerts(
            rule,
            occurrence,
            PaymentReminderSettings(),
            now - 24 * 60 * 60_000L,
            now + 2 * 60 * 60_000L,
            zone,
        )

        assertEquals(1, alerts.size)
        assertTrue(alerts.single().isSnooze)
        assertEquals(snoozedUntil, alerts.single().scheduledAtMillis)
    }

    @Test
    fun `quiet hours defer daytime overnight and DST gap without changing due occurrence`() {
        val daytime = PaymentReminderSettings(
            quietHoursEnabled = true,
            quietHoursStartMinutes = 13 * 60,
            quietHoursEndMinutes = 15 * 60,
        )
        val daytimeAt = today.atTime(14, 0).atZone(zone).toInstant().toEpochMilli()
        assertEquals(
            today.atTime(15, 0).atZone(zone).toInstant().toEpochMilli(),
            deferOutOfQuietHours(daytimeAt, zone, daytime),
        )

        val overnight = PaymentReminderSettings(quietHoursEnabled = true)
        val nightAt = today.atTime(23, 30).atZone(zone).toInstant().toEpochMilli()
        assertEquals(
            today.plusDays(1).atTime(7, 0).atZone(zone).toInstant().toEpochMilli(),
            deferOutOfQuietHours(nightAt, zone, overnight),
        )

        val losAngeles = ZoneId.of("America/Los_Angeles")
        val dstGapResolved = LocalDate.of(2026, 3, 8).atTime(2, 30).atZone(losAngeles)
        val dstSettings = PaymentReminderSettings(
            quietHoursEnabled = true,
            quietHoursStartMinutes = 2 * 60,
            quietHoursEndMinutes = 7 * 60,
        )
        val deferred = Instant.ofEpochMilli(
            deferOutOfQuietHours(dstGapResolved.toInstant().toEpochMilli(), losAngeles, dstSettings),
        ).atZone(losAngeles)
        assertEquals(7, deferred.hour)
        assertEquals(LocalDate.of(2026, 3, 8), deferred.toLocalDate())
    }

    @Test
    fun `duplicate lead configuration collapses and summary respects quiet hours`() {
        val due = today.plusDays(1)
        val rule = rule(id = 22).copy(advanceDays = listOf(0, 0, 0))
        val occurrence = occurrence(rule, due)
        val alerts = buildPaymentReminderAlerts(
            rule,
            occurrence,
            PaymentReminderSettings(),
            today.atStartOfDay(zone).toInstant().toEpochMilli(),
            due.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli(),
            zone,
        )
        assertEquals(1, alerts.size)

        val settings = PaymentReminderSettings(
            dailySummaryEnabled = true,
            summaryTimeMinutes = 6 * 60,
            quietHoursEnabled = true,
            quietHoursStartMinutes = 22 * 60,
            quietHoursEndMinutes = 7 * 60,
        )
        val now = today.atTime(5, 0).atZone(zone)
        assertEquals(today.atTime(7, 0), nextPaymentReminderSummaryAt(settings, now)?.toLocalDateTime())
    }

    @Test
    fun `invalid legacy credit due date is ignored instead of crashing scheduler`() {
        val corrupt = cardBill(99, today, 1_00).copy(dueDateEpochDay = Long.MAX_VALUE)
        assertTrue(buildPaymentReminderTargets(emptyList(), listOf(corrupt), today).isEmpty())
    }

    private fun rule(
        id: Long,
        targetType: PaymentReminderTargetType = PaymentReminderTargetType.MANUAL_BILL,
        targetKey: String = manualBillReminderTargetKey(1),
    ) = PaymentReminderRule(
        id = id,
        targetType = targetType,
        targetKey = targetKey,
        zoneId = zone.id,
        alertTimeMinutes = 9 * 60,
        generation = 1,
    )

    private fun occurrence(rule: PaymentReminderRule, due: LocalDate) = PaymentReminderOccurrence(
        ruleId = rule.id,
        targetType = rule.targetType,
        targetKey = rule.targetKey,
        title = "Test bill",
        kind = rule.kind,
        amountMinor = 1_000_00,
        dueDateEpochDay = due.toEpochDay(),
        dueAtMillis = due.atTime(rule.alertTime).atZone(zone).toInstant().toEpochMilli(),
        status = when {
            due.isBefore(today) -> PaymentReminderStatus.OVERDUE
            due == today -> PaymentReminderStatus.DUE_TODAY
            else -> PaymentReminderStatus.UPCOMING
        },
        showAmount = false,
        priority = rule.priority,
        notificationStyle = rule.notificationStyle,
        vibrate = rule.vibrate,
    )

    private fun cardBill(id: Long, due: LocalDate, amountMinor: Long) = CreditCardBill(
        id = id,
        billKey = "hdfc-1234:${due.toEpochDay()}",
        sourceMessageId = "sms-$id",
        accountId = 5,
        cardIdentityKey = "hdfc-1234",
        accountHint = "1234",
        institutionName = "HDFC Bank",
        totalDueMinor = amountMinor,
        dueDateEpochDay = due.toEpochDay(),
        detectedAt = due.minusDays(20).atStartOfDay(zone).toInstant().toEpochMilli(),
        sender = "HDFCCARD",
    )
}
