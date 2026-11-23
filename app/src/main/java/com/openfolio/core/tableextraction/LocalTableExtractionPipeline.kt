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
    ): List<ExtractedTable> =
        withContext(Dispatchers.IO) {
            // 1. Extract positioned words with geometric coordinates
            val words = geometryTextExtractor.extractWords(file, fileName, password)
            if (words.isEmpty()) return@withContext emptyList()

            // 2. Lossless line-by-line table construction (no rows dropped)
            val table = tableDetectionEngine.extractAllLinesAsTable(words, fileName)
            if (table.rows.isEmpty()) return@withContext emptyList()

            // 3. Generate CSV file with &COMMA; escaping and prepended metadata
            val csvFile = csvTableGenerator.generateCsvFile(table)
            listOf(table.copy(csvFile = csvFile))
        }
}
