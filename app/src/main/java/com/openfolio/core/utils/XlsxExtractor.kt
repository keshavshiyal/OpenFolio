package com.openfolio.core.utils

import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import javax.inject.Inject
import javax.xml.parsers.SAXParser
import javax.xml.parsers.SAXParserFactory
import org.xml.sax.Attributes
import org.xml.sax.helpers.DefaultHandler

class XlsxExtractor @Inject constructor() {
    fun extract(file: File, headerRowIndex: Int = 0): StructuredTable {
        require(headerRowIndex >= 0) { "Header row index must be at least 0." }
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

    fun read(file: File, headerRowIndex: Int = 0): StructuredTable = extract(file, headerRowIndex)

    fun readPreviewRows(file: File, limit: Int = 100): List<List<String>> {
        require(limit > 0) { "Limit must be at least 1." }
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
            val sheetHandler = SheetHandler(sharedStrings, rowLimit = limit)
            try {
                zipFile.getInputStream(worksheetEntry).use { input ->
                    parser.parse(input, sheetHandler)
                }
            } catch (_: MaxRowsReachedException) {
                // Expected when rowLimit is reached
            }

            return sheetHandler.allRows.take(limit)
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

private class MaxRowsReachedException : org.xml.sax.SAXException("Max rows reached")

private class SheetHandler(
    private val sharedStrings: List<String>,
    private val rowLimit: Int = Int.MAX_VALUE,
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
                while (allRows.size < targetRow && allRows.size < rowLimit) {
                    allRows.add(emptyList())
                }
                if (allRows.size < rowLimit) {
                    if (allRows.size == targetRow) {
                        allRows.add(ArrayList(currentRow))
                    } else if (targetRow < allRows.size) {
                        allRows[targetRow] = ArrayList(currentRow)
                    }
                }
                nextDefaultRowIndex = targetRow + 1
                if (allRows.size >= rowLimit) {
                    throw MaxRowsReachedException()
                }
            }
        }
    }
}
