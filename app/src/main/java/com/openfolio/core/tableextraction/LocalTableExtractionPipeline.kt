package com.openfolio.core.tableextraction

import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Singleton
class LocalTableExtractionPipeline @Inject constructor(
    private val geometryTextExtractor: GeometryTextExtractor,
    private val tableDetectionEngine: TableDetectionEngine,
    private val csvTableGenerator: CsvTableGenerator,
) {

    suspend fun extractTablesFromDocument(
        file: File,
        fileName: String,
        password: String? = null,
        writeCsv: Boolean = true,
    ): List<ExtractedTable> =
        withContext(Dispatchers.IO) {
            val allRows = mutableListOf<List<String>>()
            val pageIndices = mutableListOf<Int>()
            var maxCols = 0

            // Stream geometry extraction and row construction page-by-page without retaining all words in RAM.
            geometryTextExtractor.forEachPageWords(file, fileName, password) { pageIndex, _, pageWords ->
                if (pageWords.isEmpty()) return@forEachPageWords
                val pageRows = tableDetectionEngine.extractPageRows(pageWords, pageIndex)
                if (pageRows.isNotEmpty()) {
                    pageIndices.add(pageIndex)
                    for (row in pageRows) {
                        if (row.size > maxCols) maxCols = row.size
                        allRows.add(row)
                    }
                }
            }

            if (allRows.isEmpty()) return@withContext emptyList()

            val effectiveCols = maxOf(2, maxCols)
            val headers = (1..effectiveCols).map { "Column $it" }
            val paddedRows = allRows.map { row ->
                if (row.size < effectiveCols) {
                    row + List(effectiveCols - row.size) { "" }
                } else {
                    row
                }
            }
            val table = ExtractedTable(
                tableIndex = 1,
                pageIndices = pageIndices,
                headers = headers,
                rows = paddedRows,
                sourceFileName = fileName,
            )

            if (writeCsv) {
                val csvFile = csvTableGenerator.generateCsvFile(table)
                listOf(table.copy(csvFile = csvFile))
            } else {
                listOf(table)
            }
        }
}
