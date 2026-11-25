package com.openfolio.core.tableextraction

import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs

@Singleton
class TableDetectionEngine @Inject constructor() {

    /**
     * Lossless, line-by-line table builder that extracts every page line-by-line without
     * dropping rows due to column structure changes or interleaved noise/sentence lines.
     */
    fun extractAllLinesAsTable(
        words: List<ExtractedWord>,
        sourceFileName: String,
    ): ExtractedTable {
        if (words.isEmpty()) {
            return ExtractedTable(
                tableIndex = 1,
                pageIndices = listOf(1),
                headers = listOf("Column 1", "Column 2"),
                rows = emptyList(),
                sourceFileName = sourceFileName,
            )
        }

        // 1. Group words by page in sorted order
        val pages = words.groupBy { it.pageIndex }.toSortedMap()

        // 2. For each page, cluster words into rows and sort top-to-bottom
        val allRows = mutableListOf<List<String>>()
        val pageIndices = pages.keys.toList()

        for ((pageIndex, pageWords) in pages) {
            val pageRows = clusterWordsIntoRows(pageWords, pageIndex).sortedBy { it.top }
            for (row in pageRows) {
                val cells = mergeWordsIntoRowCells(row)
                if (cells.isEmpty()) continue

                if (cells.size == 1) {
                    allRows.add(listOf(cells[0].text))
                } else {
                    allRows.add(cells.map { it.text })
                }
            }
        }

        if (allRows.isEmpty()) {
            return ExtractedTable(
                tableIndex = 1,
                pageIndices = pageIndices.ifEmpty { listOf(1) },
                headers = listOf("Column 1", "Column 2"),
                rows = emptyList(),
                sourceFileName = sourceFileName,
            )
        }

        // 4. Compute maxCols
        val maxCols = maxOf(2, allRows.maxOfOrNull { it.size } ?: 1)

        // 5. Generate generic headers
        val headers = (1..maxCols).map { "Column $it" }

        // 6. Pad every row to maxCols with empty strings
        val paddedRows = allRows.map { row ->
            if (row.size < maxCols) {
                row + List(maxCols - row.size) { "" }
            } else {
                row
            }
        }

        // 7. Return single unified ExtractedTable
        return ExtractedTable(
            tableIndex = 1,
            pageIndices = pageIndices,
            headers = headers,
            rows = paddedRows,
            sourceFileName = sourceFileName,
        )
    }

