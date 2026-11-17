package com.openfolio.feature.home

import com.openfolio.core.data.local.dao.DailyNetWorthDelta
import java.math.BigDecimal
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TrajectoryCalculatorTest {

    private val baseDate = LocalDate.of(2026, 11, 17).toEpochDay()

    @Test
    fun returnsAtLeastTwoPointsForEmptyData() {
        val result = TrajectoryCalculator.calculate(
            period = "1M",
            dailyDeltas = emptyList(),
            currentNetWorth = BigDecimal("100000.00"),
            todayEpochDay = baseDate,
        )

        assertTrue(result.points.size >= 2)
        assertEquals("0.0%", result.changePercent)
        assertTrue(result.isPositive)
        assertEquals("past month", result.asOf)
    }

    @Test
    fun handlesZeroNetWorthGracefully() {
        val result = TrajectoryCalculator.calculate(
            period = "1M",
            dailyDeltas = emptyList(),
            currentNetWorth = BigDecimal.ZERO,
            todayEpochDay = baseDate,
        )

        assertTrue(result.points.size >= 2)
        assertTrue(result.points.all { it == 0.0f })
        assertEquals("0.0%", result.changePercent)
    }

    @Test
    fun updatesVisuallyWhenDifferentFilterSelected() {
        // User has transactions over the last 6 months
        val deltas = listOf(
            DailyNetWorthDelta(baseDate - 150, 50000.0), // ~5 months ago
            DailyNetWorthDelta(baseDate - 90, 30000.0),  // ~3 months ago
            DailyNetWorthDelta(baseDate - 20, 20000.0),  // 20 days ago
        )

        val result1M = TrajectoryCalculator.calculate("1M", deltas, BigDecimal("100000"), baseDate)
        val result6M = TrajectoryCalculator.calculate("6M", deltas, BigDecimal("100000"), baseDate)
        val result1Y = TrajectoryCalculator.calculate("1Y", deltas, BigDecimal("100000"), baseDate)

        // Trajectory curves across different periods must not be identical
        assertNotEquals(result1M.points, result6M.points)
        assertNotEquals(result6M.points, result1Y.points)
        assertEquals("past month", result1M.asOf)
        assertEquals("past 6 months", result6M.asOf)
        assertEquals("past year", result1Y.asOf)
    }

    @Test
    fun handlesEdgeCaseOneMonthOfDataWithOneYearFilterSelected() {
        // User has only 1 month of data (transaction 20 days ago)
        val deltas = listOf(
            DailyNetWorthDelta(baseDate - 20, 100000.0),
        )

        val result1Y = TrajectoryCalculator.calculate("1Y", deltas, BigDecimal("100000"), baseDate)

        // Across 1 year (365 days), early sample points (from day 365 to day 21 ago) must be 0
        // and then jump up to 100000
        val earlyPoints = result1Y.points.take(15)
        // Earliest points should be at the lower bound
        assertEquals(earlyPoints.first(), earlyPoints[5], 0.01f)
        // Later points should be higher
        assertTrue(result1Y.points.last() > earlyPoints.first())
        assertEquals("+100.0%", "+${result1Y.changePercent}")
    }

    @Test
    fun calculatesNegativeChangeCorrectly() {
        // Net worth dropped due to fee/tax/loss
        val deltas = listOf(
            DailyNetWorthDelta(baseDate - 60, 100000.0),
            DailyNetWorthDelta(baseDate - 10, -20000.0),
        )

        val result = TrajectoryCalculator.calculate("1M", deltas, BigDecimal("80000"), baseDate)

        // From 1M ago (net worth was 100k) to today (80k), change is -20%
        assertFalse(result.isPositive)
        assertEquals("20.0%", result.changePercent)
        assertEquals(BigDecimal("80000.00"), result.minAmount)
        assertEquals(BigDecimal("100000.00"), result.maxAmount)
    }

    @Test
    fun formatsLabelsForPeriods() {
        val result1M = TrajectoryCalculator.calculate("1M", emptyList(), BigDecimal.ZERO, baseDate)
        assertEquals("Today", result1M.endLabel)
        assertEquals("17 Oct", result1M.startLabel)

        val result1Y = TrajectoryCalculator.calculate("1Y", emptyList(), BigDecimal.ZERO, baseDate)
        assertEquals("Today", result1Y.endLabel)
        assertEquals("Nov 2025", result1Y.startLabel)
    }
}
