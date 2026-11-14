package com.openfolio.core.utils

import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
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

    @Test
    fun columnReferenceToIndexCalculatesCorrectZeroBasedIndex() {
        assertEquals(0, columnReferenceToIndex("A1"))
        assertEquals(1, columnReferenceToIndex("B12"))
        assertEquals(25, columnReferenceToIndex("Z99"))
        assertEquals(26, columnReferenceToIndex("AA1"))
        assertEquals(27, columnReferenceToIndex("AB5"))
        assertEquals(52, columnReferenceToIndex("BA10"))
        assertEquals(-1, columnReferenceToIndex(""))
        assertEquals(-1, columnReferenceToIndex("123"))
    }

    @Test
    fun readsXlsxWithSharedStringsAndValues() {
        val sharedStringsXml = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
            <sst xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" count="6" uniqueCount="6">
                <si><t>Symbol</t></si>
                <si><t>Type</t></si>
                <si><t>Qty</t></si>
                <si><t>Rate</t></si>
                <si><t>Amount</t></si>
                <si><t>INFOSYS</t></si>
                <si><t>BUY</t></si>
            </sst>
        """.trimIndent()

        val sheetXml = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
            <worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
                <sheetData>
                    <row r="1">
                        <c r="A1" t="s"><v>0</v></c>
                        <c r="B1" t="s"><v>1</v></c>
                        <c r="C1" t="s"><v>2</v></c>
                        <c r="D1" t="s"><v>3</v></c>
                        <c r="E1" t="s"><v>4</v></c>
                    </row>
                    <row r="2">
                        <c r="A2" t="s"><v>5</v></c>
                        <c r="B2" t="s"><v>6</v></c>
                        <c r="C2"><v>10</v></c>
                        <c r="D2"><v>1500.00</v></c>
                        <c r="E2"><v>15000.00</v></c>
                    </row>
                </sheetData>
            </worksheet>
        """.trimIndent()

        val file = createTestXlsx(sheetXml = sheetXml, sharedStringsXml = sharedStringsXml)
        try {
            val table = StructuredFileParser().read(file)

            assertEquals(
                listOf("Symbol", "Type", "Qty", "Rate", "Amount"),
                table.headers,
            )
            assertEquals(
                listOf("INFOSYS", "BUY", "10", "1500.00", "15000.00"),
                table.rows.single(),
            )
        } finally {
            file.delete()
        }
    }

    @Test
    fun readsXlsxWithInlineStringsAndSparseColumns() {
        val sheetXml = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
            <worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
                <sheetData>
                    <row r="1">
                        <c r="A1" t="inlineStr"><is><t>Col1</t></is></c>
                        <c r="B1" t="inlineStr"><is><t>Col2</t></is></c>
                        <c r="C1" t="inlineStr"><is><t>Col3</t></is></c>
                        <c r="D1" t="inlineStr"><is><t>Col4</t></is></c>
                    </row>
                    <row r="2">
                        <c r="A2" t="inlineStr"><is><t>ItemA</t></is></c>
                        <!-- Missing B2 and C2, goes straight to D2 -->
                        <c r="D2"><f>A2+1</f><v>99.50</v></c>
                    </row>
                </sheetData>
            </worksheet>
        """.trimIndent()

        val file = createTestXlsx(sheetXml = sheetXml)
        try {
            val table = StructuredFileParser().read(file)

            assertEquals(
                listOf("Col1", "Col2", "Col3", "Col4"),
                table.headers,
            )
            assertEquals(
                listOf("ItemA", "", "", "99.50"),
                table.rows.single(),
            )
        } finally {
            file.delete()
        }
    }

    @Test
    fun throwsWhenXlsxHasNoHeaders() {
        val sheetXml = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
            <worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
                <sheetData/>
            </worksheet>
        """.trimIndent()

        val file = createTestXlsx(sheetXml = sheetXml)
        try {
            val error = assertThrows(IllegalArgumentException::class.java) {
                StructuredFileParser().read(file)
            }
            assertEquals("The selected workbook has no header row.", error.message)
        } finally {
            file.delete()
        }
    }

    private fun createTestXlsx(
        sheetXml: String,
        sharedStringsXml: String? = null,
    ): File {
        val file = File.createTempFile("openfolio-test", ".xlsx")
        ZipOutputStream(FileOutputStream(file)).use { zip ->
            fun writeEntry(name: String, content: String) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(content.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }

            writeEntry(
                "[Content_Types].xml",
                """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                <Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
                    <Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
                    <Default Extension="xml" ContentType="application/xml"/>
                </Types>""".trimIndent(),
            )
            writeEntry(
                "_rels/.rels",
                """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
                    <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/>
                </Relationships>""".trimIndent(),
            )
            writeEntry(
                "xl/workbook.xml",
                """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                <workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
                    <sheets><sheet name="Sheet1" sheetId="1" r:id="rId1"/></sheets>
                </workbook>""".trimIndent(),
            )
            writeEntry(
                "xl/_rels/workbook.xml.rels",
                """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
                    <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet1.xml"/>
                </Relationships>""".trimIndent(),
            )
            if (sharedStringsXml != null) {
                writeEntry("xl/sharedStrings.xml", sharedStringsXml)
            }
            writeEntry("xl/worksheets/sheet1.xml", sheetXml)
        }
        return file
    }
}
