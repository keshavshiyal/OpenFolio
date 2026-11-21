package com.openfolio.core.utils

import java.io.File

interface DocumentTextExtractor {
    suspend fun extractText(file: File, fileName: String, password: String? = null): String
}
