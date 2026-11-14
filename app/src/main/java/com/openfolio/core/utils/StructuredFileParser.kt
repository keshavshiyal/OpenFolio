package com.openfolio.core.utils

import com.opencsv.CSVReader
import java.io.File
import java.io.FileInputStream
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import javax.inject.Inject
import javax.xml.parsers.SAXParser
import javax.xml.parsers.SAXParserFactory
import org.xml.sax.Attributes
import org.xml.sax.helpers.DefaultHandler

data class StructuredTable(
    val headers: List<String>,
    val rows: List<List<String>>,
)

class StructuredFileParser @Inject constructor() {
    fun read(file: File, headerRowNumber: Int = 1): StructuredTable {
        require(headerRowNumber >= 1) { "Header row number must be at least 1." }
        val headerRowIndex = headerRowNumber - 1
        return when (file.extension.lowercase()) {
            "csv" -> readCsv(file, headerRowIndex)
            "xlsx" -> readXlsx(file, headerRowIndex)
            else -> throw IllegalArgumentException(
                "Structured mapping supports CSV and XLSX files only.",
            )
        }
    }

    private fun readCsv(file: File, headerRowIndex: Int): StructuredTable =
        InputStreamReader(FileInputStream(file), StandardCharsets.UTF_8).use { input ->
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

    private fun readXlsx(file: File, headerRowIndex: Int): StructuredTable {
        val zip = try {
            ZipFile(file)
        } catch (exception: Exception) {
            throw IllegalArgumentException(
                "Unable to open Excel file. Ensure it is a valid, unencrypted XLSX file.",
                exception,
            )
        }

        zip.use { zipFile ->
            val parser = createSaxParser()

            val sharedStrings = mutableListOf<String>()
            val sharedStringsEntry = zipFile.getEntry("xl/sharedStrings.xml")
                ?: zipFile.getEntry("xl/SharedStrings.xml")
            if (sharedStringsEntry != null) {
                val sharedStringsHandler = SharedStringsHandler()
                zipFile.getInputStream(sharedStringsEntry).use { input ->
                    parser.parse(input, sharedStringsHandler)
                }
                sharedStrings.addAll(sharedStringsHandler.strings)
            }

            val worksheetEntry = findWorksheetEntry(zipFile, parser)
            val sheetHandler = SheetHandler(sharedStrings)
            zipFile.getInputStream(worksheetEntry).use { input ->
                parser.parse(input, sheetHandler)
            }

            val allRows = sheetHandler.allRows
            if (headerRowIndex !in allRows.indices) {
                throw IllegalArgumentException("The specified header row does not exist in this file.")
            }

            val headerRow = allRows[headerRowIndex]
            val headers = uniqueHeaders(headerRow)
            val dataRows = allRows.drop(headerRowIndex + 1)
                .filter { row -> row.any(String::isNotBlank) }
                .map { row ->
                    headers.indices.map { index -> row.getOrNull(index).orEmpty().trim() }
                }
            return StructuredTable(headers, dataRows)
        }
    }

    private fun findWorksheetEntry(zipFile: ZipFile, parser: SAXParser): ZipEntry {
        val workbookRelsEntry = zipFile.getEntry("xl/_rels/workbook.xml.rels")
        if (workbookRelsEntry != null) {
            val relsHandler = WorkbookRelsHandler()
            try {
                zipFile.getInputStream(workbookRelsEntry).use { input ->
                    parser.parse(input, relsHandler)
                }
                val target = relsHandler.firstWorksheetTarget
                if (!target.isNullOrBlank()) {
                    val normalizedPath = when {
                        target.startsWith("/") -> target.removePrefix("/")
                        target.startsWith("xl/") -> target
                        else -> "xl/$target"
                    }
                    val targetEntry = zipFile.getEntry(normalizedPath)
                    if (targetEntry != null) return targetEntry
                }
            } catch (_: Exception) {
                // Fallback to name-based discovery
            }
        }

        zipFile.getEntry("xl/worksheets/sheet1.xml")?.let { return it }
        zipFile.getEntry("xl/worksheets/Sheet1.xml")?.let { return it }

        val worksheetEntries = zipFile.entries().asSequence()
            .filter { entry ->
                entry.name.startsWith("xl/worksheets/", ignoreCase = true) &&
                    entry.name.endsWith(".xml", ignoreCase = true)
            }
            .sortedBy { it.name }
            .toList()

        return worksheetEntries.firstOrNull()
            ?: throw IllegalArgumentException("The selected workbook contains no worksheets.")
    }

    private fun createSaxParser(): SAXParser {
        val factory = SAXParserFactory.newInstance()
        factory.isNamespaceAware = false
        factory.isValidating = false
        try {
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false)
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false)
            factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
        } catch (_: Exception) {
            // Android platform XML parser may not recognize Xerces feature URIs
        }
        return factory.newSAXParser()
    }

    private fun uniqueHeaders(rawHeaders: List<String>): List<String> {
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
}

internal fun columnReferenceToIndex(cellRef: String): Int {
    var col = 0
    var hasLetters = false
    for (ch in cellRef) {
        if (ch in 'A'..'Z') {
            col = col * 26 + (ch - 'A' + 1)
            hasLetters = true
        } else if (ch in 'a'..'z') {
            col = col * 26 + (ch - 'a' + 1)
            hasLetters = true
        } else {
            break
        }
    }
    return if (hasLetters && col > 0) col - 1 else -1
}

