package com.openfolio.core.data.repository

import android.content.ContentResolver
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.provider.OpenableColumns
import com.openfolio.core.data.local.ExtractedTextStore
import com.openfolio.core.data.local.OpenFolioDatabase
import com.openfolio.core.data.local.dao.AccountDao
import com.openfolio.core.data.local.dao.AssetDao
import com.openfolio.core.data.local.dao.ColumnMappingDao
import com.openfolio.core.data.local.dao.DocumentDao
import com.openfolio.core.data.local.dao.DraftTransactionDao
import com.openfolio.core.data.local.dao.LotDao
import com.openfolio.core.data.local.dao.TransactionDao
import com.openfolio.core.data.local.entity.AccountEntity
import com.openfolio.core.data.local.entity.AssetClass
import com.openfolio.core.data.local.entity.AssetEntity
import com.openfolio.core.data.local.entity.ColumnMappingEntity
import com.openfolio.core.data.local.entity.DocumentEntity
import com.openfolio.core.data.local.entity.DocumentStatus
import com.openfolio.core.data.local.entity.DraftTransactionEntity
import com.openfolio.core.data.local.entity.TransactionEntity
import com.openfolio.core.data.local.entity.TransactionStatus
import com.openfolio.core.data.local.entity.TransactionType
import com.openfolio.core.data.remote.ai.AiExtractionBatchResult
import com.openfolio.core.data.remote.ai.AiProvider
import com.openfolio.core.data.remote.ai.TruncationAwareAiProvider
import com.openfolio.core.domain.repository.DuplicateDocumentException
import com.openfolio.core.utils.DocumentTextExtractor
import com.openfolio.core.utils.StructuredFileParser
import com.openfolio.core.utils.StructuredTable
import com.openfolio.core.worker.DocumentImportScheduler
import java.io.ByteArrayInputStream
import java.io.File
import java.math.BigDecimal
import java.security.MessageDigest
import java.time.LocalDate
import java.time.ZoneOffset
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.eq
import org.mockito.kotlin.isNull
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

class DocumentRepositoryLargeImportTest {

    @Rule
    @JvmField
    val tempFolder = TemporaryFolder()

