package com.openfolio.feature.settings

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.openfolio.core.data.local.export.AppBackupManager
import com.openfolio.core.data.local.export.BackupDecryptionException
import com.openfolio.core.data.local.export.CsvReportExporter
import com.openfolio.core.data.local.export.CsvReportType
import com.openfolio.core.data.local.export.DatabaseExporter
import com.openfolio.core.data.local.export.InvalidBackupFormatException
import com.openfolio.core.data.local.export.LegalDossierGenerator
import com.openfolio.core.data.repository.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
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
    val isExportingBackup: Boolean = false,
    val isImportingBackup: Boolean = false,
    val activeCsvReportType: CsvReportType? = null,
    val databaseVersion: Int? = null,
    val tableCount: Int? = null,
    val totalRowCount: Long? = null,
    val lastExportedFileName: String? = null,
    val apiKeyStorageError: String? = null,
)

sealed interface SettingsEvent {
    data class ShowSnackbar(val message: String) : SettingsEvent
    data class ShareExportFile(
        val uri: Uri,
        val title: String,
        val mimeType: String = "application/json",
    ) : SettingsEvent
}

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository,
    private val databaseExporter: DatabaseExporter,
    @ApplicationContext private val context: Context,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val legalDossierGenerator: LegalDossierGenerator? = null,
    private val appBackupManager: AppBackupManager? = null,
    private val csvReportExporter: CsvReportExporter? = null,
) : ViewModel() {

    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    private val _events = MutableSharedFlow<SettingsEvent>()
    val events: SharedFlow<SettingsEvent> = _events.asSharedFlow()

    init {
        viewModelScope.launch {
            settingsRepository.geminiApiKeyFlow.collect { key ->
                _uiState.update { it.copy(apiKey = key) }
            }
        }
        viewModelScope.launch {
            val modelFlow: StateFlow<String>? = settingsRepository.geminiModelNameFlow
            modelFlow?.collect { model ->
                _uiState.update { it.copy(modelName = model) }
            }
        }
        viewModelScope.launch {
            val errorFlow: StateFlow<String?>? = settingsRepository.apiKeyStorageError
            errorFlow?.collect { error ->
                _uiState.update { it.copy(apiKeyStorageError = error) }
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

    fun clearCorruptedApiKeyStorage() {
        viewModelScope.launch {
            settingsRepository.clearCorruptedApiKey()
            _uiState.update { it.copy(apiKey = "", apiKeyStorageError = null) }
            _events.emit(SettingsEvent.ShowSnackbar("Secure key storage reset. Please re-enter your API key."))
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
                val exportDir = prepareExportDir()
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
                val exportDir = prepareExportDir()
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
                _events.emit(SettingsEvent.ShareExportFile(shareUri, fileName, "text/markdown"))
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

    fun getDefaultBackupFileName(): String {
        appBackupManager?.let { return it.getDefaultBackupFileName() }
        val timestamp = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss")
            .withZone(ZoneOffset.UTC)
            .format(Instant.now())
        return "openfolio_backup_${timestamp}.ofbackup"
    }

    fun exportEncryptedBackupToUri(
        uri: Uri,
        password: String,
        includeApiKey: Boolean = false,
    ) {
        val manager = appBackupManager
        if (manager == null) {
            viewModelScope.launch {
                _events.emit(SettingsEvent.ShowSnackbar("Backup service is unavailable."))
            }
            return
        }
        viewModelScope.launch(ioDispatcher) {
            _uiState.value = _uiState.value.copy(isExportingBackup = true)
            try {
                val outputStream = context.contentResolver.openOutputStream(uri)
                    ?: throw IllegalStateException("Could not open destination file for writing.")
                val summary = outputStream.use { stream ->
                    manager.exportEncryptedBackup(stream, password, includeApiKey)
                }
                _events.emit(
                    SettingsEvent.ShowSnackbar(
                        "Encrypted backup saved (${summary.totalRows} records, ${summary.inboxDocumentFilesCount} inbox file(s))."
                    )
                )
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                _events.emit(
                    SettingsEvent.ShowSnackbar(
                        "Failed to export backup: ${t.localizedMessage ?: t.message ?: "Unknown error"}"
                    )
                )
            } finally {
                _uiState.value = _uiState.value.copy(isExportingBackup = false)
            }
        }
    }

    fun exportAndShareEncryptedBackup(
        password: String,
        includeApiKey: Boolean = false,
    ) {
        val manager = appBackupManager
        if (manager == null) {
            viewModelScope.launch {
                _events.emit(SettingsEvent.ShowSnackbar("Backup service is unavailable."))
            }
            return
        }
        viewModelScope.launch(ioDispatcher) {
            _uiState.value = _uiState.value.copy(isExportingBackup = true)
            try {
                val exportDir = prepareExportDir()
                val fileName = getDefaultBackupFileName()
                val targetFile = File(exportDir, fileName)
                val summary = targetFile.outputStream().use { stream ->
                    manager.exportEncryptedBackup(stream, password, includeApiKey)
                }
                val shareUri = FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.fileprovider",
                    targetFile,
                )
                _events.emit(
                    SettingsEvent.ShareExportFile(
                        uri = shareUri,
                        title = fileName,
                        mimeType = "application/octet-stream",
                    )
                )
                _events.emit(
                    SettingsEvent.ShowSnackbar(
                        "Encrypted backup ready (${summary.totalRows} records, ${summary.inboxDocumentFilesCount} inbox file(s))."
                    )
                )
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                _events.emit(
                    SettingsEvent.ShowSnackbar(
                        "Failed to share backup: ${t.localizedMessage ?: t.message ?: "Unknown error"}"
                    )
                )
            } finally {
                _uiState.value = _uiState.value.copy(isExportingBackup = false)
            }
        }
    }

    fun importEncryptedBackupFromUri(
        uri: Uri,
        password: String,
    ) {
        val manager = appBackupManager
        if (manager == null) {
            viewModelScope.launch {
                _events.emit(SettingsEvent.ShowSnackbar("Restore service is unavailable."))
            }
            return
        }
        viewModelScope.launch(ioDispatcher) {
            _uiState.value = _uiState.value.copy(isImportingBackup = true)
            try {
                val inputStream = context.contentResolver.openInputStream(uri)
                    ?: throw IllegalStateException("Could not open backup archive for reading.")
                val summary = inputStream.use { stream ->
                    manager.importEncryptedBackup(stream, password)
                }
                loadDatabaseSummary()
                _events.emit(
                    SettingsEvent.ShowSnackbar(
                        "Restored ${summary.totalRows} records across ${summary.tableCount} tables (${summary.restoredDocumentFilesCount} inbox file(s))."
                    )
                )
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                val errorText = when (t) {
                    is BackupDecryptionException -> "Incorrect backup password or corrupted archive."
                    is InvalidBackupFormatException -> t.message ?: "Selected file is not a valid OpenFolio backup."
                    else -> "Restore failed: ${t.localizedMessage ?: t.message ?: "Unknown error"}"
                }
                _events.emit(SettingsEvent.ShowSnackbar(errorText))
            } finally {
                _uiState.value = _uiState.value.copy(isImportingBackup = false)
            }
        }
    }

    fun getDefaultCsvFileName(reportType: CsvReportType): String {
        csvReportExporter?.let { return it.getDefaultFileName(reportType) }
        val timestamp = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss")
            .withZone(ZoneOffset.UTC)
            .format(Instant.now())
        return "openfolio_${reportType.name.lowercase()}_${timestamp}.csv"
    }

    fun exportCsvReportToUri(reportType: CsvReportType, uri: Uri) {
        val exporter = csvReportExporter
        if (exporter == null) {
            viewModelScope.launch {
                _events.emit(SettingsEvent.ShowSnackbar("CSV exporter is unavailable."))
            }
            return
        }
        viewModelScope.launch(ioDispatcher) {
            _uiState.value = _uiState.value.copy(activeCsvReportType = reportType)
            try {
                val outputStream = context.contentResolver.openOutputStream(uri)
                    ?: throw IllegalStateException("Could not open destination CSV file for writing.")
                val result = outputStream.use { stream ->
                    exporter.exportReport(reportType, stream)
                }
                _events.emit(
                    SettingsEvent.ShowSnackbar(
                        "Exported ${result.reportType.title} (${result.rowCount} rows) to CSV."
                    )
                )
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                _events.emit(
                    SettingsEvent.ShowSnackbar(
                        "Failed to export ${reportType.title}: ${t.localizedMessage ?: t.message ?: "Unknown error"}"
                    )
                )
            } finally {
                _uiState.value = _uiState.value.copy(activeCsvReportType = null)
            }
        }
    }

    fun exportAndShareCsvReport(reportType: CsvReportType) {
        val exporter = csvReportExporter
        if (exporter == null) {
            viewModelScope.launch {
                _events.emit(SettingsEvent.ShowSnackbar("CSV exporter is unavailable."))
            }
            return
        }
        viewModelScope.launch(ioDispatcher) {
            _uiState.value = _uiState.value.copy(activeCsvReportType = reportType)
            try {
                val exportDir = prepareExportDir()
                val fileName = getDefaultCsvFileName(reportType)
                val targetFile = File(exportDir, fileName)
                val result = targetFile.outputStream().use { stream ->
                    exporter.exportReport(reportType, stream)
                }
                val shareUri = FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.fileprovider",
                    targetFile,
                )
                _events.emit(
                    SettingsEvent.ShareExportFile(
                        uri = shareUri,
                        title = fileName,
                        mimeType = "text/csv",
                    )
                )
                _events.emit(
                    SettingsEvent.ShowSnackbar(
                        "${result.reportType.title} CSV ready (${result.rowCount} rows)."
                    )
                )
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                _events.emit(
                    SettingsEvent.ShowSnackbar(
                        "Failed to share ${reportType.title}: ${t.localizedMessage ?: t.message ?: "Unknown error"}"
                    )
                )
            } finally {
                _uiState.value = _uiState.value.copy(activeCsvReportType = null)
            }
        }
    }

    private fun prepareExportDir(): File {
        val exportDir = File(context.cacheDir, "exports").apply { mkdirs() }
        val cutoffMillis = System.currentTimeMillis() - STALE_EXPORT_MAX_AGE_MS
        exportDir.listFiles()?.forEach { file ->
            if (file.isFile && file.lastModified() < cutoffMillis) {
                runCatching { file.delete() }
            }
        }
        return exportDir
    }

    private companion object {
        const val STALE_EXPORT_MAX_AGE_MS = 15L * 60L * 1000L
    }
}
