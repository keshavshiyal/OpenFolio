package com.openfolio.core.data.local

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.BufferedInputStream
import java.io.ByteArrayInputStream
import java.io.EOFException
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.SequenceInputStream
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.security.GeneralSecurityException
import java.security.KeyStore
import java.security.SecureRandom
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Chunked AES-256-GCM file encryption backed by Android Keystore.
 *
 * Uses O(chunkSize) heap memory so large PDF statements and sidecar text files can be
 * streamed safely without loading entire payloads into memory. Unencrypted legacy files
 * (without the [MAGIC_HEADER] prefix) remain transparently readable.
 */
@Singleton
class LocalFileCrypto internal constructor(
    private val keyProvider: () -> SecretKey,
    private val random: SecureRandom = SecureRandom(),
) {
    @Inject
    constructor(@ApplicationContext context: Context) : this(
        keyProvider = { AndroidKeystoreKeyProvider.getOrCreateKey(KEY_ALIAS) },
    ) {
        // ApplicationContext retained only for Hilt graph scoping; Keystore is process-global.
        context.applicationContext
    }

    fun isEncrypted(file: File): Boolean {
        if (!file.exists() || !file.isFile || file.length() < HEADER_BYTES) return false
        return runCatching {
            FileInputStream(file).use { input ->
                val prefix = ByteArray(MAGIC_BYTES.size)
                val read = readFullyOrEof(input, prefix)
                read == MAGIC_BYTES.size && prefix.contentEquals(MAGIC_BYTES)
            }
        }.getOrDefault(false)
    }

    fun encryptingOutputStream(target: OutputStream): OutputStream =
        ChunkedGcmOutputStream(target, keyProvider(), DEFAULT_CHUNK_SIZE, random)

    fun openPlaintextInputStream(file: File): InputStream =
        decryptingInputStream(BufferedInputStream(FileInputStream(file), IO_BUFFER_SIZE))

    fun decryptingInputStream(source: InputStream): InputStream {
        val prefix = ByteArray(MAGIC_BYTES.size)
        val prefixRead = readFullyOrEof(source, prefix)
        if (prefixRead < MAGIC_BYTES.size || !prefix.contentEquals(MAGIC_BYTES)) {
            if (prefixRead <= 0) {
                return ByteArrayInputStream(ByteArray(0))
            }
            return SequenceInputStream(ByteArrayInputStream(prefix, 0, prefixRead), source)
        }

        val chunkSizeBuf = ByteArray(4)
        val sizeRead = readFullyOrEof(source, chunkSizeBuf)
        if (sizeRead < 4) {
            source.close()
            throw IOException("Corrupted encrypted file header.")
        }
        val chunkSize = ByteBuffer.wrap(chunkSizeBuf).int
        if (chunkSize !in MIN_CHUNK_SIZE..MAX_CHUNK_SIZE) {
            source.close()
            throw IOException("Unsupported encrypted chunk size: $chunkSize")
        }

        val header = ByteArray(HEADER_BYTES)
        System.arraycopy(prefix, 0, header, 0, MAGIC_BYTES.size)
        System.arraycopy(chunkSizeBuf, 0, header, MAGIC_BYTES.size, 4)

        return ChunkedGcmInputStream(source, keyProvider(), header, chunkSize)
    }

    /**
     * Provides a seekable plaintext [File] for APIs such as Android PdfRenderer or ZipFile.
     * Unencrypted legacy files are passed directly; encrypted files are streamed into a
     * short-lived scratch file and wiped deterministically when [block] completes.
     */
    inline fun <T> withDecryptedTempFile(
        sourceFile: File,
        scratchDir: File,
        extension: String,
        block: (File) -> T,
    ): T {
        if (!isEncrypted(sourceFile)) {
            return block(sourceFile)
        }
        if (!scratchDir.exists()) {
            scratchDir.mkdirs()
        }
        val safeExt = extension.lowercase().replace(Regex("[^a-z0-9]"), "").ifBlank { "tmp" }
        val tempFile = File(scratchDir, "dec_${UUID.randomUUID()}.$safeExt")
        try {
            openPlaintextInputStream(sourceFile).use { input ->
                FileOutputStream(tempFile).use { output ->
                    val buffer = ByteArray(IO_BUFFER_SIZE)
                    while (true) {
                        val read = input.read(buffer)
                        if (read <= 0) break
                        output.write(buffer, 0, read)
                    }
                    output.flush()
                }
            }
            return block(tempFile)
        } finally {
            runCatching { tempFile.delete() }
        }
    }

    internal object AndroidKeystoreKeyProvider {
        @Synchronized
        fun getOrCreateKey(alias: String): SecretKey {
            val keyStore = KeyStore.getInstance(ANDROID_KEY_STORE).apply { load(null) }
            (keyStore.getKey(alias, null) as? SecretKey)?.let { return it }
            return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEY_STORE).run {
                init(
                    KeyGenParameterSpec.Builder(
                        alias,
                        KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                    )
                        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                        .setKeySize(AES_KEY_BITS)
                        .setRandomizedEncryptionRequired(true)
                        .build(),
                )
                generateKey()
            }
        }
    }

    private class ChunkedGcmOutputStream(
        private val out: OutputStream,
        private val key: SecretKey,
        private val chunkSize: Int,
        private val random: SecureRandom,
    ) : OutputStream() {
        private val header = buildHeader(chunkSize)
        private val buffer = ByteArray(chunkSize)
        private var bufferedCount = 0
        private var chunkIndex = 0
        private var headerWritten = false
        private var closed = false

        override fun write(b: Int) {
            checkOpen()
            if (bufferedCount >= chunkSize) {
                emitChunk(isFinal = false)
            }
            buffer[bufferedCount++] = b.toByte()
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            checkOpen()
            if (off < 0 || len < 0 || off + len > b.size) {
                throw IndexOutOfBoundsException("Invalid offset/length for write")
            }
            var cursor = off
            var remaining = len
            while (remaining > 0) {
                if (bufferedCount >= chunkSize) {
                    emitChunk(isFinal = false)
                }
                val toCopy = minOf(remaining, chunkSize - bufferedCount)
                System.arraycopy(b, cursor, buffer, bufferedCount, toCopy)
                bufferedCount += toCopy
                cursor += toCopy
                remaining -= toCopy
            }
        }

        override fun flush() {
            if (!closed && headerWritten) {
                out.flush()
            }
        }

        override fun close() {
            if (closed) return
            closed = true
            try {
                emitChunk(isFinal = true)
                out.flush()
            } finally {
                buffer.fill(0)
                runCatching { out.close() }
            }
        }

        private fun checkOpen() {
            if (closed) throw IOException("Stream is closed.")
        }

        private fun emitChunk(isFinal: Boolean) {
            if (!headerWritten) {
                out.write(header)
                headerWritten = true
            }
            val iv = ByteArray(IV_BYTES).also(random::nextBytes)
            val aad = buildChunkAad(header, chunkIndex, isFinal)
            val ciphertext = try {
                Cipher.getInstance(CIPHER_TRANSFORMATION).run {
                    init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
                    updateAAD(aad)
                    doFinal(buffer, 0, bufferedCount)
                }
            } catch (e: GeneralSecurityException) {
                throw IOException("Failed to encrypt local storage chunk.", e)
            }

            val meta = ByteBuffer.allocate(1 + IV_BYTES + 4)
                .put(if (isFinal) 1.toByte() else 0.toByte())
                .put(iv)
                .putInt(ciphertext.size)
                .array()
            out.write(meta)
            out.write(ciphertext)
            bufferedCount = 0
            chunkIndex++
        }
    }

    private class ChunkedGcmInputStream(
        private val input: InputStream,
        private val key: SecretKey,
        private val header: ByteArray,
        private val chunkSize: Int,
    ) : InputStream() {
        private var currentPlain: ByteArray = ByteArray(0)
        private var currentPos = 0
        private var chunkIndex = 0
        private var reachedFinalChunk = false
        private var closed = false

        override fun read(): Int {
            val one = ByteArray(1)
            val count = read(one, 0, 1)
            return if (count <= 0) -1 else one[0].toInt() and 0xFF
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (closed) throw IOException("Stream is closed.")
            if (off < 0 || len < 0 || off + len > b.size) {
                throw IndexOutOfBoundsException("Invalid offset/length for read")
            }
            if (len == 0) return 0

            while (currentPos >= currentPlain.size) {
                if (reachedFinalChunk) return -1
                if (!readNextChunk()) return -1
            }

            val available = currentPlain.size - currentPos
            val toCopy = minOf(len, available)
            System.arraycopy(currentPlain, currentPos, b, off, toCopy)
            currentPos += toCopy
            return toCopy
        }

        override fun close() {
            if (closed) return
            closed = true
            currentPlain.fill(0)
            input.close()
        }

        private fun readNextChunk(): Boolean {
            val frameHeader = ByteArray(1 + IV_BYTES + 4)
            val readHeaderBytes = readFullyOrEof(input, frameHeader)
            if (readHeaderBytes == 0) {
                throw IOException("Encrypted file ended unexpectedly without final authenticated block.")
            }
            if (readHeaderBytes < frameHeader.size) {
                throw IOException("Truncated encrypted chunk header.")
            }

            val bb = ByteBuffer.wrap(frameHeader)
            val isFinal = bb.get().toInt() != 0
            val iv = ByteArray(IV_BYTES).also { bb.get(it) }
            val cipherLen = bb.int
            val maxExpectedCipherLen = chunkSize + (GCM_TAG_BITS / 8)
            if (cipherLen < (GCM_TAG_BITS / 8) || cipherLen > maxExpectedCipherLen) {
                throw IOException("Invalid encrypted chunk size: $cipherLen")
            }

            val ciphertext = ByteArray(cipherLen)
            val readCipher = readFullyOrEof(input, ciphertext)
            if (readCipher < cipherLen) {
                throw EOFException("Unexpected end of encrypted payload.")
            }

            val aad = buildChunkAad(header, chunkIndex, isFinal)
            val plain = try {
                Cipher.getInstance(CIPHER_TRANSFORMATION).run {
                    init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
                    updateAAD(aad)
                    doFinal(ciphertext)
                }
            } catch (e: GeneralSecurityException) {
                throw IOException("Encrypted local file failed integrity verification.", e)
            }

            currentPlain.fill(0)
            currentPlain = plain
            currentPos = 0
            chunkIndex++
            if (isFinal) {
                reachedFinalChunk = true
                if (input.read() != -1) {
                    throw IOException("Unexpected trailing bytes after final encrypted chunk.")
                }
            }
            return plain.isNotEmpty()
        }
    }

    companion object {
        private const val ANDROID_KEY_STORE = "AndroidKeyStore"
        private const val KEY_ALIAS = "openfolio_local_files_key"
        private const val CIPHER_TRANSFORMATION = "AES/GCM/NoPadding"
        private const val AES_KEY_BITS = 256
        private const val GCM_TAG_BITS = 128
        private const val IV_BYTES = 12
        private const val MAGIC_HEADER = "OPENFOLIO_ENC_V1"
        private val MAGIC_BYTES = MAGIC_HEADER.toByteArray(StandardCharsets.US_ASCII)
        private const val HEADER_BYTES = 16 + 4
        private const val DEFAULT_CHUNK_SIZE = 64 * 1024
        private const val MIN_CHUNK_SIZE = 1024
        private const val MAX_CHUNK_SIZE = 4 * 1024 * 1024
        const val IO_BUFFER_SIZE = 32 * 1024
        const val DEC_TEMP_DIR_NAME = "dec_tmp"

        private fun buildHeader(chunkSize: Int): ByteArray =
            ByteBuffer.allocate(HEADER_BYTES)
                .put(MAGIC_BYTES)
                .putInt(chunkSize)
                .array()

        private fun buildChunkAad(header: ByteArray, chunkIndex: Int, isFinal: Boolean): ByteArray =
            ByteBuffer.allocate(header.size + 4 + 1)
                .put(header)
                .putInt(chunkIndex)
                .put(if (isFinal) 1.toByte() else 0.toByte())
                .array()

        private fun readFullyOrEof(stream: InputStream, dest: ByteArray): Int {
            var offset = 0
            while (offset < dest.size) {
                val read = stream.read(dest, offset, dest.size - offset)
                if (read < 0) break
                offset += read
            }
            return offset
        }
    }
}
