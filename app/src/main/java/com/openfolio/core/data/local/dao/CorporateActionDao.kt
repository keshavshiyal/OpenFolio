package com.openfolio.core.data.local.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.openfolio.core.data.local.entity.CorporateActionEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface CorporateActionDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(action: CorporateActionEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(actions: List<CorporateActionEntity>)

    @Update
    suspend fun update(action: CorporateActionEntity)

    @Delete
    suspend fun delete(action: CorporateActionEntity)

    @Query("SELECT * FROM corporate_action WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): CorporateActionEntity?

    @Query("SELECT * FROM corporate_action WHERE assetId = :assetId ORDER BY effectiveDate DESC")
    fun getByAssetId(assetId: String): Flow<List<CorporateActionEntity>>
}
