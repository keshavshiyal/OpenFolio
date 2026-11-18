package com.openfolio.core.data.local.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.openfolio.core.data.local.entity.LotEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface LotDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(lot: LotEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(lots: List<LotEntity>)

    @Update
    suspend fun update(lot: LotEntity)

    @Delete
    suspend fun delete(lot: LotEntity)

    @Query("SELECT * FROM lot WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): LotEntity?

    @Query("SELECT * FROM lot WHERE assetId = :assetId ORDER BY acquiredDate ASC")
    fun getByAssetId(assetId: String): Flow<List<LotEntity>>

    @Query("SELECT * FROM lot WHERE assetId = :assetId AND remainingQuantity > 0 ORDER BY acquiredDate ASC")
    fun getActiveLotsByAssetId(assetId: String): Flow<List<LotEntity>>

    @Query("DELETE FROM lot WHERE assetId = :assetId")
    suspend fun deleteByAssetId(assetId: String)
}
