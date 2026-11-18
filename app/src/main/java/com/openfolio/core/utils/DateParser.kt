package com.openfolio.core.utils

import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeFormatterBuilder
import java.time.format.DateTimeParseException
import java.util.Locale

/**
 * Robust date parser and standardizer for financial statements and user input.
 * Gracefully strips timestamps (e.g. "12-03-2025 14:30:00", "2025-03-12T14:30:00Z"),
 * handles multiple date conventions (ISO, DD/MM/YYYY, DD-MM-YYYY, DD-MMM-YYYY, US, Excel serials),
 * and standardizes output to LocalDate or ISO YYYY-MM-DD strings.
 */
object DateParser {

    private val TIME_STRIP_REGEX = Regex(
        """(?i)(?:[T\s]\d{1,2}:\d{2}(?::\d{2})?(?:\.\d+)?(?:\s*(?:AM|PM|am|pm))?(?:[+-]\d{2}:?\d{2}|Z)?).*$""",
    )
    private val TRAILING_AM_PM_REGEX = Regex("""(?i)\s*(?:AM|PM)\b""")

    private val FORMATTERS: List<DateTimeFormatter> = listOf(
        DateTimeFormatter.ISO_LOCAL_DATE, // uuuu-MM-dd
        DateTimeFormatterBuilder().parseCaseInsensitive().appendPattern("d/M/uuuu").toFormatter(Locale.ENGLISH),
        DateTimeFormatterBuilder().parseCaseInsensitive().appendPattern("d-M-uuuu").toFormatter(Locale.ENGLISH),
        DateTimeFormatterBuilder().parseCaseInsensitive().appendPattern("d.M.uuuu").toFormatter(Locale.ENGLISH),
        DateTimeFormatterBuilder().parseCaseInsensitive().appendPattern("d-MMM-uuuu").toFormatter(Locale.ENGLISH),
        DateTimeFormatterBuilder().parseCaseInsensitive().appendPattern("d/MMM/uuuu").toFormatter(Locale.ENGLISH),
        DateTimeFormatterBuilder().parseCaseInsensitive().appendPattern("d MMM uuuu").toFormatter(Locale.ENGLISH),
        DateTimeFormatterBuilder().parseCaseInsensitive().appendPattern("d-MMMM-uuuu").toFormatter(Locale.ENGLISH),
        DateTimeFormatterBuilder().parseCaseInsensitive().appendPattern("d MMMM uuuu").toFormatter(Locale.ENGLISH),
        DateTimeFormatterBuilder().parseCaseInsensitive().appendPattern("M/d/uuuu").toFormatter(Locale.ENGLISH),
        DateTimeFormatterBuilder().parseCaseInsensitive().appendPattern("M-d-uuuu").toFormatter(Locale.ENGLISH),
        DateTimeFormatterBuilder().parseCaseInsensitive().appendPattern("uuuu/M/d").toFormatter(Locale.ENGLISH),
        DateTimeFormatterBuilder().parseCaseInsensitive().appendPattern("uuuu.M.d").toFormatter(Locale.ENGLISH),
        // 2-digit year patterns
        DateTimeFormatterBuilder().parseCaseInsensitive().appendPattern("d/M/uu").toFormatter(Locale.ENGLISH),
        DateTimeFormatterBuilder().parseCaseInsensitive().appendPattern("d-M-uu").toFormatter(Locale.ENGLISH),
        DateTimeFormatterBuilder().parseCaseInsensitive().appendPattern("d-MMM-uu").toFormatter(Locale.ENGLISH),
        DateTimeFormatterBuilder().parseCaseInsensitive().appendPattern("d MMM uu").toFormatter(Locale.ENGLISH),
    )

    /**
     * Parse any messy date string (with or without timestamps) into a LocalDate.
     * Never crashes; returns null if string cannot be parsed.
     */
    fun parseToLocalDate(raw: String?): LocalDate? {
        if (raw.isNullOrBlank()) return null
        val cleaned = raw.trim()
            .replace(TIME_STRIP_REGEX, "")
            .replace(TRAILING_AM_PM_REGEX, "")
            .trim()

        if (cleaned.isBlank()) return null

        for (formatter in FORMATTERS) {
            try {
                return LocalDate.parse(cleaned, formatter)
            } catch (_: DateTimeParseException) {
                // Try next pattern
            }
        }

        // Try Excel serial integer (e.g., 45363 -> 2024-03-12)
        val excelSerial = cleaned.toBigDecimalOrNull()?.toLong()
        if (excelSerial != null && excelSerial in 1..100_000) {
            return runCatching { LocalDate.of(1899, 12, 30).plusDays(excelSerial) }.getOrNull()
        }

        return null
    }

    /**
     * Strictly standardizes the date into YYYY-MM-DD string, stripping time.
     */
    fun parseToStandardString(raw: String?): String? =
        parseToLocalDate(raw)?.format(DateTimeFormatter.ISO_LOCAL_DATE)

    /**
     * Converts to start-of-day epoch milliseconds (UTC).
     */
    fun parseToEpochMilli(raw: String?): Long? =
        parseToLocalDate(raw)?.atStartOfDay(ZoneOffset.UTC)?.toInstant()?.toEpochMilli()
}
