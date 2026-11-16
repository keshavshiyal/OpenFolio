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

    @Test
    fun parseTradeDateSupportsMultipleFormats() {
        assertNotNull("ISO format", provider.parseTradeDate("2024-05-10"))
        assertNotNull("DD/MM/YYYY format", provider.parseTradeDate("10/5/2024"))
        assertNotNull("DD-MM-YYYY format", provider.parseTradeDate("10-5-2024"))
        assertNotNull("MM/DD/YYYY format", provider.parseTradeDate("5/10/2024"))
        assertNull("Blank date", provider.parseTradeDate("   "))
        assertNull("Invalid date", provider.parseTradeDate("not-a-date"))
    }

    @Test
    fun systemPromptIncludesHoldingsDateAndConfidenceRules() {
        val prompt = GeminiAiProvider.buildSystemPrompt("2026-11-16")
        assertTrue("Prompt demands tradeDate field", prompt.contains("tradeDate"))
        assertTrue("Prompt contains Holdings Statement instruction", prompt.contains("Holdings Statement (which lacks transaction dates)"))
        assertTrue("Prompt contains fallback date", prompt.contains("2026-11-16"))
        assertTrue("Prompt contains 95-100 confidence rule", prompt.contains("Assign a confidence score of 95-100 if you clearly find the asset name, quantity, and price."))
        assertTrue("Prompt contains 80-94 confidence rule", prompt.contains("Assign 80-94 if one field is missing."))
        assertTrue("Prompt contains below 80 rule", prompt.contains("Assign below 80 only if the data is ambiguous."))
    }
}
