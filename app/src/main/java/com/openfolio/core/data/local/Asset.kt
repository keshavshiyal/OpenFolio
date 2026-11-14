package com.openfolio.core.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.TypeConverters
import com.openfolio.core.domain.model.AssetClass
import java.math.BigDecimal

@Entity(tableName = "assets")
@TypeConverters(LedgerConverters::class)
data class Asset(
    @PrimaryKey val id: String,
    val accountId: String,
    val name: String,
    val ticker: String?,
    val assetClass: AssetClass,
    val detailsJson: String,
    val currency: String,
    val currentValue: BigDecimal?,
    val updatedAt: Long,
)
