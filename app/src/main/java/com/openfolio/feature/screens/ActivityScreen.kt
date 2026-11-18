package com.openfolio.feature.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.CallSplit
import androidx.compose.material.icons.filled.CardGiftcard
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.Paid
import androidx.compose.material.icons.filled.Receipt
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.openfolio.core.data.local.entity.AssetClass
import com.openfolio.core.data.local.entity.TransactionEntity
import com.openfolio.core.data.local.entity.TransactionType
import com.openfolio.core.ui.components.MoneyText
import com.openfolio.core.ui.components.MoneyTextStyle
import com.openfolio.core.ui.theme.LocalOpenFolioColors
import com.openfolio.feature.activity.ActivityDateRange
import com.openfolio.feature.activity.ActivityTransactionItem
import com.openfolio.feature.activity.ActivityUiState
import com.openfolio.feature.activity.ActivityViewModel
import com.openfolio.feature.assets.toDisplayTitle
import java.math.BigDecimal
import java.time.format.DateTimeFormatter
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ActivityScreen(
    viewModel: ActivityViewModel,
    onNavigateToInbox: () -> Unit,
    onNavigateToAsset: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val colors = LocalOpenFolioColors.current
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(uiState.userMessage) {
        uiState.userMessage?.let { message ->
            snackbarHostState.showSnackbar(message)
            viewModel.clearMessage()
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
                            text = "Activity",
                            style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                            color = colors.content,
                        )
                        if (!uiState.isLoading && uiState.transactions.isNotEmpty()) {
                            Text(
                                text = "${uiState.filteredTransactions.size} of ${uiState.totalCount} transactions",
                                style = MaterialTheme.typography.labelSmall.copy(fontFeatureSettings = "tnum"),
                                color = colors.muted,
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = colors.background,
                ),
                actions = {
                    IconButton(onClick = viewModel::toggleShowValues) {
                        Icon(
                            imageVector = if (uiState.showValues) Icons.Filled.Visibility else Icons.Filled.VisibilityOff,
                            contentDescription = if (uiState.showValues) "Hide values" else "Show values",
                            tint = colors.muted,
                        )
                    }
                },
            )
        },
    ) { innerPadding ->
        when {
            uiState.isLoading -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator(color = colors.brand)
                }
            }

            uiState.isEmpty -> {
                EmptyActivityView(
                    onImportClick = onNavigateToInbox,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding),
                )
            }

            else -> {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding),
                    contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    // Summary KPI Cards
                    item(key = "activity_summary_card") {
                        ActivitySummaryCard(
                            totalBuyVolume = uiState.totalBuyVolume,
                            totalSellVolume = uiState.totalSellVolume,
                            totalRealizedGain = uiState.totalRealizedGain,
                            showValues = uiState.showValues,
                        )
                    }

                    // Search and Filters
                    item(key = "activity_filters") {
                        ActivityFilterSection(
                            searchQuery = uiState.searchQuery,
                            onSearchQueryChange = viewModel::onSearchQueryChange,
                            selectedDateRange = uiState.selectedDateRange,
                            onSelectDateRange = viewModel::onSelectDateRange,
                            selectedTypeFilter = uiState.selectedTypeFilter,
                            onSelectTypeFilter = viewModel::onSelectTypeFilter,
                            selectedAssetClassFilter = uiState.selectedAssetClassFilter,
                            onSelectAssetClassFilter = viewModel::onSelectAssetClassFilter,
                            isFilterActive = uiState.isFilterActive,
                            onClearFilters = viewModel::clearFilters,
                        )
                    }

                    // No match state
                    if (uiState.filteredTransactions.isEmpty()) {
                        item(key = "activity_no_matches") {
                            NoMatchesView(onClearFilters = viewModel::clearFilters)
                        }
                    } else {
                        // Grouped Transactions
                        uiState.groupedTransactions.forEach { (monthYearHeader, itemsInMonth) ->
                            item(key = "header_$monthYearHeader") {
                                Text(
                                    text = monthYearHeader,
                                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                    color = colors.content,
                                    modifier = Modifier.padding(top = 8.dp, bottom = 4.dp),
                                )
                            }

                            items(itemsInMonth, key = { it.transaction.id }) { item ->
                                ActivityTransactionRow(
                                    item = item,
                                    showValues = uiState.showValues,
                                    onClick = { viewModel.onTransactionClick(item) },
                                )
                            }
                        }
                    }

                    item {
                        Spacer(modifier = Modifier.height(80.dp))
                    }
                }
            }
        }
    }

    // Transaction Details Sheet
    uiState.selectedTransactionForDetails?.let { selectedItem ->
        TransactionDetailsSheet(
            item = selectedItem,
            showValues = uiState.showValues,
            onDismiss = viewModel::onDismissDetails,
            onViewAsset = {
                viewModel.onDismissDetails()
                onNavigateToAsset(selectedItem.transaction.assetId)
            },
            onDelete = {
                viewModel.onDeleteTransactionRequested(selectedItem.transaction)
            },
        )
    }

    // Delete Confirmation Dialog
    uiState.transactionToDelete?.let { txnToDelete ->
        AlertDialog(
            onDismissRequest = viewModel::onDismissDeleteDialog,
            title = { Text("Delete Transaction", color = colors.content) },
            text = {
                Text(
                    text = "Are you sure you want to delete this ${txnToDelete.type.name} transaction? The asset's holdings, cost basis, and realized profit/loss will be automatically recalculated on FIFO basis.",
                    color = colors.muted,
                )
            },
            confirmButton = {
                Button(
                    onClick = viewModel::onConfirmDeleteTransaction,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = colors.lossGlyph,
                        contentColor = colors.onAccent,
                    ),
                    enabled = !uiState.isDeleting,
                ) {
                    if (uiState.isDeleting) {
                        CircularProgressIndicator(
                            color = colors.onAccent,
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                        )
                    } else {
                        Text("Delete")
                    }
                }
            },
            dismissButton = {
                TextButton(
                    onClick = viewModel::onDismissDeleteDialog,
                    enabled = !uiState.isDeleting,
                ) {
                    Text("Cancel", color = colors.muted)
                }
            },
            containerColor = colors.surface,
        )
    }
}

