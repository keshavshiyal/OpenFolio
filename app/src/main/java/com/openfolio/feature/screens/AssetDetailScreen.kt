package com.openfolio.feature.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.openfolio.core.data.local.entity.AssetClass
import com.openfolio.core.data.local.entity.AssetEntity
import com.openfolio.core.data.local.entity.AssetStatus
import com.openfolio.core.data.local.entity.TransactionEntity
import com.openfolio.core.data.local.entity.TransactionType
import com.openfolio.core.ui.components.MoneyText
import com.openfolio.core.ui.components.MoneyTextStyle
import com.openfolio.core.ui.theme.LocalOpenFolioColors
import com.openfolio.core.utils.AssetNameNormalizer
import com.openfolio.core.utils.DateParser
import com.openfolio.feature.assets.AssetDetailUiState
import com.openfolio.feature.assets.AssetDetailViewModel
import com.openfolio.feature.assets.toDisplayTitle
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException

private val DISPLAY_DATE_FORMATTER = DateTimeFormatter.ofPattern("dd MMM yyyy")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AssetDetailScreen(
    viewModel: AssetDetailViewModel,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val colors = LocalOpenFolioColors.current
    val snackbarHostState = remember { SnackbarHostState() }
    var showMenu by remember { mutableStateOf(false) }
    var showDeleteAssetConfirm by remember { mutableStateOf(false) }
    var showEditAssetDialog by remember { mutableStateOf(false) }
    var showRenameDialog by remember { mutableStateOf(false) }
    var showSellDialog by remember { mutableStateOf(false) }
    var showChangeAssetClassDialog by remember { mutableStateOf(false) }

    LaunchedEffect(uiState.isDeleted) {
        if (uiState.isDeleted) {
            onBack()
        }
    }

    LaunchedEffect(uiState.errorMessage) {
        uiState.errorMessage?.let { error ->
            snackbarHostState.showSnackbar(error)
            viewModel.clearErrorMessage()
        }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = colors.background,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = uiState.asset?.displayName ?: "Asset Details",
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                            color = colors.content,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        uiState.asset?.let { asset ->
                            Text(
                                text = "${asset.assetClass.toDisplayTitle()} • ${asset.currency}",
                                style = MaterialTheme.typography.bodySmall,
                                color = colors.muted,
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                            tint = colors.content,
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { showEditAssetDialog = true }) {
                        Icon(
                            imageVector = Icons.Filled.Edit,
                            contentDescription = "Edit Asset",
                            tint = colors.content,
                        )
                    }
                    IconButton(onClick = viewModel::toggleValuesVisibility) {
                        Icon(
                            imageVector = if (uiState.showValues) Icons.Filled.Visibility else Icons.Filled.VisibilityOff,
                            contentDescription = if (uiState.showValues) "Hide values" else "Show values",
                            tint = colors.content,
                        )
                    }
                    Box {
                        IconButton(onClick = { showMenu = true }) {
                            Icon(
                                imageVector = Icons.Filled.MoreVert,
                                contentDescription = "More options",
                                tint = colors.content,
                            )
                        }
                        DropdownMenu(
                            expanded = showMenu,
                            onDismissRequest = { showMenu = false },
                            modifier = Modifier.background(colors.surface),
                        ) {
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        text = "Edit Details",
                                        color = colors.content,
                                    )
                                },
                                onClick = {
                                    showMenu = false
                                    showEditAssetDialog = true
                                },
                            )
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        text = "Rename Asset",
                                        color = colors.content,
                                    )
                                },
                                onClick = {
                                    showMenu = false
                                    showRenameDialog = true
                                },
                            )
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        text = "Change Asset Class",
                                        color = colors.content,
                                    )
                                },
                                onClick = {
                                    showMenu = false
                                    showChangeAssetClassDialog = true
                                },
                            )
                            if (uiState.asset != null && uiState.asset!!.totalQuantity > BigDecimal.ZERO) {
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            text = "Record Sale / Exit",
                                            color = colors.brand,
                                        )
                                    },
                                    onClick = {
                                        showMenu = false
                                        showSellDialog = true
                                    },
                                )
                            }
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        text = "Delete Asset",
                                        color = colors.lossGlyph,
                                    )
                                },
                                onClick = {
                                    showMenu = false
                                    showDeleteAssetConfirm = true
                                },
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = colors.background,
                ),
            )
        },
    ) { innerPadding ->
        if (uiState.isLoading && uiState.asset == null) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator(color = colors.brand)
            }
        } else {
            AssetDetailContent(
                uiState = uiState,
                onEditTransaction = viewModel::startEditTransaction,
                onDeleteTransaction = viewModel::startDeleteTransaction,
                onDeleteAsset = { showDeleteAssetConfirm = true },
                onSellClick = { showSellDialog = true },
                onBack = onBack,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
            )
        }

        // Sell Asset Dialog
        if (showSellDialog && uiState.asset != null) {
            SellAssetDialog(
                asset = uiState.asset!!,
                onDismiss = { showSellDialog = false },
                onConfirmSell = { qty, price, proceeds, date, fees, taxes ->
                    viewModel.recordSell(qty, price, proceeds, date, fees, taxes)
                },
            )
        }

        // Change Asset Class Dialog
        if (showChangeAssetClassDialog && uiState.asset != null) {
            ChangeAssetClassDialog(
                currentClass = uiState.asset!!.assetClass,
                onDismiss = { showChangeAssetClassDialog = false },
                onConfirm = { newClass ->
                    viewModel.updateAssetClass(newClass)
                },
            )
        }

        // Edit Asset Details Dialog
        if (showEditAssetDialog && uiState.asset != null) {
            EditAssetDetailsDialog(
                asset = uiState.asset!!,
                onDismiss = { showEditAssetDialog = false },
                onSave = { newName, newTicker, newIsin, newClass ->
                    showEditAssetDialog = false
                    viewModel.updateAssetDetails(newName, newTicker, newIsin, newClass)
                },
            )
        }

        // Rename Asset Dialog
        if (showRenameDialog && uiState.asset != null) {
            RenameAssetDialog(
                currentName = uiState.asset!!.displayName,
                onDismiss = { showRenameDialog = false },
                onConfirm = { newName ->
                    showRenameDialog = false
                    viewModel.updateDisplayName(newName)
                },
            )
        }

        // Edit Transaction Dialog
        uiState.editingTransaction?.let { txn ->
            EditTransactionDialog(
                transaction = txn,
                onDismiss = viewModel::dismissEditTransaction,
                onSave = viewModel::saveEditedTransaction,
            )
        }

        // Delete Transaction Confirmation Dialog
        uiState.deletingTransaction?.let { txn ->
            DeleteTransactionConfirmDialog(
                transaction = txn,
                onDismiss = viewModel::dismissDeleteTransaction,
                onConfirm = viewModel::confirmDeleteTransaction,
            )
        }

        // Delete Asset Confirmation Dialog
        if (showDeleteAssetConfirm) {
            AlertDialog(
                onDismissRequest = { showDeleteAssetConfirm = false },
                title = {
                    Text(
                        text = "Delete Asset?",
                        style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                        color = colors.content,
                    )
                },
                text = {
                    Text(
                        text = "Are you sure you want to delete '${uiState.asset?.displayName}'? All associated transactions and lots will also be removed.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.content,
                    )
                },
                confirmButton = {
                    Button(
                        onClick = {
                            showDeleteAssetConfirm = false
                            viewModel.deleteAsset()
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = colors.lossGlyph,
                            contentColor = colors.onAccent,
                        ),
                    ) {
                        Text("Delete")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showDeleteAssetConfirm = false }) {
                        Text("Cancel", color = colors.muted)
                    }
                },
                containerColor = colors.surface,
            )
        }
    }
}

