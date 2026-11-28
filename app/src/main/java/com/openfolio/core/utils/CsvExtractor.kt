package com.openfolio.core.utils

import com.opencsv.CSVReader
import com.opencsv.CSVReaderBuilder
import com.openfolio.core.data.local.LocalFileCrypto
import com.openfolio.core.tableextraction.CsvTableGenerator
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets
import javax.inject.Inject

class CsvExtractor @Inject constructor(
    private val fileCrypto: LocalFileCrypto? = null,
) {
    constructor() : this(null)

    private fun openStream(file: File): InputStream =
        fileCrypto?.openPlaintextInputStream(file) ?: FileInputStream(file)

    fun extract(file: File, headerRowIndex: Int = 0): StructuredTable {
        require(headerRowIndex >= 0) { "Header row index must be at least 0." }
        return InputStreamReader(openStream(file), StandardCharsets.UTF_8).use { input ->
            CSVReader(input).use { reader ->
                var currentRowIndex = 0
                var rawHeaders: List<String>? = null
                while (true) {
                    val rawRow = reader.readNext()?.toList() ?: break
                    if (rawRow.firstOrNull()?.trimStart()?.startsWith("#") == true) {
                        continue
                    }
                    val row = rawRow.map { it.replace(CsvTableGenerator.COMMA_TOKEN, ",") }
                    if (currentRowIndex == headerRowIndex) {
                        rawHeaders = row
                        break
                    }
                    currentRowIndex++
                }
                if (rawHeaders == null) {
                    throw IllegalArgumentException("The specified header row does not exist in this file.")
                }
                val headers = uniqueHeaders(rawHeaders)
                val rows = mutableListOf<List<String>>()
                while (true) {
                    val rawCells = reader.readNext()?.toList() ?: break
                    if (rawCells.firstOrNull()?.trimStart()?.startsWith("#") == true) {
                        continue
                    }
                    val cells = rawCells.map { it.replace(CsvTableGenerator.COMMA_TOKEN, ",") }
                    rows += headers.indices.map { index -> cells.getOrNull(index).orEmpty().trim() }
                }
                StructuredTable(headers, rows)
            }
        }
    }

    fun read(file: File, headerRowIndex: Int = 0): StructuredTable = extract(file, headerRowIndex)

    fun readPreviewRows(file: File, limit: Int = 100): List<List<String>> {
        require(limit > 0) { "Limit must be at least 1." }
        return InputStreamReader(openStream(file), StandardCharsets.UTF_8).use { input ->
            CSVReaderBuilder(input).build().use { reader ->
                val rows = mutableListOf<List<String>>()
                while (rows.size < limit) {
                    val rawRow = reader.readNext()?.toList() ?: break
                    if (rawRow.firstOrNull()?.trimStart()?.startsWith("#") == true) {
                        continue
                    }
                    val row = rawRow.map { it.replace(CsvTableGenerator.COMMA_TOKEN, ",") }
                    rows += row
                }
                rows
            }
        }
    }
}
