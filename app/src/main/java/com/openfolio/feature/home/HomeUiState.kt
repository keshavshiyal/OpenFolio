package com.openfolio.feature.home

import com.openfolio.core.domain.model.DashboardData
import java.math.BigDecimal

sealed interface HomeUiState {
    val showValues: Boolean
        get() = when (this) {
            is Success -> this.showValues
            else -> true
        }

    data object Loading : HomeUiState

    data object Empty : HomeUiState

    data class Success(
        val data: DashboardData,
        override val showValues: Boolean = true,
        val selectedPeriod: String = "1M",
        val trajectoryPoints: List<Float> = DEFAULT_TRAJECTORY_POINTS,
        val allocations: List<AssetAllocation> = DEFAULT_ALLOCATIONS,
        val asOf: String = "as of today",
        val changePercent: String = "2.1%",
    ) : HomeUiState {
        companion object {
            val DEFAULT_TRAJECTORY_POINTS: List<Float> = listOf(
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
            )
            val DEFAULT_ALLOCATIONS: List<AssetAllocation> = listOf(
                AssetAllocation("Equity", 35, AllocationColor.Slot1),
                AssetAllocation("Mutual funds", 25, AllocationColor.Slot2),
                AssetAllocation("Fixed deposits", 18, AllocationColor.Slot3),
                AssetAllocation("Gold", 12, AllocationColor.Slot4),
                AssetAllocation("Cash", 10, AllocationColor.Slot5),
            )
        }
    }
}

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
