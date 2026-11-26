package com.openfolio.feature.review

import android.content.Context
import android.net.Uri
import com.openfolio.core.data.local.entity.AssetClass
import com.openfolio.core.data.local.entity.DocumentEntity
import com.openfolio.core.data.local.entity.DocumentStatus
import com.openfolio.core.data.local.entity.DraftTransactionEntity
import com.openfolio.core.data.local.entity.ColumnMappingEntity
import com.openfolio.core.data.remote.ai.AiCarryOverContext
import com.openfolio.core.domain.repository.AiBatchProcessResult
import com.openfolio.core.domain.repository.AiBatchStopReason
import com.openfolio.core.domain.repository.AiExtractionException
import com.openfolio.core.domain.repository.DocumentRepository
import com.openfolio.core.domain.repository.EmptyExtractedTextException
import com.openfolio.core.domain.repository.MissingApiKeyException
import com.openfolio.core.domain.repository.NoFinancialDataException
import com.openfolio.core.domain.repository.DuplicateMatchInfo
import com.openfolio.core.domain.repository.SaveDraftTransactionsResult
import com.openfolio.feature.screens.autoMapHeaders
import com.openfolio.feature.screens.formatEpochMillisToDate
import java.io.File
import com.openfolio.core.tableextraction.ExtractedTable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ReviewViewModelTest {
    private val document = DocumentEntity(
        id = "document-1",
        fileName = "reliance-statement.pdf",
        localPath = "/documents/reliance.pdf",
        fileHash = "hash",
        fileSize = 100L,
        importDate = 1L,
        status = DocumentStatus.OCR_COMPLETE.name,
        extractedText = "Local statement text",
    )
    private lateinit var repository: FakeDocumentRepository
    private lateinit var viewModel: ReviewViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        repository = FakeDocumentRepository(document)
        viewModel = ReviewViewModel(repository)
        viewModel.loadDocument(document.id)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun loadsDocumentAndStartsWithNoDrafts() {
        assertEquals(ReviewUiState.Loaded(document), viewModel.uiState.value)
        assertTrue(viewModel.drafts.value.isEmpty())
    }

    @Test
    fun processWithAiCreatesFiveMockDraftsWithMixedConfidence() {
        viewModel.onProcessWithAi()

        assertEquals(5, viewModel.drafts.value.size)
        assertEquals(
            listOf(98, 98, 98, 85, 65),
            viewModel.drafts.value.map(DraftTransactionEntity::confidence),
        )
        assertTrue(viewModel.drafts.value.all { it.documentId == document.id })
        assertEquals(1, repository.aiProcessCallCount)
    }

    @Test
    fun savesDraftsAndReturnsDeduplicationCounts() {
        viewModel.onProcessWithAi()
        viewModel.saveConfirmed()

        assertEquals(
            ReviewActionState.Saved(SaveDraftTransactionsResult(savedCount = 4, duplicateCount = 1)),
            viewModel.actionState.value,
        )
    }

    @Test
    fun identifiesDuplicatesAndUnselectsThemByDefault() {
        viewModel.onProcessWithAi()
        val firstDraftId = viewModel.drafts.value.first().id
        repository.duplicateMapToReturn = mapOf(
            firstDraftId to DuplicateMatchInfo(
                isDuplicate = true,
                reason = "Existing identical transaction found",
            ),
        )
        viewModel.loadDocument(document.id)

        val items = viewModel.draftReviewItems.value
        assertEquals(5, items.size)
        val dupItem = items.first { it.draft.id == firstDraftId }
        assertTrue(dupItem.isDuplicate)
        assertTrue(!dupItem.isSelected)
        assertEquals("Existing identical transaction found", dupItem.duplicateReason)

        val nonDup = items.filter { it.draft.id != firstDraftId }
        assertTrue(nonDup.all { !it.isDuplicate && it.isSelected })
    }

    @Test
    fun promptsReviewWhenSavingWithSelectedDuplicates() {
        viewModel.onProcessWithAi()
        val firstDraftId = viewModel.drafts.value.first().id
        repository.duplicateMapToReturn = mapOf(
            firstDraftId to DuplicateMatchInfo(isDuplicate = true),
        )
        viewModel.loadDocument(document.id)

        // Explicitly select the duplicate item
        viewModel.toggleDraftSelection(firstDraftId)

        viewModel.saveConfirmed()

        assertTrue(viewModel.actionState.value is ReviewActionState.PromptDuplicateReview)
        val prompt = viewModel.actionState.value as ReviewActionState.PromptDuplicateReview
        assertEquals(1, prompt.duplicateCount)
        assertEquals(5, prompt.totalSelectedCount)
    }

    @Test
    fun skipAllDuplicatesUnselectsDuplicatesAndSavesUniqueOnly() {
        viewModel.onProcessWithAi()
        val firstDraftId = viewModel.drafts.value.first().id
        repository.duplicateMapToReturn = mapOf(
            firstDraftId to DuplicateMatchInfo(isDuplicate = true),
        )
        viewModel.loadDocument(document.id)

        // Select it then skip all duplicates
        viewModel.toggleDraftSelection(firstDraftId)
        viewModel.skipAllDuplicates()

        viewModel.saveConfirmed()

        // Should save without prompt
        assertTrue(viewModel.actionState.value is ReviewActionState.Saved)
        val savedIds = repository.lastSavedSelectedIds
        assertNotNull(savedIds)
        assertTrue(firstDraftId !in savedIds!!)
        assertEquals(4, savedIds.size)
    }

    @Test
    fun addsAndEditsManualDrafts() {
        viewModel.saveDraft(
            draftId = null,
            assetName = "Manual Holding",
            transactionType = "BUY",
            quantity = "2",
            price = "100.00",
            netAmount = "200.00",
        )
        val original = viewModel.drafts.value.single()
        assertTrue(original.isManual)
        assertEquals(100, original.confidence)

        viewModel.saveDraft(
            draftId = original.id,
            assetName = "Updated Holding",
            transactionType = "BUY",
            quantity = "3",
            price = "110.00",
            netAmount = "330.00",
        )

        assertEquals(1, viewModel.drafts.value.size)
        assertEquals("Updated Holding", viewModel.drafts.value.single().assetName)
        assertEquals(original.id, viewModel.drafts.value.single().id)
    }

    @Test
    fun editingDraftPreservesExistingTradeDate() = runTest {
        val existingDraft = DraftTransactionEntity(
            id = "draft-date-1",
            documentId = document.id,
            assetName = "TCS",
            transactionType = "BUY",
            quantity = "10",
            price = "3500.00",
            netAmount = "35000.00",
            confidence = 95,
            tradeDate = 1715385600000L, // 2024-05-11 UTC
        )
        repository.insertDraftTransactionsIfEmpty(document.id, listOf(existingDraft))

        viewModel.saveDraft(
            draftId = "draft-date-1",
            assetName = "TCS Updated",
            transactionType = "BUY",
            quantity = "15",
            price = "3500.00",
            netAmount = "52500.00",
        )

        val updated = viewModel.drafts.value.first { it.id == "draft-date-1" }
        assertEquals("TCS Updated", updated.assetName)
        assertEquals(1715385600000L, updated.tradeDate)
    }

    @Test
    fun formatEpochMillisToDateFormatsCorrectly() {
        // Test known epoch millis
        val epochMillis = 1715385600000L
        val formatted = formatEpochMillisToDate(epochMillis)
        assertTrue(formatted.matches(Regex("\\d{4}-\\d{2}-\\d{2}")))
    }

    @Test
    fun doesNotStartAiWhenOcrIsNotComplete() {
        repository.document = document.copy(status = DocumentStatus.PENDING_OCR.name)
        viewModel.loadDocument(document.id)
        viewModel.onProcessWithAi()

        assertEquals(0, repository.aiProcessCallCount)
        assertTrue(viewModel.actionState.value is ReviewActionState.Error)
    }

    @Test
    fun mapsStructuredDocumentAndLoadsFileHeaders() {
        repository.document = document.copy(status = DocumentStatus.READY_FOR_MAPPING.name)
        viewModel.loadDocument(document.id)

        assertEquals(
            listOf("Symbol", "Type", "Quantity", "Price", "Amount", "Date"),
            viewModel.structuredHeaders.value,
        )
        viewModel.processMappedData(
            brokerName = "Zerodha",
            assetColumn = "Symbol",
            typeColumn = "Type",
            quantityColumn = "Quantity",
            priceColumn = "Price",
            amountColumn = "Amount",
            dateColumn = "Date",
            headerRowNumber = 3,
        )

        assertEquals(1, repository.structuredProcessCallCount)
        assertEquals(3, repository.lastProcessedMapping?.headerRowNumber)
        assertEquals("Mapped asset", viewModel.drafts.value.single().assetName)
    }

    @Test
    fun reloadsHeadersWhenHeaderRowChanges() {
        repository.document = document.copy(status = DocumentStatus.READY_FOR_MAPPING.name)
        viewModel.loadDocument(document.id)

        assertEquals(
            listOf("Symbol", "Type", "Quantity", "Price", "Amount", "Date"),
            viewModel.structuredHeaders.value,
        )

        viewModel.loadHeadersForHeaderRow(3)

        assertEquals(
            listOf("H3_Symbol", "H3_Type", "H3_Quantity", "H3_Price", "H3_Amount", "H3_Date"),
            viewModel.structuredHeaders.value,
        )
    }

    @Test
    fun clearsHeadersWhenHeaderRowFails() {
        repository.document = document.copy(status = DocumentStatus.READY_FOR_MAPPING.name)
        viewModel.loadDocument(document.id)

        assertEquals(
            listOf("Symbol", "Type", "Quantity", "Price", "Amount", "Date"),
            viewModel.structuredHeaders.value,
        )

        viewModel.loadHeadersForHeaderRow(99)

        assertEquals(emptyList<String>(), viewModel.structuredHeaders.value)
        assertEquals(
            ReviewActionState.Error("The specified header row does not exist in this file."),
            viewModel.actionState.value,
        )
    }

    @Test
    fun exposesMissingDocumentState() {
        repository.document = null
        viewModel.loadDocument("missing")

        assertEquals(ReviewUiState.NotFound, viewModel.uiState.value)
    }

    @Test
    fun processWithAiHandlesEmptyExtractedTextException() {
        val exception = EmptyExtractedTextException()
        repository.throwExceptionOnAi = exception
        viewModel.onProcessWithAi()

        assertEquals(
            ReviewActionState.Error(exception.message!!),
            viewModel.actionState.value,
        )
        assertEquals(exception.message, viewModel.errorMessage.value)
    }

    @Test
    fun processWithAiHandlesMissingApiKeyException() {
        repository.throwExceptionOnAi = MissingApiKeyException()
        viewModel.onProcessWithAi()

        assertEquals(ReviewActionState.MissingApiKey, viewModel.actionState.value)
    }

    @Test
    fun processWithAiHandlesAiExtractionException() {
        val exception = AiExtractionException("Invalid JSON")
        repository.throwExceptionOnAi = exception
        viewModel.onProcessWithAi()

        assertEquals(
            ReviewActionState.Error(exception.message!!),
            viewModel.actionState.value,
        )
        assertEquals(exception.message, viewModel.errorMessage.value)
    }

    @Test
    fun processWithAiHandlesNoFinancialDataException() {
        val exception = NoFinancialDataException()
        repository.throwExceptionOnAi = exception
        viewModel.onProcessWithAi()

        val expected = "No financial transactions found in this document."
        assertEquals(
            ReviewActionState.NoFinancialData(expected),
            viewModel.actionState.value,
        )
        assertEquals(expected, viewModel.errorMessage.value)
        assertEquals(Pair("document-1", DocumentStatus.FAILED.name), repository.lastUpdatedStatus)
    }

    @Test
    fun processWithAiHandlesGenericException() {
        val exception = RuntimeException("Connection timeout")
        repository.throwExceptionOnAi = exception
        viewModel.onProcessWithAi()

        val expected = "Network error: Connection timeout"
        assertEquals(
            ReviewActionState.Error(expected),
            viewModel.actionState.value,
        )
        assertEquals(expected, viewModel.errorMessage.value)
    }

    @Test
    fun autoMapHeadersCorrectlyMapsAllFields() {
        val headers = listOf("Stock Symbol", "Trade Qty", "Unit Price", "Total Amount", "Trade Date", "Action Type")
        val mapping = autoMapHeaders(headers)

        assertEquals("Stock Symbol", mapping["Asset"])
        assertEquals("Trade Qty", mapping["Quantity"])
        assertEquals("Unit Price", mapping["Price"])
        assertEquals("Total Amount", mapping["Amount"])
        assertEquals("Trade Date", mapping["Date"])
        assertEquals("Action Type", mapping["Type"])
    }

    @Test
    fun autoMapHeadersRecognizesAlternativeKeywords() {
        val headers = listOf("Company Name", "Units", "NAV", "Value", "Time", "Buy/Sell")
        val mapping = autoMapHeaders(headers)

        assertEquals("Company Name", mapping["Asset"])
        assertEquals("Units", mapping["Quantity"])
        assertEquals("NAV", mapping["Price"])
        assertEquals("Value", mapping["Amount"])
        assertEquals("Time", mapping["Date"])
        assertEquals("Buy/Sell", mapping["Type"])
    }

    private class FakeDocumentRepository(
        var document: DocumentEntity?,
    ) : DocumentRepository {
        var throwExceptionOnAi: Exception? = null
        var lastUpdatedStatus: Pair<String, String>? = null

        override suspend fun updateDocumentStatus(documentId: String, status: String) {
            lastUpdatedStatus = Pair(documentId, status)
        }
        private val drafts = MutableStateFlow<List<DraftTransactionEntity>>(emptyList())
        var aiProcessCallCount = 0
            private set
        var structuredProcessCallCount = 0
            private set

        var lastProcessedMapping: ColumnMappingEntity? = null
            private set

        override fun getAllDocuments(): Flow<List<DocumentEntity>> =
            flowOf(listOfNotNull(document))

        override suspend fun getDocumentById(id: String): DocumentEntity? =
            document?.takeIf { it.id == id }

        override fun observeDocumentById(id: String): Flow<DocumentEntity?> =
            flowOf(document?.takeIf { it.id == id })

        override fun getDraftTransactions(
            documentId: String,
        ): Flow<List<DraftTransactionEntity>> = drafts

        override suspend fun insertDraftTransactionsIfEmpty(
            documentId: String,
            drafts: List<DraftTransactionEntity>,
        ) {
            if (this.drafts.value.isEmpty()) this.drafts.value = drafts
        }

        override suspend fun insertDraftTransaction(draft: DraftTransactionEntity) {
            drafts.value = drafts.value + draft
        }

        override suspend fun updateDraftTransaction(draft: DraftTransactionEntity) {
            drafts.value = drafts.value.map { if (it.id == draft.id) draft else it }
        }

        override suspend fun setDraftAccepted(draftId: String, accepted: Boolean) = Unit

        override suspend fun acceptHighConfidenceDrafts(
            documentId: String,
            minimumConfidence: Int,
        ) = Unit

        var duplicateMapToReturn: Map<String, DuplicateMatchInfo> = emptyMap()
        var lastSavedSelectedIds: Set<String>? = null
        var customResult: SaveDraftTransactionsResult? = null

        override suspend fun findDuplicateDrafts(
            documentId: String,
        ): Map<String, DuplicateMatchInfo> = duplicateMapToReturn

        override suspend fun saveDraftTransactions(
            documentId: String,
            selectedDraftIds: Set<String>?,
        ): SaveDraftTransactionsResult {
            lastSavedSelectedIds = selectedDraftIds
            return customResult ?: SaveDraftTransactionsResult(savedCount = 4, duplicateCount = 1)
        }

        override suspend fun processDocumentWithAi(documentId: String) {
            throwExceptionOnAi?.let { throw it }
            processDocumentWithMockAi(documentId)
        }

        var nextBatchResultOverride: AiBatchProcessResult? = null
        var nextBatchDraftsToInsert: List<DraftTransactionEntity> = emptyList()
        var autoProcessResultOverride: AiBatchProcessResult? = null
        var updatedCarryOverContexts = mutableListOf<AiCarryOverContext>()
        var updatedAssetClasses = mutableListOf<AssetClass>()

        override suspend fun processNextAiBatch(
            documentId: String,
            onProgress: (suspend (stage: String, current: Int, total: Int) -> Unit)?,
        ): AiBatchProcessResult {
            nextBatchResultOverride?.let { overrideResult ->
                throwExceptionOnAi?.let { throw it }
                aiProcessCallCount++
                if (nextBatchDraftsToInsert.isNotEmpty()) {
                    drafts.value = drafts.value + nextBatchDraftsToInsert
                }
                document = document?.copy(
                    processedPages = overrideResult.endPage,
                    pageCount = overrideResult.totalPages,
                    status = if (overrideResult.stopReason == AiBatchStopReason.COMPLETED_ALL_PAGES) {
                        DocumentStatus.AI_COMPLETE.name
                    } else {
                        DocumentStatus.AI_PAUSED.name
                    },
                    carryOverContextJson = overrideResult.carryOverContext.toJson()
                        .takeUnless { overrideResult.carryOverContext.isEmpty() },
                    extractionError = overrideResult.message,
                )
                return overrideResult
            }
            return super.processNextAiBatch(documentId, onProgress)
        }

        override suspend fun autoProcessRemainingAiBatches(
            documentId: String,
            onProgress: (suspend (stage: String, current: Int, total: Int) -> Unit)?,
        ): AiBatchProcessResult {
            autoProcessResultOverride?.let { overrideResult ->
                throwExceptionOnAi?.let { throw it }
                aiProcessCallCount++
                document = document?.copy(
                    processedPages = overrideResult.endPage,
                    pageCount = overrideResult.totalPages,
                    status = if (overrideResult.stopReason == AiBatchStopReason.COMPLETED_ALL_PAGES) {
                        DocumentStatus.AI_COMPLETE.name
                    } else {
                        DocumentStatus.AI_PAUSED.name
                    },
                    carryOverContextJson = overrideResult.carryOverContext.toJson()
                        .takeUnless { overrideResult.carryOverContext.isEmpty() },
                    extractionError = overrideResult.message,
                )
                return overrideResult
            }
            return super.autoProcessRemainingAiBatches(documentId, onProgress)
        }

        override suspend fun updateCarryOverContext(documentId: String, context: AiCarryOverContext) {
            updatedCarryOverContexts.add(context)
            document = document?.copy(carryOverContextJson = context.toJson())
        }

        override suspend fun updateDocumentAssetClass(documentId: String, assetClass: AssetClass) {
            updatedAssetClasses.add(assetClass)
            document = document?.copy(assetClass = assetClass.name)
        }

        override suspend fun processDocumentWithMockAi(documentId: String) {
            aiProcessCallCount++
            insertDraftTransactionsIfEmpty(
                documentId,
                listOf(98, 98, 98, 85, 65).mapIndexed { index, confidence ->
                    DraftTransactionEntity(
                        documentId = documentId,
                        assetName = "Test asset $index",
                        transactionType = "BUY",
                        quantity = "1",
                        price = "10",
                        netAmount = "10",
                        confidence = confidence,
                    )
                },
            )
        }

        override suspend fun getStructuredFileHeaders(
            documentId: String,
            headerRowNumber: Int,
        ): List<String> =
            when (headerRowNumber) {
                3 -> listOf("H3_Symbol", "H3_Type", "H3_Quantity", "H3_Price", "H3_Amount", "H3_Date")
                99 -> throw IllegalArgumentException("The specified header row does not exist in this file.")
                else -> listOf("Symbol", "Type", "Quantity", "Price", "Amount", "Date")
            }

        override suspend fun processStructuredDocument(
            documentId: String,
            mapping: ColumnMappingEntity,
        ) {
            structuredProcessCallCount++
            lastProcessedMapping = mapping
            drafts.value = listOf(
                DraftTransactionEntity(
                    documentId = documentId,
                    assetName = "Mapped asset",
                    transactionType = "BUY",
                    quantity = "1",
                    price = "10",
                    netAmount = "10",
                    confidence = 100,
                ),
            )
            document = document?.copy(status = DocumentStatus.AI_COMPLETE.name)
        }

        override suspend fun ingestFile(uri: Uri, context: Context): DocumentEntity =
            error("Not used in this test")

        var attachedCsvDocumentId: String? = null
        var attachedCsvFile: File? = null

        override suspend fun attachExtractedCsv(documentId: String, csvFile: File) {
            attachedCsvDocumentId = documentId
            attachedCsvFile = csvFile
        }

        var unlockCallCount = 0
        var lastUnlockPassword: String? = null
        var unlockResult: Result<Unit> = Result.success(Unit)

        override suspend fun unlockAndProcessPdf(
            documentId: String,
            password: String,
        ): Result<Unit> {
            unlockCallCount++
            lastUnlockPassword = password
            if (unlockResult.isSuccess) {
                document = document?.copy(extractedText = "Unlocked document text")
            }
            return unlockResult
        }

        var backgroundSchedulerEnabled = false
        var geminiApiKeyPresent = true
        val enqueuedBatchDocIds = mutableListOf<String>()
        val enqueuedAutoDocIds = mutableListOf<String>()
        val cancelledDocIds = mutableListOf<String>()

        override fun hasBackgroundScheduler(): Boolean = backgroundSchedulerEnabled

        override suspend fun hasGeminiApiKey(): Boolean = geminiApiKeyPresent

        override suspend fun enqueueAiBatch(documentId: String) {
            enqueuedBatchDocIds.add(documentId)
        }

        override suspend fun enqueueAiAutoProcess(documentId: String) {
            enqueuedAutoDocIds.add(documentId)
        }

        override suspend fun cancelAiProcessing(documentId: String) {
            cancelledDocIds.add(documentId)
        }
    }

    @Test
    fun unlockPdfSuccessClearsPasswordPromptAndStoresPassword() = runTest {
        val doc = DocumentEntity(
            id = "doc_locked",
            fileName = "cams.pdf",
            localPath = "/data/cams.pdf",
            fileHash = "hash1",
            fileSize = 100L,
            importDate = 1000L,
            status = DocumentStatus.OCR_FAILED.name,
            extractedText = "[PASSWORD_PROTECTED]",
        )
        repository.document = doc
        viewModel.loadDocument("doc_locked")
        testScheduler.advanceUntilIdle()

        assertTrue(viewModel.isPasswordProtected.value)

        viewModel.unlockPdf("ABCDE1234F")
        testScheduler.advanceUntilIdle()

        assertFalse(viewModel.isPasswordProtected.value)
        assertEquals("ABCDE1234F", viewModel.unlockedPassword.value)
        assertNull(viewModel.passwordError.value)
        assertEquals(1, repository.unlockCallCount)
        assertEquals("ABCDE1234F", repository.lastUnlockPassword)
    }

    @Test
    fun unlockPdfFailureSetsPasswordError() = runTest {
        val doc = DocumentEntity(
            id = "doc_locked_2",
            fileName = "cams.pdf",
            localPath = "/data/cams.pdf",
            fileHash = "hash2",
            fileSize = 100L,
            importDate = 1000L,
            status = DocumentStatus.OCR_FAILED.name,
            extractedText = "[PASSWORD_PROTECTED]",
        )
        repository.document = doc
        repository.unlockResult = Result.failure<Unit>(Exception("Incorrect password"))
        viewModel.loadDocument("doc_locked_2")
        testScheduler.advanceUntilIdle()

        viewModel.unlockPdf("WRONG_PASS")
        testScheduler.advanceUntilIdle()

        assertTrue(viewModel.isPasswordProtected.value)
        assertEquals("Incorrect password", viewModel.passwordError.value)
        assertNull(viewModel.unlockedPassword.value)
    }

    @Test
    fun dismissTableReviewHidesDialog() {
        viewModel.dismissTableReview()
        assertEquals(false, viewModel.showTableReview.value)
    }

    @Test
    fun resumesAiExtractionForFailedDocumentEvenWithPartialDrafts() = runTest {
        val partialDraft = DraftTransactionEntity(
            id = "partial-1",
            documentId = document.id,
            assetName = "Partial Asset",
            transactionType = "BUY",
            quantity = "5",
            price = "100",
            netAmount = "500",
            confidence = 95,
        )
        repository.insertDraftTransactionsIfEmpty(document.id, listOf(partialDraft))
        repository.document = document.copy(
            status = DocumentStatus.FAILED.name,
            pageCount = 10,
            processedPages = 5,
            extractionError = "Paused at page 5",
        )
        viewModel.loadDocument(document.id)
        testScheduler.advanceUntilIdle()

        viewModel.onProcessWithAi()
        testScheduler.advanceUntilIdle()

        assertEquals(1, repository.aiProcessCallCount)
    }

    @Test
    fun batch1PauseUpdatesCarryOverOnUserFixAndGatesCommitUntilAllPagesFinish() = runTest {
        val multiPageDoc = document.copy(
            status = DocumentStatus.OCR_COMPLETE.name,
            pageCount = 15,
            processedPages = 0,
        )
        repository.document = multiPageDoc
        val ctx1 = AiCarryOverContext(
            activeAmc = "HDFC Mutual Fund",
            activeScheme = "HDFC Top 100",
            activeIsin = "INF179K01BE2",
            activeBroker = "CAMS",
        )
        repository.nextBatchResultOverride = AiBatchProcessResult(
            startPage = 1,
            endPage = 5,
            totalPages = 15,
            newDraftsCount = 1,
            flaggedDraftCount = 0,
            carryOverContext = ctx1,
            stopReason = AiBatchStopReason.PAUSED_AFTER_BATCH,
        )
        val batch1Draft = DraftTransactionEntity(
            id = "b1-draft",
            documentId = multiPageDoc.id,
            assetName = "HDFC Top 100",
            transactionType = "BUY",
            quantity = "10",
            price = "100",
            netAmount = "1000",
            confidence = 95,
            assetClass = "STOCK",
            broker = "CAMS",
        )
        repository.nextBatchDraftsToInsert = listOf(batch1Draft)
        viewModel.loadDocument(multiPageDoc.id)
        testScheduler.advanceUntilIdle()

        viewModel.onProcessWithAi()
        testScheduler.advanceUntilIdle()

        val loadedAfterBatch1 = (viewModel.uiState.value as ReviewUiState.Loaded).document
        assertEquals(DocumentStatus.AI_PAUSED.name, loadedAfterBatch1.status)
        assertEquals(5, loadedAfterBatch1.processedPages)
        assertTrue(viewModel.batchBannerMessage.value?.contains("Pages 1–5 of 15") == true)

        // Commit is blocked while pages remain staged/pending
        viewModel.saveConfirmed()
        assertNull(repository.lastSavedSelectedIds)
        assertTrue(viewModel.errorMessage.value?.contains("Finish all 15 pages") == true)

        // User fixes scheme name, asset class, and broker -> updates carryOverContext & doc assetClass
        viewModel.saveDraft(
            draftId = batch1Draft.id,
            assetName = "HDFC Top 100 Fund - Direct Plan",
            transactionType = "BUY",
            quantity = "10",
            price = "100",
            netAmount = "1000",
            assetClass = "MUTUAL_FUND",
            broker = "MFCentral",
        )
        testScheduler.advanceUntilIdle()

        assertEquals("HDFC Top 100 Fund - Direct Plan", repository.updatedCarryOverContexts.last().activeScheme)
        assertEquals("MFCentral", repository.updatedCarryOverContexts.last().activeBroker)
        assertEquals(AssetClass.MUTUAL_FUND, repository.updatedAssetClasses.last())

        // Auto-process finishes remaining pages, unlocking final commit
        repository.autoProcessResultOverride = AiBatchProcessResult(
            startPage = 6,
            endPage = 15,
            totalPages = 15,
            newDraftsCount = 2,
            flaggedDraftCount = 0,
            carryOverContext = repository.updatedCarryOverContexts.last(),
            stopReason = AiBatchStopReason.COMPLETED_ALL_PAGES,
        )
        viewModel.onAutoProcessRemainingAi()
        testScheduler.advanceUntilIdle()

        viewModel.saveConfirmed()
        testScheduler.advanceUntilIdle()
        assertTrue(viewModel.actionState.value is ReviewActionState.Saved)
    }

    @Test
    fun nonBlockingBackgroundAiChecksApiKeyEnqueuesWorkAndSupportsCancellation() = runTest {
        val multiPageDoc = document.copy(
            id = "doc-bg-ai",
            pageCount = 12,
            processedPages = 0,
            status = DocumentStatus.OCR_COMPLETE.name,
        )
        repository.document = multiPageDoc
        repository.backgroundSchedulerEnabled = true
        repository.geminiApiKeyPresent = false

        viewModel.loadDocument(multiPageDoc.id)
        testScheduler.advanceUntilIdle()

        // Without API key -> MissingApiKey dialog state, nothing enqueued
        viewModel.onProcessWithAi()
        testScheduler.advanceUntilIdle()
        assertEquals(ReviewActionState.MissingApiKey, viewModel.actionState.value)
        assertTrue(repository.enqueuedBatchDocIds.isEmpty())

        viewModel.dismissMissingApiKeyDialog()
        assertEquals(ReviewActionState.Idle, viewModel.actionState.value)

        // With API key -> non-blocking batch enqueue & auto-process enqueue & pause/cancel
        repository.geminiApiKeyPresent = true
        viewModel.onProcessWithAi()
        testScheduler.advanceUntilIdle()
        assertEquals(listOf(multiPageDoc.id), repository.enqueuedBatchDocIds)
        assertEquals(ReviewActionState.Idle, viewModel.actionState.value)

        viewModel.onAutoProcessRemainingAi()
        testScheduler.advanceUntilIdle()
        assertEquals(listOf(multiPageDoc.id), repository.enqueuedAutoDocIds)

        viewModel.onCancelAiProcessing()
        testScheduler.advanceUntilIdle()
        assertEquals(listOf(multiPageDoc.id), repository.cancelledDocIds)
    }
}
