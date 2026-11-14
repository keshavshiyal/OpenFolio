package com.openfolio.feature.review

import android.content.Context
import android.net.Uri
import com.openfolio.core.data.local.entity.DocumentEntity
import com.openfolio.core.data.local.entity.DocumentStatus
import com.openfolio.core.data.local.entity.DraftTransactionEntity
import com.openfolio.core.data.local.entity.ColumnMappingEntity
import com.openfolio.core.domain.repository.DocumentRepository
import com.openfolio.core.domain.repository.SaveDraftTransactionsResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
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
    fun exposesMissingDocumentState() {
        repository.document = null
        viewModel.loadDocument("missing")

        assertEquals(ReviewUiState.NotFound, viewModel.uiState.value)
    }

    private class FakeDocumentRepository(
        var document: DocumentEntity?,
    ) : DocumentRepository {
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

        override suspend fun saveDraftTransactions(
            documentId: String,
        ): SaveDraftTransactionsResult =
            SaveDraftTransactionsResult(savedCount = 4, duplicateCount = 1)

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
            if (headerRowNumber == 3) {
                listOf("H3_Symbol", "H3_Type", "H3_Quantity", "H3_Price", "H3_Amount", "H3_Date")
            } else {
                listOf("Symbol", "Type", "Quantity", "Price", "Amount", "Date")
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
    }
}
