package com.openfolio.core.ui.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import java.math.BigDecimal
import java.math.RoundingMode
import java.text.NumberFormat
import java.util.Locale

enum class MoneyTextStyle {
    Display,
    Title,
    Body,
}

@Composable
fun MoneyText(
    amount: BigDecimal,
    modifier: Modifier = Modifier,
    style: MoneyTextStyle = MoneyTextStyle.Body,
    showValues: Boolean = true,
) {
    val typography = MaterialTheme.typography
    val textStyle = when (style) {
        MoneyTextStyle.Display -> typography.displaySmall
        MoneyTextStyle.Title -> typography.titleLarge
        MoneyTextStyle.Body -> typography.bodyLarge
    }

    Text(
        text = if (showValues) formatIndianRupees(amount) else "— — —",
        modifier = modifier,
        style = textStyle.withTabularFigures(),
    )
}

fun formatIndianRupees(
    amount: BigDecimal,
    formatter: NumberFormat? = null,
): String {
    val rounded = amount.setScale(2, RoundingMode.HALF_UP)
    val absAmount = rounded.abs()
    val plainString = absAmount.toPlainString()
    val parts = plainString.split('.')
    val integerPart = parts[0]
    val fractionPart = if (parts.size > 1) parts[1] else "00"
    val groupedInteger = groupIndianDigits(integerPart)
    val sign = if (amount.signum() < 0) "-" else ""
    return "₹ $sign$groupedInteger.$fractionPart"
}

private fun createIndianNumberFormatter(): NumberFormat =
    NumberFormat.getNumberInstance(Locale("en", "IN")).apply {
        isGroupingUsed = false
        minimumFractionDigits = 0
        maximumFractionDigits = 0
        roundingMode = RoundingMode.HALF_UP
    }

private fun groupIndianDigits(digits: String): String {
    if (digits.length <= 3) return digits

    val firstGroupLength = (digits.length - 3) % 2
    val groups = buildList {
        if (firstGroupLength > 0) add(digits.take(firstGroupLength))
        var index = firstGroupLength
        while (index < digits.length - 3) {
            add(digits.substring(index, index + 2))
            index += 2
        }
        add(digits.takeLast(3))
    }
    return groups.joinToString(",")
}

private fun TextStyle.withTabularFigures(): TextStyle =
    copy(fontFeatureSettings = "tnum")