private class WorkbookRelsHandler : DefaultHandler() {
    var firstWorksheetTarget: String? = null
        private set

    override fun startElement(
        uri: String?,
        localName: String?,
        qName: String,
        attributes: Attributes,
    ) {
        val tag = if (localName.isNullOrEmpty()) qName else localName
        if (tag.equals("Relationship", ignoreCase = true) && firstWorksheetTarget == null) {
            val type = attributes.getValue("Type").orEmpty()
            if (type.contains("worksheet", ignoreCase = true)) {
                firstWorksheetTarget = attributes.getValue("Target")
            }
        }
    }
}

private class SharedStringsHandler : DefaultHandler() {
    val strings = mutableListOf<String>()
    private val currentText = StringBuilder()
    private var insideText = false

    override fun startElement(
        uri: String?,
        localName: String?,
        qName: String,
        attributes: Attributes,
    ) {
        val tag = if (localName.isNullOrEmpty()) qName else localName
        when {
            tag.equals("si", ignoreCase = true) -> currentText.setLength(0)
            tag.equals("t", ignoreCase = true) -> insideText = true
        }
    }

    override fun characters(ch: CharArray, start: Int, length: Int) {
        if (insideText) {
            currentText.append(ch, start, length)
        }
    }

    override fun endElement(uri: String?, localName: String?, qName: String) {
        val tag = if (localName.isNullOrEmpty()) qName else localName
        when {
            tag.equals("t", ignoreCase = true) -> insideText = false
            tag.equals("si", ignoreCase = true) -> strings.add(currentText.toString())
        }
    }
}

private class SheetHandler(
    private val sharedStrings: List<String>,
) : DefaultHandler() {
    val allRows = mutableListOf<List<String>>()
    private val currentRow = mutableListOf<String>()
    private val currentCellValue = StringBuilder()
    private var currentCellType: String? = null
    private var currentCellColIndex: Int = -1
    private var nextDefaultColIndex: Int = 0
    private var currentRowIndex: Int = -1
    private var nextDefaultRowIndex: Int = 0
    private var insideValueTag = false
    private var insideInlineTextTag = false

    override fun startElement(
        uri: String?,
        localName: String?,
        qName: String,
        attributes: Attributes,
    ) {
        val tag = if (localName.isNullOrEmpty()) qName else localName
        when {
            tag.equals("row", ignoreCase = true) -> {
                currentRow.clear()
                nextDefaultColIndex = 0
                val rowNum = attributes.getValue("r")?.toIntOrNull()
                currentRowIndex = if (rowNum != null && rowNum > 0) rowNum - 1 else nextDefaultRowIndex
            }
            tag.equals("c", ignoreCase = true) -> {
                currentCellValue.setLength(0)
                currentCellType = attributes.getValue("t")
                val cellRef = attributes.getValue("r")
                currentCellColIndex = if (!cellRef.isNullOrBlank()) {
                    columnReferenceToIndex(cellRef)
                } else {
                    nextDefaultColIndex
                }
            }
            tag.equals("v", ignoreCase = true) -> {
                insideValueTag = true
            }
            tag.equals("t", ignoreCase = true) -> {
                insideInlineTextTag = true
            }
        }
    }

    override fun characters(ch: CharArray, start: Int, length: Int) {
        if (insideValueTag || insideInlineTextTag) {
            currentCellValue.append(ch, start, length)
        }
    }

    override fun endElement(uri: String?, localName: String?, qName: String) {
        val tag = if (localName.isNullOrEmpty()) qName else localName
        when {
            tag.equals("v", ignoreCase = true) -> {
                insideValueTag = false
            }
            tag.equals("t", ignoreCase = true) -> {
                insideInlineTextTag = false
            }
            tag.equals("c", ignoreCase = true) -> {
                val rawValue = currentCellValue.toString()
                val cellValue = when (currentCellType?.lowercase()) {
                    "s" -> {
                        val index = rawValue.trim().toIntOrNull()
                        if (index != null && index in sharedStrings.indices) {
                            sharedStrings[index]
                        } else {
                            rawValue
                        }
                    }
                    "b" -> {
                        when (rawValue.trim()) {
                            "1" -> "TRUE"
                            "0" -> "FALSE"
                            else -> rawValue
                        }
                    }
                    else -> rawValue
                }

                val targetCol = if (currentCellColIndex >= 0) currentCellColIndex else nextDefaultColIndex
                while (currentRow.size < targetCol) {
                    currentRow.add("")
                }
                if (currentRow.size == targetCol) {
                    currentRow.add(cellValue)
                } else {
                    currentRow[targetCol] = cellValue
                }
                nextDefaultColIndex = targetCol + 1
            }
            tag.equals("row", ignoreCase = true) -> {
                val targetRow = if (currentRowIndex >= 0) currentRowIndex else nextDefaultRowIndex
                while (allRows.size < targetRow) {
                    allRows.add(emptyList())
                }
                if (allRows.size == targetRow) {
                    allRows.add(ArrayList(currentRow))
                } else {
                    allRows[targetRow] = ArrayList(currentRow)
                }
                nextDefaultRowIndex = targetRow + 1
            }
        }
    }
}
