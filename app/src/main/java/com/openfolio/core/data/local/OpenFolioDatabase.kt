package com.openfolio.core.data.local

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [AppMetadataEntity::class],
    version = 1,
    exportSchema = false,
)
abstract class OpenFolioDatabase : RoomDatabase() {
    abstract fun appMetadataDao(): AppMetadataDao
}
