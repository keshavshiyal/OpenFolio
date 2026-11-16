package com.openfolio.core.data.local.export

import android.database.Cursor
import androidx.sqlite.db.SupportSQLiteDatabase
import com.openfolio.core.data.local.OpenFolioDatabase
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.io.ByteArrayOutputStream

class DatabaseExporterTest {

    private fun createMockCursor(
        columnNames: Array<String>,
        rows: List<List<Any?>>,
    ): Cursor {
        val cursor = mock<Cursor>()
        var currentIndex = -1

        whenever(cursor.columnNames).thenReturn(columnNames)
        whenever(cursor.columnCount).thenReturn(columnNames.size)

        whenever(cursor.getColumnIndex(any())).thenAnswer { invocation ->
            val name = invocation.getArgument<String>(0)
            columnNames.indexOf(name)
        }
        whenever(cursor.getColumnIndexOrThrow(any())).thenAnswer { invocation ->
            val name = invocation.getArgument<String>(0)
            val idx = columnNames.indexOf(name)
            if (idx < 0) throw IllegalArgumentException("Column '$name' not found in ${columnNames.toList()}")
            idx
        }

        whenever(cursor.moveToNext()).thenAnswer {
            currentIndex++
            currentIndex < rows.size
        }

        whenever(cursor.isNull(any())).thenAnswer { invocation ->
            val col = invocation.getArgument<Int>(0)
            rows[currentIndex][col] == null
        }

        whenever(cursor.getString(any())).thenAnswer { invocation ->
            val col = invocation.getArgument<Int>(0)
            rows[currentIndex][col]?.toString()
        }

        whenever(cursor.getLong(any())).thenAnswer { invocation ->
            val col = invocation.getArgument<Int>(0)
            (rows[currentIndex][col] as? Number)?.toLong() ?: 0L
        }

        whenever(cursor.getInt(any())).thenAnswer { invocation ->
            val col = invocation.getArgument<Int>(0)
            (rows[currentIndex][col] as? Number)?.toInt() ?: 0
        }

        whenever(cursor.getDouble(any())).thenAnswer { invocation ->
            val col = invocation.getArgument<Int>(0)
            (rows[currentIndex][col] as? Number)?.toDouble() ?: 0.0
        }

        whenever(cursor.getBlob(any())).thenAnswer { invocation ->
            val col = invocation.getArgument<Int>(0)
            rows[currentIndex][col] as? ByteArray
        }

        whenever(cursor.getType(any())).thenAnswer { invocation ->
            val col = invocation.getArgument<Int>(0)
            when (rows[currentIndex][col]) {
                null -> Cursor.FIELD_TYPE_NULL
                is Long, is Int, is Short, is Byte -> Cursor.FIELD_TYPE_INTEGER
                is Double, is Float -> Cursor.FIELD_TYPE_FLOAT
                is ByteArray -> Cursor.FIELD_TYPE_BLOB
                else -> Cursor.FIELD_TYPE_STRING
            }
        }

        whenever(cursor.close()).thenAnswer {
            currentIndex = -1
            null
        }

        return cursor
    }

