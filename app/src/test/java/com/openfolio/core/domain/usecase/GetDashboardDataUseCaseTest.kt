package com.openfolio.core.domain.usecase

import com.openfolio.core.data.local.dao.TransactionDao
import com.openfolio.core.data.local.entity.TransactionEntity
import com.openfolio.core.data.local.entity.TransactionStatus
import com.openfolio.core.data.local.entity.TransactionType
import java.math.BigDecimal
import java.time.LocalDate
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GetDashboardDataUseCaseTest {
    @Test
    fun combinesAssetsAndLiabilitiesAndCalculatesNetWorth() = runTest {
        val sampleTxn = TransactionEntity(
            id = "txn-1",
            accountId = "acc-1",
            assetId = "Reliance",
            type = TransactionType.BUY,
            tradeDate = LocalDate.now(),
            grossAmount = BigDecimal("150000.00"),
            netAmount = BigDecimal("150000.00"),
            currency = "INR",
            fingerprint = "fp-1",
            status = TransactionStatus.CONFIRMED,
        )
        val dao = FakeTransactionDao(
            assets = 150000.0,
            liabilities = 50000.0,
            recentTransactions = listOf(sampleTxn),
        )
        val useCase = GetDashboardDataUseCase(dao)

        val result = useCase().first()

        assertEquals(0, BigDecimal("150000.0").compareTo(result.totalAssets))
        assertEquals(0, BigDecimal("50000.0").compareTo(result.totalLiabilities))
        assertEquals(0, BigDecimal("100000.0").compareTo(result.netWorth))
        assertEquals(1, result.recentTransactions.size)
        assertEquals("Reliance", result.recentTransactions.first().assetId)
    }

    @Test
    fun handlesNullAssetsSafely() = runTest {
        val dao = FakeTransactionDao(
            assets = null,
            liabilities = 0.0,
            recentTransactions = emptyList(),
        )
        val useCase = GetDashboardDataUseCase(dao)

        val result = useCase().first()

        assertEquals(0, BigDecimal.ZERO.compareTo(result.totalAssets))
        assertEquals(0, BigDecimal.ZERO.compareTo(result.totalLiabilities))
        assertEquals(0, BigDecimal.ZERO.compareTo(result.netWorth))
        assertEquals(BigDecimal.ZERO, result.totalAssets)
        assertEquals(BigDecimal.ZERO, result.totalLiabilities)
        assertEquals(BigDecimal.ZERO, result.netWorth)
        assertTrue(result.recentTransactions.isEmpty())
    }

    @Test
    fun mapsAssetIdToDisplayNameFromAssetEntity() = runTest {
        val sampleTxn = TransactionEntity(
            id = "txn-1",
            accountId = "acc-1",
            assetId = "hdfc_bank_ltd",
            type = TransactionType.BUY,
            tradeDate = LocalDate.now(),
            grossAmount = BigDecimal("50000.00"),
            netAmount = BigDecimal("50000.00"),
            currency = "INR",
            fingerprint = "fp-1",
            status = TransactionStatus.CONFIRMED,
        )
        val dao = FakeTransactionDao(
            assets = 50000.0,
            liabilities = 0.0,
            recentTransactions = listOf(sampleTxn),
            allTransactions = listOf(sampleTxn),
        )
        val assetDao = FakeAssetDao(
            listOf(
                com.openfolio.core.data.local.entity.AssetEntity(
                    id = "hdfc_bank_ltd",
                    assetClass = com.openfolio.core.data.local.entity.AssetClass.STOCK,
                    displayName = "HDFC Bank Ltd",
                    currency = "INR",
                ),
            ),
        )
        val useCase = GetDashboardDataUseCase(dao, assetDao)

        val result = useCase().first()

        assertEquals("HDFC Bank Ltd", result.recentTransactions.first().assetId)
        assertEquals(1, result.allocations.size)
        assertEquals("Equity", result.allocations.first().label)
        assertEquals(100, result.allocations.first().percentage)
    }

    @Test
    fun formatsAssetIdNicelyWhenAssetEntityNotFound() = runTest {
        val sampleTxn = TransactionEntity(
            id = "txn-1",
            accountId = "acc-1",
            assetId = "tata_consultancy_services",
            type = TransactionType.BUY,
            tradeDate = LocalDate.now(),
            grossAmount = BigDecimal("35000.00"),
            netAmount = BigDecimal("35000.00"),
            currency = "INR",
            fingerprint = "fp-1",
            status = TransactionStatus.CONFIRMED,
        )
        val dao = FakeTransactionDao(
            assets = 35000.0,
            liabilities = 0.0,
            recentTransactions = listOf(sampleTxn),
        )
        val useCase = GetDashboardDataUseCase(dao)

        val result = useCase().first()

        assertEquals("Tata Consultancy Services", result.recentTransactions.first().assetId)
    }

    @Test
    fun returnsTotalTransactionCountFromTransactionDaoFlow() = runTest {
        val dao = FakeTransactionDao(
            assets = 1000.0,
            liabilities = 0.0,
            recentTransactions = emptyList(),
            totalCount = 42,
        )
        val useCase = GetDashboardDataUseCase(dao)

        val result = useCase().first()
        assertEquals(42, result.totalTransactions)
    }

    private class FakeTransactionDao(
        private val assets: Double?,
        private val liabilities: Double?,
        private val recentTransactions: List<TransactionEntity>,
        private val allTransactions: List<TransactionEntity> = emptyList(),
        private val totalCount: Int = allTransactions.size,
    ) : TransactionDao {
        override suspend fun insert(transaction: TransactionEntity) = Unit
        override suspend fun insertAll(transactions: List<TransactionEntity>) = Unit
        override suspend fun update(transaction: TransactionEntity) = Unit
        override suspend fun delete(transaction: TransactionEntity) = Unit
        override suspend fun deleteById(id: String) = Unit
        override suspend fun getByFingerprint(fingerprint: String): TransactionEntity? = null
        override suspend fun containsFingerprint(fingerprint: String): Boolean = false
        override suspend fun getById(id: String): TransactionEntity? = null
        override fun getAll(): Flow<List<TransactionEntity>> = flowOf(allTransactions)
        override suspend fun getAllOnce(): List<TransactionEntity> = allTransactions
        override suspend fun getByAssetIdOnce(assetId: String): List<TransactionEntity> = emptyList()
        override fun getByAssetId(assetId: String): Flow<List<TransactionEntity>> = flowOf(emptyList())
        override fun getByAccountId(accountId: String): Flow<List<TransactionEntity>> = flowOf(emptyList())
        override fun getTotalAssetsFlow(): Flow<Double?> = flowOf(assets)
        override fun getTotalLiabilitiesFlow(): Flow<Double?> = flowOf(liabilities)
        override fun getTotalTransactionCount(): Flow<Int> = flowOf(totalCount)
        override fun getRecentTransactions(limit: Int): Flow<List<TransactionEntity>> = flowOf(recentTransactions)
        override fun getAssetClassAllocations(): Flow<List<com.openfolio.core.data.local.dao.AssetClassAllocationRow>> {
            val list = allTransactions
                .filter { it.type.name in setOf("BUY", "DIVIDEND", "INTEREST") }
                .groupBy { com.openfolio.core.data.local.entity.AssetClass.STOCK }
                .map { (cls, txns) ->
                    com.openfolio.core.data.local.dao.AssetClassAllocationRow(cls, txns.sumOf { it.grossAmount.toDouble() })
                }
            return flowOf(list)
        }
        override fun getDailyNetWorthDeltas(): Flow<List<com.openfolio.core.data.local.dao.DailyNetWorthDelta>> {
            val list = allTransactions
                .groupBy { it.tradeDate.toEpochDay() }
                .map { (date, txns) ->
                    val delta = txns.sumOf { txn ->
                        if (txn.type.name in setOf("BUY", "TRANSFER_IN", "INTEREST", "DIVIDEND")) {
                            txn.grossAmount.toDouble()
                        } else {
                            -txn.grossAmount.toDouble()
                        }
                    }
                    com.openfolio.core.data.local.dao.DailyNetWorthDelta(date, delta)
                }
                .sortedBy { it.tradeDate }
            return flowOf(list)
        }
    }

    @Test
    fun returnsTotalPortfolioValueFromAssetDaoFlow() = runTest {
        val sampleTxn = TransactionEntity(
            id = "txn-1",
            accountId = "acc-1",
            assetId = "reliance",
            type = TransactionType.BUY,
            tradeDate = LocalDate.now(),
            grossAmount = BigDecimal("200000.00"),
            netAmount = BigDecimal("200000.00"),
            currency = "INR",
            fingerprint = "fp-1",
            status = TransactionStatus.CONFIRMED,
        )
        val dao = FakeTransactionDao(
            assets = 0.0,
            liabilities = 0.0,
            recentTransactions = listOf(sampleTxn),
        )
        val assetDao = FakeAssetDao(
            assets = listOf(
                com.openfolio.core.data.local.entity.AssetEntity(
                    id = "reliance",
                    displayName = "Reliance Industries",
                    assetClass = com.openfolio.core.data.local.entity.AssetClass.STOCK,
                    totalQuantity = BigDecimal("10"),
                    totalInvested = BigDecimal("250000.00"),
                ),
            ),
            portfolioValue = BigDecimal("250000.00"),
        )
        val useCase = GetDashboardDataUseCase(dao, assetDao)

        val result = useCase().first()
        assertEquals(0, BigDecimal("250000.00").compareTo(result.totalAssets))
        assertEquals(0, BigDecimal("250000.00").compareTo(result.netWorth))
    }

    private class FakeAssetDao(
        private val assets: List<com.openfolio.core.data.local.entity.AssetEntity> = emptyList(),
        private val portfolioValue: BigDecimal? = null,
    ) : com.openfolio.core.data.local.dao.AssetDao {
        override suspend fun insert(asset: com.openfolio.core.data.local.entity.AssetEntity) = Unit
        override suspend fun insertAll(assets: List<com.openfolio.core.data.local.entity.AssetEntity>) = Unit
        override suspend fun update(asset: com.openfolio.core.data.local.entity.AssetEntity) = Unit
        override suspend fun delete(asset: com.openfolio.core.data.local.entity.AssetEntity) = Unit
        override suspend fun getById(id: String): com.openfolio.core.data.local.entity.AssetEntity? =
            assets.firstOrNull { it.id == id }
        override fun observeById(id: String): Flow<com.openfolio.core.data.local.entity.AssetEntity?> =
            flowOf(assets.firstOrNull { it.id == id })
        override suspend fun deleteById(id: String) = Unit
        override fun getAll(): Flow<List<com.openfolio.core.data.local.entity.AssetEntity>> = flowOf(assets)
        override suspend fun getAllOnce(): List<com.openfolio.core.data.local.entity.AssetEntity> = assets
        override fun getByAssetClass(assetClass: com.openfolio.core.data.local.entity.AssetClass): Flow<List<com.openfolio.core.data.local.entity.AssetEntity>> =
            flowOf(assets.filter { it.assetClass == assetClass })
        override suspend fun updateAggregates(
            assetId: String,
            qty: BigDecimal,
            invested: BigDecimal,
            avgCost: BigDecimal,
            realizedPnL: BigDecimal,
            timestamp: Long,
        ) = Unit
        override suspend fun updateDisplayName(id: String, displayName: String, updatedAt: Long) = Unit
        override fun getTotalPortfolioValueFlow(): Flow<BigDecimal?> = flowOf(
            portfolioValue ?: assets.filter { it.status == com.openfolio.core.data.local.entity.AssetStatus.ACTIVE }
                .map { it.totalInvested }
                .fold(BigDecimal.ZERO) { acc, v -> acc + v }
                .takeIf { it > BigDecimal.ZERO || assets.isNotEmpty() },
        )
    }
}
