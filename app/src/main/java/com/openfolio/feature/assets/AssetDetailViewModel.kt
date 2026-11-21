package com.openfolio.feature.assets

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.openfolio.core.data.local.entity.AssetClass
import com.openfolio.core.data.local.entity.AssetEntity
import com.openfolio.core.data.local.entity.TransactionEntity
import com.openfolio.core.data.local.entity.TransactionStatus
import com.openfolio.core.data.local.entity.TransactionType
import com.openfolio.core.data.repository.SettingsRepository
import com.openfolio.core.domain.repository.AssetRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class AssetDetailUiState(
    val isLoading: Boolean = true,
    val asset: AssetEntity? = null,
    val transactions: List<TransactionEntity> = emptyList(),
    val showValues: Boolean = true,
    val currentValue: BigDecimal? = null,
    val unrealizedPnL: BigDecimal? = null,
    val editingTransaction: TransactionEntity? = null,
    val deletingTransaction: TransactionEntity? = null,
    val isSaving: Boolean = false,
    val isDeleted: Boolean = false,
    val errorMessage: String? = null,
)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class AssetDetailViewModel @Inject constructor(
    private val assetRepository: AssetRepository,
    private val settingsRepository: SettingsRepository? = null,
) : ViewModel() {

    private val _assetId = MutableStateFlow("")
    private val _localShowValues = MutableStateFlow(true)
    private val _editingTransaction = MutableStateFlow<TransactionEntity?>(null)
    private val _deletingTransaction = MutableStateFlow<TransactionEntity?>(null)
    private val _isSaving = MutableStateFlow(false)
    private val _isDeleted = MutableStateFlow(false)
    private val _errorMessage = MutableStateFlow<String?>(null)

    val uiState: StateFlow<AssetDetailUiState> = _assetId.flatMapLatest { id ->
        if (id.isBlank()) {
            flowOf(AssetDetailUiState(isLoading = false))
        } else {
            combine(
                assetRepository.getAssetById(id),
                assetRepository.getTransactionsForAsset(id),
                settingsRepository?.showValuesFlow ?: _localShowValues,
                _editingTransaction,
                _deletingTransaction,
                _isSaving,
                _isDeleted,
                _errorMessage,
            ) { values ->
                val asset = values[0] as AssetEntity?
                @Suppress("UNCHECKED_CAST")
                val transactions = values[1] as List<TransactionEntity>
                val showValues = values[2] as Boolean
                val editing = values[3] as TransactionEntity?
                val deleting = values[4] as TransactionEntity?
                val isSaving = values[5] as Boolean
                val isDeleted = values[6] as Boolean
                val error = values[7] as String?

                val currentValue = if (asset?.currentPrice != null) {
                    asset.totalQuantity.multiply(asset.currentPrice).setScale(2, RoundingMode.HALF_UP)
                } else null

                val unrealizedPnL = if (currentValue != null && asset != null) {
                    currentValue.subtract(asset.totalInvested)
                } else null

                AssetDetailUiState(
                    isLoading = false,
                    asset = asset,
                    transactions = transactions,
                    showValues = showValues,
                    currentValue = currentValue,
                    unrealizedPnL = unrealizedPnL,
                    editingTransaction = editing,
                    deletingTransaction = deleting,
                    isSaving = isSaving,
                    isDeleted = isDeleted,
                    errorMessage = error,
                )
            }
        }
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = AssetDetailUiState(isLoading = true),
    )

    fun loadAsset(assetId: String) {
        _assetId.value = assetId
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

    fun startEditTransaction(transaction: TransactionEntity) {
        _editingTransaction.value = transaction
    }

    fun dismissEditTransaction() {
        _editingTransaction.value = null
    }

    fun saveEditedTransaction(updatedTransaction: TransactionEntity) {
        viewModelScope.launch {
            _isSaving.value = true
            try {
                assetRepository.updateTransaction(updatedTransaction)
                _editingTransaction.value = null
            } catch (e: Exception) {
                _errorMessage.value = e.message ?: "Failed to update transaction"
            } finally {
                _isSaving.value = false
            }
        }
    }

    fun startDeleteTransaction(transaction: TransactionEntity) {
        _deletingTransaction.value = transaction
    }

    fun dismissDeleteTransaction() {
        _deletingTransaction.value = null
    }

    fun confirmDeleteTransaction() {
        val toDelete = _deletingTransaction.value ?: return
        viewModelScope.launch {
            _isSaving.value = true
            try {
                assetRepository.deleteTransaction(toDelete.id)
                _deletingTransaction.value = null
            } catch (e: Exception) {
                _errorMessage.value = e.message ?: "Failed to delete transaction"
            } finally {
                _isSaving.value = false
            }
        }
    }

    fun deleteAsset() {
        val assetId = _assetId.value
        if (assetId.isBlank()) return
        viewModelScope.launch {
            _isSaving.value = true
            try {
                assetRepository.deleteAsset(assetId)
                _isDeleted.value = true
            } catch (e: Exception) {
                _errorMessage.value = e.message ?: "Failed to delete asset"
            } finally {
                _isSaving.value = false
            }
        }
    }

    fun recordSell(
        quantity: BigDecimal,
        unitPrice: BigDecimal,
        netAmount: BigDecimal,
        tradeDate: LocalDate,
        fees: BigDecimal = BigDecimal.ZERO,
        taxes: BigDecimal = BigDecimal.ZERO,
    ) {
        val currentAsset = uiState.value.asset ?: return
        if (quantity <= BigDecimal.ZERO) {
            _errorMessage.value = "Sell quantity must be greater than zero."
            return
        }
        if (quantity > currentAsset.totalQuantity) {
            _errorMessage.value = "Cannot sell more than available quantity (${currentAsset.totalQuantity})."
            return
        }

        viewModelScope.launch {
            _isSaving.value = true
            try {
                val sellTransaction = TransactionEntity(
                    accountId = "default_account",
                    assetId = currentAsset.id,
                    type = TransactionType.SELL,
                    tradeDate = tradeDate,
                    quantity = quantity,
                    unitPrice = unitPrice,
                    grossAmount = quantity.multiply(unitPrice),
                    fees = fees,
                    taxes = taxes,
                    netAmount = netAmount,
                    currency = currentAsset.currency,
                    fingerprint = "SELL_${currentAsset.id}_${tradeDate}_${System.currentTimeMillis()}",
                    status = TransactionStatus.CONFIRMED,
                    confidence = 1.0,
                )
                assetRepository.saveTransaction(sellTransaction)
            } catch (e: Exception) {
                _errorMessage.value = e.message ?: "Failed to record sell transaction"
            } finally {
                _isSaving.value = false
            }
        }
    }

    fun updateAssetClass(newClass: AssetClass) {
        val currentAsset = uiState.value.asset ?: return
        viewModelScope.launch {
            _isSaving.value = true
            try {
                assetRepository.updateAsset(currentAsset.copy(assetClass = newClass))
            } catch (e: Exception) {
                _errorMessage.value = e.message ?: "Failed to update asset class"
            } finally {
                _isSaving.value = false
            }
        }
    }

    fun updateAssetDetails(
        displayName: String,
        ticker: String?,
        isin: String?,
        assetClass: AssetClass,
    ) {
        val trimmed = displayName.trim()
        if (trimmed.isBlank()) {
            _errorMessage.value = "Asset name cannot be blank."
            return
        }
        val currentAsset = uiState.value.asset ?: return
        viewModelScope.launch {
            _isSaving.value = true
            try {
                val cleanTicker = ticker?.trim()?.uppercase()?.takeIf(String::isNotBlank)
                val cleanIsin = isin?.trim()?.uppercase()?.takeIf(String::isNotBlank)
                val idObj = runCatching { org.json.JSONObject(currentAsset.identifiersJson) }
                    .getOrDefault(org.json.JSONObject())
                if (cleanTicker != null) {
                    idObj.put("ticker", cleanTicker)
                } else {
                    idObj.remove("ticker")
                }
                if (cleanIsin != null) {
                    idObj.put("isin", cleanIsin)
                } else {
                    idObj.remove("isin")
                }

                val updatedAsset = currentAsset.copy(
                    displayName = trimmed,
                    assetClass = assetClass,
                    identifiersJson = idObj.toString(),
                    updatedAt = System.currentTimeMillis(),
                )
                assetRepository.updateAsset(updatedAsset)
            } catch (e: Exception) {
                _errorMessage.value = e.message ?: "Failed to update asset details"
            } finally {
                _isSaving.value = false
            }
        }
    }

    fun updateDisplayName(newDisplayName: String) {
        val trimmed = newDisplayName.trim()
        if (trimmed.isBlank()) {
            _errorMessage.value = "Asset name cannot be blank."
            return
        }
        val currentAsset = uiState.value.asset ?: return
        viewModelScope.launch {
            _isSaving.value = true
            try {
                assetRepository.updateAssetDisplayName(currentAsset.id, trimmed)
            } catch (e: Exception) {
                _errorMessage.value = e.message ?: "Failed to update asset name"
            } finally {
                _isSaving.value = false
            }
        }
    }

    fun updateAssetMetadata(identifiersJson: String, detailsJson: String) {
        val currentAsset = uiState.value.asset ?: return
        viewModelScope.launch {
            _isSaving.value = true
            try {
                assetRepository.updateAsset(
                    currentAsset.copy(
                        identifiersJson = identifiersJson,
                        detailsJson = detailsJson,
                    ),
                )
            } catch (e: Exception) {
                _errorMessage.value = e.message ?: "Failed to update asset metadata"
            } finally {
                _isSaving.value = false
            }
        }
    }

    fun clearErrorMessage() {
        _errorMessage.value = null
    }
}
