package com.openfolio.core.utils

import java.io.File
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class CsvTextExtractor @Inject constructor() : DocumentParser {
    override fun supports(fileName: String): Boolean =
        fileName.substringAfterLast('.', "").equals("csv", ignoreCase = true)

    override suspend fun extractText(file: File): String = withContext(Dispatchers.IO) {
        file.readText(Charsets.UTF_8)
    }
}
