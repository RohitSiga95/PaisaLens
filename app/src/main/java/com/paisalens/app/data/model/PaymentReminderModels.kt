package com.paisalens.app.data.model

import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.YearMonth
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit

/** The durable bill identity a reminder rule follows as statement cycles move forward. */
enum class PaymentReminderTargetType {
    MANUAL_BILL,
    CREDIT_CARD,
}

/** User-facing grouping; this intentionally remains bill-focused rather than becoming a task app. */
enum class PaymentReminderKind {
    CREDIT_CARD,
    UTILITY,
    EMI_LOAN,
    SUBSCRIPTION,
    OTHER,
}

enum class PaymentReminderPriority {
    NORMAL,
    HIGH,
    URGENT,
}

/** Android ultimately controls channel importance. PaisaLens never requests full-screen alarms. */
enum class PaymentReminderNotificationStyle {
    STANDARD,
    PROMINENT,
    SILENT,
}

enum class PaymentReminderStatus {
    UPCOMING,
    DUE_TODAY,
    OVERDUE,
    SNOOZED,
    SKIPPED,
}

data class PaymentReminderRule(
    val id: Long = 0,
    val targetType: PaymentReminderTargetType,
    /** [manualBillReminderTargetKey] or [creditCardReminderTargetKey]. */
    val targetKey: String,
    val kind: PaymentReminderKind = when (targetType) {
        PaymentReminderTargetType.MANUAL_BILL -> PaymentReminderKind.UTILITY
        PaymentReminderTargetType.CREDIT_CARD -> PaymentReminderKind.CREDIT_CARD
    },
    val enabled: Boolean = true,
    /** Local wall-clock minute, in 0..1439. */
    val alertTimeMinutes: Int = DEFAULT_PAYMENT_REMINDER_TIME_MINUTES,
    /** IANA timezone ID captured explicitly so travel never silently shifts a bill's chosen time. */
    val zoneId: String = ZoneId.systemDefault().id,
    /** Days before the bill is due. Zero is an on-the-day reminder. */
    val advanceDays: List<Int> = DEFAULT_PAYMENT_REMINDER_ADVANCE_DAYS,
    /** Zero disables overdue repeats; otherwise repeat every N days while the source remains due. */
    val overdueRepeatDays: Int = 1,
    val snoozeMinutes: List<Int> = DEFAULT_PAYMENT_REMINDER_SNOOZE_MINUTES,
    val priority: PaymentReminderPriority = PaymentReminderPriority.HIGH,
    val notificationStyle: PaymentReminderNotificationStyle = PaymentReminderNotificationStyle.STANDARD,
    /** Amounts stay concealed unless the user opts in for this rule. */
    val showAmount: Boolean = false,
    val vibrate: Boolean = true,
    val categoryLabel: String? = null,
    val colorHex: String = DEFAULT_PAYMENT_REMINDER_COLOR,
    val notes: String? = null,
    /** The latest source occurrence skipped by "Skip next"; recurring bills advance past it. */
    val skippedOccurrenceEpochDay: Long? = null,
    /** Snooze belongs to an occurrence and never mutates its bill recurrence. */
    val snoozedOccurrenceEpochDay: Long? = null,
    val snoozedUntilMillis: Long? = null,
    /** Monotonically changes on every edit so an already-queued stale broadcast becomes a no-op. */
    val generation: Long = 0,
    val createdAt: Long = 0,
    val updatedAt: Long = 0,
) {
    fun normalized(systemZone: ZoneId = ZoneId.systemDefault()): PaymentReminderRule {
        val expectedPrefix = when (targetType) {
            PaymentReminderTargetType.MANUAL_BILL -> MANUAL_BILL_TARGET_PREFIX
            PaymentReminderTargetType.CREDIT_CARD -> CREDIT_CARD_TARGET_PREFIX
        }
        val cleanTarget = targetKey.trim().take(MAX_TARGET_KEY_LENGTH)
        require(cleanTarget.startsWith(expectedPrefix) && cleanTarget.length > expectedPrefix.length) {
            "Reminder target does not match its bill type"
        }
        val cleanAdvanceDays = advanceDays.asSequence()
            .filter { it in 0..MAX_ADVANCE_DAYS }
            .distinct()
            .sortedDescending()
            .take(MAX_ALERT_OPTIONS)
            .toList()
            .ifEmpty { listOf(0) }
        val cleanSnoozeMinutes = snoozeMinutes.asSequence()
            .filter { it in 1..MAX_SNOOZE_MINUTES }
            .distinct()
            .sorted()
            .take(MAX_ALERT_OPTIONS)
            .toList()
            .ifEmpty { DEFAULT_PAYMENT_REMINDER_SNOOZE_MINUTES }
        val safeZone = runCatching { ZoneId.of(zoneId) }.getOrDefault(systemZone).id
        return copy(
            targetKey = cleanTarget,
            alertTimeMinutes = alertTimeMinutes.coerceIn(0, MINUTES_PER_DAY - 1),
            zoneId = safeZone,
            advanceDays = cleanAdvanceDays,
            overdueRepeatDays = overdueRepeatDays.coerceIn(0, MAX_OVERDUE_REPEAT_DAYS),
            snoozeMinutes = cleanSnoozeMinutes,
            categoryLabel = categoryLabel.cleanReminderText(MAX_CATEGORY_LENGTH),
            colorHex = normalizeReminderColor(colorHex),
            notes = notes.cleanReminderText(MAX_NOTES_LENGTH),
            skippedOccurrenceEpochDay = skippedOccurrenceEpochDay?.takeIf { it in MIN_EPOCH_DAY..MAX_EPOCH_DAY },
            snoozedOccurrenceEpochDay = snoozedOccurrenceEpochDay?.takeIf { it in MIN_EPOCH_DAY..MAX_EPOCH_DAY },
            snoozedUntilMillis = snoozedUntilMillis?.takeIf { it > 0L },
            generation = generation.coerceAtLeast(0L),
        )
    }

    val alertTime: LocalTime
        get() = LocalTime.of(alertTimeMinutes / 60, alertTimeMinutes % 60)
}

