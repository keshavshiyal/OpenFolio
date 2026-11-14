package com.openfolio.core.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface AppMetadataDao {
    @Query("SELECT * FROM app_metadata WHERE `key` = :key")
    fun observe(key: String): Flow<AppMetadataEntity?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(metadata: AppMetadataEntity)
}
