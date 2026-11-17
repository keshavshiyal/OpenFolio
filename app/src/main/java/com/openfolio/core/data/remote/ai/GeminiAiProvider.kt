package com.openfolio.core.data.remote.ai

import android.util.Log
import com.google.ai.client.generativeai.GenerativeModel
import com.google.ai.client.generativeai.type.content
import com.google.ai.client.generativeai.type.generationConfig
import com.openfolio.core.data.local.entity.DraftTransactionEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.math.BigDecimal
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class GeminiAiProvider @Inject constructor() : AiProvider {

    override suspend fun extractTransactions(
        rawText: String,
        apiKey: String,
        documentId: String,
    ): Result<List<DraftTransactionEntity>> = withContext(Dispatchers.IO) {
        runCatching {
            try {
                require(apiKey.isNotBlank()) { "API key must not be blank." }
                require(rawText.isNotBlank()) { "Raw text must not be blank." }

                val model = GenerativeModel(
                    modelName = "gemini-2.5-flash",
                    apiKey = apiKey,
                    generationConfig = generationConfig {
                        responseMimeType = "application/json"
                    },
                    systemInstruction = content {
                        text(SYSTEM_PROMPT)
                    },
                )

                val response = model.generateContent(rawText)
                val responseText = response.text.orEmpty().trim()
                parseJsonResponse(responseText, documentId)
            } catch (exception: Throwable) {
                Log.e("GeminiAiProvider", "Gemini API call failed: ${exception.message}", exception)
                throw exception
            }
        }
    }

    internal fun parseJsonResponse(
        responseText: String,
        documentId: String,
    ): List<DraftTransactionEntity> {
        val cleaned = responseText
            .removePrefix("```json")
            .removePrefix("```JSON")
            .removePrefix("```")
            .removeSuffix("```")
            .trim()

        val jsonArray = JSONArray(cleaned)
        val transactions = mutableListOf<DraftTransactionEntity>()
        val allowedAssetClasses = setOf("STOCK", "MUTUAL_FUND", "BOND", "FD", "HOUSE", "LAND", "GOLD", "CRYPTO")

        for (i in 0 until jsonArray.length()) {
            val obj = jsonArray.getJSONObject(i)
            val assetName = obj.optString("assetName", "").trim()
            if (assetName.isBlank()) continue

            val rawAssetClass = obj.optString("assetClass", "STOCK").trim().uppercase()
            val assetClass = if (rawAssetClass in allowedAssetClasses) rawAssetClass else "STOCK"

            val transactionType = obj.optString("transactionType", "BUY").trim().uppercase()
            val quantity = extractJsonString(obj, "quantity", "1")
            val price = extractJsonString(obj, "price", "0")
            val netAmount = extractJsonString(obj, "netAmount", "0")
            val tradeDateStr = obj.optString("tradeDate", "").trim()
            val confidence = obj.optInt("confidence", 85).coerceIn(0, 100)

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

    internal fun parseTradeDate(tradeDateStr: String): Long? {
        val date = tradeDateStr.trim().takeIf(String::isNotEmpty) ?: return null
        val formatters = listOf(
            DateTimeFormatter.ISO_LOCAL_DATE,
            DateTimeFormatter.ofPattern("d/M/uuuu"),
            DateTimeFormatter.ofPattern("d-M-uuuu"),
            DateTimeFormatter.ofPattern("M/d/uuuu"),
        )
        for (formatter in formatters) {
            try {
                val localDate = LocalDate.parse(date, formatter)
                return localDate.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
            } catch (_: Exception) {
                // try next format
            }
        }
        return null
    }

    companion object {
        fun buildSystemPrompt(today: String = LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE)): String =
            """
            You are an expert financial parser. Extract all transactions and holdings from the provided text. Return ONLY a valid JSON array of objects without markdown formatting like ```json.

            CRITICAL: If the provided text does not contain financial transactions, stock holdings, or bank statements (e.g., it is a random letter, invoice, or unrelated document), you MUST return an empty JSON array: [].

            Each JSON object MUST include the following fields in the schema:
            - assetName (string): Full name or trading symbol of the asset/security.
            - assetClass (string): Category of asset. Allowed values: STOCK, MUTUAL_FUND, BOND, FD, HOUSE, LAND, GOLD, CRYPTO. Default to STOCK if uncertain.
            - transactionType (string): BUY, SELL, DIVIDEND, INTEREST, FEE, TAX, or CHARGE. For holdings statements, use BUY.
            - quantity (string): Numeric quantity of shares/units as a string (e.g., "10", "144.8"). Never output floating point approximations or scientific notation.
            - price (string): Price per share/unit or average cost basis as a string.
            - netAmount (string): Total transaction amount or current/invested value as a string.
            - tradeDate (string): Transaction date in YYYY-MM-DD format. The tradeDate field is mandatory in the JSON schema. If you are processing a Holdings Statement (which lacks transaction dates), you must use the current date ($today) in YYYY-MM-DD format.
            - confidence (integer 0-100): Assign a confidence score of 95-100 if you clearly find the asset name, quantity, and price. Assign 80-94 if one field is missing. Assign below 80 only if the data is ambiguous.
            """.trimIndent()

        val SYSTEM_PROMPT: String
            get() = buildSystemPrompt()
    }
}
