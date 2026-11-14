package com.openfolio.feature.review

import android.content.Context
import android.net.Uri
import com.openfolio.core.data.local.entity.DocumentEntity
import com.openfolio.core.domain.repository.DocumentRepository
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
    }

    @Test
    fun exposesMissingDocumentState() {
        viewModel.loadDocument("missing")

        assertEquals(ReviewUiState.NotFound, viewModel.uiState.value)
    }

    private class FakeDocumentRepository(
        private val document: DocumentEntity,
    ) : DocumentRepository {
        override fun getAllDocuments(): Flow<List<DocumentEntity>> = flowOf(listOf(document))

        override suspend fun getDocumentById(id: String): DocumentEntity? =
            document.takeIf { it.id == id }

        override suspend fun ingestFile(uri: Uri, context: Context): DocumentEntity =
            error("Not used in this test")
    }
}
