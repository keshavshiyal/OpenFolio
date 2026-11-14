package com.openfolio.feature.review

import android.content.Context
import android.net.Uri
import com.openfolio.core.data.local.entity.DocumentEntity
import com.openfolio.core.data.local.entity.DraftTransactionEntity
import com.openfolio.core.domain.repository.DocumentRepository
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
    )
    private lateinit var viewModel: ReviewViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        viewModel = ReviewViewModel(FakeDocumentRepository(document))
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun loadsDocumentById() {
        viewModel.loadDocument(document.id)

        assertEquals(ReviewUiState.Loaded(document), viewModel.uiState.value)
        assertEquals(5, viewModel.drafts.value.size)
        assertTrue(viewModel.drafts.value.all { it.documentId == document.id })
        assertEquals(
            listOf(98, 98, 98, 85, 60),
            viewModel.drafts.value.map(DraftTransactionEntity::confidence),
        )
    }

    @Test
    fun acceptsOnlyHighConfidenceDraftsInBulk() {
        val repository = FakeDocumentRepository(document)
        viewModel = ReviewViewModel(repository)
        viewModel.loadDocument(document.id)

        viewModel.acceptAllHighConfidence()

        assertEquals(
            listOf(true, true, true, false, false),
            viewModel.drafts.value.map(DraftTransactionEntity::isAccepted),
        )
    }

    @Test
    fun savesAcceptedDraftsAndExposesSavedCount() {
        val repository = FakeDocumentRepository(document)
        viewModel = ReviewViewModel(repository)
        viewModel.loadDocument(document.id)
        viewModel.acceptAllHighConfidence()

        viewModel.saveConfirmed()

        assertEquals(3, (viewModel.actionState.value as ReviewActionState.Saved).count)
    }

    @Test
    fun exposesMissingDocumentState() {
        viewModel.loadDocument("missing")

        assertEquals(ReviewUiState.NotFound, viewModel.uiState.value)
    }

    private class FakeDocumentRepository(
        private val document: DocumentEntity,
    ) : DocumentRepository {
        private val drafts = MutableStateFlow<List<DraftTransactionEntity>>(emptyList())

        override fun getAllDocuments(): Flow<List<DocumentEntity>> = flowOf(listOf(document))

        override suspend fun getDocumentById(id: String): DocumentEntity? =
            document.takeIf { it.id == id }

        override fun getDraftTransactions(
            documentId: String,
        ): Flow<List<DraftTransactionEntity>> = drafts

        override suspend fun insertDraftTransactionsIfEmpty(
            documentId: String,
            drafts: List<DraftTransactionEntity>,
        ) {
            if (this.drafts.value.isEmpty()) this.drafts.value = drafts
        }

        override suspend fun setDraftAccepted(draftId: String, accepted: Boolean) {
            drafts.value = drafts.value.map {
                if (it.id == draftId) it.copy(isAccepted = accepted) else it
            }
        }

        override suspend fun acceptHighConfidenceDrafts(
            documentId: String,
            minimumConfidence: Int,
        ) {
            drafts.value = drafts.value.map {
                if (it.documentId == documentId && it.confidence >= minimumConfidence) {
                    it.copy(isAccepted = true)
                } else {
                    it
                }
            }
        }

        override suspend fun saveAcceptedDraftTransactions(documentId: String): Int =
            drafts.value.count { it.documentId == documentId && it.isAccepted }

        override suspend fun ingestFile(uri: Uri, context: Context): DocumentEntity =
            error("Not used in this test")
    }
}
