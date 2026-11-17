package com.openfolio.core.domain.usecase

import com.openfolio.core.data.local.dao.AssetDao
import com.openfolio.core.data.local.dao.TransactionDao
import com.openfolio.core.data.local.entity.AssetClass
import com.openfolio.core.domain.model.DashboardAllocation
import com.openfolio.core.domain.model.DashboardData
import java.math.BigDecimal
import javax.inject.Inject
import kotlin.math.roundToInt
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

class GetDashboardDataUseCase @Inject constructor(
    private val transactionDao: TransactionDao,
    private val assetDao: AssetDao? = null,
) {
    operator fun invoke(): Flow<DashboardData> {
        val assets = (assetDao?.getTotalPortfolioValueFlow() ?: transactionDao.getTotalAssetsFlow().map {
            if (it == null || it == 0.0) BigDecimal.ZERO else BigDecimal(it)
        }).map { it ?: BigDecimal.ZERO }
        val liabilities = transactionDao.getTotalLiabilitiesFlow().map {
            if (it == null || it == 0.0) BigDecimal.ZERO else BigDecimal(it)
        }
        val totalCountFlow = transactionDao.getTotalTransactionCount()
        val recentTxnsFlow = transactionDao.getRecentTransactions(5)
        val allocationsFlow = transactionDao.getAssetClassAllocations()
        val deltasFlow = transactionDao.getDailyNetWorthDeltas()
        val assetEntitiesFlow = assetDao?.getAll() ?: flowOf(emptyList())

        return combine(
            combine(assets, liabilities, totalCountFlow) { a, l, c -> Triple(a, l, c) },
            recentTxnsFlow,
            allocationsFlow,
            deltasFlow,
            assetEntitiesFlow,
        ) { (totalAssets, totalLiabilities, totalCount), recentTransactions, allocationRows, dailyDeltas, assetEntities ->
            val netWorth = totalAssets - totalLiabilities
            val assetsById = assetEntities.associateBy { it.id }

            val mappedRecent = recentTransactions.map { txn ->
                val displayName = assetsById[txn.assetId]?.displayName
                    ?: formatAssetIdNicely(txn.assetId)
                txn.copy(assetId = displayName)
            }

            val totalAllocAmount = allocationRows.sumOf { it.totalAmount }
            val allocations = if (totalAllocAmount > 0.0) {
                allocationRows.map { row ->
                    val pct = (row.totalAmount / totalAllocAmount * 100.0).roundToInt()
                    DashboardAllocation(
                        label = row.assetClass.toDisplayName(),
                        percentage = pct,
                    )
                }.filter { it.percentage > 0 }
            } else {
                emptyList()
            }

            DashboardData(
                netWorth = netWorth,
                totalAssets = totalAssets,
                totalLiabilities = totalLiabilities,
                recentTransactions = mappedRecent,
                allocations = allocations,
                totalTransactions = totalCount,
                dailyDeltas = dailyDeltas,
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
}
