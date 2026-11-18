package com.openfolio.feature.activity

import com.openfolio.core.data.local.dao.AssetDao
import com.openfolio.core.data.local.dao.TransactionDao
import com.openfolio.core.data.local.entity.AssetClass
import com.openfolio.core.data.local.entity.AssetEntity
import com.openfolio.core.data.local.entity.AssetStatus
import com.openfolio.core.data.local.entity.TransactionEntity
import com.openfolio.core.data.local.entity.TransactionStatus
import com.openfolio.core.data.local.entity.TransactionType
import com.openfolio.core.domain.repository.AssetRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
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
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.math.BigDecimal
import java.time.LocalDate

@OptIn(ExperimentalCoroutinesApi::class)
class ActivityViewModelTest {

    private val testDispatcher = StandardTestDispatcher()

    private val transactionsFlow = MutableStateFlow<List<TransactionEntity>>(emptyList())
    private val assetsFlow = MutableStateFlow<List<AssetEntity>>(emptyList())
    private val deletedTransactionIds = mutableListOf<String>()

    private val transactionDao = mock<TransactionDao>()
    private val assetDao = mock<AssetDao>()

    private val fakeAssetRepository = object : AssetRepository {
        override fun getAllAssets(): Flow<List<AssetEntity>> = assetsFlow
        override fun getAssetById(id: String): Flow<AssetEntity?> = flowOf(null)
        override suspend fun getAssetByIdOnce(id: String): AssetEntity? = null
        override fun getTransactionsForAsset(assetId: String): Flow<List<TransactionEntity>> = flowOf(emptyList())
        override suspend fun getTransactionsForAssetOnce(assetId: String): List<TransactionEntity> = emptyList()
        override suspend fun saveTransaction(transaction: TransactionEntity) {}
        override suspend fun updateTransaction(transaction: TransactionEntity) {}
        override suspend fun deleteTransaction(transactionId: String) {
            deletedTransactionIds.add(transactionId)
        }
        override suspend fun recalculateAsset(assetId: String) {}
        override suspend fun deleteAsset(assetId: String) {}
    }

    private val assetStock = AssetEntity(
        id = "nmdc",
        displayName = "NMDC Ltd",
        assetClass = AssetClass.STOCK,
        totalQuantity = BigDecimal("5"),
        totalInvested = BigDecimal("50.00"),
        averageCost = BigDecimal("10.00"),
        realizedPnL = BigDecimal("50.00"),
        status = AssetStatus.ACTIVE,
    )

    private val assetMf = AssetEntity(
        id = "ppfas_flexi",
        displayName = "Parag Parikh Flexi Cap",
        assetClass = AssetClass.MUTUAL_FUND,
        totalQuantity = BigDecimal("100"),
        totalInvested = BigDecimal("5000.00"),
        averageCost = BigDecimal("50.00"),
        realizedPnL = BigDecimal.ZERO,
        status = AssetStatus.ACTIVE,
    )

    private val buyNmdc = TransactionEntity(
        id = "tx-1",
        accountId = "acc-1",
        assetId = "nmdc",
        type = TransactionType.BUY,
        tradeDate = LocalDate.now().minusDays(5),
        settlementDate = LocalDate.now().minusDays(3),
        quantity = BigDecimal("10"),
        unitPrice = BigDecimal("10.00"),
        grossAmount = BigDecimal("100.00"),
        netAmount = BigDecimal("100.00"),
        currency = "INR",
        fingerprint = "fp-1",
        status = TransactionStatus.CONFIRMED,
    )

    private val sellNmdc = TransactionEntity(
        id = "tx-2",
        accountId = "acc-1",
        assetId = "nmdc",
        type = TransactionType.SELL,
        tradeDate = LocalDate.now().minusDays(2),
        settlementDate = LocalDate.now().minusDays(1),
        quantity = BigDecimal("5"),
        unitPrice = BigDecimal("20.00"),
        grossAmount = BigDecimal("100.00"),
        netAmount = BigDecimal("100.00"),
        currency = "INR",
        fingerprint = "fp-2",
        status = TransactionStatus.CONFIRMED,
    )

