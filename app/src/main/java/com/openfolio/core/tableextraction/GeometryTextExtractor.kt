package com.openfolio.core.tableextraction

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException
import com.tom_roush.pdfbox.rendering.PDFRenderer
import com.tom_roush.pdfbox.text.PDFTextStripper
import com.tom_roush.pdfbox.text.TextPosition
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
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
            val extension = fileName.substringAfterLast('.', file.extension).lowercase()
            when (extension) {
                "pdf" -> extractFromPdf(file, password)
                "png", "jpg", "jpeg" -> extractFromImage(file)
                else -> throw IllegalArgumentException("Unsupported file type for geometry extraction: $fileName")
            }
        }

    private fun extractFromPdf(file: File, password: String? = null): List<ExtractedWord> {
        val doc = try {
            if (password.isNullOrBlank()) {
                PDDocument.load(file)
            } else {
                PDDocument.load(file, password)
            }
        } catch (e: Exception) {
            val isPassword = e is InvalidPasswordException ||
                e.message?.contains("password", ignoreCase = true) == true ||
                e.cause is InvalidPasswordException
            if (isPassword) {
                if (e is InvalidPasswordException) throw e
                throw java.io.IOException("This PDF is password-protected. Please enter password.", e)
            }
            throw e
        }

        return doc.use { document ->
            val pageCount = document.numberOfPages
            val digitalWords = mutableListOf<ExtractedWord>()

            val stripper = object : PDFTextStripper() {
                val words = mutableListOf<ExtractedWord>()
                private val wordBuffer = StringBuilder()
                private var startX = 0f
                private var startY = 0f
                private var endX = 0f
                private var maxHeight = 0f
                var pageIdx = 0

                init {
                    sortByPosition = true
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
                        // If character gap is larger than typical space, flush current word
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
                                )
                            )
                        }
                        wordBuffer.clear()
                    }
                }
            }

            for (p in 1..pageCount) {
                stripper.pageIdx = p
                stripper.startPage = p
                stripper.endPage = p
                stripper.getText(document)
                stripper.flush()
                digitalWords.addAll(stripper.words)
                stripper.words.clear()
            }

            // If digital extraction found meaningful text, return it
            if (digitalWords.size >= 15) {
                return@use digitalWords
            }

            // Fallback: scanned PDF -> render to bitmap and run ML Kit OCR
            extractFromScannedPdf(document, minOf(pageCount, MAX_OCR_PAGES))
        }
    }

    private fun extractFromScannedPdf(document: PDDocument, maxPages: Int): List<ExtractedWord> {
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        val renderer = PDFRenderer(document)
        val allWords = mutableListOf<ExtractedWord>()

        try {
            for (p in 0 until maxPages) {
                val pageIndex = p + 1
                var bitmap: Bitmap? = null
                try {
                    bitmap = renderer.renderImageWithDPI(p, OCR_DPI)
                    val inputImage = InputImage.fromBitmap(bitmap, 0)
                    val visionText = Tasks.await(recognizer.process(inputImage))

                    for (block in visionText.textBlocks) {
                        for (line in block.lines) {
                            for (element in line.elements) {
                                val rect = element.boundingBox
                                if (rect != null && element.text.isNotBlank()) {
                                    allWords.add(
                                        ExtractedWord(
                                            text = element.text.trim(),
                                            bbox = BoundingBox(
                                                left = rect.left.toFloat(),
                                                top = rect.top.toFloat(),
                                                right = rect.right.toFloat(),
                                                bottom = rect.bottom.toFloat(),
                                            ),
                                            pageIndex = pageIndex,
                                        )
                                    )
                                }
                            }
                        }
                    }
                } finally {
                    bitmap?.recycle()
                }
            }
        } finally {
            recognizer.close()
        }
        return allWords
    }

    private fun extractFromImage(file: File): List<ExtractedWord> {
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        try {
            val image = InputImage.fromFilePath(context, Uri.fromFile(file))
            val visionText = Tasks.await(recognizer.process(image))
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
                                    pageIndex = 1,
                                )
                            )
                        }
                    }
                }
            }
            return words
        } finally {
            recognizer.close()
        }
    }

    private companion object {
        const val OCR_DPI = 150f
        const val MAX_OCR_PAGES = 50
    }
}
