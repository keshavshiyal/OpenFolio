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
    val trajectoryPoints: List<Float> = listOf(
        0.34f,
        0.42f,
        0.38f,
        0.55f,
        0.49f,
        0.63f,
        0.58f,
        0.76f,
        0.69f,
        0.84f,
    ),
    val allocations: List<AssetAllocation> = listOf(
        AssetAllocation("Equity", 35, AllocationColor.Slot1),
        AssetAllocation("Mutual funds", 25, AllocationColor.Slot2),
        AssetAllocation("Fixed deposits", 18, AllocationColor.Slot3),
        AssetAllocation("Gold", 12, AllocationColor.Slot4),
        AssetAllocation("Cash", 10, AllocationColor.Slot5),
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
    Slot1,
    Slot2,
    Slot3,
    Slot4,
    Slot5,
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
