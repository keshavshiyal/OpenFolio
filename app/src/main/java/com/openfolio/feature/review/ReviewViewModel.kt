package com.openfolio.feature.review

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.openfolio.core.data.local.entity.AssetClass
import com.openfolio.core.data.local.entity.ColumnMappingEntity
import com.openfolio.core.data.local.entity.DocumentEntity
import com.openfolio.core.data.local.entity.DocumentStatus
import com.openfolio.core.data.local.entity.DraftTransactionEntity
import com.openfolio.core.data.remote.ai.AiCarryOverContext
import com.openfolio.core.domain.repository.AiBatchProcessResult
import com.openfolio.core.domain.repository.AiBatchStopReason
import com.openfolio.core.domain.repository.AiExtractionException
import com.openfolio.core.domain.repository.DocumentRepository
import com.openfolio.core.domain.repository.EmptyExtractedTextException
import com.openfolio.core.domain.repository.MissingApiKeyException
import com.openfolio.core.domain.repository.NoFinancialDataException
import com.openfolio.core.domain.repository.SaveDraftTransactionsResult
import com.openfolio.core.tableextraction.CsvTableGenerator
import com.openfolio.core.tableextraction.ExtractedTable
import com.openfolio.core.tableextraction.LocalTableExtractionPipeline
import com.openfolio.core.utils.AssetNameNormalizer
import dagger.hilt.android.lifecycle.HiltViewModel
import java.io.File
import java.math.BigDecimal
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