data class PaymentReminderSettings(
    val enabled: Boolean = true,
    val quietHoursEnabled: Boolean = false,
    val quietHoursStartMinutes: Int = 22 * 60,
    val quietHoursEndMinutes: Int = 7 * 60,
    val dailySummaryEnabled: Boolean = false,
    val summaryTimeMinutes: Int = 8 * 60,
    val defaultAdvanceDays: List<Int> = DEFAULT_PAYMENT_REMINDER_ADVANCE_DAYS,
    val defaultSnoozeMinutes: List<Int> = DEFAULT_PAYMENT_REMINDER_SNOOZE_MINUTES,
    val overdueHighlightEnabled: Boolean = true,
) {
    fun normalized(): PaymentReminderSettings = copy(
        quietHoursStartMinutes = quietHoursStartMinutes.coerceIn(0, MINUTES_PER_DAY - 1),
        quietHoursEndMinutes = quietHoursEndMinutes.coerceIn(0, MINUTES_PER_DAY - 1),
        summaryTimeMinutes = summaryTimeMinutes.coerceIn(0, MINUTES_PER_DAY - 1),
        defaultAdvanceDays = defaultAdvanceDays.asSequence()
            .filter { it in 0..MAX_ADVANCE_DAYS }
            .distinct()
            .sortedDescending()
            .take(MAX_ALERT_OPTIONS)
            .toList()
            .ifEmpty { listOf(0) },
        defaultSnoozeMinutes = defaultSnoozeMinutes.asSequence()
            .filter { it in 1..MAX_SNOOZE_MINUTES }
            .distinct()
            .sorted()
            .take(MAX_ALERT_OPTIONS)
            .toList()
            .ifEmpty { DEFAULT_PAYMENT_REMINDER_SNOOZE_MINUTES },
    )
}

/** A current source cycle. Due dates and paid state always come from the source bill, not a copy. */
data class PaymentReminderTarget(
    val targetType: PaymentReminderTargetType,
    val targetKey: String,
    val title: String,
    val amountMinor: Long,
    val dueDateEpochDay: Long,
    val accountId: Long? = null,
    val accountName: String? = null,
    val notes: String? = null,
)

data class PaymentReminderOccurrence(
    val ruleId: Long,
    val targetType: PaymentReminderTargetType,
    val targetKey: String,
    val title: String,
    val kind: PaymentReminderKind,
    val amountMinor: Long,
    val dueDateEpochDay: Long,
    val dueAtMillis: Long,
    val accountId: Long? = null,
    val accountName: String? = null,
    val categoryLabel: String? = null,
    val colorHex: String = DEFAULT_PAYMENT_REMINDER_COLOR,
    val notes: String? = null,
    val status: PaymentReminderStatus,
    val showAmount: Boolean,
    val priority: PaymentReminderPriority,
    val notificationStyle: PaymentReminderNotificationStyle,
    val vibrate: Boolean,
)

