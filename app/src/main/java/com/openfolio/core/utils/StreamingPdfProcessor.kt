package com.openfolio.core.utils

import android.content.Context
import android.net.Uri
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException
import com.tom_roush.pdfbox.rendering.ImageType
import com.tom_roush.pdfbox.rendering.PDFRenderer
import com.tom_roush.pdfbox.text.PDFTextStripper
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.IOException
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

data class PdfPageContent(
    val pageNumber: Int,
    val totalPages: Int,
    val text: String,
    val usedOcr: Boolean,
    val ocrLimitReached: Boolean = false,
)

data class StreamingPdfResult(
    val totalPages: Int,
    val ocrPagesProcessed: Int,
    val ocrLimitExceeded: Boolean,
)

/**
 * Memory-bounded, page-by-page PDF and OCR processor.
 *
 * Uses PDFBox mixed memory mode (8 MB RAM cap spilling to disk scratch), processes one page at a time,
 * lazily initializes PDFRenderer and ML Kit TextRecognition only for low-text pages, renders RGB_565
 * bitmaps, recycles bitmaps immediately, and respects coroutine cancellation between pages.
 */
@Singleton
class StreamingPdfProcessor @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    suspend fun processPages(
        file: File,
        password: String? = null,
        maxOcrPages: Int = DEFAULT_MAX_OCR_PAGES,
        onPage: suspend (PdfPageContent) -> Unit,
    ): StreamingPdfResult = withContext(Dispatchers.IO) {
        val scratchRoot = File(context.cacheDir, SCRATCH_DIR_NAME).apply { mkdirs() }
        cleanupStaleScratch(scratchRoot)
        val jobScratch = File(scratchRoot, UUID.randomUUID().toString()).apply { mkdirs() }
        val memorySetting = MemoryUsageSetting.setupMixed(MAX_PDF_MAIN_MEMORY_BYTES)
            .setTempDir(jobScratch)

        var recognizer: TextRecognizer? = null
        try {
            val document = loadDocument(file, password, memorySetting)
            document.use { doc ->
                val totalPages = doc.numberOfPages
                val stripper = PDFTextStripper().apply {
                    sortByPosition = true
                    suppressDuplicateOverlappingText = true
                }
                var renderer: PDFRenderer? = null
                var ocrPagesUsed = 0
                var ocrLimitExceeded = false

                for (pageNumber in 1..totalPages) {
                    currentCoroutineContext().ensureActive()
                    stripper.startPage = pageNumber
                    stripper.endPage = pageNumber
                    val digitalText = stripper.getText(doc)
                    var resolvedText = digitalText
                    var usedOcr = false
                    var limitHitOnPage = false

                    if (digitalText.trim().length < MIN_DIGITAL_TEXT_LENGTH) {
                        if (ocrPagesUsed < maxOcrPages) {
                            ocrPagesUsed++
                            usedOcr = true
                            val activeRenderer = renderer ?: PDFRenderer(doc).also { renderer = it }
                            val activeRecognizer = recognizer
                                ?: TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
                                    .also { recognizer = it }

                            val bitmap = activeRenderer.renderImageWithDPI(
                                pageNumber - 1,
                                OCR_DPI,
                                ImageType.RGB,
                            )
                            try {
                                currentCoroutineContext().ensureActive()
                                val visionText = activeRecognizer
                                    .process(InputImage.fromBitmap(bitmap, 0))
                                    .awaitCancellable()
                                val ocrText = reconstructLinesFromVisionText(visionText)
                                resolvedText = when {
                                    ocrText.isNotBlank() && digitalText.isNotBlank() -> "$digitalText\n$ocrText"
                                    ocrText.isNotBlank() -> ocrText
                                    else -> digitalText
                                }
                            } finally {
                                bitmap.recycle()
                            }
                        } else {
                            ocrLimitExceeded = true
                            limitHitOnPage = true
                        }
                    }

                    onPage(
                        PdfPageContent(
                            pageNumber = pageNumber,
                            totalPages = totalPages,
                            text = resolvedText,
                            usedOcr = usedOcr,
                            ocrLimitReached = limitHitOnPage,
                        ),
                    )
                }

                StreamingPdfResult(
                    totalPages = totalPages,
                    ocrPagesProcessed = ocrPagesUsed,
                    ocrLimitExceeded = ocrLimitExceeded,
                )
            }
        } finally {
            try {
                recognizer?.close()
            } catch (_: Exception) {
            }
            jobScratch.deleteRecursively()
        }
    }

    suspend fun extractImageText(file: File): String = withContext(Dispatchers.IO) {
        currentCoroutineContext().ensureActive()
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        try {
            val image = InputImage.fromFilePath(context, Uri.fromFile(file))
            val visionText = recognizer.process(image).awaitCancellable()
            reconstructLinesFromVisionText(visionText)
        } finally {
            try {
                recognizer.close()
            } catch (_: Exception) {
            }
        }
    }

    private fun loadDocument(
        file: File,
        password: String?,
        memorySetting: MemoryUsageSetting,
    ): PDDocument {
        return try {
            if (password.isNullOrBlank()) {
                PDDocument.load(file, memorySetting)
            } else {
                PDDocument.load(file, password, memorySetting)
            }
        } catch (e: Exception) {
            val isPassword = e is InvalidPasswordException ||
                e.message?.contains("password", ignoreCase = true) == true ||
                e.cause is InvalidPasswordException
            if (isPassword) {
                if (e is InvalidPasswordException) throw e
                throw IOException("This PDF is password-protected. Please enter password.", e)
            }
            throw e
        }
    }

    internal fun cleanupStaleScratch(scratchRoot: File, maxAgeMillis: Long = STALE_SCRATCH_MAX_AGE_MS) {
        val children = scratchRoot.listFiles() ?: return
        val cutoff = System.currentTimeMillis() - maxAgeMillis
        for (child in children) {
            if (child.lastModified() < cutoff) {
                child.deleteRecursively()
            }
        }
    }

    internal companion object {
        const val MAX_PDF_MAIN_MEMORY_BYTES = 8L * 1024L * 1024L
        const val MIN_DIGITAL_TEXT_LENGTH = 40
        const val DEFAULT_MAX_OCR_PAGES = 150
        const val OCR_DPI = 150f
        const val SCRATCH_DIR_NAME = "pdfbox_scratch"
        const val STALE_SCRATCH_MAX_AGE_MS = 6L * 60L * 60L * 1000L

        fun reconstructLinesFromVisionText(visionText: Text): String {
            val allLines = mutableListOf<Text.Line>()
            for (block in visionText.textBlocks) {
                allLines.addAll(block.lines)
            }
            if (allLines.isEmpty()) {
                return visionText.text
            }
            val withBounds = allLines.filter { it.boundingBox != null && it.text.isNotBlank() }
            if (withBounds.isEmpty()) {
                return visionText.text
            }

            val sortedByY = withBounds.sortedWith(
                compareBy<Text.Line> { it.boundingBox!!.centerY() }
                    .thenBy { it.boundingBox!!.left },
            )
            val visualRows = mutableListOf<MutableList<Text.Line>>()
            val rowBounds = mutableListOf<Pair<Float, Float>>()

            for (line in sortedByY) {
                val box = line.boundingBox!!
                val centerY = box.centerY().toFloat()
                var matchedRowIdx = -1
                for (i in visualRows.lastIndex downTo 0) {
                    val (top, bottom) = rowBounds[i]
                    val rowHeight = (bottom - top).coerceAtLeast(8f)
                    val tolerance = rowHeight * 0.45f
                    if (centerY in (top - tolerance)..(bottom + tolerance)) {
                        matchedRowIdx = i
                        break
                    }
                    if (centerY - bottom > rowHeight * 1.5f) {
                        break
                    }
                }
                if (matchedRowIdx >= 0) {
                    visualRows[matchedRowIdx].add(line)
                    val (oldTop, oldBottom) = rowBounds[matchedRowIdx]
                    rowBounds[matchedRowIdx] = minOf(oldTop, box.top.toFloat()) to maxOf(oldBottom, box.bottom.toFloat())
                } else {
                    visualRows.add(mutableListOf(line))
                    rowBounds.add(box.top.toFloat() to box.bottom.toFloat())
                }
            }

            return visualRows.joinToString("\n") { row ->
                row.sortedBy { it.boundingBox!!.left }
                    .joinToString("  ") { it.text.trim() }
            }
        }
    }
}
