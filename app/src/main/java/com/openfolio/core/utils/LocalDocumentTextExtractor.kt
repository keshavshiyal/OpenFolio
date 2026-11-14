package com.openfolio.core.utils

import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.rendering.PDFRenderer
import com.tom_roush.pdfbox.text.PDFTextStripper
import java.io.File
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class LocalDocumentTextExtractor @Inject constructor() : DocumentTextExtractor {
    override suspend fun extractText(file: File, fileName: String): String =
        withContext(Dispatchers.IO) {
            when (fileName.substringAfterLast('.', "").lowercase()) {
                "pdf" -> extractPdfText(file)
                "csv" -> file.readText(Charsets.UTF_8)
                else -> throw IllegalArgumentException("Unsupported document type: $fileName")
            }
        }

    private fun extractPdfText(file: File): String {
        PDDocument.load(file).use { document ->
            val extracted = PDFTextStripper().getText(document)
            if (extracted.trim().length >= MIN_SEARCHABLE_TEXT_LENGTH) return extracted
            return extractFirstPagesWithOcr(document, extracted)
        }
    }

    private fun extractFirstPagesWithOcr(
        document: PDDocument,
        existingText: String,
    ): String {
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        try {
            val renderer = PDFRenderer(document)
            val pageCount = minOf(document.numberOfPages, OCR_PAGE_LIMIT)
            val ocrText = buildList {
                repeat(pageCount) { page ->
                    val bitmap = renderer.renderImageWithDPI(page, OCR_DPI)
                    try {
                        val result = Tasks.await(
                            recognizer.process(InputImage.fromBitmap(bitmap, 0)),
                        )
                        add(result.text)
                    } finally {
                        bitmap.recycle()
                    }
                }
            }.filter(String::isNotBlank).joinToString(separator = "\n")

            return listOf(existingText, ocrText)
                .filter(String::isNotBlank)
                .joinToString(separator = "\n")
        } finally {
            recognizer.close()
        }
    }

    private companion object {
        const val MIN_SEARCHABLE_TEXT_LENGTH = 20
        const val OCR_PAGE_LIMIT = 3
        const val OCR_DPI = 150f
    }
}
