package com.openfolio.core.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.openfolio.core.data.local.dao.DocumentDao
import com.openfolio.core.data.local.dao.DraftTransactionDao
import com.openfolio.core.data.local.dao.TransactionDao
import com.openfolio.core.data.local.entity.DocumentEntity
import com.openfolio.core.data.local.entity.DraftTransactionEntity

@Database(
    entities = [
        Account::class,
        AppMetadataEntity::class,
        Asset::class,
        DocumentEntity::class,
        DraftTransactionEntity::class,
        Transaction::class,
    ],
    version = 6,
    exportSchema = false,
)
@TypeConverters(LedgerConverters::class)
abstract class OpenFolioDatabase : RoomDatabase() {
    abstract fun appMetadataDao(): AppMetadataDao

    abstract fun documentDao(): DocumentDao

    abstract fun draftTransactionDao(): DraftTransactionDao

    abstract fun transactionDao(): TransactionDao

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

        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `draft_transactions` (
                        `id` TEXT NOT NULL,
                        `documentId` TEXT NOT NULL,
                        `assetName` TEXT NOT NULL,
                        `transactionType` TEXT NOT NULL,
                        `quantity` TEXT NOT NULL,
                        `price` TEXT NOT NULL,
                        `netAmount` TEXT NOT NULL,
                        `confidence` INTEGER NOT NULL,
                        `isAccepted` INTEGER NOT NULL,
                        PRIMARY KEY(`id`),
                        FOREIGN KEY(`documentId`) REFERENCES `documents`(`id`)
                            ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_draft_transactions_documentId` " +
                        "ON `draft_transactions` (`documentId`)",
                )
            }
        }

        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE `draft_transactions_new` (
                        `id` TEXT NOT NULL,
                        `documentId` TEXT NOT NULL,
                        `assetName` TEXT NOT NULL,
                        `transactionType` TEXT NOT NULL,
                        `quantity` TEXT NOT NULL,
                        `price` TEXT NOT NULL,
                        `netAmount` TEXT NOT NULL,
                        `confidence` INTEGER NOT NULL,
                        `isAccepted` INTEGER NOT NULL,
                        `isManual` INTEGER NOT NULL DEFAULT 0,
                        `fingerprint` TEXT NOT NULL DEFAULT '',
                        PRIMARY KEY(`id`),
                        FOREIGN KEY(`documentId`) REFERENCES `documents`(`id`)
                            ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    """
                    INSERT INTO `draft_transactions_new` (
                        `id`, `documentId`, `assetName`, `transactionType`, `quantity`,
                        `price`, `netAmount`, `confidence`, `isAccepted`
                    )
                    SELECT `id`, `documentId`, `assetName`, `transactionType`, `quantity`,
                        `price`, `netAmount`, `confidence`, `isAccepted`
                    FROM `draft_transactions`
                    """.trimIndent(),
                )
                db.execSQL("DROP TABLE `draft_transactions`")
                db.execSQL("ALTER TABLE `draft_transactions_new` RENAME TO `draft_transactions`")
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_draft_transactions_documentId` " +
                        "ON `draft_transactions` (`documentId`)",
                )
            }
        }
    }
}