    private val buyMf = TransactionEntity(
        id = "tx-3",
        accountId = "acc-1",
        assetId = "ppfas_flexi",
        type = TransactionType.BUY,
        tradeDate = LocalDate.now().minusMonths(2),
        quantity = BigDecimal("100"),
        unitPrice = BigDecimal("50.00"),
        grossAmount = BigDecimal("5000.00"),
        netAmount = BigDecimal("5000.00"),
        currency = "INR",
        notes = "SIP investment",
        fingerprint = "fp-3",
        status = TransactionStatus.CONFIRMED,
    )

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        deletedTransactionIds.clear()
        whenever(transactionDao.getAll()).thenReturn(transactionsFlow)
        whenever(assetDao.getAll()).thenReturn(assetsFlow)
        assetsFlow.value = listOf(assetStock, assetMf)
        transactionsFlow.value = listOf(sellNmdc, buyNmdc, buyMf)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun createViewModel() = ActivityViewModel(
        transactionDao = transactionDao,
        assetDao = assetDao,
        assetRepository = fakeAssetRepository,
    )

    @Test
    fun loadsTransactionsAndCalculatesFifoRealizedPnL() = runTest {
        val viewModel = createViewModel()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.uiState.collect {}
        }
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertFalse(state.isLoading)
        assertEquals(3, state.transactions.size)
        assertEquals(3, state.totalCount)

        // Buy volume = 100 + 5000 = 5100
        assertEquals(BigDecimal("5100.00"), state.totalBuyVolume)
        // Sell volume = 100
        assertEquals(BigDecimal("100.00"), state.totalSellVolume)
        // Realized gain on sell: 5 qty * (20 - 10) = 50.00
        assertEquals(BigDecimal("50.00"), state.totalRealizedGain)

        // Check the individual SELL transaction item has realizedPnL = 50.00
        val sellItem = state.transactions.first { it.transaction.id == "tx-2" }
        assertEquals(BigDecimal("50.00"), sellItem.realizedPnL)
        assertEquals("NMDC Ltd", sellItem.assetDisplayName)
        assertEquals(AssetClass.STOCK, sellItem.assetClass)

