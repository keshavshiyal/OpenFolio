package com.openfolio.core.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.openfolio.core.data.local.Transaction

@Dao
interface TransactionDao {
    @Query("SELECT EXISTS(SELECT 1 FROM transactions WHERE fingerprint = :fingerprint)")
    suspend fun containsFingerprint(fingerprint: String): Boolean

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(transaction: Transaction)
}
