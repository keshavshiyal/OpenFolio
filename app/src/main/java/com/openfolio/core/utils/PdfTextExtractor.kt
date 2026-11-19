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
            val totalPages = document.numberOfPages
            if (totalPages <= 1) {
                return@use PDFTextStripper().getText(document)
            }

            val textBuilder = StringBuilder()
            val stripper = PDFTextStripper()

            for (pageNum in 1..totalPages) {
                stripper.startPage = pageNum
                stripper.endPage = pageNum
                val pageText = stripper.getText(document)

                if (isRelevantPage(pageText)) {
                    textBuilder.append("--- PAGE ").append(pageNum).append(" ---\n")
                    textBuilder.append(pageText.trim())
                    textBuilder.append("\n\n")
                }
            }

            val result = textBuilder.toString().trim()
            if (result.isNotBlank()) result else PDFTextStripper().getText(document)
        }
    }

    private companion object {
        val DATE_PATTERN = Regex("""\b\d{1,2}[-/]\w{3}[-/]\d{2,4}\b|\b\d{1,2}[-/]\d{1,2}[-/]\d{2,4}\b|\b\d{4}-\d{2}-\d{2}\b""")
    }

    internal fun isRelevantPage(pageText: String): Boolean {
        val trimmed = pageText.trim()
        if (trimmed.length < 20) return false

        val lower = trimmed.lowercase()
        val hasTransactionSignals = DATE_PATTERN.containsMatchIn(trimmed) ||
            lower.contains("folio no") || lower.contains("folio number") ||
            lower.contains("isin: in") || lower.contains("isin : in") ||
            (lower.contains("units") && (lower.contains("purchase") || lower.contains("sale") || lower.contains("redeem")))

        val isDisclaimerHeader = lower.contains("disclaimer") ||
            lower.contains("glossary") ||
            lower.contains("investor charter") ||
            lower.contains("standard terms and conditions") ||
            lower.contains("guidelines for investors")

        if (isDisclaimerHeader && !hasTransactionSignals) {
            return false
        }

        return true
    }
}
