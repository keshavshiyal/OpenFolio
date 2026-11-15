package com.openfolio.core.data.remote.ai

import com.openfolio.core.data.local.entity.DraftTransactionEntity

interface AiProvider {
    suspend fun extractTransactions(
        rawText: String,
        apiKey: String,
        documentId: String = "",
    ): Result<List<DraftTransactionEntity>>
}
