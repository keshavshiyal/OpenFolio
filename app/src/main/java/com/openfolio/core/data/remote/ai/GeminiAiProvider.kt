package com.openfolio.core.data.remote.ai

import android.util.Log
import com.google.ai.client.generativeai.GenerativeModel
import com.google.ai.client.generativeai.type.content
import com.google.ai.client.generativeai.type.generationConfig
import com.openfolio.core.data.local.entity.DraftTransactionEntity
import com.openfolio.core.utils.AssetNameNormalizer
import com.openfolio.core.utils.DateParser
import java.math.BigDecimal
import java.net.SocketTimeoutException
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.UUID
import java.util.concurrent.TimeoutException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

@Singleton
class GeminiAiProvider internal constructor(
    private val delayFn: suspend (Long) -> Unit,
    private val generateContentFn: (suspend (apiKey: String, rawText: String) -> String)?,
) : TruncationAwareAiProvider {

    @Inject
    constructor() : this(
        delayFn = { millis -> delay(millis) },
        generateContentFn = null,
    )

    internal data class ParsedAiResponse(
        val drafts: List<DraftTransactionEntity>,
        val wasTruncated: Boolean,
    )

    @Volatile
    private var cachedModelKey: Pair<String, String>? = null

    @Volatile
    private var cachedModel: GenerativeModel? = null

    private val modelLock = Any()

    private fun getOrCreateModel(apiKey: String): GenerativeModel {
        val today = LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE)
        val cacheKey = apiKey to today
        cachedModel?.takeIf { cachedModelKey == cacheKey }?.let { return it }

        synchronized(modelLock) {
            cachedModel?.takeIf { cachedModelKey == cacheKey }?.let { return it }
            val created = GenerativeModel(
                modelName = MODEL_NAME,
                apiKey = apiKey,
                generationConfig = generationConfig {
                    temperature = 0f
                    maxOutputTokens = MAX_OUTPUT_TOKENS
                    responseMimeType = "application/json"
                },
                systemInstruction = content {
                    text(buildSystemPrompt(today))
                },
            )
            cachedModelKey = cacheKey
            cachedModel = created
            return created
        }
    }

    override suspend fun extractTransactions(
        rawText: String,
        apiKey: String,
        documentId: String,
    ): Result<List<DraftTransactionEntity>> =
        extractTransactionsDetailed(rawText, apiKey, documentId).map { it.drafts }

    override suspend fun extractTransactionsDetailed(
        rawText: String,
        apiKey: String,
        documentId: String,
    ): Result<AiExtractionBatchResult> = withContext(Dispatchers.IO) {
        try {
            require(apiKey.isNotBlank()) { "API key must not be blank." }
            require(rawText.isNotBlank()) { "Raw text must not be blank." }

            val responseText = executeWithRetry {
                if (generateContentFn != null) {
                    generateContentFn.invoke(apiKey, rawText)
                } else {
                    val model = getOrCreateModel(apiKey)
                    val response = model.generateContent(rawText)
                    response.text.orEmpty().trim()
                }
            }
            val parsed = parseJsonResponseWithMetadata(responseText, documentId)
            Result.success(
                AiExtractionBatchResult(
                    drafts = parsed.drafts,
                    wasTruncated = parsed.wasTruncated,
                ),
            )
        } catch (ce: CancellationException) {
            throw ce
        } catch (exception: Throwable) {
            runCatching { Log.e("GeminiAiProvider", "Gemini API call failed: ${exception.message}", exception) }
            Result.failure(exception)
        }
    }

    internal suspend fun <T> executeWithRetry(
        maxAttempts: Int = MAX_RETRY_ATTEMPTS,
        initialDelayMs: Long = INITIAL_RETRY_DELAY_MS,
        maxDelayMs: Long = MAX_RETRY_DELAY_MS,
        block: suspend () -> T,
    ): T {
        var delayMillis = initialDelayMs
        var lastError: Throwable? = null
        for (attempt in 1..maxAttempts) {
            currentCoroutineContext().ensureActive()
            try {
                return block()
            } catch (ce: CancellationException) {
                throw ce
            } catch (error: Throwable) {
                lastError = error
                if (attempt == maxAttempts || !isRetryableError(error)) {
                    throw error
                }
                delayFn(delayMillis.coerceAtMost(maxDelayMs))
                delayMillis = (delayMillis * 2).coerceAtMost(maxDelayMs)
            }
        }
        throw lastError ?: IllegalStateException("Retry attempts exhausted.")
    }

    internal fun isRetryableError(error: Throwable): Boolean {
        val chain = generateSequence(error) { it.cause }.toList()
        if (chain.any { it is IllegalArgumentException || it is JSONException }) {
            return false
        }
        return chain.any { t ->
            if (t is SocketTimeoutException || t is TimeoutException) {
                return@any true
            }
            val msg = t.message.orEmpty().lowercase()
            val cls = t::class.java.simpleName.lowercase()
            if (msg.contains("api key") || msg.contains("401") || msg.contains("403") || msg.contains("400")) {
                return@any false
            }
            msg.contains("429") ||
                msg.contains("resource_exhausted") ||
                msg.contains("resource exhausted") ||
                msg.contains("too many requests") ||
                msg.contains("rate limit") ||
                msg.contains("503") ||
                msg.contains("service unavailable") ||
                msg.contains("timeout") ||
                msg.contains("timed out") ||
                cls.contains("quota") ||
                cls.contains("timeout")
        }
    }

    internal fun parseJsonResponse(
        responseText: String,
        documentId: String,
    ): List<DraftTransactionEntity> =
        parseJsonResponseWithMetadata(responseText, documentId).drafts

    internal fun parseJsonResponseWithMetadata(
        responseText: String,
        documentId: String,
    ): ParsedAiResponse {
        var cleaned = responseText.trim()
            .removePrefix("```json")
            .removePrefix("```JSON")
            .removePrefix("```")
            .removeSuffix("```")
            .trim()

        val firstBracket = cleaned.indexOf('[')
        if (firstBracket > 0) {
            cleaned = cleaned.substring(firstBracket).trim()
        }

        return try {
            val jsonArray = JSONArray(cleaned)
            val objects = buildList(jsonArray.length()) {
                for (i in 0 until jsonArray.length()) {
                    jsonArray.optJSONObject(i)?.let(::add)
                }
            }
            ParsedAiResponse(
                drafts = mapJsonObjectsToDrafts(objects, documentId),
                wasTruncated = false,
            )
        } catch (jsonEx: JSONException) {
            val salvagedObjects = salvageCompleteJsonObjects(cleaned)
            val looksLikeArray = cleaned.startsWith("[")
            if (salvagedObjects.isEmpty() && !looksLikeArray) {
                throw jsonEx
            }
            ParsedAiResponse(
                drafts = mapJsonObjectsToDrafts(salvagedObjects, documentId),
                wasTruncated = true,
            )
        }
    }

    private fun salvageCompleteJsonObjects(raw: String): List<JSONObject> {
        val salvaged = mutableListOf<JSONObject>()
        var inString = false
        var escapeNext = false
        var braceDepth = 0
        var objectStart = -1

        for (i in raw.indices) {
            val ch = raw[i]
            if (escapeNext) {
                escapeNext = false
                continue
            }
            if (inString) {
                when (ch) {
                    '\\' -> escapeNext = true
                    '"' -> inString = false
                }
                continue
            }
            when (ch) {
                '"' -> inString = true
                '{' -> {
                    if (braceDepth == 0) {
                        objectStart = i
                    }
                    braceDepth++
                }
                '}' -> {
                    if (braceDepth > 0) {
                        braceDepth--
                        if (braceDepth == 0 && objectStart >= 0) {
                            val objectText = raw.substring(objectStart, i + 1)
                            runCatching { JSONObject(objectText) }.getOrNull()?.let(salvaged::add)
                            objectStart = -1
                        }
                    }
                }
            }
        }
        return salvaged
    }

    private fun mapJsonObjectsToDrafts(
        objects: List<JSONObject>,
        documentId: String,
    ): List<DraftTransactionEntity> {
        val transactions = mutableListOf<DraftTransactionEntity>()
        for (obj in objects) {
            val assetName = obj.optString("assetName", "").trim()
            if (assetName.isBlank()) continue

            val rawAssetClass = obj.optString("assetClass", "STOCK").trim().uppercase()
            val assetClass = if (rawAssetClass in ALLOWED_ASSET_CLASSES) rawAssetClass else "STOCK"

            val transactionType = obj.optString("transactionType", "BUY").trim().uppercase()
            val quantity = extractJsonString(obj, "quantity", "1")
            val price = extractJsonString(obj, "price", "0")
            val netAmount = extractJsonString(obj, "netAmount", "0")
            val tradeDateStr = obj.optString("tradeDate", "").trim()
            val confidence = obj.optInt("confidence", 85).coerceIn(0, 100)

            val broker = obj.optString("broker", "").trim().takeIf(String::isNotBlank)
            val rawIsin = obj.optString("isin", "").trim().takeIf(String::isNotBlank)
            val ticker = obj.optString("ticker", "").trim().uppercase().takeIf(String::isNotBlank)
            val isin = rawIsin?.let(AssetNameNormalizer::extractIsin) ?: AssetNameNormalizer.extractIsin(assetName)

            val tradeDateMillis = parseTradeDate(tradeDateStr)

            transactions.add(
                DraftTransactionEntity(
                    id = UUID.randomUUID().toString(),
                    documentId = documentId,
                    assetName = assetName,
                    transactionType = transactionType,
                    quantity = quantity,
                    price = price,
                    netAmount = netAmount,
                    confidence = confidence,
                    isAccepted = false,
                    isManual = false,
                    tradeDate = tradeDateMillis,
                    assetClass = assetClass,
                    broker = broker,
                    isin = isin,
                    ticker = ticker,
                ),
            )
        }
        return transactions
    }

    private fun extractJsonString(obj: JSONObject, key: String, default: String): String {
        if (obj.isNull(key)) return default
        val stringValue = when (val raw = obj.opt(key)) {
            null -> default
            is String -> raw.trim().ifBlank { default }
            is Number -> raw.toString().trim().ifBlank { default }
            else -> raw.toString().trim().ifBlank { default }
        }
        return runCatching {
            val clean = stringValue.replace(",", "").trim()
            BigDecimal(clean).toPlainString()
        }.getOrDefault(stringValue)
    }

    internal fun parseTradeDate(tradeDateStr: String): Long? =
        DateParser.parseToEpochMilli(tradeDateStr)

    companion object {
        private const val MODEL_NAME = "gemini-2.5-flash"
        private const val MAX_OUTPUT_TOKENS = 8192
        private const val MAX_RETRY_ATTEMPTS = 4
        private const val INITIAL_RETRY_DELAY_MS = 2_000L
        private const val MAX_RETRY_DELAY_MS = 30_000L
        private val ALLOWED_ASSET_CLASSES = setOf(
            "STOCK", "MUTUAL_FUND", "BOND", "FD", "HOUSE", "LAND", "GOLD", "CRYPTO",
        )

        fun buildSystemPrompt(today: String = LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE)): String =
            """
            You are an expert financial parser. Extract all transactions and holdings from the provided text exhaustively. Return ONLY a valid JSON array of objects without markdown formatting like ```json.

            CRITICAL: If the provided text does not contain financial transactions, stock holdings, or bank statements (e.g., it is a random letter, invoice, or unrelated document), you MUST return an empty JSON array: [].

            Each JSON object MUST include the following fields in the schema:
            - assetName (string): Full name or trading symbol of the asset/security.
            - assetClass (string): Category of asset. Allowed values: STOCK, MUTUAL_FUND, BOND, FD, HOUSE, LAND, GOLD, CRYPTO. Default to STOCK if uncertain.
            - transactionType (string): BUY, SELL, DIVIDEND, INTEREST, FEE, TAX, or CHARGE. For holdings statements, use BUY.
            - quantity (string): Numeric quantity of shares/units as a string (e.g., "10", "144.8"). Never output floating point approximations or scientific notation.
            - price (string): Price per share/unit or average cost basis as a string.
            - netAmount (string): Total transaction amount or current/invested value as a string.
            - tradeDate (string): Transaction date in YYYY-MM-DD format. The tradeDate field is mandatory in the JSON schema. You MUST gracefully strip any time component (e.g., "12-03-2025 14:30:00" -> "2025-03-12"). If you are processing a Holdings Statement (which lacks transaction dates), you must use the current date ($today) in YYYY-MM-DD format.
            - isin (string, optional): 12-character International Securities Identification Number starting with two letters (e.g., "INE002A01018", "INF209K01157"). Extract aggressively wherever present in the text, scheme name, or security details.
            - ticker (string, optional): Trading symbol or ticker (e.g., "RELIANCE", "INFY", "HDFCBANK"). Extract aggressively wherever present.
            - broker (string, optional): Broker, financial institution, or depository where the transaction was executed (e.g., "Zerodha", "Groww", "HDFC Bank", "CAMS", "KFintech", "ICICI Direct").
            - confidence (integer 0-100): Assign a confidence score of 95-100 if you clearly find the asset name, quantity, and price. Assign 80-94 if one field is missing. Assign below 80 only if the data is ambiguous.
            """.trimIndent()

        val SYSTEM_PROMPT: String
            get() = buildSystemPrompt()
    }
}
