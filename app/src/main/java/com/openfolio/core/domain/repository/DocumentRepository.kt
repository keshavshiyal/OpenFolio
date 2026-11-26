package com.openfolio.core.domain.repository

import android.content.Context
import android.net.Uri
import com.openfolio.core.data.local.entity.AssetClass
import com.openfolio.core.data.local.entity.DocumentEntity
import com.openfolio.core.data.local.entity.ColumnMappingEntity
import com.openfolio.core.data.local.entity.DraftTransactionEntity
import com.openfolio.core.data.remote.ai.AiCarryOverContext
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

    suspend fun processDocumentWithAi(
        documentId: String,
        onProgress: (suspend (stage: String, current: Int, total: Int) -> Unit)?,
    ) {
        processDocumentWithAi(documentId)
    }

    suspend fun processNextAiBatch(
        documentId: String,
        onProgress: (suspend (stage: String, current: Int, total: Int) -> Unit)? = null,
    ): AiBatchProcessResult {
        processDocumentWithAi(documentId, onProgress)
        return AiBatchProcessResult(
            startPage = 1,
            endPage = 1,
            totalPages = 1,
            newDraftsCount = 0,
            flaggedDraftCount = 0,
            carryOverContext = AiCarryOverContext(),
            stopReason = AiBatchStopReason.COMPLETED_ALL_PAGES,
        )
    }

    suspend fun autoProcessRemainingAiBatches(
        documentId: String,
        onProgress: (suspend (stage: String, current: Int, total: Int) -> Unit)? = null,
    ): AiBatchProcessResult {
        processDocumentWithAi(documentId, onProgress)
        return AiBatchProcessResult(
            startPage = 1,
            endPage = 1,
            totalPages = 1,
            newDraftsCount = 0,
            flaggedDraftCount = 0,
            carryOverContext = AiCarryOverContext(),
            stopReason = AiBatchStopReason.COMPLETED_ALL_PAGES,
        )
    }

    suspend fun updateCarryOverContext(
        documentId: String,
        context: AiCarryOverContext,
    ) {}

    suspend fun updateDocumentAssetClass(
        documentId: String,
        assetClass: AssetClass,
    ) {}

    suspend fun rollbackDocumentImport(
        documentId: String,
    ): RollbackImportResult = RollbackImportResult(0, 0)

    suspend fun processDocumentWithMockAi(documentId: String)

    suspend fun runLocalExtraction(
        documentId: String,
        password: String? = null,
        onProgress: (suspend (stage: String, current: Int, total: Int) -> Unit)? = null,
    ): Result<Unit> = Result.success(Unit)

    suspend fun unlockAndProcessPdf(
        documentId: String,
        password: String,
    ): Result<Unit>

    suspend fun retryLocalExtraction(documentId: String) {}

    suspend fun enqueueAiExtraction(documentId: String) {}

    suspend fun recoverInterruptedImports() {}

    suspend fun deleteDocument(documentId: String) {}

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

enum class AiBatchStopReason {
    COMPLETED_ALL_PAGES,
    PAUSED_AFTER_BATCH,
    AUTO_PAUSED_NEEDS_REVIEW,
}

data class AiBatchProcessResult(
    val startPage: Int,
    val endPage: Int,
    val totalPages: Int,
    val newDraftsCount: Int,
    val flaggedDraftCount: Int,
    val carryOverContext: AiCarryOverContext,
    val stopReason: AiBatchStopReason,
    val message: String? = null,
)

data class RollbackImportResult(
    val deletedTransactionCount: Int,
    val recalculatedAssetCount: Int,
)
