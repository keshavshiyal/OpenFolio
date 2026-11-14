package com.openfolio.core.utils

import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class CsvTextExtractorTest {
    @Test
    fun extractsUtf8CsvText() = runBlocking {
        val file = File.createTempFile("openfolio-", ".csv")
        val expected = "Date,Description,Amount\n2026-11-14,BUY RELIANCE,28500"
        try {
            file.writeText(expected, Charsets.UTF_8)

            assertEquals(expected, CsvTextExtractor().extractText(file))
        } finally {
            check(file.delete() || !file.exists()) {
                "Could not delete the temporary CSV test file."
            }
        }
    }

    @Test
    fun supportsCsvExtensionCaseInsensitively() {
        assertEquals(true, CsvTextExtractor().supports("statement.CSV"))
        assertEquals(false, CsvTextExtractor().supports("statement.xlsx"))
    }
}
