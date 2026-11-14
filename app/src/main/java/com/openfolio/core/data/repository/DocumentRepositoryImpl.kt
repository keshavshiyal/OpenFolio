package com.openfolio.core.data.repository

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.openfolio.core.data.local.dao.DocumentDao
import com.openfolio.core.data.local.entity.DocumentEntity
import com.openfolio.core.domain.repository.DocumentRepository
import com.openfolio.core.domain.repository.DuplicateDocumentException
import com.openfolio.core.utils.DocumentParser
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

class DocumentRepositoryImpl @Inject constructor(
    private val documentDao: DocumentDao,
    private val documentParsers: Set<@JvmSuppressWildcards DocumentParser>,
) : DocumentRepository {
    override fun getAllDocuments(): Flow<List<DocumentEntity>> =
        documentDao.getAllDocuments()

    override suspend fun getDocumentById(id: String): DocumentEntity? =
        withContext(Dispatchers.IO) {
            documentDao.getDocumentById(id)
        }

    override suspend fun ingestFile(uri: Uri, context: Context): DocumentEntity =
        withContext(Dispatchers.IO) {
            val resolver = context.contentResolver
            val metadata = resolver.query(
                uri,
                arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
                null,
                null,
                null,
            )?.use { cursor ->
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (cursor.moveToFirst()) {
                    FileMetadata(
                        fileName = nameIndex.takeIf { it >= 0 }
                            ?.let(cursor::getString)
                            ?.takeIf(String::isNotBlank)
                            ?: UNKNOWN_FILE_NAME,
                        fileSize = sizeIndex.takeIf { it >= 0 }
                            ?.let(cursor::getLong)
                            ?.takeIf { it >= 0L },
                    )
                } else {
                    FileMetadata(UNKNOWN_FILE_NAME, null)
                }
            } ?: FileMetadata(UNKNOWN_FILE_NAME, null)

            val hash = resolver.openInputStream(uri)?.use { calculateSha256(it) }
                ?: throw IOException("Unable to read the selected file.")
            if (documentDao.getDocumentByHash(hash) != null) {
                throw DuplicateDocumentException(hash)
            }

            val documentsDirectory = File(context.filesDir, DOCUMENTS_DIRECTORY)
            if (!documentsDirectory.exists() && !documentsDirectory.mkdirs()) {
                throw IOException("Unable to create the private documents directory.")
            }

            val destination = File(documentsDirectory, "${UUID.randomUUID()}.pdf")
            try {
                val copiedSize = resolver.openInputStream(uri)?.use { input ->
                    FileOutputStream(destination).use { output ->
                        input.copyTo(output)
                    }
                } ?: throw IOException("Unable to read the selected file.")

                val extractedText = documentParsers
                    .firstOrNull { it.supports(metadata.fileName) }
                    ?.extractText(destination)
                val document = DocumentEntity(
                    fileName = metadata.fileName,
                    localPath = destination.absolutePath,
                    fileHash = hash,
                    fileSize = metadata.fileSize ?: copiedSize,
                    importDate = System.currentTimeMillis(),
                    extractedText = extractedText,
                )
                documentDao.insert(document)
                document
            } catch (exception: Exception) {
                if (destination.exists() && !destination.delete()) {
                    exception.addSuppressed(
                        IOException("Unable to remove the incomplete imported file."),
                    )
                }
                throw exception
            }
        }

    private data class FileMetadata(
        val fileName: String,
        val fileSize: Long?,
    )

    private companion object {
        const val DOCUMENTS_DIRECTORY = "documents"
        const val UNKNOWN_FILE_NAME = "unknown_file.pdf"
    }
}

internal suspend fun calculateSha256(inputStream: InputStream): String =
    withContext(Dispatchers.IO) {
        hashSha256(inputStream)
    }

private fun hashSha256(inputStream: InputStream): String {
    val digest = MessageDigest.getInstance("SHA-256")
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
    while (true) {
        val bytesRead = inputStream.read(buffer)
        if (bytesRead < 0) break
        digest.update(buffer, 0, bytesRead)
    }
    return digest.digest().joinToString(separator = "") { byte ->
        "%02x".format(byte.toInt() and 0xff)
    }
}
