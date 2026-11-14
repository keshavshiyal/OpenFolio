package com.openfolio.core.data.repository

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.room.withTransaction
import com.openfolio.core.data.local.OpenFolioDatabase
import com.openfolio.core.data.local.Transaction
import com.openfolio.core.data.local.TransactionStatus
import com.openfolio.core.data.local.TransactionType
import com.openfolio.core.data.local.dao.DocumentDao
import com.openfolio.core.data.local.dao.DraftTransactionDao
import com.openfolio.core.data.local.dao.TransactionDao
import com.openfolio.core.data.local.entity.DocumentEntity
import com.openfolio.core.data.local.entity.DocumentStatus
import com.openfolio.core.data.local.entity.DraftTransactionEntity
import com.openfolio.core.di.ApplicationScope
import com.openfolio.core.domain.repository.DocumentRepository
import com.openfolio.core.domain.repository.DuplicateDocumentException
import com.openfolio.core.domain.repository.SaveDraftTransactionsResult
import com.openfolio.core.utils.DocumentTextExtractor
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.math.BigDecimal
import java.security.MessageDigest
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class DocumentRepositoryImpl @Inject constructor(
    private val documentDao: DocumentDao,
    private val draftTransactionDao: DraftTransactionDao,
    private val transactionDao: TransactionDao,
    private val database: OpenFolioDatabase,
    private val documentTextExtractor: DocumentTextExtractor,
    @ApplicationScope private val applicationScope: CoroutineScope,
) : DocumentRepository {
    override fun getAllDocuments(): Flow<List<DocumentEntity>> =
        documentDao.getAllDocuments().flowOn(Dispatchers.IO)

    override suspend fun getDocumentById(id: String): DocumentEntity? =
        withContext(Dispatchers.IO) {
            documentDao.getDocumentById(id)
        }

    override fun observeDocumentById(id: String): Flow<DocumentEntity?> =
        documentDao.observeDocumentById(id).flowOn(Dispatchers.IO)

    override fun getDraftTransactions(documentId: String): Flow<List<DraftTransactionEntity>> =
        draftTransactionDao.getByDocumentId(documentId).flowOn(Dispatchers.IO)

    override suspend fun insertDraftTransactionsIfEmpty(
        documentId: String,
        drafts: List<DraftTransactionEntity>,
    ) = withContext(Dispatchers.IO) {
        database.withTransaction {
            require(documentDao.getDocumentById(documentId) != null) {
                "Document not found: $documentId"
            }
            require(drafts.all { it.documentId == documentId }) {
                "Draft transactions must belong to the requested document."
            }
            if (draftTransactionDao.getByDocumentIdOnce(documentId).isEmpty()) {
                draftTransactionDao.insertAll(drafts)
            }
        }
    }

    override suspend fun insertDraftTransaction(draft: DraftTransactionEntity) =
        withContext(Dispatchers.IO) {
            require(documentDao.getDocumentById(draft.documentId) != null) {
                "Document not found: ${draft.documentId}"
            }
            draftTransactionDao.insert(draft)
        }

    override suspend fun updateDraftTransaction(draft: DraftTransactionEntity) =
        withContext(Dispatchers.IO) {
            draftTransactionDao.update(draft)
        }

    override suspend fun setDraftAccepted(draftId: String, accepted: Boolean) {
        withContext(Dispatchers.IO) {
            draftTransactionDao.setAccepted(draftId, accepted)
        }
    }

    override suspend fun acceptHighConfidenceDrafts(
        documentId: String,
        minimumConfidence: Int,
    ) {
        require(minimumConfidence in 0..100)
        withContext(Dispatchers.IO) {
            draftTransactionDao.acceptHighConfidence(documentId, minimumConfidence)
        }
    }

    override suspend fun saveDraftTransactions(
        documentId: String,
    ): SaveDraftTransactionsResult = withContext(Dispatchers.IO) {
        database.withTransaction {
            val document = requireNotNull(documentDao.getDocumentById(documentId)) {
                "Document not found: $documentId"
            }
            if (document.status == DocumentStatus.COMPLETED.name) {
                return@withTransaction SaveDraftTransactionsResult(0, 0)
            }

            var savedCount = 0
            var duplicateCount = 0
            draftTransactionDao.getByDocumentIdOnce(documentId).forEach { draft ->
                val fingerprint = createTransactionFingerprint(
                    assetName = draft.assetName,
                    transactionType = draft.transactionType,
                    quantity = draft.quantity,
                    netAmount = draft.netAmount,
                    date = Math.floorDiv(document.importDate, MILLIS_PER_DAY),
                )
                if (transactionDao.containsFingerprint(fingerprint)) {
                    duplicateCount++
                } else {
                    val quantity = BigDecimal(draft.quantity)
                    val price = BigDecimal(draft.price)
                    transactionDao.insert(
                        Transaction(
                            id = draft.id,
                            assetId = null,
                            type = TransactionType.valueOf(draft.transactionType),
                            tradeDate = document.importDate,
                            settlementDate = null,
                            quantity = quantity,
                            grossAmount = quantity.multiply(price),
                            fees = BigDecimal.ZERO,
                            taxes = BigDecimal.ZERO,
                            netAmount = BigDecimal(draft.netAmount),
                            currency = "INR",
                            fingerprint = fingerprint,
                            documentId = document.id,
                            sourcePage = null,
                            confidenceScore = draft.confidence / 100f,
                            status = TransactionStatus.CONFIRMED,
                            isManual = draft.isManual,
                            fxRate = null,
                            baseCurrencyAmount = null,
                        ),
                    )
                    savedCount++
                }
            }
            draftTransactionDao.deleteByDocumentId(documentId)
            documentDao.updateStatus(documentId, DocumentStatus.COMPLETED.name)
            SaveDraftTransactionsResult(savedCount, duplicateCount)
        }
    }

    override suspend fun processDocumentWithMockAi(documentId: String) =
        withContext(Dispatchers.IO) {
            val document = requireNotNull(documentDao.getDocumentById(documentId)) {
                "Document not found: $documentId"
            }
            require(
                document.status == DocumentStatus.OCR_COMPLETE.name ||
                    document.status == DocumentStatus.AI_COMPLETE.name,
            ) {
                "Document text is not ready for AI processing."
            }
            require(document.extractedText?.isNotBlank() == true) {
                "No extracted text is available to process."
            }
            documentDao.updateStatus(documentId, DocumentStatus.AI_COMPLETE.name)
            val textChunks = document.extractedText.chunked(AI_CHUNK_SIZE)
            require(textChunks.isNotEmpty()) { "No extracted text is available to process." }
            textChunks.forEach { kotlinx.coroutines.yield() }
            insertDraftTransactionsIfEmpty(documentId, mockAiDraftTransactions(documentId))
        }

    override suspend fun ingestFile(uri: Uri, context: Context): DocumentEntity =
        withContext(Dispatchers.IO) {
            val resolver = context.contentResolver
            val metadata = resolver.query(
                uri,
                arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
                null,
                null,
                null,
            )?.use { cursor ->
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (cursor.moveToFirst()) {
                    FileMetadata(
                        fileName = nameIndex.takeIf { it >= 0 }
                            ?.let(cursor::getString)
                            ?.takeIf(String::isNotBlank)
                            ?: UNKNOWN_FILE_NAME,
                        fileSize = sizeIndex.takeIf { it >= 0 }
                            ?.let(cursor::getLong)
                            ?.takeIf { it >= 0L },
                    )
                } else {
                    FileMetadata(UNKNOWN_FILE_NAME, null)
                }
            } ?: FileMetadata(UNKNOWN_FILE_NAME, null)

            val hash = resolver.openInputStream(uri)?.use { calculateSha256(it) }
                ?: throw IOException("Unable to read the selected file.")
            if (documentDao.getDocumentByHash(hash) != null) {
                throw DuplicateDocumentException(hash)
            }

            val documentsDirectory = File(context.filesDir, DOCUMENTS_DIRECTORY)
            if (!documentsDirectory.exists() && !documentsDirectory.mkdirs()) {
                throw IOException("Unable to create the private documents directory.")
            }
            val extension = metadata.fileName.substringAfterLast('.', "pdf")
                .lowercase()
                .takeIf { it in SUPPORTED_EXTENSIONS }
                ?: "pdf"
            val destination = File(documentsDirectory, "${UUID.randomUUID()}.$extension")
            try {
                val copiedSize = resolver.openInputStream(uri)?.use { input ->
                    FileOutputStream(destination).use { output -> input.copyTo(output) }
                } ?: throw IOException("Unable to read the selected file.")
                val document = DocumentEntity(
                    fileName = metadata.fileName,
                    localPath = destination.absolutePath,
                    fileHash = hash,
                    fileSize = metadata.fileSize ?: copiedSize,
                    importDate = System.currentTimeMillis(),
                    status = DocumentStatus.PENDING_OCR.name,
                )
                documentDao.insert(document)
                applicationScope.launch {
                    try {
                        val extractedText = documentTextExtractor.extractText(
                            destination,
                            document.fileName,
                        )
                        require(extractedText.isNotBlank()) {
                            "No text could be extracted from this document."
                        }
                        documentDao.updateExtractionResult(
                            document.id,
                            extractedText,
                            DocumentStatus.OCR_COMPLETE.name,
                        )
                    } catch (exception: CancellationException) {
                        throw exception
                    } catch (exception: Exception) {
                        documentDao.updateExtractionResult(
                            document.id,
                            null,
                            DocumentStatus.OCR_FAILED.name,
                        )
                    }
                }
                document
            } catch (exception: Exception) {
                if (destination.exists() && !destination.delete()) {
                    exception.addSuppressed(
                        IOException("Unable to remove the incomplete imported file."),
                    )
                }
                throw exception
            }
        }

    private data class FileMetadata(
        val fileName: String,
        val fileSize: Long?,
    )

    private companion object {
        const val DOCUMENTS_DIRECTORY = "documents"
        const val UNKNOWN_FILE_NAME = "unknown_file.pdf"
        const val AI_CHUNK_SIZE = 3_000
        const val MILLIS_PER_DAY = 86_400_000L
        val SUPPORTED_EXTENSIONS = setOf("pdf", "csv")
    }
}

