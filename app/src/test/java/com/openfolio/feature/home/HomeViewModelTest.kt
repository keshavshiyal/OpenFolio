package com.openfolio.feature.home

import android.content.Context
import android.net.Uri
import com.openfolio.core.data.local.dao.TransactionDao
import com.openfolio.core.data.local.entity.ColumnMappingEntity
import com.openfolio.core.data.local.entity.DocumentEntity
import com.openfolio.core.data.local.entity.DraftTransactionEntity
import com.openfolio.core.data.local.entity.TransactionEntity
import com.openfolio.core.data.local.entity.TransactionStatus
import com.openfolio.core.data.local.entity.TransactionType
import com.openfolio.core.domain.repository.DocumentRepository
import com.openfolio.core.domain.repository.DuplicateDocumentException
import com.openfolio.core.domain.repository.SaveDraftTransactionsResult
import com.openfolio.core.domain.usecase.GetDashboardDataUseCase
import java.math.BigDecimal
import java.time.LocalDate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.mock

@OptIn(ExperimentalCoroutinesApi::class)
class HomeViewModelTest {
    private lateinit var viewModel: HomeViewModel
    private val sampleTxn = TransactionEntity(
        id = "txn-1",
        accountId = "acc-1",
        assetId = "RELIANCE",
        type = TransactionType.BUY,
        tradeDate = LocalDate.now(),
        grossAmount = BigDecimal("12500"),
        netAmount = BigDecimal("12500"),
        currency = "INR",
        fingerprint = "fp-1",
        status = TransactionStatus.CONFIRMED,
    )

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        viewModel = createViewModel()
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

