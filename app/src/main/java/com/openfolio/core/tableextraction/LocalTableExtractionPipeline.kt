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

    suspend fun extractTablesFromDocument(file: File, fileName: String): List<ExtractedTable> =
        withContext(Dispatchers.IO) {
            // 1. Extract positioned words with geometric coordinates
            val words = geometryTextExtractor.extractWords(file, fileName)
            if (words.isEmpty()) return@withContext emptyList()

            // 2. Heuristic clustering engine: rows and columns detection
            val detected = tableDetectionEngine.detectTables(words, fileName)
            if (detected.isEmpty()) return@withContext emptyList()

            // 3. Generate CSV files with prepended metadata
            detected.map { table ->
                val csvFile = csvTableGenerator.generateCsvFile(table)
                table.copy(csvFile = csvFile)
            }
        }
}
