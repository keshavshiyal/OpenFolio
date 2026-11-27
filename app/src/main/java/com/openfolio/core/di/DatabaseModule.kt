package com.openfolio.core.di

import android.content.Context
import androidx.room.Room
import com.openfolio.core.data.local.AppMetadataDao
import com.openfolio.core.data.local.DatabasePassphraseProvider
import com.openfolio.core.data.local.OpenFolioDatabase
import com.openfolio.core.data.local.dao.AccountDao
import com.openfolio.core.data.local.dao.AssetDao
import com.openfolio.core.data.local.dao.ColumnMappingDao
import com.openfolio.core.data.local.dao.CorporateActionDao
import com.openfolio.core.data.local.dao.DocumentDao
import com.openfolio.core.data.local.dao.DraftTransactionDao
import com.openfolio.core.data.local.dao.LotDao
import com.openfolio.core.data.local.dao.TransactionDao
import com.openfolio.core.data.local.dao.ValuationDao
import com.openfolio.core.data.local.export.AppBackupManager
import com.openfolio.core.data.local.export.AppBackupManagerImpl
import com.openfolio.core.data.local.export.CsvReportExporter
import com.openfolio.core.data.local.export.CsvReportExporterImpl
import com.openfolio.core.data.local.export.DatabaseExporter
import com.openfolio.core.data.local.export.DatabaseExporterImpl
import com.openfolio.core.data.repository.AssetRepositoryImpl
import com.openfolio.core.data.repository.SettingsRepository
import com.openfolio.core.domain.repository.AssetRepository
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton
import net.sqlcipher.database.SQLiteDatabase
import net.sqlcipher.database.SupportFactory

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {
    @Provides
    @Singleton
    fun provideDatabase(
        @ApplicationContext context: Context,
        passphraseProvider: DatabasePassphraseProvider,
    ): OpenFolioDatabase {
        SQLiteDatabase.loadLibs(context)
        return Room.databaseBuilder(
            context,
            OpenFolioDatabase::class.java,
            "openfolio.db",
        )
            .fallbackToDestructiveMigration()
            .fallbackToDestructiveMigrationOnDowngrade()
            .openHelperFactory(SupportFactory(passphraseProvider.getPassphrase()))
            .build()
    }

    @Provides
    fun provideAppMetadataDao(database: OpenFolioDatabase): AppMetadataDao =
        database.appMetadataDao()

    @Provides
    fun provideDocumentDao(database: OpenFolioDatabase): DocumentDao =
        database.documentDao()

    @Provides
    fun provideColumnMappingDao(database: OpenFolioDatabase): ColumnMappingDao =
        database.columnMappingDao()

    @Provides
    fun provideDraftTransactionDao(database: OpenFolioDatabase): DraftTransactionDao =
        database.draftTransactionDao()

    @Provides
    fun provideTransactionDao(database: OpenFolioDatabase): TransactionDao =
        database.transactionDao()

    @Provides
    fun provideAccountDao(database: OpenFolioDatabase): AccountDao =
        database.accountDao()

    @Provides
    fun provideAssetDao(database: OpenFolioDatabase): AssetDao =
        database.assetDao()

    @Provides
    fun provideLotDao(database: OpenFolioDatabase): LotDao =
        database.lotDao()

    @Provides
    fun provideValuationDao(database: OpenFolioDatabase): ValuationDao =
        database.valuationDao()

    @Provides
    fun provideCorporateActionDao(database: OpenFolioDatabase): CorporateActionDao =
        database.corporateActionDao()

    @Provides
    @Singleton
    fun provideDatabaseExporter(database: OpenFolioDatabase): DatabaseExporter =
        DatabaseExporterImpl(database)

    @Provides
    @Singleton
    fun provideAppBackupManager(
        database: OpenFolioDatabase,
        databaseExporter: DatabaseExporter,
        settingsRepository: SettingsRepository,
        @ApplicationContext context: Context,
    ): AppBackupManager =
        AppBackupManagerImpl(database, databaseExporter, settingsRepository, context)

    @Provides
    @Singleton
    fun provideCsvReportExporter(database: OpenFolioDatabase): CsvReportExporter =
        CsvReportExporterImpl(database)

    @Provides
    @Singleton
    fun provideAssetRepository(
        database: OpenFolioDatabase,
        assetDao: AssetDao,
        transactionDao: TransactionDao,
        lotDao: LotDao,
    ): AssetRepository =
        AssetRepositoryImpl(database, assetDao, transactionDao, lotDao)
}