        val state = viewModel.uiState.value
        assertTrue(state is HomeUiState.Success)
        assertEquals("6M", (state as HomeUiState.Success).selectedPeriod)
    }

    @Test
    fun providesTrajectoryPoints() {
        val state = viewModel.uiState.value
        assertTrue(state is HomeUiState.Success)
        val success = state as HomeUiState.Success

        assertTrue(success.trajectoryPoints.size >= 2)
    }

    @Test
    fun mapsAllocationsWhenTransactionsProvided() {
        val stockTxn = sampleTxn.copy(grossAmount = BigDecimal("10000"))
        val mfTxn = sampleTxn.copy(id = "txn-2", assetId = "NIFTY_50_INDEX", grossAmount = BigDecimal("10000"))
        val fakeAssetDao = FakeAssetDao(
            listOf(
                com.openfolio.core.data.local.entity.AssetEntity(
                    id = "RELIANCE",
                    assetClass = com.openfolio.core.data.local.entity.AssetClass.STOCK,
                    displayName = "Reliance",
                    currency = "INR",
                ),
                com.openfolio.core.data.local.entity.AssetEntity(
                    id = "NIFTY_50_INDEX",
                    assetClass = com.openfolio.core.data.local.entity.AssetClass.MUTUAL_FUND,
                    displayName = "Nifty 50 Index",
                    currency = "INR",
                ),
            ),
        )
        val dao = FakeTransactionDao(
            assets = 20000.0,
            liabilities = 0.0,
            recentTransactions = listOf(stockTxn, mfTxn),
            allTransactions = listOf(stockTxn, mfTxn),
        )
        val useCase = GetDashboardDataUseCase(dao, fakeAssetDao)
        val vm = HomeViewModel(
            context = mock(),
            documentRepository = FakeDocumentRepository(),
            getDashboardDataUseCase = useCase,
        )

        val state = vm.uiState.value
        assertTrue(state is HomeUiState.Success)
        val success = state as HomeUiState.Success
        assertEquals(2, success.allocations.size)
        assertEquals("Equity", success.allocations[0].label)
        assertEquals(50, success.allocations[0].percentage)
        assertEquals("Mutual funds", success.allocations[1].label)
        assertEquals(50, success.allocations[1].percentage)
    }

    @Test
    fun emitsEmptyStateWhenAssetsZeroAndNoTransactions() {
        val emptyViewModel = createViewModel(
            assets = null,
            liabilities = 0.0,
            recentTransactions = emptyList(),
        )

        assertEquals(HomeUiState.Empty, emptyViewModel.uiState.value)
    }

    @Test
    fun emitsSuccessStateWhenAssetsPresent() {
        val state = viewModel.uiState.value
        assertTrue(state is HomeUiState.Success)
        val success = state as HomeUiState.Success

        assertEquals(0, BigDecimal("3120000.0").compareTo(success.data.totalAssets))
        assertEquals(0, BigDecimal("661700.0").compareTo(success.data.totalLiabilities))
        assertEquals(0, BigDecimal("2458300.0").compareTo(success.data.netWorth))
        assertEquals(1, success.data.recentTransactions.size)
    }

    @Test
    fun emitsSuccessStateWhenOnlyTransactionsPresent() {
        val successViewModel = createViewModel(
            assets = null,
            liabilities = 0.0,
            recentTransactions = listOf(sampleTxn),
        )

        val state = successViewModel.uiState.value
        assertTrue(state is HomeUiState.Success)
        val success = state as HomeUiState.Success
        assertEquals(1, success.data.recentTransactions.size)
    }

    @Test
    fun reportsDuplicateFileErrorsInIngestionState() {
        viewModel = createViewModel(
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

    private fun createViewModel(
        documentRepository: DocumentRepository = FakeDocumentRepository(),
        assets: Double? = 3120000.0,
        liabilities: Double = 661700.0,
        recentTransactions: List<TransactionEntity> = listOf(sampleTxn),
    ): HomeViewModel {
        val dao = FakeTransactionDao(assets, liabilities, recentTransactions)
        val useCase = GetDashboardDataUseCase(dao)
        return HomeViewModel(
            context = mock(),
            documentRepository = documentRepository,
            getDashboardDataUseCase = useCase,
        )
    }

    private class FakeTransactionDao(
        private val assets: Double?,
        private val liabilities: Double?,
        private val recentTransactions: List<TransactionEntity>,
        private val allTransactions: List<TransactionEntity> = emptyList(),
    ) : TransactionDao {
        override suspend fun insert(transaction: TransactionEntity) = Unit
        override suspend fun insertAll(transactions: List<TransactionEntity>) = Unit
        override suspend fun update(transaction: TransactionEntity) = Unit
        override suspend fun delete(transaction: TransactionEntity) = Unit
        override suspend fun getByFingerprint(fingerprint: String): TransactionEntity? = null
        override suspend fun containsFingerprint(fingerprint: String): Boolean = false
        override suspend fun getById(id: String): TransactionEntity? = null
        override fun getAll(): Flow<List<TransactionEntity>> = flowOf(allTransactions)
        override fun getByAssetId(assetId: String): Flow<List<TransactionEntity>> = flowOf(emptyList())
        override fun getByAccountId(accountId: String): Flow<List<TransactionEntity>> = flowOf(emptyList())
        override fun getTotalAssetsFlow(): Flow<Double?> = flowOf(assets)
        override fun getTotalLiabilitiesFlow(): Flow<Double?> = flowOf(liabilities)
        override fun getRecentTransactions(limit: Int): Flow<List<TransactionEntity>> = flowOf(recentTransactions)
    }

    private class FakeAssetDao(
        private val assets: List<com.openfolio.core.data.local.entity.AssetEntity> = emptyList(),
    ) : com.openfolio.core.data.local.dao.AssetDao {
        override suspend fun insert(asset: com.openfolio.core.data.local.entity.AssetEntity) = Unit
        override suspend fun insertAll(assets: List<com.openfolio.core.data.local.entity.AssetEntity>) = Unit
        override suspend fun update(asset: com.openfolio.core.data.local.entity.AssetEntity) = Unit
        override suspend fun delete(asset: com.openfolio.core.data.local.entity.AssetEntity) = Unit
        override suspend fun getById(id: String): com.openfolio.core.data.local.entity.AssetEntity? =
            assets.firstOrNull { it.id == id }
        override fun getAll(): Flow<List<com.openfolio.core.data.local.entity.AssetEntity>> = flowOf(assets)
        override fun getByAssetClass(assetClass: com.openfolio.core.data.local.entity.AssetClass): Flow<List<com.openfolio.core.data.local.entity.AssetEntity>> =
            flowOf(assets.filter { it.assetClass == assetClass })
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

        override suspend fun updateDocumentStatus(documentId: String, status: String) = Unit

        override suspend fun processDocumentWithAi(documentId: String) = Unit

        override suspend fun processDocumentWithMockAi(documentId: String) = Unit

        override suspend fun getStructuredFileHeaders(
            documentId: String,
            headerRowNumber: Int,
        ): List<String> = emptyList()

        override suspend fun processStructuredDocument(
            documentId: String,
            mapping: ColumnMappingEntity,
        ) = Unit

        override suspend fun ingestFile(
            uri: Uri,
            context: Context,
        ): DocumentEntity = ingest()
    }
}
