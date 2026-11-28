package com.openfolio.core.data.local.export

import android.database.Cursor
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteStatement
import com.openfolio.core.data.local.OpenFolioDatabase
import com.openfolio.core.data.repository.SettingsRepository
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.OutputStream
import java.nio.charset.StandardCharsets
import kotlinx.coroutines.test.runTest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

class AppBackupManagerTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun databaseSchemaVersionIsResetToOne() {
        assertEquals(1, OpenFolioDatabase.SCHEMA_VERSION)
    }

    @Test
    fun encryptedBackupRoundTripRestoresDatabaseRowsInboxFilesSidecarTextAndSettings() = runTest {
        val sourceWorkDir = tempFolder.newFolder("src_work")
        val sourceDocsDir = tempFolder.newFolder("src_docs")
        val sourceTextDir = tempFolder.newFolder("src_text")

        val sourcePdf = File(sourceDocsDir, "cams_cas.pdf").apply {
            writeBytes("%PDF-1.4\nsample statement bytes\n%%EOF".toByteArray(StandardCharsets.UTF_8))
        }
        val sourceText = File(sourceTextDir, "doc-1.txt").apply {
            writeText("HDFC Equity Fund 100 units @ 50.00")
        }

        val sourceDb = mock<SupportSQLiteDatabase>()
        whenever(sourceDb.version).thenReturn(OpenFolioDatabase.SCHEMA_VERSION)
        whenever(sourceDb.query(any<String>())).thenAnswer { invocation ->
            val sql = invocation.getArgument<String>(0)
            if (sql.contains("FROM `documents`")) {
                createMockCursor(
                    columnNames = arrayOf("id", "fileName", "localPath", "extractedTextPath"),
                    rows = listOf(
                        listOf("doc-1", "cams_cas.pdf", sourcePdf.absolutePath, sourceText.absolutePath),
                    ),
                )
            } else {
                createMockCursor(arrayOf("name"), emptyList())
            }
        }

        val dbExportPayload = JSONObject().apply {
            put("metadata", JSONObject().apply {
                put("format", "OpenFolio Database Export")
                put("databaseVersion", 1)
                put("exportedAt", "2026-11-27T10:00:00Z")
            })
            put("databaseVersion", 1)
            put("exportedAt", "2026-11-27T10:00:00Z")
            put("tables", JSONObject().apply {
                put("documents", JSONObject().apply {
                    put("columns", JSONArray(listOf("id", "fileName", "localPath", "extractedTextPath")))
                    put("rows", JSONArray().put(
                        JSONObject().apply {
                            put("id", "doc-1")
                            put("fileName", "cams_cas.pdf")
                            put("localPath", sourcePdf.absolutePath)
                            put("extractedTextPath", sourceText.absolutePath)
                        },
                    ))
                })
                put("assets", JSONObject().apply {
                    put("columns", JSONArray(listOf("id", "displayName")))
                    put("rows", JSONArray().put(
                        JSONObject().apply {
                            put("id", "asset-1")
                            put("displayName", "HDFC Equity Fund")
                        },
                    ))
                })
            })
        }

        val exporter = object : DatabaseExporter {
            override suspend fun getExportMetadata(): DatabaseExportMetadata =
                DatabaseExportMetadata(1, 2, 2L, "", emptyList())

            override suspend fun exportToJson(outputStream: OutputStream): ExportResult {
                outputStream.write(dbExportPayload.toString().toByteArray(StandardCharsets.UTF_8))
                outputStream.flush()
                return ExportResult(2, 2L, 1, "db.json", "2026-11-27T10:00:00Z")
            }

            override suspend fun exportDatabaseToJson(
                db: SupportSQLiteDatabase,
                outputStream: OutputStream,
            ): ExportResult = exportToJson(outputStream)
        }

        val sourceSettings = mock<SettingsRepository>()
        whenever(sourceSettings.getShowValues()).thenReturn(false)
        whenever(sourceSettings.getGeminiModelName()).thenReturn("gemini-3.8-flash")
        whenever(sourceSettings.getGeminiApiKey()).thenReturn("AIza-test-secret")

        val sourceManager = AppBackupManagerImpl(
            database = mock(),
            databaseExporter = exporter,
            settingsRepository = sourceSettings,
            workBaseDir = sourceWorkDir,
            documentsDir = sourceDocsDir,
            extractedTextDir = sourceTextDir,
            dbProvider = { sourceDb },
        )

        val encryptedBytes = ByteArrayOutputStream().use { out ->
            val summary = sourceManager.exportEncryptedBackup(
                outputStream = out,
                password = "strong-passphrase-123",
                includeApiKey = true,
            )
            assertEquals(2, summary.tableCount)
            assertEquals(2L, summary.totalRows)
            assertEquals(1, summary.inboxDocumentFilesCount)
            assertEquals(1, summary.extractedTextFilesCount)
            out.toByteArray()
        }

        // Verify bytes are encrypted (plain JSON/PDF strings must not leak unencrypted)
        val rawAsText = String(encryptedBytes, StandardCharsets.ISO_8859_1)
        assertFalse(rawAsText.contains("AIza-test-secret"))
        assertFalse(rawAsText.contains("HDFC Equity Fund"))
        assertTrue(rawAsText.startsWith("OFBKUP01"))

        // Target environment with brand-new paths
        val targetWorkDir = tempFolder.newFolder("dst_work")
        val targetDocsDir = tempFolder.newFolder("dst_docs")
        val targetTextDir = tempFolder.newFolder("dst_text")
        File(targetDocsDir, "stale.pdf").writeText("stale")

        val targetDb = mock<SupportSQLiteDatabase>()
        whenever(targetDb.version).thenReturn(1)
        whenever(targetDb.query(any<String>())).thenAnswer { invocation ->
            val sql = invocation.getArgument<String>(0)
            when {
                sql.contains("sqlite_master") -> createMockCursor(
                    arrayOf("name"),
                    listOf(listOf("documents"), listOf("assets")),
                )
                sql.contains("PRAGMA table_info(`documents`)") -> createMockCursor(
                    arrayOf("name"),
                    listOf(listOf("id"), listOf("fileName"), listOf("localPath"), listOf("extractedTextPath")),
                )
                sql.contains("PRAGMA table_info(`assets`)") -> createMockCursor(
                    arrayOf("name"),
                    listOf(listOf("id"), listOf("displayName")),
                )
                else -> createMockCursor(emptyArray(), emptyList())
            }
        }

        val boundStringsBySql = mutableMapOf<String, MutableList<Pair<Int, String>>>()
        whenever(targetDb.compileStatement(any())).thenAnswer { invocation ->
            val sql = invocation.getArgument<String>(0)
            val captures = boundStringsBySql.getOrPut(sql) { mutableListOf() }
            mock<SupportSQLiteStatement>().apply {
                whenever(bindString(any(), any())).thenAnswer { bindInv ->
                    captures.add(bindInv.getArgument<Int>(0) to bindInv.getArgument<String>(1))
                    Unit
                }
                whenever(executeInsert()).thenReturn(1L)
            }
        }

        val targetSettings = mock<SettingsRepository>()
        var invalidated = false
        val targetManager = AppBackupManagerImpl(
            database = mock(),
            databaseExporter = exporter,
            settingsRepository = targetSettings,
            workBaseDir = targetWorkDir,
            documentsDir = targetDocsDir,
            extractedTextDir = targetTextDir,
            dbProvider = { targetDb },
            transactionRunner = { _, block -> block() },
            invalidateTables = { invalidated = true },
        )

        val restoreSummary = targetManager.importEncryptedBackup(
            inputStream = ByteArrayInputStream(encryptedBytes),
            password = "strong-passphrase-123",
        )

        assertEquals(2, restoreSummary.tableCount)
        assertEquals(2L, restoreSummary.totalRows)
        assertEquals(1, restoreSummary.restoredDocumentFilesCount)
        assertEquals(1, restoreSummary.restoredExtractedTextCount)
        assertTrue(restoreSummary.restoredSettings)
        assertTrue(invalidated)

        verify(targetDb).execSQL("DELETE FROM `documents`")
        verify(targetDb).execSQL("DELETE FROM `assets`")

        val docBindings = boundStringsBySql.entries.first { it.key.contains("INTO `documents`") }.value
        val boundValues = docBindings.map { it.second }
        val expectedRestoredDocPath = File(targetDocsDir, "doc-1_cams_cas.pdf").absolutePath
        val expectedRestoredTextPath = File(targetTextDir, "doc-1.txt").absolutePath
        assertTrue(boundValues.contains(expectedRestoredDocPath))
        assertTrue(boundValues.contains(expectedRestoredTextPath))

        val restoredPdfFile = File(expectedRestoredDocPath)
        assertTrue(restoredPdfFile.exists())
        assertArrayEquals(sourcePdf.readBytes(), restoredPdfFile.readBytes())
        assertFalse(File(targetDocsDir, "stale.pdf").exists())

        val restoredTextFile = File(expectedRestoredTextPath)
        assertTrue(restoredTextFile.exists())
        assertEquals("HDFC Equity Fund 100 units @ 50.00", restoredTextFile.readText())

        verify(targetSettings).setShowValues(false)
        verify(targetSettings).setGeminiModelName("gemini-3.8-flash")
        verify(targetSettings).setGeminiApiKey("AIza-test-secret")
    }

    @Test(expected = BackupDecryptionException::class)
    fun wrongPasswordThrowsBackupDecryptionExceptionAndLeavesTargetUntouched() = runTest {
        val workDir = tempFolder.newFolder("w1")
        val docsDir = tempFolder.newFolder("d1")
        val textDir = tempFolder.newFolder("t1")
        val existingDoc = File(docsDir, "keep_me.pdf").apply { writeText("keep") }

        val db = mock<SupportSQLiteDatabase>()
        whenever(db.version).thenReturn(1)
        whenever(db.query(any<String>())).thenAnswer { createMockCursor(emptyArray(), emptyList()) }

        val exporter = object : DatabaseExporter {
            override suspend fun getExportMetadata() = DatabaseExportMetadata(1, 0, 0L, "", emptyList())
            override suspend fun exportToJson(outputStream: OutputStream): ExportResult {
                outputStream.write("""{"databaseVersion":1,"tables":{}}""".toByteArray())
                return ExportResult(0, 0L, 1, "db.json", "now")
            }

            override suspend fun exportDatabaseToJson(
                db: SupportSQLiteDatabase,
                outputStream: OutputStream,
            ): ExportResult = exportToJson(outputStream)
        }

        val settings = mock<SettingsRepository>()
        whenever(settings.getShowValues()).thenReturn(true)
        whenever(settings.getGeminiModelName()).thenReturn("gemini-3.8-flash")

        val manager = AppBackupManagerImpl(
            database = mock(),
            databaseExporter = exporter,
            settingsRepository = settings,
            workBaseDir = workDir,
            documentsDir = docsDir,
            extractedTextDir = textDir,
            dbProvider = { db },
            transactionRunner = { _, block -> block() },
        )

        val backupBytes = ByteArrayOutputStream().use { out ->
            manager.exportEncryptedBackup(out, "correct-password-1")
            out.toByteArray()
        }

        try {
            manager.importEncryptedBackup(ByteArrayInputStream(backupBytes), "wrong-password-9")
        } finally {
            verify(db, never()).execSQL(any())
            assertTrue(existingDoc.exists())
        }
    }

    @Test(expected = InvalidBackupFormatException::class)
    fun invalidHeaderThrowsInvalidBackupFormatException() = runTest {
        val workDir = tempFolder.newFolder("w2")
        val manager = AppBackupManagerImpl(
            database = mock(),
            databaseExporter = mock(),
            settingsRepository = mock(),
            workBaseDir = workDir,
            documentsDir = tempFolder.newFolder("d2"),
            extractedTextDir = tempFolder.newFolder("t2"),
            dbProvider = { mock() },
        )
        manager.importEncryptedBackup(
            inputStream = ByteArrayInputStream("NOT_AN_OPENFOLIO_ARCHIVE_1234567890".toByteArray()),
            password = "password123",
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun shortPasswordIsRejectedDuringExport() = runTest {
        val manager = AppBackupManagerImpl(
            database = mock(),
            databaseExporter = mock(),
            settingsRepository = mock(),
            workBaseDir = tempFolder.newFolder("w3"),
            documentsDir = tempFolder.newFolder("d3"),
            extractedTextDir = tempFolder.newFolder("t3"),
            dbProvider = { mock() },
        )
        manager.exportEncryptedBackup(ByteArrayOutputStream(), "123")
    }

    @Test
    fun futureDatabaseVersionBackupIsRejectedWithoutDeletingLocalFiles() = runTest {
        val workDir = tempFolder.newFolder("fv_work")
        val docsDir = tempFolder.newFolder("fv_docs")
        val textDir = tempFolder.newFolder("fv_text")
        val existingDoc = File(docsDir, "keep.pdf").apply { writeText("IMPORTANT_PDF") }

        val futureDb = mock<SupportSQLiteDatabase>()
        whenever(futureDb.version).thenReturn(OpenFolioDatabase.SCHEMA_VERSION + 5)
        whenever(futureDb.query(any<String>())).thenAnswer { createMockCursor(arrayOf("name"), emptyList()) }

        val futurePayload = JSONObject().apply {
            put("metadata", JSONObject().apply {
                put("format", "OpenFolio Database Export")
                put("databaseVersion", OpenFolioDatabase.SCHEMA_VERSION + 5)
                put("exportedAt", "2026-11-27T10:00:00Z")
            })
            put("tables", JSONObject().apply {
                put("assets", JSONObject().apply {
                    put("columns", JSONArray(listOf("id")))
                    put("rows", JSONArray())
                })
            })
        }
        val exporter = object : DatabaseExporter {
            override suspend fun getExportMetadata(): DatabaseExportMetadata =
                DatabaseExportMetadata(1, 0, 0L, "", emptyList())
            override suspend fun exportToJson(outputStream: OutputStream): ExportResult {
                outputStream.write(futurePayload.toString().toByteArray(StandardCharsets.UTF_8))
                return ExportResult(1, 0L, OpenFolioDatabase.SCHEMA_VERSION + 5, "db.json", "2026-11-27T10:00:00Z")
            }
            override suspend fun exportDatabaseToJson(db: SupportSQLiteDatabase, outputStream: OutputStream) =
                exportToJson(outputStream)
        }
        val settings = mock<SettingsRepository>()
        whenever(settings.getShowValues()).thenReturn(true)
        whenever(settings.getGeminiModelName()).thenReturn("gemini-3.8-flash")

        val manager = AppBackupManagerImpl(
            database = mock(),
            databaseExporter = exporter,
            settingsRepository = settings,
            workBaseDir = workDir,
            documentsDir = docsDir,
            extractedTextDir = textDir,
            dbProvider = { futureDb },
        )
        val bytes = ByteArrayOutputStream().use { out ->
            manager.exportEncryptedBackup(out, "pass-future-123")
            out.toByteArray()
        }

        val targetDb = mock<SupportSQLiteDatabase>()
        var threw = false
        try {
            manager.importEncryptedBackup(ByteArrayInputStream(bytes), "pass-future-123")
        } catch (_: InvalidBackupFormatException) {
            threw = true
        }
        assertTrue(threw)
        assertTrue(existingDoc.exists())
        assertEquals("IMPORTANT_PDF", existingDoc.readText())
        verify(targetDb, never()).execSQL(any())
    }

    @Test
    fun failedRestoreTransactionPreservesExistingDocumentsAndSidecars() = runTest {
        val srcWork = tempFolder.newFolder("ar_src_w")
        val srcDocs = tempFolder.newFolder("ar_src_d")
        val srcText = tempFolder.newFolder("ar_src_t")
        File(srcDocs, "new_stmt.pdf").writeText("NEW_STATEMENT")

        val srcDb = mock<SupportSQLiteDatabase>()
        whenever(srcDb.version).thenReturn(1)
        whenever(srcDb.query(any<String>())).thenAnswer { inv ->
            if (inv.getArgument<String>(0).contains("FROM `documents`")) {
                createMockCursor(
                    arrayOf("id", "fileName", "localPath", "extractedTextPath"),
                    listOf(listOf("doc-new", "new_stmt.pdf", File(srcDocs, "new_stmt.pdf").absolutePath, null)),
                )
            } else {
                createMockCursor(arrayOf("name"), emptyList())
            }
        }
        val payload = JSONObject().apply {
            put("metadata", JSONObject().apply {
                put("format", "OpenFolio Database Export")
                put("databaseVersion", 1)
                put("exportedAt", "2026-11-27T10:00:00Z")
            })
            put("tables", JSONObject().apply {
                put("documents", JSONObject().apply {
                    put("columns", JSONArray(listOf("id", "fileName", "localPath")))
                    put("rows", JSONArray().put(JSONObject().apply {
                        put("id", "doc-new")
                        put("fileName", "new_stmt.pdf")
                        put("localPath", "/old/new_stmt.pdf")
                    }))
                })
            })
        }
        val exporter = object : DatabaseExporter {
            override suspend fun getExportMetadata(): DatabaseExportMetadata =
                DatabaseExportMetadata(1, 1, 1L, "", emptyList())
            override suspend fun exportToJson(outputStream: OutputStream): ExportResult {
                outputStream.write(payload.toString().toByteArray(StandardCharsets.UTF_8))
                return ExportResult(1, 1L, 1, "db.json", "2026-11-27T10:00:00Z")
            }
            override suspend fun exportDatabaseToJson(db: SupportSQLiteDatabase, outputStream: OutputStream) =
                exportToJson(outputStream)
        }
        val settings = mock<SettingsRepository>()
        whenever(settings.getShowValues()).thenReturn(true)
        whenever(settings.getGeminiModelName()).thenReturn("gemini-3.8-flash")

        val exportMgr = AppBackupManagerImpl(
            database = mock(),
            databaseExporter = exporter,
            settingsRepository = settings,
            workBaseDir = srcWork,
            documentsDir = srcDocs,
            extractedTextDir = srcText,
            dbProvider = { srcDb },
        )
        val bytes = ByteArrayOutputStream().use { out ->
            exportMgr.exportEncryptedBackup(out, "atomic-pass-123")
            out.toByteArray()
        }

        val dstWork = tempFolder.newFolder("ar_dst_w")
        val dstDocs = tempFolder.newFolder("ar_dst_d")
        val dstText = tempFolder.newFolder("ar_dst_t")
        val existingDoc = File(dstDocs, "original_keep.pdf").apply { writeText("KEEP_ORIGINAL_DOC") }
        val existingTxt = File(dstText, "original.txt").apply { writeText("KEEP_ORIGINAL_TEXT") }

        val failingDb = mock<SupportSQLiteDatabase>()
        whenever(failingDb.query(any<String>())).thenAnswer { createMockCursor(arrayOf("name"), emptyList()) }
        val failingMgr = AppBackupManagerImpl(
            database = mock(),
            databaseExporter = exporter,
            settingsRepository = settings,
            workBaseDir = dstWork,
            documentsDir = dstDocs,
            extractedTextDir = dstText,
            dbProvider = { failingDb },
            transactionRunner = { _, _ -> throw IllegalStateException("Simulated disk full mid-DB commit") },
        )

        var threw = false
        try {
            failingMgr.importEncryptedBackup(ByteArrayInputStream(bytes), "atomic-pass-123")
        } catch (_: IllegalStateException) {
            threw = true
        }
        assertTrue(threw)
        assertTrue(existingDoc.exists())
        assertEquals("KEEP_ORIGINAL_DOC", existingDoc.readText())
        assertTrue(existingTxt.exists())
        assertEquals("KEEP_ORIGINAL_TEXT", existingTxt.readText())
    }

    @Test
    fun encryptedLocalFilesRoundTripThroughBackupWithLocalFileCrypto() = runTest {
        val crypto = com.openfolio.core.data.local.LocalFileCrypto(
            keyProvider = { javax.crypto.spec.SecretKeySpec(ByteArray(32) { 0x62 }, "AES") },
        )
        val srcWork = tempFolder.newFolder("enc_src_w")
        val srcDocs = tempFolder.newFolder("enc_src_d")
        val srcText = tempFolder.newFolder("enc_src_t")

        val encDoc = File(srcDocs, "secret.pdf").apply {
            crypto.encryptingOutputStream(outputStream()).use {
                it.write("%PDF-1.7-CONFIDENTIAL".toByteArray(StandardCharsets.UTF_8))
            }
        }
        val encTxt = File(srcText, "doc-enc.txt").apply {
            crypto.encryptingOutputStream(outputStream()).use {
                it.write("<<<OPENFOLIO_PAGE:1>>>\nSecret Folio Content".toByteArray(StandardCharsets.UTF_8))
            }
        }

        val srcDb = mock<SupportSQLiteDatabase>()
        whenever(srcDb.version).thenReturn(1)
        whenever(srcDb.query(any<String>())).thenAnswer { inv ->
            if (inv.getArgument<String>(0).contains("FROM `documents`")) {
                createMockCursor(
                    arrayOf("id", "fileName", "localPath", "extractedTextPath"),
                    listOf(listOf("doc-enc", "secret.pdf", encDoc.absolutePath, encTxt.absolutePath)),
                )
            } else {
                createMockCursor(arrayOf("name"), emptyList())
            }
        }
        val payload = JSONObject().apply {
            put("metadata", JSONObject().apply {
                put("format", "OpenFolio Database Export")
                put("databaseVersion", 1)
                put("exportedAt", "2026-11-27T10:00:00Z")
            })
            put("tables", JSONObject().apply {
                put("documents", JSONObject().apply {
                    put("columns", JSONArray(listOf("id", "fileName", "localPath", "extractedTextPath")))
                    put("rows", JSONArray().put(JSONObject().apply {
                        put("id", "doc-enc")
                        put("fileName", "secret.pdf")
                        put("localPath", encDoc.absolutePath)
                        put("extractedTextPath", encTxt.absolutePath)
                    }))
                })
            })
        }
        val exporter = object : DatabaseExporter {
            override suspend fun getExportMetadata(): DatabaseExportMetadata =
                DatabaseExportMetadata(1, 1, 1L, "", emptyList())
            override suspend fun exportToJson(outputStream: OutputStream): ExportResult {
                outputStream.write(payload.toString().toByteArray(StandardCharsets.UTF_8))
                return ExportResult(1, 1L, 1, "db.json", "2026-11-27T10:00:00Z")
            }
            override suspend fun exportDatabaseToJson(db: SupportSQLiteDatabase, outputStream: OutputStream) =
                exportToJson(outputStream)
        }
        val settings = mock<SettingsRepository>()
        whenever(settings.getShowValues()).thenReturn(true)
        whenever(settings.getGeminiModelName()).thenReturn("gemini-3.8-flash")

        val srcMgr = AppBackupManagerImpl(
            database = mock(),
            databaseExporter = exporter,
            settingsRepository = settings,
            workBaseDir = srcWork,
            documentsDir = srcDocs,
            extractedTextDir = srcText,
            dbProvider = { srcDb },
            fileCrypto = crypto,
        )
        val backupBytes = ByteArrayOutputStream().use { out ->
            srcMgr.exportEncryptedBackup(out, "enc-backup-pass")
            out.toByteArray()
        }

        val dstWork = tempFolder.newFolder("enc_dst_w")
        val dstDocs = tempFolder.newFolder("enc_dst_d")
        val dstText = tempFolder.newFolder("enc_dst_t")
        val dstDb = mock<SupportSQLiteDatabase>()
        whenever(dstDb.query(any<String>())).thenAnswer { inv ->
            val sql = inv.getArgument<String>(0)
            when {
                sql.contains("sqlite_master") -> createMockCursor(arrayOf("name"), listOf(listOf("documents")))
                sql.contains("PRAGMA table_info(`documents`)") -> createMockCursor(
                    arrayOf("name"),
                    listOf(listOf("id"), listOf("fileName"), listOf("localPath"), listOf("extractedTextPath")),
                )
                else -> createMockCursor(arrayOf("name"), emptyList())
            }
        }
        whenever(dstDb.compileStatement(any())).thenReturn(mock<SupportSQLiteStatement>())

        val dstMgr = AppBackupManagerImpl(
            database = mock(),
            databaseExporter = exporter,
            settingsRepository = settings,
            workBaseDir = dstWork,
            documentsDir = dstDocs,
            extractedTextDir = dstText,
            dbProvider = { dstDb },
            transactionRunner = { _, block -> block() },
            fileCrypto = crypto,
        )
        val restored = dstMgr.importEncryptedBackup(ByteArrayInputStream(backupBytes), "enc-backup-pass")
        assertEquals(1, restored.restoredDocumentFilesCount)
        assertEquals(1, restored.restoredExtractedTextCount)

        val restoredDoc = File(dstDocs, "doc-enc_secret.pdf")
        assertTrue(restoredDoc.exists())
        assertTrue(crypto.isEncrypted(restoredDoc))
        assertEquals(
            "%PDF-1.7-CONFIDENTIAL",
            crypto.openPlaintextInputStream(restoredDoc).bufferedReader().readText(),
        )
        val restoredTxt = File(dstText, "doc-enc.txt")
        assertTrue(restoredTxt.exists())
        assertTrue(crypto.isEncrypted(restoredTxt))
        assertTrue(
            crypto.openPlaintextInputStream(restoredTxt).bufferedReader().readText().contains("Secret Folio Content"),
        )
    }

    private fun createMockCursor(
        columnNames: Array<String>,
        rows: List<List<Any?>>,
    ): Cursor {
        val cursor = mock<Cursor>()
        var idx = -1
        whenever(cursor.columnNames).thenReturn(columnNames)
        whenever(cursor.columnCount).thenReturn(columnNames.size)
        whenever(cursor.getColumnIndex(any())).thenAnswer { inv ->
            columnNames.indexOf(inv.getArgument<String>(0))
        }
        whenever(cursor.getColumnIndexOrThrow(any())).thenAnswer { inv ->
            val col = inv.getArgument<String>(0)
            columnNames.indexOf(col).also {
                if (it < 0) throw IllegalArgumentException("Missing column $col")
            }
        }
        whenever(cursor.moveToNext()).thenAnswer {
            idx++
            idx < rows.size
        }
        whenever(cursor.isNull(any())).thenAnswer { inv ->
            rows[idx][inv.getArgument(0)] == null
        }
        whenever(cursor.getString(any())).thenAnswer { inv ->
            rows[idx][inv.getArgument(0)]?.toString()
        }
        whenever(cursor.close()).thenAnswer {
            idx = -1
            null
        }
        return cursor
    }
}
