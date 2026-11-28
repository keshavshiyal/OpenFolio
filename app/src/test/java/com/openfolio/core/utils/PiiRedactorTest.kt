package com.openfolio.core.utils

import com.openfolio.core.data.remote.ai.AiCarryOverContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PiiRedactorTest {

    @Test
    fun redactForAiStripsPersonalIdentifiersWhilePreservingFinancialFieldsAndIsin() {
        val rawStatement = """
            Investor Name: Rajesh Kumar Sharma
            PAN: ABCDE1234F
            Email: rajesh.sharma@example.com
            Mobile: +91 9876543210
            Aadhaar: 1234 5678 9012
            IFSC: HDFC0001234
            Folio No: 1234567/89
            Demat Account ID: IN300183-10293847
            HDFC Top 100 Fund - Growth ISIN: INF179K01BE2
            Reliance Industries Ltd (INF879O01019 / INE002A01018)
            15-Mar-2024 BUY Qty 10.5 Price 2500.00 Net 26250.00
        """.trimIndent()

        val scrubbed = PiiRedactor.redactForAi(rawStatement)

        assertFalse(scrubbed.contains("Rajesh Kumar Sharma"))
        assertFalse(scrubbed.contains("ABCDE1234F"))
        assertFalse(scrubbed.contains("rajesh.sharma@example.com"))
        assertFalse(scrubbed.contains("9876543210"))
        assertFalse(scrubbed.contains("1234 5678 9012"))
        assertFalse(scrubbed.contains("HDFC0001234"))
        assertFalse(scrubbed.contains("1234567/89"))
        assertFalse(scrubbed.contains("IN300183-10293847"))

        assertTrue(scrubbed.contains("INF179K01BE2"))
        assertTrue(scrubbed.contains("INF879O01019"))
        assertTrue(scrubbed.contains("INE002A01018"))
        assertTrue(scrubbed.contains("HDFC Top 100 Fund"))
        assertTrue(scrubbed.contains("Reliance Industries Ltd"))
        assertTrue(scrubbed.contains("15-Mar-2024 BUY Qty 10.5 Price 2500.00 Net 26250.00"))

        val mergedHeaderLine = "PAN: ABCDE1234F Folio No: 998877/11 HDFCBANK ISIN: INF179K01BE2"
        val scrubbedMerged = PiiRedactor.redactForAi(mergedHeaderLine)
        assertFalse(scrubbedMerged.contains("ABCDE1234F"))
        assertFalse(scrubbedMerged.contains("998877/11"))
        assertTrue(scrubbedMerged.contains("HDFCBANK"))
        assertTrue(scrubbedMerged.contains("INF179K01BE2"))
    }

    @Test
    fun sanitizeCarryOverContextClearsFolioAndAccountIdAndRedactsText() {
        val dirty = AiCarryOverContext(
            activeAmc = "HDFC Mutual Fund PAN ABCDE1234F",
            activeScheme = "HDFC Top 100 Fund INF179K01BE2",
            activeIsin = "INF179K01BE2",
            activeFolio = "987654/32",
            activeBroker = "CAMS",
            activeAssetClass = "MUTUAL_FUND",
            activeAccountId = "NSDL-99887766",
            incompleteTailText = "Folio No: 987654/32 01-Apr-2024 BUY 1000",
            recentBoundaryTransactions = listOf("Client ID: 55443322 BUY 10 INF179K01BE2"),
        )

        val cleaned = PiiRedactor.sanitizeCarryOverContextForAi(dirty)!!

        assertEquals("", cleaned.activeFolio)
        assertEquals("", cleaned.activeAccountId)
        assertEquals("INF179K01BE2", cleaned.activeIsin)
        assertEquals("CAMS", cleaned.activeBroker)
        assertFalse(cleaned.activeAmc.contains("ABCDE1234F"))
        assertFalse(cleaned.incompleteTailText.contains("987654/32"))
        assertTrue(cleaned.incompleteTailText.contains("01-Apr-2024 BUY 1000"))
        assertFalse(cleaned.recentBoundaryTransactions.single().contains("55443322"))
        assertTrue(cleaned.recentBoundaryTransactions.single().contains("INF179K01BE2"))
    }
}
