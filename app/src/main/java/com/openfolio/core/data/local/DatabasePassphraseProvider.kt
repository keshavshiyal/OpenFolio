package com.openfolio.core.data.local

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import dagger.hilt.android.qualifiers.ApplicationContext
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DatabasePassphraseProvider @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    @Synchronized
    fun getPassphrase(): ByteArray {
        val key = getOrCreateKey()
        val storedIv = preferences.getString(IV_KEY, null)
        val storedCiphertext = preferences.getString(CIPHERTEXT_KEY, null)
        if (storedIv != null && storedCiphertext != null) {
            return decrypt(
                key = key,
                iv = Base64.decode(storedIv, Base64.NO_WRAP),
                ciphertext = Base64.decode(storedCiphertext, Base64.NO_WRAP),
            )
        }
        check(storedIv == null && storedCiphertext == null) {
            "The encrypted database key is incomplete."
        }

        val passphrase = ByteArray(PASSPHRASE_LENGTH).also(SecureRandom()::nextBytes)
        val cipher = Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.ENCRYPT_MODE, key)
        }
        val persisted = preferences.edit()
            .putString(IV_KEY, Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
            .putString(
                CIPHERTEXT_KEY,
                Base64.encodeToString(cipher.doFinal(passphrase), Base64.NO_WRAP),
            )
            .commit()
        check(persisted) { "Unable to persist the encrypted database key." }
        return passphrase
    }

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
    }
}
