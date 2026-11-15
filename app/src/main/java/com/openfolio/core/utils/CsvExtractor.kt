package com.openfolio.core.utils

import com.opencsv.CSVReader
import com.opencsv.CSVReaderBuilder
import java.io.File
import java.io.FileInputStream
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets
import javax.inject.Inject

class CsvExtractor @Inject constructor() {
    fun extract(file: File, headerRowIndex: Int = 0): StructuredTable {
        require(headerRowIndex >= 0) { "Header row index must be at least 0." }
        return InputStreamReader(FileInputStream(file), StandardCharsets.UTF_8).use { input ->
            CSVReader(input).use { reader ->
                var currentRowIndex = 0
                var rawHeaders: List<String>? = null
                while (true) {
                    val row = reader.readNext()?.toList() ?: break
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
                    val cells = reader.readNext()?.toList() ?: break
                    rows += headers.indices.map { index -> cells.getOrNull(index).orEmpty().trim() }
                }
                StructuredTable(headers, rows)
            }
        }
    }

    fun read(file: File, headerRowIndex: Int = 0): StructuredTable = extract(file, headerRowIndex)

    fun readPreviewRows(file: File, limit: Int = 100): List<List<String>> {
        require(limit > 0) { "Limit must be at least 1." }
        return InputStreamReader(FileInputStream(file), StandardCharsets.UTF_8).use { input ->
            CSVReaderBuilder(input).build().use { reader ->
                val rows = mutableListOf<List<String>>()
                while (rows.size < limit) {
                    val row = reader.readNext()?.toList() ?: break
                    rows += row
                }
                rows
            }
        }
    }
}
