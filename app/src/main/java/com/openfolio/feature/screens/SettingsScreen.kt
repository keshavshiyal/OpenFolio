package com.openfolio.feature.screens

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.openfolio.core.data.local.export.CsvReportType
import com.openfolio.core.data.repository.SettingsRepository
import com.openfolio.core.ui.theme.LocalOpenFolioColors
import com.openfolio.feature.settings.SettingsEvent
import com.openfolio.feature.settings.SettingsViewModel
import kotlinx.coroutines.launch

private const val DEVELOPER_ATTRIBUTION = "Developed by : Keshav shiyal"
private const val DEVELOPER_GITHUB_URL = "https://www.github.com/keshavshiyal"

private enum class BackupExportMode {
    SAVE_FILE,
    SHARE,
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel,
    modifier: Modifier = Modifier,
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val colors = LocalOpenFolioColors.current
    val snackbarHostState = remember { SnackbarHostState() }
    val coroutineScope = rememberCoroutineScope()
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    val scrollState = rememberScrollState()

    var pendingBackupMode by remember { mutableStateOf<BackupExportMode?>(null) }
    var pendingBackupPassword by remember { mutableStateOf("") }
    var pendingIncludeApiKey by remember { mutableStateOf(false) }
    var pendingRestoreUri by remember { mutableStateOf<Uri?>(null) }
    var pendingCsvType by remember { mutableStateOf<CsvReportType?>(null) }