    @Test
    fun exportDatabaseToJsonIntrospectsSchemaAndExportsAllRows() = runTest {
        val db = mock<SupportSQLiteDatabase>()
        whenever(db.version).thenReturn(9)

        whenever(db.query(any<String>())).thenAnswer { invocation ->
            val sql = invocation.getArgument<String>(0)
            when {
                sql.contains("sqlite_master") && sql.contains("type = 'table'") -> createMockCursor(
                    columnNames = arrayOf("name", "sql"),
                    rows = listOf(
                        listOf("accounts", "CREATE TABLE `accounts` (`id` TEXT NOT NULL, `name` TEXT NOT NULL, PRIMARY KEY(`id`))"),
                        listOf("future_altered_table", "CREATE TABLE `future_altered_table` (`id` TEXT, `new_col` TEXT)"),
                    ),
                )
                sql.contains("room_master_table") -> createMockCursor(
                    columnNames = arrayOf("identity_hash"),
                    rows = listOf(listOf("hash_xyz_789")),
                )
                sql.contains("PRAGMA table_info(`accounts`)") -> createMockCursor(
                    columnNames = arrayOf("cid", "name", "type", "notnull", "dflt_value", "pk"),
                    rows = listOf(
                        listOf(0, "id", "TEXT", 1, null, 1),
                        listOf(1, "name", "TEXT", 1, null, 0),
                    ),
                )
                sql.contains("PRAGMA table_info(`future_altered_table`)") -> createMockCursor(
                    columnNames = arrayOf("cid", "name", "type", "notnull", "dflt_value", "pk"),
                    rows = listOf(
                        listOf(0, "id", "TEXT", 0, null, 1),
                        listOf(1, "new_col", "INTEGER", 0, null, 0),
                        listOf(2, "future_added_column", "REAL", 0, "0.0", 0),
                    ),
                )
                sql.contains("SELECT COUNT(*) FROM `accounts`") -> createMockCursor(
                    columnNames = arrayOf("count"),
                    rows = listOf(listOf(1L)),
                )
                sql.contains("SELECT COUNT(*) FROM `future_altered_table`") -> createMockCursor(
                    columnNames = arrayOf("count"),
                    rows = listOf(listOf(2L)),
                )
                sql.contains("SELECT * FROM `accounts`") -> createMockCursor(
                    columnNames = arrayOf("id", "name"),
                    rows = listOf(
                        listOf("acc_1", "HDFC Savings"),
                    ),
                )
                sql.contains("SELECT * FROM `future_altered_table`") -> createMockCursor(
                    columnNames = arrayOf("id", "new_col", "future_added_column"),
                    rows = listOf(
                        listOf("row_1", 100L, 99.5),
                        listOf("row_2", null, 0.0),
                    ),
                )
                else -> createMockCursor(arrayOf("empty"), emptyList())
            }
        }

        val roomDb = mock<OpenFolioDatabase>()
        val exporter = DatabaseExporterImpl(roomDb)
        val outputStream = ByteArrayOutputStream()

        val result = exporter.exportDatabaseToJson(db, outputStream)

        assertEquals(2, result.tableCount)
        assertEquals(3L, result.totalRowCount)
        assertEquals(9, result.databaseVersion)
        assertTrue(result.defaultFileName.startsWith("openfolio_db_v9_"))

        // Parse and verify output JSON structure
        val jsonString = outputStream.toString(Charsets.UTF_8.name())
        val json = JSONObject(jsonString)

        // Verify metadata
        val metadata = json.getJSONObject("metadata")
        assertEquals(9, metadata.getInt("databaseVersion"))
        assertEquals(2, metadata.getInt("totalTables"))
        assertEquals(3, metadata.getLong("totalRows"))
        assertEquals("hash_xyz_789", metadata.getString("roomIdentityHash"))

        // Verify tables object
        val tables = json.getJSONObject("tables")
        assertTrue(tables.has("accounts"))
        assertTrue(tables.has("future_altered_table"))

        // Verify accounts table
        val accountsTable = tables.getJSONObject("accounts")
        assertEquals(1, accountsTable.getInt("rowCount"))
        val accountsCols = accountsTable.getJSONArray("columns")
        assertEquals(2, accountsCols.length())
        assertEquals("id", accountsCols.getJSONObject(0).getString("name"))
        val accountsRows = accountsTable.getJSONArray("rows")
        assertEquals(1, accountsRows.length())
        assertEquals("acc_1", accountsRows.getJSONObject(0).getString("id"))
        assertEquals("HDFC Savings", accountsRows.getJSONObject(0).getString("name"))

        // Verify future_altered_table with added dynamic columns
        val futureTable = tables.getJSONObject("future_altered_table")
        assertEquals(2, futureTable.getInt("rowCount"))
        val futureCols = futureTable.getJSONArray("columns")
        assertEquals(3, futureCols.length())
        assertEquals("future_added_column", futureCols.getJSONObject(2).getString("name"))

        val futureRows = futureTable.getJSONArray("rows")
        assertEquals(2, futureRows.length())
        val row1 = futureRows.getJSONObject(0)
        assertEquals("row_1", row1.getString("id"))
        assertEquals(100, row1.getInt("new_col"))
        assertEquals(99.5, row1.getDouble("future_added_column"), 0.01)

        val row2 = futureRows.getJSONObject(1)
        assertEquals("row_2", row2.getString("id"))
        assertTrue(row2.isNull("new_col"))
        assertEquals(0.0, row2.getDouble("future_added_column"), 0.01)
    }

