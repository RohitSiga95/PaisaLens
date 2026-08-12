package com.paisalens.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UpiBalanceCheckSheetTest {

    @Test
    fun blankBalanceIsRejectedWithGuidance() {
        val result = validateUserEnteredBalance("   ")

        assertFalse(result.isValid)
        assertNull(result.balanceMinor)
        assertEquals("Enter the balance shown in your UPI app.", result.errorMessage)
    }

    @Test
    fun decimalBalanceIsConvertedToMinorUnits() {
        val result = validateUserEnteredBalance(" 1250.50 ")

        assertTrue(result.isValid)
        assertEquals(125_050L, result.balanceMinor)
        assertNull(result.errorMessage)
    }

    @Test
    fun zeroBalanceIsValid() {
        val result = validateUserEnteredBalance("0")

        assertTrue(result.isValid)
        assertEquals(0L, result.balanceMinor)
    }

    @Test
    fun malformedBalancesAreRejected() {
        listOf("1.234", "₹100", ".50", "12 34").forEach { input ->
            assertFalse("Expected '$input' to be rejected", validateUserEnteredBalance(input).isValid)
        }
    }

    @Test
    fun negativeOverdraftBalanceIsAccepted() {
        val result = validateUserEnteredBalance("-850.25")

        assertTrue(result.isValid)
        assertEquals(-85_025L, result.balanceMinor)
    }

    @Test
    fun oneTrillionRupeeCapIsEnforced() {
        assertEquals(
            100_000_000_000_000L,
            validateUserEnteredBalance("1000000000000.00").balanceMinor,
        )
        assertEquals(
            -100_000_000_000_000L,
            validateUserEnteredBalance("-1000000000000.00").balanceMinor,
        )
        assertFalse(validateUserEnteredBalance("1000000000000.01").isValid)
        assertFalse(validateUserEnteredBalance("10000000000000").isValid)
    }

    @Test
    fun inputFilterKeepsOneLeadingMinusAndTwoDecimals() {
        assertEquals("-12345.67", "  -12,345.6789".userEnteredBalanceInput())
        assertEquals("0.50", ".509".userEnteredBalanceInput())
        assertEquals("1234567890123", "123456789012345".userEnteredBalanceInput())
    }

    @Test
    fun sourceLabelCollapsesWhitespaceAndIdentifiesManualEntry() {
        assertEquals(
            "User entered after Google Pay check",
            upiUserEnteredSourceLabel("  Google   Pay  "),
        )
        assertEquals(
            "User entered after UPI check",
            upiUserEnteredSourceLabel("   "),
        )
        assertEquals(
            "User entered after UPI check",
            upiUserEnteredSourceLabel(null),
        )
    }

    @Test
    fun sourceLabelLimitsUntrustedAppNameLength() {
        val result = upiUserEnteredSourceLabel("A".repeat(100))

        assertEquals("User entered after ${"A".repeat(30)} check", result)
        assertTrue(result.length <= 55)
    }
}
