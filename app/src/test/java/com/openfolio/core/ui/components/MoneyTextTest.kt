package com.openfolio.core.ui.components

import java.math.BigDecimal
import org.junit.Assert.assertEquals
import org.junit.Test

class MoneyTextTest {
    @Test
    fun formatsRupeesUsingIndianDigitGrouping() {
        assertEquals(
            "₹ 24,58,300",
            formatIndianRupees(BigDecimal("2458300")),
        )
    }

    @Test
    fun formatsFractionalAmountsAsWholeRupees() {
        assertEquals(
            "₹ 1,235",
            formatIndianRupees(BigDecimal("1234.56")),
        )
    }

    @Test
    fun formatsNegativeAmountsWithIndianGrouping() {
        assertEquals(
            "₹ -1,23,456",
            formatIndianRupees(BigDecimal("-123456")),
        )
    }
}
