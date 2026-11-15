package com.openfolio.feature.review

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.openfolio.core.data.local.entity.ColumnMappingEntity
import com.openfolio.core.data.local.entity.DocumentEntity
import com.openfolio.core.data.local.entity.DocumentStatus
import com.openfolio.core.data.local.entity.DraftTransactionEntity
import com.openfolio.core.domain.repository.DocumentRepository
import com.openfolio.core.domain.repository.MissingApiKeyException
import com.openfolio.core.domain.repository.SaveDraftTransactionsResult
import dagger.hilt.android.lifecycle.HiltViewModel
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
) : ViewModel() {
    private val _uiState = MutableStateFlow<ReviewUiState>(ReviewUiState.Loading)
    val uiState: StateFlow<ReviewUiState> = _uiState.asStateFlow()

    private val _drafts = MutableStateFlow<List<DraftTransactionEntity>>(emptyList())
    val drafts: StateFlow<List<DraftTransactionEntity>> = _drafts.asStateFlow()

    private val _structuredHeaders = MutableStateFlow<List<String>>(emptyList())
    val structuredHeaders: StateFlow<List<String>> = _structuredHeaders.asStateFlow()

    private val _actionState = MutableStateFlow<ReviewActionState>(ReviewActionState.Idle)
    val actionState: StateFlow<ReviewActionState> = _actionState.asStateFlow()

    private var draftsJob: Job? = null
    private var documentJob: Job? = null

    fun loadDocument(documentId: String) {
        draftsJob?.cancel()
        documentJob?.cancel()
        _uiState.value = ReviewUiState.Loading
        _drafts.value = emptyList()
        _structuredHeaders.value = emptyList()
        _actionState.value = ReviewActionState.Idle
        viewModelScope.launch {
            try {
                val document = documentRepository.getDocumentById(documentId)
                if (document == null) {
                    _uiState.value = ReviewUiState.NotFound
                    return@launch
                }
                _uiState.value = ReviewUiState.Loaded(document)
                documentJob = viewModelScope.launch {
                    documentRepository.observeDocumentById(documentId).collect { updated ->
                        if (updated != null) _uiState.value = ReviewUiState.Loaded(updated)
                    }
                }
                draftsJob = viewModelScope.launch {
                    documentRepository.getDraftTransactions(documentId).collect { items ->
                        _drafts.value = items
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
        if (_drafts.value.isNotEmpty() || _actionState.value == ReviewActionState.ProcessingAi) return
        if (document.status != DocumentStatus.OCR_COMPLETE.name && document.status != DocumentStatus.AI_COMPLETE.name) {
            _actionState.value = ReviewActionState.Error(
                "Wait for local text extraction to finish before processing.",
            )
            return
        }
        viewModelScope.launch {
            runAction(ReviewActionState.ProcessingAi) {
                documentRepository.processDocumentWithAi(document.id)
            }
        }
    }

    fun dismissMissingApiKeyDialog() {
        if (_actionState.value == ReviewActionState.MissingApiKey) {
            _actionState.value = ReviewActionState.Idle
        }
    }

    fun dismissError() {
        if (_actionState.value is ReviewActionState.Error) {
            _actionState.value = ReviewActionState.Idle
        }
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

    fun saveDraft(
        draftId: String?,
        assetName: String,
        transactionType: String,
        quantity: String,
        price: String,
        netAmount: String,
    ) {
        val documentId = (uiState.value as? ReviewUiState.Loaded)?.document?.id ?: return
        viewModelScope.launch {
            runAction(ReviewActionState.Saving) {
                require(assetName.isNotBlank()) { "Asset name is required." }
                require(transactionType in SUPPORTED_TRANSACTION_TYPES) {
                    "Choose a valid transaction type."
                }
                require(quantity.toBigDecimalOrNull() != null && quantity.toBigDecimal() > java.math.BigDecimal.ZERO) {
                    "Quantity must be a positive number."
                }
                require(price.toBigDecimalOrNull() != null && price.toBigDecimal() >= java.math.BigDecimal.ZERO) {
                    "Price must be a valid non-negative number."
                }
                require(netAmount.toBigDecimalOrNull() != null && netAmount.toBigDecimal() >= java.math.BigDecimal.ZERO) {
                    "Amount must be a valid non-negative number."
                }
                val previous = draftId?.let { id ->
                    _drafts.value.firstOrNull { it.id == id }
                }
                val draft = DraftTransactionEntity(
                    id = previous?.id ?: java.util.UUID.randomUUID().toString(),
                    documentId = documentId,
                    assetName = assetName.trim(),
                    transactionType = transactionType,
                    quantity = quantity.trim(),
                    price = price.trim(),
                    netAmount = netAmount.trim(),
                    confidence = previous?.confidence ?: 100,
                    isAccepted = previous?.isAccepted ?: false,
                    isManual = true,
                )
                if (previous == null) {
                    documentRepository.insertDraftTransaction(draft)
                } else {
                    documentRepository.updateDraftTransaction(draft)
                }
            }
        }
    }

    fun saveConfirmed() {
        val documentId = (uiState.value as? ReviewUiState.Loaded)?.document?.id ?: return
        if (_drafts.value.isEmpty()) return
        viewModelScope.launch {
            runAction(ReviewActionState.Saving) {
                val result = documentRepository.saveDraftTransactions(documentId)
                _actionState.value = ReviewActionState.Saved(result)
            }
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
        } catch (throwable: Throwable) {
            _actionState.value = ReviewActionState.Error(
                throwable.message ?: "AI extraction failed. Please try again or add manually.",
            )
        }
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
    data class Error(val message: String) : ReviewActionState
}
