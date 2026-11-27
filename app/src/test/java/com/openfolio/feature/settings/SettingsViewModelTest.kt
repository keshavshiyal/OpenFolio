package com.openfolio.feature.settings

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import com.openfolio.core.data.local.export.AppBackupManager
import com.openfolio.core.data.local.export.BackupDecryptionException
import com.openfolio.core.data.local.export.BackupExportSummary
import com.openfolio.core.data.local.export.BackupRestoreSummary
import com.openfolio.core.data.local.export.CsvReportExportResult
import com.openfolio.core.data.local.export.CsvReportExporter
import com.openfolio.core.data.local.export.CsvReportType
import com.openfolio.core.data.local.export.DatabaseExportMetadata
import com.openfolio.core.data.local.export.DatabaseExporter
import com.openfolio.core.data.local.export.ExportResult
import com.openfolio.core.data.repository.SettingsRepository
import java.io.ByteArrayInputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.io.ByteArrayOutputStream

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModelTest {
    private val testDispatcher = StandardTestDispatcher()
    private val repository = mock<SettingsRepository>()
    private val databaseExporter = mock<DatabaseExporter>()
    private val context = mock<Context>()
    private val contentResolver = mock<ContentResolver>()
    private val apiKeyFlow = MutableStateFlow("initial-key")
    private val modelNameFlow = MutableStateFlow(SettingsRepository.DEFAULT_GEMINI_MODEL_NAME)

    @Before
    fun setUp() = runTest {
        Dispatchers.setMain(testDispatcher)
        whenever(repository.geminiApiKeyFlow).thenReturn(apiKeyFlow)
        whenever(repository.geminiModelNameFlow).thenReturn(modelNameFlow)
        whenever(context.contentResolver).thenReturn(contentResolver)
        whenever(databaseExporter.getExportMetadata()).thenReturn(
            DatabaseExportMetadata(
                databaseVersion = 9,
                tableCount = 10,
                totalRowCount = 42L,
                roomIdentityHash = "hash123",
                tables = emptyList(),
            )
        )
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun initialStateLoadsApiKeyAndDatabaseMetadata() = runTest {
        val viewModel = SettingsViewModel(repository, databaseExporter, context, testDispatcher)
        advanceUntilIdle()

        assertEquals("initial-key", viewModel.uiState.value.apiKey)
        assertFalse(viewModel.uiState.value.isKeyVisible)
        assertEquals(9, viewModel.uiState.value.databaseVersion)
        assertEquals(10, viewModel.uiState.value.tableCount)
        assertEquals(42L, viewModel.uiState.value.totalRowCount)
    }

    @Test
    fun onApiKeyChangeUpdatesState() = runTest {
        val viewModel = SettingsViewModel(repository, databaseExporter, context, testDispatcher)
        advanceUntilIdle()

        viewModel.onApiKeyChange("new-key-123")
        assertEquals("new-key-123", viewModel.uiState.value.apiKey)
    }

    @Test
    fun toggleKeyVisibilityFlipsVisibility() = runTest {
        val viewModel = SettingsViewModel(repository, databaseExporter, context, testDispatcher)
        advanceUntilIdle()

        assertFalse(viewModel.uiState.value.isKeyVisible)
        viewModel.toggleKeyVisibility()
        assertTrue(viewModel.uiState.value.isKeyVisible)
        viewModel.toggleKeyVisibility()
        assertFalse(viewModel.uiState.value.isKeyVisible)
    }

    @Test
    fun saveApiKeyInvokesRepositoryAndEmitsSnackbarEvent() = runTest {
        val viewModel = SettingsViewModel(repository, databaseExporter, context, testDispatcher)
        advanceUntilIdle()

        viewModel.onApiKeyChange("saved-key")
        viewModel.onModelNameChange("gemini-3.6-flash")
        val events = mutableListOf<SettingsEvent>()
        val job = launch { viewModel.events.collect { events.add(it) } }

        viewModel.saveApiKey()
        advanceUntilIdle()

        verify(repository).setGeminiApiKey("saved-key")
        verify(repository).setGeminiModelName("gemini-3.6-flash")
        assertEquals("gemini-3.8-flash", SettingsRepository.DEFAULT_GEMINI_MODEL_NAME)
        assertEquals("gemini-3.8-flash", SettingsRepository.resolveMigratedModelName("gemini-2.5-flash"))
        assertEquals("gemini-3.6-flash", SettingsRepository.resolveMigratedModelName("gemini-3.6-flash"))
        assertEquals(1, events.size)
        assertTrue(events[0] is SettingsEvent.ShowSnackbar)
        assertEquals("API key saved securely.", (events[0] as SettingsEvent.ShowSnackbar).message)
        job.cancel()
    }

    @Test
    fun exportDatabaseToUriSuccessUpdatesStateAndEmitsSnackbar() = runTest {
        val testUri = mock<Uri>()
        val outputStream = ByteArrayOutputStream()
        whenever(contentResolver.openOutputStream(testUri)).thenReturn(outputStream)
        whenever(databaseExporter.exportToJson(any())).thenReturn(
            ExportResult(
                tableCount = 10,
                totalRowCount = 42L,
                databaseVersion = 9,
                defaultFileName = "openfolio_db_v9_20261116.json",
                exportedAt = "2026-11-16T15:00:00Z",
            )
        )

        val viewModel = SettingsViewModel(repository, databaseExporter, context, testDispatcher)
        advanceUntilIdle()

        val events = mutableListOf<SettingsEvent>()
        val job = launch { viewModel.events.collect { events.add(it) } }

        viewModel.exportDatabaseToUri(testUri)
        advanceUntilIdle()

        assertEquals("openfolio_db_v9_20261116.json", viewModel.uiState.value.lastExportedFileName)
        assertFalse(viewModel.uiState.value.isExporting)
        assertEquals(1, events.size)
        assertTrue(events[0] is SettingsEvent.ShowSnackbar)
        assertTrue((events[0] as SettingsEvent.ShowSnackbar).message.contains("Exported 10 tables"))
        job.cancel()
    }

    @Test
    fun exportDatabaseToUriFailureEmitsErrorSnackbar() = runTest {
        val testUri = mock<Uri>()
        whenever(contentResolver.openOutputStream(testUri)).thenReturn(null)

        val viewModel = SettingsViewModel(repository, databaseExporter, context, testDispatcher)
        advanceUntilIdle()

        val events = mutableListOf<SettingsEvent>()
        val job = launch { viewModel.events.collect { events.add(it) } }

        viewModel.exportDatabaseToUri(testUri)
        advanceUntilIdle()

        assertFalse(viewModel.uiState.value.isExporting)
        assertEquals(1, events.size)
        assertTrue(events[0] is SettingsEvent.ShowSnackbar)
        assertTrue((events[0] as SettingsEvent.ShowSnackbar).message.contains("Failed to export"))
        job.cancel()
    }

    @Test
    fun getDefaultExportFileNameContainsDbVersion() = runTest {
        val viewModel = SettingsViewModel(repository, databaseExporter, context, testDispatcher)
        advanceUntilIdle()

        val fileName = viewModel.getDefaultExportFileName()
        assertTrue(fileName.startsWith("openfolio_db_v9_"))
        assertTrue(fileName.endsWith(".json"))
    }

    @Test
    fun exportAndImportEncryptedBackupEmitExpectedSnackbarsAndRefreshMetadata() = runTest {
        val backupManager = mock<AppBackupManager>()
        val csvExporter = mock<CsvReportExporter>()
        val exportUri = mock<Uri>()
        val importUri = mock<Uri>()

        whenever(contentResolver.openOutputStream(exportUri)).thenReturn(ByteArrayOutputStream())
        whenever(contentResolver.openInputStream(importUri)).thenReturn(ByteArrayInputStream(byteArrayOf(1, 2, 3)))
        whenever(backupManager.exportEncryptedBackup(any(), any(), any())).thenReturn(
            BackupExportSummary("backup.ofbackup", 10, 42L, 3, 2, "now"),
        )
        whenever(backupManager.importEncryptedBackup(any(), any())).thenReturn(
            BackupRestoreSummary(10, 42L, 3, 2, true),
        )

        val viewModel = SettingsViewModel(
            settingsRepository = repository,
            databaseExporter = databaseExporter,
            context = context,
            ioDispatcher = testDispatcher,
            appBackupManager = backupManager,
            csvReportExporter = csvExporter,
        )
        advanceUntilIdle()

        val events = mutableListOf<SettingsEvent>()
        val job = launch { viewModel.events.collect { events.add(it) } }

        viewModel.exportEncryptedBackupToUri(exportUri, "secret123", includeApiKey = false)
        advanceUntilIdle()
        viewModel.importEncryptedBackupFromUri(importUri, "secret123")
        advanceUntilIdle()

        assertFalse(viewModel.uiState.value.isExportingBackup)
        assertFalse(viewModel.uiState.value.isImportingBackup)
        assertEquals(2, events.size)
        assertTrue((events[0] as SettingsEvent.ShowSnackbar).message.contains("Encrypted backup saved"))
        assertTrue((events[1] as SettingsEvent.ShowSnackbar).message.contains("Restored 42 records"))
        job.cancel()
    }

    @Test
    fun wrongBackupPasswordEmitsFriendlyDecryptionMessage() = runTest {
        val backupManager = mock<AppBackupManager>()
        val importUri = mock<Uri>()
        whenever(contentResolver.openInputStream(importUri)).thenReturn(ByteArrayInputStream(byteArrayOf(1, 2, 3)))
        whenever(backupManager.importEncryptedBackup(any(), any())).thenAnswer {
            throw BackupDecryptionException("Bad tag")
        }

        val viewModel = SettingsViewModel(
            settingsRepository = repository,
            databaseExporter = databaseExporter,
            context = context,
            ioDispatcher = testDispatcher,
            appBackupManager = backupManager,
        )
        advanceUntilIdle()

        val events = mutableListOf<SettingsEvent>()
        val job = launch { viewModel.events.collect { events.add(it) } }

        viewModel.importEncryptedBackupFromUri(importUri, "wrong")
        advanceUntilIdle()

        assertFalse(viewModel.uiState.value.isImportingBackup)
        assertEquals(
            "Incorrect backup password or corrupted archive.",
            (events.single() as SettingsEvent.ShowSnackbar).message,
        )
        job.cancel()
    }

    @Test
    fun exportCsvReportToUriWritesReportAndEmitsConfirmation() = runTest {
        val csvExporter = mock<CsvReportExporter>()
        val csvUri = mock<Uri>()
        whenever(contentResolver.openOutputStream(csvUri)).thenReturn(ByteArrayOutputStream())
        whenever(csvExporter.exportReport(any(), any())).thenReturn(
            CsvReportExportResult(CsvReportType.CURRENT_HOLDINGS, 5, "holdings.csv"),
        )

        val viewModel = SettingsViewModel(
            settingsRepository = repository,
            databaseExporter = databaseExporter,
            context = context,
            ioDispatcher = testDispatcher,
            csvReportExporter = csvExporter,
        )
        advanceUntilIdle()

        val events = mutableListOf<SettingsEvent>()
        val job = launch { viewModel.events.collect { events.add(it) } }

        viewModel.exportCsvReportToUri(CsvReportType.CURRENT_HOLDINGS, csvUri)
        advanceUntilIdle()

        assertEquals(null, viewModel.uiState.value.activeCsvReportType)
        assertEquals(
            "Exported Current Holdings (5 rows) to CSV.",
            (events.single() as SettingsEvent.ShowSnackbar).message,
        )
        job.cancel()
    }
}
