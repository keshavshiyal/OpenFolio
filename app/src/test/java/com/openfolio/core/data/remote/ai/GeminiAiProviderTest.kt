package com.openfolio.core.data.remote.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GeminiAiProviderTest {
    private val provider = GeminiAiProvider()

    @Test
    fun parseJsonResponseWithValidJsonArray() {
        val json = """
            [
              {
                "assetName": "HDFC Bank",
                "transactionType": "BUY",
                "quantity": "10",
                "price": "1500.50",
                "netAmount": "15005.00",
                "tradeDate": "2024-05-10",
                "confidence": 95
              },
              {
                "assetName": "TCS",
                "transactionType": "DIVIDEND",
                "quantity": "1",
                "price": "28.00",
                "netAmount": "28.00",
                "tradeDate": "2024-06-15",
                "confidence": 90
              }
            ]
        """.trimIndent()

        val results = provider.parseJsonResponse(json, "doc-123")

        assertEquals(2, results.size)
        val first = results[0]
        assertEquals("doc-123", first.documentId)
        assertEquals("HDFC Bank", first.assetName)
        assertEquals("BUY", first.transactionType)
        assertEquals("10", first.quantity)
        assertEquals("1500.50", first.price)
        assertEquals("15005.00", first.netAmount)
        assertEquals(95, first.confidence)
        assertNotNull(first.tradeDate)

        val second = results[1]
        assertEquals("doc-123", second.documentId)
        assertEquals("TCS", second.assetName)
        assertEquals("DIVIDEND", second.transactionType)
    }

    @Test
    fun parseJsonResponseWithMarkdownFences() {
        val json = """
            ```json
            [
              {
                "assetName": "Infosys",
                "transactionType": "SELL",
                "quantity": "5",
                "price": "1400",
                "netAmount": "7000",
                "tradeDate": "2024-01-20",
                "confidence": 88
              }
            ]
            ```
        """.trimIndent()

        val results = provider.parseJsonResponse(json, "doc-abc")

        assertEquals(1, results.size)
        assertEquals("Infosys", results[0].assetName)
        assertEquals("SELL", results[0].transactionType)
        assertEquals("doc-abc", results[0].documentId)
    }

    @Test
    fun parseJsonResponseHandlesMissingOptionalFieldsGracefully() {
        val json = """
            [
              {
                "assetName": "Gold ETF"
              }
            ]
        """.trimIndent()

        val results = provider.parseJsonResponse(json, "doc-xyz")

        assertEquals(1, results.size)
        assertEquals("Gold ETF", results[0].assetName)
        assertEquals("BUY", results[0].transactionType)
        assertEquals("1", results[0].quantity)
        assertEquals("0", results[0].price)
        assertNull(results[0].tradeDate)
    }

    @Test
    fun parseJsonResponseSkipsEmptyAssetNames() {
        val json = """
            [
              {
                "assetName": "   ",
                "transactionType": "BUY"
              }
            ]
        """.trimIndent()

        val results = provider.parseJsonResponse(json, "doc-xyz")
        assertTrue(results.isEmpty())
    }
}
