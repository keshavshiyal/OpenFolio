package com.openfolio.feature.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.openfolio.core.data.local.entity.DocumentStatus
import com.openfolio.core.data.local.entity.DraftTransactionEntity
import com.openfolio.core.ui.theme.LocalOpenFolioColors
import com.openfolio.feature.review.ReviewActionState
import com.openfolio.feature.review.ReviewUiState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReviewScreen(
    uiState: ReviewUiState,
    drafts: List<DraftTransactionEntity>,
    structuredHeaders: List<String>,
    actionState: ReviewActionState,
    onBack: () -> Unit,
    onSkip: () -> Unit,
    onProcessWithAi: () -> Unit,
    onProcessMappedData: (String, String, String, String, String, String, String) -> Unit,
    onSaveDraft: (String?, String, String, String, String, String) -> Unit,
    onSaveConfirmed: () -> Unit,
    onSaveComplete: () -> Unit,
) {
    val colors = LocalOpenFolioColors.current
    val document = (uiState as? ReviewUiState.Loaded)?.document
    val snackbarHostState = remember { SnackbarHostState() }
    var showManualForm by remember { mutableStateOf(false) }
    var editingDraft by remember { mutableStateOf<DraftTransactionEntity?>(null) }

    LaunchedEffect(actionState) {
        when (actionState) {
            is ReviewActionState.Saved -> {
                snackbarHostState.showSnackbar(
                    "Saved ${actionState.result.savedCount} records. " +
                        "Skipped ${actionState.result.duplicateCount} duplicates.",
                )
                onSaveComplete()
            }
            is ReviewActionState.Error -> snackbarHostState.showSnackbar(actionState.message)
            else -> Unit
        }
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
                        Icon(Icons.Filled.ArrowBack, contentDescription = "Back")
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
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(0.4f),
                color = colors.border,
            ) {
                Column(
                    modifier = Modifier.fillMaxSize(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Text(
                        text = when (uiState) {
                            is ReviewUiState.Error -> uiState.message
                            ReviewUiState.NotFound -> "This document is no longer available."
                            else -> "PDF Preview (Coming Soon)"
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

            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(0.6f),
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
                            isProcessing = actionState == ReviewActionState.ProcessingMapping ||
                                actionState == ReviewActionState.LoadingMapping,
                            onProcess = onProcessMappedData,
                            modifier = Modifier.weight(1f),
                        )
                    } else if (drafts.isEmpty()) {
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
                                document?.status == DocumentStatus.PENDING_OCR.name ->
                                    "Local text extraction is in progress."
                                document?.status == DocumentStatus.OCR_FAILED.name ->
                                    "Local text extraction failed. You can still add records manually."
                                document?.status == DocumentStatus.OCR_COMPLETE.name ||
                                    document?.status == DocumentStatus.AI_COMPLETE.name ->
                                    "Ready to extract transactions from this statement."
                                else -> "Waiting for local text extraction."
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = colors.muted,
                        )
                        if (
                            document?.status == DocumentStatus.OCR_COMPLETE.name ||
                            document?.status == DocumentStatus.AI_COMPLETE.name
                        ) {
                            Button(
                                onClick = onProcessWithAi,
                                enabled = actionState != ReviewActionState.ProcessingAi,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text("Process with AI")
                            }
                        }
                    } else {
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
                            items(drafts, key = DraftTransactionEntity::id) { draft ->
                                DraftTransactionRow(
                                    draft = draft,
                                    onEdit = {
                                        editingDraft = draft
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
                            enabled = actionState != ReviewActionState.Saving &&
                                actionState != ReviewActionState.ProcessingAi,
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
                                Text("Save Confirmed")
                            }
                        }
                    }
                }
            }
        }
    }

    if (actionState == ReviewActionState.ProcessingAi) {
        AlertDialog(
            onDismissRequest = {},
            title = { Text("Extracting transactions...") },
            text = {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CircularProgressIndicator(color = colors.brand)
                    Text("Analyzing statement chunks locally with mock extraction.")
                }
            },
            confirmButton = {},
            dismissButton = {},
        )
    }

    if (showManualForm) {
        ModalBottomSheet(
            onDismissRequest = { showManualForm = false },
            containerColor = colors.surface,
        ) {
            ManualDraftForm(
                draft = editingDraft,
                onSave = { draftId, asset, type, quantity, price, amount ->
                    onSaveDraft(draftId, asset, type, quantity, price, amount)
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
    isProcessing: Boolean,
    onProcess: (String, String, String, String, String, String, String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalOpenFolioColors.current
    var brokerName by remember(headers) { mutableStateOf("") }
    var assetColumn by remember(headers) { mutableStateOf(suggestHeader(headers, "asset", "symbol", "security", "name")) }
    var typeColumn by remember(headers) { mutableStateOf(suggestHeader(headers, "type", "side", "action")) }
    var quantityColumn by remember(headers) { mutableStateOf(suggestHeader(headers, "quantity", "qty", "units")) }
    var priceColumn by remember(headers) { mutableStateOf(suggestHeader(headers, "price", "rate", "unit price")) }
    var amountColumn by remember(headers) { mutableStateOf(suggestHeader(headers, "amount", "value", "total")) }
    var dateColumn by remember(headers) { mutableStateOf(suggestHeader(headers, "date", "trade date")) }
    val requiredColumns = listOf(assetColumn, typeColumn, quantityColumn, priceColumn, amountColumn)
    val canProcess = !isProcessing &&
        headers.isNotEmpty() &&
        brokerName.isNotBlank() &&
        requiredColumns.all(String::isNotBlank) &&
        requiredColumns.toSet().size == requiredColumns.size

    LazyColumn(
        modifier = modifier,
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
            MappingColumnDropdown("Asset", headers, assetColumn) { assetColumn = it }
        }
        item {
            MappingColumnDropdown("Type", headers, typeColumn) { typeColumn = it }
        }
        item {
            MappingColumnDropdown("Quantity", headers, quantityColumn) { quantityColumn = it }
        }
        item {
            MappingColumnDropdown("Price", headers, priceColumn) { priceColumn = it }
        }
        item {
            MappingColumnDropdown("Amount", headers, amountColumn) { amountColumn = it }
        }
        item {
            MappingColumnDropdown(
                label = "Date (optional)",
                headers = headers,
                selected = dateColumn,
                allowUnmapped = true,
                onSelect = { dateColumn = it },
            )
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
        item {
            Button(
                onClick = {
                    onProcess(
                        brokerName,
                        assetColumn,
                        typeColumn,
                        quantityColumn,
                        priceColumn,
                        amountColumn,
                        dateColumn,
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
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MappingColumnDropdown(
    label: String,
    headers: List<String>,
    selected: String,
    allowUnmapped: Boolean = false,
    onSelect: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it },
    ) {
        OutlinedTextField(
            value = selected.ifBlank { "Select a column" },
            onValueChange = {},
            readOnly = true,
            label = { Text(label) },
            trailingIcon = {
                androidx.compose.material3.ExposedDropdownMenuDefaults.TrailingIcon(expanded)
            },
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor(),
            singleLine = true,
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            if (allowUnmapped) {
                DropdownMenuItem(
                    text = { Text("Not mapped") },
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

private fun suggestHeader(headers: List<String>, vararg suggestions: String): String =
    headers.firstOrNull { header ->
        val normalized = header.lowercase().replace(Regex("[^a-z0-9]"), "")
        suggestions.any { suggestion ->
            normalized.contains(suggestion.lowercase().replace(Regex("[^a-z0-9]"), ""))
        }
    }.orEmpty()

@Composable
private fun DraftTransactionRow(
    draft: DraftTransactionEntity,
    onEdit: () -> Unit,
) {
    val colors = LocalOpenFolioColors.current
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = if (draft.isManual) colors.brandSubtle else colors.background,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                Text(
                    text = draft.assetName,
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.content,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = "${draft.transactionType} · Qty ${draft.quantity} · ₹ ${draft.price}",
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontFeatureSettings = "tnum",
                    ),
                    color = colors.muted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
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
    onSave: (String?, String, String, String, String, String) -> Unit,
) {
    val colors = LocalOpenFolioColors.current
    var asset by remember(draft?.id) { mutableStateOf(draft?.assetName.orEmpty()) }
    var type by remember(draft?.id) { mutableStateOf(draft?.transactionType ?: "BUY") }
    var quantity by remember(draft?.id) { mutableStateOf(draft?.quantity.orEmpty()) }
    var price by remember(draft?.id) { mutableStateOf(draft?.price.orEmpty()) }
    var amount by remember(draft?.id) { mutableStateOf(draft?.netAmount.orEmpty()) }

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
        OutlinedTextField(
            value = asset,
            onValueChange = { asset = it },
            label = { Text("Asset") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )
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
        Button(
            onClick = { onSave(draft?.id, asset, type, quantity, price, amount) },
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
