package com.openfolio.core.utils

import java.io.File

interface DocumentParser {
    fun supports(fileName: String): Boolean

    suspend fun extractText(file: File): String
}
