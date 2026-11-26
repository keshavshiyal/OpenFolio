package com.openfolio.core.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import java.util.UUID

enum class DocumentStatus {
    PENDING_OCR,
    OCR_COMPLETE,
    OCR_FAILED,
    READY_FOR_MAPPING,
    AI_PAUSED,
    AI_COMPLETE,
    COMPLETED,
    PENDING,
    PROCESSING,
    FAILED,
}

@Entity(tableName = "documents", indices = [Index(value = ["fileHash"])])
data class DocumentEntity(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val fileName: String,
    val localPath: String,
    val fileHash: String,
    val fileSize: Long,
    val importDate: Long,
    val extractionStatus: ExtractionStatus = ExtractionStatus.PENDING_OCR,
    val extractedText: String? = null,
    val status: String = extractionStatus.name,
    val assetClass: String = "STOCK",
    @ColumnInfo(defaultValue = "NULL") val extractedTextPath: String? = null,
    @ColumnInfo(defaultValue = "0") val pageCount: Int = 0,
    @ColumnInfo(defaultValue = "0") val processedPages: Int = 0,
    @ColumnInfo(defaultValue = "NULL") val carryOverContextJson: String? = null,
    @ColumnInfo(defaultValue = "NULL") val extractionError: String? = null,
) {
    fun getAssetClassEnum(): AssetClass = try {
        AssetClass.valueOf(assetClass.trim().uppercase())
    } catch (_: Exception) {
        AssetClass.STOCK
    }
}
