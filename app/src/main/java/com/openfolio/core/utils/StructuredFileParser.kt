package com.openfolio.core.utils

import com.opencsv.CSVReader
import java.io.File
import java.io.FileInputStream
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets
import javax.inject.Inject
import org.dhatim.fastexcel.reader.ReadableWorkbook

data class StructuredTable(
    val headers: List<String>,
    val rows: List<List<String>>,
)

class StructuredFileParser @Inject constructor() {
    fun read(file: File): StructuredTable =
        when (file.extension.lowercase()) {
            "csv" -> readCsv(file)
            "xlsx" -> readXlsx(file)
            else -> throw IllegalArgumentException(
                "Structured mapping supports CSV and XLSX files only.",
            )
        }

    private fun readCsv(file: File): StructuredTable =
        InputStreamReader(FileInputStream(file), StandardCharsets.UTF_8).use { input ->
            CSVReader(input).use { reader ->
                val rawHeaders = reader.readNext()?.toList()
                    ?: throw IllegalArgumentException("The selected file has no header row.")
                val headers = uniqueHeaders(rawHeaders)
                val rows = mutableListOf<List<String>>()
                while (true) {
                    val cells = reader.readNext()?.toList() ?: break
                    rows += headers.indices.map { index -> cells.getOrNull(index).orEmpty().trim() }
                }
                StructuredTable(headers, rows)
            }
        }

    private fun readXlsx(file: File): StructuredTable =
        FileInputStream(file).use { input ->
            ReadableWorkbook(input).use { workbook ->
                val sheet = workbook.firstSheet
                sheet.openStream().use { rows ->
                    val iterator = rows.iterator()
                    if (!iterator.hasNext()) {
                        throw IllegalArgumentException("The selected workbook has no header row.")
                    }
                    val headers = uniqueHeaders(readRow(iterator.next()))
                    val data = mutableListOf<List<String>>()
                    while (iterator.hasNext()) {
                        val cells = readRow(iterator.next())
                        data += headers.indices.map { index -> cells.getOrNull(index).orEmpty().trim() }
                    }
                    StructuredTable(headers, data)
                }
            }
        }

    private fun readRow(row: org.dhatim.fastexcel.reader.Row): List<String> =
        (0 until row.cellCount).map(row::getCellText)

    private fun uniqueHeaders(rawHeaders: List<String>): List<String> {
        require(rawHeaders.isNotEmpty()) { "The selected file has no columns." }
        val seen = mutableMapOf<String, Int>()
        return rawHeaders.mapIndexed { index, raw ->
            val base = raw.removePrefix("\uFEFF").trim().ifEmpty { "Column ${index + 1}" }
            val occurrence = (seen[base] ?: 0) + 1
            seen[base] = occurrence
            if (occurrence == 1) base else "$base ($occurrence)"
        }
    }
}
