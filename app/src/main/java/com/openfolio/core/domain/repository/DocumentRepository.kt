package com.openfolio.core.domain.repository

import android.content.Context
import android.net.Uri
import com.openfolio.core.data.local.entity.DocumentEntity
import kotlinx.coroutines.flow.Flow

interface DocumentRepository {
    fun getAllDocuments(): Flow<List<DocumentEntity>>

    suspend fun ingestFile(uri: Uri, context: Context): DocumentEntity
}
