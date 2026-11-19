package com.openfolio.core.ui.components

import java.math.BigDecimal
import org.junit.Assert.assertEquals
import org.junit.Test

class MoneyTextTest {
    @Test
    fun formatsRupeesUsingIndianDigitGroupingWithTwoDecimals() {
        assertEquals(
            "₹ 24,58,300.00",
            formatIndianRupees(BigDecimal("2458300")),
        )
    }

    @Test
    fun formatsFractionalAmountsRoundedToTwoDecimals() {
        assertEquals(
            "₹ 1,234.56",
            formatIndianRupees(BigDecimal("1234.56")),
        )
        assertEquals(
            "₹ 1,234.57",
            formatIndianRupees(BigDecimal("1234.567")),
        )
        assertEquals(
            "₹ 1,234.50",
            formatIndianRupees(BigDecimal("1234.5")),
        )
    }

    @Test
    fun formatsNegativeAmountsWithIndianGroupingAndTwoDecimals() {
        assertEquals(
            "₹ -1,23,456.00",
            formatIndianRupees(BigDecimal("-123456")),
        )
        assertEquals(
            "₹ -1,23,456.78",
            formatIndianRupees(BigDecimal("-123456.78")),
        )
    }
}
