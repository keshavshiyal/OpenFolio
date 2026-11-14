package com.openfolio.core.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import java.util.UUID

enum class DocumentStatus {
    PENDING,
    PROCESSING,
    COMPLETED,
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
    val status: String = DocumentStatus.PENDING.name,
)
