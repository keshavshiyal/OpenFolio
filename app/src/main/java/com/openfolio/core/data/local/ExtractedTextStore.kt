package com.openfolio.core.data.local

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import javax.inject.Inject
import javax.inject.Singleton

/**
 * File-backed sidecar storage for extracted document text, keeping multi-megabyte PDF text
 * out of SQLCipher / Room CursorWindow limits while supporting O(1)-memory page-by-page streaming.
 */
@Singleton
class ExtractedTextStore internal constructor(
    private val directory: File,
) {
    @Inject
    constructor(@ApplicationContext context: Context) : this(File(context.filesDir, DIRECTORY_NAME))

    fun openWriter(documentId: String): BufferedWriter {
        if (!directory.exists()) {
            directory.mkdirs()
        }
        val target = fileFor(documentId)
        return BufferedWriter(
            OutputStreamWriter(FileOutputStream(target), Charsets.UTF_8),
            BUFFER_SIZE,
        )
    }

    fun writePage(writer: BufferedWriter, pageNumber: Int, pageText: String) {
        writer.write(PAGE_MARKER_PREFIX)
        writer.write(pageNumber.toString())
        writer.write(PAGE_MARKER_SUFFIX)
        writer.newLine()
        val trimmedEnd = pageText.trimEnd('\r', '\n')
        if (trimmedEnd.isNotEmpty()) {
            writer.write(trimmedEnd)
            writer.newLine()
        }
    }

    inline fun forEachPage(documentId: String, onPage: (pageNumber: Int, pageText: String) -> Unit) {
        val file = fileFor(documentId)
        if (!file.exists()) return
        BufferedReader(InputStreamReader(FileInputStream(file), Charsets.UTF_8), BUFFER_SIZE).use { reader ->
            var currentPage = -1
            val buffer = StringBuilder()
            var hasLineInPage = false
            while (true) {
                val line = reader.readLine() ?: break
                val parsedPage = parsePageMarker(line)
                if (parsedPage != null) {
                    if (currentPage > 0) {
                        onPage(currentPage, buffer.toString())
                    }
                    currentPage = parsedPage
                    buffer.setLength(0)
                    hasLineInPage = false
                } else {
                    if (currentPage < 0) {
                        currentPage = 1
                    }
                    if (hasLineInPage) {
                        buffer.append('\n')
                    }
                    buffer.append(line)
                    hasLineInPage = true
                }
            }
            if (currentPage > 0) {
                onPage(currentPage, buffer.toString())
            }
        }
    }

    fun readPagesRange(documentId: String, pageRange: IntRange): Map<Int, String> {
        if (pageRange.isEmpty()) return emptyMap()
        val result = LinkedHashMap<Int, String>()
        forEachPage(documentId) { pageNumber, pageText ->
            if (pageNumber in pageRange) {
                result[pageNumber] = pageText
            }
        }
        return result
    }

    fun getMaxPageNumber(documentId: String): Int {
        val file = fileFor(documentId)
        if (!file.exists()) return 0
        var maxPage = 0
        BufferedReader(InputStreamReader(FileInputStream(file), Charsets.UTF_8), BUFFER_SIZE).use { reader ->
            while (true) {
                val line = reader.readLine() ?: break
                val parsed = parsePageMarker(line)
                if (parsed != null && parsed > maxPage) {
                    maxPage = parsed
                }
            }
        }
        return maxPage
    }

    fun readSample(documentId: String, maxChars: Int = 16_000): String {
        if (maxChars <= 0) return ""
        val file = fileFor(documentId)
        if (!file.exists()) return ""
        val sb = StringBuilder(minOf(maxChars, 4096))
        BufferedReader(InputStreamReader(FileInputStream(file), Charsets.UTF_8), BUFFER_SIZE).use { reader ->
            while (sb.length < maxChars) {
                val line = reader.readLine() ?: break
                if (parsePageMarker(line) != null) continue
                if (sb.isEmpty() && line.isBlank()) continue
                if (sb.isNotEmpty()) {
                    if (sb.length + 1 >= maxChars) break
                    sb.append('\n')
                }
                val remaining = maxChars - sb.length
                if (line.length <= remaining) {
                    sb.append(line)
                } else {
                    sb.append(line, 0, remaining)
                    break
                }
            }
        }
        return sb.toString()
    }

    fun hasText(documentId: String): Boolean {
        val file = fileFor(documentId)
        if (!file.exists() || file.length() == 0L) return false
        BufferedReader(InputStreamReader(FileInputStream(file), Charsets.UTF_8), BUFFER_SIZE).use { reader ->
            while (true) {
                val line = reader.readLine() ?: return false
                if (parsePageMarker(line) == null && line.isNotBlank()) {
                    return true
                }
            }
        }
    }

    fun delete(documentId: String) {
        val file = fileFor(documentId)
        if (file.exists()) {
            file.delete()
        }
    }

    fun pathFor(documentId: String): String = fileFor(documentId).absolutePath

    @PublishedApi
    internal fun fileFor(documentId: String): File {
        require(
            documentId.isNotBlank() &&
                !documentId.contains('/') &&
                !documentId.contains('\\') &&
                !documentId.contains(".."),
        ) {
            "Invalid documentId for sidecar storage: $documentId"
        }
        return File(directory, "$documentId.txt")
    }

    @PublishedApi
    internal fun parsePageMarker(line: String): Int? {
        val prefix = when {
            line.startsWith(PAGE_MARKER_PREFIX) && line.endsWith(PAGE_MARKER_SUFFIX) -> PAGE_MARKER_PREFIX
            line.startsWith(ALT_PAGE_MARKER_PREFIX) && line.endsWith(PAGE_MARKER_SUFFIX) -> ALT_PAGE_MARKER_PREFIX
            else -> return null
        }
        val numPart = line.substring(prefix.length, line.length - PAGE_MARKER_SUFFIX.length)
        return numPart.toIntOrNull()?.takeIf { it > 0 }
    }

    companion object {
        const val DIRECTORY_NAME = "extracted_text"
        const val PAGE_MARKER_PREFIX = "<<<OPENFOLIO_PAGE:"
        const val ALT_PAGE_MARKER_PREFIX = "<<<PAGE:"
        const val PAGE_MARKER_SUFFIX = ">>>"
        const val BUFFER_SIZE = 32 * 1024
    }
}
