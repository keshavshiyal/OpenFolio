package com.openfolio.core.utils

import android.content.Context
import com.openfolio.core.data.local.LocalFileCrypto
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import javax.inject.Inject
import javax.xml.XMLConstants
import javax.xml.parsers.SAXParser
import javax.xml.parsers.SAXParserFactory
import org.xml.sax.Attributes
import org.xml.sax.helpers.DefaultHandler

class XlsxExtractor @Inject constructor(
    private val fileCrypto: LocalFileCrypto? = null,
    @ApplicationContext private val context: Context? = null,
) {
    constructor() : this(null, null)

    fun extract(file: File, headerRowIndex: Int = 0): StructuredTable {
        require(headerRowIndex >= 0) { "Header row index must be at least 0." }
        return withReadableXlsxFile(file) { readableFile ->
            val zip = try {
                ZipFile(readableFile)
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
                    val sharedStringsHandler = SharedStringsHandler(MAX_SHARED_STRINGS)
                    openBoundedZipEntryStream(zipFile, sharedStringsEntry, MAX_XML_ENTRY_BYTES).use { input ->
                        parser.parse(input, sharedStringsHandler)
                    }
                    sharedStrings.addAll(sharedStringsHandler.strings)
                }

                val worksheetEntry = findWorksheetEntry(zipFile, parser)
                val sheetHandler = SheetHandler(sharedStrings, rowLimit = MAX_WORKSHEET_ROWS)
                openBoundedZipEntryStream(zipFile, worksheetEntry, MAX_XML_ENTRY_BYTES).use { input ->
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
                StructuredTable(headers, dataRows)
            }
        }
    }

    fun read(file: File, headerRowIndex: Int = 0): StructuredTable = extract(file, headerRowIndex)

    fun readPreviewRows(file: File, limit: Int = 100): List<List<String>> {
        require(limit > 0) { "Limit must be at least 1." }
        val cappedLimit = minOf(limit, MAX_WORKSHEET_ROWS)
        return withReadableXlsxFile(file) { readableFile ->
            val zip = try {
                ZipFile(readableFile)
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
                    val sharedStringsHandler = SharedStringsHandler(MAX_SHARED_STRINGS)
                    openBoundedZipEntryStream(zipFile, sharedStringsEntry, MAX_XML_ENTRY_BYTES).use { input ->
                        parser.parse(input, sharedStringsHandler)
                    }
                    sharedStrings.addAll(sharedStringsHandler.strings)
                }

                val worksheetEntry = findWorksheetEntry(zipFile, parser)
                val sheetHandler = SheetHandler(sharedStrings, rowLimit = cappedLimit)
                try {
                    openBoundedZipEntryStream(zipFile, worksheetEntry, MAX_XML_ENTRY_BYTES).use { input ->
                        parser.parse(input, sheetHandler)
                    }
                } catch (_: MaxRowsReachedException) {
                    // Expected when rowLimit is reached
                }

                sheetHandler.allRows.take(cappedLimit)
            }
        }
    }

    private inline fun <T> withReadableXlsxFile(file: File, block: (File) -> T): T {
        val crypto = fileCrypto ?: return block(file)
        val scratchDir = context?.cacheDir?.let { File(it, LocalFileCrypto.DEC_TEMP_DIR_NAME) }
            ?: file.parentFile
            ?: File(".")
        return crypto.withDecryptedTempFile(file, scratchDir, "xlsx", block)
    }

    private fun openBoundedZipEntryStream(
        zipFile: ZipFile,
        entry: ZipEntry,
        maxBytes: Long,
    ): InputStream {
        val reportedSize = entry.size
        if (reportedSize > maxBytes) {
            throw IllegalArgumentException("XLSX entry '${entry.name}' exceeds maximum permitted XML size.")
        }
        val compressedSize = entry.compressedSize
        if (reportedSize > 0L && compressedSize > 0L && reportedSize > compressedSize * MAX_COMPRESSION_RATIO) {
            throw IllegalArgumentException("XLSX entry '${entry.name}' exceeds safe compression ratio.")
        }
        return BoundedXmlInputStream(zipFile.getInputStream(entry), entry.name, maxBytes)
    }

    private fun findWorksheetEntry(zipFile: ZipFile, parser: SAXParser): ZipEntry {
        val workbookRelsEntry = zipFile.getEntry("xl/_rels/workbook.xml.rels")
        if (workbookRelsEntry != null) {
            val relsHandler = WorkbookRelsHandler()
            try {
                openBoundedZipEntryStream(zipFile, workbookRelsEntry, MAX_RELS_ENTRY_BYTES).use { input ->
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
            } catch (e: SecurityException) {
                throw e
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
        runCatching { factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true) }
        runCatching { factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
        runCatching { factory.setFeature("http://xml.org/sax/features/external-general-entities", false) }
        runCatching { factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
        runCatching { factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false) }
        return factory.newSAXParser()
    }

    private class BoundedXmlInputStream(
        delegate: InputStream,
        private val entryName: String,
        private val maxBytes: Long,
    ) : FilterInputStream(delegate) {
        private var bytesRead: Long = 0L

        override fun read(): Int {
            val b = super.read()
            if (b >= 0) {
                account(1L)
            }
            return b
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            val n = super.read(b, off, len)
            if (n > 0) {
                account(n.toLong())
            }
            return n
        }

        private fun account(delta: Long) {
            bytesRead += delta
            if (bytesRead > maxBytes) {
                throw IllegalArgumentException(
                    "XLSX entry '$entryName' exceeded maximum permitted uncompressed XML size ($maxBytes bytes).",
                )
            }
        }
    }

    companion object {
        const val MAX_XML_ENTRY_BYTES = 32L * 1024L * 1024L
        const val MAX_RELS_ENTRY_BYTES = 2L * 1024L * 1024L
        const val MAX_COMPRESSION_RATIO = 100L
        const val MAX_SHARED_STRINGS = 500_000
        const val MAX_WORKSHEET_ROWS = 200_000
        const val MAX_WORKSHEET_COLUMNS = 512
        const val MAX_CELL_CHARS = 32_768
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

private class SharedStringsHandler(
    private val maxStrings: Int,
) : DefaultHandler() {
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
        if (insideText && currentText.length < XlsxExtractor.MAX_CELL_CHARS) {
            val allowed = minOf(length, XlsxExtractor.MAX_CELL_CHARS - currentText.length)
            currentText.append(ch, start, allowed)
        }
    }

    override fun endElement(uri: String?, localName: String?, qName: String) {
        val tag = if (localName.isNullOrEmpty()) qName else localName
        when {
            tag.equals("t", ignoreCase = true) -> insideText = false
            tag.equals("si", ignoreCase = true) -> {
                if (strings.size >= maxStrings) {
                    throw IllegalArgumentException("XLSX shared strings exceed maximum allowed count ($maxStrings).")
                }
                strings.add(currentText.toString())
            }
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
                val resolvedRow = if (rowNum != null && rowNum > 0) rowNum - 1 else nextDefaultRowIndex
                if (resolvedRow >= XlsxExtractor.MAX_WORKSHEET_ROWS) {
                    throw IllegalArgumentException("XLSX row index exceeds maximum allowed limit.")
                }
                currentRowIndex = resolvedRow
            }
            tag.equals("c", ignoreCase = true) -> {
                currentCellValue.setLength(0)
                currentCellType = attributes.getValue("t")
                val cellRef = attributes.getValue("r")
                val resolvedCol = if (!cellRef.isNullOrBlank()) {
                    columnReferenceToIndex(cellRef)
                } else {
                    nextDefaultColIndex
                }
                if (resolvedCol >= XlsxExtractor.MAX_WORKSHEET_COLUMNS) {
                    currentCellColIndex = -1
                } else {
                    currentCellColIndex = resolvedCol
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
        if ((insideValueTag || insideInlineTextTag) && currentCellValue.length < XlsxExtractor.MAX_CELL_CHARS) {
            val allowed = minOf(length, XlsxExtractor.MAX_CELL_CHARS - currentCellValue.length)
            currentCellValue.append(ch, start, allowed)
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
                val targetCol = if (currentCellColIndex >= 0) currentCellColIndex else nextDefaultColIndex
                if (targetCol in 0 until XlsxExtractor.MAX_WORKSHEET_COLUMNS) {
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
            }
            tag.equals("row", ignoreCase = true) -> {
                val targetRow = if (currentRowIndex >= 0) currentRowIndex else nextDefaultRowIndex
                if (targetRow >= XlsxExtractor.MAX_WORKSHEET_ROWS) {
                    throw IllegalArgumentException("XLSX row count exceeds maximum allowed limit.")
                }
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
