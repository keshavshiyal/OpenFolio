package com.openfolio.core.utils

import android.content.Context
import com.openfolio.core.data.local.LocalFileCrypto
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class LocalDocumentTextExtractor @Inject constructor(
    @ApplicationContext private val context: Context,
    private val fileCrypto: LocalFileCrypto? = null,
) : DocumentTextExtractor {
    private val streamingPdfProcessor by lazy { StreamingPdfProcessor(context, fileCrypto) }

    override suspend fun extractText(
        file: File,
        fileName: String,
        password: String?,
    ): String =
        withContext(Dispatchers.IO) {
            when (fileName.substringAfterLast('.', "").lowercase()) {
                "pdf" -> extractPdfText(file, password)
                "png", "jpg", "jpeg" -> streamingPdfProcessor.extractImageText(file)
                else -> throw IllegalArgumentException("Unsupported document type: $fileName")
            }
        }

    private suspend fun extractPdfText(file: File, password: String? = null): String {
        val sb = StringBuilder()
        streamingPdfProcessor.processPages(file, password) { page ->
            if (page.text.isNotBlank()) {
                if (sb.isNotEmpty()) sb.append('\n')
                sb.append(page.text.trim())
            }
        }
        return sb.toString()
    }
}
