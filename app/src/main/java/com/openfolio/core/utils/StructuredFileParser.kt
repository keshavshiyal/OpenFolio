package com.openfolio.core.utils

import java.io.File
import javax.inject.Inject

data class StructuredTable(
    val headers: List<String>,
    val rows: List<List<String>>,
)

class StructuredFileParser @Inject constructor(
    private val csvExtractor: CsvExtractor = CsvExtractor(),
    private val xlsxExtractor: XlsxExtractor = XlsxExtractor(),
) {
    fun read(file: File, headerRowNumber: Int = 1): StructuredTable {
        require(headerRowNumber >= 1) { "Header row number must be at least 1." }
        val headerRowIndex = headerRowNumber - 1
        return when (file.extension.lowercase()) {
            "csv" -> csvExtractor.extract(file, headerRowIndex)
            "xlsx" -> xlsxExtractor.extract(file, headerRowIndex)
            else -> throw IllegalArgumentException(
                "Structured mapping supports CSV and XLSX files only.",
            )
        }
    }

    fun readWithIndex(file: File, headerRowIndex: Int = 0): StructuredTable {
        require(headerRowIndex >= 0) { "Header row index must be at least 0." }
        return when (file.extension.lowercase()) {
            "csv" -> csvExtractor.extract(file, headerRowIndex)
            "xlsx" -> xlsxExtractor.extract(file, headerRowIndex)
            else -> throw IllegalArgumentException(
                "Structured mapping supports CSV and XLSX files only.",
            )
        }
    }
}

internal fun uniqueHeaders(rawHeaders: List<String>): List<String> {
    val trimmedHeaders = rawHeaders.map { it.removePrefix("\uFEFF").trim() }
    val lastNonBlankIndex = trimmedHeaders.indexOfLast(String::isNotBlank)
    require(lastNonBlankIndex >= 0) { "The selected header row has no columns." }
    val activeHeaders = trimmedHeaders.take(lastNonBlankIndex + 1)
    val seen = mutableMapOf<String, Int>()
    return activeHeaders.mapIndexed { index, raw ->
        val base = raw.ifEmpty { "Column ${index + 1}" }
        val occurrence = (seen[base] ?: 0) + 1
        seen[base] = occurrence
        if (occurrence == 1) base else "$base ($occurrence)"
    }
}
