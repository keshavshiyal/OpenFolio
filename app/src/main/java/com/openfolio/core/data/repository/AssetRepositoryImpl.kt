package com.openfolio.core.data.repository

import androidx.room.withTransaction
import com.openfolio.core.data.local.OpenFolioDatabase
import com.openfolio.core.data.local.dao.AssetDao
import com.openfolio.core.data.local.dao.LotDao
import com.openfolio.core.data.local.dao.TransactionDao
import com.openfolio.core.data.local.entity.AssetEntity
import com.openfolio.core.data.local.entity.TransactionEntity
import com.openfolio.core.domain.calculator.FifoCostBasisCalculator
import com.openfolio.core.domain.calculator.RecalculationResult
import com.openfolio.core.domain.repository.AssetRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext

class AssetRepositoryImpl(
    private val database: OpenFolioDatabase,
    private val assetDao: AssetDao,
    private val transactionDao: TransactionDao,
    private val lotDao: LotDao,
    private val runTransaction: suspend (suspend () -> Unit) -> Unit = { block -> database.withTransaction { block() } },
) : AssetRepository {

    override fun getAllAssets(): Flow<List<AssetEntity>> =
        assetDao.getAll().flowOn(Dispatchers.IO)

    override fun getAssetById(id: String): Flow<AssetEntity?> =
        assetDao.observeById(id).flowOn(Dispatchers.IO)

    override suspend fun getAssetByIdOnce(id: String): AssetEntity? = withContext(Dispatchers.IO) {
        assetDao.getById(id)
    }

    override fun getTransactionsForAsset(assetId: String): Flow<List<TransactionEntity>> =
        transactionDao.getByAssetId(assetId).flowOn(Dispatchers.IO)

    override suspend fun getTransactionsForAssetOnce(assetId: String): List<TransactionEntity> =
        withContext(Dispatchers.IO) {
            transactionDao.getByAssetIdOnce(assetId)
        }

    override suspend fun saveTransaction(transaction: TransactionEntity): Unit = withContext(Dispatchers.IO) {
        runTransaction {
            transactionDao.insert(transaction)
            recalculateInternal(transaction.assetId)
        }
    }

    override suspend fun updateTransaction(transaction: TransactionEntity): Unit = withContext(Dispatchers.IO) {
        runTransaction {
            val existing = transactionDao.getById(transaction.id)
            val oldAssetId = existing?.assetId
            transactionDao.update(transaction)
            recalculateInternal(transaction.assetId)
            if (oldAssetId != null && oldAssetId != transaction.assetId) {
                recalculateInternal(oldAssetId)
            }
        }
    }

    override suspend fun deleteTransaction(transactionId: String): Unit = withContext(Dispatchers.IO) {
        runTransaction {
            val existing = transactionDao.getById(transactionId) ?: return@runTransaction
            val assetId = existing.assetId
            transactionDao.delete(existing)
            recalculateInternal(assetId)
        }
    }

    override suspend fun recalculateAsset(assetId: String): Unit = withContext(Dispatchers.IO) {
        runTransaction {
            recalculateInternal(assetId)
        }
    }

    override suspend fun deleteAsset(assetId: String): Unit = withContext(Dispatchers.IO) {
        runTransaction {
            lotDao.deleteByAssetId(assetId)
            assetDao.deleteById(assetId)
        }
    }

    private suspend fun recalculateInternal(assetId: String): RecalculationResult {
        val txns = transactionDao.getByAssetIdOnce(assetId)
        val result = FifoCostBasisCalculator.calculate(assetId, txns)

        lotDao.deleteByAssetId(assetId)
        if (result.lots.isNotEmpty()) {
            lotDao.insertAll(result.lots)
        }

        val asset = assetDao.getById(assetId)
        if (asset != null) {
            val updated = asset.copy(
                totalQuantity = result.totalQuantity,
                totalInvested = result.totalInvested,
                averageCost = result.averageCost,
                realizedPnL = result.realizedPnL,
                status = result.status,
                updatedAt = System.currentTimeMillis(),
            )
            assetDao.update(updated)
        }
        return result
    }
}