    @Test
    fun saveDraftTransactionsProcesses1600DraftsInBatchesWithDeduplicationAndFifo() = runBlocking {
        val docId = "doc-large-1600"
        val documentDao = mock<DocumentDao>()
        val draftDao = mock<DraftTransactionDao>()
        val txnDao = mock<TransactionDao>()
        val accountDao = mock<AccountDao>()
        val assetDao = mock<AssetDao>()
        val lotDao = mock<LotDao>()
        val database = mock<OpenFolioDatabase>()

        whenever(database.accountDao()).thenReturn(accountDao)
        whenever(database.assetDao()).thenReturn(assetDao)
        whenever(database.lotDao()).thenReturn(lotDao)

        val storedAssets = mutableMapOf<String, AssetEntity>()
        val storedTxns = mutableListOf<TransactionEntity>()

        whenever(documentDao.getDocumentById(docId)).thenReturn(
            DocumentEntity(
                id = docId,
                fileName = "large_cas.pdf",
                localPath = "/tmp/large_cas.pdf",
                fileHash = "hash-large",
                fileSize = 10_000_000L,
                importDate = 1700000000000L,
                status = DocumentStatus.AI_COMPLETE.name,
            ),
        )
        whenever(assetDao.getAllOnce()).thenAnswer { storedAssets.values.toList() }
        whenever(assetDao.insert(any())).thenAnswer { inv ->
            val asset = inv.getArgument<AssetEntity>(0)
            storedAssets[asset.id] = asset
            Unit
        }
        whenever(assetDao.update(any())).thenAnswer { inv ->
            val asset = inv.getArgument<AssetEntity>(0)
            storedAssets[asset.id] = asset
            Unit
        }
        whenever(assetDao.getById(any())).thenAnswer { inv ->
            storedAssets[inv.getArgument<String>(0)]
        }

        val drafts = mutableListOf<DraftTransactionEntity>()
        val baseDate = LocalDate.of(2024, 1, 1)
        for (i in 0 until 1_600) {
            val assetIdx = i % 10
            val isin = "INE001A0100$assetIdx"
            val tradeDate = baseDate.plusDays((i / 10).toLong())
            val isSell = (i / 10) % 4 == 3
            drafts.add(
                DraftTransactionEntity(
                    id = "draft-$i",
                    documentId = docId,
                    assetName = "Company $assetIdx Limited",
                    transactionType = if (isSell) "SELL" else "BUY",
                    quantity = if (isSell) "5" else "10",
                    price = "100.00",
                    netAmount = if (isSell) "500.00" else "1000.00",
                    confidence = 98,
                    tradeDate = tradeDate.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
                    assetClass = "STOCK",
                    broker = "Zerodha",
                    isin = isin,
                    sourcePage = (i / 20) + 1,
                ),
            )
        }

        // Add two legitimate intra-document identical fills on the exact same day
        val sameDayMillis = LocalDate.of(2024, 12, 1).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        val intraFill1 = DraftTransactionEntity(
            id = "draft-fill-1",
            documentId = docId,
            assetName = "Company 0 Limited",
            transactionType = "BUY",
            quantity = "7",
            price = "125.00",
            netAmount = "875.00",
            confidence = 98,
            tradeDate = sameDayMillis,
            assetClass = "STOCK",
            broker = "Zerodha",
            isin = "INE001A01000",
            sourcePage = 81,
        )
        val intraFill2 = intraFill1.copy(id = "draft-fill-2")
        drafts.add(intraFill1)
        drafts.add(intraFill2)

        whenever(draftDao.getByDocumentIdOnce(docId)).thenReturn(drafts)

        // Pre-seed 50 existing DB fingerprints
        val existingFingerprintSet = drafts.take(50).map { draft ->
            val tradeDate = LocalDate.ofEpochDay(Math.floorDiv(draft.tradeDate!!, DocumentRepositoryImpl.MILLIS_PER_DAY))
            createTransactionFingerprint(
                assetId = draft.isin!!,
                isin = draft.isin,
                ticker = null,
                assetName = draft.assetName,
                transactionType = draft.transactionType,
                quantity = draft.quantity,
                unitPrice = draft.price,
                netAmount = draft.netAmount,
                date = tradeDate.toEpochDay(),
                broker = draft.broker,
            )
        }.toSet()

        whenever(txnDao.getExistingFingerprints(any())).thenAnswer { inv ->
            val batch = inv.getArgument<List<String>>(0)
            assertTrue("Fingerprint query batch must be <= 500", batch.size <= 500)
            batch.filter { it in existingFingerprintSet }
        }
        whenever(txnDao.insertAll(any())).thenAnswer { inv ->
            val batch = inv.getArgument<List<TransactionEntity>>(0)
            assertTrue("Insert batch must be <= 200", batch.size <= 200)
            storedTxns.addAll(batch)
            Unit
        }
        whenever(txnDao.getByAssetIdOnce(any())).thenAnswer { inv ->
            val assetId = inv.getArgument<String>(0)
            storedTxns.filter { it.assetId == assetId }
        }

        val repository = createRepository(
            documentDao = documentDao,
            draftTransactionDao = draftDao,
            transactionDao = txnDao,
            database = database,
        ).apply {
            transactionRunner = { block -> block() }
        }

        val result = repository.saveDraftTransactions(docId)

        assertEquals(1_552, result.savedCount)
        assertEquals(50, result.duplicateCount)
        assertEquals(1_552, storedTxns.size)

        // Account inserted once, never per row
        verify(accountDao, times(1)).insert(any<AccountEntity>())
        // Never uses N+1 containsFingerprint queries
        verify(txnDao, never()).containsFingerprint(any())
        // Batched fingerprint lookup was used
        verify(txnDao, times(4)).getExistingFingerprints(any())
        // Intra-document identical fills were both preserved
        assertEquals(2, storedTxns.count { it.id == "draft-fill-1" || it.id == "draft-fill-2" })
        // Provenance sourcePage preserved
        assertEquals(81, storedTxns.first { it.id == "draft-fill-1" }.sourcePage)
        // FIFO recalculated once per touched asset (10 unique assets)
        verify(lotDao, times(10)).deleteByAssetId(any())
        verify(assetDao, times(10)).updateAggregates(any(), any(), any(), any(), any(), any())
    }

