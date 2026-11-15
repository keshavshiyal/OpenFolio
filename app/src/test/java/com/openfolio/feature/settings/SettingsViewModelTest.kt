package com.openfolio.feature.settings

import com.openfolio.core.data.repository.SettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModelTest {
    private val testDispatcher = StandardTestDispatcher()
    private val repository = mock<SettingsRepository>()
    private val apiKeyFlow = MutableStateFlow("initial-key")

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        whenever(repository.geminiApiKeyFlow).thenReturn(apiKeyFlow)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun initialStateLoadsApiKeyFromRepository() = runTest {
        val viewModel = SettingsViewModel(repository)
        advanceUntilIdle()

        assertEquals("initial-key", viewModel.uiState.value.apiKey)
        assertFalse(viewModel.uiState.value.isKeyVisible)
    }

    @Test
    fun onApiKeyChangeUpdatesState() = runTest {
        val viewModel = SettingsViewModel(repository)
        advanceUntilIdle()

        viewModel.onApiKeyChange("new-key-123")
        assertEquals("new-key-123", viewModel.uiState.value.apiKey)
    }

    @Test
    fun toggleKeyVisibilityFlipsVisibility() = runTest {
        val viewModel = SettingsViewModel(repository)
        advanceUntilIdle()

        assertFalse(viewModel.uiState.value.isKeyVisible)
        viewModel.toggleKeyVisibility()
        assertTrue(viewModel.uiState.value.isKeyVisible)
        viewModel.toggleKeyVisibility()
        assertFalse(viewModel.uiState.value.isKeyVisible)
    }

    @Test
    fun saveApiKeyInvokesRepositoryAndEmitsSnackbarEvent() = runTest {
        val viewModel = SettingsViewModel(repository)
        advanceUntilIdle()

        viewModel.onApiKeyChange("saved-key")
        val events = mutableListOf<SettingsEvent>()
        val job = launch { viewModel.events.collect { events.add(it) } }

        viewModel.saveApiKey()
        advanceUntilIdle()

        verify(repository).setGeminiApiKey("saved-key")
        assertEquals(1, events.size)
        assertTrue(events[0] is SettingsEvent.ShowSnackbar)
        assertEquals("API key saved securely.", (events[0] as SettingsEvent.ShowSnackbar).message)
        job.cancel()
    }
}
