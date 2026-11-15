package com.openfolio.core.data.local.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID

@Entity(
    tableName = "valuation",
    foreignKeys = [
        ForeignKey(
            entity = AssetEntity::class,
            parentColumns = ["id"],
            childColumns = ["assetId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["assetId"]),
    ],
)
data class ValuationEntity(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val assetId: String,
    val date: LocalDate,
    val value: BigDecimal,
    val currency: String,
    val source: String,
)
