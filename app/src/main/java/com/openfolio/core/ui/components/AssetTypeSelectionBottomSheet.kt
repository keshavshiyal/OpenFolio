package com.openfolio.core.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ShowChart
import androidx.compose.material.icons.filled.AccountBalance
import androidx.compose.material.icons.filled.Category
import androidx.compose.material.icons.filled.CurrencyBitcoin
import androidx.compose.material.icons.filled.PieChart
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.openfolio.core.data.local.entity.AssetClass
import com.openfolio.core.ui.theme.LocalOpenFolioColors

data class AssetTypeOption(
    val assetClass: AssetClass,
    val title: String,
    val description: String,
    val icon: ImageVector,
)

private val ASSET_TYPE_OPTIONS = listOf(
    AssetTypeOption(
        assetClass = AssetClass.MUTUAL_FUND,
        title = "Mutual Funds",
        description = "CAMS, KFintech, CAS consolidated statements, AMC",
        icon = Icons.Filled.PieChart,
    ),
    AssetTypeOption(
        assetClass = AssetClass.STOCK,
        title = "Stocks & ETFs",
        description = "Zerodha, Groww, Upstox, Angel One trade books",
        icon = Icons.AutoMirrored.Outlined.ShowChart,
    ),
    AssetTypeOption(
        assetClass = AssetClass.CRYPTO,
        title = "Cryptocurrency",
        description = "Exchange trade history & crypto transactions",
        icon = Icons.Filled.CurrencyBitcoin,
    ),
    AssetTypeOption(
        assetClass = AssetClass.FD,
        title = "Fixed Deposits",
        description = "Bank fixed deposits & interest certificates",
        icon = Icons.Filled.AccountBalance,
    ),
    AssetTypeOption(
        assetClass = AssetClass.SGB,
        title = "Bonds & SGB",
        description = "Sovereign Gold Bonds, Govt & Corporate Bonds",
        icon = Icons.Filled.Shield,
    ),
    AssetTypeOption(
        assetClass = AssetClass.OTHER,
        title = "Other Assets",
        description = "Real estate, gold, vehicles or custom assets",
        icon = Icons.Filled.Category,
    ),
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AssetTypeSelectionBottomSheet(
    onDismissRequest: () -> Unit,
    onSelectAssetType: (AssetClass) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalOpenFolioColors.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        sheetState = sheetState,
        containerColor = colors.surface,
        modifier = modifier,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = 36.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = "Import Statement",
                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                    color = colors.content,
                )
                Text(
                    text = "Select the type of asset statement you are importing",
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.muted,
                )
            }

            Spacer(modifier = Modifier.height(4.dp))

            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                ASSET_TYPE_OPTIONS.forEach { option ->
                    AssetTypeOptionCard(
                        option = option,
                        onClick = { onSelectAssetType(option.assetClass) },
                    )
                }
            }
        }
    }
}

@Composable
private fun AssetTypeOptionCard(
    option: AssetTypeOption,
    onClick: () -> Unit,
) {
    val colors = LocalOpenFolioColors.current

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(12.dp),
        color = colors.background,
        border = BorderStroke(1.dp, colors.border),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Surface(
                shape = CircleShape,
                color = colors.brand.copy(alpha = 0.12f),
                modifier = Modifier.size(40.dp),
            ) {
                Icon(
                    imageVector = option.icon,
                    contentDescription = null,
                    tint = colors.brand,
                    modifier = Modifier
                        .padding(8.dp)
                        .size(24.dp),
                )
            }

            Spacer(modifier = Modifier.width(14.dp))

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = option.title,
                    style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold),
                    color = colors.content,
                )
                Text(
                    text = option.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.muted,
                )
            }
        }
    }
}