internal suspend fun calculateSha256(inputStream: InputStream): String =
    withContext(Dispatchers.IO) {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val bytesRead = inputStream.read(buffer)
            if (bytesRead < 0) break
            digest.update(buffer, 0, bytesRead)
        }
        digest.digest().toLowerHex()
    }

internal fun createTransactionFingerprint(
    assetName: String,
    transactionType: String,
    quantity: String,
    netAmount: String,
    date: Long,
): String {
    val normalized = listOf(
        assetName.trim().lowercase(),
        transactionType.uppercase(),
        quantity.trim(),
        netAmount.trim(),
        date.toString(),
    ).joinToString("|")
    return MessageDigest.getInstance("SHA-256")
        .digest(normalized.toByteArray(Charsets.UTF_8))
        .toLowerHex()
}

private fun ByteArray.toLowerHex(): String =
    joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xff) }

private fun mockAiDraftTransactions(documentId: String): List<DraftTransactionEntity> =
    listOf(
        DraftTransactionEntity(
            documentId = documentId,
            assetName = "Reliance Industries",
            transactionType = "BUY",
            quantity = "10",
            price = "2850.00",
            netAmount = "28500.00",
            confidence = 98,
        ),
        DraftTransactionEntity(
            documentId = documentId,
            assetName = "HDFC Bank",
            transactionType = "BUY",
            quantity = "5",
            price = "1680.00",
            netAmount = "8400.00",
            confidence = 98,
        ),
        DraftTransactionEntity(
            documentId = documentId,
            assetName = "Tata Consultancy Services",
            transactionType = "SELL",
            quantity = "2",
            price = "4125.00",
            netAmount = "8250.00",
            confidence = 98,
        ),
        DraftTransactionEntity(
            documentId = documentId,
            assetName = "ICICI Prudential Bluechip Fund",
            transactionType = "BUY",
            quantity = "12.5",
            price = "78.40",
            netAmount = "980.00",
            confidence = 85,
        ),
        DraftTransactionEntity(
            documentId = documentId,
            assetName = "Nippon India Small Cap Fund",
            transactionType = "BUY",
            quantity = "8",
            price = "156.25",
            netAmount = "1250.00",
            confidence = 65,
        ),
    )
