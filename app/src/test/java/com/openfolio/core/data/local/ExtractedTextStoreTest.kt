package com.openfolio.core.data.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ExtractedTextStoreTest {

    @Rule
    @JvmField
    val tempFolder = TemporaryFolder()

    @Test
    fun writesAndStreamsBack250SyntheticPagesWithMarkers() {
        val store = ExtractedTextStore(tempFolder.newFolder("extracted_text"))
        val docId = "doc-250-pages"

        store.openWriter(docId).use { writer ->
            for (page in 1..250) {
                store.writePage(
                    writer,
                    page,
                    "Page $page header\n01-Jan-2024 SIP Purchase ${page * 100}.00 ${page}.500 100.0000",
                )
            }
            writer.flush()
        }

        assertTrue(store.hasText(docId))

        val rawContent = store.fileFor(docId).readText(Charsets.UTF_8)
        assertTrue(rawContent.contains("<<<OPENFOLIO_PAGE:1>>>"))
        assertTrue(rawContent.contains("<<<OPENFOLIO_PAGE:250>>>"))

        val streamedPages = mutableListOf<Pair<Int, String>>()
        store.forEachPage(docId) { pageNumber, pageText ->
            streamedPages.add(pageNumber to pageText)
        }

        assertEquals(250, streamedPages.size)
        assertEquals(1, streamedPages.first().first)
        assertTrue(streamedPages.first().second.contains("Page 1 header"))
        assertEquals(250, streamedPages.last().first)
        assertTrue(streamedPages.last().second.contains("25000.00"))
    }

    @Test
    fun readSampleCapsCharactersAndStripsMarkers() {
        val store = ExtractedTextStore(tempFolder.newFolder("extracted_text_sample"))
        val docId = "doc-sample"

        store.openWriter(docId).use { writer ->
            for (page in 1..20) {
                store.writePage(writer, page, "Transaction row on page $page: " + "X".repeat(200))
            }
            writer.flush()
        }

        val sample = store.readSample(docId, maxChars = 500)
        assertTrue(sample.length <= 500)
        assertFalse(sample.contains(ExtractedTextStore.PAGE_MARKER_PREFIX))
        assertTrue(sample.contains("Transaction row on page 1"))
    }

    @Test
    fun hasTextReturnsFalseForMissingOrBlankPagesAndDeleteRemovesFile() {
        val store = ExtractedTextStore(tempFolder.newFolder("extracted_text_blank"))
        assertFalse(store.hasText("missing-doc"))

        val blankDocId = "doc-blank"
        store.openWriter(blankDocId).use { writer ->
            store.writePage(writer, 1, "   ")
            store.writePage(writer, 2, "")
            writer.flush()
        }
        assertFalse(store.hasText(blankDocId))

        store.delete(blankDocId)
        assertFalse(store.fileFor(blankDocId).exists())
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsPathTraversalDocumentId() {
        val store = ExtractedTextStore(tempFolder.newFolder("extracted_text_security"))
        store.fileFor("../escape")
    }
}
