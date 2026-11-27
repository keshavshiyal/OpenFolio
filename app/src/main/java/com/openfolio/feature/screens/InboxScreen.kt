package com.openfolio.feature.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.openfolio.core.data.local.entity.AssetClass
import com.openfolio.core.data.local.entity.AssetEntity
import com.openfolio.core.data.local.entity.DocumentEntity
import com.openfolio.core.data.local.entity.DocumentStatus
import com.openfolio.core.data.local.entity.TransactionType
import com.openfolio.core.data.repository.DocumentRepositoryImpl
import com.openfolio.core.ui.components.AssetTypeSelectionBottomSheet
import com.openfolio.core.ui.components.ImportOptionsBottomSheet
import com.openfolio.core.ui.components.ManualEntryBottomSheet
import com.openfolio.core.ui.components.ManualTransactionInput
import com.openfolio.core.ui.theme.LocalOpenFolioColors
import com.openfolio.feature.assets.toDisplayTitle
import java.math.BigDecimal
import java.time.LocalDate
import java.util.Locale
import kotlinx.coroutines.launch

private val DOCUMENT_MIME_TYPES = arrayOf(
    "application/pdf",
    "image/*",
    "text/csv",
    "text/comma-separated-values",
    "application/csv",
    "application/vnd.ms-excel",
    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
)

@Composable
fun InboxScreen(
    documents: List<DocumentEntity>,
    onDocumentClick: (String) -> Unit,
    onProcessWithAiClick: ((String) -> Unit)? = null,
    onRetryClick: ((DocumentEntity) -> Unit)? = null,
    onRollbackImport: ((DocumentEntity) -> Unit)? = null,
    actionMessage: String? = null,
    onDismissActionMessage: () -> Unit = {},
    onImport: ((Uri, AssetClass) -> Unit)? = null,
    existingAssets: List<AssetEntity> = emptyList(),
    onSaveManualTransaction: ((String, AssetClass, TransactionType, LocalDate, BigDecimal, BigDecimal, BigDecimal, BigDecimal, BigDecimal, String?, String?, String?) -> Unit)? = null,
    onSaveManualTransactionResult: (suspend (ManualTransactionInput) -> Result<Unit>)? = null,
) {
    val colors = LocalOpenFolioColors.current
    val awaitingReview = documents.count { it.status != DocumentStatus.COMPLETED.name }
    var showImportOptionsSheet by remember { mutableStateOf(false) }
    var showAssetTypeSheet by remember { mutableStateOf(false) }
    var showManualTransactionDialog by remember { mutableStateOf(false) }
    var pendingAssetClass by remember { mutableStateOf(AssetClass.STOCK) }
    var documentToRollback by remember { mutableStateOf<DocumentEntity?>(null) }
    val snackbarHostState = remember { SnackbarHostState() }
    val coroutineScope = rememberCoroutineScope()

    LaunchedEffect(actionMessage) {
        if (!actionMessage.isNullOrBlank()) {
            snackbarHostState.showSnackbar(actionMessage)
            onDismissActionMessage()
        }
    }

    val documentPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
        onResult = { uri ->
            uri?.let { onImport?.invoke(it, pendingAssetClass) }
        },
    )

    Scaffold(
        containerColor = colors.background,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        floatingActionButton = {
            if (onImport != null || onSaveManualTransactionResult != null || onSaveManualTransaction != null) {
                ExtendedFloatingActionButton(
                    onClick = { showImportOptionsSheet = true },
                    icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                    text = { Text("Import") },
                    containerColor = colors.brand,
                    contentColor = colors.onAccent,
                )
            }
        },
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentPadding = PaddingValues(
                start = 20.dp,
                top = 24.dp,
                end = 20.dp,
                bottom = 100.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        text = "Inbox",
                        style = MaterialTheme.typography.headlineMedium,
                        color = colors.content,
                    )
                    Text(
                        text = "$awaitingReview documents awaiting review",
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.muted,
                    )
                }
            }

            if (documents.isEmpty()) {
                item {
                    Text(
                        text = "Imported statements will appear here.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.muted,
                        modifier = Modifier.padding(vertical = 12.dp),
                    )
                }
            } else {
                items(documents, key = DocumentEntity::id) { document ->
                    DocumentCard(
                        document = document,
                        onClick = { onDocumentClick(document.id) },
                        onProcessWithAi = { (onProcessWithAiClick ?: onDocumentClick)(document.id) },
                        onRetry = {
                            if (onRetryClick != null) {
                                onRetryClick(document)
                            } else {
                                onDocumentClick(document.id)
                            }
                        },
                        onUndoImport = if (onRollbackImport != null && document.status == DocumentStatus.COMPLETED.name) {
                            { documentToRollback = document }
                        } else {
                            null
                        },
                    )
                }
            }
        }
    }

    if (showImportOptionsSheet) {
        ImportOptionsBottomSheet(
            onDismissRequest = { showImportOptionsSheet = false },
            onSelectImportStatements = {
                showImportOptionsSheet = false
                showAssetTypeSheet = true
            },
            onSelectManualEntry = {
                showImportOptionsSheet = false
                showManualTransactionDialog = true
            },
        )
    }

    if (showAssetTypeSheet) {
        AssetTypeSelectionBottomSheet(
            onDismissRequest = { showAssetTypeSheet = false },
            onSelectAssetType = { selectedClass ->
                pendingAssetClass = selectedClass
                showAssetTypeSheet = false
                documentPicker.launch(DOCUMENT_MIME_TYPES)
            },
        )
    }

    if (showManualTransactionDialog) {
        ManualEntryBottomSheet(
            existingAssets = existingAssets,
            onDismiss = { showManualTransactionDialog = false },
            onSave = { input ->
                if (onSaveManualTransactionResult != null) {
                    val result = onSaveManualTransactionResult(input)
                    if (result.isSuccess) {
                        coroutineScope.launch {
                            snackbarHostState.showSnackbar(
                                "Manual entry saved. Tap Undo on its card to revert.",
                            )
                        }
                    }
                    result
                } else if (onSaveManualTransaction != null) {
                    onSaveManualTransaction(
                        input.assetName,
                        input.assetClass,
                        input.type,
                        input.tradeDate,
                        input.quantity,
                        input.unitPrice,
                        input.netAmount,
                        input.fees,
                        input.taxes,
                        input.notes,
                        input.broker,
                        input.tickerOrIsin,
                    )
                    Result.success(Unit)
                } else {
                    Result.failure(IllegalStateException("Manual entry saving is not configured."))
                }
            },
        )
    }

    documentToRollback?.let { targetDoc ->
        AlertDialog(
            onDismissRequest = { documentToRollback = null },
            containerColor = colors.surface,
            title = {
                Text(
                    text = "Undo Import?",
                    style = MaterialTheme.typography.titleMedium,
                    color = colors.content,
                )
            },
            text = {
                Text(
                    text = "Remove all transactions imported from ${targetDoc.fileName} and recalculate portfolio FIFO cost basis?",
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.muted,
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        onRollbackImport?.invoke(targetDoc)
                        documentToRollback = null
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = colors.lossGlyph,
                        contentColor = colors.onAccent,
                    ),
                ) {
                    Text("Undo Import")
                }
            },
            dismissButton = {
                TextButton(onClick = { documentToRollback = null }) {
                    Text("Cancel", color = colors.muted)
                }
            },
        )
    }
}