@Composable
private fun AssetDetailContent(
    uiState: AssetDetailUiState,
    onEditTransaction: (TransactionEntity) -> Unit,
    onDeleteTransaction: (TransactionEntity) -> Unit,
    onDeleteAsset: () -> Unit,
    onSellClick: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalOpenFolioColors.current
    val asset = uiState.asset

    LazyColumn(
        modifier = modifier,
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        // Core Summary Card
        if (asset != null) {
            item(key = "asset_summary_card") {
                AssetSummaryCard(
                    asset = asset,
                    currentValue = uiState.currentValue,
                    unrealizedPnL = uiState.unrealizedPnL,
                    showValues = uiState.showValues,
                    onSellClick = onSellClick,
                )
            }
        }

        // Ledger Section Header
        item(key = "ledger_header") {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = "Transaction History",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                        color = colors.content,
                    )
                    Box(
                        modifier = Modifier
                            .clip(CircleShape)
                            .background(colors.brandSubtle)
                            .padding(horizontal = 8.dp, vertical = 2.dp),
                    ) {
                        Text(
                            text = "${uiState.transactions.size}",
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontWeight = FontWeight.Bold,
                                fontFeatureSettings = "tnum",
                            ),
                            color = colors.brand,
                        )
                    }
                }
            }
        }

        // Empty Ledger State (e.g. all transactions deleted)
        if (uiState.transactions.isEmpty()) {
            item(key = "empty_ledger") {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    color = colors.surface,
                    border = androidx.compose.foundation.BorderStroke(1.dp, colors.border),
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(32.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Box(
                            modifier = Modifier
                                .size(56.dp)
                                .clip(CircleShape)
                                .background(colors.brandSubtle),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                imageVector = Icons.Filled.History,
                                contentDescription = null,
                                tint = colors.brand,
                                modifier = Modifier.size(28.dp),
                            )
                        }

                        Text(
                            text = "No transactions remaining",
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                            color = colors.content,
                            textAlign = TextAlign.Center,
                        )

                        Text(
                            text = "All historical transactions for this asset have been deleted. Holdings and cost basis are currently zero.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = colors.muted,
                            textAlign = TextAlign.Center,
                        )

                        Spacer(modifier = Modifier.height(4.dp))

                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            OutlinedButton(
                                onClick = onDeleteAsset,
                                colors = ButtonDefaults.outlinedButtonColors(
                                    contentColor = colors.lossGlyph,
                                ),
                                border = ButtonDefaults.outlinedButtonBorder.copy(
                                    brush = androidx.compose.ui.graphics.SolidColor(colors.lossGlyph),
                                ),
                            ) {
                                Text("Delete Asset")
                            }

                            Button(
                                onClick = onBack,
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = colors.brand,
                                    contentColor = colors.onAccent,
                                ),
                            ) {
                                Text("Back to Assets")
                            }
                        }
                    }
                }
            }
        } else {
            // Ledger Items (Ordered chronologically, newest first)
            items(
                items = uiState.transactions,
                key = { it.id },
            ) { txn ->
                TransactionLedgerRow(
                    transaction = txn,
                    showValues = uiState.showValues,
                    onEdit = { onEditTransaction(txn) },
                    onDelete = { onDeleteTransaction(txn) },
                )
            }
        }
    }
}

