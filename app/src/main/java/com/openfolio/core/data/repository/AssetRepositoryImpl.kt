package com.openfolio.core.data.repository

import androidx.room.withTransaction
import com.openfolio.core.data.local.OpenFolioDatabase
import com.openfolio.core.data.local.dao.AssetDao
import com.openfolio.core.data.local.dao.LotDao
import com.openfolio.core.data.local.dao.TransactionDao
import com.openfolio.core.data.local.entity.AccountEntity
import com.openfolio.core.data.local.entity.AccountType
import com.openfolio.core.data.local.entity.AssetClass
import com.openfolio.core.data.local.entity.AssetEntity
import com.openfolio.core.data.local.entity.AssetStatus
import com.openfolio.core.data.local.entity.DocumentEntity
import com.openfolio.core.data.local.entity.DocumentStatus
import com.openfolio.core.data.local.entity.ExtractionStatus
import com.openfolio.core.data.local.entity.TransactionEntity
import com.openfolio.core.data.local.entity.TransactionStatus
import com.openfolio.core.data.local.entity.TransactionType
import com.openfolio.core.domain.calculator.FifoCostBasisCalculator
import com.openfolio.core.domain.calculator.RecalculationResult
import com.openfolio.core.domain.repository.AssetRepository
import com.openfolio.core.domain.repository.DuplicateTransactionException
import com.openfolio.core.utils.AssetNameNormalizer
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext

