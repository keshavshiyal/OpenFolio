package com.openfolio.core.data.local.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import java.time.LocalDate
import java.util.UUID

@Entity(
    tableName = "corporate_action",
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
data class CorporateActionEntity(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val assetId: String,
    val type: String,
    val effectiveDate: LocalDate,
    val ratio: String,
    val detailsJson: String = "{}",
)
