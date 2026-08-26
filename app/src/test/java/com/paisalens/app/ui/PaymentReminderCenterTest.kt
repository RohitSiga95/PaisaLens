package com.paisalens.app.ui

import com.paisalens.app.data.model.PaymentReminderKind
import com.paisalens.app.data.model.PaymentReminderNotificationStyle
import com.paisalens.app.data.model.PaymentReminderOccurrence
import com.paisalens.app.data.model.PaymentReminderPriority
import com.paisalens.app.data.model.PaymentReminderRule
import com.paisalens.app.data.model.PaymentReminderStatus
import com.paisalens.app.data.model.PaymentReminderTarget
import com.paisalens.app.data.model.PaymentReminderTargetType
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Test

class PaymentReminderCenterTest {
    private val today = LocalDate.of(2026, 8, 26)
    private val utc = ZoneId.of("UTC")
    private val nowMillis = today.atStartOfDay(utc).toInstant().toEpochMilli()

    @Test
    fun `search covers bill account category kind and private notes`() {
        val electricity = rule(1, "manual-bill:1", PaymentReminderKind.UTILITY, category = "Home")
        val card = rule(2, "credit-card:hdfc", PaymentReminderKind.CREDIT_CARD, notes = "Travel card")
        val targets = listOf(
            target(electricity, "BESCOM", accountName = "Salary account"),
            target(card, "HDFC statement", accountName = "Everyday card"),
        )

        assertEquals(
            listOf(1L),
            buildPaymentReminderListItems(
                rules = listOf(electricity, card),
                targets = targets,
                occurrences = emptyList(),
                query = "home",
                filter = PaymentReminderFilter.ALL,
                nowMillis = nowMillis,
                systemZone = utc,
            ).map { it.rule.id },
        )
        assertEquals(
            listOf(2L),
            buildPaymentReminderListItems(
                rules = listOf(electricity, card),
                targets = targets,
                occurrences = emptyList(),
                query = "travel",
                filter = PaymentReminderFilter.ALL,
                nowMillis = nowMillis,
                systemZone = utc,
            ).map { it.rule.id },
        )
    }

    @Test
    fun `filters distinguish snoozed overdue today and paused reminders`() {
        val todayRule = rule(1, "manual-bill:1", PaymentReminderKind.UTILITY)
        val overdueRule = rule(2, "manual-bill:2", PaymentReminderKind.UTILITY)
        val snoozedRule = rule(3, "credit-card:3", PaymentReminderKind.CREDIT_CARD)
        val pausedRule = rule(4, "manual-bill:4", PaymentReminderKind.OTHER).copy(enabled = false)
        val occurrences = listOf(
            occurrence(todayRule, today, PaymentReminderStatus.DUE_TODAY),
            occurrence(overdueRule, today.minusDays(2), PaymentReminderStatus.OVERDUE),
            occurrence(snoozedRule, today.plusDays(2), PaymentReminderStatus.SNOOZED),
        )
        val rules = listOf(todayRule, overdueRule, snoozedRule, pausedRule)
        val targets = rules.map { target(it, "Bill ${it.id}") }

        fun ids(filter: PaymentReminderFilter) = buildPaymentReminderListItems(
            rules = rules,
            targets = targets,
            occurrences = occurrences,
            query = "",
            filter = filter,
            nowMillis = nowMillis,
            systemZone = utc,
        ).map { it.rule.id }

        assertEquals(listOf(1L), ids(PaymentReminderFilter.TODAY))
        assertEquals(listOf(2L), ids(PaymentReminderFilter.OVERDUE))
        assertEquals(listOf(3L), ids(PaymentReminderFilter.SNOOZED))
        assertEquals(listOf(4L), ids(PaymentReminderFilter.PAUSED))
    }

    @Test
    fun `month grouping sorts due months and keeps waiting rules last`() {
        val august = rule(1, "manual-bill:1", PaymentReminderKind.UTILITY)
        val september = rule(2, "manual-bill:2", PaymentReminderKind.UTILITY)
        val waiting = rule(3, "credit-card:3", PaymentReminderKind.CREDIT_CARD)
        val items = listOf(
            PaymentReminderListItem(september, target(september, "September"), occurrence(september, today.plusMonths(1), PaymentReminderStatus.UPCOMING)),
            PaymentReminderListItem(waiting, null, null),
            PaymentReminderListItem(august, target(august, "August"), occurrence(august, today, PaymentReminderStatus.DUE_TODAY)),
        )

        assertEquals(
            listOf(YearMonth.of(2026, 8), YearMonth.of(2026, 9), null),
            groupPaymentReminderItemsByMonth(items).map { it.first },
        )
    }

    @Test
    fun `new reminder targets exclude every target that already has a rule`() {
        val configured = rule(1, "manual-bill:1", PaymentReminderKind.UTILITY)
        val available = rule(2, "credit-card:2", PaymentReminderKind.CREDIT_CARD)
        val targets = listOf(target(configured, "Electricity"), target(available, "Travel card"))

        assertEquals(
            listOf(available.targetKey),
            unconfiguredPaymentReminderTargets(listOf(configured), targets).map { it.targetKey },
        )
        assertEquals(emptyList<PaymentReminderTarget>(), unconfiguredPaymentReminderTargets(listOf(configured, available), targets))
    }

    @Test
    fun `today filter uses captured reminder timezone after travel`() {
        val pacificRule = rule(1, "manual-bill:1", PaymentReminderKind.UTILITY)
            .copy(zoneId = "America/Los_Angeles")
        val instant = java.time.Instant.parse("2026-08-27T00:30:00Z").toEpochMilli()

        val result = buildPaymentReminderListItems(
            rules = listOf(pacificRule),
            targets = listOf(target(pacificRule, "Electricity")),
            occurrences = listOf(occurrence(pacificRule, LocalDate.of(2026, 8, 26), PaymentReminderStatus.DUE_TODAY)),
            query = "",
            filter = PaymentReminderFilter.TODAY,
            nowMillis = instant,
            systemZone = utc,
        )

        assertEquals(listOf(1L), result.map { it.rule.id })
    }

    private fun rule(
        id: Long,
        key: String,
        kind: PaymentReminderKind,
        category: String? = null,
        notes: String? = null,
    ) = PaymentReminderRule(
        id = id,
        targetType = if (key.startsWith("credit-card:")) PaymentReminderTargetType.CREDIT_CARD
        else PaymentReminderTargetType.MANUAL_BILL,
        targetKey = key,
        kind = kind,
        categoryLabel = category,
        notes = notes,
        zoneId = utc.id,
    )

    private fun target(rule: PaymentReminderRule, title: String, accountName: String? = null) = PaymentReminderTarget(
        targetType = rule.targetType,
        targetKey = rule.targetKey,
        title = title,
        amountMinor = 10_000,
        dueDateEpochDay = today.toEpochDay(),
        accountName = accountName,
    )

    private fun occurrence(
        rule: PaymentReminderRule,
        date: LocalDate,
        status: PaymentReminderStatus,
    ) = PaymentReminderOccurrence(
        ruleId = rule.id,
        targetType = rule.targetType,
        targetKey = rule.targetKey,
        title = "Bill ${rule.id}",
        kind = rule.kind,
        amountMinor = 10_000,
        dueDateEpochDay = date.toEpochDay(),
        dueAtMillis = date.toEpochDay() * 86_400_000L,
        status = status,
        showAmount = false,
        priority = PaymentReminderPriority.HIGH,
        notificationStyle = PaymentReminderNotificationStyle.STANDARD,
        vibrate = true,
    )
}
