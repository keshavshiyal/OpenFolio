package com.openfolio.feature.home

import com.openfolio.core.data.local.dao.DailyNetWorthDelta
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs

data class TrajectoryResult(
    val points: List<Float>,
    val changePercent: String,
    val isPositive: Boolean,
    val asOf: String,
    val minAmount: BigDecimal,
    val maxAmount: BigDecimal,
    val startLabel: String,
    val endLabel: String,
)

object TrajectoryCalculator {
    private const val DEFAULT_SAMPLE_COUNT = 20

    private val MONTH_DAY_FORMATTER = DateTimeFormatter.ofPattern("d MMM", Locale.US)
    private val MONTH_YEAR_FORMATTER = DateTimeFormatter.ofPattern("MMM yyyy", Locale.US)

    fun calculate(
        period: String,
        dailyDeltas: List<DailyNetWorthDelta>,
        currentNetWorth: BigDecimal,
        todayEpochDay: Long = LocalDate.now().toEpochDay(),
    ): TrajectoryResult {
        val today = LocalDate.ofEpochDay(todayEpochDay)
        val sortedDeltas = dailyDeltas.sortedBy { it.tradeDate }

        val (startEpochDay, asOf, startLabel) = when (period) {
            "1M" -> {
                val start = today.minusMonths(1)
                Triple(start.toEpochDay(), "past month", MONTH_DAY_FORMATTER.format(start))
            }
            "6M" -> {
                val start = today.minusMonths(6)
                Triple(start.toEpochDay(), "past 6 months", MONTH_YEAR_FORMATTER.format(start))
            }
            "1Y" -> {
                val start = today.minusYears(1)
                Triple(start.toEpochDay(), "past year", MONTH_YEAR_FORMATTER.format(start))
            }
            "ALL" -> {
                val earliest = sortedDeltas.firstOrNull()?.tradeDate
                val start = if (earliest != null && earliest < todayEpochDay) {
                    LocalDate.ofEpochDay(earliest)
                } else {
                    today.minusMonths(1)
                }
                Triple(start.toEpochDay(), "all time", MONTH_YEAR_FORMATTER.format(start))
            }
            else -> {
                val start = today.minusMonths(1)
                Triple(start.toEpochDay(), "past month", MONTH_DAY_FORMATTER.format(start))
            }
        }

        val endLabel = "Today"

        if (sortedDeltas.isEmpty()) {
            val netWorthVal = currentNetWorth.max(BigDecimal.ZERO)
            return TrajectoryResult(
                points = if (netWorthVal > BigDecimal.ZERO) listOf(0.5f, 0.5f) else listOf(0f, 0f),
                changePercent = "0.0%",
                isPositive = true,
                asOf = asOf,
                minAmount = netWorthVal,
                maxAmount = netWorthVal,
                startLabel = startLabel,
                endLabel = endLabel,
            )
        }

        val sampleCount = DEFAULT_SAMPLE_COUNT
        val daySpan = (todayEpochDay - startEpochDay).coerceAtLeast(1)
        val step = daySpan.toDouble() / (sampleCount - 1)

        val sampleDays = (0 until sampleCount).map { i ->
            (startEpochDay + (i * step)).toLong().coerceIn(startEpochDay, todayEpochDay)
        }

        // Calculate cumulative net worth at each sample day
        val values = sampleDays.map { day ->
            sortedDeltas
                .filter { it.tradeDate <= day }
                .sumOf { it.delta }
        }

        val minVal = values.minOrNull() ?: 0.0
        val maxVal = values.maxOrNull() ?: 0.0

        val points = if (maxVal == minVal) {
            if (maxVal > 0.0) {
                List(sampleCount) { 0.5f }
            } else {
                List(sampleCount) { 0.0f }
            }
        } else {
            val range = maxVal - minVal
            val pad = range * 0.1
            val lo = minVal - pad
            val hi = maxVal + pad
            values.map { v ->
                ((v - lo) / (hi - lo)).toFloat().coerceIn(0.05f, 0.95f)
            }
        }

        val startVal = values.first()
        val endVal = values.last()
        val diff = endVal - startVal
        val pct = when {
            abs(startVal) > 0.001 -> (diff / abs(startVal)) * 100.0
            endVal > 0 -> 100.0
            endVal < 0 -> -100.0
            else -> 0.0
        }
        val isPositive = diff >= 0
        val changePercent = String.format(Locale.US, "%.1f%%", abs(pct))

        val minAmount = BigDecimal(minVal).setScale(2, RoundingMode.HALF_UP)
        val maxAmount = BigDecimal(maxVal).setScale(2, RoundingMode.HALF_UP)

        return TrajectoryResult(
            points = points,
            changePercent = changePercent,
            isPositive = isPositive,
            asOf = asOf,
            minAmount = minAmount,
            maxAmount = maxAmount,
            startLabel = startLabel,
            endLabel = endLabel,
        )
    }
}
