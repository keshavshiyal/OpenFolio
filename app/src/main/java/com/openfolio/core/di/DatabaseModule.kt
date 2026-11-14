package com.openfolio.core.di

import android.content.Context
import androidx.room.Room
import com.openfolio.core.data.local.AppMetadataDao
import com.openfolio.core.data.local.DatabasePassphraseProvider
import com.openfolio.core.data.local.OpenFolioDatabase
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
            .addMigrations(OpenFolioDatabase.MIGRATION_1_2)
            .openHelperFactory(SupportFactory(passphraseProvider.getPassphrase()))
            .build()
    }

    @Provides
    fun provideAppMetadataDao(database: OpenFolioDatabase): AppMetadataDao =
        database.appMetadataDao()
}
