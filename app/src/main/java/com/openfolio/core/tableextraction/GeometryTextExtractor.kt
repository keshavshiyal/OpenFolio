package com.openfolio.core.tableextraction

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.openfolio.core.utils.StreamingPdfProcessor
import com.openfolio.core.utils.awaitCancellable
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException
import com.tom_roush.pdfbox.rendering.ImageType
import com.tom_roush.pdfbox.rendering.PDFRenderer
import com.tom_roush.pdfbox.text.PDFTextStripper
import com.tom_roush.pdfbox.text.TextPosition
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

@Singleton
class GeometryTextExtractor @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    suspend fun extractWords(
        file: File,
        fileName: String,
        password: String? = null,
    ): List<ExtractedWord> =
        withContext(Dispatchers.IO) {
            val allWords = mutableListOf<ExtractedWord>()
            forEachPageWords(file, fileName, password) { _, _, words ->
                allWords.addAll(words)
            }
            allWords
        }

    suspend fun forEachPageWords(
        file: File,
        fileName: String,
        password: String? = null,
        maxOcrPages: Int = MAX_OCR_PAGES,
        onPageWords: suspend (pageIndex: Int, totalPages: Int, words: List<ExtractedWord>) -> Unit,
    ): Unit = withContext(Dispatchers.IO) {
        val extension = fileName.substringAfterLast('.', file.extension).lowercase()
        when (extension) {
            "pdf" -> streamPdfWords(file, password, maxOcrPages, onPageWords)
            "png", "jpg", "jpeg" -> {
                val words = extractFromImage(file)
                onPageWords(1, 1, words)
            }
            else -> throw IllegalArgumentException("Unsupported file type for geometry extraction: $fileName")
        }
    }

    private suspend fun streamPdfWords(
        file: File,
        password: String? = null,
        maxOcrPages: Int = MAX_OCR_PAGES,
        onPageWords: suspend (pageIndex: Int, totalPages: Int, words: List<ExtractedWord>) -> Unit,
    ) {
        val scratchRoot = File(context.cacheDir, StreamingPdfProcessor.SCRATCH_DIR_NAME).apply { mkdirs() }
        val jobScratch = File(scratchRoot, UUID.randomUUID().toString()).apply { mkdirs() }
        val memorySetting = MemoryUsageSetting.setupMixed(StreamingPdfProcessor.MAX_PDF_MAIN_MEMORY_BYTES)
            .setTempDir(jobScratch)

        var recognizer: TextRecognizer? = null
        try {
            val doc = try {
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

            doc.use { document ->
                val pageCount = document.numberOfPages
                val stripper = WordPositionStripper()
                var renderer: PDFRenderer? = null
                var ocrPagesUsed = 0

                for (p in 1..pageCount) {
                    currentCoroutineContext().ensureActive()
                    stripper.pageIdx = p
                    stripper.startPage = p
                    stripper.endPage = p
                    stripper.getText(document)
                    stripper.flush()

                    val pageWords = if (stripper.words.size >= MIN_DIGITAL_WORDS_PER_PAGE) {
                        ArrayList(stripper.words)
                    } else if (ocrPagesUsed < maxOcrPages) {
                        ocrPagesUsed++
                        val activeRenderer = renderer ?: PDFRenderer(document).also { renderer = it }
                        val activeRecognizer = recognizer
                            ?: TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
                                .also { recognizer = it }
                        ocrSinglePage(activeRenderer, activeRecognizer, p)
                    } else {
                        ArrayList(stripper.words)
                    }

                    stripper.words.clear()
                    onPageWords(p, pageCount, pageWords)
                }
            }
        } finally {
            try {
                recognizer?.close()
            } catch (_: Exception) {
            }
            jobScratch.deleteRecursively()
        }
    }

    private suspend fun ocrSinglePage(
        renderer: PDFRenderer,
        recognizer: TextRecognizer,
        pageIndex: Int,
    ): List<ExtractedWord> {
        var bitmap: Bitmap? = null
        return try {
            bitmap = renderer.renderImageWithDPI(pageIndex - 1, OCR_DPI, ImageType.RGB)
            currentCoroutineContext().ensureActive()
            val inputImage = InputImage.fromBitmap(bitmap, 0)
            val visionText = recognizer.process(inputImage).awaitCancellable()
            visionTextToWords(visionText, pageIndex)
        } finally {
            bitmap?.recycle()
        }
    }

    private suspend fun extractFromImage(file: File): List<ExtractedWord> {
        currentCoroutineContext().ensureActive()
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        return try {
            val image = InputImage.fromFilePath(context, Uri.fromFile(file))
            val visionText = recognizer.process(image).awaitCancellable()
            visionTextToWords(visionText, pageIndex = 1)
        } finally {
            try {
                recognizer.close()
            } catch (_: Exception) {
            }
        }
    }

    private fun visionTextToWords(
        visionText: com.google.mlkit.vision.text.Text,
        pageIndex: Int,
    ): List<ExtractedWord> {
        val words = mutableListOf<ExtractedWord>()
        for (block in visionText.textBlocks) {
            for (line in block.lines) {
                for (element in line.elements) {
                    val rect = element.boundingBox
                    if (rect != null && element.text.isNotBlank()) {
                        words.add(
                            ExtractedWord(
                                text = element.text.trim(),
                                bbox = BoundingBox(
                                    left = rect.left.toFloat(),
                                    top = rect.top.toFloat(),
                                    right = rect.right.toFloat(),
                                    bottom = rect.bottom.toFloat(),
                                ),
                                pageIndex = pageIndex,
                            ),
                        )
                    }
                }
            }
        }
        return words
    }

    private class WordPositionStripper : PDFTextStripper() {
        val words = mutableListOf<ExtractedWord>()
        private val wordBuffer = StringBuilder()
        private var startX = 0f
        private var startY = 0f
        private var endX = 0f
        private var maxHeight = 0f
        var pageIdx = 0

        init {
            sortByPosition = true
            suppressDuplicateOverlappingText = true
        }

        override fun processTextPosition(text: TextPosition) {
            val unicode = text.unicode
            if (unicode.isNullOrBlank()) {
                flush()
                return
            }

            val x = text.xDirAdj
            val y = text.yDirAdj
            val width = text.widthDirAdj
            val height = text.heightDir.coerceAtLeast(6f)

            if (wordBuffer.isNotEmpty()) {
                val deltaX = x - endX
                val deltaY = kotlin.math.abs(y - startY)
                if (deltaX > width * 0.7f || deltaX < -1f || deltaY > height * 0.4f) {
                    flush()
                }
            }

            if (wordBuffer.isEmpty()) {
                startX = x
                startY = y
                maxHeight = height
            }
            wordBuffer.append(unicode)
            endX = x + width
            maxHeight = maxOf(maxHeight, height)
        }

        fun flush() {
            if (wordBuffer.isNotEmpty()) {
                val str = wordBuffer.toString().trim()
                if (str.isNotEmpty()) {
                    words.add(
                        ExtractedWord(
                            text = str,
                            bbox = BoundingBox(
                                left = startX,
                                top = startY - maxHeight,
                                right = endX,
                                bottom = startY,
                            ),
                            pageIndex = pageIdx,
                        ),
                    )
                }
                wordBuffer.clear()
            }
        }
    }

    private companion object {
        const val OCR_DPI = 150f
        const val MAX_OCR_PAGES = 150
        const val MIN_DIGITAL_WORDS_PER_PAGE = 5
    }
}
