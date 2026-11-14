package com.openfolio.core.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.TypeConverters
import java.math.BigDecimal

@Entity(tableName = "transactions")
@TypeConverters(LedgerConverters::class)
data class Transaction(
    @PrimaryKey val id: String,
    val assetId: String?,
    val type: TransactionType,
    val tradeDate: Long,
    val settlementDate: Long?,
    val quantity: BigDecimal,
    val grossAmount: BigDecimal,
    val fees: BigDecimal,
    val taxes: BigDecimal,
    val netAmount: BigDecimal,
    val currency: String,
    val fingerprint: String,
    val documentId: String?,
    val sourcePage: Int?,
    val confidenceScore: Float?,
    val status: TransactionStatus,
    val isManual: Boolean,
    val fxRate: BigDecimal?,
    val baseCurrencyAmount: BigDecimal?,
)
