package com.openfolio.core.data.local

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.security.GeneralSecurityException
import java.security.KeyStore
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DatabasePassphraseProvider internal constructor(
    private val preferences: SharedPreferences,
    private val databaseFile: File,
    private val keyProvider: () -> SecretKey,
    private val random: SecureRandom = SecureRandom(),
) {
    @Inject
    constructor(@ApplicationContext context: Context) : this(
        preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE),
        databaseFile = context.getDatabasePath(OpenFolioDatabase.DATABASE_NAME),
        keyProvider = { getOrCreateKey() },
    )

    sealed interface DatabaseKeyState {
        data class Ready(val passphrase: ByteArray) : DatabaseKeyState
        data class Corrupted(val reason: String) : DatabaseKeyState
    }

    @Volatile
    private var lastState: DatabaseKeyState? = null

    @Synchronized
    fun resolveDatabaseKeyState(forceRefresh: Boolean = false): DatabaseKeyState {
        if (!forceRefresh) {
            lastState?.let { if (it is DatabaseKeyState.Ready) return it }
        }

        val state = computeKeyState()
        lastState = state
        return state
    }

    fun isDatabaseReady(): Boolean = resolveDatabaseKeyState() is DatabaseKeyState.Ready

    @Synchronized
    fun getPassphrase(): ByteArray {
        return when (val state = resolveDatabaseKeyState()) {
            is DatabaseKeyState.Ready -> state.passphrase.copyOf()
            is DatabaseKeyState.Corrupted -> error(state.reason)
        }
    }

    @Synchronized
    fun getPassphraseOrPlaceholder(): ByteArray {
        return when (val state = resolveDatabaseKeyState()) {
            is DatabaseKeyState.Ready -> state.passphrase.copyOf()
            is DatabaseKeyState.Corrupted -> ByteArray(PASSPHRASE_LENGTH) { 0x42 }
        }
    }

    @Synchronized
    fun resetCorruptedDatabaseAndKey(): Boolean {
        return runCatching {
            val wal = File("${databaseFile.absolutePath}-wal")
            val shm = File("${databaseFile.absolutePath}-shm")
            val journal = File("${databaseFile.absolutePath}-journal")
            listOf(databaseFile, wal, shm, journal).forEach { target ->
                if (target.exists()) target.delete()
            }
            preferences.edit().clear().commit()
            lastState = null
            resolveDatabaseKeyState(forceRefresh = true) is DatabaseKeyState.Ready
        }.getOrDefault(false)
    }

    private fun computeKeyState(): DatabaseKeyState {
        val hasExistingDb = databaseFile.exists() && databaseFile.length() > 0L
        val storedIv = preferences.getString(IV_KEY, null)
        val storedCiphertext = preferences.getString(CIPHERTEXT_KEY, null)

        val key = try {
            keyProvider()
        } catch (e: GeneralSecurityException) {
            return DatabaseKeyState.Corrupted(
                "Android Keystore failed to unlock the database master key (${e::class.java.simpleName}).",
            )
        } catch (e: Exception) {
            return DatabaseKeyState.Corrupted(
                "Unable to access secure device key storage (${e::class.java.simpleName}).",
            )
        }

        if (storedIv != null && storedCiphertext != null) {
            return try {
                val iv = Base64.getDecoder().decode(storedIv)
                val ciphertext = Base64.getDecoder().decode(storedCiphertext)
                DatabaseKeyState.Ready(decrypt(key = key, iv = iv, ciphertext = ciphertext))
            } catch (e: GeneralSecurityException) {
                if (!hasExistingDb) {
                    recoverEmptyInstallAndGenerateKey(key)
                } else {
                    DatabaseKeyState.Corrupted(
                        "Stored database passphrase could not be decrypted. Existing data was preserved safely.",
                    )
                }
            } catch (_: IllegalArgumentException) {
                if (!hasExistingDb) {
                    recoverEmptyInstallAndGenerateKey(key)
                } else {
                    DatabaseKeyState.Corrupted(
                        "Stored database key metadata is malformed. Existing data was preserved safely.",
                    )
                }
            }
        }

        if (storedIv != null || storedCiphertext != null) {
            return if (!hasExistingDb) {
                recoverEmptyInstallAndGenerateKey(key)
            } else {
                DatabaseKeyState.Corrupted("The encrypted database key is incomplete.")
            }
        }

        return generateAndPersistKey(key)
    }

    private fun recoverEmptyInstallAndGenerateKey(key: SecretKey): DatabaseKeyState {
        preferences.edit().clear().commit()
        return generateAndPersistKey(key)
    }

    private fun generateAndPersistKey(key: SecretKey): DatabaseKeyState {
        val passphrase = ByteArray(PASSPHRASE_LENGTH).also(random::nextBytes)
        return try {
            val cipher = Cipher.getInstance(TRANSFORMATION).apply {
                init(Cipher.ENCRYPT_MODE, key)
            }
            val ivEncoded = Base64.getEncoder().encodeToString(cipher.iv)
            val ctEncoded = Base64.getEncoder().encodeToString(cipher.doFinal(passphrase))
            val persisted = preferences.edit()
                .putString(IV_KEY, ivEncoded)
                .putString(CIPHERTEXT_KEY, ctEncoded)
                .commit()
            if (!persisted) {
                passphrase.fill(0)
                DatabaseKeyState.Corrupted("Unable to persist the encrypted database key.")
            } else {
                DatabaseKeyState.Ready(passphrase)
            }
        } catch (e: GeneralSecurityException) {
            passphrase.fill(0)
            DatabaseKeyState.Corrupted(
                "Unable to encrypt a new database passphrase (${e::class.java.simpleName}).",
            )
        }
    }

    private fun decrypt(key: SecretKey, iv: ByteArray, ciphertext: ByteArray): ByteArray =
        Cipher.getInstance(TRANSFORMATION).run {
            init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_LENGTH, iv))
            doFinal(ciphertext)
        }

    private companion object {
        const val ANDROID_KEY_STORE = "AndroidKeyStore"
        const val CIPHERTEXT_KEY = "ciphertext"
        const val GCM_TAG_LENGTH = 128
        const val IV_KEY = "iv"
        const val KEY_ALIAS = "openfolio_database_key"
        const val PASSPHRASE_LENGTH = 32
        const val PREFERENCES_NAME = "openfolio_database_key"
        const val TRANSFORMATION = "AES/GCM/NoPadding"

        @Synchronized
        private fun getOrCreateKey(): SecretKey {
            val keyStore = KeyStore.getInstance(ANDROID_KEY_STORE).apply { load(null) }
            (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }

            return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEY_STORE).run {
                init(
                    KeyGenParameterSpec.Builder(
                        KEY_ALIAS,
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
    }
}
