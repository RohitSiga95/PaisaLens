package com.paisalens.app.data.backup

import com.paisalens.app.data.model.BillReminder
import com.paisalens.app.data.model.PaisaLensBackupSnapshot
import com.paisalens.app.data.model.PaymentReminderKind
import com.paisalens.app.data.model.PaymentReminderNotificationStyle
import com.paisalens.app.data.model.PaymentReminderPriority
import com.paisalens.app.data.model.PaymentReminderRule
import com.paisalens.app.data.model.PaymentReminderSettings
import com.paisalens.app.data.model.PaymentReminderTargetType
import com.paisalens.app.data.model.manualBillReminderTargetKey
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PaymentReminderBackupTest {
    private val passphrase = "correct horse".toCharArray()

    @Test
    fun `format ten round trip preserves reminder policy state and verification count`() {
        val snapshot = snapshotWithReminder()
        val bytes = ByteArrayOutputStream().also { output ->
            PaisaLensBackupCodec.write(snapshot, passphrase.copyOf(), output)
        }.toByteArray()

        val restored = PaisaLensBackupCodec.read(passphrase.copyOf(), ByteArrayInputStream(bytes))
        val metadata = PaisaLensBackupCodec.verify(passphrase.copyOf(), ByteArrayInputStream(bytes))

        assertEquals(snapshot.paymentReminderRules, restored.paymentReminderRules)
        assertEquals(snapshot.paymentReminderSettings, restored.paymentReminderSettings)
        assertEquals(10, metadata.formatVersion)
        assertEquals(1, metadata.paymentReminderRuleCount)
    }

    @Test
    fun `format nine remains readable and safely defaults reminder settings`() {
        val bytes = ByteArrayOutputStream().also { output ->
            PaisaLensBackupCodec.writeVersionForTesting(
                snapshotWithReminder(),
                passphrase.copyOf(),
                output,
                formatVersion = 9,
            )
        }.toByteArray()

        val restored = PaisaLensBackupCodec.read(passphrase.copyOf(), ByteArrayInputStream(bytes))
        val metadata = PaisaLensBackupCodec.verify(passphrase.copyOf(), ByteArrayInputStream(bytes))

        assertTrue(restored.paymentReminderRules.isEmpty())
        assertEquals(PaymentReminderSettings(), restored.paymentReminderSettings)
        assertEquals(9, metadata.formatVersion)
        assertEquals(0, metadata.paymentReminderRuleCount)
    }

    private fun snapshotWithReminder(): PaisaLensBackupSnapshot {
        val bill = BillReminder(
            id = 1,
            title = "Electricity",
            amountMinor = 1_250_00,
            dueDateEpochDay = LocalDate.of(2026, 9, 3).toEpochDay(),
            recurrenceMonths = 1,
        )
        val rule = PaymentReminderRule(
            id = 2,
            targetType = PaymentReminderTargetType.MANUAL_BILL,
            targetKey = manualBillReminderTargetKey(bill.id),
            kind = PaymentReminderKind.UTILITY,
            alertTimeMinutes = 8 * 60 + 15,
            zoneId = "Asia/Kolkata",
            advanceDays = listOf(10, 3, 0),
            overdueRepeatDays = 2,
            snoozeMinutes = listOf(15, 120),
            priority = PaymentReminderPriority.URGENT,
            notificationStyle = PaymentReminderNotificationStyle.PROMINENT,
            showAmount = true,
            vibrate = false,
            categoryLabel = "Utilities",
            colorHex = "#22AA88",
            notes = "Pay from salary account",
            skippedOccurrenceEpochDay = bill.dueDateEpochDay - 31,
            snoozedOccurrenceEpochDay = bill.dueDateEpochDay,
            snoozedUntilMillis = 1_777_777_777_000L,
            generation = 6,
            createdAt = 100,
            updatedAt = 200,
        )
        return PaisaLensBackupSnapshot(
            createdAt = 300,
            transactions = emptyList(),
            budgets = emptyList(),
            accounts = emptyList(),
            customCategories = emptyList(),
            merchantRules = emptyList(),
            bills = listOf(bill),
            paymentReminderRules = listOf(rule),
            paymentReminderSettings = PaymentReminderSettings(
                enabled = true,
                quietHoursEnabled = true,
                quietHoursStartMinutes = 21 * 60,
                quietHoursEndMinutes = 7 * 60,
                dailySummaryEnabled = true,
                summaryTimeMinutes = 8 * 60,
                defaultAdvanceDays = listOf(5, 1, 0),
                defaultSnoozeMinutes = listOf(30, 180),
                overdueHighlightEnabled = false,
            ),
        )
    }
}
