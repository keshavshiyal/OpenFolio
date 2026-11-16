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

    private class FakeTransactionDao(
        private val assets: Double?,
        private val liabilities: Double,
        private val recentTransactions: List<TransactionEntity>,
    ) : TransactionDao {
        override suspend fun insert(transaction: TransactionEntity) = Unit
        override suspend fun insertAll(transactions: List<TransactionEntity>) = Unit
        override suspend fun update(transaction: TransactionEntity) = Unit
        override suspend fun delete(transaction: TransactionEntity) = Unit
        override suspend fun getByFingerprint(fingerprint: String): TransactionEntity? = null
        override suspend fun containsFingerprint(fingerprint: String): Boolean = false
        override suspend fun getById(id: String): TransactionEntity? = null
        override fun getAll(): Flow<List<TransactionEntity>> = flowOf(emptyList())
        override fun getByAssetId(assetId: String): Flow<List<TransactionEntity>> = flowOf(emptyList())
        override fun getByAccountId(accountId: String): Flow<List<TransactionEntity>> = flowOf(emptyList())
        override fun getTotalAssetsFlow(): Flow<Double?> = flowOf(assets)
        override fun getTotalLiabilitiesFlow(): Flow<Double> = flowOf(liabilities)
        override fun getRecentTransactions(limit: Int): Flow<List<TransactionEntity>> = flowOf(recentTransactions)
    }
}
