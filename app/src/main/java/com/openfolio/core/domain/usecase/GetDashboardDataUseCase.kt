package com.openfolio.core.domain.usecase

import com.openfolio.core.data.local.dao.AssetDao
import com.openfolio.core.data.local.dao.TransactionDao
import com.openfolio.core.data.local.entity.AssetClass
import com.openfolio.core.data.local.entity.AssetEntity
import com.openfolio.core.data.local.entity.TransactionEntity
import com.openfolio.core.domain.model.DashboardAllocation
import com.openfolio.core.domain.model.DashboardData
import java.math.BigDecimal
import javax.inject.Inject
import kotlin.math.roundToInt
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf

class GetDashboardDataUseCase @Inject constructor(
    private val transactionDao: TransactionDao,
    private val assetDao: AssetDao? = null,
) {
    operator fun invoke(): Flow<DashboardData> {
        val assetsFlow = transactionDao.getTotalAssetsFlow()
        val liabilitiesFlow = transactionDao.getTotalLiabilitiesFlow()
        val recentTxnsFlow = transactionDao.getRecentTransactions(RECENT_TRANSACTIONS_LIMIT)
        val allTxnsFlow = transactionDao.getAll()
        val assetEntitiesFlow = assetDao?.getAll() ?: flowOf(emptyList())

        return combine(
            assetsFlow,
            liabilitiesFlow,
            recentTxnsFlow,
            allTxnsFlow,
            assetEntitiesFlow,
        ) { totalAssetsDouble, liabilitiesDouble, recentTransactions, allTransactions, assetEntities ->
            val totalAssets = if (totalAssetsDouble == null || totalAssetsDouble == 0.0) {
                BigDecimal.ZERO
            } else {
                BigDecimal.valueOf(totalAssetsDouble)
            }
            val totalLiabilities = if (liabilitiesDouble == 0.0) {
                BigDecimal.ZERO
            } else {
                BigDecimal.valueOf(liabilitiesDouble)
            }
            val netWorth = totalAssets - totalLiabilities

            val assetsById = assetEntities.associateBy { it.id }

            val mappedRecent = recentTransactions.map { txn ->
                val displayName = assetsById[txn.assetId]?.displayName
                    ?: formatAssetIdNicely(txn.assetId)
                txn.copy(assetId = displayName)
            }

            val allocations = calculateAllocations(allTransactions, assetsById)

            DashboardData(
                netWorth = netWorth,
                totalAssets = totalAssets,
                totalLiabilities = totalLiabilities,
                recentTransactions = mappedRecent,
                allocations = allocations,
            )
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

    private fun calculateAllocations(
        transactions: List<TransactionEntity>,
        assetsById: Map<String, AssetEntity>,
    ): List<DashboardAllocation> {
        val positiveTxns = transactions.filter {
            val typeUpper = it.type.name.uppercase()
            typeUpper in setOf("BUY", "DIVIDEND", "INTEREST")
        }
        if (positiveTxns.isEmpty()) return emptyList()

        val amountsByClass = mutableMapOf<AssetClass, BigDecimal>()
        for (txn in positiveTxns) {
            val asset = assetsById[txn.assetId]
                ?: assetsById.values.firstOrNull { it.displayName.equals(txn.assetId, ignoreCase = true) }
            val assetClass = asset?.assetClass ?: AssetClass.STOCK
            val amount = txn.grossAmount
            amountsByClass[assetClass] = (amountsByClass[assetClass] ?: BigDecimal.ZERO) + amount
        }

        val total = amountsByClass.values.fold(BigDecimal.ZERO) { acc, cur -> acc + cur }
        if (total <= BigDecimal.ZERO) return emptyList()

        val sorted = amountsByClass.entries.sortedByDescending { it.value }

        return sorted.map { entry ->
            val pct = (entry.value.toDouble() / total.toDouble() * 100.0).roundToInt()
            DashboardAllocation(
                label = entry.key.toDisplayName(),
                percentage = pct,
            )
        }.filter { it.percentage > 0 }
    }

    private fun AssetClass.toDisplayName(): String = when (this) {
        AssetClass.STOCK -> "Equity"
        AssetClass.MUTUAL_FUND -> "Mutual funds"
        AssetClass.BOND -> "Bonds"
        AssetClass.SGB -> "SGB"
        AssetClass.FD -> "Fixed deposits"
        AssetClass.HOUSE, AssetClass.LAND -> "Real estate"
        AssetClass.VEHICLE -> "Vehicles"
        AssetClass.CRYPTO -> "Crypto"
        AssetClass.GOLD -> "Gold"
        AssetClass.CASH -> "Cash"
        AssetClass.OTHER -> "Other"
    }

    private companion object {
        const val RECENT_TRANSACTIONS_LIMIT = 5
    }
}
