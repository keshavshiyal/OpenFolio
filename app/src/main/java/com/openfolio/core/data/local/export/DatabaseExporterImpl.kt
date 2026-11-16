package com.openfolio.core.data.local.export

import android.database.Cursor
import androidx.sqlite.db.SupportSQLiteDatabase
import com.openfolio.core.data.local.OpenFolioDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedWriter
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Base64
import javax.inject.Inject

class DatabaseExporterImpl @Inject constructor(
    private val database: OpenFolioDatabase,
) : DatabaseExporter {

    override suspend fun getExportMetadata(): DatabaseExportMetadata = withContext(Dispatchers.IO) {
        val db = database.openHelper.readableDatabase
        inspectMetadata(db)
    }

    override suspend fun exportToJson(outputStream: OutputStream): ExportResult = withContext(Dispatchers.IO) {
        val db = database.openHelper.readableDatabase
        exportDatabaseToJson(db, outputStream)
    }

    override suspend fun exportDatabaseToJson(
        db: SupportSQLiteDatabase,
        outputStream: OutputStream,
    ): ExportResult = withContext(Dispatchers.IO) {
        val now = Instant.now()
        val isoTimestamp = DateTimeFormatter.ISO_INSTANT.format(now)
        val fileTimestamp = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss")
            .withZone(ZoneOffset.UTC)
            .format(now)

        val dbVersion = db.version
        val defaultFileName = "openfolio_db_v${dbVersion}_${fileTimestamp}.json"

        val tableEntries = getTableEntries(db)
        val roomIdentityHash = getRoomIdentityHash(db)

        val tableSummaries = mutableListOf<TableSummary>()
        val rowCounts = mutableMapOf<String, Long>()
        var totalRows = 0L

        // Inspect summaries and counts in a single pass
        for ((tableName, _) in tableEntries) {
            val rowCount = getTableRowCount(db, tableName)
            val columns = getColumnSchemas(db, tableName)
            rowCounts[tableName] = rowCount
            totalRows += rowCount
            tableSummaries.add(
                TableSummary(
                    name = tableName,
                    rowCount = rowCount,
                    columnCount = columns.size,
                )
            )
        }

        val writer = BufferedWriter(OutputStreamWriter(outputStream, StandardCharsets.UTF_8))
        val jsonWriter = JsonStreamWriter(writer, indent = "  ")

        jsonWriter.use { jw ->
            jw.beginObject()

            // Root metadata
            jw.name("metadata").beginObject()
            jw.name("format").value("OpenFolio Database Export")
            jw.name("exportVersion").value(1L)
            jw.name("databaseVersion").value(dbVersion.toLong())
            jw.name("exportedAt").value(isoTimestamp)
            if (roomIdentityHash != null) {
                jw.name("roomIdentityHash").value(roomIdentityHash)
            }
            jw.name("totalTables").value(tableEntries.size.toLong())
            jw.name("totalRows").value(totalRows)
            jw.endObject() // end metadata

            // Tables section
            jw.name("tables").beginObject()

            for ((tableName, createSql) in tableEntries) {
                jw.name(tableName).beginObject()
                if (createSql != null) {
                    jw.name("createSql").value(createSql)
                }

                val rowCount = rowCounts[tableName] ?: 0L
                jw.name("rowCount").value(rowCount)

                // Columns schema
                val columns = getColumnSchemas(db, tableName)
                jw.name("columns").beginArray()
                for (col in columns) {
                    jw.beginObject()
                    jw.name("cid").value(col.cid.toLong())
                    jw.name("name").value(col.name)
                    jw.name("type").value(col.type)
                    jw.name("notNull").value(col.notNull)
                    jw.name("defaultValue").value(col.defaultValue)
                    jw.name("primaryKey").value(col.primaryKey)
                    jw.endObject()
                }
                jw.endArray() // end columns

                // Foreign keys schema
                val fks = getForeignKeySchemas(db, tableName)
                if (fks.isNotEmpty()) {
                    jw.name("foreignKeys").beginArray()
                    for (fk in fks) {
                        jw.beginObject()
                        jw.name("id").value(fk.id.toLong())
                        jw.name("seq").value(fk.seq.toLong())
                        jw.name("table").value(fk.table)
                        jw.name("from").value(fk.from)
                        jw.name("to").value(fk.to)
                        jw.name("onUpdate").value(fk.onUpdate)
                        jw.name("onDelete").value(fk.onDelete)
                        jw.endObject()
                    }
                    jw.endArray()
                }

                // Indices
                val indices = getIndexSchemas(db, tableName)
                if (indices.isNotEmpty()) {
                    jw.name("indices").beginArray()
                    for (idx in indices) {
                        jw.beginObject()
                        jw.name("name").value(idx.name)
                        jw.name("sql").value(idx.sql)
                        jw.endObject()
                    }
                    jw.endArray()
                }

                // Rows array
                jw.name("rows").beginArray()
                streamRows(db, tableName, jw)
                jw.endArray() // end rows

                jw.endObject() // end tableName object
            }

            jw.endObject() // end tables
            jw.endObject() // end root
            jw.flush()
        }

        ExportResult(
            tableCount = tableEntries.size,
            totalRowCount = totalRows,
            databaseVersion = dbVersion,
            defaultFileName = defaultFileName,
            exportedAt = isoTimestamp,
        )
    }

    private fun inspectMetadata(db: SupportSQLiteDatabase): DatabaseExportMetadata {
        val tableEntries = getTableEntries(db)
        val roomIdentityHash = getRoomIdentityHash(db)
        var totalRows = 0L
        val summaries = mutableListOf<TableSummary>()

        for ((tableName, _) in tableEntries) {
            val count = getTableRowCount(db, tableName)
            val columns = getColumnSchemas(db, tableName)
            totalRows += count
            summaries.add(
                TableSummary(
                    name = tableName,
                    rowCount = count,
                    columnCount = columns.size,
                )
            )
        }

        return DatabaseExportMetadata(
            databaseVersion = db.version,
            tableCount = tableEntries.size,
            totalRowCount = totalRows,
            roomIdentityHash = roomIdentityHash,
            tables = summaries,
        )
    }

    private fun getTableEntries(db: SupportSQLiteDatabase): List<Pair<String, String?>> {
        val list = mutableListOf<Pair<String, String?>>()
        val query = """
            SELECT name, sql FROM sqlite_master 
            WHERE type = 'table' 
              AND name NOT LIKE 'sqlite_%' 
              AND name NOT LIKE 'android_metadata' 
              AND name != 'room_master_table' 
            ORDER BY name ASC
        """.trimIndent()

        db.query(query).use { cursor ->
            val nameCol = cursor.getColumnIndexOrThrow("name")
            val sqlCol = cursor.getColumnIndexOrThrow("sql")
            while (cursor.moveToNext()) {
                val name = cursor.getString(nameCol)
                val sql = if (cursor.isNull(sqlCol)) null else cursor.getString(sqlCol)
                list.add(name to sql)
            }
        }
        return list
    }

    private fun getRoomIdentityHash(db: SupportSQLiteDatabase): String? {
        return try {
            val query = "SELECT identity_hash FROM room_master_table LIMIT 1"
            db.query(query).use { cursor ->
                if (cursor.moveToNext()) cursor.getString(0) else null
            }
        } catch (_: Throwable) {
            null
        }
    }

    private fun getColumnSchemas(db: SupportSQLiteDatabase, tableName: String): List<ColumnSchema> {
        val list = mutableListOf<ColumnSchema>()
        try {
            db.query("PRAGMA table_info(`$tableName`)").use { cursor ->
                val cidCol = cursor.getColumnIndex("cid")
                val nameCol = cursor.getColumnIndex("name")
                val typeCol = cursor.getColumnIndex("type")
                val notNullCol = cursor.getColumnIndex("notnull")
                val dfltCol = cursor.getColumnIndex("dflt_value")
                val pkCol = cursor.getColumnIndex("pk")

                while (cursor.moveToNext()) {
                    list.add(
                        ColumnSchema(
                            cid = if (cidCol >= 0) cursor.getInt(cidCol) else 0,
                            name = if (nameCol >= 0) cursor.getString(nameCol) else "",
                            type = if (typeCol >= 0) cursor.getString(typeCol) else "",
                            notNull = if (notNullCol >= 0) cursor.getInt(notNullCol) == 1 else false,
                            defaultValue = if (dfltCol >= 0 && !cursor.isNull(dfltCol)) cursor.getString(dfltCol) else null,
                            primaryKey = if (pkCol >= 0) cursor.getInt(pkCol) > 0 else false,
                        )
                    )
                }
            }
        } catch (_: Throwable) {
            // Pragma failed, return empty
        }
        return list
    }

    private fun getForeignKeySchemas(db: SupportSQLiteDatabase, tableName: String): List<ForeignKeySchema> {
        val list = mutableListOf<ForeignKeySchema>()
        try {
            db.query("PRAGMA foreign_key_list(`$tableName`)").use { cursor ->
                val idCol = cursor.getColumnIndex("id")
                val seqCol = cursor.getColumnIndex("seq")
                val tableCol = cursor.getColumnIndex("table")
                val fromCol = cursor.getColumnIndex("from")
                val toCol = cursor.getColumnIndex("to")
                val onUpdateCol = cursor.getColumnIndex("on_update")
                val onDeleteCol = cursor.getColumnIndex("on_delete")

                while (cursor.moveToNext()) {
                    list.add(
                        ForeignKeySchema(
                            id = if (idCol >= 0) cursor.getInt(idCol) else 0,
                            seq = if (seqCol >= 0) cursor.getInt(seqCol) else 0,
                            table = if (tableCol >= 0) cursor.getString(tableCol) else "",
                            from = if (fromCol >= 0) cursor.getString(fromCol) else "",
                            to = if (toCol >= 0 && !cursor.isNull(toCol)) cursor.getString(toCol) else null,
                            onUpdate = if (onUpdateCol >= 0) cursor.getString(onUpdateCol) else "NO ACTION",
                            onDelete = if (onDeleteCol >= 0) cursor.getString(onDeleteCol) else "NO ACTION",
                        )
                    )
                }
            }
        } catch (_: Throwable) {
            // Optional
        }
        return list
    }

    private fun getIndexSchemas(db: SupportSQLiteDatabase, tableName: String): List<IndexSchema> {
        val list = mutableListOf<IndexSchema>()
        try {
            val query = """
                SELECT name, sql FROM sqlite_master 
                WHERE type = 'index' 
                  AND tbl_name = '$tableName' 
                  AND sql IS NOT NULL 
                ORDER BY name ASC
            """.trimIndent()
            db.query(query).use { cursor ->
                val nameCol = cursor.getColumnIndex("name")
                val sqlCol = cursor.getColumnIndex("sql")
                while (cursor.moveToNext()) {
                    list.add(
                        IndexSchema(
                            name = if (nameCol >= 0) cursor.getString(nameCol) else "",
                            sql = if (sqlCol >= 0 && !cursor.isNull(sqlCol)) cursor.getString(sqlCol) else null,
                        )
                    )
                }
            }
        } catch (_: Throwable) {
            // Optional
        }
        return list
    }

    private fun getTableRowCount(db: SupportSQLiteDatabase, tableName: String): Long {
        return try {
            db.query("SELECT COUNT(*) FROM `$tableName`").use { cursor ->
                if (cursor.moveToNext()) cursor.getLong(0) else 0L
            }
        } catch (_: Throwable) {
            0L
        }
    }

    private fun streamRows(
        db: SupportSQLiteDatabase,
        tableName: String,
        jsonWriter: JsonStreamWriter,
    ) {
        db.query("SELECT * FROM `$tableName`").use { cursor ->
            val columnNames = cursor.columnNames
            val columnCount = cursor.columnCount
            while (cursor.moveToNext()) {
                jsonWriter.beginObject()
                for (i in 0 until columnCount) {
                    val colName = columnNames[i]
                    jsonWriter.name(colName)
                    when (cursor.getType(i)) {
                        Cursor.FIELD_TYPE_NULL -> jsonWriter.nullValue()
                        Cursor.FIELD_TYPE_INTEGER -> jsonWriter.value(cursor.getLong(i))
                        Cursor.FIELD_TYPE_FLOAT -> jsonWriter.value(cursor.getDouble(i))
                        Cursor.FIELD_TYPE_STRING -> jsonWriter.value(cursor.getString(i))
                        Cursor.FIELD_TYPE_BLOB -> {
                            val bytes = cursor.getBlob(i)
                            if (bytes != null) {
                                val b64 = Base64.getEncoder().encodeToString(bytes)
                                jsonWriter.value("base64:$b64")
                            } else {
                                jsonWriter.nullValue()
                            }
                        }
                        else -> {
                            val str = cursor.getString(i)
                            if (str != null) jsonWriter.value(str) else jsonWriter.nullValue()
                        }
                    }
                }
                jsonWriter.endObject()
            }
        }
    }
}
