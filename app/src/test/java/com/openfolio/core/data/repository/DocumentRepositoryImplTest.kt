package com.openfolio.core.data.repository

import android.content.ContentResolver
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.provider.OpenableColumns
import com.openfolio.core.data.local.dao.DocumentDao
import com.openfolio.core.data.local.dao.DraftTransactionDao
import com.openfolio.core.data.local.dao.TransactionDao
import com.openfolio.core.data.local.entity.DocumentEntity
import com.openfolio.core.data.local.OpenFolioDatabase
import com.openfolio.core.utils.DocumentTextExtractor
import com.openfolio.core.domain.repository.DuplicateDocumentException
import java.io.ByteArrayInputStream
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CoroutineScope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.eq
import org.mockito.kotlin.isNull
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

class DocumentRepositoryImplTest {
    @Test
    fun calculateSha256ReturnsExpectedDigest() = runBlocking {
        val hash = calculateSha256(ByteArrayInputStream("hello".toByteArray()))

        assertEquals(
            "2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824",
            hash,
        )
    }

    @Test
    fun transactionFingerprintIsStableAndIncludesTransactionFields() {
        val fingerprint = createTransactionFingerprint(
            assetName = "  Reliance Industries ",
            transactionType = "buy",
            quantity = "10",
            netAmount = "28500.00",
            date = 20_000L,
        )

        assertEquals(
            fingerprint,
            createTransactionFingerprint(
                assetName = "reliance industries",
                transactionType = "BUY",
                quantity = "10",
                netAmount = "28500.00",
                date = 20_000L,
            ),
        )
        assertTrue(
            fingerprint != createTransactionFingerprint(
                assetName = "Reliance Industries",
                transactionType = "BUY",
                quantity = "11",
                netAmount = "28500.00",
                date = 20_000L,
            ),
        )
    }

    @Test
    fun ingestFileThrowsWhenHashAlreadyExists() {
        val dao = mock<DocumentDao>()
        val context = mock<Context>()
        val resolver = mock<ContentResolver>()
        val cursor = mock<Cursor>()
        val uri = mock<Uri>()
        val fileBytes = "statement contents".toByteArray()
        val expectedHash = runBlocking {
            calculateSha256(ByteArrayInputStream(fileBytes))
        }

        whenever(context.contentResolver).thenReturn(resolver)
        whenever(
            resolver.query(
                eq(uri),
                anyOrNull(),
                isNull(),
                isNull(),
                isNull(),
            ),
        ).thenReturn(cursor)
        whenever(cursor.moveToFirst()).thenReturn(true)
        whenever(cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)).thenReturn(0)
        whenever(cursor.getColumnIndex(OpenableColumns.SIZE)).thenReturn(1)
        whenever(cursor.getString(0)).thenReturn("statement.pdf")
        whenever(cursor.getLong(1)).thenReturn(fileBytes.size.toLong())
        whenever(resolver.openInputStream(uri)).thenReturn(ByteArrayInputStream(fileBytes))
        runBlocking {
            whenever(dao.getDocumentByHash(expectedHash)).thenReturn(
                DocumentEntity(
                    fileName = "prior.pdf",
                    localPath = "/private/prior.pdf",
                    fileHash = expectedHash,
                    fileSize = fileBytes.size.toLong(),
                    importDate = 1L,
                ),
            )
        }

        val repository = DocumentRepositoryImpl(
            documentDao = dao,
            draftTransactionDao = mock<DraftTransactionDao>(),
            transactionDao = mock<TransactionDao>(),
            database = mock<OpenFolioDatabase>(),
            documentTextExtractor = mock<DocumentTextExtractor>(),
            applicationScope = mock<CoroutineScope>(),
        )

        assertThrows(DuplicateDocumentException::class.java) {
            runBlocking { repository.ingestFile(uri, context) }
        }
        runBlocking {
            verify(dao, never()).insert(org.mockito.kotlin.any())
        }
    }
}
