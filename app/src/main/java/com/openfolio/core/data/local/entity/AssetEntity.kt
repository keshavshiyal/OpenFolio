package com.openfolio.core.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.util.UUID

@Entity(tableName = "asset")
data class AssetEntity(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val assetClass: AssetClass,
    val displayName: String,
    val currency: String,
    val identifiersJson: String = "{}",
    val detailsJson: String = "{}",
    val status: AssetStatus = AssetStatus.ACTIVE,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
)
