package com.openfolio.feature.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Sort
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.openfolio.core.data.local.entity.AssetClass
import com.openfolio.core.data.local.entity.AssetStatus
import com.openfolio.core.ui.components.MoneyText
import com.openfolio.core.ui.components.MoneyTextStyle
import com.openfolio.core.ui.theme.LocalOpenFolioColors
import com.openfolio.feature.assets.AssetGroup
import com.openfolio.feature.assets.AssetSortOption
import com.openfolio.feature.assets.AssetUiModel
import com.openfolio.feature.assets.AssetsUiState
import com.openfolio.feature.assets.AssetsViewModel
import com.openfolio.feature.assets.toDisplayTitle
import java.math.BigDecimal

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AssetsScreen(
    viewModel: AssetsViewModel,
    onAssetClick: (String) -> Unit,
    onImportClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val colors = LocalOpenFolioColors.current

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = colors.background,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = "Assets",
                            style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                            color = colors.content,
                        )
                        if (!uiState.isEmpty) {
                            Text(
                                text = "${uiState.totalHoldingsCount} active holdings",
                                style = MaterialTheme.typography.bodySmall.copy(
                                    fontFeatureSettings = "tnum",
                                ),
                                color = colors.muted,
                            )
                        }
                    }
                },
                actions = {
                    IconButton(onClick = viewModel::toggleValuesVisibility) {
                        Icon(
                            imageVector = if (uiState.showValues) Icons.Filled.Visibility else Icons.Filled.VisibilityOff,
                            contentDescription = if (uiState.showValues) "Hide values" else "Show values",
                            tint = colors.content,
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = colors.background,
                ),
            )
        },
    ) { innerPadding ->
        if (uiState.isLoading) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator(color = colors.brand)
            }
        } else if (uiState.isEmpty) {
            EmptyAssetsView(
                onImportClick = onImportClick,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
            )
        } else {
            AssetsContent(
                uiState = uiState,
                onSearchQueryChange = viewModel::onSearchQueryChange,
                onSelectAssetClass = viewModel::onSelectAssetClass,
                onSelectSortOption = viewModel::onSelectSortOption,
                onAssetClick = onAssetClick,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
            )
        }
    }
}