@Composable
fun DocumentCard(
    document: DocumentEntity,
    onClick: () -> Unit,
    onProcessWithAi: (() -> Unit)? = null,
    onRetry: (() -> Unit)? = null,
    onUndoImport: (() -> Unit)? = null,
) {
    val colors = LocalOpenFolioColors.current
    val shape = RoundedCornerShape(12.dp)
    val isInProgress = document.status == DocumentStatus.PENDING_OCR.name ||
        document.status == DocumentStatus.PROCESSING.name
    val isFailed = document.status == DocumentStatus.FAILED.name ||
        document.status == DocumentStatus.OCR_FAILED.name
    val isLocked = document.extractedText == DocumentRepositoryImpl.PASSWORD_PROTECTED_MARKER

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = shape,
        color = colors.surface,
        border = BorderStroke(1.dp, colors.border),
    ) {
        BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
            val isNarrow = maxWidth < 360.dp
            Column(
                modifier = Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Description,
                        contentDescription = "Document",
                        tint = colors.muted,
                        modifier = Modifier.size(24.dp),
                    )
                    Spacer(Modifier.width(12.dp))
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(3.dp),
                    ) {
                        Text(
                            text = document.fileName,
                            style = MaterialTheme.typography.bodyLarge,
                            color = colors.content,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Surface(
                                shape = RoundedCornerShape(4.dp),
                                color = colors.brand.copy(alpha = 0.12f),
                            ) {
                                Text(
                                    text = document.getAssetClassEnum().toDisplayTitle(),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = colors.brand,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                )
                            }
                            Text(
                                text = "•  ${formatFileSize(document.fileSize)}",
                                style = MaterialTheme.typography.bodySmall.copy(fontFeatureSettings = "tnum"),
                                color = colors.muted,
                            )
                        }
                        Text(
                            text = "SHA-256: ${document.fileHash.take(8)}...",
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            color = colors.muted,
                        )
                    }
                    if (!isNarrow) {
                        Spacer(Modifier.width(10.dp))
                        DocumentCardActionControl(
                            document = document,
                            isFailed = isFailed,
                            isLocked = isLocked,
                            onClick = onClick,
                            onProcessWithAi = onProcessWithAi,
                            onRetry = onRetry,
                            onUndoImport = onUndoImport,
                        )
                    }
                }

                if (isNarrow) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                    ) {
                        DocumentCardActionControl(
                            document = document,
                            isFailed = isFailed,
                            isLocked = isLocked,
                            onClick = onClick,
                            onProcessWithAi = onProcessWithAi,
                            onRetry = onRetry,
                            onUndoImport = onUndoImport,
                        )
                    }
                }

                if (isInProgress) {
                    val progressText = if (document.pageCount > 0) {
                        "Processing page ${document.processedPages} of ${document.pageCount}"
                    } else {
                        "Preparing extraction..."
                    }
                    Text(
                        text = progressText,
                        style = MaterialTheme.typography.bodySmall.copy(fontFeatureSettings = "tnum"),
                        color = colors.brand,
                    )
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

                if (isFailed && !document.extractionError.isNullOrBlank()) {
                    Text(
                        text = document.extractionError,
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.lossGlyph,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DocumentCardActionControl(
    document: DocumentEntity,
    isFailed: Boolean,
    isLocked: Boolean,
    onClick: () -> Unit,
    onProcessWithAi: (() -> Unit)?,
    onRetry: (() -> Unit)?,
    onUndoImport: (() -> Unit)?,
) {
    val colors = LocalOpenFolioColors.current
    when {
        document.status == DocumentStatus.OCR_COMPLETE.name -> {
            Button(
                onClick = { onProcessWithAi?.invoke() ?: onClick() },
                colors = ButtonDefaults.buttonColors(
                    containerColor = colors.brand,
                    contentColor = colors.onAccent,
                ),
                shape = RoundedCornerShape(8.dp),
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
            ) {
                Text(
                    text = "Process with AI",
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 1,
                )
            }
        }
        isFailed && !isLocked && onRetry != null -> {
            OutlinedButton(
                onClick = onRetry,
                shape = RoundedCornerShape(8.dp),
                border = BorderStroke(1.dp, colors.brand),
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
            ) {
                Text(
                    text = if (document.status == DocumentStatus.FAILED.name && document.processedPages > 0) {
                        "Resume"
                    } else {
                        "Retry"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.brand,
                    maxLines = 1,
                )
            }
        }
        document.status == DocumentStatus.AI_PAUSED.name -> {
            Button(
                onClick = onClick,
                colors = ButtonDefaults.buttonColors(
                    containerColor = colors.brand,
                    contentColor = colors.onAccent,
                ),
                shape = RoundedCornerShape(8.dp),
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
            ) {
                Text(
                    text = "Continue (${document.processedPages}/${document.pageCount})",
                    style = MaterialTheme.typography.labelSmall.copy(fontFeatureSettings = "tnum"),
                    maxLines = 1,
                )
            }
        }
        document.status == DocumentStatus.COMPLETED.name && onUndoImport != null -> {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                DocumentStatusBadge(document.status)
                TextButton(
                    onClick = onUndoImport,
                    modifier = Modifier.align(Alignment.CenterVertically),
                    contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp),
                ) {
                    Text(
                        text = "Undo",
                        style = MaterialTheme.typography.labelSmall,
                        color = colors.lossGlyph,
                        maxLines = 1,
                    )
                }
            }
        }
        else -> {
            DocumentStatusBadge(document.status)
        }
    }
}

@Composable
private fun DocumentStatusBadge(status: String) {
    val colors = LocalOpenFolioColors.current
    val label: String
    val foreground: androidx.compose.ui.graphics.Color
    val background: androidx.compose.ui.graphics.Color
    when (status) {
        DocumentStatus.PENDING_OCR.name -> {
            label = "Extracting..."
            foreground = colors.brand
            background = colors.brandSubtle
        }
        DocumentStatus.PROCESSING.name -> {
            label = "Processing AI..."
            foreground = colors.brand
            background = colors.brandSubtle
        }
        DocumentStatus.OCR_FAILED.name -> {
            label = "OCR Failed"
            foreground = colors.lossGlyph
            background = colors.lossGlyph.copy(alpha = 0.12f)
        }
        DocumentStatus.FAILED.name -> {
            label = "Failed"
            foreground = colors.lossGlyph
            background = colors.lossGlyph.copy(alpha = 0.12f)
        }
        DocumentStatus.OCR_COMPLETE.name,
        DocumentStatus.AI_PAUSED.name,
        DocumentStatus.READY_FOR_MAPPING.name,
        DocumentStatus.AI_COMPLETE.name -> {
            label = when (status) {
                DocumentStatus.OCR_COMPLETE.name -> "Ready for AI"
                DocumentStatus.AI_PAUSED.name -> "Paused"
                DocumentStatus.READY_FOR_MAPPING.name -> "Map columns"
                else -> "Ready for review"
            }
            foreground = colors.brand
            background = colors.brandSubtle
        }
        DocumentStatus.COMPLETED.name -> {
            Row(
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                androidx.compose.material3.Icon(
                    imageVector = Icons.Filled.CheckCircle,
                    contentDescription = null,
                    tint = colors.gainGlyph,
                    modifier = Modifier.size(18.dp),
                )
                Text(
                    text = "Completed",
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.gainGlyph,
                )
            }
            return
        }
        else -> {
            label = status.lowercase().replaceFirstChar { it.uppercase() }
            foreground = colors.muted
            background = colors.background
        }
    }
    Surface(
        shape = RoundedCornerShape(50.dp),
        color = background,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = foreground,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
        )
    }
}

private fun formatFileSize(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024L * 1024 -> "${bytes / 1024} KB"
    else -> String.format(Locale.ROOT, "%.1f MB", bytes / (1024.0 * 1024.0))
}
