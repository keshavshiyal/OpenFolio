package com.openfolio.feature.review

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.openfolio.core.data.local.entity.DocumentEntity
import com.openfolio.core.domain.repository.DocumentRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

@HiltViewModel
class ReviewViewModel @Inject constructor(
    private val documentRepository: DocumentRepository,
) : ViewModel() {
    private val _uiState = MutableStateFlow<ReviewUiState>(ReviewUiState.Loading)
    val uiState: StateFlow<ReviewUiState> = _uiState.asStateFlow()

    fun loadDocument(documentId: String) {
        viewModelScope.launch {
            _uiState.value = ReviewUiState.Loading
            try {
                val document = documentRepository.getDocumentById(documentId)
                _uiState.value = document?.let { ReviewUiState.Loaded(it) }
                    ?: ReviewUiState.NotFound
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                _uiState.value = ReviewUiState.Error(
                    exception.message ?: "Unable to load this document.",
                )
            }
        }
    }
}

sealed interface ReviewUiState {
    data object Loading : ReviewUiState
    data object NotFound : ReviewUiState
    data class Loaded(val document: DocumentEntity) : ReviewUiState
    data class Error(val message: String) : ReviewUiState
}
