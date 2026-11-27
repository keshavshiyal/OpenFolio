package com.openfolio.core.worker

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.openfolio.core.data.local.PdfPasswordStore
import com.openfolio.core.domain.repository.DocumentRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CancellationException

@HiltWorker
class DocumentImportWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val documentRepository: DocumentRepository,
    private val pdfPasswordStore: PdfPasswordStore,
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        val documentId = inputData.getString(KEY_DOCUMENT_ID)?.takeIf(String::isNotBlank)
            ?: return Result.failure()
        val mode = inputData.getString(KEY_MODE) ?: MODE_LOCAL_EXTRACT
        val fileSize = inputData.getLong(KEY_FILE_SIZE, 0L)

        val shouldPromoteForeground = fileSize > FOREGROUND_SIZE_THRESHOLD_BYTES || mode == MODE_AI_EXTRACT
        if (shouldPromoteForeground) {
            trySetForeground(mode, 0, 0, documentId)
        }

        var lastForegroundUpdatePage = -2
        val onProgress: suspend (String, Int, Int) -> Unit = { stage, current, total ->
            setProgress(
                workDataOf(
                    KEY_STAGE to stage,
                    KEY_CURRENT to current,
                    KEY_TOTAL to total,
                ),
            )
            if (shouldPromoteForeground || total > 2) {
                if (current - lastForegroundUpdatePage >= 2 || current == total || current == 1) {
                    lastForegroundUpdatePage = current
                    trySetForeground(stage, current, total, documentId)
                }
            }
        }

        return try {
            when (mode) {
                MODE_LOCAL_EXTRACT -> {
                    val transientPassword = pdfPasswordStore.take(documentId)
                    val extractionResult = documentRepository.runLocalExtraction(
                        documentId = documentId,
                        password = transientPassword,
                        onProgress = onProgress,
                    )
                    if (extractionResult.isSuccess) {
                        if (documentRepository.hasGeminiApiKey()) {
                            runCatching {
                                documentRepository.processNextAiBatch(
                                    documentId = documentId,
                                    onProgress = onProgress,
                                )
                            }
                        }
                        Result.success()
                    } else {
                        Result.failure()
                    }
                }
                MODE_AI_EXTRACT -> {
                    documentRepository.processDocumentWithAi(
                        documentId = documentId,
                        onProgress = onProgress,
                    )
                    Result.success()
                }
                MODE_AI_BATCH -> {
                    documentRepository.processNextAiBatch(
                        documentId = documentId,
                        onProgress = onProgress,
                    )
                    Result.success()
                }
                MODE_AI_AUTO -> {
                    documentRepository.autoProcessRemainingAiBatches(
                        documentId = documentId,
                        onProgress = onProgress,
                    )
                    Result.success()
                }
                else -> Result.failure()
            }
        } catch (ce: CancellationException) {
            throw ce
        } catch (_: Exception) {
            Result.failure()
        } finally {
            pdfPasswordStore.clear(documentId)
        }
    }

    private suspend fun trySetForeground(
        stage: String,
        current: Int,
        total: Int,
        documentId: String,
    ) {
        try {
            setForeground(createForegroundInfo(stage, current, total, documentId))
        } catch (_: IllegalStateException) {
            // Graceful fallback when OS restricts background foreground-service promotion
        } catch (_: SecurityException) {
            // Graceful fallback when foreground permission/type is restricted
        }
    }

    private fun createForegroundInfo(
        stage: String,
        current: Int,
        total: Int,
        documentId: String,
    ): ForegroundInfo {
        ensureNotificationChannel()
        val label = when (stage) {
            MODE_AI_EXTRACT, "AI_EXTRACT" -> "Extracting transactions with Gemini AI"
            else -> "Reading statement pages"
        }
        val contentText = if (total > 0) {
            "$label • Page $current of $total"
        } else {
            label
        }
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle("OpenFolio Import")
            .setContentText(contentText)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(total.coerceAtLeast(0), current.coerceAtLeast(0), total <= 0)
            .build()

        val notificationId = NOTIFICATION_ID_BASE + (documentId.hashCode() and 0x0FFF)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(
                notificationId,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
        } else {
            ForegroundInfo(notificationId, notification)
        }
    }

    private fun ensureNotificationChannel() {
        val manager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Document Imports",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "Shows progress while parsing large PDF statements."
            }
            manager.createNotificationChannel(channel)
        }
    }

    companion object {
        const val KEY_DOCUMENT_ID = "document_id"
        const val KEY_MODE = "import_mode"
        const val KEY_FILE_SIZE = "file_size"
        const val KEY_STAGE = "stage"
        const val KEY_CURRENT = "current"
        const val KEY_TOTAL = "total"
        const val MODE_LOCAL_EXTRACT = "LOCAL_EXTRACT"
        const val MODE_AI_EXTRACT = "AI_EXTRACT"
        const val MODE_AI_BATCH = "AI_BATCH"
        const val MODE_AI_AUTO = "AI_AUTO"
        private const val CHANNEL_ID = "openfolio_imports"
        private const val NOTIFICATION_ID_BASE = 4100
        private const val FOREGROUND_SIZE_THRESHOLD_BYTES = 2L * 1024L * 1024L
    }
}