@Composable
private fun ActivitySummaryCard(
    totalBuyVolume: BigDecimal,
    totalSellVolume: BigDecimal,
    totalRealizedGain: BigDecimal,
    showValues: Boolean,
    modifier: Modifier = Modifier,
) {
    val colors = LocalOpenFolioColors.current

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = colors.surface),
        border = BorderStroke(1.dp, colors.border),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = "Total Buys",
                        style = MaterialTheme.typography.labelSmall,
                        color = colors.muted,
                    )
                    MoneyText(
                        amount = totalBuyVolume,
                        style = MoneyTextStyle.Title,
                        showValues = showValues,
                    )
                }

                Column(
                    horizontalAlignment = Alignment.End,
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        text = "Total Sells",
                        style = MaterialTheme.typography.labelSmall,
                        color = colors.muted,
                    )
                    MoneyText(
                        amount = totalSellVolume,
                        style = MoneyTextStyle.Title,
                        showValues = showValues,
                    )
                }
            }

            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(10.dp),
                color = colors.background,
                border = BorderStroke(1.dp, colors.border),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "Realized Profit / Loss",
                        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Medium),
                        color = colors.content,
                    )

                    val isPositive = totalRealizedGain >= BigDecimal.ZERO
                    val glyphColor = if (isPositive) colors.gainGlyph else colors.lossGlyph
                    val sign = if (isPositive) "+" else ""

                    if (showValues) {
                        Text(
                            text = "$sign₹${totalRealizedGain.toPlainString()}",
                            style = MaterialTheme.typography.titleMedium.copy(
                                fontWeight = FontWeight.Bold,
                                fontFeatureSettings = "tnum",
                            ),
                            color = glyphColor,
                        )
                    } else {
                        Text(
                            text = "••••••",
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                            color = colors.muted,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ActivityFilterSection(
    searchQuery: String,
    onSearchQueryChange: (String) -> Unit,
    selectedDateRange: ActivityDateRange,
    onSelectDateRange: (ActivityDateRange) -> Unit,
    selectedTypeFilter: TransactionType?,
    onSelectTypeFilter: (TransactionType?) -> Unit,
    selectedAssetClassFilter: AssetClass?,
    onSelectAssetClassFilter: (AssetClass?) -> Unit,
    isFilterActive: Boolean,
    onClearFilters: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalOpenFolioColors.current

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        // Search Input
        OutlinedTextField(
            value = searchQuery,
            onValueChange = onSearchQueryChange,
            placeholder = { Text("Search by asset, type, or note", color = colors.muted) },
            leadingIcon = {
                Icon(Icons.Filled.Search, contentDescription = null, tint = colors.muted)
            },
            trailingIcon = {
                if (searchQuery.isNotEmpty()) {
                    IconButton(onClick = { onSearchQueryChange("") }) {
                        Icon(Icons.Filled.Clear, contentDescription = "Clear", tint = colors.muted)
                    }
                }
            },
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedContainerColor = colors.surface,
                unfocusedContainerColor = colors.surface,
                focusedBorderColor = colors.brand,
                unfocusedBorderColor = colors.border,
            ),
            singleLine = true,
        )

        // Filter chips scrolling row
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (isFilterActive) {
                FilterChip(
                    selected = true,
                    onClick = onClearFilters,
                    label = { Text("Clear all") },
                    leadingIcon = {
                        Icon(Icons.Filled.Clear, contentDescription = null, modifier = Modifier.size(16.dp))
                    },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = colors.lossGlyph.copy(alpha = 0.12f),
                        selectedLabelColor = colors.lossGlyph,
                    ),
                )
            }

            // Date Range chips
            ActivityDateRange.entries.forEach { range ->
                FilterChip(
                    selected = selectedDateRange == range,
                    onClick = { onSelectDateRange(range) },
                    label = { Text(range.label) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = colors.brand.copy(alpha = 0.12f),
                        selectedLabelColor = colors.brand,
                        containerColor = colors.surface,
                        labelColor = colors.muted,
                    ),
                )
            }

            // Common Transaction Types
            listOf(
                TransactionType.BUY to "Buys",
                TransactionType.SELL to "Sells",
                TransactionType.DIVIDEND to "Dividends",
            ).forEach { (type, label) ->
                FilterChip(
                    selected = selectedTypeFilter == type,
                    onClick = {
                        onSelectTypeFilter(if (selectedTypeFilter == type) null else type)
                    },
                    label = { Text(label) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = colors.brand.copy(alpha = 0.12f),
                        selectedLabelColor = colors.brand,
                        containerColor = colors.surface,
                        labelColor = colors.muted,
                    ),
                )
            }

            // Key Asset Classes
            listOf(
                AssetClass.STOCK to "Stocks",
                AssetClass.MUTUAL_FUND to "Mutual Funds",
                AssetClass.CRYPTO to "Crypto",
                AssetClass.FD to "FD",
            ).forEach { (assetClass, label) ->
                FilterChip(
                    selected = selectedAssetClassFilter == assetClass,
                    onClick = {
                        onSelectAssetClassFilter(if (selectedAssetClassFilter == assetClass) null else assetClass)
                    },
                    label = { Text(label) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = colors.brand.copy(alpha = 0.12f),
                        selectedLabelColor = colors.brand,
                        containerColor = colors.surface,
                        labelColor = colors.muted,
                    ),
                )
            }
        }
    }
}

