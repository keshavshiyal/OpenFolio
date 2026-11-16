package com.openfolio.core.data.repository

import android.content.ContentResolver
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.provider.OpenableColumns
import com.openfolio.core.data.local.dao.DocumentDao
import com.openfolio.core.data.local.dao.ColumnMappingDao
import com.openfolio.core.data.local.dao.DraftTransactionDao
import com.openfolio.core.data.local.dao.TransactionDao
import com.openfolio.core.data.local.entity.ColumnMappingEntity
import com.openfolio.core.data.local.entity.DocumentEntity
import com.openfolio.core.data.local.OpenFolioDatabase
import com.openfolio.core.utils.DocumentTextExtractor
import com.openfolio.core.utils.StructuredFileParser
import com.openfolio.core.data.remote.ai.AiProvider
import com.openfolio.core.data.repository.SettingsRepository
import com.openfolio.core.utils.StructuredTable
import com.openfolio.core.data.local.entity.DocumentStatus
import com.openfolio.core.data.local.entity.DraftTransactionEntity
import com.openfolio.core.domain.repository.AiExtractionException
import com.openfolio.core.domain.repository.DuplicateDocumentException
import com.openfolio.core.domain.repository.EmptyExtractedTextException
import com.openfolio.core.domain.repository.MissingApiKeyException
import java.io.ByteArrayInputStream
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CoroutineScope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.eq
import org.mockito.kotlin.isNull
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

class DocumentRepositoryImplTest {
    @Test
    fun calculateSha256ReturnsExpectedDigest() = runBlocking {
        val hash = calculateSha256(ByteArrayInputStream("hello".toByteArray()))

        assertEquals(
            "2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824",
            hash,
        )
    }

    @Test
    fun transactionFingerprintIsStableAndIncludesTransactionFields() {
        val fingerprint = createTransactionFingerprint(
            assetName = "  Reliance Industries ",
            transactionType = "buy",
            quantity = "10",
            netAmount = "28500.00",
            date = 20_000L,
        )

        assertEquals(
            fingerprint,
            createTransactionFingerprint(
                assetName = "reliance industries",
                transactionType = "BUY",
                quantity = "10",
                netAmount = "28500.00",
                date = 20_000L,
            ),
        )
        assertTrue(
            fingerprint != createTransactionFingerprint(
                assetName = "Reliance Industries",
                transactionType = "BUY",
                quantity = "11",
                netAmount = "28500.00",
                date = 20_000L,
            ),
        )
    }

    @Test
    fun ingestFileThrowsWhenHashAlreadyExists() {
        val dao = mock<DocumentDao>()
        val context = mock<Context>()
        val resolver = mock<ContentResolver>()
        val cursor = mock<Cursor>()
        val uri = mock<Uri>()
        val fileBytes = "statement contents".toByteArray()
        val expectedHash = runBlocking {
            calculateSha256(ByteArrayInputStream(fileBytes))
        }

        whenever(context.contentResolver).thenReturn(resolver)
        whenever(
            resolver.query(
                eq(uri),
                anyOrNull(),
                isNull(),
                isNull(),
                isNull(),
            ),
        ).thenReturn(cursor)
        whenever(cursor.moveToFirst()).thenReturn(true)
        whenever(cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)).thenReturn(0)
        whenever(cursor.getColumnIndex(OpenableColumns.SIZE)).thenReturn(1)
        whenever(cursor.getString(0)).thenReturn("statement.pdf")
        whenever(cursor.getLong(1)).thenReturn(fileBytes.size.toLong())
        whenever(resolver.openInputStream(uri)).thenReturn(ByteArrayInputStream(fileBytes))
        runBlocking {
            whenever(dao.getDocumentByHash(expectedHash)).thenReturn(
                DocumentEntity(
                    fileName = "prior.pdf",
                    localPath = "/private/prior.pdf",
                    fileHash = expectedHash,
                    fileSize = fileBytes.size.toLong(),
                    importDate = 1L,
                ),
            )
        }

        val repository = DocumentRepositoryImpl(
            documentDao = dao,
            columnMappingDao = mock<ColumnMappingDao>(),
            draftTransactionDao = mock<DraftTransactionDao>(),
            transactionDao = mock<TransactionDao>(),
            database = mock<OpenFolioDatabase>(),
            documentTextExtractor = mock<DocumentTextExtractor>(),
            structuredFileParser = mock<StructuredFileParser>(),
            settingsRepository = mock<SettingsRepository>(),
            aiProvider = mock<AiProvider>(),
            applicationScope = mock<CoroutineScope>(),
        )

