package com.openfolio.feature.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.openfolio.core.data.local.entity.AssetClass
import com.openfolio.core.data.local.entity.DocumentStatus
import com.openfolio.core.data.repository.DocumentRepositoryImpl
import com.openfolio.feature.assets.toDisplayTitle
import com.openfolio.core.data.local.entity.DraftTransactionEntity
import com.openfolio.core.tableextraction.ExtractedTable
import com.openfolio.core.ui.theme.LocalOpenFolioColors
import com.openfolio.feature.review.DocumentPreview
import com.openfolio.feature.review.DraftReviewItem
import com.openfolio.feature.review.ReviewActionState
import com.openfolio.feature.review.ReviewUiState
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReviewScreen(
    uiState: ReviewUiState,
    drafts: List<DraftTransactionEntity>,
    structuredHeaders: List<String>,
    actionState: ReviewActionState,
    errorMessage: String? = null,
    draftReviewItems: List<DraftReviewItem> = emptyList(),
    isPasswordProtected: Boolean = false,
    passwordError: String? = null,
    unlockedPassword: String? = null,
    onUnlockPdf: (String) -> Unit = {},
    onBack: () -> Unit,
    onSkip: () -> Unit,
    onProcessWithAi: () -> Unit,
    onProcessNextAiBatch: () -> Unit = {},
    onAutoProcessRemainingAi: () -> Unit = {},
    onCancelAiProcessing: () -> Unit = {},
    batchBannerMessage: String? = null,
    onRetryLocalExtraction: () -> Unit = {},
    onProcessMappedData: (String, String, String, String, String, String, String, Int) -> Unit,
    onReloadHeaders: (Int) -> Unit,
    onSaveDraft: (String?, String, String, String, String, String) -> Unit,
    onSaveDraftWithClass: ((String?, String, String, String, String, String, String) -> Unit)? = null,
    onSaveDraftWithDetails: ((String?, String, String, String, String, String, String, String?) -> Unit)? = null,
    onSaveConfirmed: () -> Unit,
    onSaveComplete: () -> Unit,
    onNavigateToInbox: () -> Unit = onBack,
    onNavigateToSettings: () -> Unit = {},
    onDismissMissingApiKey: () -> Unit = {},
    onDismissError: () -> Unit = {},
    onToggleDraftSelection: (String) -> Unit = {},
    onSkipAllDuplicates: () -> Unit = {},
    onSelectAllDrafts: () -> Unit = {},
    onConfirmSkipDuplicatesAndSave: () -> Unit = {},
    onConfirmSaveAllAnyway: () -> Unit = {},
    onDismissDuplicatePrompt: () -> Unit = {},
    onUpdateAllAssetClass: (AssetClass) -> Unit = {},
    extractedTables: List<ExtractedTable> = emptyList(),
    showTableReview: Boolean = false,
    onExtractTablesLocally: () -> Unit = {},
    onDismissTableReview: () -> Unit = {},
    onApproveExtractedTable: (ExtractedTable, Set<Int>) -> Unit = { _, _ -> },
) {
    val colors = LocalOpenFolioColors.current
    val document = (uiState as? ReviewUiState.Loaded)?.document
    val snackbarHostState = remember { SnackbarHostState() }
    var showManualForm by remember { mutableStateOf(false) }
    var editingDraft by remember { mutableStateOf<DraftTransactionEntity?>(null) }
    var headerRowText by remember { mutableStateOf("1") }
    val headerRowNumber = headerRowText.toIntOrNull() ?: 1
    var passwordInput by remember { mutableStateOf("") }
    var passwordVisible by remember { mutableStateOf(false) }

    val isAiRunning = actionState == ReviewActionState.ProcessingAi ||
        document?.status == DocumentStatus.PROCESSING.name

    LaunchedEffect(errorMessage) {
        if (!errorMessage.isNullOrBlank() && actionState !is ReviewActionState.NoFinancialData) {
            snackbarHostState.showSnackbar(errorMessage)
            onDismissError()
        }
    }

    LaunchedEffect(actionState) {
        when (actionState) {
            is ReviewActionState.Saved -> {
                snackbarHostState.showSnackbar(
                    "Saved ${actionState.result.savedCount} records. " +
                        "Skipped ${actionState.result.duplicateCount} duplicates.",
                )
                onSaveComplete()
            }
            is ReviewActionState.NoFinancialData -> {
                snackbarHostState.showSnackbar(actionState.message)
                onNavigateToInbox()
            }
            is ReviewActionState.Error -> {
                if (errorMessage.isNullOrBlank()) {
                    snackbarHostState.showSnackbar(actionState.message)
                }
            }
            else -> Unit
        }
    }

    if (actionState == ReviewActionState.MissingApiKey) {
        AlertDialog(
            onDismissRequest = onDismissMissingApiKey,
            title = {
                Text(
                    text = "Gemini API Key Required",
                    style = MaterialTheme.typography.titleLarge,
                    color = colors.content,
                )
            },
            text = {
                Text(
                    text = "Please configure your API key in Settings.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.muted,
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onDismissMissingApiKey()
                        onNavigateToSettings()
                    },
                ) {
                    Text("Go to Settings", color = colors.brand)
                }
            },
            dismissButton = {
                TextButton(onClick = onDismissMissingApiKey) {
                    Text("Cancel", color = colors.muted)
                }
            },
            containerColor = colors.surface,
        )
    }

    if (actionState is ReviewActionState.PromptDuplicateReview) {
        AlertDialog(
            onDismissRequest = onDismissDuplicatePrompt,
            containerColor = colors.surface,
            title = {
                Text(
                    text = "Duplicate Transactions Detected",
                    style = MaterialTheme.typography.titleMedium,
                    color = colors.content,
                )
            },
            text = {
                Text(
                    text = "${actionState.duplicateCount} of the ${actionState.totalSelectedCount} selected transaction(s) are identical to records already present in your portfolio.\n\n" +
                        "Do you want to skip duplicates and save only unique transactions, or import all anyway?",
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.muted,
                )
            },
            confirmButton = {
                Button(
                    onClick = onConfirmSkipDuplicatesAndSave,
                ) {
                    Text("Skip Duplicates & Save")
                }
            },
            dismissButton = {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(
                        onClick = onDismissDuplicatePrompt,
                    ) {
                        Text("Cancel", color = colors.muted)
                    }
                    TextButton(
                        onClick = onConfirmSaveAllAnyway,
                    ) {
                        Text("Import Anyway", color = colors.lossGlyph)
                    }
                }
            },
        )
    }

    Scaffold(
        containerColor = colors.background,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "Review (${drafts.size} items)",
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.titleLarge,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    TextButton(onClick = onSkip) {
                        Text("Skip", color = colors.brand)
                    }
                },
                windowInsets = TopAppBarDefaults.windowInsets,
            )
        },
    ) { innerPadding ->
        val density = LocalDensity.current
        val isKeyboardVisible = WindowInsets.ime.getBottom(density) > 0
        val isMapping = document?.status == DocumentStatus.READY_FOR_MAPPING.name && drafts.isEmpty()
        val hasPendingAiPages = document != null &&
            document.pageCount > 0 &&
            document.processedPages in 1 until document.pageCount &&
            document.status in setOf(
                DocumentStatus.AI_PAUSED.name,
                DocumentStatus.PROCESSING.name,
                DocumentStatus.FAILED.name,
            )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .imePadding()
                .navigationBarsPadding(),
        ) {
            if (!isMapping || !isKeyboardVisible) {
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(if (isMapping) 0.35f else 0.4f),
                    color = colors.background,
                ) {
                    if (document != null && document.localPath.isNotBlank()) {
                        val fileExtension = document.fileName.substringAfterLast('.', "")
                            .ifBlank { document.localPath.substringAfterLast('.', "") }
                        DocumentPreview(
                            filePath = document.localPath,
                            fileExtension = fileExtension,
                            headerRowIndex = headerRowNumber,
                            password = unlockedPassword,
                            modifier = Modifier.fillMaxSize(),
                        )
                    } else {
                        Column(
                            modifier = Modifier.fillMaxSize(),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center,
                        ) {
                            Text(
                                text = when (uiState) {
                                    is ReviewUiState.Error -> uiState.message
                                    ReviewUiState.NotFound -> "This document is no longer available."
                                    else -> "Loading document…"
                                },
                                style = MaterialTheme.typography.bodyLarge,
                                color = colors.muted,
                            )
                            document?.let {
                                Text(
                                    text = it.fileName,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = colors.muted,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                }
            }

            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(if (isMapping && isKeyboardVisible) 1f else if (isMapping) 0.65f else 0.6f),
                color = colors.surface,
                shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = if (
                            document?.status == DocumentStatus.READY_FOR_MAPPING.name &&
                            drafts.isEmpty()
                        ) {
                            "Map File Columns"
                        } else {
                            "${drafts.size} Extracted Transactions"
                        },
                        style = MaterialTheme.typography.titleLarge,
                        color = colors.content,
                    )

                    if (
                        document?.status == DocumentStatus.READY_FOR_MAPPING.name &&
                        drafts.isEmpty()
                    ) {
                        ColumnMappingForm(
                            headers = structuredHeaders,
                            headerRowText = headerRowText,
                            onHeaderRowChange = { headerRowText = it },
                            isProcessing = actionState == ReviewActionState.ProcessingMapping ||
                                actionState == ReviewActionState.LoadingMapping,
                            onReloadHeaders = onReloadHeaders,
                            onProcess = onProcessMappedData,
                            modifier = Modifier.weight(1f),
                        )
                    } else if (drafts.isEmpty()) {
                        val isLocked = isPasswordProtected || document?.extractedText == "[PASSWORD_PROTECTED]"
                        if (isLocked) {
                            Surface(
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(12.dp),
                                color = colors.brand.copy(alpha = 0.08f),
                                border = BorderStroke(1.dp, colors.brand.copy(alpha = 0.25f)),
                            ) {
                                Column(
                                    modifier = Modifier.padding(16.dp),
                                    verticalArrangement = Arrangement.spacedBy(10.dp),
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    ) {
                                        Icon(
                                            imageVector = Icons.Filled.Lock,
                                            contentDescription = null,
                                            tint = colors.brand,
                                            modifier = Modifier.size(20.dp),
                                        )
                                        Text(
                                            text = "Password-Protected PDF",
                                            style = MaterialTheme.typography.titleSmall,
                                            color = colors.content,
                                        )
                                    }
                                    Text(
                                        text = "This statement is encrypted. Common passwords: PAN number (e.g. ABCDE1234F) or Date of Birth (DDMMYYYY).",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = colors.muted,
                                    )
                                    OutlinedTextField(
                                        value = passwordInput,
                                        onValueChange = { passwordInput = it },
                                        label = { Text("PDF Password") },
                                        placeholder = { Text("Enter PAN or DOB") },
                                        singleLine = true,
                                        modifier = Modifier.fillMaxWidth(),
                                        visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                                        trailingIcon = {
                                            val image = if (passwordVisible) Icons.Filled.Visibility else Icons.Filled.VisibilityOff
                                            val description = if (passwordVisible) "Hide password" else "Show password"
                                            IconButton(onClick = { passwordVisible = !passwordVisible }) {
                                                Icon(imageVector = image, contentDescription = description)
                                            }
                                        },
                                        isError = !passwordError.isNullOrBlank(),
                                        supportingText = if (!passwordError.isNullOrBlank()) {
                                            { Text(passwordError, color = colors.lossGlyph) }
                                        } else null,
                                    )
                                    Button(
                                        onClick = { onUnlockPdf(passwordInput) },
                                        enabled = passwordInput.isNotBlank() &&
                                            !isAiRunning &&
                                            actionState != ReviewActionState.ProcessingMapping,
                                        modifier = Modifier.fillMaxWidth(),
                                        colors = ButtonDefaults.buttonColors(
                                            containerColor = colors.brand,
                                            contentColor = colors.onAccent,
                                        ),
                                    ) {
                                        Text("Unlock & Extract Pages")
                                    }
                                }
                            }
                        }

                        var batchClassExpanded by remember { mutableStateOf(false) }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = "Category: ${document?.getAssetClassEnum()?.toDisplayTitle() ?: "Mixed"}",
                                style = MaterialTheme.typography.bodySmall,
                                color = colors.muted,
                            )
                            Box {
                                TextButton(onClick = { batchClassExpanded = true }) {
                                    Text("Change Category (All)", color = colors.brand)
                                }
                                DropdownMenu(
                                    expanded = batchClassExpanded,
                                    onDismissRequest = { batchClassExpanded = false },
                                    modifier = Modifier.background(colors.surface),
                                ) {
                                    AssetClass.entries.forEach { cls ->
                                        DropdownMenuItem(
                                            text = { Text(cls.toDisplayTitle(), color = colors.content) },
                                            onClick = {
                                                batchClassExpanded = false
                                                onUpdateAllAssetClass(cls)
                                            },
                                        )
                                    }
                                }
                            }
                        }

                        Button(
                            onClick = {
                                editingDraft = null
                                showManualForm = true
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text("Add Missing Record")
                        }
                        Text(
                            text = when {
                                uiState is ReviewUiState.Loading -> "Loading document…"
                                uiState is ReviewUiState.Error -> uiState.message
                                uiState == ReviewUiState.NotFound -> "No document found."
                                isLocked -> "Enter password above to decrypt and parse transactions."
                                document?.status == DocumentStatus.PENDING_OCR.name ||
                                    document?.status == DocumentStatus.PROCESSING.name ->
                                    if ((document.pageCount) > 0) {
                                        "Page ${document.processedPages} of ${document.pageCount}"
                                    } else {
                                        "Local text extraction is in progress."
                                    }
                                document?.status == DocumentStatus.AI_PAUSED.name ->
                                    batchBannerMessage
                                        ?: "Paused at page ${document.processedPages} of ${document.pageCount}. Choose Next ${DocumentRepositoryImpl.DEFAULT_AI_BATCH_PAGES} Pages or Auto-Process Remaining."
                                document?.status == DocumentStatus.OCR_FAILED.name ->
                                    document.extractionError ?: "Local text extraction failed. You can retry or add records manually."
                                document?.status == DocumentStatus.FAILED.name ->
                                    document.extractionError ?: "AI extraction was interrupted. Tap Resume or Retry to continue."
                                document?.status == DocumentStatus.OCR_COMPLETE.name ||
                                    document?.status == DocumentStatus.AI_COMPLETE.name ->
                                    "Ready to extract transactions from this statement."
                                else -> "Waiting for local text extraction."
                            },
                            style = MaterialTheme.typography.bodyMedium.copy(fontFeatureSettings = "tnum"),
                            color = if (document?.status == DocumentStatus.OCR_FAILED.name || document?.status == DocumentStatus.FAILED.name) {
                                colors.lossGlyph
                            } else {
                                colors.muted
                            },
                        )
                        if (isAiRunning) {
                            NonBlockingAiProgressCard(
                                processedPages = document?.processedPages ?: 0,
                                pageCount = document?.pageCount ?: 0,
                                stagedCount = drafts.size,
                                onPause = onCancelAiProcessing,
                            )
                        } else if (hasPendingAiPages) {
                            val activeDoc = document!!
                            BatchProgressControlsCard(
                                document = activeDoc,
                                stagedCount = drafts.size,
                                bannerMessage = batchBannerMessage ?: activeDoc.extractionError,
                                isProcessing = isAiRunning,
                                onNextBatch = onProcessNextAiBatch,
                                onAutoProcessRemaining = onAutoProcessRemainingAi,
                            )
                        } else if (document?.status == DocumentStatus.PENDING_OCR.name) {
                            if (document.pageCount > 0) {
                                val fraction = (document.processedPages.toFloat() / document.pageCount.coerceAtLeast(1))
                                    .coerceIn(0f, 1f)
                                LinearProgressIndicator(
                                    progress = { fraction },
                                    modifier = Modifier.fillMaxWidth(),
                                    color = colors.brand,
                                    trackColor = colors.brandSubtle,
                                )
                            } else {
                                LinearProgressIndicator(
                                    modifier = Modifier.fillMaxWidth(),
                                    color = colors.brand,
                                    trackColor = colors.brandSubtle,
                                )
                            }
                        }
                        if (!isLocked && document?.status == DocumentStatus.OCR_FAILED.name) {
                            OutlinedButton(
                                onClick = onRetryLocalExtraction,
                                modifier = Modifier.fillMaxWidth(),
                                border = BorderStroke(1.dp, colors.brand),
                            ) {
                                Text("Retry Page Extraction", color = colors.brand)
                            }
                        }
                        if (
                            !isLocked && !isAiRunning && !hasPendingAiPages && (
                                document?.status == DocumentStatus.OCR_COMPLETE.name ||
                                document?.status == DocumentStatus.AI_COMPLETE.name ||
                                document?.status == DocumentStatus.FAILED.name
                            )
                        ) {
                            Column(
                                modifier = Modifier.fillMaxWidth(),
                                verticalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                Button(
                                    onClick = onProcessWithAi,
                                    enabled = actionState != ReviewActionState.ProcessingMapping,
                                    modifier = Modifier.fillMaxWidth(),
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = colors.brand,
                                        contentColor = colors.onAccent,
                                    ),
                                ) {
                                    Icon(
                                        imageVector = Icons.Filled.AutoAwesome,
                                        contentDescription = null,
                                        modifier = Modifier.size(18.dp),
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    val aiButtonLabel = when {
                                        document.status == DocumentStatus.FAILED.name && document.processedPages > 0 ->
                                            "Resume Gemini AI (Page ${document.processedPages + 1})"
                                        document.status == DocumentStatus.FAILED.name ->
                                            "Retry Gemini AI Extraction"
                                        else -> "Extract Records with Gemini AI"
                                    }
                                    Text(aiButtonLabel)
                                }
                                if (document.pageCount > DocumentRepositoryImpl.DEFAULT_AI_BATCH_PAGES) {
                                    Text(
                                        text = "Sends up to ${DocumentRepositoryImpl.DEFAULT_AI_BATCH_PAGES} pages per request so you can verify Batch 1 before continuing.",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = colors.muted,
                                    )
                                }
                            }
                        }
                    } else {
                        if (isAiRunning) {
                            NonBlockingAiProgressCard(
                                processedPages = document?.processedPages ?: 0,
                                pageCount = document?.pageCount ?: 0,
                                stagedCount = drafts.size,
                                onPause = onCancelAiProcessing,
                            )
                        } else if (hasPendingAiPages) {
                            val activeDoc = document!!
                            BatchProgressControlsCard(
                                document = activeDoc,
                                stagedCount = drafts.size,
                                bannerMessage = batchBannerMessage ?: activeDoc.extractionError,
                                isProcessing = isAiRunning,
                                onNextBatch = onProcessNextAiBatch,
                                onAutoProcessRemaining = onAutoProcessRemainingAi,
                            )
                        } else if (document?.status == DocumentStatus.FAILED.name) {
                            Surface(
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(12.dp),
                                color = colors.lossGlyph.copy(alpha = 0.08f),
                                border = BorderStroke(1.dp, colors.lossGlyph.copy(alpha = 0.25f)),
                            ) {
                                Column(
                                    modifier = Modifier.padding(12.dp),
                                    verticalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    Text(
                                        text = document.extractionError
                                            ?: "Partial drafts extracted (${document.processedPages}/${document.pageCount} pages). Resume to finish remaining pages.",
                                        style = MaterialTheme.typography.bodySmall.copy(fontFeatureSettings = "tnum"),
                                        color = colors.lossGlyph,
                                    )
                                    OutlinedButton(
                                        onClick = onProcessWithAi,
                                        enabled = !isAiRunning,
                                        border = BorderStroke(1.dp, colors.brand),
                                    ) {
                                        Text(
                                            text = if (document.processedPages > 0) {
                                                "Resume AI Extraction (Page ${document.processedPages + 1})"
                                            } else {
                                                "Retry AI Extraction"
                                            },
                                            color = colors.brand,
                                        )
                                    }
                                }
                            }
                        }
                        val itemsToDisplay = remember(drafts, draftReviewItems) {
                            if (draftReviewItems.isNotEmpty()) {
                                draftReviewItems
                            } else {
                                drafts.map { DraftReviewItem(draft = it, isDuplicate = false, isSelected = true) }
                            }
                        }
                        val duplicateCount = itemsToDisplay.count { it.isDuplicate }
                        val selectedCount = itemsToDisplay.count { it.isSelected }

                        if (duplicateCount > 0) {
                            Surface(
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(12.dp),
                                color = colors.lossGlyph.copy(alpha = 0.08f),
                                border = BorderStroke(1.dp, colors.lossGlyph.copy(alpha = 0.25f)),
                            ) {
                                Column(
                                    modifier = Modifier.padding(12.dp),
                                    verticalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    ) {
                                        Icon(
                                            imageVector = Icons.Filled.Warning,
                                            contentDescription = null,
                                            tint = colors.lossGlyph,
                                            modifier = Modifier.size(18.dp),
                                        )
                                        Text(
                                            text = "$duplicateCount Duplicate(s) Found",
                                            style = MaterialTheme.typography.titleSmall,
                                            color = colors.lossGlyph,
                                        )
                                    }
                                    Text(
                                        text = "Identical transactions already exist in your portfolio. They are unselected by default to prevent double counting.",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = colors.content,
                                    )
                                    Row(
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    ) {
                                        OutlinedButton(
                                            onClick = onSkipAllDuplicates,
                                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                                        ) {
                                            Text("Skip All Duplicates", style = MaterialTheme.typography.labelMedium)
                                        }
                                        OutlinedButton(
                                            onClick = onSelectAllDrafts,
                                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                                        ) {
                                            Text("Select All", style = MaterialTheme.typography.labelMedium)
                                        }
                                    }
                                }
                            }
                        }

                        var batchClassExpanded by remember { mutableStateOf(false) }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = "Category: ${document?.getAssetClassEnum()?.toDisplayTitle() ?: "Mixed"}",
                                style = MaterialTheme.typography.bodySmall,
                                color = colors.muted,
                            )
                            Box {
                                TextButton(onClick = { batchClassExpanded = true }) {
                                    Text("Change Category (All)", color = colors.brand)
                                }
                                DropdownMenu(
                                    expanded = batchClassExpanded,
                                    onDismissRequest = { batchClassExpanded = false },
                                    modifier = Modifier.background(colors.surface),
                                ) {
                                    AssetClass.entries.forEach { cls ->
                                        DropdownMenuItem(
                                            text = { Text(cls.toDisplayTitle(), color = colors.content) },
                                            onClick = {
                                                batchClassExpanded = false
                                                onUpdateAllAssetClass(cls)
                                            },
                                        )
                                    }
                                }
                            }
                        }

                        Button(
                            onClick = {
                                editingDraft = null
                                showManualForm = true
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text("Add Missing Record")
                        }
                        LazyColumn(
                            modifier = Modifier.weight(1f),
                            contentPadding = PaddingValues(vertical = 2.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            items(itemsToDisplay, key = { it.draft.id }) { item ->
                                DraftTransactionRow(
                                    item = item,
                                    documentImportDate = document?.importDate,
                                    onToggleSelection = { onToggleDraftSelection(item.draft.id) },
                                    onEdit = {
                                        editingDraft = item.draft
                                        showManualForm = true
                                    },
                                )
                            }
                        }
                        (actionState as? ReviewActionState.Error)?.let { error ->
                            Text(
                                text = error.message,
                                style = MaterialTheme.typography.bodySmall,
                                color = colors.lossGlyph,
                            )
                        }
                        Button(
                            onClick = onSaveConfirmed,
                            enabled = !hasPendingAiPages &&
                                !isAiRunning &&
                                actionState != ReviewActionState.Saving,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            if (actionState == ReviewActionState.Saving) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(18.dp),
                                    color = colors.onAccent,
                                    strokeWidth = 2.dp,
                                )
                                Text("  Saving…")
                            } else {
                                val label = when {
                                    isAiRunning ->
                                        "Extracting in Background ($selectedCount Staged)"
                                    hasPendingAiPages ->
                                        "Complete All ${document!!.pageCount} Pages to Commit ($selectedCount Staged)"
                                    selectedCount == 0 -> "Skip All & Complete"
                                    selectedCount < itemsToDisplay.size -> "Save Selected ($selectedCount / ${itemsToDisplay.size})"
                                    else -> "Save Confirmed ($selectedCount)"
                                }
                                Text(
                                    text = label,
                                    style = MaterialTheme.typography.labelLarge.copy(fontFeatureSettings = "tnum"),
                                )
                            }
                        }
                    }
                }
            }
        }
    }



    if (showManualForm) {
        ModalBottomSheet(
            onDismissRequest = { showManualForm = false },
            containerColor = colors.surface,
        ) {
            ManualDraftForm(
                draft = editingDraft,
                documentImportDate = document?.importDate,
                onSave = { draftId, asset, type, quantity, price, amount, assetClass, broker ->
                    when {
                        onSaveDraftWithDetails != null ->
                            onSaveDraftWithDetails(draftId, asset, type, quantity, price, amount, assetClass, broker)
                        onSaveDraftWithClass != null ->
                            onSaveDraftWithClass(draftId, asset, type, quantity, price, amount, assetClass)
                        else ->
                            onSaveDraft(draftId, asset, type, quantity, price, amount)
                    }
                    showManualForm = false
                },
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ColumnMappingForm(
    headers: List<String>,
    headerRowText: String,
    onHeaderRowChange: (String) -> Unit,
    isProcessing: Boolean,
    onReloadHeaders: (Int) -> Unit,
    onProcess: (String, String, String, String, String, String, String, Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalOpenFolioColors.current
    var brokerName by remember { mutableStateOf("") }

    var assetManuallyModified by remember { mutableStateOf(false) }
    var typeManuallyModified by remember { mutableStateOf(false) }
    var quantityManuallyModified by remember { mutableStateOf(false) }
    var priceManuallyModified by remember { mutableStateOf(false) }
    var amountManuallyModified by remember { mutableStateOf(false) }
    var dateManuallyModified by remember { mutableStateOf(false) }

    var assetColumn by remember { mutableStateOf("") }
    var typeColumn by remember { mutableStateOf("") }
    var quantityColumn by remember { mutableStateOf("") }
    var priceColumn by remember { mutableStateOf("") }
    var amountColumn by remember { mutableStateOf("") }
    var dateColumn by remember { mutableStateOf("") }

    // When header row changes, reset manual flags so new headers auto-map
    LaunchedEffect(headerRowText) {
        assetManuallyModified = false
        typeManuallyModified = false
        quantityManuallyModified = false
        priceManuallyModified = false
        amountManuallyModified = false
        dateManuallyModified = false
    }

    LaunchedEffect(headers, headerRowText) {
        if (headers.isNotEmpty()) {
            val autoMapping = autoMapHeaders(headers)
            if (!assetManuallyModified || assetColumn !in headers) {
                assetColumn = autoMapping["Asset"].orEmpty()
            }
            if (!typeManuallyModified || typeColumn !in headers) {
                typeColumn = autoMapping["Type"].orEmpty()
            }
            if (!quantityManuallyModified || quantityColumn !in headers) {
                quantityColumn = autoMapping["Quantity"].orEmpty()
            }
            if (!priceManuallyModified || priceColumn !in headers) {
                priceColumn = autoMapping["Price"].orEmpty()
            }
            if (!amountManuallyModified || amountColumn !in headers) {
                amountColumn = autoMapping["Amount"].orEmpty()
            }
            if (!dateManuallyModified || dateColumn !in headers) {
                dateColumn = autoMapping["Date"].orEmpty()
            }
        }
    }

    val headerRowNum = headerRowText.toIntOrNull()
    val isValidHeaderRow = headerRowNum != null && headerRowNum >= 1
    val mappedColumns = listOf(assetColumn, typeColumn, quantityColumn, priceColumn, amountColumn, dateColumn)
        .filter(String::isNotBlank)
    val isAssetMapped = assetColumn.isNotBlank()
    val canProcess = !isProcessing &&
        isValidHeaderRow &&
        headers.isNotEmpty() &&
        isAssetMapped &&
        mappedColumns.toSet().size == mappedColumns.size

    Column(
        modifier = modifier.fillMaxSize(),
    ) {
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                Text(
                    text = "Choose which file column matches each transaction field.",
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.muted,
                )
            }
            item {
                OutlinedTextField(
                    value = headerRowText,
                    onValueChange = { newText ->
                        val digitsOnly = newText.filter { it.isDigit() }
                        onHeaderRowChange(digitsOnly)
                        val parsed = digitsOnly.toIntOrNull()
                        if (parsed != null && parsed >= 1) {
                            onReloadHeaders(parsed)
                        }
                    },
                    label = { Text("Header Row Number") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
            }
            item {
                if (headers.isNotEmpty()) {
                    val previewHeaders = headers.take(4).joinToString(", ")
                    Text(
                        text = "Detected headers: $previewHeaders",
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.muted,
                    )
                } else if (!isProcessing) {
                    Text(
                        text = "No headers detected on row ${headerRowText.ifBlank { "0" }}.",
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.lossGlyph,
                    )
                }
            }
            item {
                MappingColumnDropdown("Asset", headers, assetColumn) {
                    assetColumn = it
                    assetManuallyModified = true
                }
            }
            item {
                MappingColumnDropdown("Type", headers, typeColumn) {
                    typeColumn = it
                    typeManuallyModified = true
                }
            }
            item {
                MappingColumnDropdown("Quantity", headers, quantityColumn) {
                    quantityColumn = it
                    quantityManuallyModified = true
                }
            }
            item {
                MappingColumnDropdown("Price", headers, priceColumn) {
                    priceColumn = it
                    priceManuallyModified = true
                }
            }
            item {
                MappingColumnDropdown("Amount", headers, amountColumn) {
                    amountColumn = it
                    amountManuallyModified = true
                }
            }
            item {
                MappingColumnDropdown("Date", headers, dateColumn) {
                    dateColumn = it
                    dateManuallyModified = true
                }
            }
            item {
                OutlinedTextField(
                    value = brokerName,
                    onValueChange = { brokerName = it },
                    label = { Text("Save this mapping as (e.g., Zerodha)") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
            }
            if (headers.isEmpty() && !isProcessing) {
                item {
                    Text(
                        text = "Could not read the file headers.",
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.lossGlyph,
                    )
                }
            }
        }

        // Sticky Bottom Bar
        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = colors.surface,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                if (!isAssetMapped) {
                    Text(
                        text = "Please map the Asset column to continue.",
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.loss,
                    )
                }
                Button(
                    onClick = {
                        onProcess(
                            brokerName.ifBlank { "Custom" },
                            assetColumn,
                            typeColumn,
                            quantityColumn,
                            priceColumn,
                            amountColumn,
                            dateColumn,
                            headerRowNum ?: 1,
                        )
                    },
                    enabled = canProcess,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    if (isProcessing) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            color = colors.onAccent,
                            strokeWidth = 2.dp,
                        )
                        Text("  Processing mapped data…")
                    } else {
                        Text("Process Mapped Data")
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MappingColumnDropdown(
    label: String,
    headers: List<String>,
    selected: String,
    allowUnmapped: Boolean = true,
    onSelect: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it },
    ) {
        OutlinedTextField(
            value = if (selected.isBlank()) "-- No map --" else selected,
            onValueChange = {},
            readOnly = true,
            label = { Text(label) },
            trailingIcon = {
                androidx.compose.material3.ExposedDropdownMenuDefaults.TrailingIcon(expanded)
            },
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor(MenuAnchorType.PrimaryNotEditable),
            singleLine = true,
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            if (allowUnmapped) {
                DropdownMenuItem(
                    text = { Text("-- No map --") },
                    onClick = {
                        onSelect("")
                        expanded = false
                    },
                )
            }
            headers.forEach { header ->
                DropdownMenuItem(
                    text = { Text(header) },
                    onClick = {
                        onSelect(header)
                        expanded = false
                    },
                )
            }
        }
    }
}

internal fun autoMapHeaders(headers: List<String>): Map<String, String> {
    val result = mutableMapOf<String, String>()
    val rules = listOf(
        "Asset" to listOf("symbol", "name", "asset", "stock", "company", "instrument", "security", "scrip"),
        "Quantity" to listOf("qty", "quantity", "units", "shares", "nos", "volume"),
        "Price" to listOf("price", "rate", "nav", "cost"),
        "Amount" to listOf("amount", "amt", "total", "value", "consideration"),
        "Date" to listOf("date", "time"),
        "Type" to listOf("type", "action", "buy/sell", "side"),
    )

    val used = mutableSetOf<String>()
    for ((field, keywords) in rules) {
        val matched = headers.firstOrNull { header ->
            header !in used && keywords.any { it.equals(header.trim(), ignoreCase = true) }
        } ?: headers.firstOrNull { header ->
            header !in used && matchesKeyword(header, keywords)
        } ?: headers.firstOrNull { header ->
            matchesKeyword(header, keywords)
        }

        if (matched != null) {
            result[field] = matched
            used.add(matched)
        }
    }
    return result
}

private fun matchesKeyword(header: String, keywords: List<String>): Boolean {
    val lower = header.lowercase().trim()
    val compact = lower.replace(" ", "").replace("_", "").replace("-", "")
    return keywords.any { keyword ->
        val kw = keyword.lowercase().trim()
        lower.contains(kw) || compact.contains(kw.replace(" ", ""))
    }
}

@Composable
private fun DraftTransactionRow(
    item: DraftReviewItem,
    documentImportDate: Long? = null,
    onToggleSelection: () -> Unit,
    onEdit: () -> Unit,
) {
    val draft = item.draft
    val colors = LocalOpenFolioColors.current
    val (dateText, isDateMissing) = when {
        draft.tradeDate != null -> formatEpochMillisToDate(draft.tradeDate) to false
        documentImportDate != null && documentImportDate > 0L ->
            "${formatEpochMillisToDate(documentImportDate)} (Import Date)" to true
        else -> "Unknown Date" to true
    }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = if (draft.isManual) colors.brandSubtle else colors.background,
        border = when {
            item.isDuplicate -> BorderStroke(1.dp, colors.lossGlyph.copy(alpha = 0.35f))
            item.isFlagged -> BorderStroke(1.dp, colors.lossGlyph.copy(alpha = 0.45f))
            else -> null
        },
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Checkbox(
                checked = item.isSelected,
                onCheckedChange = { onToggleSelection() },
                colors = CheckboxDefaults.colors(
                    checkedColor = colors.brand,
                    checkmarkColor = colors.onAccent,
                ),
            )
            Spacer(modifier = Modifier.width(4.dp))
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        text = draft.assetName,
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.content,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    Surface(
                        shape = RoundedCornerShape(4.dp),
                        color = colors.brand.copy(alpha = 0.12f),
                    ) {
                        Text(
                            text = draft.assetClass,
                            style = MaterialTheme.typography.labelSmall,
                            color = colors.brand,
                            modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp),
                        )
                    }
                    if (!draft.broker.isNullOrBlank()) {
                        Surface(
                            shape = RoundedCornerShape(4.dp),
                            color = colors.muted.copy(alpha = 0.12f),
                        ) {
                            Text(
                                text = draft.broker!!,
                                style = MaterialTheme.typography.labelSmall,
                                color = colors.muted,
                                modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp),
                            )
                        }
                    }
                    val identifier = draft.isin ?: draft.ticker
                    if (!identifier.isNullOrBlank()) {
                        Surface(
                            shape = RoundedCornerShape(4.dp),
                            color = colors.brand.copy(alpha = 0.08f),
                        ) {
                            Text(
                                text = identifier,
                                style = MaterialTheme.typography.labelSmall,
                                color = colors.brand,
                                modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp),
                            )
                        }
                    }
                    if (item.isDuplicate) {
                        Surface(
                            shape = RoundedCornerShape(4.dp),
                            color = colors.lossGlyph.copy(alpha = 0.12f),
                        ) {
                            Text(
                                text = "DUPLICATE",
                                style = MaterialTheme.typography.labelSmall,
                                color = colors.lossGlyph,
                                modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp),
                            )
                        }
                    } else if (item.isFlagged) {
                        Surface(
                            shape = RoundedCornerShape(4.dp),
                            color = colors.lossGlyph.copy(alpha = 0.12f),
                        ) {
                            Text(
                                text = "REVIEW",
                                style = MaterialTheme.typography.labelSmall,
                                color = colors.lossGlyph,
                                modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp),
                            )
                        }
                    }
                }
                Text(
                    text = "${draft.transactionType} · Qty ${draft.quantity} · ₹ ${draft.price}",
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontFeatureSettings = "tnum",
                    ),
                    color = colors.muted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = "Date: $dateText",
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontFeatureSettings = "tnum",
                    ),
                    color = if (isDateMissing) colors.loss else colors.muted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (item.isDuplicate && !item.duplicateReason.isNullOrBlank()) {
                    Text(
                        text = item.duplicateReason,
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontFeatureSettings = "tnum",
                        ),
                        color = colors.lossGlyph,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            ConfidenceBadge(draft.confidence)
            IconButton(onClick = onEdit) {
                Icon(
                    imageVector = Icons.Filled.Edit,
                    contentDescription = "Edit transaction details",
                    tint = colors.muted,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}

@Composable
private fun DraftTransactionRow(
    draft: DraftTransactionEntity,
    documentImportDate: Long? = null,
    onEdit: () -> Unit,
) {
    DraftTransactionRow(
        item = DraftReviewItem(draft = draft, isDuplicate = false, isSelected = true),
        documentImportDate = documentImportDate,
        onToggleSelection = {},
        onEdit = onEdit,
    )
}

@Composable
private fun ConfidenceBadge(confidence: Int) {
    val colors = LocalOpenFolioColors.current
    val badgeColor = when {
        confidence >= 90 -> colors.gainGlyph
        confidence >= 70 -> colors.chartPalette[2]
        else -> colors.lossGlyph
    }
    Surface(
        shape = RoundedCornerShape(50.dp),
        color = badgeColor.copy(alpha = 0.12f),
    ) {
        Text(
            text = "$confidence%",
            style = MaterialTheme.typography.labelSmall,
            color = badgeColor,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp),
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ManualDraftForm(
    draft: DraftTransactionEntity?,
    documentImportDate: Long? = null,
    onSave: (String?, String, String, String, String, String, String, String?) -> Unit,
) {
    val colors = LocalOpenFolioColors.current
    var asset by remember(draft?.id) { mutableStateOf(draft?.assetName.orEmpty()) }
    var type by remember(draft?.id) { mutableStateOf(draft?.transactionType ?: "BUY") }
    var assetClass by remember(draft?.id) { mutableStateOf(draft?.assetClass ?: "STOCK") }
    var broker by remember(draft?.id) { mutableStateOf(draft?.broker.orEmpty()) }
    var quantity by remember(draft?.id) { mutableStateOf(draft?.quantity.orEmpty()) }
    var price by remember(draft?.id) { mutableStateOf(draft?.price.orEmpty()) }
    var amount by remember(draft?.id) { mutableStateOf(draft?.netAmount.orEmpty()) }

    val (dateDisplay, isMissingDate) = when {
        draft?.tradeDate != null -> formatEpochMillisToDate(draft.tradeDate) to false
        documentImportDate != null && documentImportDate > 0L ->
            "${formatEpochMillisToDate(documentImportDate)} (Import Date)" to true
        else -> "Unknown Date" to true
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp)
            .padding(bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            text = if (draft == null) "Add Missing Record" else "Edit Transaction",
            style = MaterialTheme.typography.titleLarge,
            color = colors.content,
        )
        Text(
            text = "Date: $dateDisplay",
            style = MaterialTheme.typography.bodySmall.copy(
                fontFeatureSettings = "tnum",
            ),
            color = if (isMissingDate) colors.loss else colors.muted,
        )
        OutlinedTextField(
            value = asset,
            onValueChange = { asset = it },
            label = { Text("Asset") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )
        var classDropdownExpanded by remember { mutableStateOf(false) }
        ExposedDropdownMenuBox(
            expanded = classDropdownExpanded,
            onExpandedChange = { classDropdownExpanded = it },
        ) {
            val resolvedClass = runCatching { AssetClass.valueOf(assetClass) }.getOrDefault(AssetClass.STOCK)
            OutlinedTextField(
                value = resolvedClass.toDisplayTitle(),
                onValueChange = {},
                readOnly = true,
                label = { Text("Asset Class") },
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = classDropdownExpanded) },
                modifier = Modifier
                    .fillMaxWidth()
                    .menuAnchor(MenuAnchorType.PrimaryNotEditable),
            )
            ExposedDropdownMenu(
                expanded = classDropdownExpanded,
                onDismissRequest = { classDropdownExpanded = false },
                modifier = Modifier.background(colors.surface),
            ) {
                AssetClass.entries.forEach { cls ->
                    DropdownMenuItem(
                        text = { Text(cls.toDisplayTitle(), color = colors.content) },
                        onClick = {
                            assetClass = cls.name
                            classDropdownExpanded = false
                        },
                    )
                }
            }
        }
        OutlinedTextField(
            value = type,
            onValueChange = { type = it.uppercase() },
            label = { Text("Type (BUY, SELL, DIVIDEND)") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )
        OutlinedTextField(
            value = quantity,
            onValueChange = { quantity = it },
            label = { Text("Quantity") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )
        OutlinedTextField(
            value = price,
            onValueChange = { price = it },
            label = { Text("Price") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )
        OutlinedTextField(
            value = amount,
            onValueChange = { amount = it },
            label = { Text("Amount") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )
        OutlinedTextField(
            value = broker,
            onValueChange = { broker = it },
            label = { Text("Broker / AMC / Depository (optional)") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )
        Button(
            onClick = {
                onSave(
                    draft?.id,
                    asset,
                    type,
                    quantity,
                    price,
                    amount,
                    assetClass,
                    broker.trim().takeIf(String::isNotBlank),
                )
            },
            enabled = asset.isNotBlank() &&
                type in setOf("BUY", "SELL", "DIVIDEND", "INTEREST", "FEE", "TAX") &&
                quantity.toBigDecimalOrNull()?.let { it.signum() > 0 } == true &&
                price.toBigDecimalOrNull()?.let { it.signum() >= 0 } == true &&
                amount.toBigDecimalOrNull()?.let { it.signum() >= 0 } == true,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Save Record")
        }
    }
}

@Composable
private fun NonBlockingAiProgressCard(
    processedPages: Int,
    pageCount: Int,
    stagedCount: Int,
    onPause: () -> Unit,
) {
    val colors = LocalOpenFolioColors.current
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = colors.brandSubtle,
        border = BorderStroke(1.dp, colors.brand.copy(alpha = 0.25f)),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        color = colors.brand,
                        strokeWidth = 2.dp,
                    )
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            text = "Gemini AI extracting (non-blocking)",
                            style = MaterialTheme.typography.titleSmall,
                            color = colors.content,
                        )
                        val progressLabel = if (pageCount > 0) {
                            "Page $processedPages of $pageCount · $stagedCount staged"
                        } else {
                            "$stagedCount staged"
                        }
                        Text(
                            text = progressLabel,
                            style = MaterialTheme.typography.bodySmall.copy(fontFeatureSettings = "tnum"),
                            color = colors.muted,
                        )
                    }
                }
                TextButton(onClick = onPause) {
                    Text("Pause", color = colors.brand)
                }
            }
            if (pageCount > 0) {
                val fraction = (processedPages.toFloat() / pageCount.coerceAtLeast(1)).coerceIn(0f, 1f)
                LinearProgressIndicator(
                    progress = { fraction },
                    modifier = Modifier.fillMaxWidth(),
                    color = colors.brand,
                    trackColor = colors.surface,
                )
            } else {
                LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth(),
                    color = colors.brand,
                    trackColor = colors.surface,
                )
            }
        }
    }
}

