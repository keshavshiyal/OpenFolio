package com.openfolio.core.data.local.export

import android.content.Context
import android.database.Cursor
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteStatement
import com.openfolio.core.data.local.ExtractedTextStore
import com.openfolio.core.data.local.OpenFolioDatabase
import com.openfolio.core.data.repository.DocumentRepositoryImpl
import com.openfolio.core.data.repository.SettingsRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.FilterOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.security.GeneralSecurityException
import java.security.SecureRandom
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Base64
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

data class BackupExportSummary(
    val defaultFileName: String,
    val tableCount: Int,
    val totalRows: Long,
    val inboxDocumentFilesCount: Int,
    val extractedTextFilesCount: Int,
    val exportedAt: String,
)

data class BackupRestoreSummary(
    val tableCount: Int,
    val totalRows: Long,
    val restoredDocumentFilesCount: Int,
    val restoredExtractedTextCount: Int,
    val restoredSettings: Boolean,
)

class BackupDecryptionException(message: String, cause: Throwable? = null) : Exception(message, cause)
class InvalidBackupFormatException(message: String, cause: Throwable? = null) : Exception(message, cause)

interface AppBackupManager {
    fun getDefaultBackupFileName(): String

    suspend fun exportEncryptedBackup(
        outputStream: OutputStream,
        password: String,
        includeApiKey: Boolean = false,
    ): BackupExportSummary

    suspend fun importEncryptedBackup(
        inputStream: InputStream,
        password: String,
    ): BackupRestoreSummary
}