class AssetRepositoryImpl(
    private val database: OpenFolioDatabase,
    private val assetDao: AssetDao,
    private val transactionDao: TransactionDao,
    private val lotDao: LotDao,
    private val runTransaction: suspend (suspend () -> Unit) -> Unit = { block -> database.withTransaction { block() } },
) : AssetRepository {

    override fun getAllAssets(): Flow<List<AssetEntity>> =
        assetDao.getAll().flowOn(Dispatchers.IO)

    override fun getAssetById(id: String): Flow<AssetEntity?> =
        assetDao.observeById(id).flowOn(Dispatchers.IO)

    override suspend fun getAssetByIdOnce(id: String): AssetEntity? = withContext(Dispatchers.IO) {
        assetDao.getById(id)
    }

    override fun getTransactionsForAsset(assetId: String): Flow<List<TransactionEntity>> =
        transactionDao.getByAssetId(assetId).flowOn(Dispatchers.IO)

    override suspend fun getTransactionsForAssetOnce(assetId: String): List<TransactionEntity> =
        withContext(Dispatchers.IO) {
            transactionDao.getByAssetIdOnce(assetId)
        }

    override suspend fun saveTransaction(transaction: TransactionEntity): Unit = withContext(Dispatchers.IO) {
        runTransaction {
            transactionDao.insert(transaction)
            recalculateInternal(transaction.assetId)
        }
    }

    override suspend fun updateTransaction(transaction: TransactionEntity): Unit = withContext(Dispatchers.IO) {
        runTransaction {
            val existing = transactionDao.getById(transaction.id)
            val oldAssetId = existing?.assetId
            transactionDao.update(transaction)
            recalculateInternal(transaction.assetId)
            if (oldAssetId != null && oldAssetId != transaction.assetId) {
                recalculateInternal(oldAssetId)
            }
        }
    }

    override suspend fun deleteTransaction(transactionId: String): Unit = withContext(Dispatchers.IO) {
        runTransaction {
            val existing = transactionDao.getById(transactionId) ?: return@runTransaction
            val assetId = existing.assetId
            transactionDao.delete(existing)
            recalculateInternal(assetId)
        }
    }

    override suspend fun recalculateAsset(assetId: String): Unit = withContext(Dispatchers.IO) {
        runTransaction {
            recalculateInternal(assetId)
        }
    }

    override suspend fun deleteAsset(assetId: String): Unit = withContext(Dispatchers.IO) {
        runTransaction {
            lotDao.deleteByAssetId(assetId)
            assetDao.deleteById(assetId)
        }
    }

    override suspend fun saveAsset(asset: AssetEntity): Unit = withContext(Dispatchers.IO) {
        runTransaction {
            assetDao.insert(asset)
        }
    }

    override suspend fun updateAsset(asset: AssetEntity): Unit = withContext(Dispatchers.IO) {
        runTransaction {
            assetDao.update(asset)
        }
    }

    override suspend fun updateAssetDisplayName(assetId: String, newDisplayName: String): Unit = withContext(Dispatchers.IO) {
        val trimmed = newDisplayName.trim()
        require(trimmed.isNotBlank()) { "Display name must not be blank" }
        runTransaction {
            assetDao.updateDisplayName(assetId, trimmed)
        }
    }

    override suspend fun recordManualTransaction(
        assetName: String,
        assetClass: AssetClass,
        type: TransactionType,
        tradeDate: LocalDate,
        quantity: BigDecimal,
        unitPrice: BigDecimal,
        netAmount: BigDecimal,
        fees: BigDecimal,
        taxes: BigDecimal,
        notes: String?,
        broker: String?,
        tickerOrIsin: String?,
        selectedAssetId: String?,
    ): TransactionEntity = withContext(Dispatchers.IO) {
        val cleanName = assetName.trim().replace(Regex("\\s+"), " ")
        require(cleanName.length >= 2 && cleanName.any { it.isLetterOrDigit() }) {
            "Enter a valid asset name (at least 2 characters)."
        }
        require(!tradeDate.isAfter(LocalDate.now())) {
            "Trade date cannot be in the future."
        }
        require(
            quantity >= BigDecimal.ZERO &&
                unitPrice >= BigDecimal.ZERO &&
                netAmount >= BigDecimal.ZERO &&
                fees >= BigDecimal.ZERO &&
                taxes >= BigDecimal.ZERO,
        ) {
            "Quantity, price, amount, fees, and taxes must be non-negative."
        }
        if (type in QUANTITY_REQUIRED_TYPES) {
            require(quantity > BigDecimal.ZERO) {
                "Quantity must be greater than zero for ${type.name}."
            }
        }
        if (type == TransactionType.BUY || type == TransactionType.SELL) {
            require(unitPrice > BigDecimal.ZERO || netAmount > BigDecimal.ZERO) {
                "Enter a positive price or total amount for ${type.name}."
            }
        }
        if (type in CASH_AMOUNT_REQUIRED_TYPES) {
            require(netAmount > BigDecimal.ZERO) {
                "Amount must be greater than zero for ${type.name}."
            }
        }

        val cleanIdentifier = tickerOrIsin?.trim()?.uppercase()?.takeIf(String::isNotBlank)
        if (cleanIdentifier != null) {
            val isIsinCandidate = cleanIdentifier.length == 12 && cleanIdentifier.take(2).all { it.isLetter() }
            if (isIsinCandidate) {
                require(AssetNameNormalizer.extractIsin(cleanIdentifier) != null) {
                    "Invalid ISIN format (expected 12 alphanumeric characters ending in a digit)."
                }
            } else {
                require(TICKER_REGEX.matches(cleanIdentifier)) {
                    "Ticker/Symbol must be 1–20 letters, digits, '.', '-', or '_' without spaces."
                }
            }
        }

        val cleanBroker = broker?.trim()?.replace(Regex("\\s+"), " ")?.takeIf(String::isNotBlank)
        val cleanNotes = notes?.trim()?.takeIf(String::isNotBlank)

        var createdTransaction: TransactionEntity? = null
        runTransaction {
            val existingAssets = assetDao.getAllOnce().toMutableList()

            val isin = cleanIdentifier?.let(AssetNameNormalizer::extractIsin)
                ?: AssetNameNormalizer.extractIsin(cleanName)
            val ticker = cleanIdentifier?.takeIf { AssetNameNormalizer.extractIsin(it) == null }

            val explicitAsset = selectedAssetId?.trim()?.takeIf(String::isNotBlank)?.let { id ->
                existingAssets.firstOrNull { it.id == id } ?: assetDao.getById(id)
            }

            if (explicitAsset != null) {
                validateIdentifierCompatibility(explicitAsset, isin, ticker)
            }

            // Deduplicate asset prioritizing explicit selection, then ISIN/Ticker/name match
            val matched = explicitAsset ?: AssetNameNormalizer.findMatchingAsset(
                rawName = cleanName,
                existingAssets = existingAssets,
                identifier = cleanIdentifier,
                isin = isin,
                ticker = ticker,
            )

            val assetId = matched?.id ?: when {
                isin != null -> isin.uppercase()
                ticker != null -> ticker.uppercase()
                else -> AssetNameNormalizer.normalize(cleanName).ifBlank { UUID.randomUUID().toString() }
            }

            // Guard against selling or transferring out more units than held
            if (type == TransactionType.SELL || type == TransactionType.TRANSFER_OUT) {
                if (matched == null) {
                    throw IllegalArgumentException(
                        "No existing holdings found for \"$cleanName\". Record a BUY before recording a ${type.name}.",
                    )
                }
                val assetTxns = transactionDao.getByAssetIdOnce(matched.id)
                val availableQty = if (assetTxns.isNotEmpty()) {
                    val txnsAsOfDate = assetTxns.filter { !it.tradeDate.isAfter(tradeDate) }
                    minOf(
                        matched.totalQuantity,
                        FifoCostBasisCalculator.calculate(matched.id, txnsAsOfDate).totalQuantity,
                    )
                } else {
                    matched.totalQuantity
                }
                if (availableQty < quantity) {
                    throw IllegalArgumentException(
                        "Insufficient holdings for ${matched.displayName}: available ${availableQty.stripTrailingZeros().toPlainString()} units on $tradeDate, attempted ${quantity.stripTrailingZeros().toPlainString()}.",
                    )
                }
            }

            runCatching {
                database.accountDao()?.insert(
                    AccountEntity(
                        id = "default_account",
                        name = "Default Account",
                        type = AccountType.BROKER,
                        currency = "INR",
                    ),
                )
            }

            val resolvedAssetClass = matched?.assetClass ?: assetClass
            if (matched == null && assetDao.getById(assetId) == null) {
                val idJson = AssetNameNormalizer.buildIdentifiersJson("{}", isin, ticker)
                val newAsset = AssetEntity(
                    id = assetId,
                    displayName = cleanName,
                    assetClass = resolvedAssetClass,
                    currency = "INR",
                    totalQuantity = BigDecimal.ZERO,
                    totalInvested = BigDecimal.ZERO,
                    averageCost = BigDecimal.ZERO,
                    realizedPnL = BigDecimal.ZERO,
                    identifiersJson = idJson,
                    status = AssetStatus.ACTIVE,
                )
                assetDao.insert(newAsset)
                existingAssets.add(newAsset)
            } else if (matched != null && (isin != null || ticker != null)) {
                val curIds = matched.identifiersJson
                val merged = AssetNameNormalizer.buildIdentifiersJson(curIds, isin, ticker)
                if (merged != curIds) {
                    val updated = matched.copy(identifiersJson = merged)
                    assetDao.update(updated)
                }
            }

            val fingerprint = createTransactionFingerprint(
                assetId = assetId,
                isin = isin,
                ticker = ticker,
                assetName = cleanName,
                transactionType = type.name,
                quantity = quantity.toPlainString(),
                unitPrice = unitPrice.toPlainString(),
                netAmount = netAmount.toPlainString(),
                date = tradeDate.toEpochDay(),
                broker = cleanBroker,
            )

            if (transactionDao.containsFingerprint(fingerprint)) {
                throw DuplicateTransactionException(
                    "A matching transaction already exists for $cleanName (${type.name} on $tradeDate via ${cleanBroker ?: "Default Broker"}).",
                )
            }

            @Suppress("USELESS_ELVIS")
            val existingAssetTxns = transactionDao.getByAssetIds(listOf(assetId)) ?: emptyList()
            val semanticDuplicate = existingAssetTxns.any { ex ->
                ex.type == type &&
                    ex.tradeDate == tradeDate &&
                    (ex.broker?.trim().orEmpty().equals(cleanBroker.orEmpty(), ignoreCase = true)) &&
                    ((ex.quantity ?: BigDecimal.ZERO).subtract(quantity).abs() <= QTY_DUPLICATE_TOLERANCE) &&
                    ex.netAmount.subtract(netAmount).abs() <= AMOUNT_DUPLICATE_TOLERANCE
            }
            if (semanticDuplicate) {
                throw DuplicateTransactionException(
                    "A near-identical ${type.name} transaction for $cleanName on $tradeDate already exists.",
                )
            }

            val txnId = UUID.randomUUID().toString()
            val documentDao = runCatching { database.documentDao() }.getOrNull()
            val manualDocId: String? = if (documentDao != null) {
                val docId = "${MANUAL_DOCUMENT_PREFIX}${UUID.randomUUID()}"
                val qtyLabel = quantity.stripTrailingZeros().toPlainString()
                val netLabel = netAmount.setScale(2, RoundingMode.HALF_UP).toPlainString()
                val previewSummary = buildString {
                    append("${type.name} $qtyLabel units • Net ₹$netLabel on $tradeDate")
                    if (cleanBroker != null) append(" • $cleanBroker")
                    if (cleanNotes != null) append("\n$cleanNotes")
                }
                val manualDoc = DocumentEntity(
                    id = docId,
                    fileName = "Manual: ${matched?.displayName ?: cleanName} (${type.name})",
                    localPath = "",
                    fileHash = "${MANUAL_DOCUMENT_PREFIX}$txnId",
                    fileSize = 0L,
                    importDate = System.currentTimeMillis(),
                    extractionStatus = ExtractionStatus.COMPLETED,
                    extractedText = previewSummary,
                    status = DocumentStatus.COMPLETED.name,
                    assetClass = resolvedAssetClass.name,
                    pageCount = 1,
                    processedPages = 1,
                )
                documentDao.insert(manualDoc)
                docId
            } else {
                null
            }

            val grossAmount = if (quantity > BigDecimal.ZERO && unitPrice > BigDecimal.ZERO) {
                quantity.multiply(unitPrice)
            } else {
                netAmount
            }

            val transaction = TransactionEntity(
                id = txnId,
                accountId = "default_account",
                assetId = assetId,
                type = type,
                tradeDate = tradeDate,
                quantity = quantity,
                unitPrice = unitPrice,
                grossAmount = grossAmount,
                fees = fees,
                taxes = taxes,
                netAmount = netAmount,
                currency = "INR",
                notes = cleanNotes,
                broker = cleanBroker,
                fingerprint = fingerprint,
                status = TransactionStatus.CONFIRMED,
                documentId = manualDocId,
                confidence = 1.0,
            )

            transactionDao.insert(transaction)
            recalculateInternal(assetId)
            createdTransaction = transaction
        }
        requireNotNull(createdTransaction) { "Failed to record manual transaction" }
    }

    private fun validateIdentifierCompatibility(
        existingAsset: AssetEntity,
        newIsin: String?,
        newTicker: String?,
    ) {
        val obj = runCatching { org.json.JSONObject(existingAsset.identifiersJson) }.getOrNull()
        val existingIsin = obj?.optString("isin")?.trim()?.takeIf(String::isNotBlank)
            ?: AssetNameNormalizer.extractIsin(existingAsset.id)
        val existingTicker = obj?.optString("ticker")?.trim()?.takeIf(String::isNotBlank)

        if (newIsin != null && existingIsin != null && !existingIsin.equals(newIsin, ignoreCase = true)) {
            throw IllegalArgumentException(
                "Entered ISIN ($newIsin) conflicts with existing asset ISIN ($existingIsin).",
            )
        }
        if (newTicker != null && existingTicker != null && !existingTicker.equals(newTicker, ignoreCase = true)) {
            throw IllegalArgumentException(
                "Entered ticker ($newTicker) conflicts with existing asset ticker ($existingTicker).",
            )
        }
    }

    private companion object {
        const val MANUAL_DOCUMENT_PREFIX = "manual_"
        val TICKER_REGEX = Regex("^[A-Z0-9._-]{1,20}$")
        val QTY_DUPLICATE_TOLERANCE = BigDecimal("0.0001")
        val AMOUNT_DUPLICATE_TOLERANCE = BigDecimal("0.05")
        val QUANTITY_REQUIRED_TYPES = setOf(
            TransactionType.BUY,
            TransactionType.SELL,
            TransactionType.TRANSFER_IN,
            TransactionType.TRANSFER_OUT,
            TransactionType.BONUS,
            TransactionType.SPLIT,
        )
        val CASH_AMOUNT_REQUIRED_TYPES = setOf(
            TransactionType.DIVIDEND,
            TransactionType.INTEREST,
            TransactionType.FEE,
            TransactionType.TAX,
            TransactionType.CHARGE,
        )
    }

    private suspend fun recalculateInternal(assetId: String): RecalculationResult {
        val txns = transactionDao.getByAssetIdOnce(assetId)
        val result = FifoCostBasisCalculator.calculate(assetId, txns)

        lotDao.deleteByAssetId(assetId)
        if (result.lots.isNotEmpty()) {
            lotDao.insertAll(result.lots)
        }

        val asset = assetDao.getById(assetId)
        if (asset != null) {
            val updated = asset.copy(
                totalQuantity = result.totalQuantity,
                totalInvested = result.totalInvested,
                averageCost = result.averageCost,
                realizedPnL = result.realizedPnL,
                status = result.status,
                updatedAt = System.currentTimeMillis(),
            )
            assetDao.update(updated)
        }
        return result
    }
}
