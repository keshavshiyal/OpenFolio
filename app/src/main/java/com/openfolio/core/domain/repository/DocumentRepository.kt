package com.openfolio.core.domain.repository

import android.content.Context
import android.net.Uri
import com.openfolio.core.data.local.entity.DocumentEntity
import com.openfolio.core.data.local.entity.ColumnMappingEntity
import com.openfolio.core.data.local.entity.DraftTransactionEntity
import kotlinx.coroutines.flow.Flow

interface DocumentRepository {
    fun getAllDocuments(): Flow<List<DocumentEntity>>

    suspend fun getDocumentById(id: String): DocumentEntity?

    fun observeDocumentById(id: String): Flow<DocumentEntity?>

    fun getDraftTransactions(documentId: String): Flow<List<DraftTransactionEntity>>

    suspend fun insertDraftTransactionsIfEmpty(
        documentId: String,
        drafts: List<DraftTransactionEntity>,
    )

    suspend fun insertDraftTransaction(draft: DraftTransactionEntity)

    suspend fun updateDraftTransaction(draft: DraftTransactionEntity)

    suspend fun setDraftAccepted(draftId: String, accepted: Boolean)

    suspend fun acceptHighConfidenceDrafts(documentId: String, minimumConfidence: Int)

    suspend fun saveDraftTransactions(
        documentId: String,
        selectedDraftIds: Set<String>? = null,
    ): SaveDraftTransactionsResult

    suspend fun findDuplicateDrafts(
        documentId: String,
    ): Map<String, DuplicateMatchInfo>

    suspend fun updateDocumentStatus(documentId: String, status: String)

    suspend fun ingestFile(
        uri: Uri,
        context: Context,
        assetClass: com.openfolio.core.data.local.entity.AssetClass,
    ): DocumentEntity = ingestFile(uri, context)

    suspend fun ingestFile(
        uri: Uri,
        context: Context,
    ): DocumentEntity

    suspend fun processDocumentWithAi(documentId: String)

    suspend fun processDocumentWithMockAi(documentId: String)

    suspend fun getStructuredFileHeaders(
        documentId: String,
        headerRowNumber: Int = 1,
    ): List<String>

    suspend fun attachExtractedCsv(
        documentId: String,
        csvFile: java.io.File,
    )

    suspend fun processStructuredDocument(
        documentId: String,
        mapping: ColumnMappingEntity,
    )
}

data class SaveDraftTransactionsResult(
    val savedCount: Int,
    val duplicateCount: Int,
)

data class DuplicateMatchInfo(
    val isDuplicate: Boolean,
    val matchingTransactionId: String? = null,
    val matchingTradeDate: String? = null,
    val reason: String? = null,
)