    @Test
    fun findDuplicateDraftsMatchesAccuratelyAgainst2500ExistingTransactions() = runBlocking {
        val docId = "doc-new"
        val documentDao = mock<DocumentDao>()
        val draftDao = mock<DraftTransactionDao>()
        val txnDao = mock<TransactionDao>()
        val assetDao = mock<AssetDao>()
        val database = mock<OpenFolioDatabase>()
        whenever(database.assetDao()).thenReturn(assetDao)

        whenever(documentDao.getDocumentById(docId)).thenReturn(
            DocumentEntity(
                id = docId,
                fileName = "new_trades.pdf",
                localPath = "/tmp/new_trades.pdf",
                fileHash = "hash-new",
                fileSize = 1000L,
                importDate = 1700000000000L,
            ),
        )

        val existingAssets = (0 until 50).map { idx ->
            AssetEntity(
                id = "INE001A01${idx.toString().padStart(3, '0')}",
                displayName = "Company $idx Limited",
                assetClass = AssetClass.STOCK,
            )
        }
        whenever(assetDao.getAllOnce()).thenReturn(existingAssets)

        val baseDate = LocalDate.of(2023, 1, 1)
        val existingTxns = (0 until 2_500).map { i ->
            val assetIdx = i % 50
            val isin = "INE001A01${assetIdx.toString().padStart(3, '0')}"
            val date = baseDate.plusDays((i / 50).toLong())
            val fp = createTransactionFingerprint(
                assetId = isin,
                isin = isin,
                assetName = "Company $assetIdx Limited",
                transactionType = "BUY",
                quantity = "10",
                unitPrice = "200.00",
                netAmount = "2000.00",
                date = date.toEpochDay(),
                broker = "Zerodha",
            )
            TransactionEntity(
                id = "ex-$i",
                accountId = "default_account",
                assetId = isin,
                type = TransactionType.BUY,
                tradeDate = date,
                quantity = BigDecimal("10"),
                unitPrice = BigDecimal("200.00"),
                grossAmount = BigDecimal("2000.00"),
                netAmount = BigDecimal("2000.00"),
                currency = "INR",
                fingerprint = fp,
                status = TransactionStatus.CONFIRMED,
                documentId = "doc-old",
                broker = "Zerodha",
            )
        }
        whenever(txnDao.getAllOnce()).thenReturn(existingTxns)

        val drafts = mutableListOf<DraftTransactionEntity>()
        // 20 exact fingerprint duplicates
        for (i in 0 until 20) {
            val isin = "INE001A01${i.toString().padStart(3, '0')}"
            val date = baseDate.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
            drafts.add(
                DraftTransactionEntity(
                    id = "dup-exact-$i",
                    documentId = docId,
                    assetName = "Company $i Limited",
                    transactionType = "BUY",
                    quantity = "10",
                    price = "200.00",
                    netAmount = "2000.00",
                    confidence = 95,
                    tradeDate = date,
                    broker = "Zerodha",
                    isin = isin,
                ),
            )
        }
        // 10 semantic content duplicates (netAmount within ₹0.02 rounding tolerance)
        for (i in 20 until 30) {
            val isin = "INE001A01${i.toString().padStart(3, '0')}"
            val date = baseDate.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
            drafts.add(
                DraftTransactionEntity(
                    id = "dup-sem-$i",
                    documentId = docId,
                    assetName = "Company $i Limited",
                    transactionType = "BUY",
                    quantity = "10",
                    price = "200.00",
                    netAmount = "2000.02",
                    confidence = 95,
                    tradeDate = date,
                    broker = "Zerodha",
                    isin = isin,
                ),
            )
        }
        // 70 unique new trades
        for (i in 0 until 70) {
            val isin = "INE001A01000"
            val date = LocalDate.of(2025, 1, 1).plusDays(i.toLong())
                .atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
            drafts.add(
                DraftTransactionEntity(
                    id = "unique-$i",
                    documentId = docId,
                    assetName = "Company 0 Limited",
                    transactionType = "BUY",
                    quantity = "5",
                    price = "250.00",
                    netAmount = "1250.00",
                    confidence = 95,
                    tradeDate = date,
                    broker = "Zerodha",
                    isin = isin,
                ),
            )
        }
        whenever(draftDao.getByDocumentIdOnce(docId)).thenReturn(drafts)

        val repository = createRepository(
            documentDao = documentDao,
            draftTransactionDao = draftDao,
            transactionDao = txnDao,
            database = database,
        )

        val duplicates = repository.findDuplicateDrafts(docId)
        assertEquals(30, duplicates.size)
        assertTrue((0 until 20).all { duplicates["dup-exact-$it"]?.isDuplicate == true })
        assertTrue((20 until 30).all { duplicates["dup-sem-$it"]?.isDuplicate == true })
        assertTrue((0 until 70).none { duplicates.containsKey("unique-$it") })
    }

