package com.openfolio.core.data.local.export

import androidx.sqlite.db.SupportSQLiteDatabase
import java.io.OutputStream

interface DatabaseExporter {
    /**
     * Inspects current database summary metadata without performing a full export.
     */
    suspend fun getExportMetadata(): DatabaseExportMetadata

    /**
     * Streams the entire database in structured JSON format to the provided [outputStream].
     * Future-proof: dynamically inspects sqlite_master and table_info to handle any
     * added, altered, or migrated tables and columns without hardcoded schemas.
     */
    suspend fun exportToJson(outputStream: OutputStream): ExportResult

    /**
     * Allows exporting a specific [SupportSQLiteDatabase] instance directly (useful for tests).
     */
    suspend fun exportDatabaseToJson(db: SupportSQLiteDatabase, outputStream: OutputStream): ExportResult
}
