package com.openfolio.core.data.local.export

import com.openfolio.core.data.local.OpenFolioDatabase
import com.openfolio.core.data.local.dao.AccountDao
import com.openfolio.core.data.local.dao.AssetDao
import com.openfolio.core.data.local.dao.DocumentDao
import com.openfolio.core.data.local.entity.AccountEntity
import com.openfolio.core.data.local.entity.AccountType
import com.openfolio.core.data.local.entity.AssetClass
import com.openfolio.core.data.local.entity.AssetEntity
import com.openfolio.core.data.local.entity.DocumentEntity
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.io.ByteArrayOutputStream
import java.math.BigDecimal

class LegalDossierGeneratorTest {

    private val database = mock<OpenFolioDatabase>()
    private val assetDao = mock<AssetDao>()
    private val accountDao = mock<AccountDao>()
    private val documentDao = mock<DocumentDao>()
    private lateinit var generator: LegalDossierGenerator

    @Before
    fun setUp() {
        whenever(database.assetDao()).thenReturn(assetDao)
        whenever(database.accountDao()).thenReturn(accountDao)
        whenever(database.documentDao()).thenReturn(documentDao)
        generator = LegalDossierGenerator(database)
    }

    @Test
    fun generatesComprehensiveManifestWithTablesAndSha256Seal() = runTest {
        val testAsset = AssetEntity(
            id = "reliance",
            displayName = "Reliance Industries",
            assetClass = AssetClass.STOCK,
            totalQuantity = BigDecimal("10"),
            totalInvested = BigDecimal("25000.00"),
            averageCost = BigDecimal("2500.00"),
            identifiersJson = """{"isin":"INE002A01018","ticker":"RELIANCE"}""",
            detailsJson = """{"depository":"CDSL"}""",
        )
        val testAccount = AccountEntity(
            id = "acc1",
            name = "Zerodha Demat",
            type = AccountType.BROKER,
            institution = "Zerodha Broking Limited",
            accountNumberEncrypted = "1208160099887766",
            currency = "INR",
        )
        val testDoc = DocumentEntity(
            id = "doc1",
            fileName = "zerodha_tradebook.xlsx",
            localPath = "/documents/zerodha_tradebook.xlsx",
            fileHash = "a1b2c3d4e5f67890abcdef1234567890abcdef1234567890abcdef1234567890",
            fileSize = 1024L,
            importDate = 1700000000000L,
            assetClass = "STOCK",
        )

        whenever(assetDao.getAllOnce()).thenReturn(listOf(testAsset))
        whenever(accountDao.getAllOnce()).thenReturn(listOf(testAccount))
        whenever(documentDao.getAllDocumentsOnce()).thenReturn(listOf(testDoc))

        val dossierText = generator.generateDossierText()

        assertNotNull(dossierText)
        assertTrue(dossierText.contains("# OPENFOLIO ESTATE & ASSET MANIFEST"))
        assertTrue(dossierText.contains("Reliance Industries"))
        assertTrue(dossierText.contains("INE002A01018"))
        assertTrue(dossierText.contains("Zerodha Demat"))
        assertTrue(dossierText.contains("1208160099887766"))
        assertTrue(dossierText.contains("zerodha_tradebook.xlsx"))
        assertTrue(dossierText.contains("a1b2c3d4e5f67890"))
        assertTrue(dossierText.contains("SHA-256 Manifest Fingerprint:"))
    }

    @Test
    fun writesDossierDirectlyToOutputStream() = runTest {
        whenever(assetDao.getAllOnce()).thenReturn(emptyList())
        whenever(accountDao.getAllOnce()).thenReturn(emptyList())
        whenever(documentDao.getAllDocumentsOnce()).thenReturn(emptyList())

        val outputStream = ByteArrayOutputStream()
        generator.writeDossierToStream(outputStream)

        val writtenString = outputStream.toString(Charsets.UTF_8.name())
        assertTrue(writtenString.contains("OPENFOLIO ESTATE & ASSET MANIFEST"))
    }
}