/** A stable, independently deliverable lead/snooze/overdue alert for one source occurrence. */
data class PaymentReminderAlert(
    val stableKey: String,
    val occurrence: PaymentReminderOccurrence,
    val scheduledAtMillis: Long,
    /** Null for a snooze or overdue-repeat alert. */
    val advanceDays: Int? = null,
    val isSnooze: Boolean = false,
    val isOverdueRepeat: Boolean = false,
)

data class PaymentReminderDelivery(
    val stableKey: String,
    val ruleId: Long,
    val occurrenceEpochDay: Long,
    val scheduledAtMillis: Long,
    val deliveredAtMillis: Long,
)

fun manualBillReminderTargetKey(billId: Long): String {
    require(billId > 0L) { "A saved bill is required for a reminder" }
    return "$MANUAL_BILL_TARGET_PREFIX$billId"
}

fun creditCardReminderTargetKey(cardGroupKey: String): String {
    val clean = cardGroupKey.trim()
    require(clean.isNotBlank()) { "A saved credit-card group is required for a reminder" }
    return "$CREDIT_CARD_TARGET_PREFIX${clean.take(MAX_TARGET_KEY_LENGTH - CREDIT_CARD_TARGET_PREFIX.length)}"
}

fun buildPaymentReminderTargets(
    manualBills: List<BillReminder>,
    creditCardBills: List<CreditCardBill>,
    today: LocalDate,
    accountNamesById: Map<Long, String> = emptyMap(),
): List<PaymentReminderTarget> = buildList {
    manualBills.forEach { bill ->
        nextManualBillOccurrence(bill, today)?.let { dueDate ->
            val accountName = bill.accountId?.let(accountNamesById::get)
                ?.trim()
                ?.takeIf(String::isNotBlank)
            add(
                PaymentReminderTarget(
                    targetType = PaymentReminderTargetType.MANUAL_BILL,
                    targetKey = manualBillReminderTargetKey(bill.id),
                    title = bill.title,
                    amountMinor = bill.amountMinor,
                    dueDateEpochDay = dueDate.toEpochDay(),
                    accountId = bill.accountId,
                    accountName = accountName,
                    notes = bill.notes,
                ),
            )
        }
    }
    currentCreditCardBills(creditCardBills)
        .filter {
            it.hasOutstandingBalance &&
                it.dueDateEpochDay in MIN_EPOCH_DAY..MAX_EPOCH_DAY
        }
        .forEach { bill ->
            val accountName = bill.accountId?.let(accountNamesById::get)
                ?.trim()
                ?.takeIf(String::isNotBlank)
            add(
                PaymentReminderTarget(
                    targetType = PaymentReminderTargetType.CREDIT_CARD,
                    targetKey = creditCardReminderTargetKey(bill.creditCardBillGroupKey),
                    title = accountName ?: bill.institutionName,
                    amountMinor = bill.remainingDueMinor,
                    dueDateEpochDay = bill.dueDateEpochDay,
                    accountId = bill.accountId,
                    accountName = accountName ?: bill.accountHint,
                ),
            )
        }
}.sortedWith(compareBy<PaymentReminderTarget> { it.dueDateEpochDay }.thenBy { it.title.lowercase() })

/**
 * Resolves current UI/scheduler occurrences from authoritative bill sources. A skipped recurring
 * manual bill advances past the latest skipped anchored cycle. An SMS card cycle cannot be
 * predicted, so a skipped card reminder waits for the next statement instead.
 */
