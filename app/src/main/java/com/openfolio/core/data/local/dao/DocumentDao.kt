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

    @Query(
        """
        SELECT id, fileName, localPath, fileHash, fileSize, importDate, extractionStatus,
               CASE WHEN length(extractedText) > 4000 THEN substr(extractedText, 1, 500) ELSE extractedText END AS extractedText,
               status, assetClass, extractedTextPath, pageCount, processedPages, carryOverContextJson, extractionError
        FROM documents ORDER BY importDate DESC
        """,
    )
    fun getAllDocuments(): Flow<List<DocumentEntity>>

    @Query(
        """
        SELECT id, fileName, localPath, fileHash, fileSize, importDate, extractionStatus,
               CASE WHEN length(extractedText) > 4000 THEN substr(extractedText, 1, 500) ELSE extractedText END AS extractedText,
               status, assetClass, extractedTextPath, pageCount, processedPages, carryOverContextJson, extractionError
        FROM documents ORDER BY importDate DESC
        """,
    )
    suspend fun getAllDocumentsOnce(): List<DocumentEntity>

    @Query(
        """
        SELECT id, fileName, localPath, fileHash, fileSize, importDate, extractionStatus,
               CASE WHEN length(extractedText) > 4000 THEN substr(extractedText, 1, 500) ELSE extractedText END AS extractedText,
               status, assetClass, extractedTextPath, pageCount, processedPages, carryOverContextJson, extractionError
        FROM documents WHERE fileHash = :hash LIMIT 1
        """,
    )
    suspend fun getDocumentByHash(hash: String): DocumentEntity?

    @Query(
        """
        SELECT id, fileName, localPath, fileHash, fileSize, importDate, extractionStatus,
               CASE WHEN length(extractedText) > 4000 THEN substr(extractedText, 1, 500) ELSE extractedText END AS extractedText,
               status, assetClass, extractedTextPath, pageCount, processedPages, carryOverContextJson, extractionError
        FROM documents WHERE id = :id LIMIT 1
        """,
    )
    suspend fun getDocumentById(id: String): DocumentEntity?

    @Query(
        """
        SELECT id, fileName, localPath, fileHash, fileSize, importDate, extractionStatus,
               CASE WHEN length(extractedText) > 4000 THEN substr(extractedText, 1, 500) ELSE extractedText END AS extractedText,
               status, assetClass, extractedTextPath, pageCount, processedPages, carryOverContextJson, extractionError
        FROM documents WHERE id = :id LIMIT 1
        """,
    )
    fun observeDocumentById(id: String): Flow<DocumentEntity?>

    @Query(
        """
        SELECT id, fileName, localPath, fileHash, fileSize, importDate, extractionStatus,
               CASE WHEN length(extractedText) > 4000 THEN substr(extractedText, 1, 500) ELSE extractedText END AS extractedText,
               status, assetClass, extractedTextPath, pageCount, processedPages, carryOverContextJson, extractionError
        FROM documents WHERE status IN (:statuses)
        """,
    )
    suspend fun getDocumentsByStatuses(statuses: List<String>): List<DocumentEntity>

    @Query("UPDATE documents SET status = :status, extractionStatus = :status WHERE id = :id")
    suspend fun updateStatus(id: String, status: String)

    @Query(
        "UPDATE documents SET extractedText = :text, status = :status, extractionStatus = :status WHERE id = :id",
    )
    suspend fun updateExtractionResult(id: String, text: String?, status: String)

    @Query(
        "UPDATE documents SET processedPages = :processedPages, pageCount = :pageCount, status = :status, extractionStatus = :status WHERE id = :id",
    )
    suspend fun updateExtractionProgress(id: String, processedPages: Int, pageCount: Int, status: String)

    @Query(
        """
        UPDATE documents
        SET extractedTextPath = :extractedTextPath,
            extractedText = :previewText,
            pageCount = :pageCount,
            processedPages = :processedPages,
            status = :status,
            extractionStatus = :status,
            extractionError = :error
        WHERE id = :id
        """,
    )
    suspend fun updateExtractionFileResult(
        id: String,
        extractedTextPath: String?,
        previewText: String?,
        pageCount: Int,
        processedPages: Int,
        status: String,
        error: String?,
    )

    @Query(
        "UPDATE documents SET status = :status, extractionStatus = :status, extractionError = :error WHERE id = :id",
    )
    suspend fun updateStatusAndError(id: String, status: String, error: String?)

    @Query(
        """
        UPDATE documents
        SET processedPages = :processedPages,
            pageCount = :pageCount,
            status = :status,
            extractionStatus = :status,
            carryOverContextJson = :carryOverContextJson,
            extractionError = :error
        WHERE id = :id
        """,
    )
    suspend fun updateAiBatchProgress(
        id: String,
        processedPages: Int,
        pageCount: Int,
        status: String,
        carryOverContextJson: String?,
        error: String?,
    )

    @Query("UPDATE documents SET carryOverContextJson = :carryOverContextJson WHERE id = :id")
    suspend fun updateCarryOverContext(id: String, carryOverContextJson: String?)

    @Query("UPDATE documents SET assetClass = :assetClass WHERE id = :id")
    suspend fun updateAssetClass(id: String, assetClass: String)

    @Query(
        """
        SELECT id FROM documents
        WHERE extractedTextPath IS NULL
          AND extractedText IS NOT NULL
          AND length(extractedText) > :threshold
          AND extractedText != '[PASSWORD_PROTECTED]'
        """,
    )
    suspend fun getOversizedLegacyDocumentIds(threshold: Int = 500): List<String>

    @Query("SELECT COALESCE(length(extractedText), 0) FROM documents WHERE id = :id")
    suspend fun getLegacyTextLength(id: String): Int

    @Query("SELECT substr(extractedText, :offset, :length) FROM documents WHERE id = :id")
    suspend fun getLegacyTextSlice(id: String, offset: Int, length: Int): String?

    @Query("UPDATE documents SET extractedTextPath = :path, extractedText = :preview WHERE id = :id")
    suspend fun markLegacyTextMigrated(id: String, path: String, preview: String)
}
