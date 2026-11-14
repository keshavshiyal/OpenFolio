package com.openfolio.feature.home

import java.math.BigDecimal

data class HomeUiState(
    val showValues: Boolean = true,
    val netWorth: BigDecimal = BigDecimal("2458300"),
    val asOf: String = "as of 14 Nov, 09:12",
    val changePercent: String = "2.1%",
    val assets: BigDecimal = BigDecimal("3120000"),
    val liabilities: BigDecimal = BigDecimal("661700"),
    val selectedPeriod: String = "1M",
    val allocations: List<AssetAllocation> = listOf(
        AssetAllocation("Equity", 50, AllocationColor.Brand),
        AssetAllocation("Mutual funds", 25, AllocationColor.Gain),
        AssetAllocation("Fixed deposits", 15, AllocationColor.Muted),
        AssetAllocation("Other", 10, AllocationColor.Loss),
    ),
    val recentActivity: List<RecentActivity> = listOf(
        RecentActivity(
            title = "Bought RELIANCE",
            subtitle = "Today · 10:42",
            amount = BigDecimal("-12500"),
            icon = ActivityIcon.Buy,
        ),
        RecentActivity(
            title = "SIP HDFC Flexi",
            subtitle = "Yesterday · 09:00",
            amount = BigDecimal("-5000"),
            icon = ActivityIcon.Investment,
        ),
    ),
)

data class AssetAllocation(
    val label: String,
    val percentage: Int,
    val color: AllocationColor,
)

enum class AllocationColor {
    Brand,
    Gain,
    Muted,
    Loss,
}

data class RecentActivity(
    val title: String,
    val subtitle: String,
    val amount: BigDecimal,
    val icon: ActivityIcon,
)

enum class ActivityIcon {
    Buy,
    Investment,
}
