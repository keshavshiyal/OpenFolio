package com.openfolio.feature.activity

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.openfolio.core.data.local.dao.AssetDao
import com.openfolio.core.data.local.dao.TransactionDao
import com.openfolio.core.data.local.entity.AssetClass
import com.openfolio.core.data.local.entity.TransactionEntity
import com.openfolio.core.data.local.entity.TransactionType
import com.openfolio.core.data.repository.SettingsRepository
import com.openfolio.core.domain.calculator.FifoCostBasisCalculator
import com.openfolio.core.domain.repository.AssetRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import java.math.BigDecimal
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

@HiltViewModel
class ActivityViewModel @Inject constructor(
    private val transactionDao: TransactionDao,
    private val assetDao: AssetDao,
    private val assetRepository: AssetRepository,
    private val settingsRepository: SettingsRepository? = null,
) : ViewModel() {

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private val _selectedTypeFilter = MutableStateFlow<TransactionType?>(null)
    val selectedTypeFilter: StateFlow<TransactionType?> = _selectedTypeFilter.asStateFlow()

    private val _selectedAssetClassFilter = MutableStateFlow<AssetClass?>(null)
    val selectedAssetClassFilter: StateFlow<AssetClass?> = _selectedAssetClassFilter.asStateFlow()

    private val _selectedDateRange = MutableStateFlow(ActivityDateRange.ALL)
    val selectedDateRange: StateFlow<ActivityDateRange> = _selectedDateRange.asStateFlow()

    private val _localShowValues = MutableStateFlow(true)

    private val _selectedTransactionForDetails = MutableStateFlow<ActivityTransactionItem?>(null)
    val selectedTransactionForDetails: StateFlow<ActivityTransactionItem?> =
        _selectedTransactionForDetails.asStateFlow()

    private val _transactionToDelete = MutableStateFlow<TransactionEntity?>(null)
    val transactionToDelete: StateFlow<TransactionEntity?> = _transactionToDelete.asStateFlow()

    private val _isDeleting = MutableStateFlow(false)
    val isDeleting: StateFlow<Boolean> = _isDeleting.asStateFlow()

    private val _userMessage = MutableStateFlow<String?>(null)
    val userMessage: StateFlow<String?> = _userMessage.asStateFlow()

    private val monthYearFormatter = DateTimeFormatter.ofPattern("MMMM yyyy", Locale.getDefault())

    val uiState: StateFlow<ActivityUiState> = combine(
        transactionDao.getAll(),
        assetDao.getAll(),
        settingsRepository?.showValuesFlow ?: _localShowValues,
        combine(
            _searchQuery,
            _selectedTypeFilter,
            _selectedAssetClassFilter,
            _selectedDateRange,
        ) { query, typeFilter, classFilter, dateRange ->
            FilterParams(query, typeFilter, classFilter, dateRange)
        },
        combine(
            _selectedTransactionForDetails,
            _transactionToDelete,
            _isDeleting,
            _userMessage,
        ) { details, toDelete, deleting, message ->
            UiDialogParams(details, toDelete, deleting, message)
        },
    ) { rawTxns, allAssets, showValues, filters, dialogs ->
        val assetsById = allAssets.associateBy { it.id }

        // Compute realized gains for all sell transactions across assets using FIFO
        val txnsByAsset = rawTxns.groupBy { it.assetId }
        val allGainsMap = mutableMapOf<String, BigDecimal>()
        for ((_, assetTxns) in txnsByAsset) {
            val assetGains = FifoCostBasisCalculator.calculateTransactionGains(assetTxns)
            allGainsMap.putAll(assetGains)
        }

        // Map raw transactions to rich UI items
        val allItems = rawTxns.map { tx ->
            val asset = assetsById[tx.assetId]
            val displayName = asset?.displayName ?: formatAssetIdNicely(tx.assetId)
            val assetClass = asset?.assetClass ?: AssetClass.STOCK
            val realizedPnL = if (tx.type == TransactionType.SELL || tx.type == TransactionType.TRANSFER_OUT) {
                allGainsMap[tx.id] ?: BigDecimal.ZERO
            } else null

            ActivityTransactionItem(
                transaction = tx,
                assetDisplayName = displayName,
                assetClass = assetClass,
                currency = tx.currency,
                realizedPnL = realizedPnL,
            )
        }

        // Summary metrics across all transactions
        var totalBuyVolume = BigDecimal.ZERO
        var totalSellVolume = BigDecimal.ZERO
        var totalRealizedGain = BigDecimal.ZERO

        for (item in allItems) {
            when (item.transaction.type) {
                TransactionType.BUY, TransactionType.TRANSFER_IN -> {
                    totalBuyVolume = totalBuyVolume.add(item.transaction.netAmount)
                }
                TransactionType.SELL, TransactionType.TRANSFER_OUT -> {
                    totalSellVolume = totalSellVolume.add(item.transaction.netAmount)
                    item.realizedPnL?.let { totalRealizedGain = totalRealizedGain.add(it) }
                }
                else -> Unit
            }
        }

        // Filter items
        val filtered = allItems.filter { item ->
            matchesQuery(item, filters.query) &&
                matchesType(item, filters.typeFilter) &&
                matchesAssetClass(item, filters.classFilter) &&
                matchesDateRange(item, filters.dateRange)
        }

        // Group filtered items by Month & Year in reverse chronological order
        val grouped = linkedMapOf<String, MutableList<ActivityTransactionItem>>()
        for (item in filtered) {
            val key = item.transaction.tradeDate.format(monthYearFormatter)
            grouped.getOrPut(key) { mutableListOf() }.add(item)
        }

        ActivityUiState(
            isLoading = false,
            transactions = allItems,
            filteredTransactions = filtered,
            groupedTransactions = grouped,
            searchQuery = filters.query,
            selectedTypeFilter = filters.typeFilter,
            selectedAssetClassFilter = filters.classFilter,
            selectedDateRange = filters.dateRange,
            showValues = showValues,
            totalCount = allItems.size,
            totalBuyVolume = totalBuyVolume,
            totalSellVolume = totalSellVolume,
            totalRealizedGain = totalRealizedGain,
            selectedTransactionForDetails = dialogs.details,
            transactionToDelete = dialogs.toDelete,
            isDeleting = dialogs.deleting,
            userMessage = dialogs.message,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = ActivityUiState(isLoading = true),
    )

    fun onSearchQueryChange(query: String) {
        _searchQuery.value = query
    }

    fun onSelectTypeFilter(type: TransactionType?) {
        _selectedTypeFilter.value = type
    }

    fun onSelectAssetClassFilter(assetClass: AssetClass?) {
        _selectedAssetClassFilter.value = assetClass
    }

    fun onSelectDateRange(range: ActivityDateRange) {
        _selectedDateRange.value = range
    }

    fun clearFilters() {
        _searchQuery.value = ""
        _selectedTypeFilter.value = null
        _selectedAssetClassFilter.value = null
        _selectedDateRange.value = ActivityDateRange.ALL
    }

    fun toggleShowValues() {
        if (settingsRepository != null) {
            viewModelScope.launch {
                settingsRepository.toggleShowValues()
            }
        } else {
            _localShowValues.update { !it }
        }
    }

    fun onTransactionClick(item: ActivityTransactionItem) {
        _selectedTransactionForDetails.value = item
    }

    fun onDismissDetails() {
        _selectedTransactionForDetails.value = null
    }

    fun onDeleteTransactionRequested(transaction: TransactionEntity) {
        _transactionToDelete.value = transaction
    }

    fun onDismissDeleteDialog() {
        _transactionToDelete.value = null
    }

    fun onConfirmDeleteTransaction() {
        val toDelete = _transactionToDelete.value ?: return
        viewModelScope.launch {
            _isDeleting.value = true
            try {
                assetRepository.deleteTransaction(toDelete.id)
                _userMessage.value = "Transaction deleted and asset recalculated."
            } catch (e: Exception) {
                _userMessage.value = e.message ?: "Failed to delete transaction."
            } finally {
                _isDeleting.value = false
                _transactionToDelete.value = null
                _selectedTransactionForDetails.value = null
            }
        }
    }

    fun clearMessage() {
        _userMessage.value = null
    }

    private fun matchesQuery(item: ActivityTransactionItem, query: String): Boolean {
        if (query.isBlank()) return true
        val trimmed = query.trim()
        return item.assetDisplayName.contains(trimmed, ignoreCase = true) ||
            item.transaction.type.name.contains(trimmed, ignoreCase = true) ||
            item.assetClass.name.contains(trimmed, ignoreCase = true) ||
            item.transaction.notes?.contains(trimmed, ignoreCase = true) == true ||
            item.transaction.description?.contains(trimmed, ignoreCase = true) == true
    }

    private fun matchesType(item: ActivityTransactionItem, type: TransactionType?): Boolean {
        if (type == null) return true
        return item.transaction.type == type
    }

    private fun matchesAssetClass(item: ActivityTransactionItem, assetClass: AssetClass?): Boolean {
        if (assetClass == null) return true
        return item.assetClass == assetClass
    }

    private fun matchesDateRange(item: ActivityTransactionItem, range: ActivityDateRange): Boolean {
        if (range == ActivityDateRange.ALL) return true
        val now = LocalDate.now()
        val txDate = item.transaction.tradeDate
        return when (range) {
            ActivityDateRange.ALL -> true
            ActivityDateRange.THIS_MONTH -> {
                txDate.year == now.year && txDate.month == now.month
            }
            ActivityDateRange.LAST_3_MONTHS -> {
                val threeMonthsAgo = now.minusMonths(3).withDayOfMonth(1)
                !txDate.isBefore(threeMonthsAgo)
            }
            ActivityDateRange.THIS_YEAR -> {
                txDate.year == now.year
            }
        }
    }

    private fun formatAssetIdNicely(assetId: String): String {
        if (assetId.isBlank()) return assetId
        return assetId
            .replace('_', ' ')
            .split(' ')
            .filter { it.isNotBlank() }
            .joinToString(" ") { word ->
                word.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
            }
    }

    private data class FilterParams(
        val query: String,
        val typeFilter: TransactionType?,
        val classFilter: AssetClass?,
        val dateRange: ActivityDateRange,
    )

    private data class UiDialogParams(
        val details: ActivityTransactionItem?,
        val toDelete: TransactionEntity?,
        val deleting: Boolean,
        val message: String?,
    )
}
