package com.openfolio.feature.assets

import com.openfolio.core.data.local.entity.AssetClass
import com.openfolio.core.data.local.entity.AssetEntity
import com.openfolio.core.data.local.entity.AssetStatus
import com.openfolio.core.data.local.entity.TransactionEntity
import com.openfolio.core.data.local.entity.TransactionStatus
import com.openfolio.core.data.local.entity.TransactionType
import com.openfolio.core.domain.calculator.RecalculationResult
import com.openfolio.core.domain.repository.AssetRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.math.BigDecimal
import java.time.LocalDate

@OptIn(ExperimentalCoroutinesApi::class)
class AssetDetailViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    private val assetFlow = MutableStateFlow<AssetEntity?>(null)
    private val transactionsFlow = MutableStateFlow<List<TransactionEntity>>(emptyList())

    private val sampleAsset = AssetEntity(
        id = "asset_rel",
        displayName = "Reliance Industries",
        assetClass = AssetClass.STOCK,
        totalQuantity = BigDecimal("100"),
        totalInvested = BigDecimal("250000.00"),
        averageCost = BigDecimal("2500.00"),
        realizedPnL = BigDecimal("15000.00"),
        status = AssetStatus.ACTIVE,
    )

    private val sampleTxn = TransactionEntity(
        id = "txn_1",
        accountId = "acc_default",
        assetId = "asset_rel",
        type = TransactionType.BUY,
        tradeDate = LocalDate.of(2024, 1, 1),
        quantity = BigDecimal("100"),
        unitPrice = BigDecimal("2500"),
        grossAmount = BigDecimal("250000"),
        netAmount = BigDecimal("250000"),
        currency = "INR",
        fingerprint = "fp_1",
        status = TransactionStatus.CONFIRMED,
    )

    private var updatedTxn: TransactionEntity? = null
    private var savedTxn: TransactionEntity? = null
    private var updatedAsset: AssetEntity? = null
    private var deletedTxnId: String? = null
    private var deletedAssetId: String? = null
    private var updatedDisplayNameId: String? = null
    private var updatedDisplayNameValue: String? = null
    private var shouldFailUpdate = false

    private val fakeRepository = object : AssetRepository {
        override fun getAllAssets(): Flow<List<AssetEntity>> = MutableStateFlow(listOf(sampleAsset))
        override fun getAssetById(id: String): Flow<AssetEntity?> = assetFlow
        override suspend fun getAssetByIdOnce(id: String): AssetEntity? = assetFlow.value
        override fun getTransactionsForAsset(assetId: String): Flow<List<TransactionEntity>> = transactionsFlow
        override suspend fun getTransactionsForAssetOnce(assetId: String): List<TransactionEntity> = transactionsFlow.value
        override suspend fun saveTransaction(transaction: TransactionEntity) {
            savedTxn = transaction
        }
        override suspend fun updateTransaction(transaction: TransactionEntity) {
            if (shouldFailUpdate) throw RuntimeException("Database error updating transaction")
            updatedTxn = transaction
        }
        override suspend fun deleteTransaction(transactionId: String) {
            deletedTxnId = transactionId
        }
        override suspend fun recalculateAsset(assetId: String) {}
        override suspend fun deleteAsset(assetId: String) {
            deletedAssetId = assetId
        }
        override suspend fun updateAsset(asset: AssetEntity) {
            updatedAsset = asset
        }
        override suspend fun updateAssetDisplayName(assetId: String, newDisplayName: String) {
            if (shouldFailUpdate) throw RuntimeException("Database error updating display name")
            updatedDisplayNameId = assetId
            updatedDisplayNameValue = newDisplayName
            assetFlow.value = assetFlow.value?.copy(displayName = newDisplayName)
        }
    }

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        assetFlow.value = sampleAsset
        transactionsFlow.value = listOf(sampleTxn)
        updatedTxn = null
        deletedTxnId = null
        deletedAssetId = null
        updatedDisplayNameId = null
        updatedDisplayNameValue = null
        shouldFailUpdate = false
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun TestScope.createViewModel(): AssetDetailViewModel {
        val viewModel = AssetDetailViewModel(assetRepository = fakeRepository)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.uiState.collect()
        }
        return viewModel
    }

    @Test
    fun loadAssetEmitsAssetAndTransactions() = runTest {
        val viewModel = createViewModel()
        viewModel.loadAsset("asset_rel")
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertFalse(state.isLoading)
        assertNotNull(state.asset)
        assertEquals("Reliance Industries", state.asset?.displayName)
        assertEquals(1, state.transactions.size)
        assertEquals("txn_1", state.transactions[0].id)
        assertTrue(state.showValues)
    }

    @Test
    fun editTransactionFlowUpdatesTransactionViaRepository() = runTest {
        val viewModel = createViewModel()
        viewModel.loadAsset("asset_rel")
        advanceUntilIdle()

        // Start editing
        viewModel.startEditTransaction(sampleTxn)
        advanceUntilIdle()
        assertEquals(sampleTxn, viewModel.uiState.value.editingTransaction)

        // Save edit
        val edited = sampleTxn.copy(quantity = BigDecimal("150"), netAmount = BigDecimal("375000"))
        viewModel.saveEditedTransaction(edited)
        advanceUntilIdle()

        assertEquals(edited, updatedTxn)
        assertNull(viewModel.uiState.value.editingTransaction)
    }

    @Test
    fun deleteTransactionFlowDeletesViaRepository() = runTest {
        val viewModel = createViewModel()
        viewModel.loadAsset("asset_rel")
        advanceUntilIdle()

        // Start delete
        viewModel.startDeleteTransaction(sampleTxn)
        advanceUntilIdle()
        assertEquals(sampleTxn, viewModel.uiState.value.deletingTransaction)

        // Confirm delete
        viewModel.confirmDeleteTransaction()
        advanceUntilIdle()

        assertEquals("txn_1", deletedTxnId)
        assertNull(viewModel.uiState.value.deletingTransaction)
    }

    @Test
    fun deleteAssetInvokesRepositoryAndSetsIsDeletedTrue() = runTest {
        val viewModel = createViewModel()
        viewModel.loadAsset("asset_rel")
        advanceUntilIdle()

        viewModel.deleteAsset()
        advanceUntilIdle()

        assertEquals("asset_rel", deletedAssetId)
        assertTrue(viewModel.uiState.value.isDeleted)
    }

    @Test
    fun visibilityToggleFlipsShowValues() = runTest {
        val viewModel = createViewModel()
        viewModel.loadAsset("asset_rel")
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
    fun handlesRepositoryErrorsGracefully() = runTest {
        shouldFailUpdate = true
        val viewModel = createViewModel()
        viewModel.loadAsset("asset_rel")
        advanceUntilIdle()

        viewModel.saveEditedTransaction(sampleTxn)
        advanceUntilIdle()

        assertEquals("Database error updating transaction", viewModel.uiState.value.errorMessage)
        viewModel.clearErrorMessage()
        advanceUntilIdle()
        assertNull(viewModel.uiState.value.errorMessage)
    }

    @Test
    fun recordSellRejectsInvalidQuantities() = runTest {
        val viewModel = createViewModel()
        viewModel.loadAsset("asset_rel")
        advanceUntilIdle()

        // Zero quantity
        viewModel.recordSell(
            quantity = BigDecimal.ZERO,
            unitPrice = BigDecimal("2500"),
            netAmount = BigDecimal.ZERO,
            tradeDate = LocalDate.now(),
        )
        advanceUntilIdle()
        assertEquals("Sell quantity must be greater than zero.", viewModel.uiState.value.errorMessage)

        // Exceeds available quantity (sampleAsset has 100)
        viewModel.recordSell(
            quantity = BigDecimal("150"),
            unitPrice = BigDecimal("2500"),
            netAmount = BigDecimal("375000"),
            tradeDate = LocalDate.now(),
        )
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.errorMessage?.contains("Cannot sell more than available") == true)
    }

    @Test
    fun recordSellSavesSellTransaction() = runTest {
        val viewModel = createViewModel()
        viewModel.loadAsset("asset_rel")
        advanceUntilIdle()

        viewModel.recordSell(
            quantity = BigDecimal("40"),
            unitPrice = BigDecimal("2800"),
            netAmount = BigDecimal("112000"),
            tradeDate = LocalDate.of(2024, 6, 1),
            fees = BigDecimal("50"),
            taxes = BigDecimal("25"),
        )
        advanceUntilIdle()

        assertNotNull(savedTxn)
        assertEquals(TransactionType.SELL, savedTxn?.type)
        assertEquals(BigDecimal("40"), savedTxn?.quantity)
        assertEquals(BigDecimal("2800"), savedTxn?.unitPrice)
        assertEquals(BigDecimal("112000"), savedTxn?.netAmount)
        assertEquals(BigDecimal("50"), savedTxn?.fees)
        assertEquals(BigDecimal("25"), savedTxn?.taxes)
    }

    @Test
    fun updateAssetClassInvokesRepositoryUpdateAsset() = runTest {
        val viewModel = createViewModel()
        viewModel.loadAsset("asset_rel")
        advanceUntilIdle()

        viewModel.updateAssetClass(AssetClass.MUTUAL_FUND)
        advanceUntilIdle()

        assertNotNull(updatedAsset)
        assertEquals(AssetClass.MUTUAL_FUND, updatedAsset?.assetClass)
    }

    @Test
    fun updateDisplayNameSuccessfullyUpdatesRepositoryAndState() = runTest {
        val viewModel = createViewModel()
        viewModel.loadAsset("asset_rel")
        advanceUntilIdle()

        viewModel.updateDisplayName("Reliance Industries Limited (RIL)")
        advanceUntilIdle()

        assertEquals("asset_rel", updatedDisplayNameId)
        assertEquals("Reliance Industries Limited (RIL)", updatedDisplayNameValue)
        assertEquals("Reliance Industries Limited (RIL)", viewModel.uiState.value.asset?.displayName)
        assertNull(viewModel.uiState.value.errorMessage)
    }

    @Test
    fun updateDisplayNameWithBlankNameShowsErrorWithoutCallingRepository() = runTest {
        val viewModel = createViewModel()
        viewModel.loadAsset("asset_rel")
        advanceUntilIdle()

        viewModel.updateDisplayName("   ")
        advanceUntilIdle()

        assertNull(updatedDisplayNameId)
        assertNotNull(viewModel.uiState.value.errorMessage)
        assertEquals("Asset name cannot be blank.", viewModel.uiState.value.errorMessage)
    }
}
