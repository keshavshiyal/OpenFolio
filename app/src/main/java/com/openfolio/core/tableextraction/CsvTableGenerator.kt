package com.openfolio.core.tableextraction

import android.content.Context
import com.openfolio.core.data.local.LocalFileCrypto
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.BufferedWriter
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
    private val fileCrypto: LocalFileCrypto? = null,
) {
    constructor(@ApplicationContext context: Context) : this(context = context, fileCrypto = null)

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

        val rawStream = FileOutputStream(csvFile)
        val outStream = fileCrypto?.encryptingOutputStream(rawStream) ?: rawStream
        BufferedWriter(
            OutputStreamWriter(outStream, StandardCharsets.UTF_8),
            STREAM_BUFFER_SIZE,
        ).use { writer ->
            writeCsvContent(table, writer)
            writer.flush()
        }

        csvFile
    }

    fun buildCsvContent(table: ExtractedTable): String {
        val sb = StringBuilder()
        writeCsvContent(table, sb)
        return sb.toString()
    }

    private fun writeCsvContent(table: ExtractedTable, out: Appendable) {
        // Metadata Header Comments
        out.append("# Source: ").append(table.sourceFileName).append('\n')
        out.append("# Pages: ").append(table.pageIndices.joinToString(", ")).append('\n')
        out.append("# Table Index: ").append(table.tableIndex.toString()).append('\n')
        out.append("# Columns: ").append(table.headers.size.toString()).append('\n')
        out.append("# Rows: ").append(table.rows.size.toString()).append('\n')

        // Headers
        if (table.headers.isNotEmpty()) {
            writeCsvRow(table.headers, out)
        }

        // Data Rows
        for (row in table.rows) {
            val paddedRow = if (table.headers.isNotEmpty() && row.size < table.headers.size) {
                row + List(table.headers.size - row.size) { "" }
            } else {
                row
            }
            writeCsvRow(paddedRow, out)
        }
    }

    private fun writeCsvRow(cells: List<String>, out: Appendable) {
        if (cells.size == 1) {
            out.append(escapeCsv(cells[0])).append(",\n")
            return
        }
        for (i in cells.indices) {
            if (i > 0) out.append(',')
            out.append(escapeCsv(cells[i]))
        }
        out.append('\n')
    }

    internal fun escapeCsv(value: String): String {
        val clean = value.trim()
            .replace("\r", " ")
            .replace("\n", " ")
            .replace(",", COMMA_TOKEN)
        return if (clean.contains('"')) {
            "\"" + clean.replace("\"", "\"\"") + "\""
        } else {
            clean
        }
    }

    companion object {
        const val COMMA_TOKEN = "&COMMA;"
        private const val STREAM_BUFFER_SIZE = 32 * 1024
    }
}
