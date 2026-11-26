package com.openfolio.core.data.repository

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SettingsRepository @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val masterKeyAlias = MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC)

    private val prefs by lazy {
        try {
            EncryptedSharedPreferences.create(
                "openfolio_secure_settings",
                masterKeyAlias,
                context,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
            )
        } catch (_: Exception) {
            context.getSharedPreferences("openfolio_prefs_fallback", Context.MODE_PRIVATE)
        }
    }

    private val _geminiApiKey = MutableStateFlow("")
    val geminiApiKeyFlow: StateFlow<String> = _geminiApiKey.asStateFlow()

    private val _geminiModelName = MutableStateFlow(DEFAULT_GEMINI_MODEL_NAME)
    val geminiModelNameFlow: StateFlow<String> = _geminiModelName.asStateFlow()

    private val _showValues = MutableStateFlow(true)
    val showValuesFlow: StateFlow<Boolean> = _showValues.asStateFlow()

    init {
        _geminiApiKey.value = getGeminiApiKey()
        _geminiModelName.value = getGeminiModelName()
        _showValues.value = getShowValues()
    }

    fun getGeminiApiKey(): String {
        return try {
            prefs.getString(KEY_GEMINI_API_KEY, "") ?: ""
        } catch (_: Exception) {
            ""
        }
    }

    suspend fun setGeminiApiKey(apiKey: String) = withContext(Dispatchers.IO) {
        val trimmed = apiKey.trim()
        try {
            prefs.edit().putString(KEY_GEMINI_API_KEY, trimmed).apply()
        } catch (_: Exception) {}
        _geminiApiKey.value = trimmed
    }

    fun getGeminiModelName(): String {
        return try {
            prefs.getString(KEY_GEMINI_MODEL_NAME, DEFAULT_GEMINI_MODEL_NAME)
                ?.trim()
                ?.takeIf(String::isNotBlank)
                ?: DEFAULT_GEMINI_MODEL_NAME
        } catch (_: Exception) {
            DEFAULT_GEMINI_MODEL_NAME
        }
    }

    suspend fun setGeminiModelName(modelName: String) = withContext(Dispatchers.IO) {
        val resolved = modelName.trim().ifBlank { DEFAULT_GEMINI_MODEL_NAME }
        try {
            prefs.edit().putString(KEY_GEMINI_MODEL_NAME, resolved).apply()
        } catch (_: Exception) {}
        _geminiModelName.value = resolved
    }

    fun getShowValues(): Boolean {
        return try {
            prefs.getBoolean(KEY_SHOW_VALUES, true)
        } catch (_: Exception) {
            true
        }
    }

    suspend fun setShowValues(show: Boolean) = withContext(Dispatchers.IO) {
        try {
            prefs.edit().putBoolean(KEY_SHOW_VALUES, show).apply()
        } catch (_: Exception) {}
        _showValues.value = show
    }

    suspend fun toggleShowValues() = withContext(Dispatchers.IO) {
        setShowValues(!_showValues.value)
    }

    companion object {
        const val KEY_GEMINI_API_KEY = "gemini_api_key"
        const val KEY_GEMINI_MODEL_NAME = "gemini_model_name"
        const val DEFAULT_GEMINI_MODEL_NAME = "gemini-2.5-flash"
        const val KEY_SHOW_VALUES = "show_values"
        val SUPPORTED_GEMINI_MODELS = listOf(
            "gemini-2.5-flash",
            "gemini-2.5-pro",
            "gemini-2.0-flash",
        )
    }
}
