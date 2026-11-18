package com.openfolio.feature.activity

import com.openfolio.core.data.local.entity.AssetClass
import com.openfolio.core.data.local.entity.TransactionEntity
import com.openfolio.core.data.local.entity.TransactionType
import java.math.BigDecimal

enum class ActivityDateRange(val label: String) {
    ALL("All Time"),
    THIS_MONTH("This Month"),
    LAST_3_MONTHS("Last 3 Months"),
    THIS_YEAR("This Year"),
}

data class ActivityTransactionItem(
    val transaction: TransactionEntity,
    val assetDisplayName: String,
    val assetClass: AssetClass,
    val currency: String = "INR",
    val realizedPnL: BigDecimal? = null,
)

data class ActivityUiState(
    val isLoading: Boolean = true,
    val transactions: List<ActivityTransactionItem> = emptyList(),
    val filteredTransactions: List<ActivityTransactionItem> = emptyList(),
    val groupedTransactions: Map<String, List<ActivityTransactionItem>> = emptyMap(),
    val searchQuery: String = "",
    val selectedTypeFilter: TransactionType? = null,
    val selectedAssetClassFilter: AssetClass? = null,
    val selectedDateRange: ActivityDateRange = ActivityDateRange.ALL,
    val showValues: Boolean = true,
    val totalCount: Int = 0,
    val totalBuyVolume: BigDecimal = BigDecimal.ZERO,
    val totalSellVolume: BigDecimal = BigDecimal.ZERO,
    val totalRealizedGain: BigDecimal = BigDecimal.ZERO,
    val selectedTransactionForDetails: ActivityTransactionItem? = null,
    val transactionToDelete: TransactionEntity? = null,
    val isDeleting: Boolean = false,
    val userMessage: String? = null,
) {
    val isEmpty: Boolean
        get() = !isLoading && transactions.isEmpty()

    val isFilterActive: Boolean
        get() = searchQuery.isNotBlank() || selectedTypeFilter != null ||
            selectedAssetClassFilter != null || selectedDateRange != ActivityDateRange.ALL
}
