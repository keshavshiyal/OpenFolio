package com.openfolio.core.data.local.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.ColumnInfo
import java.util.UUID

@Entity(
    tableName = "draft_transactions",
    foreignKeys = [
        ForeignKey(
            entity = DocumentEntity::class,
            parentColumns = ["id"],
            childColumns = ["documentId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["documentId"])],
)
data class DraftTransactionEntity(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val documentId: String,
    val assetName: String,
    val transactionType: String,
    val quantity: String,
    val price: String,
    val netAmount: String,
    val confidence: Int,
    val isAccepted: Boolean = false,
    @ColumnInfo(defaultValue = "0") val isManual: Boolean = false,
    @ColumnInfo(defaultValue = "''") val fingerprint: String = "",
    val tradeDate: Long? = null,
) {
    init {
        require(confidence in 0..100) { "Confidence must be between 0 and 100." }
    }
}