    val exportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/json"),
    ) { uri ->
        if (uri != null) {
            viewModel.exportDatabaseToUri(uri)
        }
    }

    val dossierLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("text/markdown"),
    ) { uri ->
        if (uri != null) {
            viewModel.exportDossierToUri(uri)
        }
    }

    val backupCreateLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/octet-stream"),
    ) { uri ->
        val password = pendingBackupPassword
        val includeKey = pendingIncludeApiKey
        pendingBackupPassword = ""
        pendingIncludeApiKey = false
        if (uri != null && password.isNotEmpty()) {
            viewModel.exportEncryptedBackupToUri(
                uri = uri,
                password = password,
                includeApiKey = includeKey,
            )
        }
    }

    val backupOpenLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) {
            pendingRestoreUri = uri
        }
    }

    val csvCreateLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("text/csv"),
    ) { uri ->
        val reportType = pendingCsvType
        pendingCsvType = null
        if (uri != null && reportType != null) {
            viewModel.exportCsvReportToUri(reportType, uri)
        }
    }

    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                is SettingsEvent.ShowSnackbar -> {
                    snackbarHostState.showSnackbar(event.message)
                }
                is SettingsEvent.ShareExportFile -> {
                    val shareIntent = Intent(Intent.ACTION_SEND).apply {
                        type = event.mimeType
                        putExtra(Intent.EXTRA_STREAM, event.uri)
                        putExtra(Intent.EXTRA_SUBJECT, event.title)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    val chooserIntent = Intent.createChooser(shareIntent, "Share ${event.title}")
                    context.startActivity(chooserIntent)
                }
            }
        }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = colors.background,
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .verticalScroll(scrollState)
                .padding(horizontal = 20.dp, vertical = 24.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = "Settings",
                    style = MaterialTheme.typography.headlineMedium,
                    color = colors.content,
                )
                Text(
                    text = "Configure API keys, testing tools, and local preferences",
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.muted,
                )
            }

            // Gemini API Key Section
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                color = colors.surface,
                shadowElevation = 0.dp,
            ) {
                Column(
                    modifier = Modifier.padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Key,
                            contentDescription = null,
                            tint = colors.brand,
                            modifier = Modifier.size(24.dp),
                        )
                        Column {
                            Text(
                                text = "Gemini API (BYOK)",
                                style = MaterialTheme.typography.titleMedium,
                                color = colors.content,
                            )
                            Text(
                                text = "Bring Your Own Key for AI statement extraction",
                                style = MaterialTheme.typography.bodySmall,
                                color = colors.muted,
                            )
                        }
                    }

                    OutlinedTextField(
                        value = uiState.apiKey,
                        onValueChange = viewModel::onApiKeyChange,
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Gemini API Key") },
                        placeholder = { Text("AIzaSy...") },
                        singleLine = true,
                        visualTransformation = if (uiState.isKeyVisible) {
                            VisualTransformation.None
                        } else {
                            PasswordVisualTransformation()
                        },
                        trailingIcon = {
                            IconButton(onClick = viewModel::toggleKeyVisibility) {
                                Icon(
                                    imageVector = if (uiState.isKeyVisible) {
                                        Icons.Filled.VisibilityOff
                                    } else {
                                        Icons.Filled.Visibility
                                    },
                                    contentDescription = if (uiState.isKeyVisible) {
                                        "Hide API Key"
                                    } else {
                                        "Show API Key"
                                    },
                                    tint = colors.muted,
                                )
                            }
                        },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = colors.brand,
                            unfocusedBorderColor = colors.border,
                            focusedLabelColor = colors.brand,
                            cursorColor = colors.brand,
                            focusedTextColor = colors.content,
                            unfocusedTextColor = colors.content,
                        ),
                    )

                    OutlinedTextField(
                        value = uiState.modelName,
                        onValueChange = viewModel::onModelNameChange,
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Gemini Model") },
                        placeholder = { Text(SettingsRepository.DEFAULT_GEMINI_MODEL_NAME) },
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = colors.brand,
                            unfocusedBorderColor = colors.border,
                            focusedLabelColor = colors.brand,
                            cursorColor = colors.brand,
                            focusedTextColor = colors.content,
                            unfocusedTextColor = colors.content,
                        ),
                    )

                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        SettingsRepository.SUPPORTED_GEMINI_MODELS.forEach { preset ->
                            FilterChip(
                                selected = uiState.modelName.equals(preset, ignoreCase = true),
                                onClick = { viewModel.onModelNameChange(preset) },
                                label = {
                                    Text(
                                        text = preset,
                                        style = MaterialTheme.typography.labelSmall,
                                    )
                                },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = colors.brandSubtle,
                                    selectedLabelColor = colors.brand,
                                ),
                            )
                        }
                    }

                    Button(
                        onClick = viewModel::saveApiKey,
                        enabled = !uiState.isSaving,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = colors.brand,
                            contentColor = colors.onAccent,
                        ),
                        shape = RoundedCornerShape(10.dp),
                    ) {
                        if (uiState.isSaving) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                color = colors.onAccent,
                                strokeWidth = 2.dp,
                            )
                            Spacer(Modifier.width(8.dp))
                        }
                        Text(
                            text = "Save Key",
                            style = MaterialTheme.typography.labelLarge,
                        )
                    }

                    if (!uiState.apiKeyStorageError.isNullOrBlank()) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(
                                    color = colors.lossGlyph.copy(alpha = 0.08f),
                                    shape = RoundedCornerShape(8.dp),
                                )
                                .border(
                                    width = 1.dp,
                                    color = colors.lossGlyph.copy(alpha = 0.35f),
                                    shape = RoundedCornerShape(8.dp),
                                )
                                .padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text(
                                text = uiState.apiKeyStorageError.orEmpty(),
                                style = MaterialTheme.typography.bodySmall,
                                color = colors.content,
                            )
                            TextButton(
                                onClick = viewModel::clearCorruptedApiKeyStorage,
                                contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp),
                            ) {
                                Text(
                                    text = "Reset Key Storage",
                                    style = MaterialTheme.typography.labelLarge,
                                    color = colors.lossGlyph,
                                )
                            }
                        }
                    }

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(
                                color = colors.brandSubtle,
                                shape = RoundedCornerShape(8.dp),
                            )
                            .padding(12.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Lock,
                            contentDescription = null,
                            tint = colors.brand,
                            modifier = Modifier.size(16.dp),
                        )
                        Text(
                            text = "Your key is stored locally and never leaves your device.",
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.content,
                        )
                    }
                }
            }

            // Database Export Section
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                color = colors.surface,
                shadowElevation = 0.dp,
            ) {
                Column(
                    modifier = Modifier.padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Storage,
                            contentDescription = null,
                            tint = colors.brand,
                            modifier = Modifier.size(24.dp),
                        )
                        Column {
                            Text(
                                text = "Database Export (Testing)",
                                style = MaterialTheme.typography.titleMedium,
                                color = colors.content,
                            )
                            Text(
                                text = "Export entire encrypted database to a single structured JSON file",
                                style = MaterialTheme.typography.bodySmall,
                                color = colors.muted,
                            )
                        }
                    }

                    if (uiState.databaseVersion != null || uiState.tableCount != null) {
                        FlowRow(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            uiState.databaseVersion?.let { version ->
                                Surface(
                                    color = colors.brandSubtle,
                                    shape = RoundedCornerShape(8.dp),
                                ) {
                                    Text(
                                        text = "Schema v$version",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = colors.brand,
                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                    )
                                }
                            }
                            uiState.tableCount?.let { count ->
                                Surface(
                                    color = colors.background,
                                    shape = RoundedCornerShape(8.dp),
                                ) {
                                    Text(
                                        text = "$count Tables",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = colors.content,
                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                    )
                                }
                            }
                            uiState.totalRowCount?.let { rows ->
                                Surface(
                                    color = colors.background,
                                    shape = RoundedCornerShape(8.dp),
                                ) {
                                    Text(
                                        text = "$rows Records",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = colors.content,
                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                    )
                                }
                            }
                        }
                    }

                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Button(
                            onClick = {
                                exportLauncher.launch(viewModel.getDefaultExportFileName())
                            },
                            enabled = !uiState.isExporting,
                            modifier = Modifier
                                .weight(1f)
                                .widthIn(min = 132.dp)
                                .height(48.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = colors.brand,
                                contentColor = colors.onAccent,
                            ),
                            shape = RoundedCornerShape(10.dp),
                        ) {
                            if (uiState.isExporting) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(18.dp),
                                    color = colors.onAccent,
                                    strokeWidth = 2.dp,
                                )
                                Spacer(Modifier.width(8.dp))
                            } else {
                                Icon(
                                    imageVector = Icons.Filled.FileDownload,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp),
                                )
                                Spacer(Modifier.width(6.dp))
                            }
                            Text(
                                text = "Export File",
                                style = MaterialTheme.typography.labelLarge,
                                maxLines = 1,
                            )
                        }

                        OutlinedButton(
                            onClick = viewModel::exportAndShareDatabase,
                            enabled = !uiState.isExporting,
                            modifier = Modifier
                                .weight(1f)
                                .widthIn(min = 120.dp)
                                .height(48.dp),
                            colors = ButtonDefaults.outlinedButtonColors(
                                contentColor = colors.brand,
                            ),
                            border = BorderStroke(1.dp, colors.brand),
                            shape = RoundedCornerShape(10.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Share,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                                tint = colors.brand,
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                text = "Share",
                                style = MaterialTheme.typography.labelLarge,
                                color = colors.brand,
                                maxLines = 1,
                            )
                        }
                    }

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(
                                color = colors.brandSubtle,
                                shape = RoundedCornerShape(8.dp),
                            )
                            .padding(12.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Info,
                            contentDescription = null,
                            tint = colors.brand,
                            modifier = Modifier.size(16.dp),
                        )
                        Text(
                            text = "Includes full dynamic schema and data for all tables. Safe for migrations and schema alterations.",
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.content,
                        )
                    }
                }
            }

            // Succession & Legal Claim Dossier Card
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                color = colors.surface,
                border = BorderStroke(1.dp, colors.border),
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(
                        text = "Succession & Legal Claim Dossier",
                        style = MaterialTheme.typography.titleMedium,
                        color = colors.content,
                    )
                    Text(
                        text = "Generate a tamper-evident, legal source-of-truth dossier containing Folio numbers, Demat account numbers, Certificate IDs, and source statement SHA-256 hashes for legal heirs and succession claims.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.muted,
                    )

                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Button(
                            onClick = {
                                dossierLauncher.launch(viewModel.getDefaultDossierFileName())
                            },
                            enabled = !uiState.isExportingDossier,
                            modifier = Modifier
                                .weight(1f)
                                .widthIn(min = 140.dp)
                                .height(48.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = colors.brand,
                                contentColor = colors.onAccent,
                            ),
                            shape = RoundedCornerShape(10.dp),
                        ) {
                            if (uiState.isExportingDossier) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(18.dp),
                                    color = colors.onAccent,
                                    strokeWidth = 2.dp,
                                )
                                Spacer(Modifier.width(8.dp))
                            } else {
                                Icon(
                                    imageVector = Icons.Filled.FileDownload,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp),
                                )
                                Spacer(Modifier.width(6.dp))
                            }
                            Text(
                                text = "Export Dossier",
                                style = MaterialTheme.typography.labelLarge,
                                maxLines = 1,
                            )
                        }

                        OutlinedButton(
                            onClick = viewModel::exportAndShareDossier,
                            enabled = !uiState.isExportingDossier,
                            modifier = Modifier
                                .weight(1f)
                                .widthIn(min = 120.dp)
                                .height(48.dp),
                            colors = ButtonDefaults.outlinedButtonColors(
                                contentColor = colors.brand,
                            ),
                            border = BorderStroke(1.dp, colors.brand),
                            shape = RoundedCornerShape(10.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Share,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                                tint = colors.brand,
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                text = "Share",
                                style = MaterialTheme.typography.labelLarge,
                                color = colors.brand,
                                maxLines = 1,
                            )
                        }
                    }
                }
            }

            // Encrypted Full App Backup & Restore Card
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                color = colors.surface,
                border = BorderStroke(1.dp, colors.border),
            ) {
                Column(
                    modifier = Modifier.padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Lock,
                            contentDescription = null,
                            tint = colors.brand,
                            modifier = Modifier.size(24.dp),
                        )
                        Column {
                            Text(
                                text = "Encrypted App Backup & Restore",
                                style = MaterialTheme.typography.titleMedium,
                                color = colors.content,
                            )
                            Text(
                                text = "Password-protected archive with database, Inbox PDFs/images, extracted text & settings",
                                style = MaterialTheme.typography.bodySmall,
                                color = colors.muted,
                            )
                        }
                    }

                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Button(
                            onClick = { pendingBackupMode = BackupExportMode.SAVE_FILE },
                            enabled = !uiState.isExportingBackup && !uiState.isImportingBackup,
                            modifier = Modifier
                                .weight(1f)
                                .widthIn(min = 136.dp)
                                .height(48.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = colors.brand,
                                contentColor = colors.onAccent,
                            ),
                            shape = RoundedCornerShape(10.dp),
                        ) {
                            if (uiState.isExportingBackup) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(18.dp),
                                    color = colors.onAccent,
                                    strokeWidth = 2.dp,
                                )
                                Spacer(Modifier.width(8.dp))
                            } else {
                                Icon(
                                    imageVector = Icons.Filled.FileDownload,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp),
                                )
                                Spacer(Modifier.width(6.dp))
                            }
                            Text(
                                text = "Export Backup",
                                style = MaterialTheme.typography.labelLarge,
                                maxLines = 1,
                            )
                        }

                        OutlinedButton(
                            onClick = { pendingBackupMode = BackupExportMode.SHARE },
                            enabled = !uiState.isExportingBackup && !uiState.isImportingBackup,
                            modifier = Modifier
                                .weight(1f)
                                .widthIn(min = 120.dp)
                                .height(48.dp),
                            colors = ButtonDefaults.outlinedButtonColors(
                                contentColor = colors.brand,
                            ),
                            border = BorderStroke(1.dp, colors.brand),
                            shape = RoundedCornerShape(10.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Share,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                                tint = colors.brand,
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                text = "Share",
                                style = MaterialTheme.typography.labelLarge,
                                color = colors.brand,
                                maxLines = 1,
                            )
                        }
                    }

                    OutlinedButton(
                        onClick = { backupOpenLauncher.launch(arrayOf("*/*")) },
                        enabled = !uiState.isExportingBackup && !uiState.isImportingBackup,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp),
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = colors.content,
                        ),
                        border = BorderStroke(1.dp, colors.border),
                        shape = RoundedCornerShape(10.dp),
                    ) {
                        if (uiState.isImportingBackup) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                color = colors.brand,
                                strokeWidth = 2.dp,
                            )
                            Spacer(Modifier.width(8.dp))
                            Text("Restoring Archive...")
                        } else {
                            Icon(
                                imageVector = Icons.Filled.FileUpload,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                                tint = colors.brand,
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = "Import & Restore (.ofbackup)",
                                style = MaterialTheme.typography.labelLarge,
                                color = colors.content,
                                maxLines = 1,
                            )
                        }
                    }

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(
                                color = colors.brandSubtle,
                                shape = RoundedCornerShape(8.dp),
                            )
                            .padding(12.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Info,
                            contentDescription = null,
                            tint = colors.brand,
                            modifier = Modifier.size(16.dp),
                        )
                        Text(
                            text = "Encrypted with AES-256-GCM + PBKDF2. Passwords are never stored — keep yours safe.",
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.content,
                        )
                    }
                }
            }

            // CSV Financial Reports Card
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                color = colors.surface,
                border = BorderStroke(1.dp, colors.border),
            ) {
                Column(
                    modifier = Modifier.padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Description,
                            contentDescription = null,
                            tint = colors.brand,
                            modifier = Modifier.size(24.dp),
                        )
                        Column {
                            Text(
                                text = "CSV Financial Reports",
                                style = MaterialTheme.typography.titleMedium,
                                color = colors.content,
                            )
                            Text(
                                text = "Download or share spreadsheet-ready statements",
                                style = MaterialTheme.typography.bodySmall,
                                color = colors.muted,
                            )
                        }
                    }

                    CsvReportType.entries.forEachIndexed { index, reportType ->
                        val isBusy = uiState.activeCsvReportType == reportType
                        val anyBusy = uiState.activeCsvReportType != null
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text(
                                text = reportType.title,
                                style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold),
                                color = colors.content,
                            )
                            Text(
                                text = reportType.description,
                                style = MaterialTheme.typography.bodySmall,
                                color = colors.muted,
                            )
                            FlowRow(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                OutlinedButton(
                                    onClick = {
                                        pendingCsvType = reportType
                                        csvCreateLauncher.launch(viewModel.getDefaultCsvFileName(reportType))
                                    },
                                    enabled = !anyBusy,
                                    border = BorderStroke(1.dp, colors.brand),
                                    colors = ButtonDefaults.outlinedButtonColors(contentColor = colors.brand),
                                    shape = RoundedCornerShape(8.dp),
                                ) {
                                    if (isBusy) {
                                        CircularProgressIndicator(
                                            modifier = Modifier.size(16.dp),
                                            color = colors.brand,
                                            strokeWidth = 2.dp,
                                        )
                                        Spacer(Modifier.width(6.dp))
                                    } else {
                                        Icon(
                                            imageVector = Icons.Filled.FileDownload,
                                            contentDescription = null,
                                            modifier = Modifier.size(16.dp),
                                        )
                                        Spacer(Modifier.width(6.dp))
                                    }
                                    Text("Download CSV", maxLines = 1)
                                }

                                OutlinedButton(
                                    onClick = { viewModel.exportAndShareCsvReport(reportType) },
                                    enabled = !anyBusy,
                                    border = BorderStroke(1.dp, colors.border),
                                    colors = ButtonDefaults.outlinedButtonColors(contentColor = colors.content),
                                    shape = RoundedCornerShape(8.dp),
                                ) {
                                    Icon(
                                        imageVector = Icons.Filled.Share,
                                        contentDescription = null,
                                        modifier = Modifier.size(16.dp),
                                        tint = colors.muted,
                                    )
                                    Spacer(Modifier.width(6.dp))
                                    Text("Share", maxLines = 1)
                                }
                            }
                        }
                        if (index < CsvReportType.entries.lastIndex) {
                            HorizontalDivider(color = colors.border)
                        }
                    }
                }
            }

            // About Section
            val appVersion = remember(context) {
                runCatching {
                    context.packageManager.getPackageInfo(context.packageName, 0).versionName
                }.getOrNull()?.takeIf { it.isNotBlank() } ?: "1.0"
            }
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                color = colors.surface,
                border = BorderStroke(1.dp, colors.border),
            ) {
                Column(
                    modifier = Modifier.padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Info,
                            contentDescription = null,
                            tint = colors.brand,
                            modifier = Modifier.size(24.dp),
                        )
                        Column {
                            Text(
                                text = "About OpenFolio",
                                style = MaterialTheme.typography.titleMedium,
                                color = colors.content,
                            )
                            Text(
                                text = "Version $appVersion • Local-first encrypted portfolio tracker",
                                style = MaterialTheme.typography.bodySmall,
                                color = colors.muted,
                            )
                        }
                    }

                    HorizontalDivider(color = colors.border)

                    Text(
                        text = DEVELOPER_ATTRIBUTION,
                        style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold),
                        color = colors.content,
                    )

                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                runCatching {
                                    uriHandler.openUri(DEVELOPER_GITHUB_URL)
                                }.onFailure {
                                    coroutineScope.launch {
                                        snackbarHostState.showSnackbar("Unable to open $DEVELOPER_GITHUB_URL")
                                    }
                                }
                            },
                        shape = RoundedCornerShape(10.dp),
                        color = colors.brandSubtle,
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Code,
                                contentDescription = null,
                                tint = colors.brand,
                                modifier = Modifier.size(18.dp),
                            )
                            Text(
                                text = DEVELOPER_GITHUB_URL,
                                style = MaterialTheme.typography.bodyMedium.copy(
                                    fontWeight = FontWeight.Medium,
                                    textDecoration = TextDecoration.Underline,
                                ),
                                color = colors.brand,
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
        }
    }

    pendingBackupMode?.let { exportMode ->
        BackupExportPasswordDialog(
            mode = exportMode,
            onDismiss = { pendingBackupMode = null },
            onConfirm = { password, includeApiKey ->
                pendingBackupMode = null
                if (exportMode == BackupExportMode.SAVE_FILE) {
                    pendingBackupPassword = password
                    pendingIncludeApiKey = includeApiKey
                    backupCreateLauncher.launch(viewModel.getDefaultBackupFileName())
                } else {
                    viewModel.exportAndShareEncryptedBackup(
                        password = password,
                        includeApiKey = includeApiKey,
                    )
                }
            },
        )
    }

    pendingRestoreUri?.let { targetUri ->
        BackupRestorePasswordDialog(
            onDismiss = { pendingRestoreUri = null },
            onConfirm = { password ->
                pendingRestoreUri = null
                viewModel.importEncryptedBackupFromUri(targetUri, password)
            },
        )
    }
}

