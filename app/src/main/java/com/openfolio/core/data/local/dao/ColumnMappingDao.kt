package com.openfolio.core.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.openfolio.core.data.local.entity.ColumnMappingEntity

@Dao
interface ColumnMappingDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(mapping: ColumnMappingEntity)

    @Query("SELECT * FROM column_mappings ORDER BY brokerName")
    suspend fun getAll(): List<ColumnMappingEntity>
}
