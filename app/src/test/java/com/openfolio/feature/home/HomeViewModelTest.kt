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

    @Test(expected = IllegalArgumentException::class)
    fun rejectsUnsupportedChartPeriod() {
        viewModel.selectPeriod("2Y")
    }
}