@Composable
private fun AssetSummaryCard(
    asset: AssetEntity,
    currentValue: BigDecimal?,
    unrealizedPnL: BigDecimal?,
    showValues: Boolean,
    onSellClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalOpenFolioColors.current
    val typography = MaterialTheme.typography

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .border(1.dp, colors.border, RoundedCornerShape(16.dp)),
        color = colors.surface,
        tonalElevation = 2.dp,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // Top: Holding Status & Average Cost
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column {
                    Text(
                        text = "HOLDINGS",
                        style = typography.labelSmall.copy(fontWeight = FontWeight.Medium),
                        color = colors.muted,
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = if (showValues) {
                            "${formatQuantityDisplay(asset.totalQuantity)} units"
                        } else {
                            "— — —"
                        },
                        style = typography.headlineSmall.copy(
                            fontWeight = FontWeight.Bold,
                            fontFeatureSettings = "tnum",
                        ),
                        color = colors.content,
                    )
                }

                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        text = "AVG BUY PRICE",
                        style = typography.labelSmall.copy(fontWeight = FontWeight.Medium),
                        color = colors.muted,
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    MoneyText(
                        amount = asset.averageCost,
                        showValues = showValues,
                        style = MoneyTextStyle.Title,
                    )
                }
            }

            // Divider
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(colors.border),
            )

            // 2x2 Core Metrics Grid
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                // Metric 1: Total Invested
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "TOTAL INVESTED",
                        style = typography.labelSmall.copy(fontWeight = FontWeight.Medium),
                        color = colors.muted,
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    MoneyText(
                        amount = asset.totalInvested,
                        showValues = showValues,
                        style = MoneyTextStyle.Title,
                    )
                }

                // Metric 2: Realized P&L
                Column(
                    modifier = Modifier.weight(1f),
                    horizontalAlignment = Alignment.End,
                ) {
                    Text(
                        text = "REALIZED P&L",
                        style = typography.labelSmall.copy(fontWeight = FontWeight.Medium),
                        color = colors.muted,
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    RealizedPnLDisplay(
                        realizedPnL = asset.realizedPnL,
                        showValues = showValues,
                    )
                }
            }

            // Metric 3 & 4: Future-Proofing Placeholders (Current Value & Unrealized P&L)
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = colors.background,
                shape = RoundedCornerShape(10.dp),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // Current Value
                    Column {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Text(
                                text = "CURRENT VALUE",
                                style = typography.labelSmall,
                                color = colors.muted,
                            )
                            if (currentValue == null) {
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(4.dp))
                                        .background(colors.border)
                                        .padding(horizontal = 6.dp, vertical = 1.dp),
                                ) {
                                    Text(
                                        text = "Live price soon",
                                        style = typography.labelSmall.copy(fontSize = typography.labelSmall.fontSize * 0.85f),
                                        color = colors.muted,
                                    )
                                }
                            }
                        }
                        Spacer(modifier = Modifier.height(2.dp))
                        if (currentValue != null) {
                            MoneyText(
                                amount = currentValue,
                                showValues = showValues,
                                style = MoneyTextStyle.Body,
                            )
                        } else {
                            Text(
                                text = "₹ — — —",
                                style = typography.bodyMedium.copy(
                                    fontFeatureSettings = "tnum",
                                    fontWeight = FontWeight.Medium,
                                ),
                                color = colors.muted,
                            )
                        }
                    }

                    // Unrealized P&L
                    Column(horizontalAlignment = Alignment.End) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Text(
                                text = "UNREALIZED P&L",
                                style = typography.labelSmall,
                                color = colors.muted,
                            )
                            if (unrealizedPnL == null) {
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(4.dp))
                                        .background(colors.border)
                                        .padding(horizontal = 6.dp, vertical = 1.dp),
                                ) {
                                    Text(
                                        text = "Live price soon",
                                        style = typography.labelSmall.copy(fontSize = typography.labelSmall.fontSize * 0.85f),
                                        color = colors.muted,
                                    )
                                }
                            }
                        }
                        Spacer(modifier = Modifier.height(2.dp))
                        if (unrealizedPnL != null) {
                            RealizedPnLDisplay(
                                realizedPnL = unrealizedPnL,
                                showValues = showValues,
                            )
                        } else {
                            Text(
                                text = "— — —",
                                style = typography.bodyMedium.copy(
                                    fontFeatureSettings = "tnum",
                                    fontWeight = FontWeight.Medium,
                                ),
                                color = colors.muted,
                            )
                        }
                    }
                }
            }

            // Identifiers Summary (ISIN, Ticker, Folio, Certificate)
            val idSummary = remember(asset.identifiersJson) {
                runCatching {
                    val obj = org.json.JSONObject(asset.identifiersJson)
                    val keys = obj.keys()
                    keys.asSequence().map { "$it: ${obj.getString(it)}" }.joinToString(" • ")
                }.getOrNull().orEmpty()
            }
            if (idSummary.isNotBlank()) {
                Text(
                    text = idSummary,
                    style = typography.bodySmall,
                    color = colors.muted,
                )
            }

            // Sell / Exit Asset Button
            if (asset.totalQuantity > BigDecimal.ZERO) {
                Button(
                    onClick = onSellClick,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = colors.brand,
                        contentColor = colors.onAccent,
                    ),
                    shape = RoundedCornerShape(10.dp),
                ) {
                    Text("Sell / Exit Asset")
                }
            }
        }
    }
}

