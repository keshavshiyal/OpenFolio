package com.openfolio.core.data.remote.ai

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
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

    @Test
    fun parseJsonResponseSalvagesCompleteObjectsFromTruncatedJsonArray() {
        val truncated = """
            [
              {
                "assetName": "Infosys Ltd",
                "transactionType": "BUY",
                "quantity": "12.500",
                "price": "1540.25",
                "netAmount": "19253.125",
                "tradeDate": "2024-05-10",
                "notes": "Contains {braces} and \"escaped\" quotes"
              },
              {
                "assetName": "TCS",
                "transactionType": "SELL",
                "quantity": "4",
                "price": "3900.00",
                "netAmount": "15600.00",
                "tradeDate": "2024-06-01"
              },
              {
                "assetName": "HDFC Bank",
                "transactionType": "BUY",
                "quantity": "10"
        """.trimIndent()

        val parsed = provider.parseJsonResponseWithMetadata(truncated, "doc-trunc")
        assertTrue(parsed.wasTruncated)
        assertEquals(2, parsed.drafts.size)
        assertEquals("Infosys Ltd", parsed.drafts[0].assetName)
        assertEquals("12.500", parsed.drafts[0].quantity)
        assertEquals("19253.125", parsed.drafts[0].netAmount)
        assertEquals("TCS", parsed.drafts[1].assetName)
        assertEquals("SELL", parsed.drafts[1].transactionType)
    }

    @Test
    fun extractTransactionsRetriesWithExponentialBackoffOn429() = runBlocking {
        val recordedDelays = mutableListOf<Long>()
        var attempts = 0
        val retryProvider = GeminiAiProvider(
            delayFn = { millis -> recordedDelays.add(millis) },
            generateContentFn = { _, _ ->
                attempts++
                if (attempts < 4) {
                    throw RuntimeException("HTTP 429 RESOURCE_EXHAUSTED: rate limit exceeded")
                }
                """[{"assetName":"RELIANCE","transactionType":"BUY","quantity":"10","price":"2500.50","netAmount":"25005.00","tradeDate":"2024-04-12"}]"""
            },
        )

        val result = retryProvider.extractTransactionsDetailed("trade text", "api-key", "doc-retry")
        assertTrue(result.isSuccess)
        val batch = result.getOrThrow()
        assertFalse(batch.wasTruncated)
        assertEquals(1, batch.drafts.size)
        assertEquals("RELIANCE", batch.drafts[0].assetName)
        assertEquals("2500.50", batch.drafts[0].price)
        assertEquals(4, attempts)
        assertEquals(listOf(2_000L, 4_000L, 8_000L), recordedDelays)
    }

    @Test
    fun extractTransactionsDoesNotRetryNonRetryableErrorsAndRethrowsCancellation() {
        var attempts = 0
        val nonRetryProvider = GeminiAiProvider(
            delayFn = { },
            generateContentFn = { _, _ ->
                attempts++
                throw RuntimeException("HTTP 403 API key invalid")
            },
        )
        val failed = runBlocking { nonRetryProvider.extractTransactions("text", "bad-key", "doc-1") }
        assertTrue(failed.isFailure)
        assertEquals(1, attempts)

        val cancellingProvider = GeminiAiProvider(
            delayFn = { },
            generateContentFn = { _, _ -> throw CancellationException("Cancelled") },
        )
        assertThrows(CancellationException::class.java) {
            runBlocking { cancellingProvider.extractTransactions("text", "key", "doc-2") }
        }
    }

    @Test
    fun parseJsonResponseWithMetadataExtractsCarryOverContextWrapperAndSourcePage() {
        val json = """
            {
              "transactions": [
                {
                  "assetName": "HDFC Top 100 Fund - Direct Plan - Growth",
                  "assetClass": "MUTUAL_FUND",
                  "transactionType": "BUY",
                  "quantity": "49.8753",
                  "price": "100.25",
                  "netAmount": "5000.00",
                  "tradeDate": "2024-03-01",
                  "isin": "INF179K01BE2",
                  "broker": "CAMS",
                  "sourcePage": 4,
                  "confidence": 97
                }
              ],
              "carryOverContext": {
                "activeAmc": "HDFC Mutual Fund",
                "activeScheme": "HDFC Top 100 Fund - Direct Plan - Growth",
                "activeIsin": "INF179K01BE2",
                "activeFolio": "1234567/89",
                "activeBroker": "CAMS",
                "activeColumnHeaders": ["Date", "Transaction", "Amount", "Units", "NAV"],
                "incompleteTailText": "15-Mar-2024 Systematic Investment"
              }
            }
        """.trimIndent()

        val parsed = provider.parseJsonResponseWithMetadata(json, "doc-wrapper")
        assertFalse(parsed.wasTruncated)
        assertEquals(1, parsed.drafts.size)
        val draft = parsed.drafts.single()
        assertEquals("MUTUAL_FUND", draft.assetClass)
        assertEquals("49.8753", draft.quantity)
        assertEquals("INF179K01BE2", draft.isin)
        assertEquals("CAMS", draft.broker)
        assertEquals(4, draft.sourcePage)

        val ctx = parsed.carryOverContext
        assertEquals("HDFC Mutual Fund", ctx.activeAmc)
        assertEquals("HDFC Top 100 Fund - Direct Plan - Growth", ctx.activeScheme)
        assertEquals("INF179K01BE2", ctx.activeIsin)
        assertEquals("1234567/89", ctx.activeFolio)
        assertEquals("CAMS", ctx.activeBroker)
        assertEquals(listOf("Date", "Transaction", "Amount", "Units", "NAV"), ctx.activeColumnHeaders)
        assertEquals("15-Mar-2024 Systematic Investment", ctx.incompleteTailText)
    }

    @Test
    fun parseJsonResponseWithMetadataSalvagesTruncatedWrapperObject() {
        val truncatedWrapper = """
            {
              "transactions": [
                {
                  "assetName": "Parag Parikh Flexi Cap Fund",
                  "assetClass": "MUTUAL_FUND",
                  "transactionType": "BUY",
                  "quantity": "15.125",
                  "price": "75.00",
                  "netAmount": "1134.375",
                  "tradeDate": "2024-04-10",
                  "isin": "INF879O01019",
                  "sourcePage": 5
                },
                {
                  "assetName": "SBI Bluechip Fund",
                  "assetClass": "MUTUAL_FUND",
                  "transactionType": "BUY",
                  "quantity": "20.00
        """.trimIndent()

        val parsed = provider.parseJsonResponseWithMetadata(truncatedWrapper, "doc-trunc-wrap")
        assertTrue(parsed.wasTruncated)
        assertEquals(1, parsed.drafts.size)
        assertEquals("Parag Parikh Flexi Cap Fund", parsed.drafts[0].assetName)
        assertEquals("15.125", parsed.drafts[0].quantity)
        assertEquals("INF879O01019", parsed.carryOverContext.activeIsin)
    }

    @Test
    fun extractTransactionsWithContextPrependsPriorContextAndResolvesModelName() = runBlocking {
        var capturedPrompt = ""
        val contextProvider = GeminiAiProvider(
            delayFn = { },
            generateContentFn = { _, prompt ->
                capturedPrompt = prompt
                """
                {
                  "transactions": [
                    {
                      "assetName": "HDFC Top 100 Fund",
                      "assetClass": "MUTUAL_FUND",
                      "transactionType": "BUY",
                      "quantity": "10",
                      "price": "100",
                      "netAmount": "1000",
                      "tradeDate": "2024-03-15",
                      "isin": "INF179K01BE2"
                    }
                  ],
                  "carryOverContext": {
                    "activeAmc": "HDFC Mutual Fund",
                    "activeScheme": "HDFC Top 100 Fund",
                    "activeIsin": "INF179K01BE2",
                    "activeFolio": "123456/78",
                    "activeBroker": "CAMS",
                    "activeColumnHeaders": ["Date", "Particulars", "Amount"],
                    "incompleteTailText": ""
                  }
                }
                """.trimIndent()
            },
            defaultModelName = "gemini-2.5-flash",
        )

        val prior = AiCarryOverContext(
            activeAmc = "HDFC Mutual Fund",
            activeScheme = "HDFC Top 100 Fund",
            activeIsin = "INF179K01BE2",
            activeFolio = "123456/78",
            activeBroker = "CAMS",
            activeColumnHeaders = listOf("Date", "Particulars", "Amount"),
            incompleteTailText = "15-Mar-2024 Purchase",
        )

        val res = contextProvider.extractTransactionsWithContext(
            rawText = "--- PAGE 6 ---\n1000.00 10.000 100.00",
            apiKey = "test-key",
            documentId = "doc-ctx",
            priorContext = prior,
            modelName = "gemini-2.5-pro",
        )

        assertTrue(res.isSuccess)
        assertTrue(capturedPrompt.contains("CARRY-OVER CONTEXT FROM PREVIOUS PAGES"))
        assertTrue(capturedPrompt.contains("INF179K01BE2"))
        assertTrue(capturedPrompt.contains("15-Mar-2024 Purchase"))
        assertEquals("gemini-2.5-pro", contextProvider.resolveModelName("gemini-2.5-pro"))
        assertEquals("gemini-2.5-flash", contextProvider.resolveModelName(null))
    }

    @Test
    fun carryOverContextRoundTripsMultiAssetAndBoundaryFieldsAndBuildsPrompt() {
        val context = AiCarryOverContext(
            activeAmc = "Axis Mutual Fund",
            activeScheme = "Axis Nifty 50 Index Bond Fund",
            activeIsin = "INF846K01EW2",
            activeFolio = "9102938/44",
            activeBroker = "KFintech",
            activeAssetClass = "BOND",
            activeAccountId = "acc-primary-01",
            activeColumnHeaders = listOf("Date", "Nature", "Units", "NAV", "Amount"),
            incompleteTailText = "29-Mar-2024 Purchase",
            fallbackTailLines = "30-Mar-2024 Stamp Duty 0.50",
            recentBoundaryTransactions = listOf("2024-03-28|BUY|Axis Nifty|10|1000.00"),
        )

        val serialized = context.toJson()
        val restored = AiCarryOverContext.fromJson(serialized)
        assertEquals(context, restored)

        val prompt = restored!!.toPromptPrefix()
        assertTrue(prompt.contains("Active Asset Class: BOND"))
        assertTrue(prompt.contains("Active Account / Demat ID: acc-primary-01"))
        assertTrue(prompt.contains("Incomplete Tail Text from Previous Batch"))
        assertTrue(prompt.contains("29-Mar-2024 Purchase"))
        assertTrue(prompt.contains("Already Extracted Boundary Rows from Previous Batch"))
        assertTrue(prompt.contains("2024-03-28|BUY|Axis Nifty|10|1000.00"))

        val fallbackOnlyPrompt = restored.copy(incompleteTailText = "").toPromptPrefix()
        assertTrue(fallbackOnlyPrompt.contains("Trailing Lines from Previous Page"))
        assertTrue(fallbackOnlyPrompt.contains("30-Mar-2024 Stamp Duty 0.50"))
    }

    @Test
    fun defaultModelIsGemini38FlashAndRequestConfigAdaptsByModelFamily() {
        assertEquals("gemini-3.8-flash", GeminiAiProvider.DEFAULT_MODEL_NAME)
        assertEquals("gemini-3.8-flash", provider.resolveModelName(null))

        val req38 = JSONObject(
            provider.buildGenerateContentRequestJson(
                modelName = "gemini-3.8-flash",
                systemPrompt = "sys",
                userPrompt = "usr",
            ),
        )
        val gen38 = req38.getJSONObject("generationConfig")
        assertEquals(65_536, gen38.getInt("maxOutputTokens"))
        assertEquals("application/json", gen38.getString("responseMimeType"))
        assertFalse(gen38.has("temperature"))
        assertEquals("LOW", gen38.getJSONObject("thinkingConfig").getString("thinkingLevel"))

        val req4Hydrogen = JSONObject(
            provider.buildGenerateContentRequestJson(
                modelName = "gemini-4-hydrogen",
                systemPrompt = "sys",
                userPrompt = "usr",
            ),
        )
        val gen4 = req4Hydrogen.getJSONObject("generationConfig")
        assertEquals(65_536, gen4.getInt("maxOutputTokens"))
        assertFalse(gen4.has("temperature"))
        assertEquals("LOW", gen4.getJSONObject("thinkingConfig").getString("thinkingLevel"))

        val req36 = JSONObject(
            provider.buildGenerateContentRequestJson(
                modelName = "gemini-3.6-flash",
                systemPrompt = "sys",
                userPrompt = "usr",
            ),
        )
        val gen36 = req36.getJSONObject("generationConfig")
        assertEquals(65_536, gen36.getInt("maxOutputTokens"))
        assertFalse(gen36.has("temperature"))
        assertEquals("MINIMAL", gen36.getJSONObject("thinkingConfig").getString("thinkingLevel"))

        val req35Lite = JSONObject(
            provider.buildGenerateContentRequestJson(
                modelName = "gemini-3.5-flash-lite",
                systemPrompt = "sys",
                userPrompt = "usr",
            ),
        )
        val gen35Lite = req35Lite.getJSONObject("generationConfig")
        assertEquals("MINIMAL", gen35Lite.getJSONObject("thinkingConfig").getString("thinkingLevel"))

        val req25 = JSONObject(
            provider.buildGenerateContentRequestJson(
                modelName = "gemini-2.5-flash",
                systemPrompt = "sys",
                userPrompt = "usr",
            ),
        )
        val gen25 = req25.getJSONObject("generationConfig")
        assertEquals(65_536, gen25.getInt("maxOutputTokens"))
        assertEquals(0.0, gen25.getDouble("temperature"), 0.0001)
        assertEquals(0, gen25.getJSONObject("thinkingConfig").getInt("thinkingBudget"))

        val req20 = JSONObject(
            provider.buildGenerateContentRequestJson(
                modelName = "gemini-2.0-flash",
                systemPrompt = "sys",
                userPrompt = "usr",
            ),
        )
        val gen20 = req20.getJSONObject("generationConfig")
        assertEquals(8_192, gen20.getInt("maxOutputTokens"))
        assertEquals(0.0, gen20.getDouble("temperature"), 0.0001)
        assertFalse(gen20.has("thinkingConfig"))
    }

    @Test
    fun restTransportSkipsThoughtPartsAndHandlesMaxTokensWithoutFailing() = runBlocking {
        var capturedUrl = ""
        var capturedApiKey = ""
        val partialTruncatedEnvelope = """
            {
              "candidates": [
                {
                  "finishReason": "MAX_TOKENS",
                  "content": {
                    "role": "model",
                    "parts": [
                      {
                        "thought": true,
                        "text": "Internal reasoning should be ignored"
                      },
                      {
                        "text": "{\"transactions\":[{\"assetName\":\"HDFC Mid-Cap Opportunities Fund\",\"assetClass\":\"MUTUAL_FUND\",\"transactionType\":\"BUY\",\"quantity\":\"12.500\",\"price\":\"100.00\",\"netAmount\":\"1250.00\",\"tradeDate\":\"2026-05-10\",\"isin\":\"INF179K01CR2\"},{\"assetName\":\"Partial Cut"
                      }
                    ]
                  }
                }
              ]
            }
        """.trimIndent()

        val restProvider = GeminiAiProvider(
            delayFn = { },
            generateContentFn = null,
            httpPostFn = { url, apiKey, _ ->
                capturedUrl = url
                capturedApiKey = apiKey
                GeminiAiProvider.HttpPostResult(200, partialTruncatedEnvelope)
            },
        )

        val result = restProvider.extractTransactionsWithContext(
            rawText = "--- PAGE 1 ---\nHDFC Mid-Cap SIP 1250.00",
            apiKey = "AIza-test-key",
            documentId = "doc-rest-max-tokens",
        )

        assertTrue(result.isSuccess)
        assertEquals(
            "https://generativelanguage.googleapis.com/v1beta/models/gemini-3.8-flash:generateContent",
            capturedUrl,
        )
        assertEquals("AIza-test-key", capturedApiKey)
        val batch = result.getOrThrow()
        assertTrue(batch.wasTruncated)
        assertEquals(1, batch.drafts.size)
        assertEquals("HDFC Mid-Cap Opportunities Fund", batch.drafts.single().assetName)
        assertEquals("INF179K01CR2", batch.drafts.single().isin)
    }

    @Test
    fun restMaxTokensWithEmptyTextTriggersTruncationSplitInsteadOfError() = runBlocking {
        val emptyMaxTokensEnvelope = """
            {
              "candidates": [
                {
                  "finishReason": "MAX_TOKENS",
                  "content": {
                    "role": "model",
                    "parts": []
                  }
                }
              ]
            }
        """.trimIndent()

        val restProvider = GeminiAiProvider(
            delayFn = { },
            generateContentFn = null,
            httpPostFn = { _, _, _ -> GeminiAiProvider.HttpPostResult(200, emptyMaxTokensEnvelope) },
        )

        val result = restProvider.extractTransactionsWithContext(
            rawText = "--- PAGE 1 ---\nDense table",
            apiKey = "AIza-test-key",
            documentId = "doc-empty-max-tokens",
        )

        assertTrue(result.isSuccess)
        val batch = result.getOrThrow()
        assertTrue(batch.wasTruncated)
        assertTrue(batch.drafts.isEmpty())
    }

    @Test
    fun restSafetyBlockAndHttpErrorsBehaveDeterministically() = runBlocking {
        val safetyProvider = GeminiAiProvider(
            delayFn = { },
            generateContentFn = null,
            httpPostFn = { _, _, _ ->
                GeminiAiProvider.HttpPostResult(
                    200,
                    """{"candidates":[{"finishReason":"SAFETY","content":{"parts":[]}}]}""",
                )
            },
        )
        val safetyRes = safetyProvider.extractTransactionsWithContext("text", "key", "doc-safety")
        assertTrue(safetyRes.isFailure)
        assertTrue(safetyRes.exceptionOrNull()?.message?.contains("SAFETY") == true)

        var http400Attempts = 0
        val badRequestProvider = GeminiAiProvider(
            delayFn = { },
            generateContentFn = null,
            httpPostFn = { _, _, _ ->
                http400Attempts++
                GeminiAiProvider.HttpPostResult(
                    400,
                    """{"error":{"code":400,"message":"Invalid argument","status":"INVALID_ARGUMENT"}}""",
                )
            },
        )
        val badReqRes = badRequestProvider.extractTransactionsWithContext("text", "key", "doc-400")
        assertTrue(badReqRes.isFailure)
        assertEquals(1, http400Attempts)
        assertTrue(badReqRes.exceptionOrNull()?.message?.contains("Invalid argument") == true)

        var http429Attempts = 0
        val recordedDelays = mutableListOf<Long>()
        val rateLimitedProvider = GeminiAiProvider(
            delayFn = { recordedDelays.add(it) },
            generateContentFn = null,
            httpPostFn = { _, _, _ ->
                http429Attempts++
                if (http429Attempts == 1) {
                    GeminiAiProvider.HttpPostResult(
                        429,
                        """{"error":{"code":429,"message":"Rate limit exceeded","status":"RESOURCE_EXHAUSTED"}}""",
                    )
                } else {
                    GeminiAiProvider.HttpPostResult(
                        200,
                        """{"candidates":[{"finishReason":"STOP","content":{"parts":[{"text":"{\"transactions\":[],\"carryOverContext\":{}}"}]}}]}""",
                    )
                }
            },
        )
        val retryRes = rateLimitedProvider.extractTransactionsWithContext("text", "key", "doc-429")
        assertTrue(retryRes.isSuccess)
        assertEquals(2, http429Attempts)
        assertEquals(listOf(2_000L), recordedDelays)
    }

    @Test
    fun normalizesAiTransactionTypesSanitizesMalformedNumbersAndScrubsPiiPrompt() = runBlocking {
        var capturedPrompt = ""
        val provider = GeminiAiProvider(
            delayFn = { },
            generateContentFn = { _, prompt ->
                capturedPrompt = prompt
                """
                {
                  "transactions": [
                    {
                      "assetName": "HDFC Flexi Cap",
                      "transactionType": "SIP",
                      "quantity": "12.50",
                      "price": "₹ 1,200.00",
                      "netAmount": "15,000.00",
                      "confidence": 94
                    },
                    {
                      "assetName": "Strange Holding",
                      "transactionType": "FUTURE_WEIRD_TYPE",
                      "quantity": "N/A",
                      "price": "--",
                      "netAmount": "(2,500.50)",
                      "confidence": 92
                    }
                  ],
                  "carryOverContext": {}
                }
                """.trimIndent()
            },
        )

        val result = provider.extractTransactionsWithContext(
            rawText = "PAN: ABCDE1234F Folio No: 998877/11 HDFCBANK ISIN: INF179K01BE2",
            apiKey = "AIza-test",
            documentId = "doc-sanitize",
            priorContext = AiCarryOverContext(activeFolio = "998877/11", activeAccountId = "ACC-123"),
        ).getOrThrow()

        assertFalse(capturedPrompt.contains("ABCDE1234F"))
        assertFalse(capturedPrompt.contains("998877/11"))
        assertFalse(capturedPrompt.contains("ACC-123"))
        assertTrue(capturedPrompt.contains("INF179K01BE2"))

        assertEquals(2, result.drafts.size)
        val sipDraft = result.drafts[0]
        assertEquals("BUY", sipDraft.transactionType)
        assertEquals("12.50", sipDraft.quantity)
        assertEquals("1200.00", sipDraft.price)
        assertEquals("15000.00", sipDraft.netAmount)
        assertEquals(94, sipDraft.confidence)

        val malformedDraft = result.drafts[1]
        assertEquals("OTHER", malformedDraft.transactionType)
        assertEquals("1", malformedDraft.quantity)
        assertEquals("0", malformedDraft.price)
        assertEquals("-2500.50", malformedDraft.netAmount)
        assertEquals(65, malformedDraft.confidence)
    }
}