@Singleton
class AppBackupManagerImpl @Inject constructor(
    private val database: OpenFolioDatabase,
    private val databaseExporter: DatabaseExporter,
    private val settingsRepository: SettingsRepository,
    @ApplicationContext context: Context,
) : AppBackupManager {

    private val workBaseDir: File = context.cacheDir
    private val documentsDir: File = File(context.filesDir, DocumentRepositoryImpl.DOCUMENTS_DIRECTORY)
    private val extractedTextDir: File = File(context.filesDir, ExtractedTextStore.DIRECTORY_NAME)

    private var customWorkBaseDir: File = context.cacheDir
    private var customDocumentsDir: File = documentsDir
    private var customExtractedTextDir: File = extractedTextDir
    private var dbProvider: () -> SupportSQLiteDatabase = { database.openHelper.writableDatabase }
    private var transactionRunner: (SupportSQLiteDatabase, () -> Unit) -> Unit = { db, block ->
        db.beginTransaction()
        try {
            block()
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }
    private var invalidateTables: () -> Unit = {
        runCatching { database.invalidationTracker.refreshVersionsAsync() }
    }

    internal constructor(
        database: OpenFolioDatabase,
        databaseExporter: DatabaseExporter,
        settingsRepository: SettingsRepository,
        workBaseDir: File,
        documentsDir: File,
        extractedTextDir: File,
        dbProvider: (() -> SupportSQLiteDatabase)? = null,
        transactionRunner: ((SupportSQLiteDatabase, () -> Unit) -> Unit)? = null,
        invalidateTables: (() -> Unit)? = null,
    ) : this(
        database = database,
        databaseExporter = databaseExporter,
        settingsRepository = settingsRepository,
        context = FakeDirContext(workBaseDir, documentsDir.parentFile ?: workBaseDir),
    ) {
        this.customWorkBaseDir = workBaseDir
        this.customDocumentsDir = documentsDir
        this.customExtractedTextDir = extractedTextDir
        dbProvider?.let { this.dbProvider = it }
        transactionRunner?.let { this.transactionRunner = it }
        invalidateTables?.let { this.invalidateTables = it }
    }

    override fun getDefaultBackupFileName(): String {
        val timestamp = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss")
            .withZone(ZoneOffset.UTC)
            .format(Instant.now())
        return "openfolio_backup_${timestamp}.$BACKUP_EXTENSION"
    }

    override suspend fun exportEncryptedBackup(
        outputStream: OutputStream,
        password: String,
        includeApiKey: Boolean,
    ): BackupExportSummary = withContext(Dispatchers.IO) {
        val trimmedPassword = password.trim()
        require(trimmedPassword.length >= MIN_PASSWORD_LENGTH) {
            "Backup password must be at least $MIN_PASSWORD_LENGTH characters."
        }

        val db = dbProvider()
        val tempZipFile = File(customWorkBaseDir, "backup_tmp_${UUID.randomUUID()}.zip")
        tempZipFile.parentFile?.mkdirs()

        try {
            val now = Instant.now()
            val isoTimestamp = DateTimeFormatter.ISO_INSTANT.format(now)
            val fileTimestamp = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss")
                .withZone(ZoneOffset.UTC)
                .format(now)
            val fileName = "openfolio_backup_${fileTimestamp}.$BACKUP_EXTENSION"

            val docFileManifest = JSONArray()
            val textFileManifest = JSONArray()
            var dbExportResult: ExportResult? = null

            val docRecords = readDocumentRecords(db)
            val bundledTextIds = mutableSetOf<String>()
            var bundledDocsCount = 0
            var bundledTextsCount = 0

            ZipOutputStream(BufferedOutputStream(FileOutputStream(tempZipFile))).use { zipOut ->
                // 1. Database JSON
                zipOut.putNextEntry(ZipEntry(ENTRY_DATABASE_JSON))
                dbExportResult = databaseExporter.exportDatabaseToJson(
                    db = db,
                    outputStream = NonClosingOutputStream(zipOut),
                )
                zipOut.closeEntry()

                // 2. Inbox Document Binary Files + Extracted Text Sidecars
                for (doc in docRecords) {
                    val localFile = doc.localPath.takeIf { it.isNotBlank() }?.let(::File)
                    if (localFile != null && localFile.exists() && localFile.isFile) {
                        val safeName = sanitizeFileName(doc.fileName.ifBlank { localFile.name })
                        val entryPath = "$DIR_INBOX_FILES/${doc.id}/$safeName"
                        zipOut.putNextEntry(ZipEntry(entryPath))
                        FileInputStream(localFile).use { it.copyTo(zipOut, IO_BUFFER_SIZE) }
                        zipOut.closeEntry()

                        docFileManifest.put(
                            JSONObject().apply {
                                put("documentId", doc.id)
                                put("zipEntry", entryPath)
                                put("fileName", safeName)
                            },
                        )
                        bundledDocsCount++
                    }

                    val sidecarFile = resolveSidecarFile(doc)
                    if (sidecarFile != null && sidecarFile.exists() && sidecarFile.isFile && bundledTextIds.add(doc.id)) {
                        val entryPath = "$DIR_EXTRACTED_TEXT/${doc.id}.txt"
                        zipOut.putNextEntry(ZipEntry(entryPath))
                        FileInputStream(sidecarFile).use { it.copyTo(zipOut, IO_BUFFER_SIZE) }
                        zipOut.closeEntry()

                        textFileManifest.put(
                            JSONObject().apply {
                                put("documentId", doc.id)
                                put("zipEntry", entryPath)
                            },
                        )
                        bundledTextsCount++
                    }
                }

                // 3. Manifest & App Settings
                val settingsJson = JSONObject().apply {
                    put("showValues", settingsRepository.getShowValues())
                    put("geminiModelName", settingsRepository.getGeminiModelName())
                    if (includeApiKey) {
                        val apiKey = settingsRepository.getGeminiApiKey()
                        if (apiKey.isNotBlank()) {
                            put("geminiApiKey", apiKey)
                        }
                    }
                }

                val manifest = JSONObject().apply {
                    put("format", MANIFEST_FORMAT)
                    put("backupVersion", BACKUP_FORMAT_VERSION)
                    put("databaseVersion", dbExportResult?.databaseVersion ?: db.version)
                    put("exportedAt", isoTimestamp)
                    put("documentFiles", docFileManifest)
                    put("extractedTextFiles", textFileManifest)
                    put("settings", settingsJson)
                }

                zipOut.putNextEntry(ZipEntry(ENTRY_MANIFEST_JSON))
                zipOut.write(manifest.toString(2).toByteArray(StandardCharsets.UTF_8))
                zipOut.closeEntry()
                zipOut.flush()
            }

            encryptZipToStream(
                sourceZipFile = tempZipFile,
                password = trimmedPassword,
                outputStream = outputStream,
            )

            val export = requireNotNull(dbExportResult)
            BackupExportSummary(
                defaultFileName = fileName,
                tableCount = export.tableCount,
                totalRows = export.totalRowCount,
                inboxDocumentFilesCount = bundledDocsCount,
                extractedTextFilesCount = bundledTextsCount,
                exportedAt = isoTimestamp,
            )
        } finally {
            tempZipFile.delete()
        }
    }

    override suspend fun importEncryptedBackup(
        inputStream: InputStream,
        password: String,
    ): BackupRestoreSummary = withContext(Dispatchers.IO) {
        val trimmedPassword = password.trim()
        require(trimmedPassword.isNotEmpty()) { "Backup password is required." }

        val tempZipFile = File(customWorkBaseDir, "restore_tmp_${UUID.randomUUID()}.zip")
        val tempExtractDir = File(customWorkBaseDir, "restore_extract_${UUID.randomUUID()}")
        tempZipFile.parentFile?.mkdirs()

        try {
            decryptStreamToZip(
                inputStream = inputStream,
                password = trimmedPassword,
                targetZipFile = tempZipFile,
            )

            val extractedEntries = unpackZipSafely(tempZipFile, tempExtractDir)
            val manifestFile = extractedEntries[ENTRY_MANIFEST_JSON]
                ?: throw InvalidBackupFormatException("Backup archive is missing manifest.json.")
            val databaseFile = extractedEntries[ENTRY_DATABASE_JSON]
                ?: throw InvalidBackupFormatException("Backup archive is missing database.json.")

            val manifest = runCatching {
                JSONObject(manifestFile.readText(StandardCharsets.UTF_8))
            }.getOrElse { ex ->
                throw InvalidBackupFormatException("Backup manifest is corrupted.", ex)
            }
            if (manifest.optString("format") != MANIFEST_FORMAT) {
                throw InvalidBackupFormatException("Unsupported backup format.")
            }

            val databaseJson = runCatching {
                JSONObject(databaseFile.readText(StandardCharsets.UTF_8))
            }.getOrElse { ex ->
                throw InvalidBackupFormatException("Backup database payload is corrupted.", ex)
            }
            val dbMeta = databaseJson.optJSONObject("metadata")
            if (dbMeta?.optString("format") != "OpenFolio Database Export") {
                throw InvalidBackupFormatException("Invalid database export payload inside backup.")
            }
            val tablesObj = databaseJson.optJSONObject("tables")
                ?: throw InvalidBackupFormatException("Backup database payload contains no tables.")

            val restoredDocsById = mutableMapOf<String, File>()
            val restoredTextsById = mutableMapOf<String, File>()
            val finalDocPathsById = mutableMapOf<String, String>()
            val finalTextPathsById = mutableMapOf<String, String>()

            val docArray = manifest.optJSONArray("documentFiles")
            if (docArray != null) {
                for (i in 0 until docArray.length()) {
                    val item = docArray.optJSONObject(i) ?: continue
                    val docId = item.optString("documentId").trim()
                    val zipEntry = item.optString("zipEntry").trim()
                    val safeName = sanitizeFileName(item.optString("fileName").ifBlank { "document" })
                    val source = extractedEntries[zipEntry]
                    if (docId.isNotBlank() && source != null && source.isFile) {
                        restoredDocsById[docId] = source
                        finalDocPathsById[docId] = File(customDocumentsDir, "${docId}_$safeName").absolutePath
                    }
                }
            }

            val textArray = manifest.optJSONArray("extractedTextFiles")
            if (textArray != null) {
                for (i in 0 until textArray.length()) {
                    val item = textArray.optJSONObject(i) ?: continue
                    val docId = item.optString("documentId").trim()
                    val zipEntry = item.optString("zipEntry").trim()
                    val source = extractedEntries[zipEntry]
                    if (docId.isNotBlank() && source != null && source.isFile) {
                        restoredTextsById[docId] = source
                        finalTextPathsById[docId] = File(customExtractedTextDir, "$docId.txt").absolutePath
                    }
                }
            }

            val db = dbProvider()
            var restoredTableCount = 0
            var restoredRowCount = 0L

            transactionRunner(db) {
                val (tables, rows) = restoreTablesInTransaction(
                    db = db,
                    tablesObj = tablesObj,
                    finalDocPathsById = finalDocPathsById,
                    finalTextPathsById = finalTextPathsById,
                )
                restoredTableCount = tables
                restoredRowCount = rows
            }

            // Transaction committed: replace local inbox files and extracted text sidecars
            syncRestoredFiles(
                targetDir = customDocumentsDir,
                restoredByPath = finalDocPathsById.mapNotNull { (docId, path) ->
                    restoredDocsById[docId]?.let { src -> path to src }
                }.toMap(),
            )
            syncRestoredFiles(
                targetDir = customExtractedTextDir,
                restoredByPath = finalTextPathsById.mapNotNull { (docId, path) ->
                    restoredTextsById[docId]?.let { src -> path to src }
                }.toMap(),
            )

            // Restore settings
            var restoredSettings = false
            val settingsObj = manifest.optJSONObject("settings")
            if (settingsObj != null) {
                if (settingsObj.has("showValues")) {
                    settingsRepository.setShowValues(settingsObj.optBoolean("showValues", true))
                }
                val model = settingsObj.optString("geminiModelName", "").trim()
                if (model.isNotEmpty()) {
                    settingsRepository.setGeminiModelName(SettingsRepository.resolveMigratedModelName(model))
                }
                val restoredApiKey = settingsObj.optString("geminiApiKey", "").trim()
                if (restoredApiKey.isNotEmpty()) {
                    settingsRepository.setGeminiApiKey(restoredApiKey)
                }
                restoredSettings = true
            }

            invalidateTables()

            BackupRestoreSummary(
                tableCount = restoredTableCount,
                totalRows = restoredRowCount,
                restoredDocumentFilesCount = restoredDocsById.size,
                restoredExtractedTextCount = restoredTextsById.size,
                restoredSettings = restoredSettings,
            )
        } finally {
            tempZipFile.delete()
            tempExtractDir.deleteRecursively()
        }
    }

    private fun restoreTablesInTransaction(
        db: SupportSQLiteDatabase,
        tablesObj: JSONObject,
        finalDocPathsById: Map<String, String>,
        finalTextPathsById: Map<String, String>,
    ): Pair<Int, Long> {
        val localTables = getLocalTableNames(db)
        val localTableSet = localTables.toSet()
        val backupTableNames = tablesObj.keys().asSequence().toList()
        val targetTables = backupTableNames.filter { it in localTableSet }

        val deleteOrder = buildDeleteOrder(targetTables, localTables)
        for (table in deleteOrder) {
            db.execSQL("DELETE FROM `$table`")
        }

        val insertOrder = buildInsertOrder(targetTables)
        var restoredTables = 0
        var totalRows = 0L

        for (table in insertOrder) {
            val tableJson = tablesObj.optJSONObject(table) ?: continue
            val rowsArray = tableJson.optJSONArray("rows") ?: continue
            val localColumns = getLocalColumnNames(db, table)
            val backupColNames = extractBackupColumnNames(tableJson, rowsArray)
            val columnsList = if (backupColNames.isNotEmpty()) {
                localColumns.filter { it in backupColNames }
            } else {
                localColumns
            }
            if (columnsList.isEmpty()) continue
            restoredTables++

            val columnSet = columnsList.toSet()
            val placeholders = columnsList.joinToString(", ") { "?" }
            val columnSql = columnsList.joinToString(", ") { "`$it`" }
            val sql = "INSERT OR REPLACE INTO `$table` ($columnSql) VALUES ($placeholders)"

            val stmt: SupportSQLiteStatement = db.compileStatement(sql)
            stmt.use { compiled ->
                for (r in 0 until rowsArray.length()) {
                    val rowObj = rowsArray.optJSONObject(r) ?: continue
                    val docId = if (table == TABLE_DOCUMENTS) rowObj.optString("id", "").trim() else ""

                    compiled.clearBindings()
                    columnsList.forEachIndexed { index, colName ->
                        val bindIdx = index + 1
                        if (table == TABLE_DOCUMENTS && colName == "localPath") {
                            val remapped = finalDocPathsById[docId] ?: ""
                            compiled.bindString(bindIdx, remapped)
                            return@forEachIndexed
                        }
                        if (table == TABLE_DOCUMENTS && colName == "extractedTextPath") {
                            val remapped = finalTextPathsById[docId]
                            if (remapped != null) {
                                compiled.bindString(bindIdx, remapped)
                            } else {
                                compiled.bindNull(bindIdx)
                            }
                            return@forEachIndexed
                        }

                        if (!rowObj.has(colName) || rowObj.isNull(colName)) {
                            compiled.bindNull(bindIdx)
                        } else {
                            bindJsonValue(compiled, bindIdx, rowObj.get(colName), colName in columnSet)
                        }
                    }
                    compiled.executeInsert()
                    totalRows++
                }
            }
        }

        return restoredTables to totalRows
    }

    private fun extractBackupColumnNames(tableJson: JSONObject, rowsArray: JSONArray): Set<String> {
        val result = linkedSetOf<String>()
        val colsArray = tableJson.optJSONArray("columns")
        if (colsArray != null) {
            for (i in 0 until colsArray.length()) {
                val name = colsArray.optJSONObject(i)?.optString("name")?.trim()
                if (!name.isNullOrEmpty()) result.add(name)
            }
        }
        if (result.isEmpty() && rowsArray.length() > 0) {
            rowsArray.optJSONObject(0)?.keys()?.forEach { result.add(it) }
        }
        return result
    }

    private fun bindJsonValue(
        stmt: SupportSQLiteStatement,
        bindIndex: Int,
        value: Any,
        @Suppress("UNUSED_PARAMETER") hasCol: Boolean,
    ) {
        when (value) {
            is Boolean -> stmt.bindLong(bindIndex, if (value) 1L else 0L)
            is Int -> stmt.bindLong(bindIndex, value.toLong())
            is Long -> stmt.bindLong(bindIndex, value)
            is Float -> stmt.bindDouble(bindIndex, value.toDouble())
            is Double -> stmt.bindDouble(bindIndex, value)
            is Number -> stmt.bindDouble(bindIndex, value.toDouble())
            is String -> {
                if (value.startsWith("base64:")) {
                    val decoded = runCatching {
                        Base64.getDecoder().decode(value.removePrefix("base64:"))
                    }.getOrNull()
                    if (decoded != null) {
                        stmt.bindBlob(bindIndex, decoded)
                    } else {
                        stmt.bindString(bindIndex, value)
                    }
                } else {
                    stmt.bindString(bindIndex, value)
                }
            }
            else -> stmt.bindString(bindIndex, value.toString())
        }
    }

    private fun syncRestoredFiles(
        targetDir: File,
        restoredByPath: Map<String, File>,
    ) {
        if (targetDir.exists()) {
            targetDir.listFiles()?.forEach { existing ->
                if (existing.isFile) {
                    existing.delete()
                }
            }
        } else {
            targetDir.mkdirs()
        }

        for ((targetPath, sourceFile) in restoredByPath) {
            val dest = File(targetPath)
            dest.parentFile?.mkdirs()
            FileInputStream(sourceFile).use { input ->
                FileOutputStream(dest).use { output ->
                    input.copyTo(output, IO_BUFFER_SIZE)
                }
            }
        }
    }

    private fun unpackZipSafely(zipFile: File, destDir: File): Map<String, File> {
        destDir.mkdirs()
        val canonicalDest = destDir.canonicalPath + File.separator
        val extracted = mutableMapOf<String, File>()

        ZipInputStream(BufferedInputStream(FileInputStream(zipFile))).use { zipIn ->
            while (true) {
                val entry = zipIn.nextEntry ?: break
                if (entry.isDirectory) {
                    zipIn.closeEntry()
                    continue
                }
                val entryName = entry.name.replace('\\', '/')
                if (entryName.contains("..") || entryName.startsWith("/")) {
                    throw InvalidBackupFormatException("Unsafe path inside backup archive: $entryName")
                }
                val targetFile = File(destDir, entryName)
                if (!targetFile.canonicalPath.startsWith(canonicalDest)) {
                    throw InvalidBackupFormatException("Unsafe entry outside target directory: $entryName")
                }
                targetFile.parentFile?.mkdirs()
                FileOutputStream(targetFile).use { out ->
                    zipIn.copyTo(out, IO_BUFFER_SIZE)
                }
                extracted[entryName] = targetFile
                zipIn.closeEntry()
            }
        }
        return extracted
    }

    private fun encryptZipToStream(
        sourceZipFile: File,
        password: String,
        outputStream: OutputStream,
    ) {
        val random = SecureRandom()
        val salt = ByteArray(SALT_BYTES).also(random::nextBytes)
        val iv = ByteArray(IV_BYTES).also(random::nextBytes)
        val iterations = PBKDF2_ITERATIONS
        val header = buildHeaderBytes(iterations, salt, iv)
        val key = deriveAesKey(password, salt, iterations)

        val cipher = Cipher.getInstance(CIPHER_TRANSFORMATION).apply {
            init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
            updateAAD(header)
        }

        outputStream.write(header)
        val buffer = ByteArray(IO_BUFFER_SIZE)
        BufferedInputStream(FileInputStream(sourceZipFile)).use { input ->
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                val encryptedChunk = cipher.update(buffer, 0, read)
                if (encryptedChunk != null && encryptedChunk.isNotEmpty()) {
                    outputStream.write(encryptedChunk)
                }
            }
        }
        val finalBlock = cipher.doFinal()
        if (finalBlock != null && finalBlock.isNotEmpty()) {
            outputStream.write(finalBlock)
        }
        outputStream.flush()
    }

    private fun decryptStreamToZip(
        inputStream: InputStream,
        password: String,
        targetZipFile: File,
    ) {
        val buffered = BufferedInputStream(inputStream)
        val header = ByteArray(HEADER_BYTES)
        var offset = 0
        while (offset < HEADER_BYTES) {
            val read = buffered.read(header, offset, HEADER_BYTES - offset)
            if (read < 0) {
                throw InvalidBackupFormatException("Not a valid OpenFolio backup file.")
            }
            offset += read
        }

        val magic = String(header, 0, MAGIC_BYTES.size, StandardCharsets.US_ASCII)
        if (magic != MAGIC_HEADER) {
            throw InvalidBackupFormatException("Not a valid OpenFolio backup file.")
        }

        val bb = ByteBuffer.wrap(header, MAGIC_BYTES.size, HEADER_BYTES - MAGIC_BYTES.size)
        val version = bb.int
        val iterations = bb.int
        if (version != BACKUP_FORMAT_VERSION || iterations !in MIN_ITERATIONS..MAX_ITERATIONS) {
            throw InvalidBackupFormatException("Unsupported OpenFolio backup version.")
        }
        val salt = ByteArray(SALT_BYTES).also { bb.get(it) }
        val iv = ByteArray(IV_BYTES).also { bb.get(it) }

        val key = deriveAesKey(password, salt, iterations)
        val cipher = Cipher.getInstance(CIPHER_TRANSFORMATION).apply {
            init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
            updateAAD(header)
        }

        try {
            FileOutputStream(targetZipFile).use { out ->
                val buffer = ByteArray(IO_BUFFER_SIZE)
                while (true) {
                    val read = buffered.read(buffer)
                    if (read <= 0) break
                    val plainChunk = cipher.update(buffer, 0, read)
                    if (plainChunk != null && plainChunk.isNotEmpty()) {
                        out.write(plainChunk)
                    }
                }
                val finalBlock = cipher.doFinal()
                if (finalBlock != null && finalBlock.isNotEmpty()) {
                    out.write(finalBlock)
                }
                out.flush()
            }
        } catch (badTag: AEADBadTagException) {
            throw BackupDecryptionException("Incorrect backup password or corrupted backup file.", badTag)
        } catch (sec: GeneralSecurityException) {
            throw BackupDecryptionException("Unable to decrypt backup file.", sec)
        }
    }

    private fun deriveAesKey(password: String, salt: ByteArray, iterations: Int): SecretKeySpec {
        val chars = password.toCharArray()
        val spec = PBEKeySpec(chars, salt, iterations, AES_KEY_BITS)
        return try {
            val factory = SecretKeyFactory.getInstance(PBKDF2_ALGORITHM)
            val encoded = factory.generateSecret(spec).encoded
            SecretKeySpec(encoded, "AES")
        } finally {
            chars.fill('\u0000')
            spec.clearPassword()
        }
    }

    private fun buildHeaderBytes(iterations: Int, salt: ByteArray, iv: ByteArray): ByteArray =
        ByteBuffer.allocate(HEADER_BYTES)
            .put(MAGIC_BYTES)
            .putInt(BACKUP_FORMAT_VERSION)
            .putInt(iterations)
            .put(salt)
            .put(iv)
            .array()

    private fun readDocumentRecords(db: SupportSQLiteDatabase): List<DocFileInfo> {
        val docs = mutableListOf<DocFileInfo>()
        runCatching {
            db.query("SELECT id, fileName, localPath, extractedTextPath FROM `$TABLE_DOCUMENTS`").use { cursor ->
                val idCol = cursor.getColumnIndex("id")
                val nameCol = cursor.getColumnIndex("fileName")
                val localPathCol = cursor.getColumnIndex("localPath")
                val textPathCol = cursor.getColumnIndex("extractedTextPath")
                while (cursor.moveToNext()) {
                    val id = if (idCol >= 0) cursor.getString(idCol).orEmpty() else ""
                    if (id.isBlank()) continue
                    val fileName = if (nameCol >= 0) cursor.getString(nameCol).orEmpty() else ""
                    val localPath = if (localPathCol >= 0 && !cursor.isNull(localPathCol)) cursor.getString(localPathCol).orEmpty() else ""
                    val textPath = if (textPathCol >= 0 && !cursor.isNull(textPathCol)) cursor.getString(textPathCol) else null
                    docs.add(DocFileInfo(id, fileName, localPath, textPath))
                }
            }
        }
        return docs
    }

    private fun resolveSidecarFile(doc: DocFileInfo): File? {
        val fromPath = doc.extractedTextPath?.takeIf { it.isNotBlank() }?.let(::File)
        if (fromPath != null && fromPath.exists() && fromPath.isFile) {
            return fromPath
        }
        val byConvention = File(customExtractedTextDir, "${doc.id}.txt")
        return byConvention.takeIf { it.exists() && it.isFile }
    }

    private fun getLocalTableNames(db: SupportSQLiteDatabase): List<String> {
        val tables = mutableListOf<String>()
        val query = """
            SELECT name FROM sqlite_master
            WHERE type = 'table'
              AND name NOT LIKE 'sqlite_%'
              AND name NOT LIKE 'android_metadata'
              AND name != 'room_master_table'
            ORDER BY name ASC
        """.trimIndent()
        db.query(query).use { cursor ->
            val nameCol = cursor.getColumnIndexOrThrow("name")
            while (cursor.moveToNext()) {
                tables.add(cursor.getString(nameCol))
            }
        }
        return tables
    }

    private fun getLocalColumnNames(db: SupportSQLiteDatabase, tableName: String): List<String> {
        val cols = mutableListOf<String>()
        db.query("PRAGMA table_info(`$tableName`)").use { cursor ->
            val nameCol = cursor.getColumnIndex("name")
            while (cursor.moveToNext()) {
                if (nameCol >= 0) {
                    cols.add(cursor.getString(nameCol))
                }
            }
        }
        return cols
    }

    private fun buildDeleteOrder(targetTables: List<String>, allLocalTables: List<String>): List<String> {
        val candidates = (targetTables + allLocalTables).distinct()
        val childFirst = PREFERRED_DELETE_ORDER.filter { it in candidates }
        val remaining = candidates.filter { it !in childFirst }
        return childFirst + remaining
    }

    private fun buildInsertOrder(targetTables: List<String>): List<String> {
        val parentFirst = PREFERRED_INSERT_ORDER.filter { it in targetTables }
        val remaining = targetTables.filter { it !in parentFirst }
        return parentFirst + remaining
    }

    private fun sanitizeFileName(raw: String): String =
        raw.replace(Regex("[^A-Za-z0-9._-]"), "_").take(120).ifBlank { "document" }

    private data class DocFileInfo(
        val id: String,
        val fileName: String,
        val localPath: String,
        val extractedTextPath: String?,
    )

    private class NonClosingOutputStream(out: OutputStream) : FilterOutputStream(out) {
        override fun write(b: ByteArray, off: Int, len: Int) {
            out.write(b, off, len)
        }

        override fun close() {
            out.flush()
        }
    }

    private class FakeDirContext(
        private val cache: File,
        private val files: File,
    ) : android.content.ContextWrapper(null) {
        override fun getCacheDir(): File = cache
        override fun getFilesDir(): File = files
    }

    companion object {
        const val BACKUP_EXTENSION = "ofbackup"
        const val MIN_PASSWORD_LENGTH = 6
        private const val MAGIC_HEADER = "OFBKUP01"
        private val MAGIC_BYTES = MAGIC_HEADER.toByteArray(StandardCharsets.US_ASCII)
        private const val BACKUP_FORMAT_VERSION = 1
        private const val PBKDF2_ITERATIONS = 120_000
        private const val MIN_ITERATIONS = 10_000
        private const val MAX_ITERATIONS = 1_000_000
        private const val SALT_BYTES = 16
        private const val IV_BYTES = 12
        private const val HEADER_BYTES = 8 + 4 + 4 + SALT_BYTES + IV_BYTES
        private const val AES_KEY_BITS = 256
        private const val GCM_TAG_BITS = 128
        private const val PBKDF2_ALGORITHM = "PBKDF2WithHmacSHA256"
        private const val CIPHER_TRANSFORMATION = "AES/GCM/NoPadding"
        private const val IO_BUFFER_SIZE = 16 * 1024

        private const val MANIFEST_FORMAT = "OpenFolio Encrypted Backup"
        private const val ENTRY_MANIFEST_JSON = "manifest.json"
        private const val ENTRY_DATABASE_JSON = "database.json"
        private const val DIR_INBOX_FILES = "inbox_files"
        private const val DIR_EXTRACTED_TEXT = "extracted_text"
        private const val TABLE_DOCUMENTS = "documents"

        private val PREFERRED_INSERT_ORDER = listOf(
            "account",
            "app_metadata",
            "asset",
            "column_mappings",
            "documents",
            "draft_transactions",
            "lot",
            "valuation",
            "corporate_action",
            "txn",
        )
        private val PREFERRED_DELETE_ORDER = PREFERRED_INSERT_ORDER.asReversed()
    }
}
