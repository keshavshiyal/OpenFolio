package com.openfolio.core.tableextraction

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class TableDetectionEngineTest {

    private lateinit var engine: TableDetectionEngine

    @Before
    fun setUp() {
        engine = TableDetectionEngine()
    }

    @Test
    fun clusterWordsIntoRowsGroupsByVerticalOverlap() {
        // Line 1 at Y: 100..115
        val w1 = ExtractedWord("Date", BoundingBox(50f, 100f, 80f, 115f), 1)
        val w2 = ExtractedWord("Symbol", BoundingBox(150f, 102f, 190f, 114f), 1)
        val w3 = ExtractedWord("Amount", BoundingBox(250f, 101f, 290f, 116f), 1)

        // Line 2 at Y: 130..145
        val w4 = ExtractedWord("2024-01-15", BoundingBox(50f, 130f, 110f, 145f), 1)
        val w5 = ExtractedWord("RELIANCE", BoundingBox(150f, 131f, 200f, 144f), 1)
        val w6 = ExtractedWord("25000", BoundingBox(250f, 132f, 280f, 146f), 1)

        val words = listOf(w6, w2, w4, w1, w5, w3) // Scrambled order
        val rows = engine.clusterWordsIntoRows(words, pageIndex = 1)

        assertEquals(2, rows.size)
        assertEquals("Date", rows[0].words[0].text)
        assertEquals("Symbol", rows[0].words[1].text)
        assertEquals("Amount", rows[0].words[2].text)

        assertEquals("2024-01-15", rows[1].words[0].text)
        assertEquals("RELIANCE", rows[1].words[1].text)
        assertEquals("25000", rows[1].words[2].text)
    }

    @Test
    fun mergeWordsIntoRowCellsMergesCloseWordsAndSplitsColumns() {
        // Two words close together: "HDFC" and "Bank"
        val w1 = ExtractedWord("HDFC", BoundingBox(50f, 100f, 80f, 115f), 1)
        val w2 = ExtractedWord("Bank", BoundingBox(85f, 100f, 110f, 115f), 1)
        // One word far away: "1500.00"
        val w3 = ExtractedWord("1500.00", BoundingBox(300f, 100f, 350f, 115f), 1)

        val row = ExtractedRow(words = listOf(w1, w2, w3), pageIndex = 1, top = 100f, bottom = 115f)
        val cells = engine.mergeWordsIntoRowCells(row)

        assertEquals(2, cells.size)
        assertEquals("HDFC Bank", cells[0].text)
        assertEquals("1500.00", cells[1].text)
    }

    @Test
    fun detectTablesExtractsStandardTable() {
        val words = listOf(
            // Row 1 (Header)
            ExtractedWord("Date", BoundingBox(50f, 100f, 80f, 115f), 1),
            ExtractedWord("Asset", BoundingBox(200f, 100f, 240f, 115f), 1),
            ExtractedWord("Qty", BoundingBox(350f, 100f, 380f, 115f), 1),

            // Row 2
            ExtractedWord("2024-01-01", BoundingBox(50f, 130f, 120f, 145f), 1),
            ExtractedWord("TCS", BoundingBox(200f, 130f, 230f, 145f), 1),
            ExtractedWord("10", BoundingBox(350f, 130f, 370f, 145f), 1),

            // Row 3
            ExtractedWord("2024-01-02", BoundingBox(50f, 160f, 120f, 175f), 1),
            ExtractedWord("INFY", BoundingBox(200f, 160f, 235f, 175f), 1),
            ExtractedWord("25", BoundingBox(350f, 160f, 370f, 175f), 1),
        )

        val tables = engine.detectTables(words, "trade_report.pdf")

        assertEquals(1, tables.size)
        val table = tables[0]
        assertEquals(1, table.tableIndex)
        assertEquals(listOf(1), table.pageIndices)
        assertEquals(listOf("Date", "Asset", "Qty"), table.headers)
        assertEquals(2, table.rows.size)
        assertEquals(listOf("2024-01-01", "TCS", "10"), table.rows[0])
        assertEquals(listOf("2024-01-02", "INFY", "25"), table.rows[1])
    }

    @Test
    fun detectTablesSplitsWhenColumnStructureChanges() {
        val words = mutableListOf<ExtractedWord>()

        // Table 1: 2 columns (Date, Amount)
        words.addAll(listOf(
            ExtractedWord("Date", BoundingBox(50f, 100f, 80f, 115f), 1),
            ExtractedWord("Amount", BoundingBox(300f, 100f, 350f, 115f), 1),

            ExtractedWord("2024-01-01", BoundingBox(50f, 130f, 120f, 145f), 1),
            ExtractedWord("5000", BoundingBox(300f, 130f, 340f, 145f), 1),

            ExtractedWord("2024-01-02", BoundingBox(50f, 160f, 120f, 175f), 1),
            ExtractedWord("10000", BoundingBox(300f, 160f, 345f, 175f), 1),
        ))

        // Single word non-tabular separator / title
        words.add(ExtractedWord("SECTION_BREAK", BoundingBox(50f, 200f, 150f, 215f), 1))

        // Table 2: 4 columns (TxnId, Asset, Type, Price)
        words.addAll(listOf(
            ExtractedWord("TxnId", BoundingBox(50f, 250f, 90f, 265f), 1),
            ExtractedWord("Asset", BoundingBox(150f, 250f, 190f, 265f), 1),
            ExtractedWord("Type", BoundingBox(250f, 250f, 280f, 265f), 1),
            ExtractedWord("Price", BoundingBox(350f, 250f, 390f, 265f), 1),

            ExtractedWord("T1", BoundingBox(50f, 280f, 70f, 295f), 1),
            ExtractedWord("WIPRO", BoundingBox(150f, 280f, 195f, 295f), 1),
            ExtractedWord("BUY", BoundingBox(250f, 280f, 275f, 295f), 1),
            ExtractedWord("450", BoundingBox(350f, 280f, 375f, 295f), 1),

            ExtractedWord("T2", BoundingBox(50f, 310f, 70f, 325f), 1),
            ExtractedWord("ITC", BoundingBox(150f, 310f, 175f, 325f), 1),
            ExtractedWord("SELL", BoundingBox(250f, 310f, 285f, 325f), 1),
            ExtractedWord("420", BoundingBox(350f, 310f, 375f, 325f), 1),
        ))

        val tables = engine.detectTables(words, "multi_table.pdf")

        assertEquals(2, tables.size)
        assertEquals(2, tables[0].headers.size)
        assertEquals("Date", tables[0].headers[0])
        assertEquals("Amount", tables[0].headers[1])

        assertEquals(4, tables[1].headers.size)
        assertEquals("TxnId", tables[1].headers[0])
        assertEquals("Price", tables[1].headers[3])
    }

    @Test
    fun detectTablesStitchesMultiPageConsecutiveTables() {
        val words = mutableListOf<ExtractedWord>()

        // Page 1: Header + 2 rows
        words.addAll(listOf(
            ExtractedWord("Date", BoundingBox(50f, 100f, 80f, 115f), 1),
            ExtractedWord("Symbol", BoundingBox(200f, 100f, 240f, 115f), 1),
            ExtractedWord("Qty", BoundingBox(350f, 100f, 380f, 115f), 1),

            ExtractedWord("2024-01-01", BoundingBox(50f, 130f, 120f, 145f), 1),
            ExtractedWord("TCS", BoundingBox(200f, 130f, 230f, 145f), 1),
            ExtractedWord("10", BoundingBox(350f, 130f, 370f, 145f), 1),

            ExtractedWord("2024-01-02", BoundingBox(50f, 160f, 120f, 175f), 1),
            ExtractedWord("INFY", BoundingBox(200f, 160f, 235f, 175f), 1),
            ExtractedWord("20", BoundingBox(350f, 160f, 370f, 175f), 1),
        ))

        // Page 2: Repeated Header + 2 rows
        words.addAll(listOf(
            ExtractedWord("Date", BoundingBox(50f, 50f, 80f, 65f), 2),
            ExtractedWord("Symbol", BoundingBox(200f, 50f, 240f, 65f), 2),
            ExtractedWord("Qty", BoundingBox(350f, 50f, 380f, 65f), 2),

            ExtractedWord("2024-01-03", BoundingBox(50f, 80f, 120f, 95f), 2),
            ExtractedWord("HDFC", BoundingBox(200f, 80f, 235f, 95f), 2),
            ExtractedWord("30", BoundingBox(350f, 80f, 370f, 95f), 2),

            ExtractedWord("2024-01-04", BoundingBox(50f, 110f, 120f, 125f), 2),
            ExtractedWord("SBIN", BoundingBox(200f, 110f, 235f, 125f), 2),
            ExtractedWord("40", BoundingBox(350f, 110f, 370f, 125f), 2),
        ))

        val tables = engine.detectTables(words, "multipage.pdf")

        // Should stitch into 1 table spanning pages 1 and 2, with repeated header ignored
        assertEquals(1, tables.size)
        val stitched = tables[0]
        assertEquals(listOf(1, 2), stitched.pageIndices)
        assertEquals(listOf("Date", "Symbol", "Qty"), stitched.headers)
        assertEquals(4, stitched.rows.size)
        assertEquals(listOf("2024-01-01", "TCS", "10"), stitched.rows[0])
        assertEquals(listOf("2024-01-02", "INFY", "20"), stitched.rows[1])
        assertEquals(listOf("2024-01-03", "HDFC", "30"), stitched.rows[2])
        assertEquals(listOf("2024-01-04", "SBIN", "40"), stitched.rows[3])
    }

    @Test
    fun extractAllLinesAsTablePreservesInterleavedNoiseAndSentencesWithoutDroppingTransactions() {
        val words = listOf(
            // Scheme Header / sentence in Column 1
            ExtractedWord("HDFC Top 100 Fund - Growth Option", BoundingBox(50f, 50f, 300f, 65f), 1),

            // Txn 1: 6 columns
            ExtractedWord("01-Jan-2024", BoundingBox(50f, 80f, 110f, 95f), 1),
            ExtractedWord("SIP Purchase", BoundingBox(150f, 80f, 220f, 95f), 1),
            ExtractedWord("5,000.00", BoundingBox(260f, 80f, 310f, 95f), 1),
            ExtractedWord("50.123", BoundingBox(350f, 80f, 390f, 95f), 1),
            ExtractedWord("99.7540", BoundingBox(430f, 80f, 480f, 95f), 1),
            ExtractedWord("50.123", BoundingBox(520f, 80f, 560f, 95f), 1),

            // Interleaved noise: Stamp duty (1 cell)
            ExtractedWord("*** Stamp Duty ***", BoundingBox(50f, 110f, 180f, 125f), 1),

            // Txn 2: 6 columns
            ExtractedWord("15-Jan-2024", BoundingBox(50f, 140f, 110f, 155f), 1),
            ExtractedWord("SIP Purchase", BoundingBox(150f, 140f, 220f, 155f), 1),
            ExtractedWord("5,000.00", BoundingBox(260f, 140f, 310f, 155f), 1),
            ExtractedWord("49.500", BoundingBox(350f, 140f, 390f, 155f), 1),
            ExtractedWord("101.0100", BoundingBox(430f, 140f, 480f, 155f), 1),
            ExtractedWord("99.623", BoundingBox(520f, 140f, 560f, 155f), 1),

            // Interleaved noise: Address updated (1 cell)
            ExtractedWord("***Address Updated***", BoundingBox(50f, 170f, 200f, 185f), 1),

            // Txn 3: 6 columns
            ExtractedWord("05-Feb-2024", BoundingBox(50f, 200f, 110f, 215f), 1),
            ExtractedWord("Redemption", BoundingBox(150f, 200f, 220f, 215f), 1),
            ExtractedWord("(4,000.00)", BoundingBox(260f, 200f, 320f, 215f), 1),
            ExtractedWord("(38.000)", BoundingBox(350f, 200f, 400f, 215f), 1),
            ExtractedWord("105.2630", BoundingBox(430f, 200f, 480f, 215f), 1),
            ExtractedWord("61.623", BoundingBox(520f, 200f, 560f, 215f), 1),
        )

        val table = engine.extractAllLinesAsTable(words, "cas_sample.pdf")

        // All 6 rows preserved (1 header + 3 txns + 2 noise)
        assertEquals(6, table.rows.size)
        assertEquals(6, table.headers.size)

        // Sentence row is placed in Column 1 and padded to 6 columns
        assertEquals("HDFC Top 100 Fund - Growth Option", table.rows[0][0])
        assertEquals("", table.rows[0][1])

        // Txn 1 preserved in full
        assertEquals("01-Jan-2024", table.rows[1][0])
        assertEquals("SIP Purchase", table.rows[1][1])
        assertEquals("5,000.00", table.rows[1][2])
        assertEquals("50.123", table.rows[1][3])
        assertEquals("99.7540", table.rows[1][4])
        assertEquals("50.123", table.rows[1][5])

        // Noise rows preserved without breaking subsequent rows
        assertEquals("*** Stamp Duty ***", table.rows[2][0])
        assertEquals("15-Jan-2024", table.rows[3][0])
        assertEquals("***Address Updated***", table.rows[4][0])
        assertEquals("05-Feb-2024", table.rows[5][0])
    }

    @Test
    fun extractAllLinesAsTablePreservesStockTradebook() {
        val words = listOf(
            // Header row
            ExtractedWord("Trade Date", BoundingBox(50f, 50f, 120f, 65f), 1),
            ExtractedWord("Symbol", BoundingBox(180f, 50f, 230f, 65f), 1),
            ExtractedWord("Type", BoundingBox(280f, 50f, 310f, 65f), 1),
            ExtractedWord("Qty", BoundingBox(360f, 50f, 390f, 65f), 1),
            ExtractedWord("Price", BoundingBox(440f, 50f, 480f, 65f), 1),

            // Row 1
            ExtractedWord("2024-03-01", BoundingBox(50f, 80f, 120f, 95f), 1),
            ExtractedWord("TCS", BoundingBox(180f, 80f, 210f, 95f), 1),
            ExtractedWord("BUY", BoundingBox(280f, 80f, 310f, 95f), 1),
            ExtractedWord("10", BoundingBox(360f, 80f, 380f, 95f), 1),
            ExtractedWord("3800.00", BoundingBox(440f, 80f, 490f, 95f), 1),

            // Row 2
            ExtractedWord("2024-03-02", BoundingBox(50f, 110f, 120f, 125f), 1),
            ExtractedWord("INFY", BoundingBox(180f, 110f, 215f, 125f), 1),
            ExtractedWord("SELL", BoundingBox(280f, 110f, 315f, 125f), 1),
            ExtractedWord("50", BoundingBox(360f, 110f, 380f, 125f), 1),
            ExtractedWord("1600.00", BoundingBox(440f, 110f, 490f, 125f), 1),
        )

        val table = engine.extractAllLinesAsTable(words, "tradebook.pdf")

        assertEquals(3, table.rows.size)
        assertEquals(5, table.headers.size)
        assertEquals(listOf("Trade Date", "Symbol", "Type", "Qty", "Price"), table.rows[0])
        assertEquals(listOf("2024-03-01", "TCS", "BUY", "10", "3800.00"), table.rows[1])
        assertEquals(listOf("2024-03-02", "INFY", "SELL", "50", "1600.00"), table.rows[2])
    }
}
