package com.openfolio.core.utils

import java.math.BigDecimal

/**
 * Strictly parses monetary and quantity strings extracted from financial documents or AI JSON,
 * stripping grouping separators, currency symbols, whitespace, and accounting negative
 * parentheses `(123.45)`. Returns null for non-numeric placeholders such as `"N/A"` or `"NaN"`.
 */
object FinancialNumberParser {
    private val STRIP_SYMBOLS_REGEX = Regex("[,₹$€£\\s]+")
    private val ACCOUNTING_NEGATIVE_REGEX = Regex("^\\((.+)\\)$")

    fun parseOrNull(raw: String?): BigDecimal? {
        if (raw.isNullOrBlank()) return null
        val trimmed = raw.trim()
        val unwrapped = ACCOUNTING_NEGATIVE_REGEX.matchEntire(trimmed)
            ?.let { "-${it.groupValues[1]}" }
            ?: trimmed
        val normalized = STRIP_SYMBOLS_REGEX.replace(unwrapped, "")
        if (normalized.isBlank() ||
            normalized.equals("NaN", ignoreCase = true) ||
            normalized.equals("Infinity", ignoreCase = true) ||
            normalized.equals("-Infinity", ignoreCase = true) ||
            normalized.equals("+Infinity", ignoreCase = true)
        ) {
            return null
        }
        return runCatching { BigDecimal(normalized) }.getOrNull()
    }
}