    fun detectTables(
        words: List<ExtractedWord>,
        sourceFileName: String,
    ): List<ExtractedTable> {
        if (words.isEmpty()) return emptyList()

        // 1. Group words by page
        val pages = words.groupBy { it.pageIndex }.toSortedMap()

        // 2. Cluster words into rows per page (Y-axis clustering)
        val pageRows = mutableMapOf<Int, List<ExtractedRow>>()
        for ((pageIndex, pageWords) in pages) {
            val rows = clusterWordsIntoRows(pageWords, pageIndex)
            if (rows.isNotEmpty()) {
                pageRows[pageIndex] = rows
            }
        }

        // 3. Cluster rows into tables by column structure (X-axis clustering)
        val rawTables = mutableListOf<MutableTable>()
        var currentTable: MutableTable? = null

        for ((pageIndex, rows) in pageRows) {
            for (row in rows) {
                val cells = mergeWordsIntoRowCells(row)
                if (cells.size < 2) {
                    // Potential section divider, disclaimer, or header block
                    // If current table has enough rows, close it when a non-tabular row interrupts
                    if (currentTable != null && currentTable.rows.size >= 2) {
                        rawTables.add(currentTable)
                        currentTable = null
                    }
                    continue
                }

                if (currentTable == null) {
                    currentTable = MutableTable(
                        pageIndices = mutableListOf(pageIndex),
                        columnCount = cells.size,
                        columnAnchors = cells.map { it.left },
                        rows = mutableListOf(cells.map { it.text }),
                    )
                } else {
                    // Check if row matches current table's column structure
                    val isStructureMatch = isColumnStructureCompatible(
                        currentTable.columnCount,
                        currentTable.columnAnchors,
                        cells,
                    )

                    if (isStructureMatch) {
                        if (!currentTable.pageIndices.contains(pageIndex)) {
                            currentTable.pageIndices.add(pageIndex)
                        }
                        // Check if row is a repeated header from multi-page table break
                        val isDuplicateHeader = currentTable.rows.firstOrNull()?.let { headerRow ->
                            headerRow.size == cells.size &&
                                headerRow.zip(cells).all { (h, c) -> h.equals(c.text, ignoreCase = true) }
                        } ?: false

                        if (!isDuplicateHeader) {
                            currentTable.rows.add(cells.map { it.text })
                        }
                    } else {
                        // Column structure changed! Close current table and start new table
                        if (currentTable.rows.size >= 2) {
                            rawTables.add(currentTable)
                        }
                        currentTable = MutableTable(
                            pageIndices = mutableListOf(pageIndex),
                            columnCount = cells.size,
                            columnAnchors = cells.map { it.left },
                            rows = mutableListOf(cells.map { it.text }),
                        )
                    }
                }
            }
        }

        if (currentTable != null && currentTable.rows.size >= 2) {
            rawTables.add(currentTable)
        }

        // 4. Multi-page stitching: Check consecutive tables across page boundaries
        val stitchedTables = stitchMultiPageTables(rawTables)

        // 5. Convert to final ExtractedTable objects
        return stitchedTables.mapIndexed { index, table ->
            val headers = if (table.rows.isNotEmpty()) {
                table.rows.first().mapIndexed { colIdx, text ->
                    text.ifBlank { "Column ${colIdx + 1}" }
                }
            } else emptyList()

            val dataRows = if (table.rows.size > 1) {
                table.rows.drop(1)
            } else table.rows

            ExtractedTable(
                tableIndex = index + 1,
                pageIndices = table.pageIndices.distinct().sorted(),
                headers = headers,
                rows = dataRows,
                sourceFileName = sourceFileName,
            )
        }
    }

    /**
     * Extracts rows for a single page without retaining word objects across pages.
     */
    fun extractPageRows(
        pageWords: List<ExtractedWord>,
        pageIndex: Int,
    ): List<List<String>> {
        if (pageWords.isEmpty()) return emptyList()
        val pageRows = clusterWordsIntoRows(pageWords, pageIndex).sortedBy { it.top }
        val result = ArrayList<List<String>>(pageRows.size)
        for (row in pageRows) {
            val cells = mergeWordsIntoRowCells(row)
            if (cells.isEmpty()) continue
            if (cells.size == 1) {
                result.add(listOf(cells[0].text))
            } else {
                result.add(cells.map { it.text })
            }
        }
        return result
    }

    /**
     * Clusters words on a page into rows based on vertical overlap in O(W log W) time
     * by maintaining only vertically active trailing rows.
     */
    internal fun clusterWordsIntoRows(words: List<ExtractedWord>, pageIndex: Int): List<ExtractedRow> {
        if (words.isEmpty()) return emptyList()
        val sorted = words.sortedBy { it.bbox.centerY }
        val rows = mutableListOf<MutableRow>()
        val activeRows = mutableListOf<MutableRow>()

        for (word in sorted) {
            val centerY = word.bbox.centerY
            var matchingRow: MutableRow? = null

            val iterator = activeRows.iterator()
            while (iterator.hasNext()) {
                val row = iterator.next()
                val rowHeight = (row.bottom - row.top).coerceAtLeast(8f)
                val tolerance = rowHeight * 0.45f
                if (centerY > row.bottom + tolerance) {
                    // Since words are sorted by centerY ascending, no future word can match this row.
                    iterator.remove()
                    continue
                }
                if (matchingRow == null && centerY >= row.top - tolerance) {
                    matchingRow = row
                }
            }

            if (matchingRow != null) {
                matchingRow.words.add(word)
                matchingRow.top = minOf(matchingRow.top, word.bbox.top)
                matchingRow.bottom = maxOf(matchingRow.bottom, word.bbox.bottom)
            } else {
                val newRow = MutableRow(
                    words = mutableListOf(word),
                    pageIndex = pageIndex,
                    top = word.bbox.top,
                    bottom = word.bbox.bottom,
                )
                rows.add(newRow)
                activeRows.add(newRow)
            }
        }

        return rows.map { r ->
            ExtractedRow(
                words = r.words.sortedBy { it.bbox.left },
                pageIndex = r.pageIndex,
                top = r.top,
                bottom = r.bottom,
            )
        }
    }

