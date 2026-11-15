package com.openfolio.feature.inbox

import android.content.Context
import android.net.Uri
import com.openfolio.core.data.local.entity.DocumentEntity
import com.openfolio.core.data.local.entity.DraftTransactionEntity
import com.openfolio.core.data.local.entity.ColumnMappingEntity
import com.openfolio.core.domain.repository.DocumentRepository
import com.openfolio.core.domain.repository.SaveDraftTransactionsResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class InboxViewModelTest {
    private val pendingDocument = DocumentEntity(
        fileName = "statement.pdf",
        localPath = "/documents/statement.pdf",
        fileHash = "hash",
        fileSize = 100L,
        importDate = 1L,
    )
    private lateinit var viewModel: InboxViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        viewModel = InboxViewModel(FakeDocumentRepository(listOf(pendingDocument)))
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun exposesDocumentsFromRepository() {
        assertEquals(listOf(pendingDocument), viewModel.documents.value)
    }

    private class FakeDocumentRepository(
        private val items: List<DocumentEntity>,
    ) : DocumentRepository {
        override fun getAllDocuments(): Flow<List<DocumentEntity>> = flowOf(items)

        override suspend fun getDocumentById(id: String): DocumentEntity? =
            items.firstOrNull { it.id == id }

        override fun observeDocumentById(id: String): Flow<DocumentEntity?> =
            flowOf(items.firstOrNull { it.id == id })

        override fun getDraftTransactions(
            documentId: String,
        ): Flow<List<DraftTransactionEntity>> = flowOf(emptyList())

        override suspend fun insertDraftTransactionsIfEmpty(
            documentId: String,
            drafts: List<DraftTransactionEntity>,
        ) = Unit

        override suspend fun insertDraftTransaction(draft: DraftTransactionEntity) = Unit

        override suspend fun updateDraftTransaction(draft: DraftTransactionEntity) = Unit

        override suspend fun setDraftAccepted(draftId: String, accepted: Boolean) = Unit

        override suspend fun acceptHighConfidenceDrafts(
            documentId: String,
            minimumConfidence: Int,
        ) = Unit

        override suspend fun saveDraftTransactions(
            documentId: String,
        ): SaveDraftTransactionsResult = SaveDraftTransactionsResult(0, 0)

        override suspend fun processDocumentWithMockAi(documentId: String) = Unit

        override suspend fun getStructuredFileHeaders(
            documentId: String,
            headerRowNumber: Int,
        ): List<String> = emptyList()

        override suspend fun processStructuredDocument(
            documentId: String,
            mapping: ColumnMappingEntity,
        ) = Unit

        override suspend fun ingestFile(uri: Uri, context: Context): DocumentEntity =
            error("Not used in this test")
    }
}
