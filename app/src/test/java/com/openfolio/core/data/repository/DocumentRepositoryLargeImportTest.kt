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
import com.openfolio.core.data.local.entity.AssetStatus
import com.openfolio.core.data.local.entity.ColumnMappingEntity
import com.openfolio.core.data.local.entity.DocumentEntity
import com.openfolio.core.data.local.entity.DocumentStatus
import com.openfolio.core.data.local.entity.DraftTransactionEntity
import com.openfolio.core.data.local.entity.LotEntity
import com.openfolio.core.data.local.entity.TransactionEntity
import com.openfolio.core.data.local.entity.TransactionStatus
import com.openfolio.core.data.local.entity.TransactionType
import com.openfolio.core.data.remote.ai.AiCarryOverContext
import com.openfolio.core.data.remote.ai.AiExtractionBatchResult
import com.openfolio.core.data.remote.ai.AiProvider
import com.openfolio.core.data.remote.ai.ContextualAiProvider
import com.openfolio.core.data.remote.ai.TruncationAwareAiProvider
import com.openfolio.core.domain.repository.AiBatchStopReason
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
import org.junit.Assert.assertNull
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
        whenever(txnDao.getByFingerprints(any())).thenAnswer { inv ->
            val fps = inv.getArgument<List<String>>(0).toSet()
            assertTrue("Fingerprint batch must be <= 500", fps.size <= 500)
            existingTxns.filter { it.fingerprint in fps }
        }
        whenever(txnDao.getByAssetIds(any())).thenAnswer { inv ->
            val assetIds = inv.getArgument<List<String>>(0).toSet()
            assertTrue("Asset batch must be <= 500", assetIds.size <= 500)
            existingTxns.filter { it.assetId in assetIds }
        }

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
        verify(txnDao, never()).getAllOnce()
        verify(txnDao).getByFingerprints(any())
        verify(txnDao).getByAssetIds(any())
        Unit
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

    @Test
    fun selectSmartBatchEndPageStrictlyCapsAt2AndSnapsTo1OnSectionBreaksOrDensePages() {
        val repository = createRepository()

        val plainPages = (1..12).associateWith { page -> "Transaction row on page $page" }
        assertEquals(2, repository.selectSmartBatchEndPage(1, 12, plainPages))

        val closingOnPage1 = plainPages.toMutableMap().apply {
            this[1] = "12-Mar-2024 SIP 5000\nClosing Unit Balance: 150.250"
        }
        assertEquals(1, repository.selectSmartBatchEndPage(1, 12, closingOnPage1))

        val newFolioOnPage2Top = plainPages.toMutableMap().apply {
            this[2] = "Folio No: 987654/32\nSBI Bluechip Fund ISIN: INF200K01QX4"
        }
        assertEquals(1, repository.selectSmartBatchEndPage(1, 12, newFolioOnPage2Top))

        val closingOnPage2 = plainPages.toMutableMap().apply {
            this[2] = "15-Mar-2024 SIP 5000\nClosing Unit Balance: 220.000"
        }
        assertEquals(2, repository.selectSmartBatchEndPage(1, 12, closingOnPage2))

        val densePage1 = plainPages.toMutableMap().apply {
            this[1] = "X".repeat(DocumentRepositoryImpl.MAX_SINGLE_PAGE_CHARS_FOR_BATCHING)
        }
        assertEquals(1, repository.selectSmartBatchEndPage(1, 2, densePage1))

        val denseCombined = plainPages.toMutableMap().apply {
            this[1] = "A".repeat(4_600)
            this[2] = "B".repeat(4_600)
        }
        assertEquals(1, repository.selectSmartBatchEndPage(1, 2, denseCombined))

        val denseLines = plainPages.toMutableMap().apply {
            this[1] = (1..45).joinToString("\n") { "Row 1-$it" }
            this[2] = (1..85).joinToString("\n") { "Row 2-$it" }
        }
        assertEquals(1, repository.selectSmartBatchEndPage(1, 2, denseLines))
    }

    @Test
    fun statefulTwoPageBatchesPauseAfterBatch1PropagateCarryOverContextAndDeduplicateBoundary() = runBlocking {
        val docId = "doc-stateful-6"
        val documentDao = mock<DocumentDao>()
        val draftDao = mock<DraftTransactionDao>()
        val settings = mock<SettingsRepository>()
        val store = ExtractedTextStore(tempFolder.newFolder("extracted_text_stateful"))

        store.openWriter(docId).use { writer ->
            for (p in 1..6) {
                val text = when (p) {
                    1 -> "HDFC Mutual Fund\nFolio No: 123456/78\nHDFC Top 100 Fund ISIN: INF179K01BE2\n01-Jan-2024 SIP 5000 50 100"
                    2 -> "01-Feb-2024 SIP 5000 50 100\n05-Feb-2024 SIP 2500"
                    3 -> "25 100\n01-Mar-2024 SIP 5000 50 100"
                    else -> "01-Apr-2024 SIP 5000 50 100 (Page $p)"
                }
                store.writePage(writer, p, text)
            }
            writer.flush()
        }

        var currentDoc = DocumentEntity(
            id = docId,
            fileName = "cas_6_pages.pdf",
            localPath = "/tmp/cas_6_pages.pdf",
            fileHash = "hash-6",
            fileSize = 6_000L,
            importDate = 1700000000000L,
            status = DocumentStatus.OCR_COMPLETE.name,
            extractedTextPath = store.pathFor(docId),
            pageCount = 6,
            processedPages = 0,
            assetClass = AssetClass.MUTUAL_FUND.name,
        )
        val savedDrafts = mutableListOf<DraftTransactionEntity>()

        whenever(documentDao.getDocumentById(docId)).thenAnswer { currentDoc }
        whenever(documentDao.updateExtractionProgress(eq(docId), any(), any(), any())).thenAnswer { inv ->
            currentDoc = currentDoc.copy(
                processedPages = inv.getArgument(1),
                pageCount = inv.getArgument(2),
                status = inv.getArgument(3),
            )
            Unit
        }
        whenever(documentDao.updateCarryOverContext(eq(docId), anyOrNull())).thenAnswer { inv ->
            currentDoc = currentDoc.copy(carryOverContextJson = inv.getArgument(1))
            Unit
        }
        whenever(documentDao.updateAiBatchProgress(eq(docId), any(), any(), any(), anyOrNull(), anyOrNull())).thenAnswer { inv ->
            currentDoc = currentDoc.copy(
                processedPages = inv.getArgument(1),
                pageCount = inv.getArgument(2),
                status = inv.getArgument(3),
                carryOverContextJson = inv.getArgument(4),
                extractionError = inv.getArgument(5),
            )
            Unit
        }
        whenever(documentDao.updateStatus(eq(docId), any())).thenAnswer { inv ->
            currentDoc = currentDoc.copy(status = inv.getArgument(1))
            Unit
        }
        whenever(draftDao.getByDocumentIdOnce(docId)).thenAnswer { savedDrafts.toList() }
        whenever(draftDao.insertAll(any())).thenAnswer { inv ->
            savedDrafts.addAll(inv.getArgument<List<DraftTransactionEntity>>(0))
            Unit
        }
        whenever(settings.getGeminiApiKey()).thenReturn("api-key")
        whenever(settings.getGeminiModelName()).thenReturn("gemini-3.8-flash")

        val seenContexts = mutableListOf<AiCarryOverContext?>()
        val fakeContextualAi = object : ContextualAiProvider {
            override suspend fun extractTransactions(rawText: String, apiKey: String, documentId: String) =
                extractTransactionsWithContext(rawText, apiKey, documentId).map { it.drafts }

            override suspend fun extractTransactionsDetailed(rawText: String, apiKey: String, documentId: String) =
                extractTransactionsWithContext(rawText, apiKey, documentId)

            override suspend fun extractTransactionsWithContext(
                rawText: String,
                apiKey: String,
                documentId: String,
                priorContext: AiCarryOverContext?,
                modelName: String?,
            ): Result<AiExtractionBatchResult> {
                seenContexts.add(priorContext)
                return when {
                    rawText.contains("--- PAGE 1 ---") -> Result.success(
                        AiExtractionBatchResult(
                            drafts = listOf(
                                DraftTransactionEntity(
                                    id = "b1-1",
                                    documentId = documentId,
                                    assetName = "HDFC Top 100 Fund",
                                    transactionType = "BUY",
                                    quantity = "50",
                                    price = "100",
                                    netAmount = "5000",
                                    confidence = 96,
                                    tradeDate = 1704067200000L,
                                    assetClass = "MUTUAL_FUND",
                                    broker = "CAMS",
                                    isin = "INF179K01BE2",
                                    sourcePage = 2,
                                ),
                            ),
                            carryOverContext = AiCarryOverContext(
                                activeAmc = "HDFC Mutual Fund",
                                activeScheme = "HDFC Top 100 Fund",
                                activeIsin = "INF179K01BE2",
                                activeFolio = "123456/78",
                                activeBroker = "CAMS",
                                activeColumnHeaders = listOf("Date", "Transaction", "Amount", "Units", "NAV"),
                                incompleteTailText = "05-Feb-2024 SIP 2500",
                            ),
                        ),
                    )
                    rawText.contains("--- PAGE 3 ---") -> Result.success(
                        AiExtractionBatchResult(
                            drafts = listOf(
                                // Boundary duplicate of b1-1 from page 2
                                DraftTransactionEntity(
                                    id = "b2-dup",
                                    documentId = documentId,
                                    assetName = "HDFC Top 100 Fund",
                                    transactionType = "BUY",
                                    quantity = "50.00",
                                    price = "100.00",
                                    netAmount = "5000.00",
                                    confidence = 96,
                                    tradeDate = 1704067200000L,
                                    assetClass = "MUTUAL_FUND",
                                    broker = "CAMS",
                                    isin = "INF179K01BE2",
                                    sourcePage = 3,
                                ),
                                // New completed split row from tail text
                                DraftTransactionEntity(
                                    id = "b2-new",
                                    documentId = documentId,
                                    assetName = "HDFC Top 100 Fund",
                                    transactionType = "BUY",
                                    quantity = "25",
                                    price = "100",
                                    netAmount = "2500",
                                    confidence = 95,
                                    tradeDate = 1707091200000L,
                                    assetClass = "MUTUAL_FUND",
                                    broker = "CAMS",
                                    isin = "INF179K01BE2",
                                    sourcePage = 3,
                                ),
                            ),
                            carryOverContext = AiCarryOverContext(
                                activeAmc = "HDFC Mutual Fund",
                                activeScheme = "HDFC Top 100 Fund",
                                activeIsin = "INF179K01BE2",
                                activeFolio = "123456/78",
                                activeBroker = "CAMS",
                            ),
                        ),
                    )
                    else -> Result.success(
                        AiExtractionBatchResult(
                            drafts = listOf(
                                DraftTransactionEntity(
                                    id = "b3-1",
                                    documentId = documentId,
                                    assetName = "HDFC Top 100 Fund",
                                    transactionType = "BUY",
                                    quantity = "50",
                                    price = "100",
                                    netAmount = "5000",
                                    confidence = 96,
                                    tradeDate = 1711929600000L,
                                    assetClass = "MUTUAL_FUND",
                                    broker = "CAMS",
                                    isin = "INF179K01BE2",
                                    sourcePage = 5,
                                ),
                            ),
                        ),
                    )
                }
            }
        }

        val repository = createRepository(
            documentDao = documentDao,
            draftTransactionDao = draftDao,
            settingsRepository = settings,
            aiProvider = fakeContextualAi,
            extractedTextStore = store,
        )

        // Batch 1 (Pages 1-2) pauses cleanly
        val batch1 = repository.processNextAiBatch(docId)
        assertEquals(AiBatchStopReason.PAUSED_AFTER_BATCH, batch1.stopReason)
        assertEquals(1, batch1.startPage)
        assertEquals(2, batch1.endPage)
        assertEquals(DocumentStatus.AI_PAUSED.name, currentDoc.status)
        assertEquals(2, currentDoc.processedPages)
        assertTrue(currentDoc.carryOverContextJson?.contains("INF179K01BE2") == true)
        assertEquals(1, savedDrafts.size)

        // Batch 2 (Pages 3-4) inherits carryOverContext and deduplicates boundary duplicate b2-dup
        val batch2 = repository.processNextAiBatch(docId)
        assertEquals(AiBatchStopReason.PAUSED_AFTER_BATCH, batch2.stopReason)
        assertEquals(3, batch2.startPage)
        assertEquals(4, batch2.endPage)
        assertEquals(1, batch2.newDraftsCount) // b2-dup skipped, b2-new inserted
        assertEquals("05-Feb-2024 SIP 2500", seenContexts[1]?.incompleteTailText)
        assertEquals("", seenContexts[1]?.activeFolio)
        assertEquals(2, savedDrafts.size)

        // Auto-process remaining finishes pages 5-6
        val batch3 = repository.autoProcessRemainingAiBatches(docId)
        assertEquals(AiBatchStopReason.COMPLETED_ALL_PAGES, batch3.stopReason)
        assertEquals(5, batch3.startPage)
        assertEquals(6, batch3.endPage)
        assertEquals(DocumentStatus.AI_COMPLETE.name, currentDoc.status)
        assertEquals(3, savedDrafts.size)
    }

    @Test
    fun autoProcessRemainingAutoPausesOnMathMismatchOrLowConfidence() = runBlocking {
        val docId = "doc-autopause"
        val documentDao = mock<DocumentDao>()
        val draftDao = mock<DraftTransactionDao>()
        val settings = mock<SettingsRepository>()
        val store = ExtractedTextStore(tempFolder.newFolder("extracted_text_autopause"))

        store.openWriter(docId).use { writer ->
            for (p in 1..6) {
                store.writePage(writer, p, "01-Jan-2024 BUY RELIANCE 10 2500 25000 (Page $p)")
            }
            writer.flush()
        }

        var currentDoc = DocumentEntity(
            id = docId,
            fileName = "trades_6.pdf",
            localPath = "/tmp/trades_6.pdf",
            fileHash = "hash-6",
            fileSize = 6_000L,
            importDate = 1700000000000L,
            status = DocumentStatus.AI_PAUSED.name,
            extractedTextPath = store.pathFor(docId),
            pageCount = 6,
            processedPages = 2,
        )
        whenever(documentDao.getDocumentById(docId)).thenAnswer { currentDoc }
        whenever(documentDao.updateAiBatchProgress(eq(docId), any(), any(), any(), anyOrNull(), anyOrNull())).thenAnswer { inv ->
            currentDoc = currentDoc.copy(
                processedPages = inv.getArgument(1),
                pageCount = inv.getArgument(2),
                status = inv.getArgument(3),
                carryOverContextJson = inv.getArgument(4),
                extractionError = inv.getArgument(5),
            )
            Unit
        }
        whenever(settings.getGeminiApiKey()).thenReturn("api-key")
        whenever(draftDao.getByDocumentIdOnce(docId)).thenReturn(emptyList())

        val mismatchAi = object : ContextualAiProvider {
            override suspend fun extractTransactions(rawText: String, apiKey: String, documentId: String) =
                extractTransactionsWithContext(rawText, apiKey, documentId).map { it.drafts }

            override suspend fun extractTransactionsDetailed(rawText: String, apiKey: String, documentId: String) =
                extractTransactionsWithContext(rawText, apiKey, documentId)

            override suspend fun extractTransactionsWithContext(
                rawText: String,
                apiKey: String,
                documentId: String,
                priorContext: AiCarryOverContext?,
                modelName: String?,
            ): Result<AiExtractionBatchResult> = Result.success(
                AiExtractionBatchResult(
                    drafts = listOf(
                        DraftTransactionEntity(
                            documentId = documentId,
                            assetName = "RELIANCE",
                            transactionType = "BUY",
                            quantity = "10",
                            price = "2500.00",
                            netAmount = "250000.00", // 10x math mismatch!
                            confidence = 92,
                            sourcePage = 3,
                        ),
                    ),
                ),
            )
        }

        val repository = createRepository(
            documentDao = documentDao,
            draftTransactionDao = draftDao,
            settingsRepository = settings,
            aiProvider = mismatchAi,
            extractedTextStore = store,
        )

        val result = repository.autoProcessRemainingAiBatches(docId)
        assertEquals(AiBatchStopReason.AUTO_PAUSED_NEEDS_REVIEW, result.stopReason)
        assertEquals(4, result.endPage)
        assertEquals(1, result.flaggedDraftCount)
        assertEquals(DocumentStatus.AI_PAUSED.name, currentDoc.status)
        assertTrue(currentDoc.extractionError?.contains("flagged for review") == true)
    }

    @Test
    fun rollbackDocumentImportDeletesDocumentTxnsAndRecalculatesFifoLots() = runBlocking {
        val docToRollback = "doc-rollback"
        val docToKeep = "doc-keep"
        val documentDao = mock<DocumentDao>()
        val draftDao = mock<DraftTransactionDao>()
        val txnDao = mock<TransactionDao>()
        val assetDao = mock<AssetDao>()
        val lotDao = mock<LotDao>()
        val database = mock<OpenFolioDatabase>()

        whenever(database.assetDao()).thenReturn(assetDao)
        whenever(database.lotDao()).thenReturn(lotDao)

        val docEntity = DocumentEntity(
            id = docToRollback,
            fileName = "statement_undo.pdf",
            localPath = "/tmp/statement_undo.pdf",
            fileHash = "hash-undo",
            fileSize = 2000L,
            importDate = 1700000000000L,
            status = DocumentStatus.COMPLETED.name,
            extractedText = "Some extracted preview text",
            pageCount = 10,
            processedPages = 10,
        )
        whenever(documentDao.getDocumentById(docToRollback)).thenReturn(docEntity)

        val keptTxn = TransactionEntity(
            id = "tx-keep",
            accountId = "default_account",
            assetId = "INF179K01BE2",
            type = TransactionType.BUY,
            tradeDate = LocalDate.of(2024, 1, 1),
            quantity = BigDecimal("10"),
            unitPrice = BigDecimal("100.00"),
            grossAmount = BigDecimal("1000.00"),
            netAmount = BigDecimal("1000.00"),
            currency = "INR",
            fingerprint = "fp-keep",
            status = TransactionStatus.CONFIRMED,
            documentId = docToKeep,
        )
        val rolledBackSell = TransactionEntity(
            id = "tx-undo-sell",
            accountId = "default_account",
            assetId = "INF179K01BE2",
            type = TransactionType.SELL,
            tradeDate = LocalDate.of(2024, 2, 1),
            quantity = BigDecimal("4"),
            unitPrice = BigDecimal("120.00"),
            grossAmount = BigDecimal("480.00"),
            netAmount = BigDecimal("480.00"),
            currency = "INR",
            fingerprint = "fp-undo-sell",
            status = TransactionStatus.CONFIRMED,
            documentId = docToRollback,
        )
        val rolledBackOnlyAsset = TransactionEntity(
            id = "tx-undo-only",
            accountId = "default_account",
            assetId = "INE002A01018",
            type = TransactionType.BUY,
            tradeDate = LocalDate.of(2024, 2, 2),
            quantity = BigDecimal("5"),
            unitPrice = BigDecimal("2500.00"),
            grossAmount = BigDecimal("12500.00"),
            netAmount = BigDecimal("12500.00"),
            currency = "INR",
            fingerprint = "fp-undo-only",
            status = TransactionStatus.CONFIRMED,
            documentId = docToRollback,
        )

        val storedTxns = mutableListOf(keptTxn, rolledBackSell, rolledBackOnlyAsset)
        val storedAssets = mutableMapOf(
            "INF179K01BE2" to AssetEntity(
                id = "INF179K01BE2",
                displayName = "HDFC Top 100 Fund",
                assetClass = AssetClass.MUTUAL_FUND,
                totalQuantity = BigDecimal("6"),
                totalInvested = BigDecimal("600.00"),
                averageCost = BigDecimal("100.00"),
                realizedPnL = BigDecimal("80.00"),
                status = AssetStatus.ACTIVE,
            ),
            "INE002A01018" to AssetEntity(
                id = "INE002A01018",
                displayName = "Reliance Industries",
                assetClass = AssetClass.STOCK,
                totalQuantity = BigDecimal("5"),
                totalInvested = BigDecimal("12500.00"),
                averageCost = BigDecimal("2500.00"),
                status = AssetStatus.ACTIVE,
            ),
        )
        val storedLots = mutableMapOf<String, List<LotEntity>>()

        whenever(txnDao.getByDocumentIdOnce(docToRollback)).thenAnswer {
            storedTxns.filter { it.documentId == docToRollback }
        }
        whenever(txnDao.deleteByDocumentId(docToRollback)).thenAnswer {
            val before = storedTxns.size
            storedTxns.removeAll { it.documentId == docToRollback }
            before - storedTxns.size
        }
        whenever(txnDao.getByAssetIdOnce(any())).thenAnswer { inv ->
            val assetId = inv.getArgument<String>(0)
            storedTxns.filter { it.assetId == assetId }
        }
        whenever(assetDao.getById(any())).thenAnswer { inv -> storedAssets[inv.getArgument(0)] }
        whenever(lotDao.deleteByAssetId(any())).thenAnswer { inv ->
            storedLots.remove(inv.getArgument<String>(0))
            Unit
        }
        whenever(lotDao.insertAll(any())).thenAnswer { inv ->
            val lots = inv.getArgument<List<LotEntity>>(0)
            if (lots.isNotEmpty()) {
                storedLots[lots.first().assetId] = lots
            }
            Unit
        }

        val repository = createRepository(
            documentDao = documentDao,
            draftTransactionDao = draftDao,
            transactionDao = txnDao,
            database = database,
        ).apply {
            transactionRunner = { block -> block() }
        }

        val rollbackResult = repository.rollbackDocumentImport(docToRollback)
        assertEquals(2, rollbackResult.deletedTransactionCount)
        assertEquals(2, rollbackResult.recalculatedAssetCount)
        assertEquals(1, storedTxns.size)
        assertEquals("tx-keep", storedTxns.single().id)

        // HDFC lot restored to full 10 units @ 100.00 and realizedPnL reset to 0.00
        verify(assetDao).updateAggregates(
            assetId = eq("INF179K01BE2"),
            qty = eq(BigDecimal("10")),
            invested = eq(BigDecimal("1000.00")),
            avgCost = eq(BigDecimal("100.0000")),
            realizedPnL = eq(BigDecimal("0.00")),
            timestamp = any(),
        )
        assertEquals(BigDecimal("10"), storedLots["INF179K01BE2"]?.single()?.remainingQuantity)

        // Reliance aggregates zeroed and status updated to CLOSED
        verify(assetDao).updateAggregates(
            assetId = eq("INE002A01018"),
            qty = eq(BigDecimal.ZERO),
            invested = eq(BigDecimal.ZERO),
            avgCost = eq(BigDecimal.ZERO),
            realizedPnL = eq(BigDecimal.ZERO),
            timestamp = any(),
        )
        verify(assetDao).update(storedAssets["INE002A01018"]!!.copy(status = AssetStatus.CLOSED))
        verify(draftDao).deleteByDocumentId(docToRollback)
        verify(documentDao).updateAiBatchProgress(
            id = docToRollback,
            processedPages = 0,
            pageCount = 10,
            status = DocumentStatus.OCR_COMPLETE.name,
            carryOverContextJson = null,
            error = null,
        )
        Unit
    }

    @Test
    fun processFileLocallyRoutesCasPdfToOcrCompleteWithoutOfflineDrafts(): Unit = runBlocking {
        val docId = "doc-cas-route-ai"
        val documentDao = mock<DocumentDao>()
        val draftDao = mock<DraftTransactionDao>()
        val textExtractor = mock<DocumentTextExtractor>()
        val filesDir = tempFolder.newFolder("route_ai_files")
        val context = mock<Context>()
        whenever(context.filesDir).thenReturn(filesDir)
        val store = ExtractedTextStore(context)

        val pdfFile = tempFolder.newFile("cams_cas_statement.pdf")
        val casLikeContent = """
            Computer Age Management Services (CAMS)
            Consolidated Account Statement
            Folio No: 10293847 / 55
            HDFC Mid-Cap Opportunities Fund - Direct Plan - Growth (ISIN: INF179K01CR2)
            Opening Unit Balance: 100.000
            05-Apr-2024 Purchase 10000.00 50.0000 200.0000 150.0000
            Closing Unit Balance: 150.000
        """.trimIndent()
        whenever(textExtractor.extractText(pdfFile, "cams_cas_statement.pdf", null)).thenReturn(casLikeContent)

        val doc = DocumentEntity(
            id = docId,
            fileName = "cams_cas_statement.pdf",
            localPath = pdfFile.absolutePath,
            fileHash = "hash-cas-route",
            fileSize = pdfFile.length(),
            importDate = 1700000000000L,
            status = DocumentStatus.PENDING_OCR.name,
        )
        val repository = createRepository(
            documentDao = documentDao,
            draftTransactionDao = draftDao,
            documentTextExtractor = textExtractor,
            extractedTextStore = store,
        )

        val result = repository.processFileLocally(doc, pdfFile)
        assertTrue(result.isSuccess)
        verify(draftDao).deleteByDocumentId(docId)
        verify(draftDao, never()).insertAll(any())
        verify(documentDao).updateExtractionFileResult(
            id = eq(docId),
            extractedTextPath = eq(store.pathFor(docId)),
            previewText = anyOrNull(),
            pageCount = eq(1),
            processedPages = eq(0),
            status = eq(DocumentStatus.OCR_COMPLETE.name),
            error = isNull(),
        )
        assertTrue(store.readPagesRange(docId, 1..1)[1].orEmpty().contains("INF179K01CR2"))
    }

    @Test
    fun processNextAiBatchHandlesMultiAssetContinuationFallbackTailAndZeroValueStub(): Unit = runBlocking {
        val docId = "doc-bond-split-stub"
        val filesDir = tempFolder.newFolder("bond_split_files")
        val context = mock<Context>()
        whenever(context.filesDir).thenReturn(filesDir)
        val store = ExtractedTextStore(context)

        store.openWriter(docId).use { writer ->
            for (p in 1..4) {
                val pageText = when (p) {
                    1 -> "NSDL Consolidated Demat & Bond Statement\nClient ID: 10020030\nDate | Security | Qty | Price | Amount"
                    2 -> "30-Mar-2024 Complete Row BUY NHAI Bond 10 @ 1000.00 = 10000.00\n31-Mar-2024 BUY NHAI Tax Free Bond 2027 INE906B07FG1"
                    3 -> "25 units @ 1050.00 Net: 26250.00\n02-Apr-2024 SELL REC Bond 2026 INE020B08AA1 10 @ 1020.00 = 10200.00"
                    else -> "Bond activity page $p"
                }
                store.writePage(writer, p, pageText)
            }
            writer.flush()
        }

        val documentDao = mock<DocumentDao>()
        val draftDao = mock<DraftTransactionDao>()
        val settings = mock<SettingsRepository>()
        var currentDoc = DocumentEntity(
            id = docId,
            fileName = "bond_statement_4p.pdf",
            localPath = "/tmp/bond_statement_4p.pdf",
            fileHash = "hash-bond-4",
            fileSize = 4_000L,
            importDate = 1700000000000L,
            status = DocumentStatus.OCR_COMPLETE.name,
            extractedTextPath = store.pathFor(docId),
            pageCount = 4,
            processedPages = 0,
            assetClass = AssetClass.OTHER.name,
        )
        val savedDrafts = mutableListOf<DraftTransactionEntity>()

        whenever(documentDao.getDocumentById(docId)).thenAnswer { currentDoc }
        whenever(documentDao.updateExtractionProgress(eq(docId), any(), any(), any())).thenAnswer { inv ->
            currentDoc = currentDoc.copy(
                processedPages = inv.getArgument(1),
                pageCount = inv.getArgument(2),
                status = inv.getArgument(3),
            )
            Unit
        }
        whenever(documentDao.updateAiBatchProgress(eq(docId), any(), any(), any(), anyOrNull(), anyOrNull())).thenAnswer { inv ->
            currentDoc = currentDoc.copy(
                processedPages = inv.getArgument(1),
                pageCount = inv.getArgument(2),
                status = inv.getArgument(3),
                carryOverContextJson = inv.getArgument(4),
                extractionError = inv.getArgument(5),
            )
            Unit
        }
        whenever(documentDao.updateStatus(eq(docId), any())).thenAnswer { inv ->
            currentDoc = currentDoc.copy(status = inv.getArgument(1))
            Unit
        }
        whenever(draftDao.getByDocumentIdOnce(docId)).thenAnswer { savedDrafts.toList() }
        whenever(draftDao.insertAll(any())).thenAnswer { inv ->
            savedDrafts.addAll(inv.getArgument<List<DraftTransactionEntity>>(0))
            Unit
        }
        whenever(draftDao.deleteByIds(any())).thenAnswer { inv ->
            val ids = inv.getArgument<List<String>>(0).toSet()
            savedDrafts.removeAll { it.id in ids }
            Unit
        }
        whenever(settings.getGeminiApiKey()).thenReturn("api-key")
        whenever(settings.getGeminiModelName()).thenReturn("gemini-3.8-flash")

        val seenContexts = mutableListOf<AiCarryOverContext?>()
        var batchCall = 0
        val fakeAi = object : ContextualAiProvider {
            override suspend fun extractTransactions(rawText: String, apiKey: String, documentId: String) =
                extractTransactionsWithContext(rawText, apiKey, documentId).map { it.drafts }

            override suspend fun extractTransactionsDetailed(rawText: String, apiKey: String, documentId: String) =
                extractTransactionsWithContext(rawText, apiKey, documentId)

            override suspend fun extractTransactionsWithContext(
                rawText: String,
                apiKey: String,
                documentId: String,
                priorContext: AiCarryOverContext?,
                modelName: String?,
            ): Result<AiExtractionBatchResult> {
                seenContexts.add(priorContext)
                batchCall++
                return if (batchCall == 1) {
                    val march31Epoch = LocalDate.of(2024, 3, 31)
                        .atStartOfDay(ZoneOffset.UTC)
                        .toInstant()
                        .toEpochMilli()
                    // Notice Batch 1 returns empty incompleteTailText -> repository injects deterministic fallbackTailLines!
                    Result.success(
                        AiExtractionBatchResult(
                            drafts = listOf(
                                DraftTransactionEntity(
                                    id = "stub-page2",
                                    documentId = documentId,
                                    assetName = "NHAI Tax Free Bond 2027",
                                    assetClass = "BOND",
                                    transactionType = "BUY",
                                    quantity = "0",
                                    price = "0",
                                    netAmount = "0",
                                    tradeDate = march31Epoch,
                                    isin = "INE906B07FG1",
                                    broker = "NSDL",
                                    sourcePage = 2,
                                    confidence = 60,
                                ),
                            ),
                            carryOverContext = AiCarryOverContext(
                                activeBroker = "NSDL",
                                activeAssetClass = "BOND",
                                activeAccountId = "NSDL-10020030",
                                activeIsin = "INE906B07FG1",
                                activeScheme = "NHAI Tax Free Bond 2027",
                                incompleteTailText = "",
                            ),
                        ),
                    )
                } else {
                    val march31Epoch = LocalDate.of(2024, 3, 31)
                        .atStartOfDay(ZoneOffset.UTC)
                        .toInstant()
                        .toEpochMilli()
                    // Batch 2 completes the page 2->3 split row (omitting assetClass & broker to test carry-over)
                    Result.success(
                        AiExtractionBatchResult(
                            drafts = listOf(
                                DraftTransactionEntity(
                                    id = "complete-page3",
                                    documentId = documentId,
                                    assetName = "Unknown",
                                    assetClass = "",
                                    transactionType = "BUY",
                                    quantity = "25",
                                    price = "1050.00",
                                    netAmount = "26250.00",
                                    tradeDate = march31Epoch,
                                    isin = "",
                                    broker = null,
                                    sourcePage = 3,
                                    confidence = 96,
                                ),
                            ),
                            carryOverContext = AiCarryOverContext(
                                activeBroker = "NSDL",
                                activeAssetClass = "BOND",
                            ),
                        ),
                    )
                }
            }
        }

        val repository = createRepository(
            documentDao = documentDao,
            draftTransactionDao = draftDao,
            settingsRepository = settings,
            aiProvider = fakeAi,
            extractedTextStore = store,
        )

        val batch1 = repository.processNextAiBatch(docId)
        assertEquals(1, batch1.startPage)
        assertEquals(2, batch1.endPage)
        assertEquals(AiBatchStopReason.PAUSED_AFTER_BATCH, batch1.stopReason)
        assertTrue(batch1.carryOverContext.fallbackTailLines.contains("NHAI Tax Free Bond 2027"))
        assertTrue(batch1.carryOverContext.recentBoundaryTransactions.any {
            it.contains("NHAI Tax Free Bond 2027") && it.contains("2024-03-31")
        })

        val batch2 = repository.processNextAiBatch(docId)
        assertEquals(3, batch2.startPage)
        assertEquals(4, batch2.endPage)
        assertEquals(AiBatchStopReason.COMPLETED_ALL_PAGES, batch2.stopReason)

        val secondPrior = seenContexts[1]
        assertEquals("BOND", secondPrior?.activeAssetClass)
        assertEquals("", secondPrior?.activeAccountId)
        assertTrue(secondPrior?.fallbackTailLines?.contains("INE906B07FG1") == true)
        assertTrue(secondPrior?.recentBoundaryTransactions?.isNotEmpty() == true)

        verify(draftDao).deleteByIds(listOf("stub-page2"))
        assertEquals(1, savedDrafts.size)
        val completed = savedDrafts.single()
        assertEquals("BOND", completed.assetClass)
        assertEquals("NSDL", completed.broker)
        assertEquals("INE906B07FG1", completed.isin)
        assertEquals("NHAI Tax Free Bond 2027", completed.assetName)
        assertEquals("25", completed.quantity)
        assertEquals("26250.00", completed.netAmount)
    }

    @Test
    fun retryAfterMaxTokensClearsErrorAndSplitsTruncatedBatchIntoSinglePages(): Unit = runBlocking {
        val docId = "doc-max-tokens-split"
        val filesDir = tempFolder.newFolder("max_tokens_split_files")
        val context = mock<Context>()
        whenever(context.filesDir).thenReturn(filesDir)
        val store = ExtractedTextStore(context)

        store.openWriter(docId).use { writer ->
            store.writePage(writer, 1, "01-Jan-2026 BUY HDFC MF 10 100 1000")
            store.writePage(writer, 2, "02-Jan-2026 BUY SBI MF 20 100 2000")
            writer.flush()
        }

        val documentDao = mock<DocumentDao>()
        val draftDao = mock<DraftTransactionDao>()
        val settings = mock<SettingsRepository>()
        var currentDoc = DocumentEntity(
            id = docId,
            fileName = "cams_cas.pdf",
            localPath = "/tmp/cams_cas.pdf",
            fileHash = "hash-max-tokens",
            fileSize = 2_000L,
            importDate = 1700000000000L,
            status = DocumentStatus.FAILED.name,
            extractedTextPath = store.pathFor(docId),
            pageCount = 2,
            processedPages = 0,
            extractionError = "Content generation stopped. Reason: MAX_TOKENS",
            assetClass = AssetClass.MUTUAL_FUND.name,
        )
        val savedDrafts = mutableListOf<DraftTransactionEntity>()

        whenever(documentDao.getDocumentById(docId)).thenAnswer { currentDoc }
        whenever(documentDao.updateExtractionProgress(eq(docId), any(), any(), any())).thenAnswer { inv ->
            currentDoc = currentDoc.copy(
                processedPages = inv.getArgument(1),
                pageCount = inv.getArgument(2),
                status = inv.getArgument(3),
            )
            Unit
        }
        whenever(documentDao.updateStatusAndError(eq(docId), any(), anyOrNull())).thenAnswer { inv ->
            currentDoc = currentDoc.copy(
                status = inv.getArgument(1),
                extractionError = inv.getArgument(2),
            )
            Unit
        }
        whenever(documentDao.updateCarryOverContext(eq(docId), anyOrNull())).thenAnswer { inv ->
            currentDoc = currentDoc.copy(carryOverContextJson = inv.getArgument(1))
            Unit
        }
        whenever(documentDao.updateStatus(eq(docId), any())).thenAnswer { inv ->
            currentDoc = currentDoc.copy(status = inv.getArgument(1))
            Unit
        }
        whenever(draftDao.getByDocumentIdOnce(docId)).thenAnswer { savedDrafts.toList() }
        whenever(draftDao.insertAll(any())).thenAnswer { inv ->
            savedDrafts.addAll(inv.getArgument<List<DraftTransactionEntity>>(0))
            Unit
        }
        whenever(settings.getGeminiApiKey()).thenReturn("api-key")
        whenever(settings.getGeminiModelName()).thenReturn("gemini-3.8-flash")

        val splitAi = object : ContextualAiProvider {
            override suspend fun extractTransactions(rawText: String, apiKey: String, documentId: String) =
                extractTransactionsWithContext(rawText, apiKey, documentId).map { it.drafts }

            override suspend fun extractTransactionsDetailed(rawText: String, apiKey: String, documentId: String) =
                extractTransactionsWithContext(rawText, apiKey, documentId)

            override suspend fun extractTransactionsWithContext(
                rawText: String,
                apiKey: String,
                documentId: String,
                priorContext: AiCarryOverContext?,
                modelName: String?,
            ): Result<AiExtractionBatchResult> {
                val hasP1 = rawText.contains("--- PAGE 1 ---")
                val hasP2 = rawText.contains("--- PAGE 2 ---")
                return when {
                    hasP1 && hasP2 -> Result.success(
                        AiExtractionBatchResult(drafts = emptyList(), wasTruncated = true),
                    )
                    hasP1 -> Result.success(
                        AiExtractionBatchResult(
                            drafts = listOf(
                                DraftTransactionEntity(
                                    id = "p1-tx",
                                    documentId = documentId,
                                    assetName = "HDFC MF",
                                    transactionType = "BUY",
                                    quantity = "10",
                                    price = "100",
                                    netAmount = "1000",
                                    confidence = 95,
                                    sourcePage = 1,
                                    assetClass = "MUTUAL_FUND",
                                ),
                            ),
                            wasTruncated = false,
                        ),
                    )
                    else -> Result.success(
                        AiExtractionBatchResult(
                            drafts = listOf(
                                DraftTransactionEntity(
                                    id = "p2-tx",
                                    documentId = documentId,
                                    assetName = "SBI MF",
                                    transactionType = "BUY",
                                    quantity = "20",
                                    price = "100",
                                    netAmount = "2000",
                                    confidence = 95,
                                    sourcePage = 2,
                                    assetClass = "MUTUAL_FUND",
                                ),
                            ),
                            wasTruncated = false,
                        ),
                    )
                }
            }
        }

        val repository = createRepository(
            documentDao = documentDao,
            draftTransactionDao = draftDao,
            settingsRepository = settings,
            aiProvider = splitAi,
            extractedTextStore = store,
        )

        val result = repository.processNextAiBatch(docId)
        assertEquals(AiBatchStopReason.COMPLETED_ALL_PAGES, result.stopReason)
        assertEquals(2, result.newDraftsCount)
        assertEquals(2, savedDrafts.size)
        assertEquals(DocumentStatus.AI_COMPLETE.name, currentDoc.status)
        assertNull(currentDoc.extractionError)
    }

    @Test
    fun cancelAiProcessingCancelsWorkAndRevertsProcessingStatus(): Unit = runBlocking {
        val docId = "doc-cancel-ai"
        val documentDao = mock<DocumentDao>()
        val scheduler = mock<DocumentImportScheduler>()

        whenever(documentDao.getDocumentById(docId)).thenReturn(
            DocumentEntity(
                id = docId,
                fileName = "large.pdf",
                localPath = "/tmp/large.pdf",
                fileHash = "h",
                fileSize = 100L,
                importDate = 1000L,
                status = DocumentStatus.PROCESSING.name,
                processedPages = 5,
                pageCount = 15,
            ),
            DocumentEntity(
                id = docId,
                fileName = "large.pdf",
                localPath = "/tmp/large.pdf",
                fileHash = "h",
                fileSize = 100L,
                importDate = 1000L,
                status = DocumentStatus.PROCESSING.name,
                processedPages = 0,
                pageCount = 15,
            ),
        )

        val repository = createRepository(
            documentDao = documentDao,
            importScheduler = scheduler,
        )

        repository.cancelAiProcessing(docId)
        verify(scheduler).cancelImportWork(docId)
        verify(documentDao).updateStatusAndError(docId, DocumentStatus.AI_PAUSED.name, null)

        repository.cancelAiProcessing(docId)
        verify(documentDao).updateStatusAndError(docId, DocumentStatus.OCR_COMPLETE.name, null)
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