    /**
     * Groups horizontally adjacent words in a row into cell blocks.
     */
    internal fun mergeWordsIntoRowCells(row: ExtractedRow): List<RowCell> {
        val sorted = row.words.sortedBy { it.bbox.left }
        if (sorted.isEmpty()) return emptyList()

        val cells = mutableListOf<RowCell>()
        var currentText = StringBuilder(sorted.first().text)
        var left = sorted.first().bbox.left
        var right = sorted.first().bbox.right

        for (i in 1 until sorted.size) {
            val word = sorted[i]
            val gap = word.bbox.left - right
            val avgCharWidth = (word.bbox.width / word.text.length.coerceAtLeast(1)).coerceAtLeast(4f)

            // If gap is small (less than 2 character widths), treat as same cell
            if (gap < avgCharWidth * 2.4f && gap >= -2f) {
                currentText.append(" ").append(word.text)
                right = maxOf(right, word.bbox.right)
            } else {
                cells.add(RowCell(text = currentText.toString().trim(), left = left, right = right))
                currentText = StringBuilder(word.text)
                left = word.bbox.left
                right = word.bbox.right
            }
        }

        if (currentText.isNotEmpty()) {
            cells.add(RowCell(text = currentText.toString().trim(), left = left, right = right))
        }

        return cells
    }

    /**
     * Evaluates if a candidate row's column anchors align with the table's column structure.
     */
    private fun isColumnStructureCompatible(
        expectedCols: Int,
        expectedAnchors: List<Float>,
        cells: List<RowCell>,
    ): Boolean {
        if (cells.size !in (expectedCols - 1)..(expectedCols + 1)) {
            return false
        }

        // Check anchor alignments
        var matches = 0
        for (cell in cells) {
            val hasAnchorNear = expectedAnchors.any { anchor -> abs(anchor - cell.left) <= ANCHOR_TOLERANCE_PX }
            if (hasAnchorNear) matches++
        }

        val matchRatio = matches.toFloat() / maxOf(expectedCols, cells.size)
        return matchRatio >= 0.55f
    }

    /**
     * Stitches multi-page tables together if subsequent tables share identical or compatible column structure.
     */
    private fun stitchMultiPageTables(tables: List<MutableTable>): List<MutableTable> {
        if (tables.size <= 1) return tables

        val stitched = mutableListOf<MutableTable>()
        var current = tables.first()

        for (i in 1 until tables.size) {
            val next = tables[i]
            val lastPage = current.pageIndices.lastOrNull() ?: 1
            val firstPageNext = next.pageIndices.firstOrNull() ?: 1

            val isConsecutivePage = firstPageNext == lastPage || firstPageNext == lastPage + 1
            val isCompatible = isColumnStructureCompatible(
                current.columnCount,
                current.columnAnchors,
                next.rows.firstOrNull()?.mapIndexed { idx, txt ->
                    RowCell(txt, current.columnAnchors.getOrElse(idx) { 0f }, 0f)
                } ?: emptyList(),
            )

            if (isConsecutivePage && isCompatible && current.columnCount == next.columnCount) {
                // Stitch together!
                current.pageIndices.addAll(next.pageIndices)
                current.rows.addAll(next.rows)
            } else {
                stitched.add(current)
                current = next
            }
        }
        stitched.add(current)
        return stitched
    }

    private companion object {
        const val ANCHOR_TOLERANCE_PX = 45f
    }
}

internal data class RowCell(
    val text: String,
    val left: Float,
    val right: Float,
)

internal data class MutableRow(
    val words: MutableList<ExtractedWord>,
    val pageIndex: Int,
    var top: Float,
    var bottom: Float,
)

internal data class MutableTable(
    val pageIndices: MutableList<Int>,
    val columnCount: Int,
    val columnAnchors: List<Float>,
    val rows: MutableList<List<String>>,
)
