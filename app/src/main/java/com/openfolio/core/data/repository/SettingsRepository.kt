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
        EncryptedSharedPreferences.create(
            "openfolio_secure_settings",
            masterKeyAlias,
            context,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    private val _geminiApiKey = MutableStateFlow("")
    val geminiApiKeyFlow: StateFlow<String> = _geminiApiKey.asStateFlow()

    init {
        _geminiApiKey.value = getGeminiApiKey()
    }

    fun getGeminiApiKey(): String {
        return prefs.getString(KEY_GEMINI_API_KEY, "") ?: ""
    }

    suspend fun setGeminiApiKey(apiKey: String) = withContext(Dispatchers.IO) {
        val trimmed = apiKey.trim()
        prefs.edit().putString(KEY_GEMINI_API_KEY, trimmed).apply()
        _geminiApiKey.value = trimmed
    }

    companion object {
        const val KEY_GEMINI_API_KEY = "gemini_api_key"
    }
}
