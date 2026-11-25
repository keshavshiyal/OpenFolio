package com.openfolio.core.worker

import android.content.Context
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

interface DocumentImportScheduler {
    fun enqueueLocalExtract(documentId: String, fileSize: Long = 0L)

    fun enqueueAiExtract(documentId: String, fileSize: Long = 0L)

    suspend fun isImportWorkActive(documentId: String): Boolean
}

@Singleton
class WorkManagerDocumentImportScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
) : DocumentImportScheduler {

    override fun enqueueLocalExtract(documentId: String, fileSize: Long) {
        enqueue(
            documentId = documentId,
            mode = DocumentImportWorker.MODE_LOCAL_EXTRACT,
            fileSize = fileSize,
        )
    }

    override fun enqueueAiExtract(documentId: String, fileSize: Long) {
        enqueue(
            documentId = documentId,
            mode = DocumentImportWorker.MODE_AI_EXTRACT,
            fileSize = fileSize,
        )
    }

    override suspend fun isImportWorkActive(documentId: String): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val workInfos = WorkManager.getInstance(context)
                .getWorkInfosForUniqueWork(uniqueWorkName(documentId))
                .get()
            workInfos.any { !it.state.isFinished }
        }.getOrDefault(false)
    }

    private fun enqueue(documentId: String, mode: String, fileSize: Long) {
        val request = OneTimeWorkRequestBuilder<DocumentImportWorker>()
            .setInputData(
                workDataOf(
                    DocumentImportWorker.KEY_DOCUMENT_ID to documentId,
                    DocumentImportWorker.KEY_MODE to mode,
                    DocumentImportWorker.KEY_FILE_SIZE to fileSize,
                ),
            )
            .addTag(TAG_IMPORT_WORK)
            .addTag(uniqueWorkName(documentId))
            .build()

        WorkManager.getInstance(context).enqueueUniqueWork(
            uniqueWorkName(documentId),
            ExistingWorkPolicy.KEEP,
            request,
        )
    }

    companion object {
        const val TAG_IMPORT_WORK = "openfolio_import"
        fun uniqueWorkName(documentId: String): String = "openfolio_import_$documentId"
    }
}
