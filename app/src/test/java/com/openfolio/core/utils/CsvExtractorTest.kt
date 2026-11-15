package com.openfolio.core.utils

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class CsvExtractorTest {
    @Test
    fun extractsHeadersAndRowsWithDefaultHeaderRowIndexZero() {
        val file = File.createTempFile("openfolio-csv-extractor", ".csv")
        try {
            file.writeText(
                """
                Symbol,Type,Qty,Rate,Amount,Date
                RELIANCE,BUY,2,100.50,201.00,2026-01-02
                """.trimIndent(),
            )

            val table = CsvExtractor().extract(file)

            assertEquals(
                listOf("Symbol", "Type", "Qty", "Rate", "Amount", "Date"),
                table.headers,
            )
            assertEquals(
                listOf("RELIANCE", "BUY", "2", "100.50", "201.00", "2026-01-02"),
                table.rows.single(),
            )
        } finally {
            file.delete()
        }
    }

    @Test
    fun skipsMetadataRowsAndReadsCsvHeadersAtGivenRowIndex() {
        val file = File.createTempFile("openfolio-csv-extractor", ".csv")
        try {
            file.writeText(
                """
                Broker Account Statement
                Account Number: 12345678
                Date Range: 01-Jan-2026 to 31-Jan-2026
                Symbol,Type,Qty,Rate,Amount,Date
                INFY,BUY,10,1400.00,14000.00,2026-01-15
                """.trimIndent(),
            )

            val table = CsvExtractor().extract(file, headerRowIndex = 3)

            assertEquals(
                listOf("Symbol", "Type", "Qty", "Rate", "Amount", "Date"),
                table.headers,
            )
            assertEquals(
                listOf("INFY", "BUY", "10", "1400.00", "14000.00", "2026-01-15"),
                table.rows.single(),
            )
        } finally {
            file.delete()
        }
    }

    @Test
    fun handlesBlankAndDuplicateHeaders() {
        val file = File.createTempFile("openfolio-csv-extractor", ".csv")
        try {
            file.writeText("Symbol,Symbol,\nAAA,BBB,CCC")

            val table = CsvExtractor().extract(file, headerRowIndex = 0)

            assertEquals(
                listOf("Symbol", "Symbol (2)", "Column 3"),
                table.headers,
            )
            assertEquals(listOf("AAA", "BBB", "CCC"), table.rows.single())
        } finally {
            file.delete()
        }
    }

    @Test
    fun throwsWhenHeaderRowIndexOutOfBounds() {
        val file = File.createTempFile("openfolio-csv-extractor", ".csv")
        try {
            file.writeText("A,B,C\n1,2,3")
            val error = assertThrows(IllegalArgumentException::class.java) {
                CsvExtractor().extract(file, headerRowIndex = 9)
            }
            assertEquals("The specified header row does not exist in this file.", error.message)
        } finally {
            file.delete()
        }
    }

    @Test
    fun throwsWhenHeaderRowIndexIsNegative() {
        val file = File.createTempFile("openfolio-csv-extractor", ".csv")
        try {
            file.writeText("A,B,C\n1,2,3")
            val error = assertThrows(IllegalArgumentException::class.java) {
                CsvExtractor().extract(file, headerRowIndex = -1)
            }
            assertEquals("Header row index must be at least 0.", error.message)
        } finally {
            file.delete()
        }
    }
}