@Composable
private fun ActivityTransactionRow(
    item: ActivityTransactionItem,
    showValues: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalOpenFolioColors.current
    val tx = item.transaction
    val dateFormatter = remember { DateTimeFormatter.ofPattern("dd MMM yyyy", Locale.getDefault()) }

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(12.dp),
        color = colors.surface,
        border = BorderStroke(1.dp, colors.border),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Transaction Type Icon
            val (icon, tint) = getTransactionTypeIcon(tx.type, colors)
            Surface(
                shape = CircleShape,
                color = tint.copy(alpha = 0.12f),
                modifier = Modifier.size(40.dp),
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = tx.type.name,
                    tint = tint,
                    modifier = Modifier
                        .padding(10.dp)
                        .size(20.dp),
                )
            }

            Spacer(modifier = Modifier.width(12.dp))

            // Transaction Info
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        text = item.assetDisplayName,
                        style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold),
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
                            text = item.assetClass.toDisplayTitle(),
                            style = MaterialTheme.typography.labelSmall,
                            color = colors.brand,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                        )
                    }
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    val brokerPart = tx.broker?.let { " • $it" } ?: ""
                    Text(
                        text = "${tx.type.name} • ${tx.tradeDate.format(dateFormatter)}$brokerPart",
                        style = MaterialTheme.typography.bodySmall.copy(fontFeatureSettings = "tnum"),
                        color = colors.muted,
                    )
                }

                if (tx.quantity != null && tx.unitPrice != null && tx.quantity > BigDecimal.ZERO) {
                    Text(
                        text = "${tx.quantity.toPlainString()} units @ ₹${tx.unitPrice.toPlainString()}",
                        style = MaterialTheme.typography.bodySmall.copy(fontFeatureSettings = "tnum"),
                        color = colors.muted,
                    )
                }
            }

            Spacer(modifier = Modifier.width(10.dp))

            // Amount and Realized P&L
            Column(
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                MoneyText(
                    amount = tx.netAmount,
                    style = MoneyTextStyle.Body,
                    showValues = showValues,
                )

                if (item.realizedPnL != null) {
                    val isPositive = item.realizedPnL >= BigDecimal.ZERO
                    val glyphColor = if (isPositive) colors.gainGlyph else colors.lossGlyph
                    val sign = if (isPositive) "+" else ""

                    Surface(
                        shape = RoundedCornerShape(4.dp),
                        color = glyphColor.copy(alpha = 0.12f),
                    ) {
                        Text(
                            text = if (showValues) "$sign₹${item.realizedPnL.toPlainString()}" else "••••",
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontWeight = FontWeight.Bold,
                                fontFeatureSettings = "tnum",
                            ),
                            color = glyphColor,
                            modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp),
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TransactionDetailsSheet(
    item: ActivityTransactionItem,
    showValues: Boolean,
    onDismiss: () -> Unit,
    onViewAsset: () -> Unit,
    onDelete: () -> Unit,
) {
    val colors = LocalOpenFolioColors.current
    val tx = item.transaction
    val dateFormatter = remember { DateTimeFormatter.ofPattern("dd MMMM yyyy", Locale.getDefault()) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = colors.surface,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = 36.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        text = item.assetDisplayName,
                        style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                        color = colors.content,
                    )
                    Text(
                        text = item.assetClass.toDisplayTitle(),
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.muted,
                    )
                }

                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = colors.brand.copy(alpha = 0.12f),
                ) {
                    Text(
                        text = tx.type.name,
                        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                        color = colors.brand,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                    )
                }
            }

            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                color = colors.background,
                border = BorderStroke(1.dp, colors.border),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    DetailRow(label = "Date", value = tx.tradeDate.format(dateFormatter))

                    if (tx.quantity != null) {
                        DetailRow(label = "Quantity", value = tx.quantity.toPlainString())
                    }

                    if (tx.unitPrice != null) {
                        DetailRow(label = "Unit Price", value = "₹${tx.unitPrice.toPlainString()}")
                    }

                    DetailRow(
                        label = "Net Amount",
                        value = if (showValues) "₹${tx.netAmount.toPlainString()}" else "••••••",
                        isBold = true,
                    )

                    if (item.realizedPnL != null) {
                        val isPositive = item.realizedPnL >= BigDecimal.ZERO
                        val sign = if (isPositive) "+" else ""
                        val glyphColor = if (isPositive) colors.gainGlyph else colors.lossGlyph

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = "Realized P&L (FIFO)",
                                style = MaterialTheme.typography.bodySmall,
                                color = colors.muted,
                            )
                            Text(
                                text = if (showValues) "$sign₹${item.realizedPnL.toPlainString()}" else "••••••",
                                style = MaterialTheme.typography.bodySmall.copy(
                                    fontWeight = FontWeight.Bold,
                                    fontFeatureSettings = "tnum",
                                ),
                                color = glyphColor,
                            )
                        }
                    }

                    if (tx.fees != null && tx.fees > BigDecimal.ZERO) {
                        DetailRow(label = "Fees", value = "₹${tx.fees.toPlainString()}")
                    }

                    if (tx.taxes != null && tx.taxes > BigDecimal.ZERO) {
                        DetailRow(label = "Taxes", value = "₹${tx.taxes.toPlainString()}")
                    }

                    if (!tx.broker.isNullOrBlank()) {
                        DetailRow(label = "Broker / Institution", value = tx.broker)
                    }

                    if (!tx.externalRef.isNullOrBlank()) {
                        DetailRow(label = "Ref / Order ID", value = tx.externalRef)
                    }

                    if (!tx.notes.isNullOrBlank()) {
                        DetailRow(label = "Notes", value = tx.notes)
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                OutlinedButton(
                    onClick = onViewAsset,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(10.dp),
                    border = BorderStroke(1.dp, colors.brand),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = colors.brand),
                ) {
                    Icon(
                        imageVector = Icons.Filled.OpenInNew,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("View Asset")
                }

                Button(
                    onClick = onDelete,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = colors.lossGlyph.copy(alpha = 0.12f),
                        contentColor = colors.lossGlyph,
                    ),
                ) {
                    Icon(
                        imageVector = Icons.Filled.Delete,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Delete")
                }
            }
        }
    }
}

