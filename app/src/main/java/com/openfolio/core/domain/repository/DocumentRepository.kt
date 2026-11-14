package com.openfolio.core.domain.repository

import android.content.Context
import android.net.Uri
import com.openfolio.core.data.local.entity.DocumentEntity
import com.openfolio.core.data.local.entity.DraftTransactionEntity
import kotlinx.coroutines.flow.Flow

interface DocumentRepository {
    fun getAllDocuments(): Flow<List<DocumentEntity>>

    suspend fun getDocumentById(id: String): DocumentEntity?

    fun getDraftTransactions(documentId: String): Flow<List<DraftTransactionEntity>>

    suspend fun insertDraftTransactionsIfEmpty(
        documentId: String,
        drafts: List<DraftTransactionEntity>,
    )

    suspend fun setDraftAccepted(draftId: String, accepted: Boolean)

    suspend fun acceptHighConfidenceDrafts(documentId: String, minimumConfidence: Int)

    suspend fun saveAcceptedDraftTransactions(documentId: String): Int

    suspend fun ingestFile(uri: Uri, context: Context): DocumentEntity
}