@Composable
private fun BatchProgressControlsCard(
    document: com.openfolio.core.data.local.entity.DocumentEntity,
    stagedCount: Int,
    bannerMessage: String?,
    isProcessing: Boolean,
    onNextBatch: () -> Unit,
    onAutoProcessRemaining: () -> Unit,
) {
    val colors = LocalOpenFolioColors.current
    val hasAlert = !document.extractionError.isNullOrBlank() ||
        document.status == DocumentStatus.FAILED.name
    val fraction = (document.processedPages.toFloat() / document.pageCount.coerceAtLeast(1))
        .coerceIn(0f, 1f)

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = if (hasAlert) colors.lossGlyph.copy(alpha = 0.08f) else colors.brandSubtle,
        border = BorderStroke(
            1.dp,
            if (hasAlert) colors.lossGlyph.copy(alpha = 0.3f) else colors.brand.copy(alpha = 0.25f),
        ),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Page ${document.processedPages} of ${document.pageCount}",
                    style = MaterialTheme.typography.titleSmall.copy(fontFeatureSettings = "tnum"),
                    color = colors.content,
                )
                Surface(
                    shape = RoundedCornerShape(4.dp),
                    color = colors.surface,
                ) {
                    Text(
                        text = "$stagedCount staged",
                        style = MaterialTheme.typography.labelSmall.copy(fontFeatureSettings = "tnum"),
                        color = colors.brand,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                    )
                }
            }
            LinearProgressIndicator(
                progress = { fraction },
                modifier = Modifier.fillMaxWidth(),
                color = colors.brand,
                trackColor = colors.surface,
            )
            if (!bannerMessage.isNullOrBlank()) {
                Text(
                    text = bannerMessage,
                    style = MaterialTheme.typography.bodySmall.copy(fontFeatureSettings = "tnum"),
                    color = if (hasAlert) colors.lossGlyph else colors.muted,
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(
                    onClick = onNextBatch,
                    enabled = !isProcessing,
                    modifier = Modifier.weight(1f),
                    border = BorderStroke(1.dp, colors.brand),
                ) {
                    Text(
                        text = "Next ${DocumentRepositoryImpl.DEFAULT_AI_BATCH_PAGES} Pages",
                        color = colors.brand,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Button(
                    onClick = onAutoProcessRemaining,
                    enabled = !isProcessing,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = colors.brand,
                        contentColor = colors.onAccent,
                    ),
                ) {
                    Text(
                        text = "Auto-Process Remaining",
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

internal fun formatEpochMillisToDate(millis: Long): String {
    return try {
        Instant.ofEpochMilli(millis)
            .atZone(ZoneId.systemDefault())
            .toLocalDate()
            .format(DateTimeFormatter.ISO_LOCAL_DATE)
    } catch (_: Exception) {
        "Unknown Date"
    }
}
