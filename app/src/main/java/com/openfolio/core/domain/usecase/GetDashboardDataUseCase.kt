package com.openfolio.core.domain.usecase

import com.openfolio.core.data.local.dao.TransactionDao
import com.openfolio.core.domain.model.DashboardData
import java.math.BigDecimal
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine

class GetDashboardDataUseCase @Inject constructor(
    private val transactionDao: TransactionDao,
) {
    operator fun invoke(): Flow<DashboardData> {
        return combine(
            transactionDao.getTotalAssetsFlow(),
            transactionDao.getTotalLiabilitiesFlow(),
            transactionDao.getRecentTransactions(RECENT_TRANSACTIONS_LIMIT),
        ) { assetsDouble, liabilitiesDouble, recentTransactions ->
            val totalAssets = if (assetsDouble == null || assetsDouble == 0.0) {
                BigDecimal.ZERO
            } else {
                BigDecimal.valueOf(assetsDouble)
            }
            val totalLiabilities = if (liabilitiesDouble == 0.0) {
                BigDecimal.ZERO
            } else {
                BigDecimal.valueOf(liabilitiesDouble)
            }
            val netWorth = totalAssets - totalLiabilities
            DashboardData(
                netWorth = netWorth,
                totalAssets = totalAssets,
                totalLiabilities = totalLiabilities,
                recentTransactions = recentTransactions,
            )
        }
    }

    private companion object {
        const val RECENT_TRANSACTIONS_LIMIT = 5
    }
}
