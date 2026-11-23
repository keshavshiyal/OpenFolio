package com.openfolio.core.utils

import android.content.Context
import android.net.Uri
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException
import com.tom_roush.pdfbox.rendering.PDFRenderer
import com.tom_roush.pdfbox.text.PDFTextStripper
import java.io.File
import javax.inject.Inject
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class LocalDocumentTextExtractor @Inject constructor(
    @ApplicationContext private val context: Context,
) : DocumentTextExtractor {
    override suspend fun extractText(
        file: File,
        fileName: String,
        password: String?,
    ): String =
        withContext(Dispatchers.IO) {
            when (fileName.substringAfterLast('.', "").lowercase()) {
                "pdf" -> extractPdfText(file, password)
                "png", "jpg", "jpeg" -> extractImageText(file)
                else -> throw IllegalArgumentException("Unsupported document type: $fileName")
            }
        }

    private fun extractImageText(file: File): String {
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        try {
            val image = InputImage.fromFilePath(context, Uri.fromFile(file))
            return Tasks.await(recognizer.process(image)).text
        } finally {
            recognizer.close()
        }
    }

    private fun extractPdfText(file: File, password: String? = null): String {
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
            val stripper = PDFTextStripper().apply {
                sortByPosition = true // Keeps table columns aligned on one line
            }
            val extracted = stripper.getText(document)
            if (extracted.trim().length >= MIN_SEARCHABLE_TEXT_LENGTH) return@use extracted
            extractFirstPagesWithOcr(document, extracted)
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
        const val OCR_PAGE_LIMIT = 50
        const val OCR_DPI = 150f
    }
}
