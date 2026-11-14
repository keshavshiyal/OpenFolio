package com.openfolio.feature.home

import android.content.Context
import android.net.Uri
import com.openfolio.core.data.local.entity.DocumentEntity
import com.openfolio.core.data.local.entity.DraftTransactionEntity
import com.openfolio.core.domain.repository.DocumentRepository
import com.openfolio.core.domain.repository.DuplicateDocumentException
import com.openfolio.core.domain.repository.SaveDraftTransactionsResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.mock

@OptIn(ExperimentalCoroutinesApi::class)
class HomeViewModelTest {
    private lateinit var viewModel: HomeViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        viewModel = HomeViewModel(
            context = org.mockito.kotlin.mock<Context>(),
            documentRepository = FakeDocumentRepository(),
        )
    }

    @After
    fun tearDown() {
        viewModel.clearIngestionState()
        Dispatchers.resetMain()
    }

    @Test
    fun togglesValuesVisibility() {
        assertTrue(viewModel.uiState.value.showValues)

        viewModel.toggleValuesVisibility()
        assertFalse(viewModel.uiState.value.showValues)

        viewModel.toggleValuesVisibility()
        assertTrue(viewModel.uiState.value.showValues)
    }

    @Test
    fun selectsSupportedChartPeriod() {
        viewModel.selectPeriod("6M")

        assertEquals("6M", viewModel.uiState.value.selectedPeriod)
    }

    @Test
    fun providesTrajectoryPointsAndAllAllocationSlots() {
        val state = viewModel.uiState.value

        assertTrue(state.trajectoryPoints.size >= 2)
        assertEquals(
            listOf(
                AllocationColor.Slot1,
                AllocationColor.Slot2,
                AllocationColor.Slot3,
                AllocationColor.Slot4,
                AllocationColor.Slot5,
            ),
            state.allocations.map { it.color },
        )
    }

    @Test
    fun reportsDuplicateFileErrorsInIngestionState() {
        viewModel = HomeViewModel(
            context = mock(),
            documentRepository = FakeDocumentRepository {
                throw DuplicateDocumentException("duplicate-hash")
            },
        )

        viewModel.ingestFile(mock())

        assertEquals(IngestionState.Error("File already imported"), viewModel.ingestionState.value)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsUnsupportedChartPeriod() {
        viewModel.selectPeriod("2Y")
    }

    private class FakeDocumentRepository(
        private val ingest: suspend () -> DocumentEntity = { error("Not used in this test") },
    ) : DocumentRepository {
        override fun getAllDocuments(): Flow<List<DocumentEntity>> = flowOf(emptyList())

        override suspend fun getDocumentById(id: String): DocumentEntity? = null

        override fun observeDocumentById(id: String): Flow<DocumentEntity?> = flowOf(null)

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

        override suspend fun ingestFile(
            uri: Uri,
            context: Context,
        ): DocumentEntity = ingest()
    }
}
