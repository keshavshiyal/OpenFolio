package com.openfolio.core.domain.model

import com.openfolio.core.data.local.entity.TransactionEntity
import java.math.BigDecimal

data class DashboardData(
    val netWorth: BigDecimal,
    val totalAssets: BigDecimal,
    val totalLiabilities: BigDecimal,
    val recentTransactions: List<TransactionEntity>,
    val allocations: List<DashboardAllocation> = emptyList(),
)

data class DashboardAllocation(
    val label: String,
    val percentage: Int,
)