@Composable
private fun DetailRow(
    label: String,
    value: String,
    isBold: Boolean = false,
) {
    val colors = LocalOpenFolioColors.current
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = colors.muted,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall.copy(
                fontWeight = if (isBold) FontWeight.Bold else FontWeight.Medium,
                fontFeatureSettings = "tnum",
            ),
            color = colors.content,
        )
    }
}

@Composable
private fun EmptyActivityView(
    onImportClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalOpenFolioColors.current

    Box(
        modifier = modifier,
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.padding(32.dp),
        ) {
            Surface(
                shape = CircleShape,
                color = colors.brand.copy(alpha = 0.12f),
                modifier = Modifier.size(64.dp),
            ) {
                Icon(
                    imageVector = Icons.Filled.History,
                    contentDescription = null,
                    tint = colors.brand,
                    modifier = Modifier
                        .padding(16.dp)
                        .size(32.dp),
                )
            }

            Text(
                text = "No Activity Recorded",
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                color = colors.content,
            )

            Text(
                text = "Transactions from your imported statements and manual entries will appear here chronologically.",
                style = MaterialTheme.typography.bodyMedium,
                color = colors.muted,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )

            Spacer(modifier = Modifier.height(8.dp))

            Button(
                onClick = onImportClick,
                colors = ButtonDefaults.buttonColors(
                    containerColor = colors.brand,
                    contentColor = colors.onAccent,
                ),
                shape = RoundedCornerShape(10.dp),
            ) {
                Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text("Import Statement")
            }
        }
    }
}

