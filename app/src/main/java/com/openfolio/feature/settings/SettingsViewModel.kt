package com.openfolio.feature.settings

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.openfolio.core.data.local.export.DatabaseExporter
import com.openfolio.core.data.local.export.LegalDossierGenerator
import com.openfolio.core.data.repository.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import javax.inject.Inject

data class SettingsUiState(
    val apiKey: String = "",
    val modelName: String = SettingsRepository.DEFAULT_GEMINI_MODEL_NAME,
    val isKeyVisible: Boolean = false,
    val isSaving: Boolean = false,
    val isExporting: Boolean = false,
    val isExportingDossier: Boolean = false,
    val databaseVersion: Int? = null,
    val tableCount: Int? = null,
    val totalRowCount: Long? = null,
    val lastExportedFileName: String? = null,
)

sealed interface SettingsEvent {
    data class ShowSnackbar(val message: String) : SettingsEvent
    data class ShareExportFile(val uri: Uri, val title: String) : SettingsEvent
}

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository,
    private val databaseExporter: DatabaseExporter,
    @ApplicationContext private val context: Context,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val legalDossierGenerator: LegalDossierGenerator? = null,
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
        viewModelScope.launch {
            val modelFlow: StateFlow<String>? = settingsRepository.geminiModelNameFlow
            modelFlow?.collect { model ->
                _uiState.value = _uiState.value.copy(modelName = model)
            }
        }
        loadDatabaseSummary()
    }

    fun loadDatabaseSummary() {
        viewModelScope.launch(ioDispatcher) {
            try {
                val metadata = databaseExporter.getExportMetadata()
                _uiState.value = _uiState.value.copy(
                    databaseVersion = metadata.databaseVersion,
                    tableCount = metadata.tableCount,
                    totalRowCount = metadata.totalRowCount,
                )
            } catch (_: Throwable) {
                // Ignore metadata preview failures silently
            }
        }
    }

    fun onApiKeyChange(newKey: String) {
        _uiState.value = _uiState.value.copy(apiKey = newKey)
    }

    fun onModelNameChange(newModelName: String) {
        _uiState.value = _uiState.value.copy(modelName = newModelName)
    }

    fun toggleKeyVisibility() {
        _uiState.value = _uiState.value.copy(isKeyVisible = !_uiState.value.isKeyVisible)
    }

    fun saveApiKey() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isSaving = true)
            try {
                settingsRepository.setGeminiApiKey(_uiState.value.apiKey)
                val resolvedModel = _uiState.value.modelName.trim()
                    .ifBlank { SettingsRepository.DEFAULT_GEMINI_MODEL_NAME }
                settingsRepository.setGeminiModelName(resolvedModel)
                _events.emit(SettingsEvent.ShowSnackbar("API key saved securely."))
            } catch (t: Throwable) {
                _events.emit(SettingsEvent.ShowSnackbar(t.message ?: "Failed to save key."))
            } finally {
                _uiState.value = _uiState.value.copy(isSaving = false)
            }
        }
    }

    fun getDefaultExportFileName(): String {
        val now = Instant.now()
        val timestamp = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss")
            .withZone(ZoneOffset.UTC)
            .format(now)
        val version = _uiState.value.databaseVersion ?: 1
        return "openfolio_db_v${version}_${timestamp}.json"
    }

    fun exportDatabaseToUri(uri: Uri) {
        viewModelScope.launch(ioDispatcher) {
            _uiState.value = _uiState.value.copy(isExporting = true)
            try {
                val outputStream = context.contentResolver.openOutputStream(uri)
                    ?: throw IllegalStateException("Could not open destination file for writing.")
                val result = outputStream.use { stream ->
                    databaseExporter.exportToJson(stream)
                }
                _uiState.value = _uiState.value.copy(
                    databaseVersion = result.databaseVersion,
                    tableCount = result.tableCount,
                    totalRowCount = result.totalRowCount,
                    lastExportedFileName = result.defaultFileName,
                )
                _events.emit(
                    SettingsEvent.ShowSnackbar(
                        "Exported ${result.tableCount} tables (${result.totalRowCount} records) successfully."
                    )
                )
            } catch (t: Throwable) {
                _events.emit(
                    SettingsEvent.ShowSnackbar(
                        "Failed to export database: ${t.localizedMessage ?: t.message ?: "Unknown error"}"
                    )
                )
            } finally {
                _uiState.value = _uiState.value.copy(isExporting = false)
            }
        }
    }

    fun exportAndShareDatabase() {
        viewModelScope.launch(ioDispatcher) {
            _uiState.value = _uiState.value.copy(isExporting = true)
            try {
                val exportDir = File(context.cacheDir, "exports").apply { mkdirs() }
                val metadata = databaseExporter.getExportMetadata()
                val timestamp = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss")
                    .withZone(ZoneOffset.UTC)
                    .format(Instant.now())
                val fileName = "openfolio_db_v${metadata.databaseVersion}_${timestamp}.json"
                val targetFile = File(exportDir, fileName)
                val result = targetFile.outputStream().use { stream ->
                    databaseExporter.exportToJson(stream)
                }
                _uiState.value = _uiState.value.copy(
                    databaseVersion = result.databaseVersion,
                    tableCount = result.tableCount,
                    totalRowCount = result.totalRowCount,
                    lastExportedFileName = result.defaultFileName,
                )
                val shareUri = FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.fileprovider",
                    targetFile,
                )
                _events.emit(SettingsEvent.ShareExportFile(shareUri, fileName))
                _events.emit(
                    SettingsEvent.ShowSnackbar(
                        "Export ready: ${result.tableCount} tables (${result.totalRowCount} records)."
                    )
                )
            } catch (t: Throwable) {
                _events.emit(
                    SettingsEvent.ShowSnackbar(
                        "Failed to share export: ${t.localizedMessage ?: t.message ?: "Unknown error"}"
                    )
                )
            } finally {
                _uiState.value = _uiState.value.copy(isExporting = false)
            }
        }
    }

    fun getDefaultDossierFileName(): String {
        val timestamp = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss")
            .withZone(ZoneOffset.UTC)
            .format(Instant.now())
        return "openfolio_succession_manifest_${timestamp}.md"
    }

    fun exportDossierToUri(uri: Uri) {
        val generator = legalDossierGenerator ?: return
        viewModelScope.launch(ioDispatcher) {
            _uiState.value = _uiState.value.copy(isExportingDossier = true)
            try {
                val outputStream = context.contentResolver.openOutputStream(uri)
                    ?: throw IllegalStateException("Could not open destination file for writing.")
                outputStream.use { stream ->
                    generator.writeDossierToStream(stream)
                }
                _events.emit(SettingsEvent.ShowSnackbar("Succession & Legal Dossier exported successfully."))
            } catch (t: Throwable) {
                _events.emit(
                    SettingsEvent.ShowSnackbar(
                        "Failed to export dossier: ${t.localizedMessage ?: t.message ?: "Unknown error"}"
                    )
                )
            } finally {
                _uiState.value = _uiState.value.copy(isExportingDossier = false)
            }
        }
    }

    fun exportAndShareDossier() {
        val generator = legalDossierGenerator ?: return
        viewModelScope.launch(ioDispatcher) {
            _uiState.value = _uiState.value.copy(isExportingDossier = true)
            try {
                val exportDir = File(context.cacheDir, "exports").apply { mkdirs() }
                val fileName = getDefaultDossierFileName()
                val targetFile = File(exportDir, fileName)
                targetFile.outputStream().use { stream ->
                    generator.writeDossierToStream(stream)
                }
                val shareUri = FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.fileprovider",
                    targetFile,
                )
                _events.emit(SettingsEvent.ShareExportFile(shareUri, fileName))
                _events.emit(SettingsEvent.ShowSnackbar("Succession Dossier ready to share."))
            } catch (t: Throwable) {
                _events.emit(
                    SettingsEvent.ShowSnackbar(
                        "Failed to share dossier: ${t.localizedMessage ?: t.message ?: "Unknown error"}"
                    )
                )
            } finally {
                _uiState.value = _uiState.value.copy(isExportingDossier = false)
            }
        }
    }
}