    @Test
    fun createMappedDraftsSkipsMalformedFooterRowsWithoutAbortingValidRows() {
        val repository = createRepository()
        val document = DocumentEntity(
            id = "doc-map-footer",
            fileName = "broker_export.csv",
            localPath = "/tmp/broker_export.csv",
            fileHash = "hash-map",
            fileSize = 512L,
            importDate = 1700000000000L,
            status = DocumentStatus.READY_FOR_MAPPING.name,
        )
        val table = StructuredTable(
            headers = listOf("Symbol", "Type", "Qty", "Price", "Amount", "Date"),
            rows = listOf(
                listOf("INFY", "BUY", "10", "1500.00", "15000.00", "2024-03-01"),
                listOf("", "", "", "", "", ""),
                listOf("Total", "", "---", "", "15000.00", ""),
                listOf("Disclaimer: Mutual fund investments...", "INFO", "bad", "bad", "bad", "not-a-date"),
                listOf("Grand Total", "BUY", "10", "1500.00", "15000.00", "2024-03-01"),
                listOf("TCS", "SELL", "5", "3800.00", "19000.00", "2024-03-05"),
            ),
        )
        val mapping = ColumnMappingEntity(
            brokerName = "Zerodha",
            assetColumn = "Symbol",
            typeColumn = "Type",
            quantityColumn = "Qty",
            priceColumn = "Price",
            amountColumn = "Amount",
            dateColumn = "Date",
        )

        val drafts = repository.createMappedDrafts(document, table, mapping)
        assertEquals(2, drafts.size)
        assertEquals("INFY", drafts[0].assetName)
        assertEquals("TCS", drafts[1].assetName)
    }

    @Test
    fun ingestFileReadsContentUriInSinglePassAndCleansTempFileOnDuplicate() = runBlocking {
        val documentDao = mock<DocumentDao>()
        val scheduler = mock<DocumentImportScheduler>()
        val context = mock<Context>()
        val resolver = mock<ContentResolver>()
        val cursor = mock<Cursor>()
        val uri = mock<Uri>()
        val filesDir = tempFolder.newFolder("app_files")
        val payload = ("Large PDF bytes " + "A".repeat(80_000)).toByteArray()
        val expectedHash = MessageDigest.getInstance("SHA-256")
            .digest(payload)
            .joinToString("") { "%02x".format(it) }

        whenever(context.filesDir).thenReturn(filesDir)
        whenever(context.contentResolver).thenReturn(resolver)
        whenever(resolver.query(eq(uri), anyOrNull(), isNull(), isNull(), isNull())).thenReturn(cursor)
        whenever(cursor.moveToFirst()).thenReturn(true)
        whenever(cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)).thenReturn(0)
        whenever(cursor.getColumnIndex(OpenableColumns.SIZE)).thenReturn(1)
        whenever(cursor.getString(0)).thenReturn("large_statement.pdf")
        whenever(cursor.getLong(1)).thenReturn(payload.size.toLong())
        whenever(resolver.openInputStream(uri)).thenAnswer { ByteArrayInputStream(payload) }
        whenever(documentDao.getDocumentByHash(expectedHash)).thenReturn(null)

