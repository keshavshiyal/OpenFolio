package com.openfolio.core.data.local

import android.content.SharedPreferences
import java.io.File
import java.security.GeneralSecurityException
import java.util.Base64
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DatabasePassphraseProviderTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private fun testKey(seed: Byte = 0x11): SecretKey =
        SecretKeySpec(ByteArray(32) { i -> (seed.toInt() + i).toByte() }, "AES")

    @Test
    fun generatesAndRoundTripsEncryptedPassphrase() {
        val prefs = InMemorySharedPreferences()
        val dbFile = File(tempFolder.root, "openfolio.db")

        val provider1 = DatabasePassphraseProvider(prefs, dbFile, { testKey() })
        val first = provider1.getPassphrase()
        assertEquals(32, first.size)
        assertNotNull(prefs.getString("iv", null))
        assertNotNull(prefs.getString("ciphertext", null))

        val provider2 = DatabasePassphraseProvider(prefs, dbFile, { testKey() })
        assertArrayEquals(first, provider2.getPassphrase())
    }

    @Test
    fun missingDbAutoRecoversPartialOrUnreadableKeyWithoutCrash() {
        val prefs = InMemorySharedPreferences()
        prefs.edit().putString("iv", Base64.getEncoder().encodeToString(ByteArray(12)))
            // missing ciphertext simulates partial write on fresh/uninitialized install
            .commit()
        val dbFile = File(tempFolder.root, "openfolio.db")
        assertFalse(dbFile.exists())

        val provider = DatabasePassphraseProvider(prefs, dbFile, { testKey() })
        val state = provider.resolveDatabaseKeyState()
        assertTrue(state is DatabasePassphraseProvider.DatabaseKeyState.Ready)
        assertNotNull(prefs.getString("ciphertext", null))
    }

    @Test
    fun existingDbWithCorruptedKeyEntersCorruptedStateAndPreservesDatabaseFile() {
        val prefs = InMemorySharedPreferences()
        val dbFile = File(tempFolder.root, "openfolio.db").apply {
            writeBytes("SQLITE_ENCRYPTED_PAYLOAD".toByteArray())
        }
        val initial = DatabasePassphraseProvider(prefs, dbFile, { testKey(0x11) })
        assertTrue(initial.isDatabaseReady())

        // Simulate Android Keystore mismatch / corrupted wrapped key while DB already contains data
        val broken = DatabasePassphraseProvider(prefs, dbFile, { testKey(0x55) })
        val state = broken.resolveDatabaseKeyState()

        assertTrue(state is DatabasePassphraseProvider.DatabaseKeyState.Corrupted)
        assertFalse(broken.isDatabaseReady())
        assertTrue("Existing DB must NEVER be auto-deleted on key error", dbFile.exists())
        assertEquals("SQLITE_ENCRYPTED_PAYLOAD", dbFile.readText())
    }

    @Test
    fun keystoreFailureReturnsCorruptedStateInsteadOfThrowingOnBoot() {
        val prefs = InMemorySharedPreferences()
        val dbFile = File(tempFolder.root, "openfolio.db")
        val failingProvider = DatabasePassphraseProvider(
            preferences = prefs,
            databaseFile = dbFile,
            keyProvider = { throw GeneralSecurityException("Keystore locked") },
        )

        val state = failingProvider.resolveDatabaseKeyState()
        assertTrue(state is DatabasePassphraseProvider.DatabaseKeyState.Corrupted)
        // Must still return placeholder bytes so Room builder doesn't crash before recovery UI mounts
        assertEquals(32, failingProvider.getPassphraseOrPlaceholder().size)
    }

    @Test
    fun explicitResetWipesDatabaseAndGeneratesFreshReadyKey() {
        val prefs = InMemorySharedPreferences()
        val dbFile = File(tempFolder.root, "openfolio.db").apply {
            writeBytes("OLD_CORRUPTED_DB".toByteArray())
        }
        val walFile = File("${dbFile.absolutePath}-wal").apply { writeBytes("WAL".toByteArray()) }
        prefs.edit().putString("iv", "corrupted").putString("ciphertext", "corrupted").commit()

        val provider = DatabasePassphraseProvider(prefs, dbFile, { testKey(0x22) })
        assertTrue(provider.resolveDatabaseKeyState() is DatabasePassphraseProvider.DatabaseKeyState.Corrupted)

        val resetOk = provider.resetCorruptedDatabaseAndKey()
        assertTrue(resetOk)
        assertFalse(dbFile.exists())
        assertFalse(walFile.exists())
        assertTrue(provider.isDatabaseReady())
    }

    internal class InMemorySharedPreferences : SharedPreferences {
        private val map = linkedMapOf<String, Any?>()
        private val listeners = mutableSetOf<SharedPreferences.OnSharedPreferenceChangeListener>()

        override fun getAll(): MutableMap<String, *> = LinkedHashMap(map)
        override fun getString(key: String?, defValue: String?): String? = map[key] as? String ?: defValue
        @Suppress("UNCHECKED_CAST")
        override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? =
            (map[key] as? Set<String>)?.toMutableSet() ?: defValues
        override fun getInt(key: String?, defValue: Int): Int = map[key] as? Int ?: defValue
        override fun getLong(key: String?, defValue: Long): Long = map[key] as? Long ?: defValue
        override fun getFloat(key: String?, defValue: Float): Float = map[key] as? Float ?: defValue
        override fun getBoolean(key: String?, defValue: Boolean): Boolean = map[key] as? Boolean ?: defValue
        override fun contains(key: String?): Boolean = map.containsKey(key)

        override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor {
            private val updates = linkedMapOf<String, Any?>()
            private var clearAll = false

            override fun putString(key: String?, value: String?) = apply { if (key != null) updates[key] = value }
            override fun putStringSet(key: String?, values: MutableSet<String>?) = apply {
                if (key != null) updates[key] = values?.toSet()
            }
            override fun putInt(key: String?, value: Int) = apply { if (key != null) updates[key] = value }
            override fun putLong(key: String?, value: Long) = apply { if (key != null) updates[key] = value }
            override fun putFloat(key: String?, value: Float) = apply { if (key != null) updates[key] = value }
            override fun putBoolean(key: String?, value: Boolean) = apply { if (key != null) updates[key] = value }
            override fun remove(key: String?) = apply { if (key != null) updates[key] = null }
            override fun clear() = apply { clearAll = true }

            override fun commit(): Boolean {
                if (clearAll) map.clear()
                updates.forEach { (k, v) -> if (v == null) map.remove(k) else map[k] = v }
                return true
            }

            override fun apply() {
                commit()
            }
        }

        override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {
            if (listener != null) listeners.add(listener)
        }

        override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {
            if (listener != null) listeners.remove(listener)
        }
    }
}
