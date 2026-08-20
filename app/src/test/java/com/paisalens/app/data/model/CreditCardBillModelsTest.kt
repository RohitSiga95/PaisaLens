package com.paisalens.app.data.model

import org.junit.Assert.assertEquals
import org.junit.Test

class CreditCardBillModelsTest {
    @Test
    fun totalDueExcludesPaidHistory() {
        val bills = listOf(
            bill(id = 1, card = "card:hdfc:1234", amount = 120_000, dueDay = 10),
            bill(id = 2, card = "card:sbi_card:9876", amount = 230_000, dueDay = 11),
            bill(
                id = 3,
                card = "card:hdfc:1234",
                amount = 90_000,
                dueDay = 1,
                status = CreditCardBillStatus.PAID,
            ),
        )

        assertEquals(350_000L, totalCurrentCreditCardDueMinor(bills))
    }

    @Test
    fun currentDueDoesNotDoubleCountOlderUnpaidStatementBalance() {
        val bills = listOf(
            bill(id = 1, card = "card:hdfc:1234", amount = 100_000, dueDay = 10),
            bill(id = 2, card = "card:hdfc:1234", amount = 180_000, dueDay = 40),
            bill(id = 3, card = "card:sbi_card:9876", amount = 70_000, dueDay = 30),
        )

        assertEquals(listOf(3L, 2L), currentCreditCardBills(bills).map { it.id })
        assertEquals(250_000L, totalCurrentCreditCardDueMinor(bills))
    }

    @Test
    fun latestPaidCycleSuppressesAnOlderUnpaidCycle() {
        val bills = listOf(
            bill(id = 1, card = "card:hdfc:1234", amount = 100_000, dueDay = 10),
            bill(
                id = 2,
                card = "card:hdfc:1234",
                amount = 180_000,
                dueDay = 40,
                status = CreditCardBillStatus.PAID,
            ),
        )

        assertEquals(listOf(2L), currentCreditCardBills(bills).map { it.id })
        assertEquals(0L, totalCurrentCreditCardDueMinor(bills))
    }

    @Test
    fun historyIsScopedToCardAndNewestCycleFirst() {
        val bills = listOf(
            bill(id = 1, card = "card:hdfc:1234", amount = 100, dueDay = 20),
            bill(id = 2, card = "card:sbi_card:9876", amount = 200, dueDay = 30),
            bill(id = 3, card = "card:hdfc:1234", amount = 300, dueDay = 40),
        )

        assertEquals(listOf(3L, 1L), creditCardBillHistory(bills, "card:hdfc:1234").map { it.id })
    }

    @Test
    fun unresolvedBillsAreVisibleForAssignmentButExcludedFromTotals() {
        val unresolved = bill(
            id = 1,
            card = "card:hdfc:unidentified:abc",
            amount = 100_000,
            dueDay = 20,
        )

        assertEquals(emptyList<CreditCardBill>(), currentCreditCardBills(listOf(unresolved)))
        assertEquals(listOf(1L), unassignedCreditCardBills(listOf(unresolved)).map { it.id })
        assertEquals(0L, totalCurrentCreditCardDueMinor(listOf(unresolved)))
    }

    @Test
    fun preAccountAndPostAccountCyclesWithSameBankAndLastFourStayOneCard() {
        val old = bill(
            id = 1,
            card = "card:hdfc:1234",
            amount = 100_000,
            dueDay = 20,
            accountHint = "1234",
            institution = "HDFC Bank",
        )
        val latest = bill(
            id = 2,
            card = "card-account:9",
            amount = 140_000,
            dueDay = 50,
            accountHint = "1234",
            accountId = 9,
            institution = "HDFC Bank",
        )

        assertEquals(listOf(2L), currentCreditCardBills(listOf(old, latest)).map { it.id })
        assertEquals(listOf(2L, 1L), creditCardBillHistory(listOf(old, latest), latest.creditCardBillGroupKey).map { it.id })
        assertEquals(140_000L, totalCurrentCreditCardDueMinor(listOf(old, latest)))
    }

    private fun bill(
        id: Long,
        card: String,
        amount: Long,
        dueDay: Long,
        status: CreditCardBillStatus = CreditCardBillStatus.DUE,
        accountHint: String? = null,
        accountId: Long? = null,
        institution: String = "Bank",
    ) = CreditCardBill(
        id = id,
        billKey = "$card:$dueDay",
        sourceMessageId = "sms-$id",
        accountId = accountId,
        cardIdentityKey = card,
        accountHint = accountHint,
        institutionName = institution,
        totalDueMinor = amount,
        dueDateEpochDay = dueDay,
        detectedAt = dueDay * 1000,
        sender = "BANK",
        status = status,
    )
}
