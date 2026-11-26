package com.openfolio.feature.screens

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import com.openfolio.core.data.repository.SettingsRepository
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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.openfolio.core.ui.theme.LocalOpenFolioColors
import com.openfolio.feature.settings.SettingsEvent
import com.openfolio.feature.settings.SettingsViewModel

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel,
    modifier: Modifier = Modifier,
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val colors = LocalOpenFolioColors.current
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current
    val scrollState = rememberScrollState()

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

    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                is SettingsEvent.ShowSnackbar -> {
                    snackbarHostState.showSnackbar(event.message)
                }
                is SettingsEvent.ShareExportFile -> {
                    val shareIntent = Intent(Intent.ACTION_SEND).apply {
                        type = "application/json"
                        putExtra(Intent.EXTRA_STREAM, event.uri)
                        putExtra(Intent.EXTRA_SUBJECT, event.title)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    val chooserIntent = Intent.createChooser(shareIntent, "Share Database Export")
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
                            contentColor = Color.White,
                        ),
                        shape = RoundedCornerShape(10.dp),
                    ) {
                        if (uiState.isSaving) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                color = Color.White,
                                strokeWidth = 2.dp,
                            )
                            Spacer(Modifier.width(8.dp))
                        }
                        Text(
                            text = "Save Key",
                            style = MaterialTheme.typography.labelLarge,
                        )
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
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
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

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Button(
                            onClick = {
                                exportLauncher.launch(viewModel.getDefaultExportFileName())
                            },
                            enabled = !uiState.isExporting,
                            modifier = Modifier
                                .weight(1f)
                                .height(48.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = colors.brand,
                                contentColor = Color.White,
                            ),
                            shape = RoundedCornerShape(10.dp),
                        ) {
                            if (uiState.isExporting) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(18.dp),
                                    color = Color.White,
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
                            )
                        }

                        OutlinedButton(
                            onClick = viewModel::exportAndShareDatabase,
                            enabled = !uiState.isExporting,
                            modifier = Modifier
                                .weight(1f)
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

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Button(
                            onClick = {
                                dossierLauncher.launch(viewModel.getDefaultDossierFileName())
                            },
                            enabled = !uiState.isExportingDossier,
                            modifier = Modifier
                                .weight(1f)
                                .height(48.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = colors.brand,
                                contentColor = Color.White,
                            ),
                            shape = RoundedCornerShape(10.dp),
                        ) {
                            if (uiState.isExportingDossier) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(18.dp),
                                    color = Color.White,
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
                            )
                        }

                        OutlinedButton(
                            onClick = viewModel::exportAndShareDossier,
                            enabled = !uiState.isExportingDossier,
                            modifier = Modifier
                                .weight(1f)
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
                            )
                        }
                    }
                }
            }
        }
    }
}
