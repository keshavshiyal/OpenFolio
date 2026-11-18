package com.openfolio.core.data.local.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID

@Entity(
    tableName = "txn",
    foreignKeys = [
        ForeignKey(
            entity = AccountEntity::class,
            parentColumns = ["id"],
            childColumns = ["accountId"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = AssetEntity::class,
            parentColumns = ["id"],
            childColumns = ["assetId"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = DocumentEntity::class,
            parentColumns = ["id"],
            childColumns = ["documentId"],
            onDelete = ForeignKey.SET_NULL,
        ),
    ],
    indices = [
        Index(value = ["fingerprint"]),
        Index(value = ["accountId"]),
        Index(value = ["assetId"]),
        Index(value = ["documentId"]),
    ],
)
data class TransactionEntity(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val accountId: String,
    val assetId: String,
    val type: TransactionType,
    val tradeDate: LocalDate,
    val settlementDate: LocalDate? = null,
    val quantity: BigDecimal? = null,
    val unitPrice: BigDecimal? = null,
    val grossAmount: BigDecimal,
    val fees: BigDecimal? = null,
    val taxes: BigDecimal? = null,
    val netAmount: BigDecimal,
    val currency: String,
    val fxRate: BigDecimal? = null,
    val baseCurrencyAmount: BigDecimal? = null,
    val description: String? = null,
    val notes: String? = null,
    val fingerprint: String,
    val externalRef: String? = null,
    val status: TransactionStatus,
    val documentId: String? = null,
    val sourcePage: Int? = null,
    val confidence: Double? = null,
    val broker: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
)
