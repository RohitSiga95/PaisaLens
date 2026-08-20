package com.paisalens.app.ui.privacy

import com.paisalens.app.data.model.PrivacyModeConfiguration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PrivacyModeTest {
    @Test
    fun `privacy defaults preserve usability while protecting capture when enabled`() {
        val configuration = PrivacyModeConfiguration()

        assertFalse(configuration.defaultEnabled)
        assertTrue(configuration.protectScreenCapture)
    }

    @Test
    fun `mask helper never leaks the formatted amount`() {
        assertEquals(MASKED_MONEY_TEXT, maskMoneyText("₹1,23,456", privacyActive = true))
        assertEquals("₹1,23,456", maskMoneyText("₹1,23,456", privacyActive = false))
    }
}