        val repository = createRepository(
            documentDao = documentDao,
            importScheduler = scheduler,
        )

        val doc = repository.ingestFile(uri, context, AssetClass.MUTUAL_FUND)

        // Opened strictly once
        verify(resolver, times(1)).openInputStream(uri)
        assertEquals(expectedHash, doc.fileHash)
        assertEquals(payload.size.toLong(), doc.fileSize)
        assertTrue(File(doc.localPath).exists())
        assertEquals(payload.size.toLong(), File(doc.localPath).length())
        verify(scheduler).enqueueLocalExtract(doc.id, doc.fileSize)

        // Now verify duplicate detection still opens stream once and leaves no tmp file behind
        val dupUri = mock<Uri>()
        val dupCursor = mock<Cursor>()
        whenever(resolver.query(eq(dupUri), anyOrNull(), isNull(), isNull(), isNull())).thenReturn(dupCursor)
        whenever(dupCursor.moveToFirst()).thenReturn(true)
        whenever(dupCursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)).thenReturn(0)
        whenever(dupCursor.getColumnIndex(OpenableColumns.SIZE)).thenReturn(1)
        whenever(dupCursor.getString(0)).thenReturn("duplicate_statement.pdf")
        whenever(dupCursor.getLong(1)).thenReturn(payload.size.toLong())
        whenever(resolver.openInputStream(dupUri)).thenAnswer { ByteArrayInputStream(payload) }
        whenever(documentDao.getDocumentByHash(expectedHash)).thenReturn(doc)

        assertThrows(DuplicateDocumentException::class.java) {
            runBlocking { repository.ingestFile(dupUri, context, AssetClass.MUTUAL_FUND) }
        }
        verify(resolver, times(1)).openInputStream(dupUri)
        val docsDir = File(filesDir, DocumentRepositoryImpl.DOCUMENTS_DIRECTORY)
        assertFalse(docsDir.listFiles()?.any { it.name.startsWith("tmp_") } == true)
    }

    @Test
    fun processDocumentWithAiResumesFromCheckpointAndPreservesContext() = runBlocking {
        val docId = "doc-resume-ai"
        val documentDao = mock<DocumentDao>()
        val draftDao = mock<DraftTransactionDao>()
        val settings = mock<SettingsRepository>()
        val store = ExtractedTextStore(tempFolder.newFolder("extracted_text_resume"))

        store.openWriter(docId).use { writer ->
            store.writePage(writer, 1, "HDFC Top 100 Fund - Direct Plan ISIN: INF179K01BE2\n01-Jan-2024 SIP 1000 10 100")
            store.writePage(writer, 2, "01-Feb-2024 SIP 1000 10 100")
            store.writePage(writer, 3, "01-Mar-2024 SIP 1000 10 100")
            store.writePage(writer, 4, "01-Apr-2024 SIP 1000 10 100")
            writer.flush()
        }

        val failedDoc = DocumentEntity(
            id = docId,
            fileName = "statement.pdf",
            localPath = "/tmp/statement.pdf",
            fileHash = "hash-resume",
            fileSize = 5000L,
            importDate = 1700000000000L,
            status = DocumentStatus.FAILED.name,
            extractedTextPath = store.pathFor(docId),
            pageCount = 4,
            processedPages = 2,
            extractionError = "Interrupted at page 2",
        )
        whenever(documentDao.getDocumentById(docId)).thenReturn(failedDoc)
        whenever(settings.getGeminiApiKey()).thenReturn("api-key")
        whenever(draftDao.getByDocumentIdOnce(docId)).thenReturn(
            listOf(
                DraftTransactionEntity(
                    id = "prior-1",
                    documentId = docId,
                    assetName = "HDFC Top 100 Fund",
                    transactionType = "BUY",
                    quantity = "10",
                    price = "100",
                    netAmount = "1000",
                    confidence = 95,
                    isin = "INF179K01BE2",
                ),
            ),
        )

        val seenPrompts = mutableListOf<String>()
        val fakeAiProvider = object : TruncationAwareAiProvider {
            override suspend fun extractTransactions(
                rawText: String,
                apiKey: String,
                documentId: String,
            ): Result<List<DraftTransactionEntity>> = extractTransactionsDetailed(rawText, apiKey, documentId).map { it.drafts }

            override suspend fun extractTransactionsDetailed(
                rawText: String,
                apiKey: String,
                documentId: String,
            ): Result<AiExtractionBatchResult> {
                seenPrompts.add(rawText)
                return Result.success(
                    AiExtractionBatchResult(
                        drafts = listOf(
                            DraftTransactionEntity(
                                documentId = documentId,
                                assetName = "HDFC Top 100 Fund",
                                transactionType = "BUY",
                                quantity = "10",
                                price = "100",
                                netAmount = "1000",
                                confidence = 95,
                                tradeDate = 1709251200000L,
                                isin = "INF179K01BE2",
                            ),
                        ),
                        wasTruncated = false,
                    ),
                )
            }
        }

        val repository = createRepository(
            documentDao = documentDao,
            draftTransactionDao = draftDao,
            settingsRepository = settings,
            aiProvider = fakeAiProvider,
            extractedTextStore = store,
        )

        repository.processDocumentWithAi(docId)

        // Should only process pages 3-4, NOT pages 1-2
        assertEquals(1, seenPrompts.size)
        assertFalse(seenPrompts.single().contains("01-Jan-2024"))
        assertFalse(seenPrompts.single().contains("01-Feb-2024"))
        assertTrue(seenPrompts.single().contains("INF179K01BE2"))
        assertTrue(seenPrompts.single().contains("01-Mar-2024"))
        assertTrue(seenPrompts.single().contains("01-Apr-2024"))

        val draftCaptor = argumentCaptor<List<DraftTransactionEntity>>()
        verify(draftDao).insertAll(draftCaptor.capture())
        assertEquals(3, draftCaptor.firstValue.single().sourcePage)
        verify(documentDao).updateExtractionProgress(docId, 4, 4, DocumentStatus.PROCESSING.name)
        verify(documentDao).updateStatus(docId, DocumentStatus.AI_COMPLETE.name)
    }

    private fun createRepository(
        documentDao: DocumentDao = mock(),
        columnMappingDao: ColumnMappingDao = mock(),
        draftTransactionDao: DraftTransactionDao = mock(),
        transactionDao: TransactionDao = mock(),
        database: OpenFolioDatabase = mock(),
        documentTextExtractor: DocumentTextExtractor = mock(),
        structuredFileParser: StructuredFileParser = mock(),
        settingsRepository: SettingsRepository = mock(),
        aiProvider: AiProvider = mock(),
        extractedTextStore: ExtractedTextStore? = null,
        importScheduler: DocumentImportScheduler? = null,
    ) = DocumentRepositoryImpl(
        documentDao = documentDao,
        columnMappingDao = columnMappingDao,
        draftTransactionDao = draftTransactionDao,
        transactionDao = transactionDao,
        database = database,
        documentTextExtractor = documentTextExtractor,
        structuredFileParser = structuredFileParser,
        settingsRepository = settingsRepository,
        aiProvider = aiProvider,
        applicationScope = CoroutineScope(SupervisorJob()),
        extractedTextStore = extractedTextStore,
        importScheduler = importScheduler,
    )
}
