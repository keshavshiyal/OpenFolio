package com.openfolio.feature.review

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.openfolio.core.data.local.entity.DocumentEntity
import com.openfolio.core.data.local.entity.DraftTransactionEntity
import com.openfolio.core.domain.repository.DocumentRepository
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

    private val _actionState = MutableStateFlow<ReviewActionState>(ReviewActionState.Idle)
    val actionState: StateFlow<ReviewActionState> = _actionState.asStateFlow()

    private var draftsJob: Job? = null

    fun loadDocument(documentId: String) {
        draftsJob?.cancel()
        _uiState.value = ReviewUiState.Loading
        _drafts.value = emptyList()
        _actionState.value = ReviewActionState.Idle
        viewModelScope.launch {
            try {
                val document = documentRepository.getDocumentById(documentId)
                if (document == null) {
                    _uiState.value = ReviewUiState.NotFound
                    return@launch
                }

                documentRepository.insertDraftTransactionsIfEmpty(
                    documentId = documentId,
                    drafts = mockDraftTransactions(documentId),
                )
                _uiState.value = ReviewUiState.Loaded(document)
                draftsJob = viewModelScope.launch {
                    documentRepository.getDraftTransactions(documentId).collect { items ->
                        _drafts.value = items
                    }
                }
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                _uiState.value = ReviewUiState.Error(
                    exception.message ?: "Unable to load this document.",
                )
            }
        }
    }

    fun acceptAllHighConfidence() {
        val documentId = (uiState.value as? ReviewUiState.Loaded)?.document?.id ?: return
        viewModelScope.launch {
            performAction {
                documentRepository.acceptHighConfidenceDrafts(
                    documentId = documentId,
                    minimumConfidence = HIGH_CONFIDENCE_THRESHOLD,
                )
            }
        }
    }

    fun setDraftAccepted(draftId: String, accepted: Boolean) {
        viewModelScope.launch {
            performAction { documentRepository.setDraftAccepted(draftId, accepted) }
        }
    }

    fun saveConfirmed() {
        val documentId = (uiState.value as? ReviewUiState.Loaded)?.document?.id ?: return
        viewModelScope.launch {
            performAction {
                val savedCount = documentRepository.saveAcceptedDraftTransactions(documentId)
                _actionState.value = ReviewActionState.Saved(savedCount)
            }
        }
    }

    private suspend fun performAction(action: suspend () -> Unit) {
        _actionState.value = ReviewActionState.Working
        try {
            action()
            if (_actionState.value == ReviewActionState.Working) {
                _actionState.value = ReviewActionState.Idle
            }
        } catch (exception: CancellationException) {
            _actionState.value = ReviewActionState.Idle
            throw exception
        } catch (exception: Exception) {
            _actionState.value = ReviewActionState.Error(
                exception.message ?: "Unable to update these transactions.",
            )
        }
    }

    private fun mockDraftTransactions(documentId: String): List<DraftTransactionEntity> =
        listOf(
            MockDraft("Reliance Industries", "BUY", "10", "2850.00", "28500.00", 98),
            MockDraft("HDFC Bank", "BUY", "5", "1680.00", "8400.00", 98),
            MockDraft("Tata Consultancy Services", "SELL", "2", "4125.00", "8250.00", 98),
            MockDraft("ICICI Prudential Bluechip Fund", "BUY", "12.5", "78.40", "980.00", 85),
            MockDraft("Nippon India Small Cap Fund", "BUY", "8", "156.25", "1250.00", 60),
        ).map { mock ->
            DraftTransactionEntity(
                documentId = documentId,
                assetName = mock.assetName,
                transactionType = mock.transactionType,
                quantity = mock.quantity,
                price = mock.price,
                netAmount = mock.netAmount,
                confidence = mock.confidence,
            )
        }

    private data class MockDraft(
        val assetName: String,
        val transactionType: String,
        val quantity: String,
        val price: String,
        val netAmount: String,
        val confidence: Int,
    )

    private companion object {
        const val HIGH_CONFIDENCE_THRESHOLD = 90
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
    data object Working : ReviewActionState
    data class Saved(val count: Int) : ReviewActionState
    data class Error(val message: String) : ReviewActionState
}