@Composable
private fun BackupExportPasswordDialog(
    mode: BackupExportMode,
    onDismiss: () -> Unit,
    onConfirm: (password: String, includeApiKey: Boolean) -> Unit,
) {
    val colors = LocalOpenFolioColors.current
    var password by remember { mutableStateOf("") }
    var confirmPassword by remember { mutableStateOf("") }
    var showPassword by remember { mutableStateOf(false) }
    var includeApiKey by remember { mutableStateOf(false) }
    var validationError by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = colors.surface,
        title = {
            Text(
                text = if (mode == BackupExportMode.SAVE_FILE) {
                    "Encrypt & Save Backup"
                } else {
                    "Encrypt & Share Backup"
                },
                style = MaterialTheme.typography.titleMedium,
                color = colors.content,
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = "Set a strong password to encrypt your database, Inbox documents, extracted text, and settings.",
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.muted,
                )
                OutlinedTextField(
                    value = password,
                    onValueChange = {
                        password = it
                        validationError = null
                    },
                    label = { Text("Backup Password") },
                    singleLine = true,
                    visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    trailingIcon = {
                        IconButton(onClick = { showPassword = !showPassword }) {
                            Icon(
                                imageVector = if (showPassword) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                                contentDescription = if (showPassword) "Hide password" else "Show password",
                                tint = colors.muted,
                            )
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = confirmPassword,
                    onValueChange = {
                        confirmPassword = it
                        validationError = null
                    },
                    label = { Text("Confirm Password") },
                    singleLine = true,
                    visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                        Text(
                            text = "Include Gemini API key",
                            style = MaterialTheme.typography.bodyMedium,
                            color = colors.content,
                        )
                        Text(
                            text = "Keep off when sharing with others",
                            style = MaterialTheme.typography.labelSmall,
                            color = colors.muted,
                        )
                    }
                    Switch(
                        checked = includeApiKey,
                        onCheckedChange = { includeApiKey = it },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = colors.onAccent,
                            checkedTrackColor = colors.brand,
                        ),
                    )
                }
                validationError?.let { error ->
                    Text(
                        text = error,
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.loss,
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    when {
                        password.length < 6 ->
                            validationError = "Password must be at least 6 characters."
                        password != confirmPassword ->
                            validationError = "Passwords do not match."
                        else -> onConfirm(password, includeApiKey)
                    }
                },
                colors = ButtonDefaults.buttonColors(
                    containerColor = colors.brand,
                    contentColor = colors.onAccent,
                ),
            ) {
                Text(if (mode == BackupExportMode.SAVE_FILE) "Choose Location" else "Encrypt & Share")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", color = colors.muted)
            }
        },
    )
}

