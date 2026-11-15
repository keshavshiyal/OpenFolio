package com.openfolio.feature.review

import org.junit.Assert.assertEquals
import org.junit.Test

class TabularPreviewTest {
    @Test
    fun normalizesRaggedRowsByPaddingTrailingEmptyStrings() {
        val raggedRows = listOf(
            listOf("Date", "Symbol"),
            listOf("2026-01-01", "AAPL", "BUY", "10", "150.00"),
            listOf("2026-01-02", "MSFT", "BUY"),
        )

        val normalized = normalizeRaggedRows(raggedRows)

        assertEquals(3, normalized.size)
        assertEquals(listOf("Date", "Symbol", "", "", ""), normalized[0])
        assertEquals(listOf("2026-01-01", "AAPL", "BUY", "10", "150.00"), normalized[1])
        assertEquals(listOf("2026-01-02", "MSFT", "BUY", "", ""), normalized[2])
    }

    @Test
    fun handlesEmptyRowsList() {
        val normalized = normalizeRaggedRows(emptyList())
        assertEquals(emptyList<List<String>>(), normalized)
    }

    @Test
    fun handlesUniformRowsUnchanged() {
        val uniformRows = listOf(
            listOf("Col1", "Col2"),
            listOf("Val1", "Val2"),
        )

        val normalized = normalizeRaggedRows(uniformRows)

        assertEquals(uniformRows, normalized)
    }

    @Test
    fun handlesEmptySublistsPaddedToMaxColumns() {
        val mixedRows = listOf(
            emptyList(),
            listOf("A", "B"),
        )

        val normalized = normalizeRaggedRows(mixedRows)

        assertEquals(listOf("", ""), normalized[0])
        assertEquals(listOf("A", "B"), normalized[1])
    }
}
