package com.openfolio.feature.home

import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

@HiltViewModel
class HomeViewModel @Inject constructor() : ViewModel() {
    private val _uiState = MutableStateFlow(HomeUiState())
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    fun toggleValuesVisibility() {
        _uiState.update { it.copy(showValues = !it.showValues) }
    }

    fun selectPeriod(period: String) {
        require(period in PERIODS) { "Unsupported chart period: $period" }
        _uiState.update { it.copy(selectedPeriod = period) }
    }

    private companion object {
        val PERIODS = setOf("1M", "6M", "1Y", "ALL")
    }
}
