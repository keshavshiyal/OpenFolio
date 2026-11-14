package com.openfolio.core.di

import com.openfolio.core.utils.CsvTextExtractor
import com.openfolio.core.utils.DocumentParser
import com.openfolio.core.utils.DocumentTextExtractor
import com.openfolio.core.utils.LocalDocumentTextExtractor
import com.openfolio.core.utils.PdfTextExtractor
import dagger.Binds
import dagger.Module
import dagger.multibindings.IntoSet
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@Module
@InstallIn(SingletonComponent::class)
abstract class DocumentParserModule {
    @Binds
    @IntoSet
    abstract fun bindPdfTextExtractor(extractor: PdfTextExtractor): DocumentParser

    @Binds
    @IntoSet
    abstract fun bindCsvTextExtractor(extractor: CsvTextExtractor): DocumentParser

    @Binds
    abstract fun bindDocumentTextExtractor(
        extractor: LocalDocumentTextExtractor,
    ): DocumentTextExtractor
}
