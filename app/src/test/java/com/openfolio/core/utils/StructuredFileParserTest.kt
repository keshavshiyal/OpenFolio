package com.openfolio.core.utils

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Test

class StructuredFileParserTest {
    @Test
    fun readsCsvHeadersAndQuotedValues() {
        val file = File.createTempFile("openfolio-mapping", ".csv")
        try {
            file.writeText(
                """
                Symbol,Type,Qty,Rate,Amount,Date
                "RELIANCE, EQ",BUY,2,100.50,201.00,2026-01-02
                """.trimIndent(),
            )

            val table = StructuredFileParser().read(file)

            assertEquals(
                listOf("Symbol", "Type", "Qty", "Rate", "Amount", "Date"),
                table.headers,
            )
            assertEquals(
                listOf("RELIANCE, EQ", "BUY", "2", "100.50", "201.00", "2026-01-02"),
                table.rows.single(),
            )
        } finally {
            file.delete()
        }
    }

    @Test
    fun makesBlankAndDuplicateHeadersSelectable() {
        val file = File.createTempFile("openfolio-mapping", ".csv")
        try {
            file.writeText("Symbol,Symbol,\nAAA,BBB,CCC")

            val table = StructuredFileParser().read(file)

            assertEquals(
                listOf("Symbol", "Symbol (2)", "Column 3"),
                table.headers,
            )
            assertEquals(listOf("AAA", "BBB", "CCC"), table.rows.single())
        } finally {
            file.delete()
        }
    }
}
