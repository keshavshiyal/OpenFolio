package com.openfolio.core.data.remote.ai

import com.openfolio.core.data.local.entity.DraftTransactionEntity
import org.json.JSONArray
import org.json.JSONObject

data class AiCarryOverContext(
    val activeAmc: String = "",
    val activeScheme: String = "",
    val activeIsin: String = "",
    val activeFolio: String = "",
    val activeBroker: String = "",
    val activeColumnHeaders: List<String> = emptyList(),
    val incompleteTailText: String = "",
) {
    fun isEmpty(): Boolean =
        activeAmc.isBlank() &&
            activeScheme.isBlank() &&
            activeIsin.isBlank() &&
            activeFolio.isBlank() &&
            activeBroker.isBlank() &&
            activeColumnHeaders.isEmpty() &&
            incompleteTailText.isBlank()

    fun toJson(): String {
        val obj = JSONObject()
        obj.put("activeAmc", activeAmc)
        obj.put("activeScheme", activeScheme)
        obj.put("activeIsin", activeIsin)
        obj.put("activeFolio", activeFolio)
        obj.put("activeBroker", activeBroker)
        obj.put("activeColumnHeaders", JSONArray(activeColumnHeaders))
        obj.put("incompleteTailText", incompleteTailText)
        return obj.toString()
    }

    fun toPromptPrefix(): String {
        if (isEmpty()) return ""
        return buildString {
            appendLine("=== CARRY-OVER CONTEXT FROM PREVIOUS PAGES ===")
            if (activeAmc.isNotBlank()) appendLine("Active AMC / Fund House: $activeAmc")
            if (activeScheme.isNotBlank()) appendLine("Active Scheme / Security: $activeScheme")
            if (activeIsin.isNotBlank()) appendLine("Active ISIN: $activeIsin")
            if (activeFolio.isNotBlank()) appendLine("Active Folio No: $activeFolio")
            if (activeBroker.isNotBlank()) appendLine("Active Broker / Depository: $activeBroker")
            if (activeColumnHeaders.isNotEmpty()) {
                appendLine("Active Column Headers: ${activeColumnHeaders.joinToString(" | ")}")
            }
            if (incompleteTailText.isNotBlank()) {
                appendLine("Incomplete Tail Text from Previous Batch (complete if continued below):")
                appendLine(incompleteTailText)
            }
            appendLine("=== END CARRY-OVER CONTEXT ===")
        }.trim()
    }

    companion object {
        fun fromJson(rawJson: String?): AiCarryOverContext? {
            if (rawJson.isNullOrBlank()) return null
            return runCatching {
                val obj = JSONObject(rawJson)
                val headersArray = obj.optJSONArray("activeColumnHeaders")
                val headers = buildList {
                    if (headersArray != null) {
                        for (i in 0 until headersArray.length()) {
                            val header = headersArray.optString(i, "").trim()
                            if (header.isNotEmpty()) add(header)
                        }
                    }
                }
                AiCarryOverContext(
                    activeAmc = obj.optString("activeAmc", "").trim(),
                    activeScheme = obj.optString("activeScheme", "").trim(),
                    activeIsin = obj.optString("activeIsin", "").trim(),
                    activeFolio = obj.optString("activeFolio", "").trim(),
                    activeBroker = obj.optString("activeBroker", "").trim(),
                    activeColumnHeaders = headers,
                    incompleteTailText = obj.optString("incompleteTailText", "").trim(),
                )
            }.getOrNull()
        }
    }
}

data class AiExtractionBatchResult(
    val drafts: List<DraftTransactionEntity>,
    val wasTruncated: Boolean = false,
    val carryOverContext: AiCarryOverContext = AiCarryOverContext(),
)

interface AiProvider {
    suspend fun extractTransactions(
        rawText: String,
        apiKey: String,
        documentId: String = "",
    ): Result<List<DraftTransactionEntity>>
}

interface TruncationAwareAiProvider : AiProvider {
    suspend fun extractTransactionsDetailed(
        rawText: String,
        apiKey: String,
        documentId: String = "",
    ): Result<AiExtractionBatchResult>
}

interface ContextualAiProvider : TruncationAwareAiProvider {
    suspend fun extractTransactionsWithContext(
        rawText: String,
        apiKey: String,
        documentId: String = "",
        priorContext: AiCarryOverContext? = null,
        modelName: String? = null,
    ): Result<AiExtractionBatchResult>
}
