package com.openfolio.core.data.local.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.openfolio.core.data.local.entity.DocumentEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface DocumentDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(document: DocumentEntity)

    @Update
    suspend fun update(document: DocumentEntity)

    @Delete
    suspend fun delete(document: DocumentEntity)

    @Query("DELETE FROM documents WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("SELECT * FROM documents ORDER BY importDate DESC")
    fun getAllDocuments(): Flow<List<DocumentEntity>>

    @Query("SELECT * FROM documents ORDER BY importDate DESC")
    suspend fun getAllDocumentsOnce(): List<DocumentEntity>

    @Query("SELECT * FROM documents WHERE fileHash = :hash LIMIT 1")
    suspend fun getDocumentByHash(hash: String): DocumentEntity?

    @Query("SELECT * FROM documents WHERE id = :id LIMIT 1")
    suspend fun getDocumentById(id: String): DocumentEntity?

    @Query("SELECT * FROM documents WHERE id = :id LIMIT 1")
    fun observeDocumentById(id: String): Flow<DocumentEntity?>

    @Query("UPDATE documents SET status = :status, extractionStatus = :status WHERE id = :id")
    suspend fun updateStatus(id: String, status: String)

    @Query(
        "UPDATE documents SET extractedText = :text, status = :status, extractionStatus = :status WHERE id = :id",
    )
    suspend fun updateExtractionResult(id: String, text: String?, status: String)
}