    @Test
    fun exportHandlesBlobsAndEmptyTables() = runTest {
        val db = mock<SupportSQLiteDatabase>()
        whenever(db.version).thenReturn(9)

        whenever(db.query(any<String>())).thenAnswer { invocation ->
            val sql = invocation.getArgument<String>(0)
            when {
                sql.contains("sqlite_master") && sql.contains("type = 'table'") -> createMockCursor(
                    columnNames = arrayOf("name", "sql"),
                    rows = listOf(
                        listOf("binary_data", "CREATE TABLE `binary_data` (`id` TEXT, `blob_col` BLOB)"),
                        listOf("empty_table", "CREATE TABLE `empty_table` (`id` TEXT)"),
                    ),
                )
                sql.contains("PRAGMA table_info(`binary_data`)") -> createMockCursor(
                    columnNames = arrayOf("cid", "name", "type", "notnull", "dflt_value", "pk"),
                    rows = listOf(
                        listOf(0, "id", "TEXT", 1, null, 1),
                        listOf(1, "blob_col", "BLOB", 0, null, 0),
                    ),
                )
                sql.contains("PRAGMA table_info(`empty_table`)") -> createMockCursor(
                    columnNames = arrayOf("cid", "name", "type", "notnull", "dflt_value", "pk"),
                    rows = listOf(listOf(0, "id", "TEXT", 1, null, 1)),
                )
                sql.contains("SELECT COUNT(*) FROM `binary_data`") -> createMockCursor(
                    columnNames = arrayOf("count"),
                    rows = listOf(listOf(1L)),
                )
                sql.contains("SELECT COUNT(*) FROM `empty_table`") -> createMockCursor(
                    columnNames = arrayOf("count"),
                    rows = listOf(listOf(0L)),
                )
                sql.contains("SELECT * FROM `binary_data`") -> createMockCursor(
                    columnNames = arrayOf("id", "blob_col"),
                    rows = listOf(
                        listOf("b1", byteArrayOf(1, 2, 3, 4)),
                    ),
                )
                sql.contains("SELECT * FROM `empty_table`") -> createMockCursor(arrayOf("id"), emptyList())
                else -> createMockCursor(arrayOf("empty"), emptyList())
            }
        }

        val roomDb = mock<OpenFolioDatabase>()
        val exporter = DatabaseExporterImpl(roomDb)
        val outputStream = ByteArrayOutputStream()

        val result = exporter.exportDatabaseToJson(db, outputStream)

        assertEquals(2, result.tableCount)
        assertEquals(1L, result.totalRowCount)

        val json = JSONObject(outputStream.toString(Charsets.UTF_8.name()))
        val tables = json.getJSONObject("tables")

        // Check empty table
        val emptyTable = tables.getJSONObject("empty_table")
        assertEquals(0, emptyTable.getInt("rowCount"))
        assertEquals(0, emptyTable.getJSONArray("rows").length())

        // Check blob encoding
        val binaryTable = tables.getJSONObject("binary_data")
        val binaryRow = binaryTable.getJSONArray("rows").getJSONObject(0)
        assertNotNull(binaryRow.getString("blob_col"))
        assertTrue(binaryRow.getString("blob_col").startsWith("base64:"))
    }
}
