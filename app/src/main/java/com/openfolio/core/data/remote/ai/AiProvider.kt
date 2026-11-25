package com.openfolio.core.data.remote.ai

import com.openfolio.core.data.local.entity.DraftTransactionEntity

data class AiExtractionBatchResult(
    val drafts: List<DraftTransactionEntity>,
    val wasTruncated: Boolean = false,
)

interface AiProvider {
    suspend fun extractTransactions(
        rawText: String,
        apiKey: String,
        documentId: String = "",
    ): Result<List<DraftTransactionEntity>>
}

interface TruncationAwareAiProvider : AiProvider {
    suspend fun extractTransactionsDetailed(
        rawText: String,
        apiKey: String,
        documentId: String = "",
    ): Result<AiExtractionBatchResult>
}
