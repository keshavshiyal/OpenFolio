package com.openfolio.core.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import java.util.UUID

enum class DocumentStatus {
    PENDING_OCR,
    OCR_COMPLETE,
    OCR_FAILED,
    READY_FOR_MAPPING,
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
) {
    fun getAssetClassEnum(): AssetClass = try {
        AssetClass.valueOf(assetClass.trim().uppercase())
    } catch (_: Exception) {
        AssetClass.STOCK
    }
}