fun resolvePaymentReminderOccurrences(
    rules: List<PaymentReminderRule>,
    targets: List<PaymentReminderTarget>,
    manualBills: List<BillReminder>,
    nowMillis: Long,
    systemZone: ZoneId = ZoneId.systemDefault(),
): List<PaymentReminderOccurrence> {
    val targetsByKey = targets.associateBy { it.targetType to it.targetKey }
    val manualBillsByKey = manualBills.filter { it.id > 0L }.associateBy { manualBillReminderTargetKey(it.id) }
    return rules.asSequence()
        .filter { it.id > 0L && it.enabled }
        .map(PaymentReminderRule::normalized)
        .mapNotNull { rule ->
            var target = targetsByKey[rule.targetType to rule.targetKey] ?: return@mapNotNull null
            var skipped = rule.skippedOccurrenceEpochDay == target.dueDateEpochDay
            if (
                rule.targetType == PaymentReminderTargetType.MANUAL_BILL &&
                rule.skippedOccurrenceEpochDay != null &&
                rule.skippedOccurrenceEpochDay >= target.dueDateEpochDay
            ) {
                val bill = manualBillsByKey[rule.targetKey]
                if (bill != null && bill.recurrenceMonths > 0) {
                    val skippedDate = LocalDate.ofEpochDay(rule.skippedOccurrenceEpochDay)
                    val next = nextAnchoredManualBillOccurrence(bill, skippedDate)
                    target = target.copy(dueDateEpochDay = next.toEpochDay())
                    skipped = false
                }
            }
            val zone = runCatching { ZoneId.of(rule.zoneId) }.getOrDefault(systemZone)
            val dueDate = runCatching { LocalDate.ofEpochDay(target.dueDateEpochDay) }
                .getOrNull() ?: return@mapNotNull null
            val dueAt = dueDate.atTime(rule.alertTime).atZone(zone).toInstant().toEpochMilli()
            val today = Instant.ofEpochMilli(nowMillis).atZone(zone).toLocalDate()
            val status = when {
                skipped -> PaymentReminderStatus.SKIPPED
                rule.snoozedOccurrenceEpochDay == target.dueDateEpochDay &&
                    (rule.snoozedUntilMillis ?: 0L) > nowMillis -> PaymentReminderStatus.SNOOZED
                dueDate.isBefore(today) -> PaymentReminderStatus.OVERDUE
                dueDate == today -> PaymentReminderStatus.DUE_TODAY
                else -> PaymentReminderStatus.UPCOMING
            }
            PaymentReminderOccurrence(
                ruleId = rule.id,
                targetType = rule.targetType,
                targetKey = rule.targetKey,
                title = target.title,
                kind = rule.kind,
                amountMinor = target.amountMinor,
                dueDateEpochDay = target.dueDateEpochDay,
                dueAtMillis = dueAt,
                accountId = target.accountId,
                accountName = target.accountName,
                categoryLabel = rule.categoryLabel,
                colorHex = rule.colorHex,
                notes = rule.notes ?: target.notes,
                status = status,
                showAmount = rule.showAmount,
                priority = rule.priority,
                notificationStyle = rule.notificationStyle,
                vibrate = rule.vibrate,
            )
        }
        .sortedWith(compareBy<PaymentReminderOccurrence> { it.dueAtMillis }.thenBy { it.title.lowercase() })
        .toList()
}

