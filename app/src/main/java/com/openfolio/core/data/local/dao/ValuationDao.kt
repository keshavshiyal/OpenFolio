package com.openfolio.core.data.local.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.openfolio.core.data.local.entity.ValuationEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ValuationDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(valuation: ValuationEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(valuations: List<ValuationEntity>)

    @Update
    suspend fun update(valuation: ValuationEntity)

    @Delete
    suspend fun delete(valuation: ValuationEntity)

    @Query("SELECT * FROM valuation WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): ValuationEntity?

    @Query("SELECT * FROM valuation WHERE assetId = :assetId ORDER BY date DESC")
    fun getByAssetId(assetId: String): Flow<List<ValuationEntity>>

    @Query("SELECT * FROM valuation WHERE assetId = :assetId ORDER BY date DESC LIMIT 1")
    suspend fun getLatestByAssetId(assetId: String): ValuationEntity?
}
