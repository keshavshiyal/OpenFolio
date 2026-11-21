package com.openfolio.core.tableextraction

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStreamWriter
import java.nio.charset.StandardCharsets
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Singleton
class CsvTableGenerator @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    suspend fun generateCsvFile(
        table: ExtractedTable,
        outputDirectory: File? = null,
    ): File = withContext(Dispatchers.IO) {
        val dir = outputDirectory ?: File(context.filesDir, "extracted_csvs").apply {
            if (!exists()) mkdirs()
        }

        val baseName = table.sourceFileName.substringBeforeLast('.')
            .replace(Regex("[^a-zA-Z0-9_-]"), "_")
        val fileName = "table_${table.tableIndex}_${baseName}_${UUID.randomUUID().toString().take(8)}.csv"
        val csvFile = File(dir, fileName)

        val csvContent = buildCsvContent(table)

        OutputStreamWriter(FileOutputStream(csvFile), StandardCharsets.UTF_8).use { writer ->
            writer.write(csvContent)
        }

        csvFile
    }

    fun buildCsvContent(table: ExtractedTable): String {
        val sb = StringBuilder()

        // Metadata Header Comments
        sb.append("# Source: ").append(table.sourceFileName).append("\n")
        sb.append("# Pages: ").append(table.pageIndices.joinToString(", ")).append("\n")
        sb.append("# Table Index: ").append(table.tableIndex).append("\n")
        sb.append("# Columns: ").append(table.headers.size).append("\n")
        sb.append("# Rows: ").append(table.rows.size).append("\n")

        // Headers
        if (table.headers.isNotEmpty()) {
            sb.append(table.headers.joinToString(separator = ",") { escapeCsv(it) }).append("\n")
        }

        // Data Rows
        for (row in table.rows) {
            val paddedRow = if (row.size < table.headers.size) {
                row + List(table.headers.size - row.size) { "" }
            } else {
                row.take(maxOf(table.headers.size, row.size))
            }
            sb.append(paddedRow.joinToString(separator = ",") { escapeCsv(it) }).append("\n")
        }

        return sb.toString()
    }

    internal fun escapeCsv(value: String): String {
        val clean = value.trim()
        val needsQuotes = clean.contains(',') || clean.contains('"') || clean.contains('\n') || clean.contains('\r')
        return if (needsQuotes) {
            "\"" + clean.replace("\"", "\"\"") + "\""
        } else {
            clean
        }
    }
}