/** Plans one occurrence's lead, snooze, and overdue-repeat alerts without touching Android APIs. */
fun buildPaymentReminderAlerts(
    rule: PaymentReminderRule,
    occurrence: PaymentReminderOccurrence,
    settings: PaymentReminderSettings,
    nowMillis: Long,
    throughMillis: Long,
    systemZone: ZoneId = ZoneId.systemDefault(),
): List<PaymentReminderAlert> {
    if (!settings.enabled || !rule.enabled || occurrence.status == PaymentReminderStatus.SKIPPED) return emptyList()
    require(throughMillis >= nowMillis) { "Reminder planning horizon is invalid" }
    val safeRule = rule.normalized(systemZone)
    val safeSettings = settings.normalized()
    val zone = runCatching { ZoneId.of(safeRule.zoneId) }.getOrDefault(systemZone)
    val dueDate = LocalDate.ofEpochDay(occurrence.dueDateEpochDay)
    val result = mutableListOf<PaymentReminderAlert>()

    if (
        safeRule.snoozedOccurrenceEpochDay == occurrence.dueDateEpochDay &&
        (safeRule.snoozedUntilMillis ?: 0L) > nowMillis
    ) {
        val at = deferOutOfQuietHours(requireNotNull(safeRule.snoozedUntilMillis), zone, safeSettings)
        if (at <= throughMillis) {
            result += PaymentReminderAlert(
                stableKey = reminderAlertKey(safeRule, occurrence.dueDateEpochDay, "snooze:$at"),
                occurrence = occurrence,
                scheduledAtMillis = at,
                isSnooze = true,
            )
        }
        // Snooze is occurrence-level: ordinary leads and overdue repeats stay quiet until it fires.
        return result
    }

    safeRule.advanceDays.forEach { days ->
        val originalAt = dueDate.minusDays(days.toLong()).atTime(safeRule.alertTime).atZone(zone)
            .toInstant().toEpochMilli()
        val at = deferOutOfQuietHours(originalAt, zone, safeSettings)
        if (at in nowMillis..throughMillis) {
            result += PaymentReminderAlert(
                stableKey = reminderAlertKey(safeRule, occurrence.dueDateEpochDay, "lead:$days"),
                occurrence = occurrence,
                scheduledAtMillis = at,
                advanceDays = days,
            )
        }
    }

    if (safeRule.overdueRepeatDays > 0 && dueDate.isBefore(Instant.ofEpochMilli(throughMillis).atZone(zone).toLocalDate())) {
        val firstOverdueDate = dueDate.plusDays(safeRule.overdueRepeatDays.toLong())
        var repeatDate = firstOverdueDate
        val fromDate = Instant.ofEpochMilli(nowMillis).atZone(zone).toLocalDate()
        if (repeatDate.isBefore(fromDate)) {
            val elapsed = ChronoUnit.DAYS.between(firstOverdueDate, fromDate)
            val jumps = elapsed / safeRule.overdueRepeatDays
            repeatDate = firstOverdueDate.plusDays(jumps * safeRule.overdueRepeatDays)
            while (repeatDate.isBefore(fromDate)) repeatDate = repeatDate.plusDays(safeRule.overdueRepeatDays.toLong())
        }
        while (true) {
            val originalAt = repeatDate.atTime(safeRule.alertTime).atZone(zone).toInstant().toEpochMilli()
            val at = deferOutOfQuietHours(originalAt, zone, safeSettings)
            if (at > throughMillis) break
            if (at >= nowMillis) {
                result += PaymentReminderAlert(
                    stableKey = reminderAlertKey(
                        safeRule,
                        occurrence.dueDateEpochDay,
                        "overdue:${repeatDate.toEpochDay()}",
                    ),
                    occurrence = occurrence,
                    scheduledAtMillis = at,
                    isOverdueRepeat = true,
                )
            }
            repeatDate = repeatDate.plusDays(safeRule.overdueRepeatDays.toLong())
        }
    }
    // Several lead times can converge at quiet-hours end. One notification is enough; keep the
    // closest-to-due lead (or snooze/overdue item) at each instant.
    return result
        .groupBy(PaymentReminderAlert::scheduledAtMillis)
        .values
        .map { simultaneous ->
            simultaneous.minWithOrNull(
                compareBy<PaymentReminderAlert> { it.advanceDays ?: -1 }
                    .thenByDescending { it.isSnooze }
                    .thenByDescending { it.isOverdueRepeat },
            ) ?: error("Reminder alert group cannot be empty")
        }
        .sortedBy(PaymentReminderAlert::scheduledAtMillis)
}

fun nextPaymentReminderSummaryAt(
    settings: PaymentReminderSettings,
    now: ZonedDateTime,
): ZonedDateTime? {
    val safe = settings.normalized()
    if (!safe.enabled || !safe.dailySummaryEnabled) return null
    val time = LocalTime.of(safe.summaryTimeMinutes / 60, safe.summaryTimeMinutes % 60)
    val candidate = now.toLocalDate().atTime(time).atZone(now.zone)
        .let { if (it.isAfter(now)) it else it.plusDays(1) }
    val deferredMillis = deferOutOfQuietHours(candidate.toInstant().toEpochMilli(), now.zone, safe)
    return Instant.ofEpochMilli(deferredMillis).atZone(now.zone)
}

fun deferOutOfQuietHours(
    atMillis: Long,
    zoneId: ZoneId,
    settings: PaymentReminderSettings,
): Long {
    val safe = settings.normalized()
    if (!safe.quietHoursEnabled || safe.quietHoursStartMinutes == safe.quietHoursEndMinutes) return atMillis
    val at = Instant.ofEpochMilli(atMillis).atZone(zoneId)
    val minute = at.hour * 60 + at.minute
    val start = safe.quietHoursStartMinutes
    val end = safe.quietHoursEndMinutes
    val inQuietHours = if (start < end) minute in start until end else minute >= start || minute < end
    if (!inQuietHours) return atMillis
    val endTime = LocalTime.of(end / 60, end % 60)
    val endDate = when {
        start < end -> at.toLocalDate()
        minute >= start -> at.toLocalDate().plusDays(1)
        else -> at.toLocalDate()
    }
    return endDate.atTime(endTime).atZone(zoneId).toInstant().toEpochMilli()
}

