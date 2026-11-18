package com.openfolio.core.data.repository

import androidx.room.withTransaction
import com.openfolio.core.data.local.OpenFolioDatabase
import com.openfolio.core.data.local.dao.AssetDao
import com.openfolio.core.data.local.dao.LotDao
import com.openfolio.core.data.local.dao.TransactionDao
import com.openfolio.core.data.local.entity.AssetClass
import com.openfolio.core.data.local.entity.AssetEntity
import com.openfolio.core.data.local.entity.AssetStatus
import com.openfolio.core.data.local.entity.LotEntity
import com.openfolio.core.data.local.entity.TransactionEntity
import com.openfolio.core.data.local.entity.TransactionStatus
import com.openfolio.core.data.local.entity.TransactionType
import com.openfolio.core.domain.repository.DuplicateTransactionException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.math.BigDecimal
import java.time.LocalDate

class AssetRepositoryImplTest {

    private val database = mock<OpenFolioDatabase>()
    private val assetDao = mock<AssetDao>()
    private val transactionDao = mock<TransactionDao>()
    private val lotDao = mock<LotDao>()
    private val accountDao = mock<com.openfolio.core.data.local.dao.AccountDao>()

    private lateinit var repository: AssetRepositoryImpl

    private val sampleAsset = AssetEntity(
        id = "asset_rel",
        displayName = "Reliance Industries",
        assetClass = AssetClass.STOCK,
        totalQuantity = BigDecimal("100"),
        totalInvested = BigDecimal("250000.00"),
        averageCost = BigDecimal("2500.00"),
        realizedPnL = BigDecimal.ZERO,
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

    @Before
    fun setUp() = runTest {
        whenever(database.accountDao()).thenReturn(accountDao)
        whenever(assetDao.getById("asset_rel")).thenReturn(sampleAsset)
        whenever(transactionDao.getByAssetIdOnce("asset_rel")).thenReturn(listOf(sampleTxn))
        whenever(transactionDao.getById("txn_1")).thenReturn(sampleTxn)
        whenever(transactionDao.containsFingerprint(any())).thenReturn(false)

        repository = AssetRepositoryImpl(
            database = database,
            assetDao = assetDao,
            transactionDao = transactionDao,
            lotDao = lotDao,
            runTransaction = { block -> block() },
        )
    }

    @Test
    fun updateTransactionRecalculatesAssetTotalsAndSynchronizesLots() = runTest {
        val updatedTxn = sampleTxn.copy(
            quantity = BigDecimal("150"),
            unitPrice = BigDecimal("2000"),
            grossAmount = BigDecimal("300000"),
            netAmount = BigDecimal("300000"),
        )
        whenever(transactionDao.getByAssetIdOnce("asset_rel")).thenReturn(listOf(updatedTxn))

        repository.updateTransaction(updatedTxn)

        verify(transactionDao).update(updatedTxn)
        verify(lotDao).deleteByAssetId("asset_rel")

        val lotCaptor = argumentCaptor<List<LotEntity>>()
        verify(lotDao).insertAll(lotCaptor.capture())
        assertEquals(1, lotCaptor.firstValue.size)
        assertEquals(0, BigDecimal("150").compareTo(lotCaptor.firstValue[0].remainingQuantity))

        val assetCaptor = argumentCaptor<AssetEntity>()
        verify(assetDao).update(assetCaptor.capture())
        val savedAsset = assetCaptor.firstValue
        assertEquals(0, BigDecimal("150").compareTo(savedAsset.totalQuantity))
        assertEquals(0, BigDecimal("300000.00").compareTo(savedAsset.totalInvested))
        assertEquals(0, BigDecimal("2000.00").compareTo(savedAsset.averageCost))
    }

    @Test
    fun deleteTransactionRecalculatesRemainingTransactionsAndSynchronizesDatabase() = runTest {
        // When txn_1 is deleted, no transactions remain
        whenever(transactionDao.getByAssetIdOnce("asset_rel")).thenReturn(emptyList())

        repository.deleteTransaction("txn_1")

        verify(transactionDao).delete(sampleTxn)
        verify(lotDao).deleteByAssetId("asset_rel")
        verify(lotDao, never()).insertAll(any())

        val assetCaptor = argumentCaptor<AssetEntity>()
        verify(assetDao).update(assetCaptor.capture())
        val savedAsset = assetCaptor.firstValue
        assertEquals(0, BigDecimal.ZERO.compareTo(savedAsset.totalQuantity))
        assertEquals(0, BigDecimal.ZERO.compareTo(savedAsset.totalInvested))
        assertEquals(0, BigDecimal.ZERO.compareTo(savedAsset.averageCost))
        assertEquals(AssetStatus.CLOSED, savedAsset.status)
    }

    @Test
    fun updateAssetDisplayNameCallsDao() = runTest {
        repository.updateAssetDisplayName("asset_rel", "Reliance Industries Limited")
        verify(assetDao).updateDisplayName(
            eq("asset_rel"),
            eq("Reliance Industries Limited"),
            any(),
        )
    }

    @Test
    fun recordManualTransactionThrowsDuplicateTransactionExceptionWhenFingerprintMatches() = runTest {
        whenever(assetDao.getAllOnce()).thenReturn(listOf(sampleAsset))
        whenever(transactionDao.containsFingerprint(any())).thenReturn(true)

        assertThrows(DuplicateTransactionException::class.java) {
            kotlinx.coroutines.runBlocking {
                repository.recordManualTransaction(
                    assetName = "Reliance Industries",
                    assetClass = AssetClass.STOCK,
                    type = TransactionType.BUY,
                    tradeDate = LocalDate.of(2024, 1, 1),
                    quantity = BigDecimal("100"),
                    unitPrice = BigDecimal("2500"),
                    netAmount = BigDecimal("250000"),
                    broker = "Zerodha",
                )
            }
        }
    }

    @Test
    fun recordManualTransactionSucceedsAndStoresBrokerWhenNotDuplicate() = runTest {
        whenever(assetDao.getAllOnce()).thenReturn(listOf(sampleAsset))
        whenever(transactionDao.containsFingerprint(any())).thenReturn(false)
        whenever(assetDao.getById("asset_rel")).thenReturn(sampleAsset)
        whenever(transactionDao.getByAssetIdOnce("asset_rel")).thenReturn(listOf(sampleTxn))

        val createdTxn = repository.recordManualTransaction(
            assetName = "Reliance Industries",
            assetClass = AssetClass.STOCK,
            type = TransactionType.BUY,
            tradeDate = LocalDate.of(2024, 2, 1),
            quantity = BigDecimal("50"),
            unitPrice = BigDecimal("2600"),
            netAmount = BigDecimal("130000"),
            broker = "Groww",
            tickerOrIsin = "INE002A01018",
        )

        assertNotNull(createdTxn)
        assertEquals("Groww", createdTxn.broker)
        assertEquals("asset_rel", createdTxn.assetId)
        verify(transactionDao).insert(createdTxn)
    }
}