@Composable
private fun AssetsContent(
    uiState: AssetsUiState,
    onSearchQueryChange: (String) -> Unit,
    onSelectAssetClass: (AssetClass?) -> Unit,
    onSelectSortOption: (AssetSortOption) -> Unit,
    onAssetClick: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalOpenFolioColors.current
    var sortMenuExpanded by remember { mutableStateOf(false) }

    LazyColumn(
        modifier = modifier,
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        // Controls: Search & Sort Row
        item(key = "controls_header") {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                // Search Input
                OutlinedTextField(
                    value = uiState.searchQuery,
                    onValueChange = onSearchQueryChange,
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = {
                        Text(
                            text = "Search assets by name or class...",
                            style = MaterialTheme.typography.bodyMedium,
                            color = colors.muted,
                        )
                    },
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Filled.Search,
                            contentDescription = "Search",
                            tint = colors.muted,
                        )
                    },
                    trailingIcon = {
                        if (uiState.searchQuery.isNotEmpty()) {
                            IconButton(onClick = { onSearchQueryChange("") }) {
                                Icon(
                                    imageVector = Icons.Filled.Clear,
                                    contentDescription = "Clear search",
                                    tint = colors.muted,
                                )
                            }
                        }
                    },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = colors.surface,
                        unfocusedContainerColor = colors.surface,
                        focusedBorderColor = colors.brand,
                        unfocusedBorderColor = colors.border,
                        focusedTextColor = colors.content,
                        unfocusedTextColor = colors.content,
                    ),
                )

                // Sort & Filter Bar
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // Sort Dropdown Button
                    Box {
                        OutlinedButton(
                            onClick = { sortMenuExpanded = true },
                            shape = RoundedCornerShape(8.dp),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                            border = ButtonDefaults.outlinedButtonBorder.copy(
                                brush = androidx.compose.ui.graphics.SolidColor(colors.border),
                            ),
                            colors = ButtonDefaults.outlinedButtonColors(
                                containerColor = colors.surface,
                                contentColor = colors.content,
                            ),
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Sort,
                                contentDescription = "Sort",
                                modifier = Modifier.size(16.dp),
                                tint = colors.brand,
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = uiState.sortOption.displayName,
                                style = MaterialTheme.typography.labelMedium,
                                color = colors.content,
                            )
                            Icon(
                                imageVector = Icons.Filled.ArrowDropDown,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                                tint = colors.muted,
                            )
                        }

                        DropdownMenu(
                            expanded = sortMenuExpanded,
                            onDismissRequest = { sortMenuExpanded = false },
                            modifier = Modifier.background(colors.surface),
                        ) {
                            AssetSortOption.entries.forEach { option ->
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            text = option.displayName,
                                            style = MaterialTheme.typography.bodyMedium.copy(
                                                fontWeight = if (option == uiState.sortOption) FontWeight.Bold else FontWeight.Normal,
                                            ),
                                            color = if (option == uiState.sortOption) colors.brand else colors.content,
                                        )
                                    },
                                    onClick = {
                                        onSelectSortOption(option)
                                        sortMenuExpanded = false
                                    },
                                )
                            }
                        }
                    }

                    // Class filter count label
                    if (uiState.selectedClass != null) {
                        Text(
                            text = "Filtered by ${uiState.selectedClass.toDisplayTitle()}",
                            style = MaterialTheme.typography.labelSmall,
                            color = colors.brand,
                        )
                    }
                }

                // Filter Chips Row
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    FilterChip(
                        selected = uiState.selectedClass == null,
                        onClick = { onSelectAssetClass(null) },
                        label = { Text("All") },
                        colors = FilterChipDefaults.filterChipColors(
                            containerColor = colors.surface,
                            labelColor = colors.content,
                            selectedContainerColor = colors.brandSubtle,
                            selectedLabelColor = colors.brand,
                        ),
                        border = FilterChipDefaults.filterChipBorder(
                            enabled = true,
                            selected = uiState.selectedClass == null,
                            borderColor = colors.border,
                            selectedBorderColor = colors.brand,
                        ),
                    )

                    uiState.availableClasses.forEach { assetClass ->
                        FilterChip(
                            selected = uiState.selectedClass == assetClass,
                            onClick = {
                                onSelectAssetClass(if (uiState.selectedClass == assetClass) null else assetClass)
                            },
                            label = { Text(assetClass.toDisplayTitle()) },
                            colors = FilterChipDefaults.filterChipColors(
                                containerColor = colors.surface,
                                labelColor = colors.content,
                                selectedContainerColor = colors.brandSubtle,
                                selectedLabelColor = colors.brand,
                            ),
                            border = FilterChipDefaults.filterChipBorder(
                                enabled = true,
                                selected = uiState.selectedClass == assetClass,
                                borderColor = colors.border,
                                selectedBorderColor = colors.brand,
                            ),
                        )
                    }
                }
            }
        }

        // Search Results Check
        if (uiState.groupedAssets.isEmpty()) {
            item(key = "no_search_results") {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 48.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            text = "No matching assets found",
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                            color = colors.content,
                        )
                        Text(
                            text = "Try adjusting your search query or filter chips",
                            style = MaterialTheme.typography.bodyMedium,
                            color = colors.muted,
                        )
                    }
                }
            }
        } else {
            // Grouped Assets
            uiState.groupedAssets.forEach { group ->
                item(key = "group_header_${group.assetClass.name}") {
                    AssetGroupHeader(
                        group = group,
                        showValues = uiState.showValues,
                    )
                }

                items(
                    items = group.assets,
                    key = { it.id },
                ) { asset ->
                    AssetCard(
                        asset = asset,
                        showValues = uiState.showValues,
                        onClick = { onAssetClick(asset.id) },
                    )
                }
            }
        }
    }
}

@Composable
private fun AssetGroupHeader(
    group: AssetGroup,
    showValues: Boolean,
    modifier: Modifier = Modifier,
) {
    val colors = LocalOpenFolioColors.current

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(top = 12.dp, bottom = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = group.title,
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
                    text = "${group.assets.size}",
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontWeight = FontWeight.Bold,
                        fontFeatureSettings = "tnum",
                    ),
                    color = colors.brand,
                )
            }
        }

        // Subtotal invested
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = "Invested:",
                style = MaterialTheme.typography.labelSmall,
                color = colors.muted,
            )
            MoneyText(
                amount = group.totalInvested,
                showValues = showValues,
                style = MoneyTextStyle.Body,
            )
        }
    }
}

