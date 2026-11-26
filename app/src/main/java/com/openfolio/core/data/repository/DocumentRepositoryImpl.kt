package com.openfolio.core.data.repository

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import androidx.room.withTransaction
import com.openfolio.core.data.local.ExtractedTextStore
import com.openfolio.core.data.local.OpenFolioDatabase
import com.openfolio.core.data.local.PdfPasswordStore
import com.openfolio.core.data.local.dao.ColumnMappingDao
import com.openfolio.core.data.local.dao.DocumentDao
import com.openfolio.core.data.local.dao.DraftTransactionDao
import com.openfolio.core.data.local.dao.TransactionDao
import com.openfolio.core.data.local.entity.AccountEntity
import com.openfolio.core.data.local.entity.AccountType
import com.openfolio.core.data.local.entity.AssetClass
import com.openfolio.core.data.local.entity.AssetEntity
import com.openfolio.core.data.local.entity.AssetStatus
import com.openfolio.core.data.local.entity.ColumnMappingEntity
import com.openfolio.core.data.local.entity.DocumentEntity
import com.openfolio.core.data.local.entity.DocumentStatus
import com.openfolio.core.data.local.entity.DraftTransactionEntity
import com.openfolio.core.data.local.entity.TransactionEntity
import com.openfolio.core.data.local.entity.TransactionStatus
import com.openfolio.core.data.local.entity.TransactionType
import com.openfolio.core.data.remote.ai.AiCarryOverContext
import com.openfolio.core.data.remote.ai.AiExtractionBatchResult
import com.openfolio.core.data.remote.ai.AiProvider
import com.openfolio.core.data.remote.ai.ContextualAiProvider
import com.openfolio.core.data.remote.ai.TruncationAwareAiProvider
import com.openfolio.core.di.ApplicationScope
import com.openfolio.core.domain.calculator.FifoCostBasisCalculator
import com.openfolio.core.domain.repository.AiBatchProcessResult
import com.openfolio.core.domain.repository.AiBatchStopReason
import com.openfolio.core.domain.repository.AiExtractionException
import com.openfolio.core.domain.repository.DocumentRepository
import com.openfolio.core.domain.repository.DuplicateDocumentException
import com.openfolio.core.domain.repository.DuplicateMatchInfo
import com.openfolio.core.domain.repository.EmptyExtractedTextException
import com.openfolio.core.domain.repository.MissingApiKeyException
import com.openfolio.core.domain.repository.NoFinancialDataException
import com.openfolio.core.domain.repository.RollbackImportResult
import com.openfolio.core.domain.repository.SaveDraftTransactionsResult
import com.openfolio.core.tableextraction.LocalTableExtractionPipeline
import com.openfolio.core.utils.AssetNameNormalizer
import com.openfolio.core.utils.BrokerParseState
import com.openfolio.core.utils.BrokerStatementParser
import com.openfolio.core.utils.CasParseState
import com.openfolio.core.utils.CasStatementParser
import com.openfolio.core.utils.DateParser
import com.openfolio.core.utils.DocumentTextExtractor
import com.openfolio.core.utils.PdfPageContent
import com.openfolio.core.utils.StreamingPdfProcessor
import com.openfolio.core.utils.StructuredFileParser
import com.openfolio.core.utils.StructuredTable
import com.openfolio.core.worker.DocumentImportScheduler
import com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.math.BigDecimal
import java.math.RoundingMode
import java.security.DigestInputStream
import java.security.MessageDigest
import java.time.LocalDate
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.MissingFieldException

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
    private val casStatementParser: CasStatementParser = CasStatementParser(),
    private val brokerStatementParser: BrokerStatementParser = BrokerStatementParser(),
    private val localTableExtractionPipeline: LocalTableExtractionPipeline? = null,
    private val extractedTextStore: ExtractedTextStore? = null,
    private val streamingPdfProcessor: StreamingPdfProcessor? = null,
    private val pdfPasswordStore: PdfPasswordStore? = null,
    private val importScheduler: DocumentImportScheduler? = null,
) : DocumentRepository {

    internal var transactionRunner: (suspend (suspend () -> Any?) -> Any?)? = null

    @Suppress("UNCHECKED_CAST")
    private suspend fun <T> inDbTransaction(block: suspend () -> T): T {
        val runner = transactionRunner
        return if (runner != null) {
            runner(block) as T
        } else {
            database.withTransaction { block() }
        }
    }

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
        inDbTransaction {
            require(documentDao.getDocumentById(documentId) != null) {
                "Document not found: $documentId"
            }
            require(drafts.all { it.documentId == documentId }) {
                "Draft transactions must belong to the requested document."
            }
            if (draftTransactionDao.getByDocumentIdOnce(documentId).isEmpty()) {
                drafts.chunked(INSERT_BATCH_SIZE).forEach { batch ->
                    draftTransactionDao.insertAll(batch)
                }
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

    private data class PreparedDraft(
        val draft: DraftTransactionEntity,
        val resolvedTradeDate: LocalDate,
        val isin: String?,
        val ticker: String?,
        val assetId: String,
        val draftAssetClass: AssetClass,
        val fingerprint: String,
        val matchedAssetBeforeInsert: AssetEntity?,
    )

    override suspend fun saveDraftTransactions(
        documentId: String,
        selectedDraftIds: Set<String>?,
    ): SaveDraftTransactionsResult = withContext(Dispatchers.IO) {
        inDbTransaction {
            val document = requireNotNull(documentDao.getDocumentById(documentId)) {
                "Document not found: $documentId"
            }
            if (document.status == DocumentStatus.COMPLETED.name) {
                return@inDbTransaction SaveDraftTransactionsResult(0, 0)
            }

            val drafts = draftTransactionDao.getByDocumentIdOnce(documentId)
            val allDraftIdsSame = drafts.size > 1 && drafts.all { it.id == drafts.first().id }
            var duplicateCount = 0

            val eligibleDrafts = if (selectedDraftIds != null) {
                val (selected, unselected) = drafts.partition { it.id in selectedDraftIds }
                duplicateCount += unselected.size
                selected
            } else {
                drafts
            }

            if (eligibleDrafts.isEmpty()) {
                draftTransactionDao.deleteByDocumentId(documentId)
                documentDao.updateStatus(documentId, DocumentStatus.COMPLETED.name)
                return@inDbTransaction SaveDraftTransactionsResult(0, duplicateCount)
            }

            val existingAssets = database.assetDao().getAllOnce().toMutableList()
            val persistedAssetIds = existingAssets.mapTo(HashSet(existingAssets.size * 2 + 8)) { it.id }
            val assetIndex = AssetMatcherIndex(existingAssets)
            val fallbackDate = if (document.importDate > 0) {
                LocalDate.ofEpochDay(Math.floorDiv(document.importDate, MILLIS_PER_DAY))
            } else {
                LocalDate.now()
            }

            // Pass 1: O(1) in-memory asset matching and fingerprint computation
            val prepared = ArrayList<PreparedDraft>(eligibleDrafts.size)
            for (draft in eligibleDrafts) {
                val resolvedTradeDate = draft.tradeDate?.let {
                    LocalDate.ofEpochDay(Math.floorDiv(it, MILLIS_PER_DAY))
                } ?: fallbackDate

                val isin = draft.isin?.takeIf(String::isNotBlank)
                    ?: AssetNameNormalizer.extractIsin(draft.assetName)
                val ticker = draft.ticker?.takeIf(String::isNotBlank)

                val matchedAsset = assetIndex.findMatch(
                    rawName = draft.assetName,
                    identifier = isin ?: ticker,
                    isin = isin,
                    ticker = ticker,
                )
                val assetId = matchedAsset?.id ?: when {
                    isin != null -> isin.uppercase()
                    ticker != null -> ticker.uppercase()
                    else -> AssetNameNormalizer.normalize(draft.assetName).ifBlank { UUID.randomUUID().toString() }
                }
                val draftAssetClass = try {
                    AssetClass.valueOf(draft.assetClass.trim().uppercase())
                } catch (_: Exception) {
                    document.getAssetClassEnum()
                }
                val fingerprint = createTransactionFingerprint(
                    assetId = assetId,
                    isin = isin,
                    ticker = ticker,
                    assetName = draft.assetName,
                    transactionType = draft.transactionType,
                    quantity = draft.quantity,
                    unitPrice = draft.price,
                    netAmount = draft.netAmount,
                    date = resolvedTradeDate.toEpochDay(),
                    broker = draft.broker,
                )

                prepared.add(
                    PreparedDraft(
                        draft = draft,
                        resolvedTradeDate = resolvedTradeDate,
                        isin = isin,
                        ticker = ticker,
                        assetId = assetId,
                        draftAssetClass = draftAssetClass,
                        fingerprint = fingerprint,
                        matchedAssetBeforeInsert = matchedAsset,
                    ),
                )
            }

            // Batched DB fingerprint lookup (batches of 500 to respect SQLite bind limits)
            val existingFingerprints = HashSet<String>(prepared.size)
            prepared.asSequence()
                .map { it.fingerprint }
                .distinct()
                .chunked(FINGERPRINT_QUERY_BATCH_SIZE)
                .forEach { batch ->
                    existingFingerprints.addAll(transactionDao.getExistingFingerprints(batch))
                }

            val seenTxnIds = mutableSetOf<String>()
            val transactionsToInsert = ArrayList<TransactionEntity>(prepared.size)
            val touchedAssetIds = linkedSetOf<String>()
            val createdOrUpdatedAssetIds = HashSet<String>()
            var accountEnsured = false

            // Pass 2: Materialize non-duplicate transactions (preserving intra-document identical fills)
            for (item in prepared) {
                if (item.fingerprint in existingFingerprints) {
                    duplicateCount++
                    continue
                }

                if (!accountEnsured) {
                    database.accountDao().insert(
                        AccountEntity(
                            id = DEFAULT_ACCOUNT_ID,
                            name = "Default Account",
                            type = AccountType.BROKER,
                            currency = "INR",
                        ),
                    )
                    accountEnsured = true
                }

                val draft = item.draft
                val assetId = item.assetId
                val targetAsset = item.matchedAssetBeforeInsert ?: assetIndex.getById(assetId)

                if (targetAsset == null && assetId !in persistedAssetIds && assetId !in createdOrUpdatedAssetIds) {
                    val idJson = AssetNameNormalizer.buildIdentifiersJson("{}", item.isin, item.ticker)
                    val newAsset = AssetEntity(
                        id = assetId,
                        displayName = draft.assetName.trim(),
                        assetClass = item.draftAssetClass,
                        currency = "INR",
                        totalQuantity = BigDecimal.ZERO,
                        totalInvested = BigDecimal.ZERO,
                        averageCost = BigDecimal.ZERO,
                        realizedPnL = BigDecimal.ZERO,
                        identifiersJson = idJson,
                        status = AssetStatus.ACTIVE,
                    )
                    database.assetDao().insert(newAsset)
                    assetIndex.register(newAsset)
                    persistedAssetIds.add(assetId)
                    createdOrUpdatedAssetIds.add(assetId)
                } else if (targetAsset != null) {
                    var updatedAsset = targetAsset
                    var changed = false
                    if (targetAsset.totalQuantity.compareTo(BigDecimal.ZERO) == 0 &&
                        targetAsset.assetClass == AssetClass.STOCK &&
                        item.draftAssetClass != AssetClass.STOCK
                    ) {
                        updatedAsset = updatedAsset.copy(assetClass = item.draftAssetClass)
                        changed = true
                    }
                    if (item.isin != null || item.ticker != null) {
                        val merged = AssetNameNormalizer.buildIdentifiersJson(
                            updatedAsset.identifiersJson,
                            item.isin,
                            item.ticker,
                        )
                        if (merged != updatedAsset.identifiersJson) {
                            updatedAsset = updatedAsset.copy(identifiersJson = merged)
                            changed = true
                        }
                    }
                    if (changed) {
                        database.assetDao().update(updatedAsset)
                        assetIndex.register(updatedAsset)
                    }
                }

                val cleanQty = draft.quantity.replace(",", "").replace("₹", "").trim().ifBlank { "0" }
                val cleanPrice = draft.price.replace(",", "").replace("₹", "").trim().ifBlank { "0" }
                val cleanNetAmount = draft.netAmount.replace(",", "").replace("₹", "").trim().ifBlank { "0" }
                val quantity = BigDecimal(cleanQty).abs()
                var price = BigDecimal(cleanPrice).abs()
                var netAmount = BigDecimal(cleanNetAmount).abs()

                if (price.compareTo(BigDecimal.ZERO) == 0 && quantity > BigDecimal.ZERO && netAmount > BigDecimal.ZERO) {
                    price = netAmount.divide(quantity, 4, RoundingMode.HALF_UP)
                } else if (netAmount.compareTo(BigDecimal.ZERO) == 0 && quantity > BigDecimal.ZERO && price > BigDecimal.ZERO) {
                    netAmount = quantity.multiply(price)
                }

                val transactionId = if (!allDraftIdsSame && draft.id.isNotBlank() && seenTxnIds.add(draft.id)) {
                    draft.id
                } else {
                    UUID.randomUUID().toString()
                }
                transactionsToInsert.add(
                    TransactionEntity(
                        id = transactionId,
                        accountId = DEFAULT_ACCOUNT_ID,
                        assetId = assetId,
                        type = TransactionType.valueOf(draft.transactionType),
                        tradeDate = item.resolvedTradeDate,
                        settlementDate = null,
                        quantity = quantity,
                        unitPrice = price,
                        grossAmount = quantity.multiply(price),
                        fees = BigDecimal.ZERO,
                        taxes = BigDecimal.ZERO,
                        netAmount = netAmount,
                        currency = "INR",
                        fxRate = null,
                        baseCurrencyAmount = null,
                        description = null,
                        notes = null,
                        fingerprint = item.fingerprint,
                        externalRef = null,
                        status = TransactionStatus.CONFIRMED,
                        documentId = document.id,
                        sourcePage = draft.sourcePage,
                        confidence = draft.confidence / 100.0,
                        broker = draft.broker?.trim(),
                    ),
                )
                touchedAssetIds.add(assetId)
            }

            runCatching { Log.d("SaveTxn", "Total transactions to insert: ${transactionsToInsert.size}") }
            if (transactionsToInsert.isNotEmpty()) {
                transactionsToInsert.chunked(INSERT_BATCH_SIZE).forEach { batch ->
                    transactionDao.insertAll(batch)
                }
            }

            // Recalculate cost basis and aggregates on FIFO basis for all touched assets
            val lotDao = runCatching { database.lotDao() }.getOrNull()
            for (assetId in touchedAssetIds) {
                val allTxns = transactionDao.getByAssetIdOnce(assetId)
                val fifoResult = FifoCostBasisCalculator.calculate(assetId, allTxns)

                lotDao?.deleteByAssetId(assetId)
                if (fifoResult.lots.isNotEmpty()) {
                    lotDao?.insertAll(fifoResult.lots)
                }

                database.assetDao().updateAggregates(
                    assetId = assetId,
                    qty = fifoResult.totalQuantity,
                    invested = fifoResult.totalInvested,
                    avgCost = fifoResult.averageCost,
                    realizedPnL = fifoResult.realizedPnL,
                    timestamp = System.currentTimeMillis(),
                )
                val currentAsset = database.assetDao().getById(assetId)
                if (currentAsset != null && currentAsset.status != fifoResult.status) {
                    database.assetDao().update(currentAsset.copy(status = fifoResult.status))
                }
            }

            draftTransactionDao.deleteByDocumentId(documentId)
            documentDao.updateStatus(documentId, DocumentStatus.COMPLETED.name)
            SaveDraftTransactionsResult(transactionsToInsert.size, duplicateCount)
        }
    }

    override suspend fun findDuplicateDrafts(
        documentId: String,
    ): Map<String, DuplicateMatchInfo> = withContext(Dispatchers.IO) {
        val document = documentDao.getDocumentById(documentId) ?: return@withContext emptyMap()
        val drafts = draftTransactionDao.getByDocumentIdOnce(documentId)
        if (drafts.isEmpty()) return@withContext emptyMap()

        val existingAssets = database.assetDao().getAllOnce().toMutableList()
        val assetIndex = AssetMatcherIndex(existingAssets)
        val resultMap = HashMap<String, DuplicateMatchInfo>(drafts.size)

        val fallbackDate = if (document.importDate > 0) {
            LocalDate.ofEpochDay(Math.floorDiv(document.importDate, MILLIS_PER_DAY))
        } else {
            LocalDate.now()
        }

        val prepared = ArrayList<PreparedDraft>(drafts.size)
        for (draft in drafts) {
            val resolvedTradeDate = draft.tradeDate?.let {
                LocalDate.ofEpochDay(Math.floorDiv(it, MILLIS_PER_DAY))
            } ?: fallbackDate

            val isin = draft.isin?.takeIf(String::isNotBlank)
                ?: AssetNameNormalizer.extractIsin(draft.assetName)
            val ticker = draft.ticker?.takeIf(String::isNotBlank)

            val matchedAsset = assetIndex.findMatch(
                rawName = draft.assetName,
                identifier = isin ?: ticker,
                isin = isin,
                ticker = ticker,
            )
            val assetId = matchedAsset?.id ?: when {
                isin != null -> isin.uppercase()
                ticker != null -> ticker.uppercase()
                else -> AssetNameNormalizer.normalize(draft.assetName)
            }
            val draftAssetClass = try {
                AssetClass.valueOf(draft.assetClass.trim().uppercase())
            } catch (_: Exception) {
                document.getAssetClassEnum()
            }
            val fingerprint = createTransactionFingerprint(
                assetId = assetId,
                isin = isin,
                ticker = ticker,
                assetName = draft.assetName,
                transactionType = draft.transactionType,
                quantity = draft.quantity,
                unitPrice = draft.price,
                netAmount = draft.netAmount,
                date = resolvedTradeDate.toEpochDay(),
                broker = draft.broker,
            )

            prepared.add(
                PreparedDraft(
                    draft = draft,
                    resolvedTradeDate = resolvedTradeDate,
                    isin = isin,
                    ticker = ticker,
                    assetId = assetId,
                    draftAssetClass = draftAssetClass,
                    fingerprint = fingerprint,
                    matchedAssetBeforeInsert = matchedAsset,
                ),
            )
        }

        // Pass 1: Batched indexed fingerprint lookup (WHERE fingerprint IN (:fingerprints))
        val existingByFingerprint = HashMap<String, TransactionEntity>(prepared.size)
        prepared.asSequence()
            .map { it.fingerprint }
            .distinct()
            .chunked(FINGERPRINT_QUERY_BATCH_SIZE)
            .forEach { batch ->
                for (tx in transactionDao.getByFingerprints(batch)) {
                    if (tx.documentId != documentId) {
                        existingByFingerprint.putIfAbsent(tx.fingerprint, tx)
                    }
                }
            }

        val remainingForSemanticCheck = ArrayList<PreparedDraft>()
        for (item in prepared) {
            val fingerprintMatch = existingByFingerprint[item.fingerprint]
            if (fingerprintMatch != null) {
                resultMap[item.draft.id] = DuplicateMatchInfo(
                    isDuplicate = true,
                    matchingTransactionId = fingerprintMatch.id,
                    matchingTradeDate = fingerprintMatch.tradeDate.toString(),
                    reason = "Exact match with existing transaction on ${fingerprintMatch.tradeDate}",
                )
            } else {
                remainingForSemanticCheck.add(item)
            }
        }

        if (remainingForSemanticCheck.isEmpty()) {
            return@withContext resultMap
        }

        // Pass 2: Batched asset-scoped lookup only for non-fingerprint-matched drafts
        val candidateByAssetAndDate = HashMap<Pair<String, LocalDate>, MutableList<TransactionEntity>>()
        remainingForSemanticCheck.asSequence()
            .map { it.assetId }
            .filter(String::isNotBlank)
            .distinct()
            .chunked(FINGERPRINT_QUERY_BATCH_SIZE)
            .forEach { assetBatch ->
                for (tx in transactionDao.getByAssetIds(assetBatch)) {
                    if (tx.documentId != documentId) {
                        candidateByAssetAndDate
                            .getOrPut(tx.assetId to tx.tradeDate) { mutableListOf() }
                            .add(tx)
                    }
                }
            }

        for (item in remainingForSemanticCheck) {
            val draft = item.draft
            val candidates = candidateByAssetAndDate[item.assetId to item.resolvedTradeDate].orEmpty()
            if (candidates.isEmpty()) continue

            val draftQty = parseCleanBigDecimal(draft.quantity)
            val draftNet = parseCleanBigDecimal(draft.netAmount)
            val draftPrice = parseCleanBigDecimal(draft.price)

            val contentMatch = candidates.firstOrNull { ex ->
                val typeMatches = ex.type.name.equals(draft.transactionType, ignoreCase = true)
                if (!typeMatches) return@firstOrNull false

                val brokerMatches = if (draft.broker.isNullOrBlank() && ex.broker.isNullOrBlank()) {
                    true
                } else {
                    ex.broker?.trim().equals(draft.broker?.trim(), ignoreCase = true)
                }
                if (!brokerMatches) return@firstOrNull false

                val qtyMatches = draftQty != null && ex.quantity != null &&
                    ex.quantity.compareTo(draftQty) == 0

                val amountMatches = if (draftNet != null) {
                    ex.netAmount.subtract(draftNet).abs() <= BigDecimal("0.05")
                } else false

                val priceMatches = if (draftPrice != null && ex.unitPrice != null) {
                    ex.unitPrice.subtract(draftPrice).abs() <= BigDecimal("0.05")
                } else false

                qtyMatches && (amountMatches || priceMatches)
            }

            if (contentMatch != null) {
                resultMap[draft.id] = DuplicateMatchInfo(
                    isDuplicate = true,
                    matchingTransactionId = contentMatch.id,
                    matchingTradeDate = contentMatch.tradeDate.toString(),
                    reason = "Identical holding in portfolio: ${contentMatch.type} ${contentMatch.quantity} @ ₹${contentMatch.unitPrice} (${contentMatch.tradeDate})",
                )
            }
        }

        resultMap
    }

    override suspend fun updateDocumentStatus(documentId: String, status: String) =
        withContext(Dispatchers.IO) {
            documentDao.updateStatus(documentId, status)
        }

    override suspend fun updateCarryOverContext(
        documentId: String,
        context: AiCarryOverContext,
    ) = withContext(Dispatchers.IO) {
        documentDao.updateCarryOverContext(
            id = documentId,
            carryOverContextJson = context.toJson().takeUnless { context.isEmpty() },
        )
    }

    override suspend fun updateDocumentAssetClass(
        documentId: String,
        assetClass: AssetClass,
    ) = withContext(Dispatchers.IO) {
        documentDao.updateAssetClass(documentId, assetClass.name)
    }

    override suspend fun rollbackDocumentImport(
        documentId: String,
    ): RollbackImportResult = withContext(Dispatchers.IO) {
        inDbTransaction {
            val document = requireNotNull(documentDao.getDocumentById(documentId)) {
                "Document not found: $documentId"
            }
            val committedTxns = transactionDao.getByDocumentIdOnce(documentId)
            val affectedAssetIds = committedTxns.map { it.assetId }.distinct()

            if (committedTxns.isNotEmpty()) {
                transactionDao.deleteByDocumentId(documentId)
            }

            val lotDao = runCatching { database.lotDao() }.getOrNull()
            for (assetId in affectedAssetIds) {
                val remainingTxns = transactionDao.getByAssetIdOnce(assetId)
                val fifoResult = FifoCostBasisCalculator.calculate(assetId, remainingTxns)

                lotDao?.deleteByAssetId(assetId)
                if (fifoResult.lots.isNotEmpty()) {
                    lotDao?.insertAll(fifoResult.lots)
                }

                database.assetDao().updateAggregates(
                    assetId = assetId,
                    qty = fifoResult.totalQuantity,
                    invested = fifoResult.totalInvested,
                    avgCost = fifoResult.averageCost,
                    realizedPnL = fifoResult.realizedPnL,
                    timestamp = System.currentTimeMillis(),
                )
                val currentAsset = database.assetDao().getById(assetId)
                if (currentAsset != null && currentAsset.status != fifoResult.status) {
                    database.assetDao().update(currentAsset.copy(status = fifoResult.status))
                }
            }

            draftTransactionDao.deleteByDocumentId(documentId)

            val extension = document.fileName.substringAfterLast('.', "").lowercase()
            val hasText = (extractedTextStore != null &&
                (document.extractedTextPath != null || extractedTextStore.hasText(documentId)) &&
                extractedTextStore.hasText(documentId)) ||
                (!document.extractedText.isNullOrBlank() && document.extractedText != PASSWORD_PROTECTED_MARKER)
            val restoredStatus = when {
                extension in STRUCTURED_EXTENSIONS -> DocumentStatus.READY_FOR_MAPPING.name
                hasText -> DocumentStatus.OCR_COMPLETE.name
                else -> DocumentStatus.PENDING_OCR.name
            }

            documentDao.updateAiBatchProgress(
                id = documentId,
                processedPages = 0,
                pageCount = document.pageCount,
                status = restoredStatus,
                carryOverContextJson = null,
                error = null,
            )

            RollbackImportResult(
                deletedTransactionCount = committedTxns.size,
                recalculatedAssetCount = affectedAssetIds.size,
            )
        }
    }

    private enum class AiRunMode {
        SINGLE_BATCH,
        AUTO_UNTIL_REVIEW_OR_END,
        ALL_PAGES,
    }

    override suspend fun processDocumentWithAi(documentId: String) {
        processDocumentWithAi(documentId, onProgress = null)
    }

    override suspend fun processDocumentWithAi(
        documentId: String,
        onProgress: (suspend (stage: String, current: Int, total: Int) -> Unit)?,
    ) {
        runAiBatches(documentId, AiRunMode.ALL_PAGES, onProgress)
    }

    override suspend fun processNextAiBatch(
        documentId: String,
        onProgress: (suspend (stage: String, current: Int, total: Int) -> Unit)?,
    ): AiBatchProcessResult = runAiBatches(documentId, AiRunMode.SINGLE_BATCH, onProgress)

    override suspend fun autoProcessRemainingAiBatches(
        documentId: String,
        onProgress: (suspend (stage: String, current: Int, total: Int) -> Unit)?,
    ): AiBatchProcessResult = runAiBatches(documentId, AiRunMode.AUTO_UNTIL_REVIEW_OR_END, onProgress)

    @OptIn(ExperimentalSerializationApi::class)
    private suspend fun runAiBatches(
        documentId: String,
        mode: AiRunMode,
        onProgress: (suspend (stage: String, current: Int, total: Int) -> Unit)?,
    ): AiBatchProcessResult = withContext(Dispatchers.IO) {
        val document = requireNotNull(documentDao.getDocumentById(documentId)) {
            "Document not found: $documentId"
        }
        val allowedStatuses = setOf(
            DocumentStatus.OCR_COMPLETE.name,
            DocumentStatus.AI_PAUSED.name,
            DocumentStatus.AI_COMPLETE.name,
            DocumentStatus.PROCESSING.name,
            DocumentStatus.FAILED.name,
        )
        require(document.status in allowedStatuses) {
            "Document text is not ready for AI processing."
        }

        val store = extractedTextStore
        val hasSidecarText = store != null &&
            (document.extractedTextPath != null || store.hasText(documentId)) &&
            store.hasText(documentId)
        val legacyText = document.extractedText.orEmpty().trim()
        val hasLegacyText = legacyText.isNotBlank() && legacyText != PASSWORD_PROTECTED_MARKER

        if (!hasSidecarText && !hasLegacyText) {
            throw EmptyExtractedTextException()
        }

        val apiKey = settingsRepository.getGeminiApiKey().trim()
        if (apiKey.isBlank()) {
            throw MissingApiKeyException()
        }
        val configuredModelName = runCatching { settingsRepository.getGeminiModelName() }
            .getOrNull()
            ?.takeIf(String::isNotBlank)

        val isResuming = document.status in setOf(
            DocumentStatus.AI_PAUSED.name,
            DocumentStatus.PROCESSING.name,
            DocumentStatus.FAILED.name,
        ) && document.processedPages > 0

        val startPage = if (isResuming) (document.processedPages + 1).coerceAtLeast(1) else 1

        val priorDrafts = if (isResuming) {
            draftTransactionDao.getByDocumentIdOnce(documentId)
        } else {
            emptyList()
        }
        var totalExistingDraftCount = priorDrafts.size
        val boundarySignatures = priorDrafts
            .filter { it.sourcePage == null || it.sourcePage >= (startPage - 1) }
            .mapTo(HashSet()) { buildDraftSignature(it) }

        var activeCarryOver = AiCarryOverContext.fromJson(document.carryOverContextJson)
            ?: AiCarryOverContext()

        suspend fun invokeAiChunk(
            chunkText: String,
            priorContext: AiCarryOverContext,
            pagesInChunk: List<Pair<Int, String>>,
            depth: Int,
        ): AiExtractionBatchResult {
            currentCoroutineContext().ensureActive()
            val batchResult: AiExtractionBatchResult = try {
                val res = when (aiProvider) {
                    is ContextualAiProvider -> aiProvider.extractTransactionsWithContext(
                        rawText = chunkText,
                        apiKey = apiKey,
                        documentId = documentId,
                        priorContext = priorContext,
                        modelName = configuredModelName,
                    )
                    is TruncationAwareAiProvider -> {
                        val promptText = buildPromptWithContext(chunkText, priorContext)
                        aiProvider.extractTransactionsDetailed(
                            rawText = promptText,
                            apiKey = apiKey,
                            documentId = documentId,
                        )
                    }
                    else -> {
                        val promptText = buildPromptWithContext(chunkText, priorContext)
                        aiProvider.extractTransactions(
                            rawText = promptText,
                            apiKey = apiKey,
                            documentId = documentId,
                        ).map { AiExtractionBatchResult(it, wasTruncated = false) }
                    }
                }
                res.getOrThrow()
            } catch (e: MissingFieldException) {
                val msg = "AI model is unavailable. Please update the app or check your API key."
                documentDao.updateStatusAndError(documentId, DocumentStatus.FAILED.name, msg)
                throw AiExtractionException(msg, e)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (e.cause is MissingFieldException) {
                    val msg = "AI model is unavailable. Please update the app or check your API key."
                    documentDao.updateStatusAndError(documentId, DocumentStatus.FAILED.name, msg)
                    throw AiExtractionException(msg, e)
                }
                val msg = e.localizedMessage ?: "Unknown AI error"
                documentDao.updateStatusAndError(documentId, DocumentStatus.FAILED.name, msg)
                throw AiExtractionException(msg, e)
            }

            if (batchResult.wasTruncated && depth < MAX_TRUNCATION_SPLIT_DEPTH) {
                if (pagesInChunk.size > 1) {
                    val mid = pagesInChunk.size / 2
                    val firstPages = pagesInChunk.subList(0, mid)
                    val secondPages = pagesInChunk.subList(mid, pagesInChunk.size)
                    val firstText = formatBatchPages(firstPages)
                    val firstRes = if (firstText.isNotBlank()) {
                        invokeAiChunk(firstText, priorContext, firstPages, depth + 1)
                    } else {
                        AiExtractionBatchResult(emptyList(), false, priorContext)
                    }
                    val midContext = mergeCarryOverContext(priorContext, firstRes.carryOverContext)
                    val secondText = formatBatchPages(secondPages)
                    val secondRes = if (secondText.isNotBlank()) {
                        invokeAiChunk(secondText, midContext, secondPages, depth + 1)
                    } else {
                        AiExtractionBatchResult(emptyList(), false, midContext)
                    }
                    return AiExtractionBatchResult(
                        drafts = firstRes.drafts + secondRes.drafts,
                        wasTruncated = false,
                        carryOverContext = mergeCarryOverContext(midContext, secondRes.carryOverContext),
                    )
                }

                val lines = chunkText.lineSequence().filter { it.isNotBlank() }.toList()
                if (lines.size > MIN_LINES_TO_SPLIT_ON_TRUNCATION) {
                    val mid = lines.size / 2
                    val firstHalf = lines.subList(0, mid).joinToString("\n")
                    val secondHalf = lines.subList(mid, lines.size).joinToString("\n")
                    val firstRes = if (firstHalf.isNotBlank()) {
                        invokeAiChunk(firstHalf, priorContext, emptyList(), depth + 1)
                    } else {
                        AiExtractionBatchResult(emptyList(), false, priorContext)
                    }
                    val midContext = mergeCarryOverContext(priorContext, firstRes.carryOverContext)
                    val secondRes = if (secondHalf.isNotBlank()) {
                        invokeAiChunk(secondHalf, midContext, emptyList(), depth + 1)
                    } else {
                        AiExtractionBatchResult(emptyList(), false, midContext)
                    }
                    return AiExtractionBatchResult(
                        drafts = firstRes.drafts + secondRes.drafts,
                        wasTruncated = false,
                        carryOverContext = mergeCarryOverContext(midContext, secondRes.carryOverContext),
                    )
                }
            }
            return batchResult
        }

        fun normalizeAndFilterBatchDrafts(
            rawDrafts: List<DraftTransactionEntity>,
            priorContext: AiCarryOverContext,
            defaultSourcePage: Int?,
            batchEndPage: Int?,
        ): List<DraftTransactionEntity> {
            val newDrafts = ArrayList<DraftTransactionEntity>(rawDrafts.size)
            val newEndBoundarySignatures = HashSet<String>()
            for (rawDraft in rawDrafts) {
                val adjustedClass = if (rawDraft.assetClass == "STOCK" && document.assetClass != "STOCK") {
                    document.assetClass
                } else {
                    rawDraft.assetClass
                }
                val adjustedBroker = rawDraft.broker?.takeIf(String::isNotBlank)
                    ?: priorContext.activeBroker.takeIf(String::isNotBlank)
                val adjustedIsin = rawDraft.isin?.takeIf(String::isNotBlank)
                    ?: priorContext.activeIsin.takeIf { isin ->
                        isin.isNotBlank() && (
                            rawDraft.assetName.contains(priorContext.activeScheme, ignoreCase = true) ||
                                priorContext.activeScheme.contains(rawDraft.assetName, ignoreCase = true) ||
                                rawDraft.assetName.equals("Mutual Fund", ignoreCase = true)
                            )
                    }
                val adjustedAssetName = if (
                    rawDraft.assetName.equals("Mutual Fund", ignoreCase = true) &&
                    priorContext.activeScheme.isNotBlank()
                ) {
                    priorContext.activeScheme
                } else {
                    rawDraft.assetName
                }

                val adjusted = rawDraft.copy(
                    assetName = adjustedAssetName,
                    assetClass = adjustedClass,
                    broker = adjustedBroker,
                    isin = adjustedIsin,
                    sourcePage = rawDraft.sourcePage ?: defaultSourcePage,
                )
                val sig = buildDraftSignature(adjusted)
                if (sig !in boundarySignatures) {
                    newDrafts.add(adjusted)
                    if (batchEndPage == null || adjusted.sourcePage == null || adjusted.sourcePage >= batchEndPage) {
                        newEndBoundarySignatures.add(sig)
                    }
                }
            }
            boundarySignatures.clear()
            boundarySignatures.addAll(newEndBoundarySignatures)
            return newDrafts
        }

        if (hasSidecarText) {
            val activeStore = checkNotNull(store)
            val totalPages = document.pageCount.takeIf { it > 0 }
                ?: activeStore.getMaxPageNumber(documentId).coerceAtLeast(1)

            if (!isResuming) {
                documentDao.updateExtractionProgress(
                    id = documentId,
                    processedPages = 0,
                    pageCount = totalPages,
                    status = DocumentStatus.PROCESSING.name,
                )
            }

            if (isResuming && activeCarryOver.isEmpty()) {
                activeCarryOver = bootstrapCarryOverContextFromPriorPages(
                    store = activeStore,
                    documentId = documentId,
                    untilPage = startPage,
                    priorDrafts = priorDrafts,
                )
            }

            var currentPage = startPage
            var lastBatchStart = startPage.coerceAtMost(totalPages)
            var lastBatchEnd = document.processedPages.coerceAtMost(totalPages)
            var accumulatedNewDrafts = 0
            var lastFlaggedCount = 0

            while (currentPage <= totalPages) {
                currentCoroutineContext().ensureActive()
                val lookaheadPages = activeStore.readPagesRange(
                    documentId = documentId,
                    pageRange = currentPage..minOf(currentPage + 6, totalPages),
                )
                val batchEndPage = selectSmartBatchEndPage(
                    batchStartPage = currentPage,
                    totalPages = totalPages,
                    pagesByNumber = lookaheadPages,
                )
                val batchPages = (currentPage..batchEndPage).map { pageNum ->
                    pageNum to lookaheadPages[pageNum].orEmpty()
                }
                val batchText = formatBatchPages(batchPages)

                lastBatchStart = currentPage
                lastBatchEnd = batchEndPage

                val newDrafts: List<DraftTransactionEntity>
                if (batchText.isBlank()) {
                    newDrafts = emptyList()
                    activeCarryOver = activeCarryOver.copy(incompleteTailText = "")
                } else {
                    val aiResult = invokeAiChunk(
                        chunkText = batchText,
                        priorContext = activeCarryOver,
                        pagesInChunk = batchPages,
                        depth = 0,
                    )
                    newDrafts = normalizeAndFilterBatchDrafts(
                        rawDrafts = aiResult.drafts,
                        priorContext = activeCarryOver,
                        defaultSourcePage = currentPage,
                        batchEndPage = batchEndPage,
                    )
                    activeCarryOver = mergeCarryOverContext(activeCarryOver, aiResult.carryOverContext)
                }

                if (newDrafts.isNotEmpty()) {
                    newDrafts.chunked(INSERT_BATCH_SIZE).forEach { batch ->
                        draftTransactionDao.insertAll(batch)
                    }
                    accumulatedNewDrafts += newDrafts.size
                    totalExistingDraftCount += newDrafts.size
                }

                val contextJson = activeCarryOver.toJson().takeUnless { activeCarryOver.isEmpty() }
                documentDao.updateExtractionProgress(
                    id = documentId,
                    processedPages = batchEndPage,
                    pageCount = totalPages,
                    status = DocumentStatus.PROCESSING.name,
                )
                documentDao.updateCarryOverContext(documentId, contextJson)
                onProgress?.invoke("AI_EXTRACT", batchEndPage, totalPages)

                lastFlaggedCount = newDrafts.count(::isDraftFlaggedForReview)
                val finishedAll = batchEndPage >= totalPages
                currentPage = batchEndPage + 1

                if (!finishedAll && mode == AiRunMode.SINGLE_BATCH) {
                    documentDao.updateAiBatchProgress(
                        id = documentId,
                        processedPages = batchEndPage,
                        pageCount = totalPages,
                        status = DocumentStatus.AI_PAUSED.name,
                        carryOverContextJson = contextJson,
                        error = null,
                    )
                    return@withContext AiBatchProcessResult(
                        startPage = lastBatchStart,
                        endPage = batchEndPage,
                        totalPages = totalPages,
                        newDraftsCount = accumulatedNewDrafts,
                        flaggedDraftCount = lastFlaggedCount,
                        carryOverContext = activeCarryOver,
                        stopReason = AiBatchStopReason.PAUSED_AFTER_BATCH,
                    )
                }

                if (!finishedAll && mode == AiRunMode.AUTO_UNTIL_REVIEW_OR_END && lastFlaggedCount > 0) {
                    val pauseReason =
                        "Paused at page $batchEndPage of $totalPages: $lastFlaggedCount row(s) flagged for review (low confidence or amount mismatch)."
                    documentDao.updateAiBatchProgress(
                        id = documentId,
                        processedPages = batchEndPage,
                        pageCount = totalPages,
                        status = DocumentStatus.AI_PAUSED.name,
                        carryOverContextJson = contextJson,
                        error = pauseReason,
                    )
                    return@withContext AiBatchProcessResult(
                        startPage = lastBatchStart,
                        endPage = batchEndPage,
                        totalPages = totalPages,
                        newDraftsCount = accumulatedNewDrafts,
                        flaggedDraftCount = lastFlaggedCount,
                        carryOverContext = activeCarryOver,
                        stopReason = AiBatchStopReason.AUTO_PAUSED_NEEDS_REVIEW,
                        message = pauseReason,
                    )
                }
            }

            if (totalExistingDraftCount == 0) {
                throw NoFinancialDataException()
            }

            documentDao.updateStatus(documentId, DocumentStatus.AI_COMPLETE.name)
            if (document.extractionError != null) {
                documentDao.updateStatusAndError(documentId, DocumentStatus.AI_COMPLETE.name, null)
            }
            return@withContext AiBatchProcessResult(
                startPage = lastBatchStart,
                endPage = totalPages,
                totalPages = totalPages,
                newDraftsCount = accumulatedNewDrafts,
                flaggedDraftCount = lastFlaggedCount,
                carryOverContext = activeCarryOver,
                stopReason = AiBatchStopReason.COMPLETED_ALL_PAGES,
            )
        } else {
            val nonEmptyLineCount = legacyText.lineSequence().count { it.isNotBlank() }
            val chunks = if (legacyText.length <= AI_CHUNK_SIZE && nonEmptyLineCount <= AI_CHUNK_MAX_LINES) {
                listOf(legacyText)
            } else {
                splitIntoLineBoundedChunks(legacyText, AI_CHUNK_SIZE, AI_CHUNK_MAX_LINES)
            }
            require(chunks.isNotEmpty()) { "No extracted text is available to process." }

            var accumulatedNewDrafts = 0
            var lastFlaggedCount = 0
            for (chunk in chunks) {
                val aiResult = invokeAiChunk(
                    chunkText = chunk,
                    priorContext = activeCarryOver,
                    pagesInChunk = emptyList(),
                    depth = 0,
                )
                val newDrafts = normalizeAndFilterBatchDrafts(
                    rawDrafts = aiResult.drafts,
                    priorContext = activeCarryOver,
                    defaultSourcePage = null,
                    batchEndPage = null,
                )
                activeCarryOver = mergeCarryOverContext(activeCarryOver, aiResult.carryOverContext)
                if (newDrafts.isNotEmpty()) {
                    newDrafts.chunked(INSERT_BATCH_SIZE).forEach { batch ->
                        draftTransactionDao.insertAll(batch)
                    }
                    accumulatedNewDrafts += newDrafts.size
                    totalExistingDraftCount += newDrafts.size
                    lastFlaggedCount += newDrafts.count(::isDraftFlaggedForReview)
                }
            }

            if (totalExistingDraftCount == 0) {
                throw NoFinancialDataException()
            }

            documentDao.updateStatus(documentId, DocumentStatus.AI_COMPLETE.name)
            if (document.extractionError != null) {
                documentDao.updateStatusAndError(documentId, DocumentStatus.AI_COMPLETE.name, null)
            }
            return@withContext AiBatchProcessResult(
                startPage = 1,
                endPage = 1,
                totalPages = 1,
                newDraftsCount = accumulatedNewDrafts,
                flaggedDraftCount = lastFlaggedCount,
                carryOverContext = activeCarryOver,
                stopReason = AiBatchStopReason.COMPLETED_ALL_PAGES,
            )
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
            val textSample = extractedTextStore?.readSample(documentId, 64_000)
                ?.takeIf(String::isNotBlank)
                ?: document.extractedText.orEmpty()
            require(textSample.isNotBlank()) {
                "No extracted text is available to process."
            }
            documentDao.updateStatus(documentId, DocumentStatus.AI_COMPLETE.name)
            val textChunks = splitIntoSemanticChunks(textSample, AI_CHUNK_SIZE)
            require(textChunks.isNotEmpty()) { "No extracted text is available to process." }
            textChunks.forEach { kotlinx.coroutines.yield() }
            insertDraftTransactionsIfEmpty(documentId, mockAiDraftTransactions(document))
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

    override suspend fun attachExtractedCsv(
        documentId: String,
        csvFile: File,
    ) = withContext(Dispatchers.IO) {
        val doc = requireNotNull(documentDao.getDocumentById(documentId)) {
            "Document not found: $documentId"
        }
        val updated = doc.copy(
            localPath = csvFile.absolutePath,
            status = DocumentStatus.READY_FOR_MAPPING.name,
        )
        documentDao.update(updated)
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

    override suspend fun ingestFile(
        uri: Uri,
        context: Context,
    ): DocumentEntity = ingestFile(uri, context, AssetClass.STOCK)

    override suspend fun ingestFile(
        uri: Uri,
        context: Context,
        assetClass: AssetClass,
    ): DocumentEntity =
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

            val baseFilesDir = context.filesDir ?: File(System.getProperty("java.io.tmpdir") ?: ".")
            val documentsDirectory = File(baseFilesDir, DOCUMENTS_DIRECTORY)
            if (!documentsDirectory.exists() && !documentsDirectory.mkdirs()) {
                throw IOException("Unable to create the private documents directory.")
            }

            val tempFile = File(documentsDirectory, "tmp_${UUID.randomUUID()}.$extension")
            val destination = File(documentsDirectory, "${UUID.randomUUID()}.$extension")
            var insertedDocument: DocumentEntity? = null

            try {
                // Single-pass stream: compute SHA-256 hash and exact byte count while writing to temp file
                val digest = MessageDigest.getInstance("SHA-256")
                var copiedSize = 0L
                val inputStream = resolver.openInputStream(uri)
                    ?: throw IOException("Unable to read the selected file.")
                DigestInputStream(inputStream, digest).use { digestInput ->
                    FileOutputStream(tempFile).use { output ->
                        val buffer = ByteArray(INGEST_BUFFER_SIZE)
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val bytesRead = digestInput.read(buffer)
                            if (bytesRead < 0) break
                            output.write(buffer, 0, bytesRead)
                            copiedSize += bytesRead
                        }
                        output.flush()
                    }
                }
                val hash = digest.digest().toLowerHex()

                if (documentDao.getDocumentByHash(hash) != null) {
                    tempFile.delete()
                    throw DuplicateDocumentException(hash)
                }

                if (!tempFile.renameTo(destination)) {
                    tempFile.copyTo(destination, overwrite = true)
                    tempFile.delete()
                }

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
                    assetClass = assetClass.name,
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

                if (importScheduler != null) {
                    importScheduler.enqueueLocalExtract(document.id, document.fileSize)
                } else {
                    applicationScope.launch {
                        processFileLocally(document, destination, password = null)
                    }
                }
                document
            } catch (exception: Exception) {
                if (tempFile.exists()) {
                    tempFile.delete()
                }
                insertedDocument?.let {
                    extractedTextStore?.delete(it.id)
                    documentDao.deleteById(it.id)
                }
                if (destination.exists() && !destination.delete()) {
                    exception.addSuppressed(
                        IOException("Unable to remove the incomplete imported file."),
                    )
                }
                throw exception
            }
        }

    override suspend fun unlockAndProcessPdf(
        documentId: String,
        password: String,
    ): Result<Unit> = withContext(Dispatchers.IO) {
        val document = documentDao.getDocumentById(documentId)
            ?: return@withContext Result.failure(IllegalArgumentException("Document not found: $documentId"))
        val file = File(document.localPath)
        if (!file.exists()) {
            return@withContext Result.failure(IllegalArgumentException("File does not exist: ${document.localPath}"))
        }

        processFileLocally(document, file, password.trim())
    }

    override suspend fun runLocalExtraction(
        documentId: String,
        password: String?,
        onProgress: (suspend (stage: String, current: Int, total: Int) -> Unit)?,
    ): Result<Unit> = withContext(Dispatchers.IO) {
        val document = documentDao.getDocumentById(documentId)
            ?: return@withContext Result.failure(IllegalArgumentException("Document not found: $documentId"))
        val file = File(document.localPath)
        if (!file.exists()) {
            val error = "Source file is missing."
            documentDao.updateExtractionFileResult(
                id = documentId,
                extractedTextPath = null,
                previewText = null,
                pageCount = 0,
                processedPages = 0,
                status = DocumentStatus.OCR_FAILED.name,
                error = error,
            )
            return@withContext Result.failure(IOException(error))
        }
        processFileLocally(document, file, password, onProgress)
    }

    override suspend fun retryLocalExtraction(documentId: String) = withContext(Dispatchers.IO) {
        val document = documentDao.getDocumentById(documentId) ?: return@withContext
        val file = File(document.localPath)
        if (!file.exists()) {
            documentDao.updateStatusAndError(
                id = documentId,
                status = DocumentStatus.OCR_FAILED.name,
                error = "Source file no longer exists on device.",
            )
            return@withContext
        }
        documentDao.updateExtractionProgress(
            id = documentId,
            processedPages = 0,
            pageCount = document.pageCount,
            status = DocumentStatus.PENDING_OCR.name,
        )
        if (importScheduler != null) {
            importScheduler.enqueueLocalExtract(document.id, document.fileSize)
        } else {
            applicationScope.launch {
                processFileLocally(document, file, password = null)
            }
        }
    }

    override suspend fun enqueueAiExtraction(documentId: String) = withContext(Dispatchers.IO) {
        val document = documentDao.getDocumentById(documentId) ?: return@withContext
        if (importScheduler != null) {
            importScheduler.enqueueAiExtract(document.id, document.fileSize)
        } else {
            applicationScope.launch {
                runCatching { processDocumentWithAi(documentId) }
            }
        }
    }

    override suspend fun recoverInterruptedImports() = withContext(Dispatchers.IO) {
        migrateOversizedLegacyTextIfNeeded()
        val stuckDocuments = documentDao.getDocumentsByStatuses(
            listOf(DocumentStatus.PENDING_OCR.name, DocumentStatus.PROCESSING.name),
        )
        for (doc in stuckDocuments) {
            val isActive = importScheduler?.isImportWorkActive(doc.id) == true
            if (isActive) continue

            if (doc.status == DocumentStatus.PENDING_OCR.name) {
                val file = File(doc.localPath)
                if (file.exists() && importScheduler != null) {
                    importScheduler.enqueueLocalExtract(doc.id, doc.fileSize)
                } else {
                    documentDao.updateStatusAndError(
                        id = doc.id,
                        status = DocumentStatus.OCR_FAILED.name,
                        error = "Extraction was interrupted. Tap Retry to try again.",
                    )
                }
            } else if (doc.status == DocumentStatus.PROCESSING.name) {
                val hasText = doc.extractedTextPath != null ||
                    extractedTextStore?.hasText(doc.id) == true ||
                    (!doc.extractedText.isNullOrBlank() && doc.extractedText != PASSWORD_PROTECTED_MARKER)
                if (hasText) {
                    val message = if (doc.processedPages > 0 && doc.pageCount > 0) {
                        "AI extraction paused at page ${doc.processedPages} of ${doc.pageCount}. Tap Resume to continue."
                    } else {
                        "Processing was interrupted. Tap Retry to continue."
                    }
                    documentDao.updateStatusAndError(
                        id = doc.id,
                        status = DocumentStatus.FAILED.name,
                        error = message,
                    )
                } else {
                    documentDao.updateStatusAndError(
                        id = doc.id,
                        status = DocumentStatus.OCR_FAILED.name,
                        error = "Extraction was interrupted. Tap Retry to try again.",
                    )
                }
            }
        }
    }

    override suspend fun deleteDocument(documentId: String) = withContext(Dispatchers.IO) {
        val document = documentDao.getDocumentById(documentId)
        pdfPasswordStore?.clear(documentId)
        extractedTextStore?.delete(documentId)
        if (document != null && document.localPath.isNotBlank()) {
            runCatching { File(document.localPath).delete() }
        }
        draftTransactionDao.deleteByDocumentId(documentId)
        documentDao.deleteById(documentId)
    }

    private suspend fun migrateOversizedLegacyTextIfNeeded() {
        val store = extractedTextStore ?: return
        val legacyIds = runCatching { documentDao.getOversizedLegacyDocumentIds(LEGACY_PREVIEW_MAX_CHARS) }
            .getOrDefault(emptyList())
        for (docId in legacyIds) {
            runCatching {
                val totalLen = documentDao.getLegacyTextLength(docId)
                if (totalLen <= LEGACY_PREVIEW_MAX_CHARS) return@runCatching
                val preview = documentDao.getLegacyTextSlice(docId, 1, LEGACY_PREVIEW_MAX_CHARS).orEmpty()
                store.openWriter(docId).use { writer ->
                    var offset = 1
                    var pageIdx = 1
                    while (offset <= totalLen) {
                        val slice = documentDao.getLegacyTextSlice(docId, offset, LEGACY_SLICE_SIZE).orEmpty()
                        if (slice.isEmpty()) break
                        store.writePage(writer, pageIdx++, slice)
                        offset += slice.length
                    }
                    writer.flush()
                }
                documentDao.markLegacyTextMigrated(docId, store.pathFor(docId), preview)
            }
        }
    }

    private enum class StreamingParserMode {
        CAS,
        BROKER,
    }

    internal suspend fun processFileLocally(
        document: DocumentEntity,
        file: File,
        password: String? = null,
        onProgress: (suspend (stage: String, current: Int, total: Int) -> Unit)? = null,
    ): Result<Unit> {
        var totalPages = 0
        var lastPageNumber = 0
        var ocrLimitExceeded = false
        val previewBuilder = StringBuilder(LEGACY_PREVIEW_MAX_CHARS)
        var hasNonBlankText = false
        val store = extractedTextStore

        return try {
            draftTransactionDao.deleteByDocumentId(document.id)

            val casState = CasParseState()
            val brokerState = BrokerParseState()
            var detectedMode: StreamingParserMode? = null
            val draftBatch = ArrayList<DraftTransactionEntity>(DRAFT_INSERT_BATCH_SIZE)
            var totalDraftsInserted = 0

            suspend fun flushDraftBatch() {
                if (draftBatch.isNotEmpty()) {
                    draftTransactionDao.insertAll(draftBatch.toList())
                    totalDraftsInserted += draftBatch.size
                    draftBatch.clear()
                }
            }

            val sidecarWriter = store?.openWriter(document.id)

            suspend fun handlePage(page: PdfPageContent) {
                currentCoroutineContext().ensureActive()
                lastPageNumber = page.pageNumber
                totalPages = page.totalPages
                if (page.ocrLimitReached) {
                    ocrLimitExceeded = true
                }

                if (sidecarWriter != null) {
                    store?.writePage(sidecarWriter, page.pageNumber, page.text)
                }

                if (previewBuilder.length < LEGACY_PREVIEW_MAX_CHARS && page.text.isNotBlank()) {
                    val remaining = LEGACY_PREVIEW_MAX_CHARS - previewBuilder.length
                    if (previewBuilder.isNotEmpty() && remaining > 1) {
                        previewBuilder.append('\n')
                    }
                    val spaceLeft = LEGACY_PREVIEW_MAX_CHARS - previewBuilder.length
                    if (spaceLeft > 0) {
                        previewBuilder.append(page.text.take(spaceLeft))
                    }
                }

                if (page.text.isNotBlank()) {
                    hasNonBlankText = true
                }

                when (detectedMode) {
                    StreamingParserMode.CAS -> {
                        val pageDrafts = casStatementParser.parsePage(
                            pageText = page.text,
                            pageNumber = page.pageNumber,
                            documentId = document.id,
                            state = casState,
                        )
                        if (pageDrafts.isNotEmpty()) {
                            draftBatch.addAll(pageDrafts)
                        }
                    }
                    StreamingParserMode.BROKER -> {
                        val pageDrafts = brokerStatementParser.parsePage(
                            pageText = page.text,
                            pageNumber = page.pageNumber,
                            documentId = document.id,
                            state = brokerState,
                            defaultAssetClass = document.getAssetClassEnum(),
                        )
                        if (pageDrafts.isNotEmpty()) {
                            draftBatch.addAll(pageDrafts)
                        }
                    }
                    null -> {
                        val casPageDrafts = casStatementParser.parsePage(
                            pageText = page.text,
                            pageNumber = page.pageNumber,
                            documentId = document.id,
                            state = casState,
                        )
                        val brokerPageDrafts = brokerStatementParser.parsePage(
                            pageText = page.text,
                            pageNumber = page.pageNumber,
                            documentId = document.id,
                            state = brokerState,
                            defaultAssetClass = document.getAssetClassEnum(),
                        )
                        if (casPageDrafts.isNotEmpty() && casState.hasSeenCasSignal && !brokerState.hasBrokerSignal) {
                            detectedMode = StreamingParserMode.CAS
                            draftBatch.addAll(casPageDrafts)
                        } else if (brokerPageDrafts.isNotEmpty() && brokerState.hasBrokerSignal) {
                            detectedMode = StreamingParserMode.BROKER
                            draftBatch.addAll(brokerPageDrafts)
                        } else if (casPageDrafts.isNotEmpty() && casState.hasSeenCasSignal) {
                            detectedMode = StreamingParserMode.CAS
                            draftBatch.addAll(casPageDrafts)
                        } else if (brokerPageDrafts.isNotEmpty() && !casState.hasSeenCasSignal) {
                            detectedMode = StreamingParserMode.BROKER
                            draftBatch.addAll(brokerPageDrafts)
                        }
                    }
                }

                if (draftBatch.size >= DRAFT_INSERT_BATCH_SIZE) {
                    flushDraftBatch()
                }

                if (page.pageNumber == 1 || page.pageNumber % PROGRESS_UPDATE_INTERVAL_PAGES == 0 || page.pageNumber == page.totalPages) {
                    documentDao.updateExtractionProgress(
                        id = document.id,
                        processedPages = page.pageNumber,
                        pageCount = page.totalPages,
                        status = DocumentStatus.PENDING_OCR.name,
                    )
                    onProgress?.invoke("LOCAL_EXTRACT", page.pageNumber, page.totalPages)
                }
            }

            val extension = document.fileName.substringAfterLast('.', file.extension).lowercase()
            try {
                if (extension == "pdf" && streamingPdfProcessor != null) {
                    val result = streamingPdfProcessor.processPages(file, password) { page ->
                        handlePage(page)
                    }
                    totalPages = result.totalPages
                    ocrLimitExceeded = result.ocrLimitExceeded
                } else if (extension in IMAGE_EXTENSIONS && streamingPdfProcessor != null) {
                    val text = streamingPdfProcessor.extractImageText(file)
                    handlePage(PdfPageContent(pageNumber = 1, totalPages = 1, text = text, usedOcr = true))
                } else {
                    val text = documentTextExtractor.extractText(file, document.fileName, password)
                    handlePage(PdfPageContent(pageNumber = 1, totalPages = 1, text = text, usedOcr = false))
                }
                sidecarWriter?.flush()
            } finally {
                runCatching { sidecarWriter?.close() }
            }

            flushDraftBatch()

            if (!hasNonBlankText) {
                store?.delete(document.id)
                val emptyMessage = if (ocrLimitExceeded) {
                    "OCR page limit reached without extracting readable text."
                } else {
                    "No text could be extracted from this document."
                }
                documentDao.updateExtractionFileResult(
                    id = document.id,
                    extractedTextPath = null,
                    previewText = null,
                    pageCount = totalPages,
                    processedPages = lastPageNumber,
                    status = DocumentStatus.OCR_FAILED.name,
                    error = emptyMessage,
                )
                return Result.failure(EmptyExtractedTextException(emptyMessage))
            }

            val textPath = store?.pathFor(document.id)
            val previewText = previewBuilder.toString().take(LEGACY_PREVIEW_MAX_CHARS)
            val warningMsg = if (ocrLimitExceeded) {
                "Scanned PDF exceeded ${StreamingPdfProcessor.DEFAULT_MAX_OCR_PAGES}-page OCR cap; partial text extracted."
            } else {
                null
            }

            if (totalDraftsInserted > 0) {
                documentDao.updateExtractionFileResult(
                    id = document.id,
                    extractedTextPath = textPath,
                    previewText = previewText,
                    pageCount = totalPages,
                    processedPages = totalPages,
                    status = DocumentStatus.AI_COMPLETE.name,
                    error = warningMsg,
                )
                return Result.success(Unit)
            }

            // Tier 3: Offline Table Auto-Extraction Pipeline (streaming, no orphaned CSVs)
            val tableDrafts = extractDraftsFromTablesLocally(file, document, password, writeCsv = false)
            if (tableDrafts.isNotEmpty()) {
                tableDrafts.chunked(DRAFT_INSERT_BATCH_SIZE).forEach { batch ->
                    draftTransactionDao.insertAll(batch)
                }
                documentDao.updateExtractionFileResult(
                    id = document.id,
                    extractedTextPath = textPath,
                    previewText = previewText,
                    pageCount = totalPages,
                    processedPages = totalPages,
                    status = DocumentStatus.AI_COMPLETE.name,
                    error = warningMsg,
                )
                return Result.success(Unit)
            }

            documentDao.updateExtractionFileResult(
                id = document.id,
                extractedTextPath = textPath,
                previewText = previewText,
                pageCount = totalPages,
                processedPages = 0,
                status = DocumentStatus.OCR_COMPLETE.name,
                error = warningMsg,
            )
            Result.success(Unit)
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: Exception) {
            val isPassword = exception is InvalidPasswordException ||
                exception.message?.contains("password", ignoreCase = true) == true ||
                exception.cause is InvalidPasswordException

            if (isPassword) {
                store?.delete(document.id)
                documentDao.updateExtractionFileResult(
                    id = document.id,
                    extractedTextPath = null,
                    previewText = PASSWORD_PROTECTED_MARKER,
                    pageCount = 0,
                    processedPages = 0,
                    status = DocumentStatus.OCR_FAILED.name,
                    error = "This PDF is password-protected. Enter password to unlock.",
                )
            } else {
                documentDao.updateExtractionFileResult(
                    id = document.id,
                    extractedTextPath = null,
                    previewText = null,
                    pageCount = totalPages,
                    processedPages = lastPageNumber,
                    status = DocumentStatus.OCR_FAILED.name,
                    error = exception.localizedMessage ?: "Failed to extract document text.",
                )
            }
            Result.failure(exception)
        }
    }

    private suspend fun extractDraftsFromTablesLocally(
        file: File,
        document: DocumentEntity,
        password: String?,
        writeCsv: Boolean = false,
    ): List<DraftTransactionEntity> {
        val pipeline = localTableExtractionPipeline ?: return emptyList()
        return try {
            val tables = pipeline.extractTablesFromDocument(
                file = file,
                fileName = document.fileName,
                password = password,
                writeCsv = writeCsv,
            )
            if (tables.isEmpty()) return emptyList()

            val table = tables.first()
            if (table.rows.size < 2) return emptyList()

            val headerIndex = table.rows.indexOfFirst { row ->
                val line = row.joinToString(" ").lowercase()
                (line.contains("date") || line.contains("time")) &&
                    (line.contains("symbol") || line.contains("scrip") || line.contains("stock") ||
                        line.contains("security") || line.contains("instrument") || line.contains("asset") ||
                        line.contains("description")) &&
                    (line.contains("qty") || line.contains("quantity") || line.contains("shares") ||
                        line.contains("units") || line.contains("price") || line.contains("rate") ||
                        line.contains("amount") || line.contains("value"))
            }

            if (headerIndex >= 0) {
                val headers = table.rows[headerIndex]
                val dataRows = table.rows.drop(headerIndex + 1)
                val structuredTable = StructuredTable(headers, dataRows)

                val dateCol = headers.firstOrNull { it.contains("date", ignoreCase = true) }
                val assetCol = headers.firstOrNull {
                    it.contains("symbol", ignoreCase = true) || it.contains("scrip", ignoreCase = true) ||
                        it.contains("stock", ignoreCase = true) || it.contains("security", ignoreCase = true) ||
                        it.contains("instrument", ignoreCase = true) || it.contains("description", ignoreCase = true)
                }
                val typeCol = headers.firstOrNull {
                    it.contains("type", ignoreCase = true) || it.contains("action", ignoreCase = true) ||
                        it.contains("buy/sell", ignoreCase = true) || it.equals("b/s", ignoreCase = true)
                }
                val qtyCol = headers.firstOrNull {
                    it.contains("qty", ignoreCase = true) || it.contains("quantity", ignoreCase = true) ||
                        it.contains("shares", ignoreCase = true) || it.contains("units", ignoreCase = true)
                }
                val priceCol = headers.firstOrNull {
                    it.contains("price", ignoreCase = true) || it.contains("rate", ignoreCase = true) ||
                        it.contains("nav", ignoreCase = true)
                }
                val amtCol = headers.firstOrNull {
                    it.contains("amount", ignoreCase = true) || it.contains("value", ignoreCase = true) ||
                        it.contains("total", ignoreCase = true)
                }

                if (dateCol != null && assetCol != null && (qtyCol != null || amtCol != null)) {
                    val mapping = ColumnMappingEntity(
                        brokerName = document.fileName.substringBeforeLast('.'),
                        assetColumn = assetCol,
                        typeColumn = typeCol.orEmpty(),
                        quantityColumn = qtyCol.orEmpty(),
                        priceColumn = priceCol.orEmpty(),
                        amountColumn = amtCol.orEmpty(),
                        dateColumn = dateCol,
                        headerRowNumber = headerIndex + 1,
                    )
                    createMappedDrafts(document, structuredTable, mapping)
                } else emptyList()
            } else emptyList()
        } catch (ce: CancellationException) {
            throw ce
        } catch (_: Exception) {
            emptyList()
        }
    }

    private suspend fun persistMappedDrafts(
        document: DocumentEntity,
        mapping: ColumnMappingEntity,
        drafts: List<DraftTransactionEntity>,
        saveMapping: Boolean = true,
    ) {
        require(drafts.isNotEmpty()) { "No transaction rows were found in this file." }
        inDbTransaction {
            if (saveMapping) columnMappingDao.insert(mapping)
            drafts.chunked(DRAFT_INSERT_BATCH_SIZE).forEach { batch ->
                draftTransactionDao.insertAll(batch)
            }
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

            runCatching {
                val rawAssetCell = assetIndex?.let { row.getOrNull(it) }?.trim().orEmpty()
                if (rawAssetCell.lowercase() in FOOTER_ASSET_MARKERS) {
                    return@runCatching null
                }
                val assetName = rawAssetCell.ifBlank {
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

                val tradeDate = dateIndex?.let { index ->
                    parseMappedDate(row.getOrNull(index).orEmpty())
                }

                DraftTransactionEntity(
                    documentId = document.id,
                    assetName = assetName,
                    transactionType = type,
                    quantity = quantity,
                    price = price,
                    netAmount = amount,
                    confidence = 100,
                    tradeDate = tradeDate,
                    assetClass = document.assetClass,
                    broker = mapping.brokerName.trim().takeIf(String::isNotBlank),
                    isin = AssetNameNormalizer.extractIsin(assetName),
                )
            }.getOrNull()
        }
    }

    private fun computeMissingFinancials(
        qty: String?,
        price: String?,
        amount: String?,
    ): Triple<String, String, String> {
        val cleanQty = qty?.trim()?.replace(",", "")?.replace("₹", "")?.trim()?.takeIf(String::isNotBlank)
        val cleanPrice = price?.trim()?.replace(",", "")?.replace("₹", "")?.trim()?.takeIf(String::isNotBlank)
        val cleanAmount = amount?.trim()?.replace(",", "")?.replace("₹", "")?.trim()?.takeIf(String::isNotBlank)

        val qtyBd = cleanQty?.let { runCatching { BigDecimal(it.trim()) }.getOrNull() }
        val priceBd = cleanPrice?.let { runCatching { BigDecimal(it.trim()) }.getOrNull() }
        val amountBd = cleanAmount?.let { runCatching { BigDecimal(it.trim()) }.getOrNull() }

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
        val parsed = runCatching { BigDecimal(normalized.trim()) }.getOrNull()
        require(parsed != null) {
            "A transaction row has an invalid $field value."
        }
        return parsed.toPlainString()
    }

    private fun parseMappedDate(value: String): Long? {
        val date = value.trim().takeIf(String::isNotEmpty) ?: return null
        return DateParser.parseToEpochMilli(date) ?: run {
            throw IllegalArgumentException("A transaction row has an invalid date value: $value")
        }
    }

    internal fun selectSmartBatchEndPage(
        batchStartPage: Int,
        totalPages: Int,
        pagesByNumber: Map<Int, String>,
    ): Int {
        val remaining = totalPages - batchStartPage + 1
        if (remaining <= DEFAULT_AI_BATCH_PAGES) return totalPages

        val p4 = batchStartPage + 3
        val p5 = batchStartPage + 4
        val p6 = (batchStartPage + 5).coerceAtMost(totalPages)

        val text4 = pagesByNumber[p4].orEmpty()
        val text5 = pagesByNumber[p5].orEmpty()
        val text6 = pagesByNumber[p6].orEmpty()
        val nextAfter5 = pagesByNumber[p5 + 1].orEmpty()
        val nextAfter6 = pagesByNumber[p6 + 1].orEmpty()

        if (hasClosingBreak(text5) || (p5 + 1 <= totalPages && startsWithNewSection(nextAfter5))) {
            return p5
        }
        if (hasClosingBreak(text4) || startsWithNewSection(text5)) {
            return p4
        }
        if (p6 > p5 && (hasClosingBreak(text6) || (p6 + 1 <= totalPages && startsWithNewSection(nextAfter6)))) {
            return p6
        }
        if (hasSectionBreakMarker(text4) && !hasSectionBreakMarker(text5)) {
            return p4
        }
        if (p6 > p5 && hasSectionBreakMarker(text6) && !hasSectionBreakMarker(text5)) {
            return p6
        }
        return p5
    }

    private fun hasClosingBreak(text: String): Boolean =
        text.contains("Closing Unit Balance", ignoreCase = true)

    private fun hasSectionBreakMarker(text: String): Boolean =
        text.contains("Closing Unit Balance", ignoreCase = true) ||
            text.contains("Folio No", ignoreCase = true) ||
            text.contains("ISIN:", ignoreCase = true) ||
            text.contains("ISIN :", ignoreCase = true)

    private fun startsWithNewSection(text: String): Boolean {
        val leadingLines = text.lineSequence().map { it.trim() }.filter { it.isNotBlank() }.take(6)
        return leadingLines.any { line ->
            line.contains("Folio No", ignoreCase = true) ||
                line.contains("ISIN:", ignoreCase = true) ||
                line.contains("ISIN :", ignoreCase = true)
        }
    }

    private fun formatBatchPages(pages: List<Pair<Int, String>>): String = buildString {
        for ((pageNum, pageText) in pages) {
            val trimmed = pageText.trim()
            if (trimmed.isNotEmpty()) {
                if (isNotEmpty()) append("\n\n")
                append("--- PAGE ").append(pageNum).append(" ---\n")
                append(trimmed)
            }
        }
    }

    private fun buildPromptWithContext(
        chunkText: String,
        priorContext: AiCarryOverContext,
    ): String {
        val prefix = priorContext.toPromptPrefix()
        return if (prefix.isNotBlank()) "$prefix\n\n$chunkText" else chunkText
    }

    private fun mergeCarryOverContext(
        prior: AiCarryOverContext,
        next: AiCarryOverContext,
    ): AiCarryOverContext {
        if (next.isEmpty()) {
            return prior.copy(incompleteTailText = "")
        }
        val amcChanged = next.activeAmc.isNotBlank() &&
            prior.activeAmc.isNotBlank() &&
            !next.activeAmc.equals(prior.activeAmc, ignoreCase = true)
        val schemeChanged = next.activeScheme.isNotBlank() &&
            prior.activeScheme.isNotBlank() &&
            !next.activeScheme.equals(prior.activeScheme, ignoreCase = true)
        val folioChanged = next.activeFolio.isNotBlank() &&
            prior.activeFolio.isNotBlank() &&
            !next.activeFolio.equals(prior.activeFolio, ignoreCase = true)

        val resolvedAmc = next.activeAmc.ifBlank { prior.activeAmc }
        val resolvedScheme = when {
            next.activeScheme.isNotBlank() -> next.activeScheme
            amcChanged -> ""
            else -> prior.activeScheme
        }
        val resolvedIsin = when {
            next.activeIsin.isNotBlank() -> next.activeIsin
            schemeChanged || amcChanged -> ""
            else -> prior.activeIsin
        }
        val resolvedFolio = when {
            next.activeFolio.isNotBlank() -> next.activeFolio
            amcChanged -> ""
            else -> prior.activeFolio
        }
        val resolvedBroker = when {
            next.activeBroker.isNotBlank() -> next.activeBroker
            folioChanged -> next.activeBroker.ifBlank { prior.activeBroker }
            else -> prior.activeBroker
        }
        return AiCarryOverContext(
            activeAmc = resolvedAmc,
            activeScheme = resolvedScheme,
            activeIsin = resolvedIsin,
            activeFolio = resolvedFolio,
            activeBroker = resolvedBroker,
            activeColumnHeaders = next.activeColumnHeaders.ifEmpty { prior.activeColumnHeaders },
            incompleteTailText = next.incompleteTailText,
        )
    }

    private fun bootstrapCarryOverContextFromPriorPages(
        store: ExtractedTextStore,
        documentId: String,
        untilPage: Int,
        priorDrafts: List<DraftTransactionEntity>,
    ): AiCarryOverContext {
        var amc = ""
        var scheme = ""
        var isin = ""
        var folio = ""
        store.forEachPage(documentId) { pageNumber, pageText ->
            if (pageNumber >= untilPage) return@forEachPage
            for (rawLine in pageText.lineSequence()) {
                val line = rawLine.trim()
                if (line.isBlank()) continue
                val extractedIsin = AssetNameNormalizer.extractIsin(line)
                if (extractedIsin != null) {
                    isin = extractedIsin
                }
                if (line.contains("Folio No", ignoreCase = true)) {
                    folio = line.substringAfter(":", line).trim()
                } else if (line.contains("Mutual Fund", ignoreCase = true) && line.length < 90) {
                    amc = line
                } else if (isContextHeaderLine(line)) {
                    scheme = line
                }
            }
        }
        val lastDraft = priorDrafts.lastOrNull()
        return AiCarryOverContext(
            activeAmc = amc,
            activeScheme = scheme.ifBlank { lastDraft?.assetName.orEmpty() },
            activeIsin = isin.ifBlank { lastDraft?.isin.orEmpty() },
            activeFolio = folio,
            activeBroker = lastDraft?.broker.orEmpty(),
        )
    }

    internal fun isDraftFlaggedForReview(draft: DraftTransactionEntity): Boolean =
        draft.confidence < LOW_CONFIDENCE_REVIEW_THRESHOLD || hasMathMismatch(draft)

    internal fun hasMathMismatch(draft: DraftTransactionEntity): Boolean {
        val type = draft.transactionType.trim().uppercase()
        if (type !in setOf("BUY", "SELL", "TRANSFER_IN", "TRANSFER_OUT")) return false
        val qty = parseCleanBigDecimal(draft.quantity)?.abs() ?: return true
        val price = parseCleanBigDecimal(draft.price)?.abs() ?: return true
        val net = parseCleanBigDecimal(draft.netAmount)?.abs() ?: return true
        if (qty.compareTo(BigDecimal.ZERO) <= 0 ||
            price.compareTo(BigDecimal.ZERO) <= 0 ||
            net.compareTo(BigDecimal.ZERO) <= 0
        ) {
            return false
        }
        val expected = qty.multiply(price)
        val diff = expected.subtract(net).abs()
        val tolerance = maxOf(
            BigDecimal("1.00"),
            expected.multiply(BigDecimal("0.05")),
        )
        return diff > tolerance
    }

    private fun parseCleanBigDecimal(raw: String): BigDecimal? = runCatching {
        BigDecimal(raw.replace(",", "").replace("₹", "").trim())
    }.getOrNull()

    private fun buildDraftSignature(draft: DraftTransactionEntity): String {
        val normQty = parseCleanBigDecimal(draft.quantity)?.stripTrailingZeros()?.toPlainString()
            ?: draft.quantity.replace(",", "").trim()
        val normNet = parseCleanBigDecimal(draft.netAmount)?.setScale(2, RoundingMode.HALF_UP)?.toPlainString()
            ?: draft.netAmount.replace(",", "").trim()
        return listOf(
            AssetNameNormalizer.normalize(draft.assetName),
            draft.transactionType.trim().uppercase(),
            draft.tradeDate?.toString().orEmpty(),
            normQty,
            normNet,
            draft.broker?.trim()?.uppercase().orEmpty(),
            draft.isin?.trim()?.uppercase().orEmpty(),
        ).joinToString("|")
    }

    private fun isContextHeaderLine(line: String): Boolean {
        if (line.length > 160) return false
        val lower = line.lowercase()
        val isCasContext = lower.contains("isin:") ||
            lower.contains("isin :") ||
            lower.contains("folio no") ||
            lower.contains("mutual fund") ||
            lower.contains("fund -")
        if (isCasContext) return true

        val hasDate = lower.contains("date")
        val hasAsset = lower.contains("symbol") || lower.contains("scrip") || lower.contains("stock") ||
            lower.contains("security") || lower.contains("scheme") || lower.contains("particulars")
        val hasValue = lower.contains("qty") || lower.contains("quantity") || lower.contains("units") ||
            lower.contains("amount") || lower.contains("price") || lower.contains("nav")
        return hasDate && hasAsset && hasValue
    }

    internal fun splitIntoLineBoundedChunks(
        text: String,
        maxChars: Int = AI_CHUNK_SIZE,
        maxLines: Int = AI_CHUNK_MAX_LINES,
    ): List<String> {
        val chunks = mutableListOf<String>()
        val chunkBuilder = StringBuilder()
        var lineCount = 0
        var lastContextHeader: String? = null

        for (rawLine in text.lineSequence()) {
            val line = rawLine.trim()
            if (line.isBlank()) continue

            if (chunkBuilder.isNotEmpty() &&
                (lineCount >= maxLines || chunkBuilder.length + line.length + 1 > maxChars)
            ) {
                chunks.add(chunkBuilder.toString().trim())
                chunkBuilder.setLength(0)
                lineCount = 0
                val ctx = lastContextHeader
                if (ctx != null && !line.equals(ctx, ignoreCase = true)) {
                    chunkBuilder.append(ctx).append('\n')
                    lineCount++
                }
            }

            chunkBuilder.append(line).append('\n')
            lineCount++
            if (isContextHeaderLine(line)) {
                lastContextHeader = line
            }
        }
        if (chunkBuilder.isNotEmpty()) {
            chunks.add(chunkBuilder.toString().trim())
        }
        return chunks
    }

    private data class FileMetadata(
        val fileName: String,
        val fileSize: Long?,
    )

    internal fun splitIntoSemanticChunks(
        text: String,
        maxChunkSize: Int = AI_CHUNK_SIZE,
        overlapSize: Int = AI_CHUNK_OVERLAP,
    ): List<String> {
        if (text.length <= maxChunkSize) return listOf(text)

        val chunks = mutableListOf<String>()
        var startIndex = 0

        while (startIndex < text.length) {
            val remaining = text.length - startIndex
            if (remaining <= maxChunkSize) {
                val lastChunk = text.substring(startIndex).trim()
                if (lastChunk.isNotBlank()) chunks.add(lastChunk)
                break
            }

            val targetEnd = startIndex + maxChunkSize
            val minSplitPoint = startIndex + (maxChunkSize / 3)

            val pageDelimiter = "--- PAGE "
            val pageIndex = text.lastIndexOf(pageDelimiter, targetEnd)
            val chosenSplit = when {
                pageIndex > minSplitPoint -> pageIndex
                else -> {
                    val doubleNewline = text.lastIndexOf("\n\n", targetEnd)
                    if (doubleNewline > minSplitPoint) {
                        doubleNewline + 2
                    } else {
                        val singleNewline = text.lastIndexOf('\n', targetEnd)
                        if (singleNewline > minSplitPoint) singleNewline + 1 else targetEnd
                    }
                }
            }

            val chunk = text.substring(startIndex, chosenSplit).trim()
            if (chunk.isNotBlank()) {
                chunks.add(chunk)
            }

            val nextStart = chosenSplit - overlapSize
            if (nextStart <= startIndex) {
                startIndex = chosenSplit
            } else {
                val overlapPage = text.indexOf(pageDelimiter, nextStart)
                if (overlapPage in nextStart until chosenSplit) {
                    startIndex = overlapPage
                } else {
                    val lineStart = text.indexOf('\n', nextStart)
                    if (lineStart in nextStart until chosenSplit) {
                        startIndex = lineStart + 1
                    } else {
                        startIndex = nextStart
                    }
                }
            }
        }

        return chunks.ifEmpty { listOf(text) }
    }

    companion object {
        const val PASSWORD_PROTECTED_MARKER = "[PASSWORD_PROTECTED]"
        const val DOCUMENTS_DIRECTORY = "documents"
        const val UNKNOWN_FILE_NAME = "unknown_file"
        const val DEFAULT_ACCOUNT_ID = "default_account"
        const val AI_CHUNK_SIZE = 7_000
        const val AI_CHUNK_MAX_LINES = 40
        const val AI_CHUNK_OVERLAP = 1_000
        const val INGEST_BUFFER_SIZE = 64 * 1024
        const val DRAFT_INSERT_BATCH_SIZE = 200
        const val INSERT_BATCH_SIZE = 200
        const val FINGERPRINT_QUERY_BATCH_SIZE = 500
        const val PROGRESS_UPDATE_INTERVAL_PAGES = 5
        const val LEGACY_PREVIEW_MAX_CHARS = 500
        const val LEGACY_SLICE_SIZE = 100_000
        const val MAX_TRUNCATION_SPLIT_DEPTH = 2
        const val MIN_LINES_TO_SPLIT_ON_TRUNCATION = 10
        const val DEFAULT_AI_BATCH_PAGES = 5
        const val LOW_CONFIDENCE_REVIEW_THRESHOLD = 80
        const val MILLIS_PER_DAY = 86_400_000L
        val SUPPORTED_EXTENSIONS = setOf("pdf", "csv", "xlsx", "png", "jpg", "jpeg")
        val STRUCTURED_EXTENSIONS = setOf("csv", "xlsx")
        val IMAGE_EXTENSIONS = setOf("png", "jpg", "jpeg")
        private val FOOTER_ASSET_MARKERS = setOf(
            "total", "grand total", "sub total", "subtotal", "disclaimer", "summary",
        )
    }
}

internal suspend fun calculateSha256(inputStream: InputStream): String =
    withContext(Dispatchers.IO) {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(DocumentRepositoryImpl.INGEST_BUFFER_SIZE)
        while (true) {
            val bytesRead = inputStream.read(buffer)
            if (bytesRead < 0) break
            digest.update(buffer, 0, bytesRead)
        }
        digest.digest().toLowerHex()
    }

internal fun createTransactionFingerprint(
    assetName: String = "",
    transactionType: String,
    quantity: String,
    netAmount: String,
    date: Long,
    assetId: String = "",
    isin: String? = null,
    ticker: String? = null,
    unitPrice: String? = null,
    broker: String? = null,
): String {
    val cleanIsin = isin?.trim()?.uppercase()?.takeIf(String::isNotBlank)
        ?: AssetNameNormalizer.extractIsin(assetName)
        ?: AssetNameNormalizer.extractIsin(assetId)

    val cleanTicker = ticker?.trim()?.uppercase()?.takeIf(String::isNotBlank)

    val identifier = when {
        cleanIsin != null -> cleanIsin
        cleanTicker != null -> cleanTicker
        assetId.isNotBlank() -> AssetNameNormalizer.normalize(assetId)
        assetName.isNotBlank() -> AssetNameNormalizer.normalize(assetName)
        else -> ""
    }

    val cleanQty = quantity.replace(",", "").replace("₹", "").trim()
    val normQty = runCatching {
        BigDecimal(cleanQty).stripTrailingZeros().toPlainString()
    }.getOrDefault(cleanQty)

    val cleanAmount = netAmount.replace(",", "").replace("₹", "").trim()
    val normAmount = runCatching {
        BigDecimal(cleanAmount).setScale(2, RoundingMode.HALF_UP).toPlainString()
    }.getOrDefault(cleanAmount)

    val cleanPrice = unitPrice?.replace(",", "")?.replace("₹", "")?.trim()?.takeIf(String::isNotBlank)
    val normPrice = if (cleanPrice != null) {
        runCatching {
            BigDecimal(cleanPrice).setScale(2, RoundingMode.HALF_UP).toPlainString()
        }.getOrDefault(cleanPrice)
    } else {
        val q = runCatching { BigDecimal(cleanQty) }.getOrNull()
        val a = runCatching { BigDecimal(cleanAmount) }.getOrNull()
        if (q != null && a != null && q.compareTo(BigDecimal.ZERO) != 0) {
            a.divide(q, 2, RoundingMode.HALF_UP).toPlainString()
        } else {
            ""
        }
    }

    val normBroker = broker?.trim()?.uppercase().orEmpty()

    val normalized = listOf(
        identifier,
        transactionType.trim().uppercase(),
        normQty,
        normPrice,
        normAmount,
        date.toString(),
        normBroker,
    ).joinToString("|")

    return MessageDigest.getInstance("SHA-256")
        .digest(normalized.toByteArray(Charsets.UTF_8))
        .toLowerHex()
}

private fun ByteArray.toLowerHex(): String =
    joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xff) }

private fun mockAiDraftTransactions(document: DocumentEntity): List<DraftTransactionEntity> {
    val defaultClass = document.assetClass
    return listOf(
        DraftTransactionEntity(
            documentId = document.id,
            assetName = "Reliance Industries",
            transactionType = "BUY",
            quantity = "10",
            price = "2850.00",
            netAmount = "28500.00",
            confidence = 98,
            assetClass = defaultClass,
        ),
        DraftTransactionEntity(
            documentId = document.id,
            assetName = "HDFC Bank",
            transactionType = "BUY",
            quantity = "5",
            price = "1680.00",
            netAmount = "8400.00",
            confidence = 98,
            assetClass = defaultClass,
        ),
        DraftTransactionEntity(
            documentId = document.id,
            assetName = "Tata Consultancy Services",
            transactionType = "SELL",
            quantity = "2",
            price = "4125.00",
            netAmount = "8250.00",
            confidence = 98,
            assetClass = defaultClass,
        ),
        DraftTransactionEntity(
            documentId = document.id,
            assetName = "ICICI Prudential Bluechip Fund",
            transactionType = "BUY",
            quantity = "12.5",
            price = "78.40",
            netAmount = "980.00",
            confidence = 85,
            assetClass = if (defaultClass != "STOCK") defaultClass else "MUTUAL_FUND",
        ),
        DraftTransactionEntity(
            documentId = document.id,
            assetName = "Nippon India Small Cap Fund",
            transactionType = "BUY",
            quantity = "8",
            price = "156.25",
            netAmount = "1250.00",
            confidence = 65,
            assetClass = if (defaultClass != "STOCK") defaultClass else "MUTUAL_FUND",
        ),
    )
}
