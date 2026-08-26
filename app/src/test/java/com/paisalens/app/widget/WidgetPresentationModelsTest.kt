package com.paisalens.app.widget

import com.paisalens.app.data.model.AccountProfile
import com.paisalens.app.data.model.AccountType
import com.paisalens.app.data.model.BillReminder
import com.paisalens.app.data.model.CreditCardBill
import com.paisalens.app.data.model.CreditCardBillStatus
import com.paisalens.app.data.model.ExpenseCategory
import com.paisalens.app.data.model.PaymentReminderKind
import com.paisalens.app.data.model.PaymentReminderRule
import com.paisalens.app.data.model.PaymentReminderSettings
import com.paisalens.app.data.model.PaymentReminderTargetType
import com.paisalens.app.data.model.TransactionRecord
import com.paisalens.app.data.model.TransactionSource
import com.paisalens.app.data.model.TransactionType
import com.paisalens.app.data.model.creditCardReminderTargetKey
import com.paisalens.app.data.model.creditCardBillGroupKey
import com.paisalens.app.data.model.manualBillReminderTargetKey
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WidgetPresentationModelsTest {
    private val utc: ZoneId = ZoneId.of("UTC")

    @Test
    fun `privacy state always lets app lock override amount preference`() {
        assertEquals(WidgetPrivacyState.LOCKED, widgetPrivacyState(appLockEnabled = true, amountsVisible = true))
        assertFalse(widgetPrivacyState(appLockEnabled = true, amountsVisible = true).showAmounts)
        assertEquals(
            WidgetPrivacyState.AMOUNTS_HIDDEN,
            widgetPrivacyState(appLockEnabled = false, amountsVisible = false),
        )
        assertEquals(WidgetPrivacyState.VISIBLE, widgetPrivacyState(appLockEnabled = false, amountsVisible = true))
        assertEquals(
            WidgetPrivacyState.LOCKED,
            widgetPrivacyState(appLockEnabled = false, amountsVisible = true, privacyModeActive = true),
        )
    }

    @Test
    fun `monthly categories keep custom and built in other separate`() {
        val presentation = buildMonthlySpendingWidgetPresentation(
            transactions = listOf(
                expense(
                    id = 1,
                    amountMinor = 30_000,
                    category = ExpenseCategory.OTHER,
                    customCategoryId = 7,
                    customCategoryName = "Pet care",
                ),
                expense(id = 2, amountMinor = 10_000, category = ExpenseCategory.OTHER),
                expense(id = 3, amountMinor = 20_000, category = ExpenseCategory.FOOD),
            ),
            links = emptyList(),
            splits = emptyList(),
            month = YearMonth.of(2026, 8),
            zoneId = utc,
        )

        assertEquals(60_000L, presentation.spendMinor)
        assertEquals(listOf("Pet care", "Food & dining", "Other"), presentation.categoryRows.map { it.label })
        assertEquals(10_000, presentation.categoryRows.first().progressBasisPoints)
    }

    @Test
    fun `due bills combine and sort overdue manual bills`() {
        val today = LocalDate.of(2026, 8, 14)
        val presentation = buildDueBillsWidgetPresentation(
            bills = listOf(
                BillReminder(
                    id = 1,
                    title = "Electricity",
                    amountMinor = 125_000,
                    dueDateEpochDay = today.minusDays(2).toEpochDay(),
                ),
                BillReminder(
                    id = 2,
                    title = "Rent",
                    amountMinor = 900_000,
                    dueDateEpochDay = today.plusDays(5).toEpochDay(),
                ),
            ),
            transactions = emptyList(),
            loans = emptyList(),
            commitments = emptyList(),
            accounts = emptyList(),
            today = today,
            zoneId = utc,
        )

        assertEquals(listOf("Electricity", "Rent"), presentation.rows.map { it.title })
        assertEquals(1, presentation.overdueCount)
        assertEquals(1_025_000L, presentation.totalDueMinor)
    }

    @Test
    fun `credit card widget uses renamed account and only latest unpaid cycle`() {
        val today = LocalDate.of(2026, 8, 14)
        val accounts = listOf(
            AccountProfile(id = 9, name = "Travel card", type = AccountType.CREDIT_CARD),
        )
        val bills = listOf(
            cardBill(
                id = 1,
                key = "hdfc:old",
                cardIdentity = "hdfc:0801",
                amountMinor = 100_000,
                dueDate = today.minusMonths(1),
            ),
            cardBill(
                id = 2,
                key = "hdfc:new",
                cardIdentity = "hdfc:0801",
                amountMinor = 220_000,
                dueDate = today.plusDays(3),
                accountId = 9,
            ),
            cardBill(
                id = 3,
                key = "sbi:paid",
                cardIdentity = "sbi:8004",
                amountMinor = 300_000,
                dueDate = today.plusDays(2),
                status = CreditCardBillStatus.PAID,
            ),
        )

        val presentation = buildCreditCardBillsWidgetPresentation(bills, accounts, today)

        assertEquals(1, presentation.rows.size)
        assertEquals("Travel card", presentation.rows.single().cardName)
        assertEquals(220_000L, presentation.totalDueMinor)
        assertFalse(presentation.rows.single().overdue)
    }

    @Test
    fun `credit card widget reports overdue without exposing source message`() {
        val today = LocalDate.of(2026, 8, 14)
        val bill = cardBill(
            id = 1,
            key = "idfc:1",
            cardIdentity = "idfc:4567",
            amountMinor = 75_000,
            dueDate = today.minusDays(1),
        )

        val row = buildCreditCardBillsWidgetPresentation(listOf(bill), emptyList(), today).rows.single()

        assertTrue(row.overdue)
        assertEquals("IDFC ••4567", row.cardName)
    }

    @Test
    fun `payment reminder widget adapts horizon and combines manual and card bills`() {
        val today = LocalDate.of(2026, 8, 26)
        val manual = BillReminder(
            id = 7,
            title = "Electricity",
            amountMinor = 125_000,
            dueDateEpochDay = today.toEpochDay(),
        )
        val card = cardBill(
            id = 8,
            key = "hdfc:aug",
            cardIdentity = "hdfc:0801",
            amountMinor = 220_000,
            dueDate = today.plusDays(4),
        )
        val rules = listOf(
            reminderRule(
                id = 1,
                targetType = PaymentReminderTargetType.MANUAL_BILL,
                targetKey = manualBillReminderTargetKey(manual.id),
                kind = PaymentReminderKind.UTILITY,
            ),
            reminderRule(
                id = 2,
                targetType = PaymentReminderTargetType.CREDIT_CARD,
                targetKey = creditCardReminderTargetKey(card.creditCardBillGroupKey),
                kind = PaymentReminderKind.CREDIT_CARD,
            ),
        )
        val nowMillis = today.atTime(8, 0).atZone(utc).toInstant().toEpochMilli()

        val small = buildPaymentReminderWidgetPresentation(
            rules = rules,
            settings = PaymentReminderSettings(),
            manualBills = listOf(manual),
            creditCardBills = listOf(card),
            accounts = emptyList(),
            nowMillis = nowMillis,
            size = PaymentReminderWidgetSize.SMALL,
            systemZone = utc,
        )
        val medium = buildPaymentReminderWidgetPresentation(
            rules = rules,
            settings = PaymentReminderSettings(),
            manualBills = listOf(manual),
            creditCardBills = listOf(card),
            accounts = emptyList(),
            nowMillis = nowMillis,
            size = PaymentReminderWidgetSize.MEDIUM,
            systemZone = utc,
        )

        assertEquals(listOf("Electricity"), small.rows.map { it.title })
        assertEquals(listOf("Electricity", "HDFC"), medium.rows.map { it.title })
        assertEquals(2, medium.reminderCount)
        assertEquals(345_000L, medium.totalAmountMinor)
    }

    @Test
    fun `payment reminder widget disclosure fully redacts lock and privacy mode`() {
        val presentation = PaymentReminderWidgetPresentation(
            size = PaymentReminderWidgetSize.MEDIUM,
            rows = listOf(
                PaymentReminderWidgetRow(
                    ruleId = 1,
                    title = "Private card name",
                    amountMinor = 123_400,
                    dueDate = LocalDate.of(2026, 8, 27),
                    status = com.paisalens.app.data.model.PaymentReminderStatus.UPCOMING,
                    kind = PaymentReminderKind.CREDIT_CARD,
                    showAmount = true,
                ),
            ),
            reminderCount = 1,
            totalAmountMinor = 123_400,
            overdueCount = 0,
        )

        val locked = disclosePaymentReminderWidget(presentation, WidgetPrivacyState.LOCKED)
        assertTrue(locked.rows.isEmpty())
        assertEquals(null, locked.reminderCount)
        assertEquals(null, locked.overdueCount)
        assertEquals(null, locked.totalAmountMinor)

        val amountsHidden = disclosePaymentReminderWidget(presentation, WidgetPrivacyState.AMOUNTS_HIDDEN)
        assertEquals("Private card name", amountsHidden.rows.single().title)
        assertEquals(null, amountsHidden.totalAmountMinor)

        val visible = disclosePaymentReminderWidget(presentation, WidgetPrivacyState.VISIBLE)
        assertEquals(123_400L, visible.totalAmountMinor)
    }

    @Test
    fun `payment reminder widget size maps to small medium and large agendas`() {
        assertEquals(PaymentReminderWidgetSize.SMALL, paymentReminderWidgetSize(110))
        assertEquals(PaymentReminderWidgetSize.MEDIUM, paymentReminderWidgetSize(140))
        assertEquals(PaymentReminderWidgetSize.LARGE, paymentReminderWidgetSize(180))
    }

    private fun expense(
        id: Long,
        amountMinor: Long,
        category: ExpenseCategory,
        customCategoryId: Long? = null,
        customCategoryName: String? = null,
    ) = TransactionRecord(
        id = id,
        sourceMessageId = "sms-$id",
        amountMinor = amountMinor,
        merchant = "Merchant $id",
        accountHint = "0801",
        category = category,
        type = TransactionType.EXPENSE,
        occurredAt = Instant.parse("2026-08-10T12:00:00Z").toEpochMilli(),
        source = TransactionSource.CARD,
        sender = "HDFCBK",
        customCategoryId = customCategoryId,
        customCategoryName = customCategoryName,
    )

    private fun cardBill(
        id: Long,
        key: String,
        cardIdentity: String,
        amountMinor: Long,
        dueDate: LocalDate,
        accountId: Long? = null,
        status: CreditCardBillStatus = CreditCardBillStatus.DUE,
    ) = CreditCardBill(
        id = id,
        billKey = key,
        sourceMessageId = "private-source-$id",
        accountId = accountId,
        cardIdentityKey = cardIdentity,
        accountHint = cardIdentity.takeLast(4),
        institutionName = if (cardIdentity.startsWith("idfc")) "IDFC" else "HDFC",
        totalDueMinor = amountMinor,
        dueDateEpochDay = dueDate.toEpochDay(),
        detectedAt = dueDate.minusDays(10).atStartOfDay(utc).toInstant().toEpochMilli(),
        sender = "BANK",
        status = status,
    )

    private fun reminderRule(
        id: Long,
        targetType: PaymentReminderTargetType,
        targetKey: String,
        kind: PaymentReminderKind,
    ) = PaymentReminderRule(
        id = id,
        targetType = targetType,
        targetKey = targetKey,
        kind = kind,
        zoneId = utc.id,
    )
}
