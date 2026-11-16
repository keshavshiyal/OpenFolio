package com.openfolio.core.data.local.export

data class ColumnSchema(
    val cid: Int,
    val name: String,
    val type: String,
    val notNull: Boolean,
    val defaultValue: String?,
    val primaryKey: Boolean,
)

data class ForeignKeySchema(
    val id: Int,
    val seq: Int,
    val table: String,
    val from: String,
    val to: String?,
    val onUpdate: String,
    val onDelete: String,
)

data class IndexSchema(
    val name: String,
    val sql: String?,
)

data class TableSummary(
    val name: String,
    val rowCount: Long,
    val columnCount: Int,
)

data class DatabaseExportMetadata(
    val databaseVersion: Int,
    val tableCount: Int,
    val totalRowCount: Long,
    val roomIdentityHash: String? = null,
    val tables: List<TableSummary> = emptyList(),
)

data class ExportResult(
    val tableCount: Int,
    val totalRowCount: Long,
    val databaseVersion: Int,
    val defaultFileName: String,
    val exportedAt: String,
)
