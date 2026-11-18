package com.openfolio.feature.assets

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.openfolio.core.data.local.entity.AssetClass
import com.openfolio.core.data.local.entity.AssetEntity
import com.openfolio.core.data.local.entity.AssetStatus
import com.openfolio.core.data.repository.SettingsRepository
import com.openfolio.core.domain.repository.AssetRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import java.math.BigDecimal
import java.math.RoundingMode
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class AssetSortOption(val displayName: String) {
    NAME_ASC("Name (A–Z)"),
    NAME_DESC("Name (Z–A)"),
    INVESTED_DESC("Invested (High–Low)"),
    INVESTED_ASC("Invested (Low–High)"),
    REALIZED_PNL_DESC("Realized P&L (High–Low)"),
    QUANTITY_DESC("Quantity (High–Low)"),
}

data class AssetUiModel(
    val id: String,
    val displayName: String,
    val assetClass: AssetClass,
    val currency: String,
    val totalQuantity: BigDecimal,
    val totalInvested: BigDecimal,
    val averageCost: BigDecimal,
    val realizedPnL: BigDecimal,
    val currentPrice: BigDecimal?,
    val currentValue: BigDecimal?,
    val unrealizedPnL: BigDecimal?,
    val status: AssetStatus,
)

data class AssetGroup(
    val assetClass: AssetClass,
    val title: String,
    val assets: List<AssetUiModel>,
    val totalInvested: BigDecimal,
    val totalRealizedPnL: BigDecimal,
)

data class AssetsUiState(
    val isLoading: Boolean = false,
    val searchQuery: String = "",
    val selectedClass: AssetClass? = null,
    val sortOption: AssetSortOption = AssetSortOption.NAME_ASC,
    val showValues: Boolean = true,
    val availableClasses: List<AssetClass> = emptyList(),
    val groupedAssets: List<AssetGroup> = emptyList(),
    val totalPortfolioInvested: BigDecimal = BigDecimal.ZERO,
    val totalPortfolioRealizedPnL: BigDecimal = BigDecimal.ZERO,
    val totalHoldingsCount: Int = 0,
    val isEmpty: Boolean = true,
)

fun AssetClass.toDisplayTitle(): String = when (this) {
    AssetClass.STOCK -> "Stocks"
    AssetClass.MUTUAL_FUND -> "Mutual Funds"
    AssetClass.BOND -> "Bonds"
    AssetClass.SGB -> "Sovereign Gold Bonds"
    AssetClass.FD -> "Fixed Deposits"
    AssetClass.HOUSE -> "Real Estate (House)"
    AssetClass.LAND -> "Real Estate (Land)"
    AssetClass.VEHICLE -> "Vehicles"
    AssetClass.CRYPTO -> "Crypto"
    AssetClass.GOLD -> "Gold"
    AssetClass.CASH -> "Cash"
    AssetClass.OTHER -> "Other"
}

