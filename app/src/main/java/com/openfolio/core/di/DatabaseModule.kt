package com.openfolio.core.di

import android.content.Context
import androidx.room.Room
import com.openfolio.core.data.local.AppMetadataDao
import com.openfolio.core.data.local.DatabasePassphraseProvider
import com.openfolio.core.data.local.OpenFolioDatabase
import com.openfolio.core.data.local.dao.DocumentDao
import com.openfolio.core.data.local.dao.ColumnMappingDao
import com.openfolio.core.data.local.dao.DraftTransactionDao
import com.openfolio.core.data.local.dao.TransactionDao
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
            .addMigrations(
                OpenFolioDatabase.MIGRATION_1_2,
                OpenFolioDatabase.MIGRATION_2_3,
                OpenFolioDatabase.MIGRATION_3_4,
                OpenFolioDatabase.MIGRATION_4_5,
                OpenFolioDatabase.MIGRATION_5_6,
                OpenFolioDatabase.MIGRATION_6_7,
                OpenFolioDatabase.MIGRATION_7_8,
            )
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
}
