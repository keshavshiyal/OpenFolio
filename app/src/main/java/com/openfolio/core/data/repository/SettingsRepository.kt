package com.openfolio.core.data.repository

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.openfolio.core.di.ApplicationScope
import dagger.hilt.android.qualifiers.ApplicationContext
import java.nio.charset.StandardCharsets
import java.security.GeneralSecurityException
import java.security.KeyStore
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Singleton
class SettingsRepository internal constructor(
    private val securePrefs: SharedPreferences,
    private val uiPrefs: SharedPreferences,
    private val legacyFallbackPrefs: SharedPreferences?,
    private val keyProvider: () -> SecretKey,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    applicationScope: CoroutineScope? = null,
    private val deleteLegacyFallbackFn: () -> Unit = {},
) {
    @Inject
    constructor(
        @ApplicationContext context: Context,
        @ApplicationScope applicationScope: CoroutineScope,
    ) : this(
        securePrefs = context.getSharedPreferences(SECURE_PREFS_NAME, Context.MODE_PRIVATE),
        uiPrefs = context.getSharedPreferences(UI_PREFS_NAME, Context.MODE_PRIVATE),
        legacyFallbackPrefs = context.getSharedPreferences(LEGACY_FALLBACK_PREFS_NAME, Context.MODE_PRIVATE),
        keyProvider = { getOrCreateSettingsKey() },
        ioDispatcher = Dispatchers.IO,
        applicationScope = applicationScope,
        deleteLegacyFallbackFn = {
            try {
                context.deleteSharedPreferences(LEGACY_FALLBACK_PREFS_NAME)
            } catch (_: Exception) {
                // Ignore cleanup failure on legacy preferences file.
            }
        },
    )

    private val _geminiApiKey = MutableStateFlow("")
    val geminiApiKeyFlow: StateFlow<String> = _geminiApiKey.asStateFlow()

    private val _geminiModelName = MutableStateFlow(DEFAULT_GEMINI_MODEL_NAME)
    val geminiModelNameFlow: StateFlow<String> = _geminiModelName.asStateFlow()

    private val _showValues = MutableStateFlow(true)
    val showValuesFlow: StateFlow<Boolean> = _showValues.asStateFlow()

    private val _apiKeyStorageError = MutableStateFlow<String?>(null)
    val apiKeyStorageError: StateFlow<String?> = _apiKeyStorageError.asStateFlow()

    @Volatile
    private var initializedFromStorage = false

    private val lock = Any()

    init {
        if (applicationScope != null) {
            applicationScope.launch(ioDispatcher) {
                ensureInitialized()
            }
        } else {
            ensureInitialized()
        }
    }

    private fun ensureInitialized() {
        if (initializedFromStorage) return
        synchronized(lock) {
            if (initializedFromStorage) return
            migrateLegacyFallbackIfPresent()
            _geminiApiKey.value = readEncryptedApiKeyLocked()
            _geminiModelName.value = readModelNameLocked()
            _showValues.value = readShowValuesLocked()
            initializedFromStorage = true
        }
    }

    fun getGeminiApiKey(): String {
        ensureInitialized()
        return synchronized(lock) {
            val loaded = readEncryptedApiKeyLocked()
            _geminiApiKey.value = loaded
            loaded
        }
    }

    suspend fun setGeminiApiKey(apiKey: String) = withContext(ioDispatcher) {
        ensureInitialized()
        val trimmed = apiKey.trim()
        synchronized(lock) {
            if (trimmed.isEmpty()) {
                val cleared = securePrefs.edit()
                    .remove(KEY_GEMINI_API_KEY_IV)
                    .remove(KEY_GEMINI_API_KEY_CIPHERTEXT)
                    .commit()
                check(cleared) { "Unable to clear stored API key." }
                _apiKeyStorageError.value = null
                _geminiApiKey.value = ""
                return@synchronized
            }

            try {
                val key = keyProvider()
                val cipher = Cipher.getInstance(TRANSFORMATION).apply {
                    init(Cipher.ENCRYPT_MODE, key)
                }
                val ivB64 = Base64.getEncoder().encodeToString(cipher.iv)
                val ctB64 = Base64.getEncoder().encodeToString(
                    cipher.doFinal(trimmed.toByteArray(StandardCharsets.UTF_8)),
                )
                val committed = securePrefs.edit()
                    .putString(KEY_GEMINI_API_KEY_IV, ivB64)
                    .putString(KEY_GEMINI_API_KEY_CIPHERTEXT, ctB64)
                    .commit()
                check(committed) { "Failed to persist encrypted API key." }
                _apiKeyStorageError.value = null
                _geminiApiKey.value = trimmed
            } catch (e: GeneralSecurityException) {
                _apiKeyStorageError.value =
                    "Secure hardware storage rejected encryption. Please unlock your screen lock or reset the stored API key."
                throw IllegalStateException("Unable to encrypt API key via Android Keystore.", e)
            }
        }
    }

    suspend fun clearCorruptedApiKey() = withContext(ioDispatcher) {
        synchronized(lock) {
            securePrefs.edit()
                .remove(KEY_GEMINI_API_KEY_IV)
                .remove(KEY_GEMINI_API_KEY_CIPHERTEXT)
                .commit()
            _apiKeyStorageError.value = null
            _geminiApiKey.value = ""
        }
    }

    fun getGeminiModelName(): String {
        ensureInitialized()
        return synchronized(lock) {
            val resolved = readModelNameLocked()
            _geminiModelName.value = resolved
            resolved
        }
    }

    suspend fun setGeminiModelName(modelName: String) = withContext(ioDispatcher) {
        ensureInitialized()
        val resolved = modelName.trim().ifBlank { DEFAULT_GEMINI_MODEL_NAME }
        synchronized(lock) {
            uiPrefs.edit().putString(KEY_GEMINI_MODEL_NAME, resolved).commit()
            _geminiModelName.value = resolved
        }
    }

    fun getShowValues(): Boolean {
        ensureInitialized()
        return synchronized(lock) {
            val show = readShowValuesLocked()
            _showValues.value = show
            show
        }
    }

    suspend fun setShowValues(show: Boolean) = withContext(ioDispatcher) {
        ensureInitialized()
        synchronized(lock) {
            uiPrefs.edit().putBoolean(KEY_SHOW_VALUES, show).commit()
            _showValues.value = show
        }
    }

    suspend fun toggleShowValues() = withContext(ioDispatcher) {
        ensureInitialized()
        val next = synchronized(lock) { !_showValues.value }
        setShowValues(next)
    }

    private fun readEncryptedApiKeyLocked(): String {
        val ivStr = securePrefs.getString(KEY_GEMINI_API_KEY_IV, null)
        val ctStr = securePrefs.getString(KEY_GEMINI_API_KEY_CIPHERTEXT, null)
        if (ivStr == null && ctStr == null) {
            _apiKeyStorageError.value = null
            return ""
        }
        if (ivStr == null || ctStr == null) {
            _apiKeyStorageError.value =
                "Encrypted Gemini API key metadata is incomplete. Reset and re-enter your API key."
            return ""
        }
        return try {
            val iv = Base64.getDecoder().decode(ivStr)
            val ct = Base64.getDecoder().decode(ctStr)
            val key = keyProvider()
            val plain = Cipher.getInstance(TRANSFORMATION).run {
                init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
                doFinal(ct)
            }
            _apiKeyStorageError.value = null
            String(plain, StandardCharsets.UTF_8)
        } catch (_: GeneralSecurityException) {
            _apiKeyStorageError.value =
                "Unable to decrypt stored Gemini API key (Keystore changed or corrupted). Reset and re-enter your API key."
            ""
        } catch (_: IllegalArgumentException) {
            _apiKeyStorageError.value =
                "Stored Gemini API key payload is malformed. Reset and re-enter your API key."
            ""
        }
    }

    private fun readModelNameLocked(): String {
        val saved = uiPrefs.getString(KEY_GEMINI_MODEL_NAME, null)
            ?.trim()
            ?.takeIf(String::isNotBlank)
        val resolved = resolveMigratedModelName(saved)
        if (saved != null && resolved != saved) {
            runCatching { uiPrefs.edit().putString(KEY_GEMINI_MODEL_NAME, resolved).apply() }
        }
        return resolved
    }

    private fun readShowValuesLocked(): Boolean = uiPrefs.getBoolean(KEY_SHOW_VALUES, true)

    private fun migrateLegacyFallbackIfPresent() {
        val fallback = legacyFallbackPrefs ?: return
        val hasEncryptedAlready = securePrefs.contains(KEY_GEMINI_API_KEY_IV) &&
            securePrefs.contains(KEY_GEMINI_API_KEY_CIPHERTEXT)

        val legacyKey = fallback.getString(KEY_GEMINI_API_KEY, null)?.trim()
        val legacyModel = fallback.getString(KEY_GEMINI_MODEL_NAME, null)?.trim()
        val legacyHasShowValues = fallback.contains(KEY_SHOW_VALUES)

        if (!legacyModel.isNullOrBlank() && !uiPrefs.contains(KEY_GEMINI_MODEL_NAME)) {
            uiPrefs.edit().putString(KEY_GEMINI_MODEL_NAME, resolveMigratedModelName(legacyModel)).commit()
        }
        if (legacyHasShowValues && !uiPrefs.contains(KEY_SHOW_VALUES)) {
            uiPrefs.edit().putBoolean(KEY_SHOW_VALUES, fallback.getBoolean(KEY_SHOW_VALUES, true)).commit()
        }

        if (!legacyKey.isNullOrBlank() && !hasEncryptedAlready) {
            val migrated = runCatching {
                val key = keyProvider()
                val cipher = Cipher.getInstance(TRANSFORMATION).apply {
                    init(Cipher.ENCRYPT_MODE, key)
                }
                securePrefs.edit()
                    .putString(KEY_GEMINI_API_KEY_IV, Base64.getEncoder().encodeToString(cipher.iv))
                    .putString(
                        KEY_GEMINI_API_KEY_CIPHERTEXT,
                        Base64.getEncoder().encodeToString(
                            cipher.doFinal(legacyKey.toByteArray(StandardCharsets.UTF_8)),
                        ),
                    )
                    .commit()
            }.getOrDefault(false)
            if (migrated) {
                fallback.edit().clear().commit()
                deleteLegacyFallbackFn()
            }
        } else if (fallback.all.isNotEmpty()) {
            fallback.edit().clear().commit()
            deleteLegacyFallbackFn()
        }
    }

    companion object {
        private const val SECURE_PREFS_NAME = "openfolio_keystore_settings"
        private const val UI_PREFS_NAME = "openfolio_ui_prefs"
        private const val LEGACY_FALLBACK_PREFS_NAME = "openfolio_prefs_fallback"
        private const val ANDROID_KEY_STORE = "AndroidKeyStore"
        private const val SETTINGS_KEY_ALIAS = "openfolio_settings_key"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val GCM_TAG_BITS = 128

        const val KEY_GEMINI_API_KEY = "gemini_api_key"
        private const val KEY_GEMINI_API_KEY_IV = "gemini_api_key_iv_v1"
        private const val KEY_GEMINI_API_KEY_CIPHERTEXT = "gemini_api_key_ct_v1"
        const val KEY_GEMINI_MODEL_NAME = "gemini_model_name"
        const val DEFAULT_GEMINI_MODEL_NAME = "gemini-3.8-flash"
        const val KEY_SHOW_VALUES = "show_values"
        val SUPPORTED_GEMINI_MODELS = listOf(
            "gemini-3.8-flash",
            "gemini-4-hydrogen",
            "gemini-3.6-flash",
            "gemini-3.5-flash-lite",
        )
        private val DEPRECATED_GEMINI_MODELS = setOf(
            "gemini-1.5-flash",
            "gemini-1.5-pro",
            "gemini-2.0-flash",
            "gemini-2.0-flash-lite",
            "gemini-2.5-flash",
            "gemini-2.5-pro",
            "gemini-3.5-flash",
            "gemini-3.7-flash",
        )

        @Synchronized
        private fun getOrCreateSettingsKey(): SecretKey {
            val keyStore = KeyStore.getInstance(ANDROID_KEY_STORE).apply { load(null) }
            (keyStore.getKey(SETTINGS_KEY_ALIAS, null) as? SecretKey)?.let { return it }
            return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEY_STORE).run {
                init(
                    KeyGenParameterSpec.Builder(
                        SETTINGS_KEY_ALIAS,
                        KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                    )
                        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                        .setRandomizedEncryptionRequired(true)
                        .build(),
                )
                generateKey()
            }
        }

        internal fun resolveMigratedModelName(rawModelName: String?): String {
            val trimmed = rawModelName?.trim()?.takeIf(String::isNotBlank) ?: return DEFAULT_GEMINI_MODEL_NAME
            return if (trimmed.lowercase() in DEPRECATED_GEMINI_MODELS) {
                DEFAULT_GEMINI_MODEL_NAME
            } else {
                trimmed
            }
        }
    }
}