@HiltViewModel
class AssetsViewModel @Inject constructor(
    private val assetRepository: AssetRepository,
    private val settingsRepository: SettingsRepository? = null,
) : ViewModel() {

    private val _searchQuery = MutableStateFlow("")
    private val _selectedClass = MutableStateFlow<AssetClass?>(null)
    private val _sortOption = MutableStateFlow(AssetSortOption.NAME_ASC)
    private val _localShowValues = MutableStateFlow(true)

    val uiState: StateFlow<AssetsUiState> = combine(
        assetRepository.getAllAssets(),
        _searchQuery,
        _selectedClass,
        _sortOption,
        settingsRepository?.showValuesFlow ?: _localShowValues,
    ) { assets, query, selectedClass, sortOption, showValues ->
        mapToUiState(assets, query, selectedClass, sortOption, showValues)
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = AssetsUiState(isLoading = true),
    )

    fun onSearchQueryChange(query: String) {
        _searchQuery.value = query
    }

    fun onSelectAssetClass(assetClass: AssetClass?) {
        _selectedClass.value = assetClass
    }

    fun onSelectSortOption(sortOption: AssetSortOption) {
        _sortOption.value = sortOption
    }

    fun toggleValuesVisibility() {
        if (settingsRepository != null) {
            viewModelScope.launch {
                settingsRepository.toggleShowValues()
            }
        } else {
            _localShowValues.update { !it }
        }
    }

    private fun mapToUiState(
        allAssets: List<AssetEntity>,
        query: String,
        selectedClass: AssetClass?,
        sortOption: AssetSortOption,
        showValues: Boolean,
    ): AssetsUiState {
        val availableClasses = allAssets.map { it.assetClass }.distinct().sortedBy { it.name }

        // Filter by search query
        val trimmedQuery = query.trim()
        val queryFiltered = if (trimmedQuery.isEmpty()) {
            allAssets
        } else {
            allAssets.filter {
                it.displayName.contains(trimmedQuery, ignoreCase = true) ||
                    it.assetClass.name.contains(trimmedQuery, ignoreCase = true)
            }
        }

        // Filter by selected asset class
        val classFiltered = if (selectedClass == null) {
            queryFiltered
        } else {
            queryFiltered.filter { it.assetClass == selectedClass }
        }

        // Map to UI models
        val uiModels = classFiltered.map { asset ->
            val currentValue = asset.currentPrice?.let { price ->
                asset.totalQuantity.multiply(price).setScale(2, RoundingMode.HALF_UP)
            }
            val unrealizedPnL = if (currentValue != null) {
                currentValue.subtract(asset.totalInvested)
            } else null

            AssetUiModel(
                id = asset.id,
                displayName = asset.displayName,
                assetClass = asset.assetClass,
                currency = asset.currency,
                totalQuantity = asset.totalQuantity,
                totalInvested = asset.totalInvested,
                averageCost = asset.averageCost,
                realizedPnL = asset.realizedPnL,
                currentPrice = asset.currentPrice,
                currentValue = currentValue,
                unrealizedPnL = unrealizedPnL,
                status = asset.status,
            )
        }

        // Sort items
        val comparator = when (sortOption) {
            AssetSortOption.NAME_ASC -> compareBy<AssetUiModel> { it.displayName.lowercase() }
            AssetSortOption.NAME_DESC -> compareByDescending<AssetUiModel> { it.displayName.lowercase() }
            AssetSortOption.INVESTED_DESC -> compareByDescending<AssetUiModel> { it.totalInvested }
            AssetSortOption.INVESTED_ASC -> compareBy<AssetUiModel> { it.totalInvested }
            AssetSortOption.REALIZED_PNL_DESC -> compareByDescending<AssetUiModel> { it.realizedPnL }
            AssetSortOption.QUANTITY_DESC -> compareByDescending<AssetUiModel> { it.totalQuantity }
        }
        val sortedModels = uiModels.sortedWith(comparator)

        // Group by asset class visually
        val grouped = sortedModels
            .groupBy { it.assetClass }
            .map { (assetClass, assetsInGroup) ->
                val groupInvested = assetsInGroup.fold(BigDecimal.ZERO) { acc, a -> acc.add(a.totalInvested) }
                val groupRealized = assetsInGroup.fold(BigDecimal.ZERO) { acc, a -> acc.add(a.realizedPnL) }
                AssetGroup(
                    assetClass = assetClass,
                    title = assetClass.toDisplayTitle(),
                    assets = assetsInGroup,
                    totalInvested = groupInvested,
                    totalRealizedPnL = groupRealized,
                )
            }
            .sortedBy { it.title }

        val totalInvested = allAssets.fold(BigDecimal.ZERO) { acc, a -> acc.add(a.totalInvested) }
        val totalRealized = allAssets.fold(BigDecimal.ZERO) { acc, a -> acc.add(a.realizedPnL) }
        val holdingsCount = allAssets.count { it.totalQuantity > BigDecimal.ZERO }

        return AssetsUiState(
            isLoading = false,
            searchQuery = query,
            selectedClass = selectedClass,
            sortOption = sortOption,
            showValues = showValues,
            availableClasses = availableClasses,
            groupedAssets = grouped,
            totalPortfolioInvested = totalInvested,
            totalPortfolioRealizedPnL = totalRealized,
            totalHoldingsCount = holdingsCount,
            isEmpty = allAssets.isEmpty(),
        )
    }
}
