package com.openfolio.feature.home

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.openfolio.core.data.local.entity.DocumentEntity
import com.openfolio.core.domain.repository.DocumentRepository
import com.openfolio.core.domain.repository.DuplicateDocumentException
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

@HiltViewModel
class HomeViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val documentRepository: DocumentRepository,
) : ViewModel() {
    private val _uiState = MutableStateFlow(HomeUiState())
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    private val _ingestionState = MutableStateFlow<IngestionState>(IngestionState.Idle)
    val ingestionState: StateFlow<IngestionState> = _ingestionState.asStateFlow()

    private val _documents = MutableStateFlow<List<DocumentEntity>>(emptyList())
    val documents: StateFlow<List<DocumentEntity>> = _documents.asStateFlow()

    init {
        viewModelScope.launch {
            documentRepository.getAllDocuments().collect { documents ->
                _documents.value = documents
            }
        }
    }

    fun toggleValuesVisibility() {
        _uiState.update { it.copy(showValues = !it.showValues) }
    }

    fun selectPeriod(period: String) {
        require(period in PERIODS) { "Unsupported chart period: $period" }
        _uiState.update { it.copy(selectedPeriod = period) }
    }

    fun ingestFile(uri: Uri) {
        viewModelScope.launch {
            _ingestionState.value = IngestionState.Loading
            try {
                val document = documentRepository.ingestFile(uri, context)
                _ingestionState.value = IngestionState.Success(document.fileName)
            } catch (exception: CancellationException) {
                _ingestionState.value = IngestionState.Idle
                throw exception
            } catch (exception: DuplicateDocumentException) {
                _ingestionState.value = IngestionState.Error("File already imported")
            } catch (exception: Exception) {
                _ingestionState.value = IngestionState.Error(
                    exception.message ?: "Failed to read file",
                )
            }
        }
    }

    fun clearIngestionState() {
        _ingestionState.value = IngestionState.Idle
    }

    private companion object {
        val PERIODS = setOf("1M", "6M", "1Y", "ALL")
    }
}

sealed interface IngestionState {
    data object Idle : IngestionState
    data object Loading : IngestionState
    data class Success(val fileName: String) : IngestionState
    data class Error(val message: String) : IngestionState
}