@HiltViewModel
class ReviewViewModel @Inject constructor(
    private val documentRepository: DocumentRepository,
    private val localTableExtractionPipeline: LocalTableExtractionPipeline? = null,
    private val csvTableGenerator: CsvTableGenerator? = null,
) : ViewModel() {
    private val _uiState = MutableStateFlow<ReviewUiState>(ReviewUiState.Loading)
    val uiState: StateFlow<ReviewUiState> = _uiState.asStateFlow()

    private val _drafts = MutableStateFlow<List<DraftTransactionEntity>>(emptyList())
    val drafts: StateFlow<List<DraftTransactionEntity>> = _drafts.asStateFlow()

    private val _draftReviewItems = MutableStateFlow<List<DraftReviewItem>>(emptyList())
    val draftReviewItems: StateFlow<List<DraftReviewItem>> = _draftReviewItems.asStateFlow()

    private val _selectedDraftIds = MutableStateFlow<Set<String>>(emptySet())
    val selectedDraftIds: StateFlow<Set<String>> = _selectedDraftIds.asStateFlow()

    private val _hasExplicitUserSelection = MutableStateFlow(false)

    private val _structuredHeaders = MutableStateFlow<List<String>>(emptyList())
    val structuredHeaders: StateFlow<List<String>> = _structuredHeaders.asStateFlow()

    private val _extractedTables = MutableStateFlow<List<ExtractedTable>>(emptyList())
    val extractedTables: StateFlow<List<ExtractedTable>> = _extractedTables.asStateFlow()

    private val _showTableReview = MutableStateFlow(false)
    val showTableReview: StateFlow<Boolean> = _showTableReview.asStateFlow()

    private val _isPasswordProtected = MutableStateFlow(false)
    val isPasswordProtected: StateFlow<Boolean> = _isPasswordProtected.asStateFlow()

    private val _unlockedPassword = MutableStateFlow<String?>(null)
    val unlockedPassword: StateFlow<String?> = _unlockedPassword.asStateFlow()

    private val _passwordError = MutableStateFlow<String?>(null)
    val passwordError: StateFlow<String?> = _passwordError.asStateFlow()

    private val _actionState = MutableStateFlow<ReviewActionState>(ReviewActionState.Idle)
    val actionState: StateFlow<ReviewActionState> = _actionState.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    private val _batchBannerMessage = MutableStateFlow<String?>(null)
    val batchBannerMessage: StateFlow<String?> = _batchBannerMessage.asStateFlow()

    private var draftsJob: Job? = null
    private var documentJob: Job? = null

    fun loadDocument(documentId: String) {
        draftsJob?.cancel()
        documentJob?.cancel()
        _uiState.value = ReviewUiState.Loading
        _drafts.value = emptyList()
        _draftReviewItems.value = emptyList()
        _selectedDraftIds.value = emptySet()
        _hasExplicitUserSelection.value = false
        _structuredHeaders.value = emptyList()
        _extractedTables.value = emptyList()
        _showTableReview.value = false
        _isPasswordProtected.value = false
        _unlockedPassword.value = null
        _passwordError.value = null
        _actionState.value = ReviewActionState.Idle
        _errorMessage.value = null
        _batchBannerMessage.value = null
        viewModelScope.launch {
            try {
                val document = documentRepository.getDocumentById(documentId)
                if (document == null) {
                    _uiState.value = ReviewUiState.NotFound
                    return@launch
                }
                _uiState.value = ReviewUiState.Loaded(document)
                if (document.extractedText == "[PASSWORD_PROTECTED]") {
                    _isPasswordProtected.value = true
                }
                documentJob = viewModelScope.launch {
                    documentRepository.observeDocumentById(documentId).collect { updated ->
                        if (updated != null) {
                            _uiState.value = ReviewUiState.Loaded(updated)
                            if (updated.extractedText == "[PASSWORD_PROTECTED]") {
                                _isPasswordProtected.value = true
                            }
                        }
                    }
                }
                draftsJob = viewModelScope.launch {
                    documentRepository.getDraftTransactions(documentId).collect { items ->
                        _drafts.value = items
                        val duplicateMap = runCatching {
                            documentRepository.findDuplicateDrafts(documentId)
                        }.getOrDefault(emptyMap())

                        val currentSelections = _selectedDraftIds.value
                        val hasSelectionHistory = _hasExplicitUserSelection.value

                        val reviewItems = items.map { item ->
                            val dupInfo = duplicateMap[item.id]
                            val isDup = dupInfo?.isDuplicate == true
                            val isSelected = if (hasSelectionHistory) {
                                item.id in currentSelections
                            } else {
                                !isDup
                            }
                            DraftReviewItem(
                                draft = item,
                                isDuplicate = isDup,
                                duplicateReason = dupInfo?.reason,
                                isSelected = isSelected,
                                isFlagged = isDraftFlaggedForReview(item),
                            )
                        }

                        if (!hasSelectionHistory) {
                            _selectedDraftIds.value = reviewItems.filter { it.isSelected }.map { it.draft.id }.toSet()
                        }
                        _draftReviewItems.value = reviewItems
                    }
                }
                if (document.status == DocumentStatus.READY_FOR_MAPPING.name) {
                    viewModelScope.launch {
                        runAction(ReviewActionState.LoadingMapping) {
                            _structuredHeaders.value =
                                documentRepository.getStructuredFileHeaders(documentId, 1)
                        }
                    }
                }
            } catch (exception: CancellationException) {
                throw exception
            } catch (throwable: Throwable) {
                _uiState.value = ReviewUiState.Error(
                    throwable.message ?: "Unable to load this document.",
                )
            }
        }
    }

    fun onProcessWithAi() {
        val document = (uiState.value as? ReviewUiState.Loaded)?.document ?: return
        if (_actionState.value == ReviewActionState.ProcessingAi) return
        val canResume = document.status == DocumentStatus.FAILED.name ||
            document.status == DocumentStatus.AI_PAUSED.name
        if (_drafts.value.isNotEmpty() && !canResume) return
        runAiBatchAction(document) {
            documentRepository.processNextAiBatch(document.id)
        }
    }

    fun onProcessNextAiBatch() {
        val document = (uiState.value as? ReviewUiState.Loaded)?.document ?: return
        if (_actionState.value == ReviewActionState.ProcessingAi) return
        runAiBatchAction(document) {
            documentRepository.processNextAiBatch(document.id)
        }
    }

    fun onAutoProcessRemainingAi() {
        val document = (uiState.value as? ReviewUiState.Loaded)?.document ?: return
        if (_actionState.value == ReviewActionState.ProcessingAi) return
        runAiBatchAction(document) {
            documentRepository.autoProcessRemainingAiBatches(document.id)
        }
    }

    private fun runAiBatchAction(
        document: DocumentEntity,
        block: suspend () -> AiBatchProcessResult,
    ) {
        val allowedStatuses = setOf(
            DocumentStatus.OCR_COMPLETE.name,
            DocumentStatus.AI_PAUSED.name,
            DocumentStatus.AI_COMPLETE.name,
            DocumentStatus.FAILED.name,
        )
        if (document.status !in allowedStatuses) {
            val message = "Wait for local text extraction to finish before processing."
            _errorMessage.value = message
            _actionState.value = ReviewActionState.Error(message)
            return
        }
        viewModelScope.launch {
            _actionState.value = ReviewActionState.ProcessingAi
            _batchBannerMessage.value = null
            try {
                val result = block()
                documentRepository.getDocumentById(document.id)?.let { updated ->
                    _uiState.value = ReviewUiState.Loaded(updated)
                }
                _batchBannerMessage.value = when (result.stopReason) {
                    AiBatchStopReason.PAUSED_AFTER_BATCH ->
                        "Batch complete (Pages ${result.startPage}–${result.endPage} of ${result.totalPages}). Review drafts, then continue."
                    AiBatchStopReason.AUTO_PAUSED_NEEDS_REVIEW ->
                        result.message ?: "Paused at page ${result.endPage} of ${result.totalPages}: flagged rows need review."
                    AiBatchStopReason.COMPLETED_ALL_PAGES ->
                        if (result.totalPages > 1) "All ${result.totalPages} pages extracted. Review and save to portfolio." else null
                }
                _actionState.value = ReviewActionState.Idle
            } catch (e: CancellationException) {
                _actionState.value = ReviewActionState.Idle
                throw e
            } catch (e: NoFinancialDataException) {
                documentRepository.updateDocumentStatus(document.id, DocumentStatus.FAILED.name)
                val message = e.message ?: "No financial transactions found in this document."
                _errorMessage.value = message
                _actionState.value = ReviewActionState.NoFinancialData(message)
            } catch (e: EmptyExtractedTextException) {
                val message = e.message ?: "No text found in document."
                _errorMessage.value = message
                _actionState.value = ReviewActionState.Error(message)
            } catch (e: MissingApiKeyException) {
                _actionState.value = ReviewActionState.MissingApiKey
            } catch (aiException: AiExtractionException) {
                val message = aiException.message ?: "AI extraction failed."
                _errorMessage.value = message
                _actionState.value = ReviewActionState.Error(message)
            } catch (e: Exception) {
                val message = "Network error: ${e.localizedMessage ?: e.message}"
                _errorMessage.value = message
                _actionState.value = ReviewActionState.Error(message)
            }
        }
    }

    fun retryLocalExtraction() {
        val document = (uiState.value as? ReviewUiState.Loaded)?.document ?: return
        viewModelScope.launch {
            documentRepository.retryLocalExtraction(document.id)
        }
    }

    fun unlockPdf(password: String) {
        val document = (uiState.value as? ReviewUiState.Loaded)?.document ?: return
        if (password.isBlank()) {
            _passwordError.value = "Enter the PDF password."
            return
        }
        viewModelScope.launch {
            _actionState.value = ReviewActionState.ProcessingMapping
            _passwordError.value = null
            val result = documentRepository.unlockAndProcessPdf(document.id, password.trim())
            if (result.isSuccess) {
                _unlockedPassword.value = password.trim()
                _isPasswordProtected.value = false
                _actionState.value = ReviewActionState.Idle
            } else {
                _actionState.value = ReviewActionState.Idle
                _passwordError.value = result.exceptionOrNull()?.message?.takeIf { it.isNotBlank() }
                    ?: "Incorrect password. Please verify and try again."
            }
        }
    }

    fun clearPasswordError() {
        _passwordError.value = null
    }

    fun dismissMissingApiKeyDialog() {
        if (_actionState.value == ReviewActionState.MissingApiKey) {
            _actionState.value = ReviewActionState.Idle
        }
    }

    fun dismissError() {
        _errorMessage.value = null
        if (_actionState.value is ReviewActionState.Error) {
            _actionState.value = ReviewActionState.Idle
        }
    }

    fun clearErrorMessage() {
        _errorMessage.value = null
    }

    fun loadHeadersForHeaderRow(headerRowNumber: Int) {
        val document = (uiState.value as? ReviewUiState.Loaded)?.document ?: return
        if (document.status != DocumentStatus.READY_FOR_MAPPING.name) return
        if (headerRowNumber < 1) return
        viewModelScope.launch {
            runAction(ReviewActionState.LoadingMapping) {
                try {
                    _structuredHeaders.value =
                        documentRepository.getStructuredFileHeaders(document.id, headerRowNumber)
                } catch (throwable: Throwable) {
                    _structuredHeaders.value = emptyList()
                    throw throwable
                }
            }
        }
    }

    fun processMappedData(
        brokerName: String,
        assetColumn: String,
        typeColumn: String,
        quantityColumn: String,
        priceColumn: String,
        amountColumn: String,
        dateColumn: String,
        headerRowNumber: Int = 1,
    ) {
        val document = (uiState.value as? ReviewUiState.Loaded)?.document ?: return
        if (document.status != DocumentStatus.READY_FOR_MAPPING.name) return
        viewModelScope.launch {
            runAction(ReviewActionState.ProcessingMapping) {
                documentRepository.processStructuredDocument(
                    documentId = document.id,
                    mapping = ColumnMappingEntity(
                        brokerName = brokerName.trim(),
                        assetColumn = assetColumn,
                        typeColumn = typeColumn,
                        quantityColumn = quantityColumn,
                        priceColumn = priceColumn,
                        amountColumn = amountColumn,
                        dateColumn = dateColumn,
                        headerRowNumber = headerRowNumber,
                    ),
                )
            }
        }
    }

    fun extractTablesLocally() {
        val document = (uiState.value as? ReviewUiState.Loaded)?.document ?: return
        viewModelScope.launch {
            runAction(ReviewActionState.ProcessingMapping) {
                try {
                    val pipeline = localTableExtractionPipeline ?: return@runAction
                    val file = File(document.localPath)
                    val tables = pipeline.extractTablesFromDocument(file, document.fileName)
                    if (tables.isEmpty()) {
                        _errorMessage.value = "No structured tables detected in this document."
                    } else {
                        _extractedTables.value = tables
                        _showTableReview.value = true
                    }
                } catch (e: Exception) {
                    _errorMessage.value = e.message ?: "Failed to extract tables locally."
                }
            }
        }
    }

    fun dismissTableReview() {
        _showTableReview.value = false
    }

    fun approveExtractedTable(table: ExtractedTable, selectedRowIndices: Set<Int>) {
        val document = (uiState.value as? ReviewUiState.Loaded)?.document ?: return
        viewModelScope.launch {
            runAction(ReviewActionState.ProcessingMapping) {
                try {
                    val generator = csvTableGenerator ?: return@runAction
                    val filteredTable = if (table.rows.size > selectedRowIndices.size && selectedRowIndices.isNotEmpty()) {
                        table.copy(
                            rows = table.rows.filterIndexed { index, _ -> selectedRowIndices.contains(index) }
                        )
                    } else {
                        table
                    }
                    val csvFile = generator.generateCsvFile(filteredTable)
                    documentRepository.attachExtractedCsv(document.id, csvFile)
                    _showTableReview.value = false
                    _structuredHeaders.value = documentRepository.getStructuredFileHeaders(document.id, 1)
                } catch (e: Exception) {
                    _errorMessage.value = e.message ?: "Failed to approve extracted table."
                }
            }
        }
    }

    fun saveDraft(
        draftId: String?,
        assetName: String,
        transactionType: String,
        quantity: String,
        price: String,
        netAmount: String,
        assetClass: String? = null,
        broker: String? = null,
    ) {
        val loadedDoc = (uiState.value as? ReviewUiState.Loaded)?.document ?: return
        val documentId = loadedDoc.id
        viewModelScope.launch {
            runAction(ReviewActionState.Saving) {
                require(assetName.isNotBlank()) { "Asset name is required." }
                require(transactionType in SUPPORTED_TRANSACTION_TYPES) {
                    "Choose a valid transaction type."
                }
                require(quantity.toBigDecimalOrNull() != null && quantity.toBigDecimal() > BigDecimal.ZERO) {
                    "Quantity must be a positive number."
                }
                require(price.toBigDecimalOrNull() != null && price.toBigDecimal() >= BigDecimal.ZERO) {
                    "Price must be a valid non-negative number."
                }
                require(netAmount.toBigDecimalOrNull() != null && netAmount.toBigDecimal() >= BigDecimal.ZERO) {
                    "Amount must be a valid non-negative number."
                }
                val previous = draftId?.let { id ->
                    _drafts.value.firstOrNull { it.id == id }
                }
                val resolvedAssetClass = assetClass
                    ?: previous?.assetClass
                    ?: loadedDoc.assetClass
                val resolvedBroker = broker?.trim()?.takeIf(String::isNotBlank)
                    ?: if (broker == null) previous?.broker else null
                val resolvedIsin = AssetNameNormalizer.extractIsin(assetName) ?: previous?.isin
                val draft = DraftTransactionEntity(
                    id = previous?.id ?: java.util.UUID.randomUUID().toString(),
                    documentId = documentId,
                    assetName = assetName.trim(),
                    transactionType = transactionType,
                    quantity = quantity.trim(),
                    price = price.trim(),
                    netAmount = netAmount.trim(),
                    confidence = if (previous == null) 100 else maxOf(previous.confidence, 95),
                    isAccepted = previous?.isAccepted ?: false,
                    isManual = true,
                    tradeDate = previous?.tradeDate,
                    assetClass = resolvedAssetClass,
                    broker = resolvedBroker,
                    isin = resolvedIsin,
                    ticker = previous?.ticker,
                    sourcePage = previous?.sourcePage,
                )
                if (previous == null) {
                    documentRepository.insertDraftTransaction(draft)
                } else {
                    documentRepository.updateDraftTransaction(draft)
                }

                syncCarryOverContextAfterEdit(
                    document = loadedDoc,
                    previous = previous,
                    updated = draft,
                )
            }
        }
    }

    private suspend fun syncCarryOverContextAfterEdit(
        document: DocumentEntity,
        previous: DraftTransactionEntity?,
        updated: DraftTransactionEntity,
    ) {
        val existingCtx = AiCarryOverContext.fromJson(document.carryOverContextJson) ?: AiCarryOverContext()
        val schemeChanged = previous == null ||
            !updated.assetName.equals(previous.assetName, ignoreCase = true) ||
            previous.assetName.equals(existingCtx.activeScheme, ignoreCase = true)
        val brokerChanged = updated.broker != null && updated.broker != previous?.broker

        val nextCtx = existingCtx.copy(
            activeScheme = if (schemeChanged && updated.assetName.isNotBlank()) {
                updated.assetName
            } else {
                existingCtx.activeScheme
            },
            activeIsin = updated.isin ?: existingCtx.activeIsin,
            activeBroker = if (brokerChanged) {
                updated.broker.orEmpty()
            } else {
                existingCtx.activeBroker.ifBlank { updated.broker.orEmpty() }
            },
        )
        if (nextCtx != existingCtx && !nextCtx.isEmpty()) {
            documentRepository.updateCarryOverContext(document.id, nextCtx)
        }
        if (updated.assetClass != document.assetClass &&
            (previous == null || updated.assetClass != previous.assetClass)
        ) {
            runCatching { AssetClass.valueOf(updated.assetClass.trim().uppercase()) }
                .getOrNull()
                ?.let { documentRepository.updateDocumentAssetClass(document.id, it) }
        }
    }

    fun updateAllDraftsAssetClass(newAssetClass: AssetClass) {
        val document = (uiState.value as? ReviewUiState.Loaded)?.document
        viewModelScope.launch {
            val currentDrafts = _drafts.value
            for (draft in currentDrafts) {
                if (draft.assetClass != newAssetClass.name) {
                    documentRepository.updateDraftTransaction(draft.copy(assetClass = newAssetClass.name))
                }
            }
            if (document != null && document.assetClass != newAssetClass.name) {
                documentRepository.updateDocumentAssetClass(document.id, newAssetClass)
                _uiState.value = ReviewUiState.Loaded(document.copy(assetClass = newAssetClass.name))
            }
        }
    }

    fun toggleDraftSelection(draftId: String) {
        _hasExplicitUserSelection.value = true
        val current = _selectedDraftIds.value.toMutableSet()
        if (current.contains(draftId)) {
            current.remove(draftId)
        } else {
            current.add(draftId)
        }
        _selectedDraftIds.value = current
        _draftReviewItems.value = _draftReviewItems.value.map {
            if (it.draft.id == draftId) it.copy(isSelected = draftId in current) else it
        }
    }

    fun selectAllDrafts() {
        _hasExplicitUserSelection.value = true
        val allIds = _draftReviewItems.value.map { it.draft.id }.toSet()
        _selectedDraftIds.value = allIds
        _draftReviewItems.value = _draftReviewItems.value.map { it.copy(isSelected = true) }
    }

    fun deselectAllDrafts() {
        _hasExplicitUserSelection.value = true
        _selectedDraftIds.value = emptySet()
        _draftReviewItems.value = _draftReviewItems.value.map { it.copy(isSelected = false) }
    }

    fun skipAllDuplicates() {
        _hasExplicitUserSelection.value = true
        val nonDuplicates = _draftReviewItems.value.filter { !it.isDuplicate }.map { it.draft.id }.toSet()
        _selectedDraftIds.value = nonDuplicates
        _draftReviewItems.value = _draftReviewItems.value.map {
            it.copy(isSelected = !it.isDuplicate)
        }
    }

    fun saveConfirmed(overrideDuplicates: Boolean = false) {
        val document = (uiState.value as? ReviewUiState.Loaded)?.document ?: return
        val documentId = document.id
        if (_drafts.value.isEmpty()) return
        val isBatchPending = document.pageCount > 0 &&
            document.processedPages in 1 until document.pageCount &&
            document.status in setOf(
                DocumentStatus.AI_PAUSED.name,
                DocumentStatus.PROCESSING.name,
                DocumentStatus.FAILED.name,
            )
        if (isBatchPending) {
            _errorMessage.value = "Finish all ${document.pageCount} pages before committing to portfolio."
            return
        }

        val currentItems = _draftReviewItems.value
        val selectedItems = currentItems.filter { it.isSelected }
        val selectedDuplicates = selectedItems.filter { it.isDuplicate }

        if (!overrideDuplicates && selectedDuplicates.isNotEmpty()) {
            _actionState.value = ReviewActionState.PromptDuplicateReview(
                duplicateCount = selectedDuplicates.size,
                totalSelectedCount = selectedItems.size,
            )
            return
        }

        viewModelScope.launch {
            runAction(ReviewActionState.Saving) {
                val selectedIds = selectedItems.map { it.draft.id }.toSet()
                val result = documentRepository.saveDraftTransactions(documentId, selectedIds)
                _actionState.value = ReviewActionState.Saved(result)
            }
        }
    }

    fun confirmSkipDuplicatesAndSave() {
        skipAllDuplicates()
        saveConfirmed(overrideDuplicates = true)
    }

    fun confirmSaveAllAnyway() {
        saveConfirmed(overrideDuplicates = true)
    }

    fun dismissDuplicatePrompt() {
        if (_actionState.value is ReviewActionState.PromptDuplicateReview) {
            _actionState.value = ReviewActionState.Idle
        }
    }

    private suspend fun runAction(
        runningState: ReviewActionState,
        action: suspend () -> Unit,
    ) {
        _actionState.value = runningState
        try {
            action()
            if (_actionState.value == runningState) _actionState.value = ReviewActionState.Idle
        } catch (exception: CancellationException) {
            _actionState.value = ReviewActionState.Idle
            throw exception
        } catch (missingKey: MissingApiKeyException) {
            _actionState.value = ReviewActionState.MissingApiKey
        } catch (noData: NoFinancialDataException) {
            val docId = (uiState.value as? ReviewUiState.Loaded)?.document?.id
            if (docId != null) {
                documentRepository.updateDocumentStatus(docId, DocumentStatus.FAILED.name)
            }
            val message = noData.message ?: "No financial transactions found in this document."
            _errorMessage.value = message
            _actionState.value = ReviewActionState.NoFinancialData(message)
        } catch (emptyText: EmptyExtractedTextException) {
            val message = emptyText.message ?: "No text found in document."
            _errorMessage.value = message
            _actionState.value = ReviewActionState.Error(message)
        } catch (aiException: AiExtractionException) {
            val message = aiException.message ?: "AI extraction failed."
            _errorMessage.value = message
            _actionState.value = ReviewActionState.Error(message)
        } catch (throwable: Throwable) {
            val message = throwable.localizedMessage ?: throwable.message ?: "Unable to complete this action."
            _errorMessage.value = message
            _actionState.value = ReviewActionState.Error(message)
        } finally {
            if (_actionState.value == runningState) {
                _actionState.value = ReviewActionState.Idle
            }
        }
    }

    private fun isDraftFlaggedForReview(draft: DraftTransactionEntity): Boolean {
        if (draft.confidence < 80) return true
        val type = draft.transactionType.trim().uppercase()
        if (type !in setOf("BUY", "SELL", "TRANSFER_IN", "TRANSFER_OUT")) return false
        val qty = draft.quantity.replace(",", "").replace("₹", "").trim().toBigDecimalOrNull()?.abs() ?: return true
        val price = draft.price.replace(",", "").replace("₹", "").trim().toBigDecimalOrNull()?.abs() ?: return true
        val net = draft.netAmount.replace(",", "").replace("₹", "").trim().toBigDecimalOrNull()?.abs() ?: return true
        if (qty.compareTo(BigDecimal.ZERO) <= 0 ||
            price.compareTo(BigDecimal.ZERO) <= 0 ||
            net.compareTo(BigDecimal.ZERO) <= 0
        ) {
            return false
        }
        val expected = qty.multiply(price)
        val diff = expected.subtract(net).abs()
        val tolerance = maxOf(BigDecimal("1.00"), expected.multiply(BigDecimal("0.05")))
        return diff > tolerance
    }

    private companion object {
        val SUPPORTED_TRANSACTION_TYPES = setOf("BUY", "SELL", "DIVIDEND", "INTEREST", "FEE", "TAX")
    }
}

sealed interface ReviewUiState {
    data object Loading : ReviewUiState
    data object NotFound : ReviewUiState
    data class Loaded(val document: DocumentEntity) : ReviewUiState
    data class Error(val message: String) : ReviewUiState
}

sealed interface ReviewActionState {
    data object Idle : ReviewActionState
    data object ProcessingAi : ReviewActionState
    data object MissingApiKey : ReviewActionState
    data object LoadingMapping : ReviewActionState
    data object ProcessingMapping : ReviewActionState
    data object Saving : ReviewActionState
    data class Saved(val result: SaveDraftTransactionsResult) : ReviewActionState
    data class PromptDuplicateReview(val duplicateCount: Int, val totalSelectedCount: Int) : ReviewActionState
    data class NoFinancialData(val message: String = "No financial transactions found in this document.") : ReviewActionState
    data class Error(val message: String) : ReviewActionState
}

data class DraftReviewItem(
    val draft: DraftTransactionEntity,
    val isDuplicate: Boolean = false,
    val duplicateReason: String? = null,
    val isSelected: Boolean = !isDuplicate,
    val isFlagged: Boolean = false,
)
