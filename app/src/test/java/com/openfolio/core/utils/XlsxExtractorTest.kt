package com.openfolio.core.utils

import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class XlsxExtractorTest {
    @Test
    fun extractsHeadersAndRowsWithDefaultHeaderRowIndexZero() {
        val sharedStringsXml = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
            <sst xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" count="6" uniqueCount="6">
                <si><t>Symbol</t></si>
                <si><t>Type</t></si>
                <si><t>Qty</t></si>
                <si><t>Price</t></si>
                <si><t>RELIANCE</t></si>
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
                    </row>
                    <row r="2">
                        <c r="A2" t="s"><v>4</v></c>
                        <c r="B2" t="s"><v>5</v></c>
                        <c r="C2"><v>10</v></c>
                        <c r="D2"><v>2450.50</v></c>
                    </row>
                </sheetData>
            </worksheet>
        """.trimIndent()

        val file = createTestXlsx(sheetXml = sheetXml, sharedStringsXml = sharedStringsXml)
        try {
            val table = XlsxExtractor().extract(file)

            assertEquals(listOf("Symbol", "Type", "Qty", "Price"), table.headers)
            assertEquals(listOf("RELIANCE", "BUY", "10", "2450.50"), table.rows.single())
        } finally {
            file.delete()
        }
    }

    @Test
    fun skipsMetadataRowsAndReadsXlsxHeadersAtGivenRowIndex() {
        val sharedStringsXml = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
            <sst xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" count="7" uniqueCount="7">
                <si><t>Broker Statement - January 2026</t></si>
                <si><t>Symbol</t></si>
                <si><t>Type</t></si>
                <si><t>Qty</t></si>
                <si><t>Amount</t></si>
                <si><t>TATASTEEL</t></si>
                <si><t>BUY</t></si>
            </sst>
        """.trimIndent()

        val sheetXml = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
            <worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
                <sheetData>
                    <row r="1">
                        <c r="A1" t="s"><v>0</v></c>
                    </row>
                    <row r="3">
                        <c r="A3" t="s"><v>1</v></c>
                        <c r="B3" t="s"><v>2</v></c>
                        <c r="C3" t="s"><v>3</v></c>
                        <c r="D3" t="s"><v>4</v></c>
                    </row>
                    <row r="4">
                        <c r="A4" t="s"><v>5</v></c>
                        <c r="B4" t="s"><v>6</v></c>
                        <c r="C4"><v>50</v></c>
                        <c r="D4"><v>7500.00</v></c>
                    </row>
                </sheetData>
            </worksheet>
        """.trimIndent()

        val file = createTestXlsx(sheetXml = sheetXml, sharedStringsXml = sharedStringsXml)
        try {
            val table = XlsxExtractor().extract(file, headerRowIndex = 2)

            assertEquals(listOf("Symbol", "Type", "Qty", "Amount"), table.headers)
            assertEquals(listOf("TATASTEEL", "BUY", "50", "7500.00"), table.rows.single())
        } finally {
            file.delete()
        }
    }

    @Test
    fun throwsWhenHeaderRowIndexOutOfBounds() {
        val sheetXml = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
            <worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
                <sheetData>
                    <row r="1">
                        <c r="A1"><v>100</v></c>
                    </row>
                </sheetData>
            </worksheet>
        """.trimIndent()

        val file = createTestXlsx(sheetXml = sheetXml)
        try {
            val error = assertThrows(IllegalArgumentException::class.java) {
                XlsxExtractor().extract(file, headerRowIndex = 5)
            }
            assertEquals("The specified header row does not exist in this file.", error.message)
        } finally {
            file.delete()
        }
    }

    @Test
    fun throwsWhenHeaderRowIndexIsNegative() {
        val sheetXml = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
            <worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
                <sheetData/>
            </worksheet>
        """.trimIndent()

        val file = createTestXlsx(sheetXml = sheetXml)
        try {
            val error = assertThrows(IllegalArgumentException::class.java) {
                XlsxExtractor().extract(file, headerRowIndex = -1)
            }
            assertEquals("Header row index must be at least 0.", error.message)
        } finally {
            file.delete()
        }
    }

    @Test
    fun readsPreviewRowsUpToLimitFromXlsx() {
        val rowsBuilder = StringBuilder()
        for (i in 1..120) {
            rowsBuilder.append(
                """
                <row r="$i">
                    <c r="A$i"><v>$i</v></c>
                    <c r="B$i"><v>${i * 10}</v></c>
                </row>
                """.trimIndent(),
            )
        }
        val sheetXml = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
            <worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
                <sheetData>
                    $rowsBuilder
                </sheetData>
            </worksheet>
        """.trimIndent()

        val file = createTestXlsx(sheetXml = sheetXml)
        try {
            val previewRows = XlsxExtractor().readPreviewRows(file, limit = 100)

            assertEquals(100, previewRows.size)
            assertEquals(listOf("1", "10"), previewRows[0])
            assertEquals(listOf("100", "1000"), previewRows[99])
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
