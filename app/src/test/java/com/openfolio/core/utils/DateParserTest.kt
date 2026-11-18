package com.openfolio.core.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate

class DateParserTest {

    @Test
    fun stripsTimestampAndParsesIsoDate() {
        assertEquals(LocalDate.of(2025, 3, 12), DateParser.parseToLocalDate("2025-03-12 14:30:00"))
        assertEquals(LocalDate.of(2025, 3, 12), DateParser.parseToLocalDate("2025-03-12T14:30:00Z"))
        assertEquals(LocalDate.of(2025, 3, 12), DateParser.parseToLocalDate("2025-03-12T14:30:00.123+05:30"))
        assertEquals("2025-03-12", DateParser.parseToStandardString("2025-03-12 14:30:00"))
    }

    @Test
    fun stripsTimestampAndParsesIndianAndEuropeanFormats() {
        assertEquals(LocalDate.of(2025, 3, 12), DateParser.parseToLocalDate("12-03-2025 14:30:00"))
        assertEquals(LocalDate.of(2025, 3, 12), DateParser.parseToLocalDate("12/03/2025 14:30:00"))
        assertEquals(LocalDate.of(2025, 3, 12), DateParser.parseToLocalDate("12-03-2025"))
        assertEquals(LocalDate.of(2025, 3, 12), DateParser.parseToLocalDate("12/03/2025"))
        assertEquals(LocalDate.of(2025, 3, 12), DateParser.parseToLocalDate("12.03.2025 14:30:00"))
    }

    @Test
    fun parsesTextualMonthWithOrWithoutTimestamp() {
        assertEquals(LocalDate.of(2025, 3, 12), DateParser.parseToLocalDate("12-Mar-2025 14:30:00"))
        assertEquals(LocalDate.of(2025, 3, 12), DateParser.parseToLocalDate("12-MAR-2025 02:30 PM"))
        assertEquals(LocalDate.of(2025, 3, 12), DateParser.parseToLocalDate("12 Mar 2025"))
        assertEquals(LocalDate.of(2025, 3, 12), DateParser.parseToLocalDate("12 March 2025 10:15:30"))
    }

    @Test
    fun parsesTwoDigitYearDates() {
        assertEquals(LocalDate.of(2025, 3, 12), DateParser.parseToLocalDate("12/03/25 14:30:00"))
        assertEquals(LocalDate.of(2025, 3, 12), DateParser.parseToLocalDate("12-Mar-25"))
    }

    @Test
    fun parsesExcelSerialNumbers() {
        // 45363 is 2024-03-12 in Excel serial date format
        val date = DateParser.parseToLocalDate("45363")
        assertNotNull(date)
        assertEquals(2024, date?.year)
    }

    @Test
    fun handlesNullBlankAndGarbageGracefullyWithoutCrashing() {
        assertNull(DateParser.parseToLocalDate(null))
        assertNull(DateParser.parseToLocalDate(""))
        assertNull(DateParser.parseToLocalDate("   "))
        assertNull(DateParser.parseToLocalDate("not-a-real-date"))
        assertNull(DateParser.parseToStandardString("garbage-data"))
        assertNull(DateParser.parseToEpochMilli(null))
    }
}