        assertThrows(DuplicateDocumentException::class.java) {
            runBlocking { repository.ingestFile(uri, context) }
        }
        runBlocking {
            verify(dao, never()).insert(org.mockito.kotlin.any())
        }
    }

    @Test
    fun createMappedDraftsComputesMissingAmountWhenUnmapped() {
        val repository = createTestRepository()
        val document = DocumentEntity(
            id = "doc-1",
            fileName = "statement.csv",
            localPath = "/tmp/statement.csv",
            fileHash = "hash1",
            fileSize = 100L,
            importDate = 1000L,
        )
        val table = StructuredTable(
            headers = listOf("Symbol", "Type", "Qty", "Rate"),
            rows = listOf(listOf("RELIANCE", "BUY", "10", "2500.00")),
        )
        val mapping = ColumnMappingEntity(
            brokerName = "TestBroker",
            assetColumn = "Symbol",
            typeColumn = "Type",
            quantityColumn = "Qty",
            priceColumn = "Rate",
            amountColumn = "",
            dateColumn = "",
        )

        val drafts = repository.createMappedDrafts(document, table, mapping)

        assertEquals(1, drafts.size)
        val draft = drafts.single()
        assertEquals("RELIANCE", draft.assetName)
        assertEquals("BUY", draft.transactionType)
        assertEquals("10", draft.quantity)
        assertEquals("2500.00", draft.price)
        assertEquals("25000", draft.netAmount)
    }

    @Test
    fun createMappedDraftsComputesMissingPriceWhenUnmapped() {
        val repository = createTestRepository()
        val document = DocumentEntity(
            id = "doc-1",
            fileName = "statement.csv",
            localPath = "/tmp/statement.csv",
            fileHash = "hash1",
            fileSize = 100L,
            importDate = 1000L,
        )
        val table = StructuredTable(
            headers = listOf("Symbol", "Type", "Qty", "Total"),
            rows = listOf(listOf("TCS", "BUY", "10", "35000.00")),
        )
        val mapping = ColumnMappingEntity(
            brokerName = "TestBroker",
            assetColumn = "Symbol",
            typeColumn = "Type",
            quantityColumn = "Qty",
            priceColumn = "",
            amountColumn = "Total",
            dateColumn = "",
        )

        val drafts = repository.createMappedDrafts(document, table, mapping)

        assertEquals(1, drafts.size)
        val draft = drafts.single()
        assertEquals("TCS", draft.assetName)
        assertEquals("10", draft.quantity)
        assertEquals("3500", draft.price)
        assertEquals("35000.00", draft.netAmount)
    }

    @Test
    fun createMappedDraftsDefaultsUnmappedTypeToBuy() {
        val repository = createTestRepository()
        val document = DocumentEntity(
            id = "doc-1",
            fileName = "statement.csv",
            localPath = "/tmp/statement.csv",
            fileHash = "hash1",
            fileSize = 100L,
            importDate = 1000L,
        )
        val table = StructuredTable(
            headers = listOf("Symbol", "Qty", "Rate", "Total"),
            rows = listOf(listOf("INFY", "5", "1400.00", "7000.00")),
        )
        val mapping = ColumnMappingEntity(
            brokerName = "TestBroker",
            assetColumn = "Symbol",
            typeColumn = "",
            quantityColumn = "Qty",
            priceColumn = "Rate",
            amountColumn = "Total",
            dateColumn = "",
        )

        val drafts = repository.createMappedDrafts(document, table, mapping)

        assertEquals(1, drafts.size)
        val draft = drafts.single()
        assertEquals("INFY", draft.assetName)
        assertEquals("BUY", draft.transactionType)
    }

    @Test
    fun processDocumentWithAiThrowsWhenApiKeyMissing() {
        runBlocking {
            val dao = mock<DocumentDao>()
            val settings = mock<SettingsRepository>()
            val aiProvider = mock<AiProvider>()
            whenever(settings.getGeminiApiKey()).thenReturn("")
            whenever(dao.getDocumentById("doc-1")).thenReturn(
                DocumentEntity(
                    id = "doc-1",
                    fileName = "statement.pdf",
                    localPath = "/tmp/statement.pdf",
                    fileHash = "hash1",
                    fileSize = 100L,
                    importDate = 1000L,
                    status = DocumentStatus.OCR_COMPLETE.name,
                    extractedText = "Sample statement text",
                ),
            )

            val repository = DocumentRepositoryImpl(
                documentDao = dao,
                columnMappingDao = mock<ColumnMappingDao>(),
                draftTransactionDao = mock<DraftTransactionDao>(),
                transactionDao = mock<TransactionDao>(),
                database = mock<OpenFolioDatabase>(),
                documentTextExtractor = mock<DocumentTextExtractor>(),
                structuredFileParser = mock<StructuredFileParser>(),
                settingsRepository = settings,
                aiProvider = aiProvider,
                applicationScope = mock<CoroutineScope>(),
            )

            assertThrows(MissingApiKeyException::class.java) {
                runBlocking { repository.processDocumentWithAi("doc-1") }
            }
        }
    }

    @Test
    fun processDocumentWithAiThrowsWhenExtractedTextIsBlank() {
        runBlocking {
            val dao = mock<DocumentDao>()
            val settings = mock<SettingsRepository>()
            whenever(settings.getGeminiApiKey()).thenReturn("key-123")
            whenever(dao.getDocumentById("doc-blank")).thenReturn(
                DocumentEntity(
                    id = "doc-blank",
                    fileName = "statement.pdf",
                    localPath = "/tmp/statement.pdf",
                    fileHash = "hash1",
                    fileSize = 100L,
                    importDate = 1000L,
                    status = DocumentStatus.OCR_COMPLETE.name,
                    extractedText = "   ",
                ),
            )

            val repository = DocumentRepositoryImpl(
                documentDao = dao,
                columnMappingDao = mock<ColumnMappingDao>(),
                draftTransactionDao = mock<DraftTransactionDao>(),
                transactionDao = mock<TransactionDao>(),
                database = mock<OpenFolioDatabase>(),
                documentTextExtractor = mock<DocumentTextExtractor>(),
                structuredFileParser = mock<StructuredFileParser>(),
                settingsRepository = settings,
                aiProvider = mock<AiProvider>(),
                applicationScope = mock<CoroutineScope>(),
            )

            assertThrows(EmptyExtractedTextException::class.java) {
                runBlocking { repository.processDocumentWithAi("doc-blank") }
            }
        }
    }

    @Test
    fun processDocumentWithAiThrowsAiExtractionExceptionWhenProviderFails() {
        runBlocking {
            val dao = mock<DocumentDao>()
            val settings = mock<SettingsRepository>()
            val aiProvider = mock<AiProvider>()
            whenever(settings.getGeminiApiKey()).thenReturn("key-123")
            whenever(dao.getDocumentById("doc-fail")).thenReturn(
                DocumentEntity(
                    id = "doc-fail",
                    fileName = "statement.pdf",
                    localPath = "/tmp/statement.pdf",
                    fileHash = "hash1",
                    fileSize = 100L,
                    importDate = 1000L,
                    status = DocumentStatus.OCR_COMPLETE.name,
                    extractedText = "Some statement content",
                ),
            )
            whenever(aiProvider.extractTransactions("Some statement content", "key-123", "doc-fail"))
                .thenReturn(Result.failure(RuntimeException("Network error")))

            val repository = DocumentRepositoryImpl(
                documentDao = dao,
                columnMappingDao = mock<ColumnMappingDao>(),
                draftTransactionDao = mock<DraftTransactionDao>(),
                transactionDao = mock<TransactionDao>(),
                database = mock<OpenFolioDatabase>(),
                documentTextExtractor = mock<DocumentTextExtractor>(),
                structuredFileParser = mock<StructuredFileParser>(),
                settingsRepository = settings,
                aiProvider = aiProvider,
                applicationScope = mock<CoroutineScope>(),
            )

            val exception = assertThrows(AiExtractionException::class.java) {
                runBlocking { repository.processDocumentWithAi("doc-fail") }
            }
            assertTrue(exception.message!!.contains("Network error"))
        }
    }

    @OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
    @Test
    fun processDocumentWithAiThrowsClearMessageOnMissingFieldException() {
        runBlocking {
            val dao = mock<DocumentDao>()
            val settings = mock<SettingsRepository>()
            val aiProvider = mock<AiProvider>()
            whenever(settings.getGeminiApiKey()).thenReturn("key-123")
            whenever(dao.getDocumentById("doc-missing-field")).thenReturn(
                DocumentEntity(
                    id = "doc-missing-field",
                    fileName = "statement.pdf",
                    localPath = "/tmp/statement.pdf",
                    fileHash = "hash1",
                    fileSize = 100L,
                    importDate = 1000L,
                    status = DocumentStatus.OCR_COMPLETE.name,
                    extractedText = "Some statement content",
                ),
            )
            whenever(aiProvider.extractTransactions("Some statement content", "key-123", "doc-missing-field"))
                .thenReturn(Result.failure(kotlinx.serialization.MissingFieldException(listOf("candidates"), "GenerateContentResponse")))

            val repository = DocumentRepositoryImpl(
                documentDao = dao,
                columnMappingDao = mock<ColumnMappingDao>(),
                draftTransactionDao = mock<DraftTransactionDao>(),
                transactionDao = mock<TransactionDao>(),
                database = mock<OpenFolioDatabase>(),
                documentTextExtractor = mock<DocumentTextExtractor>(),
                structuredFileParser = mock<StructuredFileParser>(),
                settingsRepository = settings,
                aiProvider = aiProvider,
                applicationScope = mock<CoroutineScope>(),
            )

            val exception = assertThrows(AiExtractionException::class.java) {
                runBlocking { repository.processDocumentWithAi("doc-missing-field") }
            }
            assertEquals("AI model is unavailable. Please update the app or check your API key.", exception.message)
        }
    }

    @Test
    fun processDocumentWithAiChunksAndPersistsDrafts() {
        runBlocking {
            val dao = mock<DocumentDao>()
            val draftsDao = mock<DraftTransactionDao>()
            val settings = mock<SettingsRepository>()
            val aiProvider = mock<AiProvider>()
            whenever(settings.getGeminiApiKey()).thenReturn("test-api-key")
            whenever(dao.getDocumentById("doc-1")).thenReturn(
                DocumentEntity(
                    id = "doc-1",
                    fileName = "statement.pdf",
                    localPath = "/tmp/statement.pdf",
                    fileHash = "hash1",
                    fileSize = 100L,
                    importDate = 1000L,
                    status = DocumentStatus.OCR_COMPLETE.name,
                    extractedText = "Transaction statement text",
                ),
            )
            val mockDraft = DraftTransactionEntity(
                id = "draft-1",
                documentId = "doc-1",
                assetName = "TCS",
                transactionType = "BUY",
                quantity = "10",
                price = "3500",
                netAmount = "35000",
                confidence = 90,
            )
            whenever(aiProvider.extractTransactions("Transaction statement text", "test-api-key", "doc-1"))
                .thenReturn(Result.success(listOf(mockDraft)))

            val repository = DocumentRepositoryImpl(
                documentDao = dao,
                columnMappingDao = mock<ColumnMappingDao>(),
                draftTransactionDao = draftsDao,
                transactionDao = mock<TransactionDao>(),
                database = mock<OpenFolioDatabase>(),
                documentTextExtractor = mock<DocumentTextExtractor>(),
                structuredFileParser = mock<StructuredFileParser>(),
                settingsRepository = settings,
                aiProvider = aiProvider,
                applicationScope = mock<CoroutineScope>(),
            )

            repository.processDocumentWithAi("doc-1")

            verify(draftsDao).insertAll(listOf(mockDraft))
            verify(dao).updateStatus("doc-1", DocumentStatus.AI_COMPLETE.name)
        }
    }

    private fun createTestRepository() = DocumentRepositoryImpl(
        documentDao = mock<DocumentDao>(),
        columnMappingDao = mock<ColumnMappingDao>(),
        draftTransactionDao = mock<DraftTransactionDao>(),
        transactionDao = mock<TransactionDao>(),
        database = mock<OpenFolioDatabase>(),
        documentTextExtractor = mock<DocumentTextExtractor>(),
        structuredFileParser = mock<StructuredFileParser>(),
        settingsRepository = mock<SettingsRepository>(),
        aiProvider = mock<AiProvider>(),
        applicationScope = mock<CoroutineScope>(),
    )
}
