package com.openfolio.core.data.repository

import com.openfolio.core.data.local.DatabasePassphraseProviderTest.InMemorySharedPreferences
import java.security.GeneralSecurityException
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsRepositoryTest {

    private fun testKey(seed: Byte = 0x33): SecretKey =
        SecretKeySpec(ByteArray(32) { i -> (seed.toInt() + i).toByte() }, "AES")

    @Test
    fun apiKeyIsEncryptedViaKeystoreWithoutPlaintextPersistence() = runBlocking {
        val securePrefs = InMemorySharedPreferences()
        val uiPrefs = InMemorySharedPreferences()
        val repo = SettingsRepository(
            securePrefs = securePrefs,
            uiPrefs = uiPrefs,
            legacyFallbackPrefs = null,
            keyProvider = { testKey() },
        )

        val secretApiKey = "AIzaSySecretKey1234567890"
        repo.setGeminiApiKey(secretApiKey)

        assertEquals(secretApiKey, repo.getGeminiApiKey())
        assertEquals(secretApiKey, repo.geminiApiKeyFlow.value)
        assertNull(repo.apiKeyStorageError.value)
        assertFalse(securePrefs.contains(SettingsRepository.KEY_GEMINI_API_KEY))
        assertTrue(securePrefs.all.values.none { it.toString().contains(secretApiKey) })
    }

    @Test
    fun encryptionFailureDoesNotFallBackToPlaintextPrefs() = runBlocking {
        val securePrefs = InMemorySharedPreferences()
        val uiPrefs = InMemorySharedPreferences()
        val legacyFallback = InMemorySharedPreferences()
        val repo = SettingsRepository(
            securePrefs = securePrefs,
            uiPrefs = uiPrefs,
            legacyFallbackPrefs = legacyFallback,
            keyProvider = { throw GeneralSecurityException("Keystore unavailable") },
        )

        var threw = false
        try {
            repo.setGeminiApiKey("AIzaSyShouldNeverBePlaintext")
        } catch (_: IllegalStateException) {
            threw = true
        }

        assertTrue(threw)
        assertTrue(legacyFallback.all.isEmpty())
        assertTrue(securePrefs.all.isEmpty())
        assertTrue(uiPrefs.all.isEmpty())
        assertNotNull(repo.apiKeyStorageError.value)
    }

    @Test
    fun corruptedCiphertextSurfacesErrorStateAndCanBeClearedSafely() = runBlocking {
        val securePrefs = InMemorySharedPreferences()
        val uiPrefs = InMemorySharedPreferences()
        val repo1 = SettingsRepository(
            securePrefs = securePrefs,
            uiPrefs = uiPrefs,
            legacyFallbackPrefs = null,
            keyProvider = { testKey(0x10) },
        )
        repo1.setGeminiApiKey("AIzaSyOriginalKey")

        // New instance using a different Keystore key simulates key invalidation/corruption.
        val repo2 = SettingsRepository(
            securePrefs = securePrefs,
            uiPrefs = uiPrefs,
            legacyFallbackPrefs = null,
            keyProvider = { testKey(0x69) },
        )
        assertEquals("", repo2.getGeminiApiKey())
        assertNotNull(repo2.apiKeyStorageError.value)

        repo2.clearCorruptedApiKey()
        assertNull(repo2.apiKeyStorageError.value)
        assertEquals("", repo2.getGeminiApiKey())
    }

    @Test
    fun legacyPlaintextFallbackIsMigratedAndDeleted() = runBlocking {
        val securePrefs = InMemorySharedPreferences()
        val uiPrefs = InMemorySharedPreferences()
        val legacyFallback = InMemorySharedPreferences()
        legacyFallback.edit()
            .putString(SettingsRepository.KEY_GEMINI_API_KEY, "AIzaSyMigratedLegacyKey")
            .putString(SettingsRepository.KEY_GEMINI_MODEL_NAME, "gemini-3.8-flash")
            .putBoolean(SettingsRepository.KEY_SHOW_VALUES, false)
            .commit()
        var deleteCalled = false

        val repo = SettingsRepository(
            securePrefs = securePrefs,
            uiPrefs = uiPrefs,
            legacyFallbackPrefs = legacyFallback,
            keyProvider = { testKey() },
            deleteLegacyFallbackFn = { deleteCalled = true },
        )

        assertEquals("AIzaSyMigratedLegacyKey", repo.getGeminiApiKey())
        assertEquals("gemini-3.8-flash", repo.getGeminiModelName())
        assertFalse(repo.getShowValues())
        assertTrue(deleteCalled)
        assertTrue(legacyFallback.all.isEmpty())
    }
}