        // Check BUY item has null realizedPnL
        val buyItem = state.transactions.first { it.transaction.id == "tx-1" }
        assertNull(buyItem.realizedPnL)
    }

    @Test
    fun searchFiltersByAssetDisplayNameAndNotes() = runTest {
        val viewModel = createViewModel()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.uiState.collect {}
        }
        advanceUntilIdle()

        // Filter by asset name "Parag"
        viewModel.onSearchQueryChange("Parag")
        advanceUntilIdle()
        assertEquals(1, viewModel.uiState.value.filteredTransactions.size)
        assertEquals("ppfas_flexi", viewModel.uiState.value.filteredTransactions[0].transaction.assetId)

        // Filter by note "SIP"
        viewModel.onSearchQueryChange("SIP")
        advanceUntilIdle()
        assertEquals(1, viewModel.uiState.value.filteredTransactions.size)
        assertEquals("tx-3", viewModel.uiState.value.filteredTransactions[0].transaction.id)

        // Clear filter
        viewModel.onSearchQueryChange("")
        advanceUntilIdle()
        assertEquals(3, viewModel.uiState.value.filteredTransactions.size)
    }

    @Test
    fun typeFilterFiltersCorrectly() = runTest {
        val viewModel = createViewModel()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.uiState.collect {}
        }
        advanceUntilIdle()

        viewModel.onSelectTypeFilter(TransactionType.SELL)
        advanceUntilIdle()
        assertEquals(1, viewModel.uiState.value.filteredTransactions.size)
        assertEquals("tx-2", viewModel.uiState.value.filteredTransactions[0].transaction.id)

        viewModel.onSelectTypeFilter(TransactionType.BUY)
        advanceUntilIdle()
        assertEquals(2, viewModel.uiState.value.filteredTransactions.size)
    }

    @Test
    fun assetClassFilterFiltersMutualFundsAndStocks() = runTest {
        val viewModel = createViewModel()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.uiState.collect {}
        }
        advanceUntilIdle()

        // Filter by MUTUAL_FUND
        viewModel.onSelectAssetClassFilter(AssetClass.MUTUAL_FUND)
        advanceUntilIdle()
        assertEquals(1, viewModel.uiState.value.filteredTransactions.size)
        assertEquals(AssetClass.MUTUAL_FUND, viewModel.uiState.value.filteredTransactions[0].assetClass)

        // Filter by STOCK
        viewModel.onSelectAssetClassFilter(AssetClass.STOCK)
        advanceUntilIdle()
        assertEquals(2, viewModel.uiState.value.filteredTransactions.size)
    }

    @Test
    fun dateRangeFiltersCorrectly() = runTest {
        val viewModel = createViewModel()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.uiState.collect {}
        }
        advanceUntilIdle()

        // THIS_MONTH includes transactions from this month (tx-1 and tx-2)
        viewModel.onSelectDateRange(ActivityDateRange.THIS_MONTH)
        advanceUntilIdle()
        val thisMonth = viewModel.uiState.value.filteredTransactions
        assertTrue(thisMonth.any { it.transaction.id == "tx-1" })
        assertTrue(thisMonth.any { it.transaction.id == "tx-2" })
    }

    @Test
    fun clearFiltersResetsAllFilters() = runTest {
        val viewModel = createViewModel()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.uiState.collect {}
        }
        advanceUntilIdle()

        viewModel.onSearchQueryChange("NMDC")
        viewModel.onSelectTypeFilter(TransactionType.SELL)
        viewModel.onSelectAssetClassFilter(AssetClass.STOCK)
        viewModel.onSelectDateRange(ActivityDateRange.THIS_MONTH)
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.isFilterActive)

        viewModel.clearFilters()
        advanceUntilIdle()
        assertFalse(viewModel.uiState.value.isFilterActive)
        assertEquals("", viewModel.uiState.value.searchQuery)
        assertNull(viewModel.uiState.value.selectedTypeFilter)
        assertNull(viewModel.uiState.value.selectedAssetClassFilter)
        assertEquals(ActivityDateRange.ALL, viewModel.uiState.value.selectedDateRange)
        assertEquals(3, viewModel.uiState.value.filteredTransactions.size)
    }

    @Test
    fun transactionClickAndDismissDetails() = runTest {
        val viewModel = createViewModel()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.uiState.collect {}
        }
        advanceUntilIdle()

        val item = viewModel.uiState.value.transactions[0]
        viewModel.onTransactionClick(item)
        advanceUntilIdle()
        assertEquals(item, viewModel.uiState.value.selectedTransactionForDetails)

        viewModel.onDismissDetails()
        advanceUntilIdle()
        assertNull(viewModel.uiState.value.selectedTransactionForDetails)
    }

    @Test
    fun deleteTransactionCallsRepositoryAndRecalculates() = runTest {
        val viewModel = createViewModel()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.uiState.collect {}
        }
        advanceUntilIdle()

        viewModel.onDeleteTransactionRequested(sellNmdc)
        advanceUntilIdle()
        assertEquals(sellNmdc, viewModel.uiState.value.transactionToDelete)

        viewModel.onConfirmDeleteTransaction()
        advanceUntilIdle()

        assertEquals(listOf("tx-2"), deletedTransactionIds)
        assertNull(viewModel.uiState.value.transactionToDelete)
        assertNotNull(viewModel.uiState.value.userMessage)
    }
}
