package com.openfolio.core.data.remote.ai

import android.util.Log
import com.openfolio.core.data.local.entity.DraftTransactionEntity
import com.openfolio.core.utils.AssetNameNormalizer
import com.openfolio.core.utils.DateParser
import java.io.IOException
import java.math.BigDecimal
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.UUID
import java.util.concurrent.TimeoutException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
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
    private val defaultModelName: String = DEFAULT_MODEL_NAME,
    private val httpPostFn: (suspend (endpointUrl: String, apiKey: String, requestBody: String) -> HttpPostResult)? = null,
) : ContextualAiProvider {

    @Inject
    constructor() : this(
        delayFn = { millis -> delay(millis) },
        generateContentFn = null,
        defaultModelName = DEFAULT_MODEL_NAME,
        httpPostFn = null,
    )

    internal data class HttpPostResult(
        val statusCode: Int,
        val body: String,
    )

    internal class GeminiApiHttpException(
        val statusCode: Int,
        message: String,
    ) : IOException(message)

    internal data class ParsedAiResponse(
        val drafts: List<DraftTransactionEntity>,
        val wasTruncated: Boolean,
        val carryOverContext: AiCarryOverContext = AiCarryOverContext(),
    )

    internal fun resolveModelName(modelName: String?): String =
        modelName?.trim()?.takeIf(String::isNotBlank) ?: defaultModelName

    internal fun buildEndpointUrl(modelName: String): String {
        val cleanModel = resolveModelName(modelName).removePrefix("models/")
        val encodedModel = URLEncoder.encode(cleanModel, StandardCharsets.UTF_8.name())
        return "$GEMINI_API_BASE_URL/models/$encodedModel:generateContent"
    }

    internal fun buildGenerateContentRequestJson(
        modelName: String,
        systemPrompt: String,
        userPrompt: String,
    ): String {
        val generationConfig = JSONObject().apply {
            put("responseMimeType", "application/json")
            put("maxOutputTokens", maxOutputTokensForModel(modelName))
            if (supportsLegacyTemperature(modelName)) {
                put("temperature", 0.0)
            }
            thinkingConfigForModel(modelName)?.let { put("thinkingConfig", it) }
        }

        val systemInstruction = JSONObject().put(
            "parts",
            JSONArray().put(JSONObject().put("text", systemPrompt)),
        )

        val userContent = JSONObject().apply {
            put("role", "user")
            put("parts", JSONArray().put(JSONObject().put("text", userPrompt)))
        }

        return JSONObject().apply {
            put("systemInstruction", systemInstruction)
            put("contents", JSONArray().put(userContent))
            put("generationConfig", generationConfig)
        }.toString()
    }

    internal fun parseGenerateContentResponseJson(
        responseBody: String,
        documentId: String,
    ): ParsedAiResponse {
        val root = JSONObject(responseBody)
        val candidates = root.optJSONArray("candidates")
        if (candidates == null || candidates.length() == 0) {
            val blockReason = root.optJSONObject("promptFeedback")
                ?.optString("blockReason", "")
                ?.trim()
                .orEmpty()
            if (blockReason.isNotEmpty()) {
                throw IllegalStateException("Content generation blocked. Reason: $blockReason")
            }
            throw IllegalStateException("Gemini API returned no candidates.")
        }

        val candidate = candidates.optJSONObject(0)
            ?: throw IllegalStateException("Gemini API returned an invalid candidate.")
        val finishReason = candidate.optString("finishReason", "").trim().uppercase()

        val parts = candidate.optJSONObject("content")?.optJSONArray("parts")
        val textBuilder = StringBuilder()
        if (parts != null) {
            for (i in 0 until parts.length()) {
                val part = parts.optJSONObject(i) ?: continue
                if (part.optBoolean("thought", false)) continue
                val text = part.optString("text", "")
                if (text.isNotEmpty()) {
                    textBuilder.append(text)
                }
            }
        }
        val responseText = textBuilder.toString().trim()

        if (finishReason in BLOCKED_FINISH_REASONS) {
            throw IllegalStateException("Content generation blocked. Reason: $finishReason")
        }

        if (finishReason == FINISH_REASON_MAX_TOKENS) {
            if (responseText.isBlank()) {
                return ParsedAiResponse(drafts = emptyList(), wasTruncated = true)
            }
            return try {
                parseJsonResponseWithMetadata(responseText, documentId).copy(wasTruncated = true)
            } catch (_: JSONException) {
                ParsedAiResponse(drafts = emptyList(), wasTruncated = true)
            }
        }

        if (responseText.isBlank()) {
            if (finishReason.isNotBlank() && finishReason != FINISH_REASON_STOP) {
                throw IllegalStateException("Content generation stopped. Reason: $finishReason")
            }
            return ParsedAiResponse(drafts = emptyList(), wasTruncated = false)
        }

        return parseJsonResponseWithMetadata(responseText, documentId)
    }

    private suspend fun executeHttpPost(
        endpointUrl: String,
        apiKey: String,
        requestBody: String,
    ): HttpPostResult = withContext(Dispatchers.IO) {
        val connection = (URL(endpointUrl).openConnection() as HttpURLConnection)
        val job = currentCoroutineContext()[Job]
        val cancelHandle = job?.invokeOnCompletion { cause ->
            if (cause is CancellationException) {
                runCatching { connection.disconnect() }
            }
        }
        try {
            currentCoroutineContext().ensureActive()
            connection.requestMethod = "POST"
            connection.connectTimeout = HTTP_CONNECT_TIMEOUT_MS
            connection.readTimeout = HTTP_READ_TIMEOUT_MS
            connection.doInput = true
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json; charset=UTF-8")
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("x-goog-api-key", apiKey)

            connection.outputStream.use { out ->
                out.write(requestBody.toByteArray(StandardCharsets.UTF_8))
                out.flush()
            }

            val statusCode = connection.responseCode
            val stream = if (statusCode in 200..299) connection.inputStream else connection.errorStream
            val body = stream?.bufferedReader(StandardCharsets.UTF_8)?.use { it.readText() }.orEmpty()
            currentCoroutineContext().ensureActive()
            HttpPostResult(statusCode = statusCode, body = body)
        } catch (ioe: IOException) {
            currentCoroutineContext().ensureActive()
            throw ioe
        } finally {
            cancelHandle?.dispose()
            runCatching { connection.disconnect() }
        }
    }

    private fun parseHttpErrorMessage(statusCode: Int, responseBody: String): String {
        val trimmed = responseBody.trim()
        if (trimmed.isNotEmpty()) {
            val parsed = runCatching {
                val errorObj = JSONObject(trimmed).optJSONObject("error")
                if (errorObj != null) {
                    val apiStatus = errorObj.optString("status", "").trim()
                    val apiMsg = errorObj.optString("message", "").trim()
                    when {
                        apiStatus.isNotEmpty() && apiMsg.isNotEmpty() -> "HTTP $statusCode ($apiStatus): $apiMsg"
                        apiMsg.isNotEmpty() -> "HTTP $statusCode: $apiMsg"
                        else -> null
                    }
                } else {
                    null
                }
            }.getOrNull()
            if (parsed != null) return parsed
        }
        return "HTTP $statusCode from Gemini API"
    }

    override suspend fun extractTransactions(
        rawText: String,
        apiKey: String,
        documentId: String,
    ): Result<List<DraftTransactionEntity>> =
        extractTransactionsWithContext(rawText, apiKey, documentId).map { it.drafts }

    override suspend fun extractTransactionsDetailed(
        rawText: String,
        apiKey: String,
        documentId: String,
    ): Result<AiExtractionBatchResult> =
        extractTransactionsWithContext(rawText, apiKey, documentId)

    override suspend fun extractTransactionsWithContext(
        rawText: String,
        apiKey: String,
        documentId: String,
        priorContext: AiCarryOverContext?,
        modelName: String?,
    ): Result<AiExtractionBatchResult> = withContext(Dispatchers.IO) {
        try {
            require(apiKey.isNotBlank()) { "API key must not be blank." }
            require(rawText.isNotBlank()) { "Raw text must not be blank." }

            val resolvedModelName = resolveModelName(modelName)
            val promptInput = buildBatchPrompt(rawText, priorContext)

            val parsed = executeWithRetry {
                if (generateContentFn != null) {
                    val responseText = generateContentFn.invoke(apiKey, promptInput)
                    parseJsonResponseWithMetadata(responseText, documentId)
                } else {
                    val today = LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE)
                    val requestBody = buildGenerateContentRequestJson(
                        modelName = resolvedModelName,
                        systemPrompt = buildSystemPrompt(today),
                        userPrompt = promptInput,
                    )
                    val endpointUrl = buildEndpointUrl(resolvedModelName)
                    val httpResult = httpPostFn?.invoke(endpointUrl, apiKey, requestBody)
                        ?: executeHttpPost(endpointUrl, apiKey, requestBody)
                    if (httpResult.statusCode !in 200..299) {
                        throw GeminiApiHttpException(
                            statusCode = httpResult.statusCode,
                            message = parseHttpErrorMessage(httpResult.statusCode, httpResult.body),
                        )
                    }
                    parseGenerateContentResponseJson(httpResult.body, documentId)
                }
            }
            Result.success(
                AiExtractionBatchResult(
                    drafts = parsed.drafts,
                    wasTruncated = parsed.wasTruncated,
                    carryOverContext = parsed.carryOverContext,
                ),
            )
        } catch (ce: CancellationException) {
            throw ce
        } catch (exception: Throwable) {
            runCatching { Log.e("GeminiAiProvider", "Gemini API call failed: ${exception.message}", exception) }
            Result.failure(exception)
        }
    }

    internal fun buildBatchPrompt(
        rawText: String,
        priorContext: AiCarryOverContext?,
    ): String {
        val prefix = priorContext?.toPromptPrefix().orEmpty()
        return if (prefix.isNotBlank()) {
            "$prefix\n\n$rawText"
        } else {
            rawText
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
            if (t is GeminiApiHttpException) {
                return@any t.statusCode in RETRYABLE_HTTP_STATUS_CODES
            }
            if (t is SocketTimeoutException || t is TimeoutException) {
                return@any true
            }
            val msg = t.message.orEmpty().lowercase()
            val cls = t::class.java.simpleName.lowercase()
            if (
                msg.contains("api key") ||
                msg.contains("401") ||
                msg.contains("403") ||
                msg.contains("400") ||
                msg.contains("404")
            ) {
                return@any false
            }
            msg.contains("429") ||
                msg.contains("resource_exhausted") ||
                msg.contains("resource exhausted") ||
                msg.contains("too many requests") ||
                msg.contains("rate limit") ||
                msg.contains("500") ||
                msg.contains("502") ||
                msg.contains("503") ||
                msg.contains("504") ||
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

        val firstBrace = cleaned.indexOf('{')
        val firstBracket = cleaned.indexOf('[')
        val isObjectWrapper = firstBrace >= 0 && (firstBracket < 0 || firstBrace < firstBracket)

        if (isObjectWrapper) {
            if (firstBrace > 0) {
                cleaned = cleaned.substring(firstBrace).trim()
            }
            return try {
                val root = JSONObject(cleaned)
                val txArray = root.optJSONArray("transactions") ?: JSONArray()
                val objects = buildList(txArray.length()) {
                    for (i in 0 until txArray.length()) {
                        txArray.optJSONObject(i)?.let(::add)
                    }
                }
                val drafts = mapJsonObjectsToDrafts(objects, documentId)
                val ctxObj = root.optJSONObject("carryOverContext")
                ParsedAiResponse(
                    drafts = drafts,
                    wasTruncated = false,
                    carryOverContext = parseCarryOverContext(ctxObj, drafts),
                )
            } catch (jsonEx: JSONException) {
                val txKeyIdx = cleaned.indexOf("\"transactions\"")
                val arrStart = if (txKeyIdx >= 0) cleaned.indexOf('[', txKeyIdx) else cleaned.indexOf('[')
                if (arrStart < 0) {
                    throw jsonEx
                }
                val arraySection = cleaned.substring(arrStart)
                val salvagedObjects = salvageCompleteJsonObjects(arraySection)
                if (salvagedObjects.isEmpty() && !arraySection.trimStart().startsWith("[")) {
                    throw jsonEx
                }
                val drafts = mapJsonObjectsToDrafts(salvagedObjects, documentId)
                ParsedAiResponse(
                    drafts = drafts,
                    wasTruncated = true,
                    carryOverContext = parseCarryOverContext(null, drafts),
                )
            }
        }

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
            val drafts = mapJsonObjectsToDrafts(objects, documentId)
            ParsedAiResponse(
                drafts = drafts,
                wasTruncated = false,
                carryOverContext = parseCarryOverContext(null, drafts),
            )
        } catch (jsonEx: JSONException) {
            val salvagedObjects = salvageCompleteJsonObjects(cleaned)
            val looksLikeArray = cleaned.startsWith("[")
            if (salvagedObjects.isEmpty() && !looksLikeArray) {
                throw jsonEx
            }
            val drafts = mapJsonObjectsToDrafts(salvagedObjects, documentId)
            ParsedAiResponse(
                drafts = drafts,
                wasTruncated = true,
                carryOverContext = parseCarryOverContext(null, drafts),
            )
        }
    }

    private fun parseCarryOverContext(
        ctxObj: JSONObject?,
        drafts: List<DraftTransactionEntity>,
    ): AiCarryOverContext {
        if (ctxObj != null) {
            val headersArray = ctxObj.optJSONArray("activeColumnHeaders")
            val headers = buildList {
                if (headersArray != null) {
                    for (i in 0 until headersArray.length()) {
                        val header = headersArray.optString(i, "").trim()
                        if (header.isNotEmpty()) add(header)
                    }
                }
            }
            return AiCarryOverContext(
                activeAmc = ctxObj.optString("activeAmc", "").trim(),
                activeScheme = ctxObj.optString("activeScheme", "").trim(),
                activeIsin = ctxObj.optString("activeIsin", "").trim(),
                activeFolio = ctxObj.optString("activeFolio", "").trim(),
                activeBroker = ctxObj.optString("activeBroker", "").trim(),
                activeAssetClass = ctxObj.optString("activeAssetClass", "").trim().uppercase(),
                activeAccountId = ctxObj.optString("activeAccountId", "").trim(),
                activeColumnHeaders = headers,
                incompleteTailText = ctxObj.optString("incompleteTailText", "").trim(),
            )
        }
        val last = drafts.lastOrNull() ?: return AiCarryOverContext()
        return AiCarryOverContext(
            activeScheme = last.assetName,
            activeIsin = last.isin.orEmpty(),
            activeBroker = last.broker.orEmpty(),
            activeAssetClass = last.assetClass,
        )
    }

    private fun salvageCompleteJsonObjects(raw: String): List<JSONObject> {
        val salvaged = mutableListOf<JSONObject>()
        var inString = false
        var escapeNext = false
        var braceDepth = 0
        var bracketDepth = 0
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
                '[' -> {
                    if (braceDepth == 0) {
                        bracketDepth++
                    }
                }
                ']' -> {
                    if (braceDepth == 0) {
                        if (bracketDepth > 0) {
                            bracketDepth--
                        }
                        if (bracketDepth == 0) {
                            break
                        }
                    }
                }
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
            val sourcePage = obj.optInt("sourcePage", 0).takeIf { it > 0 }

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
                    sourcePage = sourcePage,
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
        const val DEFAULT_MODEL_NAME = "gemini-3.8-flash"
        private const val GEMINI_API_BASE_URL = "https://generativelanguage.googleapis.com/v1beta"
        private const val MAX_OUTPUT_TOKENS = 65_536
        private const val LEGACY_MAX_OUTPUT_TOKENS = 8_192
        private const val HTTP_CONNECT_TIMEOUT_MS = 30_000
        private const val HTTP_READ_TIMEOUT_MS = 180_000
        private const val MAX_RETRY_ATTEMPTS = 4
        private const val INITIAL_RETRY_DELAY_MS = 2_000L
        private const val MAX_RETRY_DELAY_MS = 30_000L
        private const val FINISH_REASON_STOP = "STOP"
        private const val FINISH_REASON_MAX_TOKENS = "MAX_TOKENS"
        private val RETRYABLE_HTTP_STATUS_CODES = setOf(429, 500, 502, 503, 504)
        private val BLOCKED_FINISH_REASONS = setOf(
            "SAFETY", "RECITATION", "PROHIBITED_CONTENT", "BLOCKLIST", "SPII", "IMAGE_SAFETY",
        )
        private val ALLOWED_ASSET_CLASSES = setOf(
            "STOCK", "MUTUAL_FUND", "BOND", "SGB", "FD", "HOUSE", "LAND", "VEHICLE", "GOLD", "CRYPTO", "CASH", "OTHER",
        )

        internal fun maxOutputTokensForModel(modelName: String): Int {
            val lower = modelName.trim().lowercase()
            return if (lower.startsWith("gemini-1.") || lower.startsWith("gemini-2.0")) {
                LEGACY_MAX_OUTPUT_TOKENS
            } else {
                MAX_OUTPUT_TOKENS
            }
        }

        internal fun supportsLegacyTemperature(modelName: String): Boolean {
            val lower = modelName.trim().lowercase()
            return lower.startsWith("gemini-1.") || lower.startsWith("gemini-2.")
        }

        internal fun thinkingConfigForModel(modelName: String): JSONObject? {
            val lower = modelName.trim().lowercase()
            return when {
                lower.startsWith("gemini-3.5-flash-lite") ||
                    lower.startsWith("gemini-3.6-flash") ||
                    lower.startsWith("gemini-3.7-flash") ->
                    JSONObject().put("thinkingLevel", "MINIMAL")
                lower.startsWith("gemini-3") || lower.startsWith("gemini-4") ->
                    JSONObject().put("thinkingLevel", "LOW")
                lower.startsWith("gemini-2.5-flash") ->
                    JSONObject().put("thinkingBudget", 0)
                lower.startsWith("gemini-2.5-pro") ->
                    JSONObject().put("thinkingBudget", 128)
                else -> null
            }
        }

        fun buildSystemPrompt(today: String = LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE)): String =
            """
            You are an expert financial parser. Extract all transactions and holdings from the provided text exhaustively. Return ONLY a valid JSON object without markdown formatting like ```json.

            CRITICAL: If the provided text does not contain financial transactions, stock holdings, or bank statements (e.g., it is a random letter, invoice, or unrelated document), you MUST return: {"transactions": [], "carryOverContext": {"activeAmc": "", "activeScheme": "", "activeIsin": "", "activeFolio": "", "activeBroker": "", "activeAssetClass": "", "activeAccountId": "", "activeColumnHeaders": [], "incompleteTailText": ""}}.

            Output JSON schema:
            {
              "transactions": [ ... ],
              "carryOverContext": {
                "activeAmc": "Active Mutual Fund / AMC / Issuer name at end of these pages (or empty)",
                "activeScheme": "Active MF scheme, stock, bond, FD, or asset name at end of these pages (or empty)",
                "activeIsin": "Active 12-char ISIN at end of these pages (or empty)",
                "activeFolio": "Active Folio No at end of these pages (or empty)",
                "activeBroker": "Active Broker / Depository name (or empty)",
                "activeAssetClass": "Active assetClass at end of these pages (or empty)",
                "activeAccountId": "Active Demat / Account / Client ID at end of these pages (or empty)",
                "activeColumnHeaders": ["Column1", "Column2"],
                "incompleteTailText": "Any incomplete split transaction row or trailing header cut off at the bottom of the last page, else empty"
              }
            }

            Cross-page and multi-batch continuation rules:
            1. If a CARRY-OVER CONTEXT FROM PREVIOUS PAGES block is prepended, inherit activeAmc, activeScheme, activeIsin, activeFolio, activeBroker, activeAssetClass, activeAccountId, and activeColumnHeaders for leading rows until a new header supersedes them.
            2. If incompleteTailText or Trailing Lines from Previous Page are provided and the top of the first page below completes a split transaction row (e.g., date/asset on previous page and quantity/price/amount on the first page here, or multi-line wrapped narration across pages), merge them into ONE complete transaction.
            3. Do NOT re-emit any transaction listed under Already Extracted Boundary Rows unless it lacked numbers/date on the previous page and is only now completed here.
            4. If the final transaction at the very bottom of the last page in this batch is cut off mid-row (missing amounts, quantity, or date), do NOT emit an incomplete zero-amount draft; instead copy its exact trailing lines into carryOverContext.incompleteTailText so the next batch completes it.

            Each transaction object inside "transactions" MUST include:
            - assetName (string): Full name or trading symbol of the asset/security.
            - assetClass (string): Category of asset. Allowed values: STOCK, MUTUAL_FUND, BOND, SGB, FD, HOUSE, LAND, VEHICLE, GOLD, CRYPTO, CASH, OTHER. Default to STOCK if uncertain.
            - transactionType (string): BUY, SELL, DIVIDEND, INTEREST, FEE, TAX, CHARGE, TRANSFER_IN, TRANSFER_OUT, SPLIT, or BONUS. For holdings statements, use BUY.
            - quantity (string): Numeric quantity of shares/units as a string (e.g., "10", "144.8"). Never output floating point approximations or scientific notation.
            - price (string): Price per share/unit or average cost basis as a string.
            - netAmount (string): Total transaction amount or current/invested value as a string.
            - tradeDate (string): Transaction date in YYYY-MM-DD format. The tradeDate field is mandatory in the JSON schema. You MUST gracefully strip any time component (e.g., "12-03-2025 14:30:00" -> "2025-03-12"). If you are processing a Holdings Statement (which lacks transaction dates), you must use the current date ($today) in YYYY-MM-DD format.
            - isin (string, optional): 12-character International Securities Identification Number starting with two letters (e.g., "INE002A01018", "INF209K01157"). Extract aggressively wherever present in the text, scheme name, or security details.
            - ticker (string, optional): Trading symbol or ticker (e.g., "RELIANCE", "INFY", "HDFCBANK"). Extract aggressively wherever present.
            - broker (string, optional): Broker, financial institution, or depository where the transaction was executed (e.g., "Zerodha", "Groww", "HDFC Bank", "CAMS", "KFintech", "ICICI Direct").
            - sourcePage (integer, optional): The page number (N) from the "--- PAGE N ---" header where this transaction appears.
            - confidence (integer 0-100): Assign a confidence score of 95-100 if you clearly find the asset name, quantity, and price. Assign 80-94 if one field is missing. Assign below 80 only if the data is ambiguous.
            """.trimIndent()

        val SYSTEM_PROMPT: String
            get() = buildSystemPrompt()
    }
}