@Composable
private fun AssetCard(
    asset: AssetUiModel,
    showValues: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalOpenFolioColors.current
    val typography = MaterialTheme.typography

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .border(1.dp, colors.border, RoundedCornerShape(12.dp))
            .clickable(onClick = onClick),
        color = colors.surface,
        tonalElevation = 1.dp,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // Row 1: Name, Class Badge, Status
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = asset.displayName,
                        style = typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                        color = colors.content,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = asset.assetClass.toDisplayTitle(),
                        style = typography.labelSmall,
                        color = colors.muted,
                    )
                }

                if (asset.status == AssetStatus.SOLD || asset.totalQuantity.compareTo(BigDecimal.ZERO) == 0) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(colors.border)
                            .padding(horizontal = 8.dp, vertical = 2.dp),
                    ) {
                        Text(
                            text = "Sold Out",
                            style = typography.labelSmall.copy(fontWeight = FontWeight.Medium),
                            color = colors.muted,
                        )
                    }
                }
            }

            // Row 2: Metrics Grid (Quantity, Total Invested, Realized Gain/Loss)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                // Quantity Held
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "HOLDINGS",
                        style = typography.labelSmall.copy(fontWeight = FontWeight.Medium),
                        color = colors.muted,
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = if (showValues) {
                            formatQuantityDisplay(asset.totalQuantity)
                        } else {
                            "— — —"
                        },
                        style = typography.bodyMedium.copy(
                            fontWeight = FontWeight.SemiBold,
                            fontFeatureSettings = "tnum",
                        ),
                        color = colors.content,
                    )
                }

                // Total Invested
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "INVESTED",
                        style = typography.labelSmall.copy(fontWeight = FontWeight.Medium),
                        color = colors.muted,
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    MoneyText(
                        amount = asset.totalInvested,
                        showValues = showValues,
                        style = MoneyTextStyle.Body,
                    )
                }

                // Realized P&L
                Column(
                    modifier = Modifier.weight(1f),
                    horizontalAlignment = Alignment.End,
                ) {
                    Text(
                        text = "REALIZED P&L",
                        style = typography.labelSmall.copy(fontWeight = FontWeight.Medium),
                        color = colors.muted,
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    RealizedPnLDisplay(
                        realizedPnL = asset.realizedPnL,
                        showValues = showValues,
                    )
                }
            }

            // Row 3: Future Compatibility Placeholders (Current Value & Unrealized PnL)
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = colors.background,
                shape = RoundedCornerShape(8.dp),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // Current Value Placeholder
                    Column {
                        Text(
                            text = "CURRENT VALUE",
                            style = typography.labelSmall.copy(fontSize = typography.labelSmall.fontSize * 0.9f),
                            color = colors.muted,
                        )
                        if (asset.currentValue != null) {
                            MoneyText(
                                amount = asset.currentValue,
                                showValues = showValues,
                                style = MoneyTextStyle.Body,
                            )
                        } else {
                            Text(
                                text = "₹ — — —",
                                style = typography.bodySmall.copy(
                                    fontFeatureSettings = "tnum",
                                    fontWeight = FontWeight.Medium,
                                ),
                                color = colors.muted,
                            )
                        }
                    }

                    // Unrealized PnL Placeholder
                    Column(horizontalAlignment = Alignment.End) {
                        Text(
                            text = "UNREALIZED P&L",
                            style = typography.labelSmall.copy(fontSize = typography.labelSmall.fontSize * 0.9f),
                            color = colors.muted,
                        )
                        if (asset.unrealizedPnL != null) {
                            RealizedPnLDisplay(
                                realizedPnL = asset.unrealizedPnL,
                                showValues = showValues,
                            )
                        } else {
                            Text(
                                text = "— — —",
                                style = typography.bodySmall.copy(
                                    fontFeatureSettings = "tnum",
                                    fontWeight = FontWeight.Medium,
                                ),
                                color = colors.muted,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun RealizedPnLDisplay(
    realizedPnL: BigDecimal,
    showValues: Boolean,
    modifier: Modifier = Modifier,
) {
    val colors = LocalOpenFolioColors.current
    val typography = MaterialTheme.typography
    val isPositive = realizedPnL > BigDecimal.ZERO
    val isNegative = realizedPnL < BigDecimal.ZERO

    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (showValues && (isPositive || isNegative)) {
            Text(
                text = if (isPositive) "▲" else "▼",
                style = typography.bodySmall,
                color = if (isPositive) colors.gainGlyph else colors.lossGlyph,
            )
        }
        MoneyText(
            amount = realizedPnL.abs(),
            showValues = showValues,
            style = MoneyTextStyle.Body,
        )
    }
}

@Composable
private fun EmptyAssetsView(
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
            verticalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier.padding(32.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(72.dp)
                    .clip(CircleShape)
                    .background(colors.brandSubtle),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Filled.AccountBalanceWallet,
                    contentDescription = null,
                    tint = colors.brand,
                    modifier = Modifier.size(36.dp),
                )
            }

            Text(
                text = "No assets yet",
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                color = colors.content,
                textAlign = TextAlign.Center,
            )

            Text(
                text = "Import a statement or add transactions to see your holdings, cost basis, and realized returns.",
                style = MaterialTheme.typography.bodyMedium,
                color = colors.muted,
                textAlign = TextAlign.Center,
            )

            Spacer(modifier = Modifier.height(8.dp))

            OutlinedButton(
                onClick = onImportClick,
                shape = RoundedCornerShape(8.dp),
                border = ButtonDefaults.outlinedButtonBorder.copy(
                    brush = androidx.compose.ui.graphics.SolidColor(colors.brand),
                ),
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = colors.brand,
                ),
            ) {
                Text("Import a statement")
            }
        }
    }
}

internal fun formatQuantityDisplay(quantity: BigDecimal): String {
    val clean = quantity.stripTrailingZeros().toPlainString()
    return clean
}
