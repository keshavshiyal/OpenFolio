package com.openfolio.core.data.remote.ai

import android.util.Log
import com.google.ai.client.generativeai.GenerativeModel
import com.google.ai.client.generativeai.type.content
import com.google.ai.client.generativeai.type.generationConfig
import com.openfolio.core.data.local.entity.DraftTransactionEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
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

        for (i in 0 until jsonArray.length()) {
            val obj = jsonArray.getJSONObject(i)
            val assetName = obj.optString("assetName", "").trim()
            if (assetName.isBlank()) continue

            val transactionType = obj.optString("transactionType", "BUY").trim().uppercase()
            val quantity = obj.optString("quantity", "1").trim()
            val price = obj.optString("price", "0").trim()
            val netAmount = obj.optString("netAmount", "0").trim()
            val tradeDateStr = obj.optString("tradeDate", "").trim()
            val confidence = obj.optInt("confidence", 85).coerceIn(0, 100)

            val tradeDateMillis = parseTradeDate(tradeDateStr)

            transactions.add(
                DraftTransactionEntity(
                    id = UUID.randomUUID().toString(),
                    documentId = documentId,
                    assetName = assetName,
                    transactionType = transactionType,
                    quantity = quantity.ifBlank { "1" },
                    price = price.ifBlank { "0" },
                    netAmount = netAmount.ifBlank { "0" },
                    confidence = confidence,
                    isAccepted = false,
                    isManual = false,
                    tradeDate = tradeDateMillis,
                ),
            )
        }

        return transactions
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
        const val SYSTEM_PROMPT =
            "You are an expert financial parser. Extract all transactions from the provided text. Return ONLY a valid JSON array of objects. Do not include markdown formatting like ```json. Each object must have: assetName (string), transactionType (string: BUY, SELL, DIVIDEND, INTEREST, FEE, TAX, CHARGE), quantity (string), price (string), netAmount (string), tradeDate (string: YYYY-MM-DD), and confidence (integer 0-100)."
    }
}
