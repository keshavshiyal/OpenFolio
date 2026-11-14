package com.openfolio.core.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.openfolio.core.data.local.dao.DocumentDao
import com.openfolio.core.data.local.entity.DocumentEntity

@Database(
    entities = [
        Account::class,
        AppMetadataEntity::class,
        Asset::class,
        DocumentEntity::class,
        Transaction::class,
    ],
    version = 4,
    exportSchema = false,
)
@TypeConverters(LedgerConverters::class)
abstract class OpenFolioDatabase : RoomDatabase() {
    abstract fun appMetadataDao(): AppMetadataDao

    abstract fun documentDao(): DocumentDao

    companion object {
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `accounts` (
                        `id` TEXT NOT NULL,
                        `name` TEXT NOT NULL,
                        `institutionName` TEXT,
                        `accountType` TEXT NOT NULL,
                        `currency` TEXT NOT NULL,
                        `createdAt` INTEGER NOT NULL,
                        PRIMARY KEY(`id`)
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `assets` (
                        `id` TEXT NOT NULL,
                        `accountId` TEXT NOT NULL,
                        `name` TEXT NOT NULL,
                        `ticker` TEXT,
                        `assetClass` TEXT NOT NULL,
                        `detailsJson` TEXT NOT NULL,
                        `currency` TEXT NOT NULL,
                        `currentValue` TEXT,
                        `updatedAt` INTEGER NOT NULL,
                        PRIMARY KEY(`id`)
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `transactions` (
                        `id` TEXT NOT NULL,
                        `assetId` TEXT,
                        `type` TEXT NOT NULL,
                        `tradeDate` INTEGER NOT NULL,
                        `settlementDate` INTEGER,
                        `quantity` TEXT NOT NULL,
                        `grossAmount` TEXT NOT NULL,
                        `fees` TEXT NOT NULL,
                        `taxes` TEXT NOT NULL,
                        `netAmount` TEXT NOT NULL,
                        `currency` TEXT NOT NULL,
                        `fingerprint` TEXT NOT NULL,
                        `documentId` TEXT,
                        `sourcePage` INTEGER,
                        `confidenceScore` REAL,
                        `status` TEXT NOT NULL,
                        `isManual` INTEGER NOT NULL,
                        `fxRate` TEXT,
                        `baseCurrencyAmount` TEXT,
                        PRIMARY KEY(`id`)
                    )
                    """.trimIndent(),
                )
            }

        }

        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `documents` (
                        `id` TEXT NOT NULL,
                        `fileName` TEXT NOT NULL,
                        `localPath` TEXT NOT NULL,
                        `fileHash` TEXT NOT NULL,
                        `fileSize` INTEGER NOT NULL,
                        `importDate` INTEGER NOT NULL,
                        `status` TEXT NOT NULL,
                        PRIMARY KEY(`id`)
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_documents_fileHash` ON `documents` (`fileHash`)",
                )
            }
        }

        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `documents` ADD COLUMN `extractedText` TEXT")
            }
        }
    }
}
