package com.paisalens.app.ui

import com.paisalens.app.data.model.PaymentCommitment
import java.time.LocalDate
import org.junit.Assert.assertNotEquals
import org.junit.Test

class SubscriptionAutopaySheetsTest {
    @Test
    fun `suggestion keys preserve physical members of a merged account`() {
        val rootId = 10L
        val first = PaymentCommitment(
            name = "Stream Co",
            merchantKey = "stream-co",
            amountMinor = 499_00,
            nextDueEpochDay = LocalDate.of(2026, 9, 1).toEpochDay(),
            accountId = rootId,
            physicalAccountId = 11L,
        )
        val second = first.copy(physicalAccountId = 12L)

        assertNotEquals(suggestionSessionKey(first), suggestionSessionKey(second))
    }
}
