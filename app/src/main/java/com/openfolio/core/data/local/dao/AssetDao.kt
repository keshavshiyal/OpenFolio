package com.openfolio.core.data.local.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.openfolio.core.data.local.entity.AssetClass
import com.openfolio.core.data.local.entity.AssetEntity
import java.math.BigDecimal
import kotlinx.coroutines.flow.Flow

@Dao
interface AssetDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(asset: AssetEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(assets: List<AssetEntity>)

    @Update
    suspend fun update(asset: AssetEntity)

    @Delete
    suspend fun delete(asset: AssetEntity)

    @Query("SELECT * FROM asset WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): AssetEntity?

    @Query("SELECT * FROM asset WHERE id = :id LIMIT 1")
    fun observeById(id: String): Flow<AssetEntity?>

    @Query("DELETE FROM asset WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("SELECT * FROM asset ORDER BY displayName ASC")
    fun getAll(): Flow<List<AssetEntity>>

    @Query("SELECT * FROM asset")
    suspend fun getAllOnce(): List<AssetEntity>

    @Query("SELECT * FROM asset WHERE assetClass = :assetClass ORDER BY displayName ASC")
    fun getByAssetClass(assetClass: AssetClass): Flow<List<AssetEntity>>

    @Query("UPDATE asset SET totalQuantity = :qty, totalInvested = :invested, averageCost = :avgCost, realizedPnL = :realizedPnL, updatedAt = :timestamp WHERE id = :assetId")
    suspend fun updateAggregates(
        assetId: String,
        qty: BigDecimal,
        invested: BigDecimal,
        avgCost: BigDecimal,
        realizedPnL: BigDecimal,
        timestamp: Long,
    )

    @Query("UPDATE asset SET displayName = :displayName, updatedAt = :updatedAt WHERE id = :id")
    suspend fun updateDisplayName(id: String, displayName: String, updatedAt: Long = System.currentTimeMillis())

    @Query("SELECT SUM(totalInvested) FROM asset WHERE status = 'ACTIVE'")
    fun getTotalPortfolioValueFlow(): Flow<BigDecimal?>
}
