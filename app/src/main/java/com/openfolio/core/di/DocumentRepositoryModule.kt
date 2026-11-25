package com.openfolio.core.di

import com.openfolio.core.data.repository.DocumentRepositoryImpl
import com.openfolio.core.domain.repository.DocumentRepository
import com.openfolio.core.worker.DocumentImportScheduler
import com.openfolio.core.worker.WorkManagerDocumentImportScheduler
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class DocumentRepositoryModule {
    @Binds
    @Singleton
    abstract fun bindDocumentRepository(
        repository: DocumentRepositoryImpl,
    ): DocumentRepository

    @Binds
    @Singleton
    abstract fun bindDocumentImportScheduler(
        scheduler: WorkManagerDocumentImportScheduler,
    ): DocumentImportScheduler
}
