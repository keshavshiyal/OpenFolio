package com.openfolio.core.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "accounts")
data class Account(
    @PrimaryKey val id: String,
    val name: String,
    val institutionName: String?,
    val accountType: String,
    val currency: String,
    val createdAt: Long,
)
