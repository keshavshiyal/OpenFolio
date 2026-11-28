package com.openfolio.core.data.local

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.SecureRandom
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LocalFileCryptoTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private fun testKey(): SecretKey {
        val raw = ByteArray(32) { i -> (i + 1).toByte() }
        return SecretKeySpec(raw, "AES")
    }

    @Test
    fun chunkedEncryptionRoundTripsMultiChunkPayloadWithoutHoldingPlaintextOnDisk() {
        val crypto = LocalFileCrypto(keyProvider = ::testKey, random = SecureRandom())
        val payload = ByteArray(150 * 1024 + 313) { i -> ((i * 31 + 7) and 0xFF).toByte() }
        val file = tempFolder.newFile("statement.pdf")

        crypto.encryptingOutputStream(FileOutputStream(file)).use { out ->
            out.write(payload)
            out.flush()
        }

        assertTrue(crypto.isEncrypted(file))
        val onDiskPrefix = file.readBytes().copyOfRange(0, 16).decodeToString()
        assertEquals("OPENFOLIO_ENC_V1", onDiskPrefix)

        val decrypted = crypto.openPlaintextInputStream(file).use { it.readBytes() }
        assertArrayEquals(payload, decrypted)
    }

    @Test
    fun zeroByteEncryptionRoundTripsSafely() {
        val crypto = LocalFileCrypto(keyProvider = ::testKey)
        val file = tempFolder.newFile("empty.txt")

        crypto.encryptingOutputStream(FileOutputStream(file)).use { out ->
            out.flush()
        }

        assertTrue(crypto.isEncrypted(file))
        val decrypted = crypto.openPlaintextInputStream(file).use { it.readBytes() }
        assertEquals(0, decrypted.size)
    }

    @Test
    fun legacyPlaintextFilesReadTransparently() {
        val crypto = LocalFileCrypto(keyProvider = ::testKey)
        val plain = "Date,Symbol,Qty,Price\n2024-01-15,TCS,5,3900\n".toByteArray()
        val file = tempFolder.newFile("legacy.csv")
        file.writeBytes(plain)

        assertFalse(crypto.isEncrypted(file))
        val actual = crypto.openPlaintextInputStream(file).use { it.readBytes() }
        assertArrayEquals(plain, actual)
    }

    @Test(expected = IOException::class)
    fun tamperedCiphertextFailsGcmAuthentication() {
        val crypto = LocalFileCrypto(keyProvider = ::testKey)
        val file = tempFolder.newFile("tampered.bin")
        crypto.encryptingOutputStream(FileOutputStream(file)).use { out ->
            out.write("Sensitive statement data 1234567890".toByteArray())
        }

        val bytes = file.readBytes()
        bytes[bytes.size - 5] = (bytes[bytes.size - 5].toInt() xor 0xFF).toByte()
        file.writeBytes(bytes)

        crypto.openPlaintextInputStream(file).use { it.readBytes() }
    }

    @Test(expected = IOException::class)
    fun truncatedEncryptedFileFailsWithoutReturningPartialData() {
        val crypto = LocalFileCrypto(keyProvider = ::testKey)
        val file = tempFolder.newFile("truncated.bin")
        crypto.encryptingOutputStream(FileOutputStream(file)).use { out ->
            out.write(ByteArray(90 * 1024) { 0x2A })
        }

        val bytes = file.readBytes()
        file.writeBytes(bytes.copyOfRange(0, bytes.size - 32))

        val out = ByteArrayOutputStream()
        crypto.openPlaintextInputStream(file).use { input ->
            input.copyTo(out)
        }
    }

    @Test
    fun withDecryptedTempFileCreatesReadableSeekableCopyAndWipesItAfterBlock() {
        val crypto = LocalFileCrypto(keyProvider = ::testKey)
        val source = tempFolder.newFile("doc.pdf")
        val scratch = tempFolder.newFolder("dec_tmp")
        val expected = "PDF_HEADER_AND_BODY".toByteArray()
        crypto.encryptingOutputStream(FileOutputStream(source)).use { it.write(expected) }

        var capturedTemp: File? = null
        val readContent = crypto.withDecryptedTempFile(source, scratch, "pdf") { readable ->
            capturedTemp = readable
            assertNotSame(source, readable)
            assertTrue(readable.exists())
            assertEquals("pdf", readable.extension)
            readable.readBytes()
        }

        assertArrayEquals(expected, readContent)
        assertFalse(capturedTemp!!.exists())
    }
}
