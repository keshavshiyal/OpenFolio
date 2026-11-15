package com.openfolio.core.di

import com.openfolio.core.data.remote.ai.AiProvider
import com.openfolio.core.data.remote.ai.GeminiAiProvider
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class AiProviderModule {
    @Binds
    @Singleton
    abstract fun bindAiProvider(
        provider: GeminiAiProvider,
    ): AiProvider
}
