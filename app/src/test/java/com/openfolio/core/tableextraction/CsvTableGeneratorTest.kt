package com.openfolio.core.tableextraction

import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.mock
import java.io.File

class CsvTableGeneratorTest {

    private lateinit var generator: CsvTableGenerator
    private val mockContext: Context = mock()

    @Before
    fun setUp() {
        generator = CsvTableGenerator(mockContext)
    }

    @Test
    fun escapeCsvEncodesCommasAndReplacesNewlines() {
        assertEquals("SimpleText", generator.escapeCsv("SimpleText"))
        assertEquals("Text&COMMA; with comma", generator.escapeCsv("Text, with comma"))
        assertEquals("4&COMMA;999.75", generator.escapeCsv("4,999.75"))
        assertEquals("Entry Load - NIL&COMMA; Exit Load - NIL", generator.escapeCsv("Entry Load - NIL, Exit Load - NIL"))
        assertEquals("\"Text with \"\"quotes\"\"\"", generator.escapeCsv("Text with \"quotes\""))
        assertEquals("Line1 Line2", generator.escapeCsv("Line1\nLine2"))
    }

    @Test
    fun buildCsvContentIncludesMetadataCommentsAndData() {
        val table = ExtractedTable(
            tableIndex = 1,
            pageIndices = listOf(1, 2),
            headers = listOf("Date", "Description", "Amount, INR"),
            rows = listOf(
                listOf("2024-01-10", "Dividend from Reliance", "1,500.00"),
                listOf("2024-01-12", "Purchase \"TCS\"", "3500.50"),
            ),
            sourceFileName = "account_statement.pdf",
        )

        val csv = generator.buildCsvContent(table)
        val lines = csv.lines()

        // Check metadata comments
        assertTrue(lines.any { it == "# Source: account_statement.pdf" })
        assertTrue(lines.any { it == "# Pages: 1, 2" })
        assertTrue(lines.any { it == "# Table Index: 1" })
        assertTrue(lines.any { it == "# Columns: 3" })
        assertTrue(lines.any { it == "# Rows: 2" })

        // Check headers with comma escaping
        assertTrue(lines.any { it == "Date,Description,Amount&COMMA; INR" })

        // Check rows
        assertTrue(lines.any { it == "2024-01-10,Dividend from Reliance,1&COMMA;500.00" })
        assertTrue(lines.any { it == "2024-01-12,\"Purchase \"\"TCS\"\"\",3500.50" })
    }

    @Test
    fun buildCsvContentPadsShorterRows() {
        val table = ExtractedTable(
            tableIndex = 2,
            pageIndices = listOf(1),
            headers = listOf("Col1", "Col2", "Col3"),
            rows = listOf(
                listOf("Val1", "Val2"), // Short row: missing 3rd column
            ),
            sourceFileName = "test.pdf",
        )

        val csv = generator.buildCsvContent(table)
        val lines = csv.lines()

        assertTrue(lines.any { it == "Val1,Val2," })
    }
}
