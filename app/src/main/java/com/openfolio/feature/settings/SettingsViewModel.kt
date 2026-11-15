package com.openfolio.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.openfolio.core.data.repository.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class SettingsUiState(
    val apiKey: String = "",
    val isKeyVisible: Boolean = false,
    val isSaving: Boolean = false,
)

sealed interface SettingsEvent {
    data class ShowSnackbar(val message: String) : SettingsEvent
}

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository,
) : ViewModel() {
    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    private val _events = MutableSharedFlow<SettingsEvent>()
    val events: SharedFlow<SettingsEvent> = _events.asSharedFlow()

    init {
        viewModelScope.launch {
            settingsRepository.geminiApiKeyFlow.collect { key ->
                _uiState.value = _uiState.value.copy(apiKey = key)
            }
        }
    }

    fun onApiKeyChange(newKey: String) {
        _uiState.value = _uiState.value.copy(apiKey = newKey)
    }

    fun toggleKeyVisibility() {
        _uiState.value = _uiState.value.copy(isKeyVisible = !_uiState.value.isKeyVisible)
    }

    fun saveApiKey() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isSaving = true)
            try {
                settingsRepository.setGeminiApiKey(_uiState.value.apiKey)
                _events.emit(SettingsEvent.ShowSnackbar("API key saved securely."))
            } catch (t: Throwable) {
                _events.emit(SettingsEvent.ShowSnackbar(t.message ?: "Failed to save key."))
            } finally {
                _uiState.value = _uiState.value.copy(isSaving = false)
            }
        }
    }
}
