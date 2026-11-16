package com.openfolio.feature.home

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.openfolio.core.data.local.entity.DocumentEntity
import com.openfolio.core.domain.repository.DocumentRepository
import com.openfolio.core.domain.repository.DuplicateDocumentException
import com.openfolio.core.domain.usecase.GetDashboardDataUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import java.math.BigDecimal
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

@HiltViewModel
class HomeViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val documentRepository: DocumentRepository,
    private val getDashboardDataUseCase: GetDashboardDataUseCase,
) : ViewModel() {
    private val _uiState = MutableStateFlow<HomeUiState>(HomeUiState.Loading)
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    private val _showValues = MutableStateFlow(true)
    private val _selectedPeriod = MutableStateFlow("1M")

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

        viewModelScope.launch {
            combine(
                getDashboardDataUseCase(),
                _showValues,
                _selectedPeriod,
            ) { data, showValues, period ->
                if ((data.totalAssets == BigDecimal.ZERO || data.totalAssets.compareTo(BigDecimal.ZERO) == 0) &&
                    (data.totalLiabilities == BigDecimal.ZERO || data.totalLiabilities.compareTo(BigDecimal.ZERO) == 0) &&
                    data.recentTransactions.isEmpty()
                ) {
                    HomeUiState.Empty
                } else {
                    val allocations = data.allocations.mapIndexed { index, alloc ->
                        val color = AllocationColor.entries.getOrElse(index) { AllocationColor.Slot5 }
                        AssetAllocation(
                            label = alloc.label,
                            percentage = alloc.percentage,
                            color = color,
                        )
                    }
                    HomeUiState.Success(
                        data = data,
                        showValues = showValues,
                        selectedPeriod = period,
                        allocations = allocations,
                    )
                }
            }.collect { state ->
                _uiState.value = state
            }
        }
    }

    fun toggleValuesVisibility() {
        _showValues.update { !it }
    }

    fun selectPeriod(period: String) {
        require(period in PERIODS) { "Unsupported chart period: $period" }
        _selectedPeriod.value = period
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
            } catch (throwable: Throwable) {
                _ingestionState.value = IngestionState.Error(
                    throwable.message ?: "Failed to read file",
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
