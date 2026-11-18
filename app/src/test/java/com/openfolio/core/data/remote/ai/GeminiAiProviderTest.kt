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
        assertEquals("STOCK", first.assetClass)
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
        assertTrue("Prompt contains non-financial document instruction", prompt.contains("CRITICAL: If the provided text does not contain financial transactions"))
        assertTrue("Prompt includes assetClass schema", prompt.contains("assetClass (string)"))
    }

    @Test
    fun parseJsonResponseExtractsAssetClassAndHandlesPrecision() {
        val json = """
            [
              {
                "assetName": "Parag Parikh Flexi Cap",
                "assetClass": "MUTUAL_FUND",
                "transactionType": "BUY",
                "quantity": 144.8,
                "price": 10.0,
                "netAmount": 1448.0,
                "tradeDate": "2024-05-10"
              },
              {
                "assetName": "Bitcoin",
                "assetClass": "CRYPTO",
                "transactionType": "BUY",
                "quantity": "0.005",
                "price": "6000000",
                "netAmount": "30000"
              }
            ]
        """.trimIndent()

        val results = provider.parseJsonResponse(json, "doc-1")
        assertEquals(2, results.size)
        assertEquals("MUTUAL_FUND", results[0].assetClass)
        assertEquals("144.8", results[0].quantity)
        assertEquals("10.0", results[0].price)
        assertEquals("1448.0", results[0].netAmount)

        assertEquals("CRYPTO", results[1].assetClass)
        assertEquals("0.005", results[1].quantity)
    }

    @Test
    fun parseJsonResponseReturnsEmptyListForNonFinancialEmptyArray() {
        val results = provider.parseJsonResponse("[]", "doc-empty")
        assertTrue(results.isEmpty())
    }

    @Test
    fun parseJsonResponseSupportsAllAllowedAssetClasses() {
        val classes = listOf("STOCK", "MUTUAL_FUND", "BOND", "FD", "HOUSE", "LAND", "GOLD", "CRYPTO")
        val json = classes.mapIndexed { index, cls ->
            """
            {
                "assetName": "Asset $index",
                "assetClass": "$cls",
                "transactionType": "BUY",
                "quantity": "10",
                "price": "100",
                "netAmount": "1000"
            }
            """.trimIndent()
        }.joinToString(prefix = "[", separator = ",", postfix = "]")

        val results = provider.parseJsonResponse(json, "doc-classes")
        assertEquals(classes.size, results.size)
        classes.forEachIndexed { index, expectedClass ->
            assertEquals(expectedClass, results[index].assetClass)
        }
    }

    @Test
    fun parseJsonResponseFallbacksToStockForUnknownAssetClass() {
        val json = """
            [
              {
                "assetName": "Unknown Asset",
                "assetClass": "ART",
                "transactionType": "BUY"
              }
            ]
        """.trimIndent()

        val results = provider.parseJsonResponse(json, "doc-unknown")
        assertEquals(1, results.size)
        assertEquals("STOCK", results[0].assetClass)
    }

    @Test
    fun parseJsonResponsePreservesStrictBigDecimalPrecisionWithoutDoubleConversion() {
        val json = """
            [
              {
                "assetName": "Precise Asset",
                "quantity": "144.80000000000001",
                "price": "1234.56789012345",
                "netAmount": "178765.4321098765"
              }
            ]
        """.trimIndent()

        val results = provider.parseJsonResponse(json, "doc-precise")
        assertEquals(1, results.size)
        assertEquals("144.80000000000001", results[0].quantity)
        assertEquals("1234.56789012345", results[0].price)
        assertEquals("178765.4321098765", results[0].netAmount)
    }

    @Test
    fun parseJsonResponseExtractsBrokerIsinAndTicker() {
        val json = """
            [
              {
                "assetName": "Reliance Industries Limited",
                "assetClass": "STOCK",
                "transactionType": "BUY",
                "quantity": "50",
                "price": "2450.00",
                "netAmount": "122500.00",
                "tradeDate": "2024-05-10 14:30:00",
                "isin": "INE002A01018",
                "ticker": "RELIANCE",
                "broker": "Zerodha",
                "confidence": 98
              }
            ]
        """.trimIndent()

        val results = provider.parseJsonResponse(json, "doc-broker-isin")
        assertEquals(1, results.size)
        val draft = results[0]
        assertEquals("Reliance Industries Limited", draft.assetName)
        assertEquals("INE002A01018", draft.isin)
        assertEquals("RELIANCE", draft.ticker)
        assertEquals("Zerodha", draft.broker)
        assertNotNull(draft.tradeDate)
    }

    @Test
    fun parseTradeDateGracefullyStripsTimestamps() {
        val parsed1 = provider.parseTradeDate("2024-05-10 14:30:00")
        assertNotNull("ISO with time", parsed1)

        val parsed2 = provider.parseTradeDate("12-03-2025 14:30:00")
        assertNotNull("DD-MM-YYYY with time", parsed2)

        val parsed3 = provider.parseTradeDate("12/03/2025 09:15:30.123")
        assertNotNull("DD/MM/YYYY with millis", parsed3)

        val parsed4 = provider.parseTradeDate("2024-05-10T14:30:00Z")
        assertNotNull("ISO with T and Z", parsed4)
    }
}