@Composable
private fun NoMatchesView(
    onClearFilters: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalOpenFolioColors.current

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            text = "No transactions match your search",
            style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium),
            color = colors.content,
        )
        Text(
            text = "Try adjusting your filters or search terms.",
            style = MaterialTheme.typography.bodySmall,
            color = colors.muted,
        )
        OutlinedButton(
            onClick = onClearFilters,
            shape = RoundedCornerShape(8.dp),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = colors.brand),
            border = BorderStroke(1.dp, colors.brand),
        ) {
            Text("Clear Filters")
        }
    }
}

private fun getTransactionTypeIcon(
    type: TransactionType,
    colors: com.openfolio.core.ui.theme.OpenFolioColors,
): Pair<ImageVector, androidx.compose.ui.graphics.Color> {
    return when (type) {
        TransactionType.BUY, TransactionType.TRANSFER_IN ->
            Icons.Filled.ArrowDownward to colors.gainGlyph
        TransactionType.SELL, TransactionType.TRANSFER_OUT ->
            Icons.Filled.ArrowUpward to colors.lossGlyph
        TransactionType.DIVIDEND, TransactionType.INTEREST ->
            Icons.Filled.Paid to colors.gainGlyph
        TransactionType.SPLIT ->
            Icons.Filled.CallSplit to colors.chartPalette[1]
        TransactionType.BONUS ->
            Icons.Filled.CardGiftcard to colors.chartPalette[2]
        else ->
            Icons.Filled.Receipt to colors.muted
    }
}