@Composable
private fun BackupRestorePasswordDialog(
    onDismiss: () -> Unit,
    onConfirm: (password: String) -> Unit,
) {
    val colors = LocalOpenFolioColors.current
    var password by remember { mutableStateOf("") }
    var showPassword by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = colors.surface,
        title = {
            Text(
                text = "Restore Encrypted Backup",
                style = MaterialTheme.typography.titleMedium,
                color = colors.content,
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            color = colors.loss.copy(alpha = 0.08f),
                            shape = RoundedCornerShape(8.dp),
                        )
                        .padding(10.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.Top,
                ) {
                    Icon(
                        imageVector = Icons.Filled.Warning,
                        contentDescription = null,
                        tint = colors.loss,
                        modifier = Modifier.size(18.dp),
                    )
                    Text(
                        text = "Restoring replaces your current portfolio database, Inbox documents, extracted text, and settings.",
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.content,
                    )
                }
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text("Backup Password") },
                    singleLine = true,
                    visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    trailingIcon = {
                        IconButton(onClick = { showPassword = !showPassword }) {
                            Icon(
                                imageVector = if (showPassword) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                                contentDescription = if (showPassword) "Hide password" else "Show password",
                                tint = colors.muted,
                            )
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(password) },
                enabled = password.isNotEmpty(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = colors.brand,
                    contentColor = colors.onAccent,
                ),
            ) {
                Text("Restore")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", color = colors.muted)
            }
        },
    )
}
