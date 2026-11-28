package com.openfolio

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import com.openfolio.core.data.local.DatabasePassphraseProvider
import com.openfolio.core.data.local.LocalFileCrypto
import com.openfolio.core.di.ApplicationScope
import com.openfolio.core.domain.repository.DocumentRepository
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import dagger.hilt.android.HiltAndroidApp
import java.io.File
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

@HiltAndroidApp
class OpenFolioApplication : Application(), Configuration.Provider {

    @Inject
    lateinit var workerFactory: HiltWorkerFactory

    @Inject
    lateinit var documentRepository: DocumentRepository

    @Inject
    lateinit var databasePassphraseProvider: DatabasePassphraseProvider

    @Inject
    @ApplicationScope
    lateinit var applicationScope: CoroutineScope

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()

    override fun onCreate() {
        super.onCreate()
        PDFBoxResourceLoader.init(this)
        applicationScope.launch(Dispatchers.IO) {
            cleanupSensitiveCacheArtifacts()
            if (databasePassphraseProvider.isDatabaseReady()) {
                runCatching {
                    documentRepository.recoverInterruptedImports()
                }
            }
        }
    }

    private fun cleanupSensitiveCacheArtifacts() {
        runCatching {
            File(cacheDir, "exports").takeIf(File::exists)?.deleteRecursively()
            File(cacheDir, LocalFileCrypto.DEC_TEMP_DIR_NAME).takeIf(File::exists)?.deleteRecursively()
            cacheDir.listFiles()?.forEach { entry ->
                val name = entry.name
                if (name.startsWith("backup_tmp_") ||
                    name.startsWith("restore_tmp_") ||
                    name.startsWith("restore_extract_")
                ) {
                    entry.deleteRecursively()
                }
            }
        }
    }
}
