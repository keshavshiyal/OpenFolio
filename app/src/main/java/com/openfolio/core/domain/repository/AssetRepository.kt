package com.openfolio.core.domain.repository

import com.openfolio.core.data.local.entity.AssetClass
import com.openfolio.core.data.local.entity.AssetEntity
import com.openfolio.core.data.local.entity.TransactionEntity
import com.openfolio.core.data.local.entity.TransactionType
import java.math.BigDecimal
import java.time.LocalDate
import kotlinx.coroutines.flow.Flow

interface AssetRepository {
    fun getAllAssets(): Flow<List<AssetEntity>>
    fun getAssetById(id: String): Flow<AssetEntity?>
    suspend fun getAssetByIdOnce(id: String): AssetEntity?
    fun getTransactionsForAsset(assetId: String): Flow<List<TransactionEntity>>
    suspend fun getTransactionsForAssetOnce(assetId: String): List<TransactionEntity>
    suspend fun saveTransaction(transaction: TransactionEntity)
    suspend fun updateTransaction(transaction: TransactionEntity)
    suspend fun deleteTransaction(transactionId: String)
    suspend fun recalculateAsset(assetId: String)
    suspend fun deleteAsset(assetId: String)
    suspend fun saveAsset(asset: AssetEntity) {}
    suspend fun updateAsset(asset: AssetEntity) {}
    suspend fun recordManualTransaction(
        assetName: String,
        assetClass: AssetClass,
        type: TransactionType,
        tradeDate: LocalDate,
        quantity: BigDecimal,
        unitPrice: BigDecimal,
        netAmount: BigDecimal,
        fees: BigDecimal = BigDecimal.ZERO,
        taxes: BigDecimal = BigDecimal.ZERO,
        notes: String? = null,
    ): TransactionEntity {
        throw UnsupportedOperationException("Manual transaction not implemented in this repository.")
    }
}
