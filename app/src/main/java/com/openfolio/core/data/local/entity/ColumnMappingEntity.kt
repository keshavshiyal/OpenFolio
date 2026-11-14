package com.openfolio.core.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import java.util.UUID

@Entity(
    tableName = "column_mappings",
    indices = [Index(value = ["brokerName"], unique = true)],
)
data class ColumnMappingEntity(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val brokerName: String,
    val assetColumn: String,
    val typeColumn: String,
    val quantityColumn: String,
    val priceColumn: String,
    val amountColumn: String,
    val dateColumn: String,
    @ColumnInfo(defaultValue = "1") val headerRowNumber: Int = 1,
) {
    init {
        require(headerRowNumber >= 1) { "Header row number must be at least 1." }
    }
}
