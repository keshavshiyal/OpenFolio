package com.openfolio.core.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import com.openfolio.core.data.local.dao.AccountDao
import com.openfolio.core.data.local.dao.AssetDao
import com.openfolio.core.data.local.dao.ColumnMappingDao
import com.openfolio.core.data.local.dao.CorporateActionDao
import com.openfolio.core.data.local.dao.DocumentDao
import com.openfolio.core.data.local.dao.DraftTransactionDao
import com.openfolio.core.data.local.dao.LotDao
import com.openfolio.core.data.local.dao.TransactionDao
import com.openfolio.core.data.local.dao.ValuationDao
import com.openfolio.core.data.local.entity.AccountEntity
import com.openfolio.core.data.local.entity.AssetEntity
import com.openfolio.core.data.local.entity.ColumnMappingEntity
import com.openfolio.core.data.local.entity.CorporateActionEntity
import com.openfolio.core.data.local.entity.DocumentEntity
import com.openfolio.core.data.local.entity.DraftTransactionEntity
import com.openfolio.core.data.local.entity.LotEntity
import com.openfolio.core.data.local.entity.TransactionEntity
import com.openfolio.core.data.local.entity.ValuationEntity

@Database(
    entities = [
        AccountEntity::class,
        AppMetadataEntity::class,
        AssetEntity::class,
        ColumnMappingEntity::class,
        CorporateActionEntity::class,
        DocumentEntity::class,
        DraftTransactionEntity::class,
        LotEntity::class,
        TransactionEntity::class,
        ValuationEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class OpenFolioDatabase : RoomDatabase() {
    abstract fun accountDao(): AccountDao

    abstract fun assetDao(): AssetDao

    abstract fun appMetadataDao(): AppMetadataDao

    abstract fun columnMappingDao(): ColumnMappingDao

    abstract fun corporateActionDao(): CorporateActionDao

    abstract fun documentDao(): DocumentDao

    abstract fun draftTransactionDao(): DraftTransactionDao

    abstract fun lotDao(): LotDao

    abstract fun transactionDao(): TransactionDao

    abstract fun valuationDao(): ValuationDao

    companion object {
        const val DATABASE_NAME = "openfolio.db"
        const val SCHEMA_VERSION = 1

        /**
         * All schema upgrades MUST add explicit non-destructive [Migration] entries here.
         */
        val MIGRATIONS: Array<Migration> = emptyArray()
    }
}
