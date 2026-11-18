package com.openfolio.feature.assets

import com.openfolio.core.data.local.entity.AssetClass
import com.openfolio.core.data.local.entity.AssetEntity
import com.openfolio.core.data.local.entity.AssetStatus
import com.openfolio.core.data.local.entity.TransactionEntity
import com.openfolio.core.domain.calculator.RecalculationResult
import com.openfolio.core.domain.repository.AssetRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.math.BigDecimal

@OptIn(ExperimentalCoroutinesApi::class)
class AssetsViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    private val assetsFlow = MutableStateFlow<List<AssetEntity>>(emptyList())

    private val stock1 = AssetEntity(
        id = "stock_rel",
        displayName = "Reliance Industries",
        assetClass = AssetClass.STOCK,
        totalQuantity = BigDecimal("100"),
        totalInvested = BigDecimal("250000.00"),
        averageCost = BigDecimal("2500.00"),
        realizedPnL = BigDecimal("15000.00"),
        status = AssetStatus.ACTIVE,
    )

    private val stock2 = AssetEntity(
        id = "stock_tcs",
        displayName = "Tata Consultancy Services",
        assetClass = AssetClass.STOCK,
        totalQuantity = BigDecimal("50"),
        totalInvested = BigDecimal("190000.00"),
        averageCost = BigDecimal("3800.00"),
        realizedPnL = BigDecimal("5000.00"),
        status = AssetStatus.ACTIVE,
    )

    private val mf1 = AssetEntity(
        id = "mf_ppfas",
        displayName = "Parag Parikh Flexi Cap Fund",
        assetClass = AssetClass.MUTUAL_FUND,
        totalQuantity = BigDecimal("1500.50"),
        totalInvested = BigDecimal("100000.00"),
        averageCost = BigDecimal("66.64"),
        realizedPnL = BigDecimal.ZERO,
        status = AssetStatus.ACTIVE,
    )

    private val bond1 = AssetEntity(
        id = "bond_gsec",
        displayName = "GOI 7.18% 2033",
        assetClass = AssetClass.BOND,
        totalQuantity = BigDecimal("100"),
        totalInvested = BigDecimal("100000.00"),
        averageCost = BigDecimal("1000.00"),
        realizedPnL = BigDecimal.ZERO,
        status = AssetStatus.ACTIVE,
    )

    private val fakeRepository = object : AssetRepository {
        override fun getAllAssets(): Flow<List<AssetEntity>> = assetsFlow
        override fun getAssetById(id: String): Flow<AssetEntity?> = flowOf(null)
        override suspend fun getAssetByIdOnce(id: String): AssetEntity? = null
        override fun getTransactionsForAsset(assetId: String): Flow<List<TransactionEntity>> = flowOf(emptyList())
        override suspend fun getTransactionsForAssetOnce(assetId: String): List<TransactionEntity> = emptyList()
        override suspend fun saveTransaction(transaction: TransactionEntity) {}
        override suspend fun updateTransaction(transaction: TransactionEntity) {}
        override suspend fun deleteTransaction(transactionId: String) {}
        override suspend fun recalculateAsset(assetId: String) {}
        override suspend fun deleteAsset(assetId: String) {}
    }

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun TestScope.createViewModel(): AssetsViewModel {
        val viewModel = AssetsViewModel(assetRepository = fakeRepository)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.uiState.collect()
        }
        return viewModel
    }

    @Test
    fun initialStateLoadsAllAssetsGroupedByAssetClass() = runTest {
        assetsFlow.value = listOf(stock1, stock2, mf1, bond1)
        val viewModel = createViewModel()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertFalse(state.isLoading)
        assertFalse(state.isEmpty)
        assertEquals(4, state.totalHoldingsCount)
        assertEquals(0, BigDecimal("640000.00").compareTo(state.totalPortfolioInvested))
        assertEquals(0, BigDecimal("20000.00").compareTo(state.totalPortfolioRealizedPnL))

        // Check visual grouping by asset class
        val classes = state.groupedAssets.map { it.assetClass }
        assertTrue(classes.contains(AssetClass.STOCK))
        assertTrue(classes.contains(AssetClass.MUTUAL_FUND))
        assertTrue(classes.contains(AssetClass.BOND))

        val stockGroup = state.groupedAssets.first { it.assetClass == AssetClass.STOCK }
        assertEquals(2, stockGroup.assets.size)
        assertEquals(0, BigDecimal("440000.00").compareTo(stockGroup.totalInvested))
    }

    @Test
    fun searchQueryFiltersAssetsCaseInsensitively() = runTest {
        assetsFlow.value = listOf(stock1, stock2, mf1, bond1)
        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.onSearchQueryChange("reliance")
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(1, state.groupedAssets.size)
        assertEquals("Reliance Industries", state.groupedAssets[0].assets[0].displayName)
    }

    @Test
    fun assetClassFilterSelectsSpecificClass() = runTest {
        assetsFlow.value = listOf(stock1, stock2, mf1, bond1)
        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.onSelectAssetClass(AssetClass.MUTUAL_FUND)
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(1, state.groupedAssets.size)
        assertEquals(AssetClass.MUTUAL_FUND, state.groupedAssets[0].assetClass)
        assertEquals("Parag Parikh Flexi Cap Fund", state.groupedAssets[0].assets[0].displayName)
    }

    @Test
    fun sortOptionSortsCorrectly() = runTest {
        assetsFlow.value = listOf(stock1, stock2)
        val viewModel = createViewModel()
        advanceUntilIdle()

        // Default Name Asc: Reliance, then Tata
        var stockGroup = viewModel.uiState.value.groupedAssets.first { it.assetClass == AssetClass.STOCK }
        assertEquals("Reliance Industries", stockGroup.assets[0].displayName)
        assertEquals("Tata Consultancy Services", stockGroup.assets[1].displayName)

        // Sort Name Desc
        viewModel.onSelectSortOption(AssetSortOption.NAME_DESC)
        advanceUntilIdle()
        stockGroup = viewModel.uiState.value.groupedAssets.first { it.assetClass == AssetClass.STOCK }
        assertEquals("Tata Consultancy Services", stockGroup.assets[0].displayName)
        assertEquals("Reliance Industries", stockGroup.assets[1].displayName)
    }

    @Test
    fun visibilityToggleFlipsShowValues() = runTest {
        assetsFlow.value = listOf(stock1)
        val viewModel = createViewModel()
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.showValues)

        viewModel.toggleValuesVisibility()
        advanceUntilIdle()
        assertFalse(viewModel.uiState.value.showValues)

        viewModel.toggleValuesVisibility()
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.showValues)
    }

    @Test
    fun emptyPortfolioSetsIsEmptyTrue() = runTest {
        assetsFlow.value = emptyList()
        val viewModel = createViewModel()
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.isEmpty)
        assertTrue(viewModel.uiState.value.groupedAssets.isEmpty())
        assertEquals(0, viewModel.uiState.value.totalHoldingsCount)
    }
}
