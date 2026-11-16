package com.openfolio.core.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.openfolio.core.data.local.entity.DraftTransactionEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface DraftTransactionDao {
    @Update
    suspend fun update(draft: DraftTransactionEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(draft: DraftTransactionEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(drafts: List<DraftTransactionEntity>)

    @Query(
        "SELECT * FROM draft_transactions WHERE documentId = :documentId " +
            "ORDER BY confidence DESC, assetName",
    )
    fun getByDocumentId(documentId: String): Flow<List<DraftTransactionEntity>>

    @Query("SELECT * FROM draft_transactions WHERE documentId = :documentId")
    suspend fun getByDocumentIdOnce(documentId: String): List<DraftTransactionEntity>

    @Query(
        "UPDATE draft_transactions SET isAccepted = 1 " +
            "WHERE documentId = :documentId AND confidence >= :minimumConfidence",
    )
    suspend fun acceptHighConfidence(documentId: String, minimumConfidence: Int)

    @Query("UPDATE draft_transactions SET isAccepted = :accepted WHERE id = :draftId")
    suspend fun setAccepted(draftId: String, accepted: Boolean)

    @Query("UPDATE draft_transactions SET assetClass = :assetClass WHERE id = :draftId")
    suspend fun updateAssetClass(draftId: String, assetClass: String)

    @Query("DELETE FROM draft_transactions WHERE documentId = :documentId")
    suspend fun deleteByDocumentId(documentId: String)
}
