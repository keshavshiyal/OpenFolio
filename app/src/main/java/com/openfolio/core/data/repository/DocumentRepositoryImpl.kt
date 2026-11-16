package com.openfolio.core.data.repository

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.room.withTransaction
import com.openfolio.core.data.local.OpenFolioDatabase
import com.openfolio.core.data.local.dao.DocumentDao
import com.openfolio.core.data.local.dao.ColumnMappingDao
import com.openfolio.core.data.local.dao.DraftTransactionDao
import com.openfolio.core.data.local.dao.TransactionDao
import com.openfolio.core.data.local.entity.AccountEntity
import com.openfolio.core.data.local.entity.AccountType
import com.openfolio.core.data.local.entity.AssetClass
import com.openfolio.core.data.local.entity.AssetEntity
import com.openfolio.core.data.local.entity.ColumnMappingEntity
import com.openfolio.core.data.local.entity.DocumentEntity
import com.openfolio.core.data.local.entity.DocumentStatus
import com.openfolio.core.data.local.entity.DraftTransactionEntity
import com.openfolio.core.data.local.entity.TransactionEntity
import com.openfolio.core.data.local.entity.TransactionStatus
import com.openfolio.core.data.local.entity.TransactionType
import com.openfolio.core.di.ApplicationScope
import com.openfolio.core.domain.repository.DocumentRepository
import com.openfolio.core.domain.repository.DuplicateDocumentException
import com.openfolio.core.domain.repository.SaveDraftTransactionsResult
import com.openfolio.core.utils.DocumentTextExtractor
import com.openfolio.core.utils.StructuredFileParser
import com.openfolio.core.utils.StructuredTable
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.security.MessageDigest
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import com.openfolio.core.data.remote.ai.AiProvider
import com.openfolio.core.domain.repository.AiExtractionException
import com.openfolio.core.domain.repository.EmptyExtractedTextException
import com.openfolio.core.domain.repository.MissingApiKeyException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class DocumentRepositoryImpl @Inject constructor(
    private val documentDao: DocumentDao,
    private val columnMappingDao: ColumnMappingDao,
    private val draftTransactionDao: DraftTransactionDao,
    private val transactionDao: TransactionDao,
    private val database: OpenFolioDatabase,
    private val documentTextExtractor: DocumentTextExtractor,
    private val structuredFileParser: StructuredFileParser,
    private val settingsRepository: SettingsRepository,
    private val aiProvider: AiProvider,
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
                    date = Math.floorDiv(draft.tradeDate ?: document.importDate, MILLIS_PER_DAY),
                )
                if (transactionDao.containsFingerprint(fingerprint)) {
                    duplicateCount++
                } else {
                    val quantity = BigDecimal(draft.quantity)
                    val price = BigDecimal(draft.price)
                    val accountId = "default_account"
                    val assetId = draft.assetName.trim().lowercase().replace("\\s+".toRegex(), "_")
                    database.accountDao().insert(
                        AccountEntity(
                            id = accountId,
                            name = "Default Account",
                            type = AccountType.BROKER,
                            currency = "INR",
                        ),
                    )
                    database.assetDao().insert(
                        AssetEntity(
                            id = assetId,
                            displayName = draft.assetName,
                            assetClass = AssetClass.STOCK,
                            currency = "INR",
                        ),
                    )
                    val dateDays = Math.floorDiv(draft.tradeDate ?: document.importDate, MILLIS_PER_DAY)
                    transactionDao.insert(
                        TransactionEntity(
                            id = draft.id,
                            accountId = accountId,
                            assetId = assetId,
                            type = TransactionType.valueOf(draft.transactionType),
                            tradeDate = LocalDate.ofEpochDay(dateDays),
                            settlementDate = null,
                            quantity = quantity,
                            unitPrice = price,
                            grossAmount = quantity.multiply(price),
                            fees = BigDecimal.ZERO,
                            taxes = BigDecimal.ZERO,
                            netAmount = BigDecimal(draft.netAmount),
                            currency = "INR",
                            fxRate = null,
                            baseCurrencyAmount = null,
                            description = null,
                            notes = null,
                            fingerprint = fingerprint,
                            externalRef = null,
                            status = TransactionStatus.CONFIRMED,
                            documentId = document.id,
                            sourcePage = null,
                            confidence = draft.confidence / 100.0,
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

    override suspend fun processDocumentWithAi(documentId: String) =
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
            val rawText = document.extractedText.orEmpty().trim()
            if (rawText.isBlank()) {
                throw EmptyExtractedTextException()
            }

            val apiKey = settingsRepository.getGeminiApiKey().trim()
            if (apiKey.isBlank()) {
                throw MissingApiKeyException()
            }

            val textChunks = rawText.chunked(AI_CHUNK_SIZE)
            require(textChunks.isNotEmpty()) { "No extracted text is available to process." }

            val aggregatedDrafts = mutableListOf<DraftTransactionEntity>()
            for (chunk in textChunks) {
                val drafts = try {
                    val result = aiProvider.extractTransactions(
                        rawText = chunk,
                        apiKey = apiKey,
                        documentId = documentId,
                    )
                    result.getOrThrow()
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    throw AiExtractionException(e.localizedMessage ?: "Unknown AI error", e)
                }
                aggregatedDrafts.addAll(drafts)
            }

            if (aggregatedDrafts.isNotEmpty()) {
                draftTransactionDao.insertAll(aggregatedDrafts)
            }
            documentDao.updateStatus(documentId, DocumentStatus.AI_COMPLETE.name)
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

    override suspend fun getStructuredFileHeaders(
        documentId: String,
        headerRowNumber: Int,
    ): List<String> =
        withContext(Dispatchers.IO) {
            val document = requireNotNull(documentDao.getDocumentById(documentId)) {
                "Document not found: $documentId"
            }
            require(document.status == DocumentStatus.READY_FOR_MAPPING.name) {
                "This document is not waiting for column mapping."
            }
            structuredFileParser.read(File(document.localPath), headerRowNumber).headers
        }

    override suspend fun processStructuredDocument(
        documentId: String,
        mapping: ColumnMappingEntity,
    ) = withContext(Dispatchers.IO) {
        val document = requireNotNull(documentDao.getDocumentById(documentId)) {
            "Document not found: $documentId"
        }
        require(document.status == DocumentStatus.READY_FOR_MAPPING.name) {
            "This document is not waiting for column mapping."
        }
        val table = structuredFileParser.read(File(document.localPath), mapping.headerRowNumber)
        val drafts = createMappedDrafts(document, table, mapping)
        persistMappedDrafts(document, mapping, drafts)
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

            val extension = metadata.fileName.substringAfterLast('.', "")
                .lowercase()
                .takeIf { it in SUPPORTED_EXTENSIONS }
                ?: throw IllegalArgumentException(
                    "Unsupported file type. Select a PDF, CSV, XLSX, PNG, or JPG file.",
                )
            val hash = resolver.openInputStream(uri)?.use { calculateSha256(it) }
                ?: throw IOException("Unable to read the selected file.")
            if (documentDao.getDocumentByHash(hash) != null) {
                throw DuplicateDocumentException(hash)
            }

            val documentsDirectory = File(context.filesDir, DOCUMENTS_DIRECTORY)
            if (!documentsDirectory.exists() && !documentsDirectory.mkdirs()) {
                throw IOException("Unable to create the private documents directory.")
            }
            val destination = File(documentsDirectory, "${UUID.randomUUID()}.$extension")
            var insertedDocument: DocumentEntity? = null
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
                    status = if (extension in STRUCTURED_EXTENSIONS) {
                        DocumentStatus.READY_FOR_MAPPING.name
                    } else {
                        DocumentStatus.PENDING_OCR.name
                    },
                )
                documentDao.insert(document)
                insertedDocument = document
                if (extension in STRUCTURED_EXTENSIONS) {
                    val mappings = columnMappingDao.getAll()
                    val matchingMapping = mappings.lastOrNull { mapping ->
                        try {
                            val table = structuredFileParser.read(destination, mapping.headerRowNumber)
                            mapping.matchesHeaders(table.headers)
                        } catch (_: Exception) {
                            false
                        }
                    }
                    if (matchingMapping != null) {
                        val table = structuredFileParser.read(destination, matchingMapping.headerRowNumber)
                        val drafts = createMappedDrafts(document, table, matchingMapping)
                        if (drafts.isNotEmpty()) {
                            persistMappedDrafts(
                                document = document,
                                mapping = matchingMapping,
                                drafts = drafts,
                                saveMapping = false,
                            )
                            return@withContext document.copy(
                                status = DocumentStatus.AI_COMPLETE.name,
                            )
                        }
                    }
                    return@withContext document
                }
                applicationScope.launch {
                    try {
                        val extractedText = documentTextExtractor.extractText(destination, document.fileName)
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
                insertedDocument?.let { documentDao.deleteById(it.id) }
                if (destination.exists() && !destination.delete()) {
                    exception.addSuppressed(
                        IOException("Unable to remove the incomplete imported file."),
                    )
                }
                throw exception
            }
        }

    private suspend fun persistMappedDrafts(
        document: DocumentEntity,
        mapping: ColumnMappingEntity,
        drafts: List<DraftTransactionEntity>,
        saveMapping: Boolean = true,
    ) {
        require(drafts.isNotEmpty()) { "No transaction rows were found in this file." }
        database.withTransaction {
            if (saveMapping) columnMappingDao.insert(mapping)
            draftTransactionDao.insertAll(drafts)
            documentDao.updateStatus(document.id, DocumentStatus.AI_COMPLETE.name)
        }
    }

    internal fun createMappedDrafts(
        document: DocumentEntity,
        table: StructuredTable,
        mapping: ColumnMappingEntity,
    ): List<DraftTransactionEntity> {
        require(mapping.brokerName.isNotBlank()) { "Enter a name for this column mapping." }
        val mappedColumns = listOf(
            mapping.assetColumn,
            mapping.typeColumn,
            mapping.quantityColumn,
            mapping.priceColumn,
            mapping.amountColumn,
            mapping.dateColumn,
        ).filter(String::isNotBlank)

        require(mappedColumns.all { it in table.headers }) {
            "The saved mapping does not match this file's headers."
        }

        val assetIndex = mapping.assetColumn.takeIf(String::isNotBlank)?.let(table.headers::indexOf)
        val typeIndex = mapping.typeColumn.takeIf(String::isNotBlank)?.let(table.headers::indexOf)
        val quantityIndex = mapping.quantityColumn.takeIf(String::isNotBlank)?.let(table.headers::indexOf)
        val priceIndex = mapping.priceColumn.takeIf(String::isNotBlank)?.let(table.headers::indexOf)
        val amountIndex = mapping.amountColumn.takeIf(String::isNotBlank)?.let(table.headers::indexOf)
        val dateIndex = mapping.dateColumn.takeIf(String::isNotBlank)?.let(table.headers::indexOf)

        return table.rows.mapNotNull { row ->
            if (row.all(String::isBlank)) return@mapNotNull null

            val assetName = assetIndex?.let { row.getOrNull(it) }?.trim().orEmpty().ifBlank {
                document.fileName.substringBeforeLast('.')
            }
            require(assetName.isNotBlank()) { "A transaction row has no asset name." }

            val rawType = typeIndex?.let { row.getOrNull(it) }?.trim().orEmpty()
            val type = if (rawType.isNotBlank()) normalizeTransactionType(rawType) else "BUY"

            val rawQty = quantityIndex?.let { row.getOrNull(it) }?.trim().orEmpty()
            val rawPrice = priceIndex?.let { row.getOrNull(it) }?.trim().orEmpty()
            val rawAmount = amountIndex?.let { row.getOrNull(it) }?.trim().orEmpty()

            val normalizedQty = if (rawQty.isNotBlank()) normalizeNumber(rawQty, "quantity") else null
            val normalizedPrice = if (rawPrice.isNotBlank()) normalizeNumber(rawPrice, "price") else null
            val normalizedAmount = if (rawAmount.isNotBlank()) normalizeNumber(rawAmount, "amount") else null

            val (quantity, price, amount) = computeMissingFinancials(
                qty = normalizedQty,
                price = normalizedPrice,
                amount = normalizedAmount,
            )

            DraftTransactionEntity(
                documentId = document.id,
                assetName = assetName,
                transactionType = type,
                quantity = quantity,
                price = price,
                netAmount = amount,
                confidence = 100,
                tradeDate = dateIndex?.let { index ->
                    parseMappedDate(row.getOrNull(index).orEmpty())
                },
            )
        }
    }

    private fun computeMissingFinancials(
        qty: String?,
        price: String?,
        amount: String?,
    ): Triple<String, String, String> {
        val qtyBd = qty?.toBigDecimalOrNull()
        val priceBd = price?.toBigDecimalOrNull()
        val amountBd = amount?.toBigDecimalOrNull()

        return when {
            qtyBd != null && priceBd != null && amountBd != null -> {
                Triple(qtyBd.toPlainString(), priceBd.toPlainString(), amountBd.toPlainString())
            }
            qtyBd != null && priceBd != null -> {
                val calculatedAmount = qtyBd.multiply(priceBd).stripTrailingZeros()
                Triple(qtyBd.toPlainString(), priceBd.toPlainString(), calculatedAmount.toPlainString())
            }
            qtyBd != null && amountBd != null -> {
                val calculatedPrice = if (qtyBd.compareTo(BigDecimal.ZERO) != 0) {
                    amountBd.divide(qtyBd, 4, RoundingMode.HALF_UP).stripTrailingZeros()
                } else {
                    BigDecimal.ZERO
                }
                Triple(qtyBd.toPlainString(), calculatedPrice.toPlainString(), amountBd.toPlainString())
            }
            priceBd != null && amountBd != null -> {
                val calculatedQty = if (priceBd.compareTo(BigDecimal.ZERO) != 0) {
                    amountBd.divide(priceBd, 4, RoundingMode.HALF_UP).stripTrailingZeros()
                } else {
                    BigDecimal.ONE
                }
                Triple(calculatedQty.toPlainString(), priceBd.toPlainString(), amountBd.toPlainString())
            }
            qtyBd != null -> {
                Triple(qtyBd.toPlainString(), "0", "0")
            }
            amountBd != null -> {
                Triple("1", amountBd.toPlainString(), amountBd.toPlainString())
            }
            priceBd != null -> {
                Triple("1", priceBd.toPlainString(), priceBd.toPlainString())
            }
            else -> {
                Triple("1", "0", "0")
            }
        }
    }

    private fun ColumnMappingEntity.matchesHeaders(headers: List<String>): Boolean {
        val headerSet = headers.toSet()
        val mapped = listOf(assetColumn, typeColumn, quantityColumn, priceColumn, amountColumn, dateColumn)
            .filter(String::isNotBlank)
        return mapped.isNotEmpty() && mapped.all { it in headerSet }
    }

    private fun normalizeTransactionType(value: String): String {
        val normalized = when (value.trim().uppercase()) {
            "PURCHASE", "BOUGHT" -> "BUY"
            "SALE", "SOLD" -> "SELL"
            "DIV" -> "DIVIDEND"
            else -> value.trim().uppercase()
        }
        return TransactionType.entries.firstOrNull { it.name == normalized }?.name
            ?: throw IllegalArgumentException("Unsupported transaction type: $value")
    }

    private fun normalizeNumber(value: String, field: String): String {
        val normalized = value.replace(",", "").replace("₹", "").trim()
        require(normalized.toBigDecimalOrNull() != null) {
            "A transaction row has an invalid $field value."
        }
        return normalized
    }

    private fun parseMappedDate(value: String): Long? {
        val date = value.trim().takeIf(String::isNotEmpty) ?: return null
        val formats = listOf(
            DateTimeFormatter.ISO_LOCAL_DATE,
            DateTimeFormatter.ofPattern("d/M/uuuu"),
            DateTimeFormatter.ofPattern("d-M-uuuu"),
            DateTimeFormatter.ofPattern("M/d/uuuu"),
        )
        formats.forEach { format ->
            try {
                return LocalDate.parse(date, format).atStartOfDay()
                    .toInstant(java.time.ZoneOffset.UTC).toEpochMilli()
            } catch (_: DateTimeParseException) {
                Unit
            }
        }
        val excelSerial = date.toBigDecimalOrNull()?.toLong()
        require(excelSerial != null && excelSerial in 1..100_000) {
            "A transaction row has an invalid date value."
        }
        return LocalDate.of(1899, 12, 30).plusDays(excelSerial).atStartOfDay()
            .toInstant(java.time.ZoneOffset.UTC).toEpochMilli()
    }

    private data class FileMetadata(
        val fileName: String,
        val fileSize: Long?,
    )

    private companion object {
        const val DOCUMENTS_DIRECTORY = "documents"
        const val UNKNOWN_FILE_NAME = "unknown_file"
        const val AI_CHUNK_SIZE = 3_000
        const val MILLIS_PER_DAY = 86_400_000L
        val SUPPORTED_EXTENSIONS = setOf("pdf", "csv", "xlsx", "png", "jpg", "jpeg")
        val STRUCTURED_EXTENSIONS = setOf("csv", "xlsx")
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