@Composable
private fun TransactionLedgerRow(
    transaction: TransactionEntity,
    showValues: Boolean,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalOpenFolioColors.current
    val typography = MaterialTheme.typography
    val formattedDate = remember(transaction.tradeDate) {
        try {
            transaction.tradeDate.format(DISPLAY_DATE_FORMATTER)
        } catch (_: Exception) {
            transaction.tradeDate.toString()
        }
    }

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .border(1.dp, colors.border, RoundedCornerShape(12.dp)),
        color = colors.surface,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // Top: Date, Type Badge, Amount
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    // Type Badge
                    TransactionTypeBadge(type = transaction.type)

                    // Trade Date
                    Text(
                        text = formattedDate,
                        style = typography.bodySmall.copy(
                            fontFeatureSettings = "tnum",
                            fontWeight = FontWeight.Medium,
                        ),
                        color = colors.muted,
                    )
                }

                // Net Amount
                MoneyText(
                    amount = transaction.netAmount,
                    showValues = showValues,
                    style = MoneyTextStyle.Title,
                )
            }

            // Middle: Quantity & Price
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val qtyStr = transaction.quantity?.let { formatQuantityDisplay(it) } ?: "—"
                val unitPriceStr = transaction.unitPrice?.let { "₹ ${formatQuantityDisplay(it)}" } ?: "—"

                Text(
                    text = if (showValues) {
                        "$qtyStr units @ $unitPriceStr"
                    } else {
                        "— — —"
                    },
                    style = typography.bodyMedium.copy(
                        fontFeatureSettings = "tnum",
                    ),
                    color = colors.content,
                )

                // Provenance chip
                val provenanceText = if (transaction.documentId != null) {
                    val conf = transaction.confidence?.let { " (${(it * 100).toInt()}%)" } ?: ""
                    "Statement$conf"
                } else {
                    "Manual"
                }
                val brokerSuffix = transaction.broker?.let { " • $it" } ?: ""
                Text(
                    text = "$provenanceText$brokerSuffix",
                    style = typography.labelSmall,
                    color = colors.muted,
                )
            }

            // Notes / Description if present
            if (!transaction.notes.isNullOrBlank() || !transaction.description.isNullOrBlank()) {
                Text(
                    text = transaction.notes ?: transaction.description.orEmpty(),
                    style = typography.bodySmall,
                    color = colors.muted,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            // Divider & Action buttons
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(colors.border),
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // Edit Button
                TextButton(
                    onClick = onEdit,
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                ) {
                    Icon(
                        imageVector = Icons.Filled.Edit,
                        contentDescription = "Edit",
                        tint = colors.brand,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = "Edit",
                        style = typography.labelMedium,
                        color = colors.brand,
                    )
                }

                Spacer(modifier = Modifier.width(8.dp))

                // Delete Button
                TextButton(
                    onClick = onDelete,
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                ) {
                    Icon(
                        imageVector = Icons.Filled.Delete,
                        contentDescription = "Delete",
                        tint = colors.lossGlyph,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = "Delete",
                        style = typography.labelMedium,
                        color = colors.lossGlyph,
                    )
                }
            }
        }
    }
}

