package com.paisalens.app.data.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class FrankfurterRateServiceTest {
    @Test
    fun validatesAndMapsReferenceRateResponse() {
        val rate = FrankfurterRateService().parseRate(
            """{"date":"2026-08-04","base":"USD","quote":"INR","rate":84.25}""",
            expectedBase = "USD",
            expectedQuote = "INR",
        )

        assertEquals("INR", rate.baseCurrency)
        assertEquals("USD", rate.quoteCurrency)
        assertEquals(84.25, rate.rate, 0.0001)
        assertEquals("2026-08-04", rate.rateDate)
    }

    @Test
    fun rejectsMismatchedCurrencyPair() {
        assertThrows(IllegalArgumentException::class.java) {
            FrankfurterRateService().parseRate(
                """{"date":"2026-08-04","base":"EUR","quote":"INR","rate":98.0}""",
                expectedBase = "USD",
                expectedQuote = "INR",
            )
        }
    }
}