private fun nextManualBillOccurrence(bill: BillReminder, today: LocalDate): LocalDate? {
    if (!bill.isActive || bill.amountMinor < 0L || bill.id <= 0L) return null
    val anchor = runCatching { LocalDate.ofEpochDay(bill.dueDateEpochDay) }.getOrNull() ?: return null
    val lastPaid = bill.lastPaidEpochDay?.let { runCatching { LocalDate.ofEpochDay(it) }.getOrNull() }
    var due = anchor
    if (lastPaid != null && !due.isAfter(lastPaid)) {
        if (bill.recurrenceMonths <= 0) return null
        val months = ChronoUnit.MONTHS.between(YearMonth.from(anchor), YearMonth.from(lastPaid)).coerceAtLeast(0)
        var occurrence = months / bill.recurrenceMonths + 1L
        due = anchoredBillDate(anchor, occurrence * bill.recurrenceMonths)
        while (!due.isAfter(lastPaid)) {
            occurrence += 1
            due = anchoredBillDate(anchor, occurrence * bill.recurrenceMonths)
        }
    }
    // An unpaid occurrence remains relevant when overdue; never silently roll it forward.
    return due
}

private fun nextAnchoredManualBillOccurrence(bill: BillReminder, occurrence: LocalDate): LocalDate {
    val anchor = LocalDate.ofEpochDay(bill.dueDateEpochDay)
    val monthsFromAnchor = ChronoUnit.MONTHS.between(YearMonth.from(anchor), YearMonth.from(occurrence))
    val nextMonths = (monthsFromAnchor / bill.recurrenceMonths + 1L) * bill.recurrenceMonths
    return anchoredBillDate(anchor, nextMonths)
}

private fun anchoredBillDate(anchor: LocalDate, monthsAfterAnchor: Long): LocalDate {
    val target = YearMonth.from(anchor).plusMonths(monthsAfterAnchor)
    return if (anchor.dayOfMonth == YearMonth.from(anchor).lengthOfMonth()) {
        target.atEndOfMonth()
    } else {
        target.atDay(anchor.dayOfMonth.coerceAtMost(target.lengthOfMonth()))
    }
}

private fun reminderAlertKey(rule: PaymentReminderRule, occurrenceEpochDay: Long, alertIdentity: String): String =
    // Delivery identity deliberately excludes the mutable rule generation. Editing notes, color,
    // privacy, or pause state must not resurrect already-delivered leads as catch-up alerts.
    "payment-reminder:${rule.id}:$occurrenceEpochDay:$alertIdentity"

private fun String?.cleanReminderText(maxLength: Int): String? = this
    ?.trim()
    ?.replace(Regex("\\s+"), " ")
    ?.take(maxLength)
    ?.takeIf(String::isNotBlank)

private fun normalizeReminderColor(value: String): String = value.trim()
    .uppercase()
    .takeIf { it.matches(Regex("#[0-9A-F]{6}")) }
    ?: DEFAULT_PAYMENT_REMINDER_COLOR

const val DEFAULT_PAYMENT_REMINDER_TIME_MINUTES = 9 * 60
val DEFAULT_PAYMENT_REMINDER_ADVANCE_DAYS = listOf(7, 3, 1, 0)
val DEFAULT_PAYMENT_REMINDER_SNOOZE_MINUTES = listOf(10, 60, 24 * 60)
const val DEFAULT_PAYMENT_REMINDER_COLOR = "#FF8A4C"

private const val MANUAL_BILL_TARGET_PREFIX = "manual-bill:"
private const val CREDIT_CARD_TARGET_PREFIX = "credit-card:"
private const val MINUTES_PER_DAY = 24 * 60
private const val MAX_ADVANCE_DAYS = 3650
private const val MAX_OVERDUE_REPEAT_DAYS = 30
private const val MAX_SNOOZE_MINUTES = 30 * MINUTES_PER_DAY
private const val MAX_ALERT_OPTIONS = 16
private const val MAX_TARGET_KEY_LENGTH = 256
private const val MAX_CATEGORY_LENGTH = 48
private const val MAX_NOTES_LENGTH = 240
private val MIN_EPOCH_DAY = LocalDate.of(1900, 1, 1).toEpochDay()
private val MAX_EPOCH_DAY = LocalDate.of(2500, 12, 31).toEpochDay()
