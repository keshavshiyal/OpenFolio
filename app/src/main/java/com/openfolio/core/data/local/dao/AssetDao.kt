package com.openfolio.core.data.local.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.openfolio.core.data.local.entity.AssetClass
import com.openfolio.core.data.local.entity.AssetEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface AssetDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(asset: AssetEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(assets: List<AssetEntity>)

    @Update
    suspend fun update(asset: AssetEntity)

    @Delete
    suspend fun delete(asset: AssetEntity)

    @Query("SELECT * FROM asset WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): AssetEntity?

    @Query("SELECT * FROM asset ORDER BY displayName ASC")
    fun getAll(): Flow<List<AssetEntity>>

    @Query("SELECT * FROM asset WHERE assetClass = :assetClass ORDER BY displayName ASC")
    fun getByAssetClass(assetClass: AssetClass): Flow<List<AssetEntity>>
}