@Composable
private fun TransactionTypeBadge(type: TransactionType) {
    val colors = LocalOpenFolioColors.current
    val (bgColor, textColor) = when (type) {
        TransactionType.BUY, TransactionType.TRANSFER_IN -> colors.brandSubtle to colors.brand
        TransactionType.SELL, TransactionType.TRANSFER_OUT -> colors.border to colors.lossGlyph
        TransactionType.DIVIDEND, TransactionType.INTEREST -> colors.brandSubtle to colors.gainGlyph
        TransactionType.BONUS, TransactionType.SPLIT -> colors.surface to colors.brand
        else -> colors.border to colors.muted
    }

    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(bgColor)
            .padding(horizontal = 8.dp, vertical = 2.dp),
    ) {
        Text(
            text = type.name.replace("_", " "),
            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
            color = textColor,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EditTransactionDialog(
    transaction: TransactionEntity,
    onDismiss: () -> Unit,
    onSave: (TransactionEntity) -> Unit,
) {
    val colors = LocalOpenFolioColors.current
    var selectedType by remember { mutableStateOf(transaction.type) }
    var typeDropdownExpanded by remember { mutableStateOf(false) }

    var tradeDateText by remember { mutableStateOf(transaction.tradeDate.toString()) }
    var quantityText by remember { mutableStateOf(transaction.quantity?.stripTrailingZeros()?.toPlainString() ?: "") }
    var unitPriceText by remember { mutableStateOf(transaction.unitPrice?.stripTrailingZeros()?.toPlainString() ?: "") }
    var netAmountText by remember { mutableStateOf(transaction.netAmount.stripTrailingZeros().toPlainString()) }
    var feesText by remember { mutableStateOf(transaction.fees?.stripTrailingZeros()?.toPlainString() ?: "0") }
    var taxesText by remember { mutableStateOf(transaction.taxes?.stripTrailingZeros()?.toPlainString() ?: "0") }
    var brokerText by remember { mutableStateOf(transaction.broker.orEmpty()) }
    var notesText by remember { mutableStateOf(transaction.notes ?: "") }

    var errorMessage by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = "Edit Transaction",
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                color = colors.content,
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                errorMessage?.let { error ->
                    Text(
                        text = error,
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.lossGlyph,
                    )
                }

                // Transaction Type Dropdown
                ExposedDropdownMenuBox(
                    expanded = typeDropdownExpanded,
                    onExpandedChange = { typeDropdownExpanded = it },
                ) {
                    OutlinedTextField(
                        value = selectedType.name,
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("Transaction Type") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = typeDropdownExpanded) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .menuAnchor(MenuAnchorType.PrimaryNotEditable, true),
                        shape = RoundedCornerShape(8.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedContainerColor = colors.surface,
                            unfocusedContainerColor = colors.surface,
                            focusedBorderColor = colors.brand,
                            unfocusedBorderColor = colors.border,
                            focusedTextColor = colors.content,
                            unfocusedTextColor = colors.content,
                        ),
                    )

                    ExposedDropdownMenu(
                        expanded = typeDropdownExpanded,
                        onDismissRequest = { typeDropdownExpanded = false },
                        modifier = Modifier.background(colors.surface),
                    ) {
                        listOf(
                            TransactionType.BUY,
                            TransactionType.SELL,
                            TransactionType.DIVIDEND,
                            TransactionType.INTEREST,
                            TransactionType.BONUS,
                            TransactionType.SPLIT,
                            TransactionType.TRANSFER_IN,
                            TransactionType.TRANSFER_OUT,
                            TransactionType.FEE,
                            TransactionType.TAX,
                        ).forEach { type ->
                            DropdownMenuItem(
                                text = { Text(type.name, color = colors.content) },
                                onClick = {
                                    selectedType = type
                                    typeDropdownExpanded = false
                                },
                            )
                        }
                    }
                }

                // Trade Date
                OutlinedTextField(
                    value = tradeDateText,
                    onValueChange = { tradeDateText = it },
                    label = { Text("Trade Date (YYYY-MM-DD)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(8.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = colors.surface,
                        unfocusedContainerColor = colors.surface,
                        focusedBorderColor = colors.brand,
                        unfocusedBorderColor = colors.border,
                        focusedTextColor = colors.content,
                        unfocusedTextColor = colors.content,
                    ),
                )

                // Quantity
                OutlinedTextField(
                    value = quantityText,
                    onValueChange = { newQty ->
                        quantityText = newQty
                        val qty = newQty.toBigDecimalOrNull()
                        val price = unitPriceText.toBigDecimalOrNull()
                        if (qty != null && price != null) {
                            netAmountText = qty.multiply(price).setScale(2, RoundingMode.HALF_UP).toPlainString()
                        }
                    },
                    label = { Text("Quantity") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(8.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = colors.surface,
                        unfocusedContainerColor = colors.surface,
                        focusedBorderColor = colors.brand,
                        unfocusedBorderColor = colors.border,
                        focusedTextColor = colors.content,
                        unfocusedTextColor = colors.content,
                    ),
                )

                // Unit Price
                OutlinedTextField(
                    value = unitPriceText,
                    onValueChange = { newPrice ->
                        unitPriceText = newPrice
                        val qty = quantityText.toBigDecimalOrNull()
                        val price = newPrice.toBigDecimalOrNull()
                        if (qty != null && price != null) {
                            netAmountText = qty.multiply(price).setScale(2, RoundingMode.HALF_UP).toPlainString()
                        }
                    },
                    label = { Text("Unit Price (₹)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(8.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = colors.surface,
                        unfocusedContainerColor = colors.surface,
                        focusedBorderColor = colors.brand,
                        unfocusedBorderColor = colors.border,
                        focusedTextColor = colors.content,
                        unfocusedTextColor = colors.content,
                    ),
                )

                // Net Amount
                OutlinedTextField(
                    value = netAmountText,
                    onValueChange = { netAmountText = it },
                    label = { Text("Net Total Amount (₹)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(8.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = colors.surface,
                        unfocusedContainerColor = colors.surface,
                        focusedBorderColor = colors.brand,
                        unfocusedBorderColor = colors.border,
                        focusedTextColor = colors.content,
                        unfocusedTextColor = colors.content,
                    ),
                )

                // Fees
                OutlinedTextField(
                    value = feesText,
                    onValueChange = { feesText = it },
                    label = { Text("Fees (₹)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(8.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = colors.surface,
                        unfocusedContainerColor = colors.surface,
                        focusedBorderColor = colors.brand,
                        unfocusedBorderColor = colors.border,
                        focusedTextColor = colors.content,
                        unfocusedTextColor = colors.content,
                    ),
                )

                // Taxes
                OutlinedTextField(
                    value = taxesText,
                    onValueChange = { taxesText = it },
                    label = { Text("Taxes (₹)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(8.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = colors.surface,
                        unfocusedContainerColor = colors.surface,
                        focusedBorderColor = colors.brand,
                        unfocusedBorderColor = colors.border,
                        focusedTextColor = colors.content,
                        unfocusedTextColor = colors.content,
                    ),
                )

                // Broker
                OutlinedTextField(
                    value = brokerText,
                    onValueChange = { brokerText = it },
                    label = { Text("Broker / Institution (Optional)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(8.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = colors.surface,
                        unfocusedContainerColor = colors.surface,
                        focusedBorderColor = colors.brand,
                        unfocusedBorderColor = colors.border,
                        focusedTextColor = colors.content,
                        unfocusedTextColor = colors.content,
                    ),
                )

                // Notes
                OutlinedTextField(
                    value = notesText,
                    onValueChange = { notesText = it },
                    label = { Text("Notes / Reference (Optional)") },
                    singleLine = false,
                    maxLines = 3,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(8.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = colors.surface,
                        unfocusedContainerColor = colors.surface,
                        focusedBorderColor = colors.brand,
                        unfocusedBorderColor = colors.border,
                        focusedTextColor = colors.content,
                        unfocusedTextColor = colors.content,
                    ),
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val parsedDate = DateParser.parseToLocalDate(tradeDateText.trim())
                    if (parsedDate == null) {
                        errorMessage = "Invalid date format. Use YYYY-MM-DD or DD-MM-YYYY."
                        return@Button
                    }

                    val cleanQty = quantityText.trim().toBigDecimalOrNull()
                    if (cleanQty == null || cleanQty < BigDecimal.ZERO) {
                        errorMessage = "Quantity must be a positive number."
                        return@Button
                    }

                    val cleanPrice = unitPriceText.trim().toBigDecimalOrNull() ?: BigDecimal.ZERO
                    val cleanNetAmount = netAmountText.trim().toBigDecimalOrNull()
                    if (cleanNetAmount == null || cleanNetAmount < BigDecimal.ZERO) {
                        errorMessage = "Net Amount must be a positive number."
                        return@Button
                    }

                    val cleanFees = feesText.trim().toBigDecimalOrNull() ?: BigDecimal.ZERO
                    val cleanTaxes = taxesText.trim().toBigDecimalOrNull() ?: BigDecimal.ZERO

                    val updatedTxn = transaction.copy(
                        type = selectedType,
                        tradeDate = parsedDate,
                        quantity = cleanQty,
                        unitPrice = cleanPrice,
                        grossAmount = cleanQty.multiply(cleanPrice).setScale(2, RoundingMode.HALF_UP),
                        fees = cleanFees,
                        taxes = cleanTaxes,
                        netAmount = cleanNetAmount,
                        broker = brokerText.trim().ifEmpty { null },
                        notes = notesText.trim().ifEmpty { null },
                        updatedAt = System.currentTimeMillis(),
                    )
                    onSave(updatedTxn)
                },
                colors = ButtonDefaults.buttonColors(
                    containerColor = colors.brand,
                    contentColor = colors.onAccent,
                ),
            ) {
                Text("Save Changes")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", color = colors.muted)
            }
        },
        containerColor = colors.surface,
    )
}

@Composable
private fun DeleteTransactionConfirmDialog(
    transaction: TransactionEntity,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    val colors = LocalOpenFolioColors.current
    val formattedDate = remember(transaction.tradeDate) {
        try {
            transaction.tradeDate.format(DISPLAY_DATE_FORMATTER)
        } catch (_: Exception) {
            transaction.tradeDate.toString()
        }
    }
    val qtyStr = transaction.quantity?.let { formatQuantityDisplay(it) } ?: "0"

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = "Delete Transaction?",
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                color = colors.content,
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "Are you sure you want to delete this ${transaction.type.name} transaction of $qtyStr units on $formattedDate?",
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.content,
                )
                Text(
                    text = "The parent asset's holding quantity, total invested, average cost, and realized P&L will be automatically recalculated and synchronized.",
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.muted,
                )
            }
        },
        confirmButton = {
            Button(
                onClick = onConfirm,
                colors = ButtonDefaults.buttonColors(
                    containerColor = colors.lossGlyph,
                    contentColor = colors.onAccent,
                ),
            ) {
                Text("Delete")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", color = colors.muted)
            }
        },
        containerColor = colors.surface,
    )
}

@Composable
private fun RenameAssetDialog(
    currentName: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    val colors = LocalOpenFolioColors.current
    var nameText by remember { mutableStateOf(currentName) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = "Rename Asset",
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                color = colors.content,
            )
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                errorMessage?.let { error ->
                    Text(
                        text = error,
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.lossGlyph,
                    )
                }
                OutlinedTextField(
                    value = nameText,
                    onValueChange = {
                        nameText = it
                        if (it.isNotBlank()) errorMessage = null
                    },
                    label = { Text("Display Name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(8.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = colors.surface,
                        unfocusedContainerColor = colors.surface,
                        focusedBorderColor = colors.brand,
                        unfocusedBorderColor = colors.border,
                        focusedTextColor = colors.content,
                        unfocusedTextColor = colors.content,
                    ),
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val trimmed = nameText.trim()
                    if (trimmed.isEmpty()) {
                        errorMessage = "Asset name cannot be empty."
                        return@Button
                    }
                    onConfirm(trimmed)
                },
                colors = ButtonDefaults.buttonColors(
                    containerColor = colors.brand,
                    contentColor = colors.onAccent,
                ),
            ) {
                Text("Save")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", color = colors.muted)
            }
        },
        containerColor = colors.surface,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditAssetDetailsDialog(
    asset: AssetEntity,
    onDismiss: () -> Unit,
    onSave: (displayName: String, ticker: String?, isin: String?, assetClass: AssetClass) -> Unit,
) {
    val colors = LocalOpenFolioColors.current
    var nameText by remember { mutableStateOf(asset.displayName) }

    val initialTicker = remember(asset.identifiersJson) {
        runCatching {
            org.json.JSONObject(asset.identifiersJson).optString("ticker").takeIf(String::isNotBlank)
        }.getOrNull()
    }
    var tickerText by remember { mutableStateOf(initialTicker ?: "") }

    val initialIsin = remember(asset.identifiersJson, asset.displayName) {
        runCatching {
            org.json.JSONObject(asset.identifiersJson).optString("isin").takeIf(String::isNotBlank)
        }.getOrNull() ?: AssetNameNormalizer.extractIsin(asset.displayName)
    }
    var isinText by remember { mutableStateOf(initialIsin ?: "") }

    var selectedClass by remember { mutableStateOf(asset.assetClass) }
    var classDropdownExpanded by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = "Edit Asset Details",
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                color = colors.content,
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                errorMessage?.let { error ->
                    Text(
                        text = error,
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.lossGlyph,
                    )
                }

                // Display Name
                OutlinedTextField(
                    value = nameText,
                    onValueChange = {
                        nameText = it
                        if (it.isNotBlank()) errorMessage = null
                    },
                    label = { Text("Asset Name *") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(8.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = colors.surface,
                        unfocusedContainerColor = colors.surface,
                        focusedBorderColor = colors.brand,
                        unfocusedBorderColor = colors.border,
                        focusedTextColor = colors.content,
                        unfocusedTextColor = colors.content,
                    ),
                )

                // Ticker / Symbol
                OutlinedTextField(
                    value = tickerText,
                    onValueChange = { tickerText = it },
                    label = { Text("Ticker / Symbol (e.g., RELIANCE, TCS)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(8.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = colors.surface,
                        unfocusedContainerColor = colors.surface,
                        focusedBorderColor = colors.brand,
                        unfocusedBorderColor = colors.border,
                        focusedTextColor = colors.content,
                        unfocusedTextColor = colors.content,
                    ),
                )

                // ISIN
                OutlinedTextField(
                    value = isinText,
                    onValueChange = { isinText = it },
                    label = { Text("ISIN (e.g., INE002A01018)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(8.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = colors.surface,
                        unfocusedContainerColor = colors.surface,
                        focusedBorderColor = colors.brand,
                        unfocusedBorderColor = colors.border,
                        focusedTextColor = colors.content,
                        unfocusedTextColor = colors.content,
                    ),
                )

                // Asset Class Dropdown
                ExposedDropdownMenuBox(
                    expanded = classDropdownExpanded,
                    onExpandedChange = { classDropdownExpanded = it },
                ) {
                    OutlinedTextField(
                        value = selectedClass.toDisplayTitle(),
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("Asset Class") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = classDropdownExpanded) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .menuAnchor(MenuAnchorType.PrimaryNotEditable),
                        shape = RoundedCornerShape(8.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedContainerColor = colors.surface,
                            unfocusedContainerColor = colors.surface,
                            focusedBorderColor = colors.brand,
                            unfocusedBorderColor = colors.border,
                            focusedTextColor = colors.content,
                            unfocusedTextColor = colors.content,
                        ),
                    )
                    ExposedDropdownMenu(
                        expanded = classDropdownExpanded,
                        onDismissRequest = { classDropdownExpanded = false },
                        modifier = Modifier.background(colors.surface),
                    ) {
                        AssetClass.entries.forEach { assetClass ->
                            DropdownMenuItem(
                                text = { Text(assetClass.toDisplayTitle(), color = colors.content) },
                                onClick = {
                                    selectedClass = assetClass
                                    classDropdownExpanded = false
                                },
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val trimmed = nameText.trim()
                    if (trimmed.isEmpty()) {
                        errorMessage = "Asset name cannot be blank."
                        return@Button
                    }
                    val cleanIsin = isinText.trim().uppercase().takeIf(String::isNotBlank)
                    if (cleanIsin != null && cleanIsin.length != 12) {
                        errorMessage = "ISIN must be 12 characters (e.g., INE002A01018)."
                        return@Button
                    }
                    onSave(
                        trimmed,
                        tickerText.trim().uppercase().takeIf(String::isNotBlank),
                        cleanIsin,
                        selectedClass,
                    )
                },
                enabled = nameText.isNotBlank(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = colors.brand,
                    contentColor = colors.onAccent,
                ),
            ) {
                Text("Save Changes")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", color = colors.muted)
            }
        },
        containerColor = colors.surface,
    )
}
