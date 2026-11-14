package com.openfolio.feature.home

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeViewModelTest {
    private val viewModel = HomeViewModel()

    @Test
    fun togglesValuesVisibility() {
        assertTrue(viewModel.uiState.value.showValues)

        viewModel.toggleValuesVisibility()
        assertFalse(viewModel.uiState.value.showValues)

        viewModel.toggleValuesVisibility()
        assertTrue(viewModel.uiState.value.showValues)
    }

    @Test
    fun selectsSupportedChartPeriod() {
        viewModel.selectPeriod("6M")

        assertEquals("6M", viewModel.uiState.value.selectedPeriod)
    }

    @Test
    fun providesTrajectoryPointsAndAllAllocationSlots() {
        val state = viewModel.uiState.value

        assertTrue(state.trajectoryPoints.size >= 2)
        assertEquals(
            listOf(
                AllocationColor.Slot1,
                AllocationColor.Slot2,
                AllocationColor.Slot3,
                AllocationColor.Slot4,
                AllocationColor.Slot5,
            ),
            state.allocations.map { it.color },
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsUnsupportedChartPeriod() {
        viewModel.selectPeriod("2Y")
    }
}
