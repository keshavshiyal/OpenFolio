package com.openfolio.core.utils

import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import java.io.File
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class PdfTextExtractor @Inject constructor() : DocumentParser {
    override fun supports(fileName: String): Boolean =
        fileName.substringAfterLast('.', "").equals("pdf", ignoreCase = true)

    override suspend fun extractText(file: File): String = withContext(Dispatchers.IO) {
        PDDocument.load(file).use { document ->
            PDFTextStripper().getText(document)
        }
    }
}
