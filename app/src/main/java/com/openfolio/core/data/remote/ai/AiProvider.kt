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
    val activeAssetClass: String = "",
    val activeAccountId: String = "",
    val activeColumnHeaders: List<String> = emptyList(),
    val incompleteTailText: String = "",
    val fallbackTailLines: String = "",
    val recentBoundaryTransactions: List<String> = emptyList(),
) {
    fun isEmpty(): Boolean =
        activeAmc.isBlank() &&
            activeScheme.isBlank() &&
            activeIsin.isBlank() &&
            activeFolio.isBlank() &&
            activeBroker.isBlank() &&
            activeAssetClass.isBlank() &&
            activeAccountId.isBlank() &&
            activeColumnHeaders.isEmpty() &&
            incompleteTailText.isBlank() &&
            fallbackTailLines.isBlank() &&
            recentBoundaryTransactions.isEmpty()

    fun toJson(): String {
        val obj = JSONObject()
        obj.put("activeAmc", activeAmc)
        obj.put("activeScheme", activeScheme)
        obj.put("activeIsin", activeIsin)
        obj.put("activeFolio", activeFolio)
        obj.put("activeBroker", activeBroker)
        obj.put("activeAssetClass", activeAssetClass)
        obj.put("activeAccountId", activeAccountId)
        obj.put("activeColumnHeaders", JSONArray(activeColumnHeaders))
        obj.put("incompleteTailText", incompleteTailText)
        obj.put("fallbackTailLines", fallbackTailLines)
        obj.put("recentBoundaryTransactions", JSONArray(recentBoundaryTransactions))
        return obj.toString()
    }

    fun toPromptPrefix(): String {
        if (isEmpty()) return ""
        return buildString {
            appendLine("=== CARRY-OVER CONTEXT FROM PREVIOUS PAGES ===")
            if (activeAssetClass.isNotBlank()) appendLine("Active Asset Class: $activeAssetClass")
            if (activeAmc.isNotBlank()) appendLine("Active AMC / Fund House / Issuer: $activeAmc")
            if (activeScheme.isNotBlank()) appendLine("Active Scheme / Security / Asset: $activeScheme")
            if (activeIsin.isNotBlank()) appendLine("Active ISIN: $activeIsin")
            if (activeFolio.isNotBlank()) appendLine("Active Folio No: $activeFolio")
            if (activeAccountId.isNotBlank()) appendLine("Active Account / Demat ID: $activeAccountId")
            if (activeBroker.isNotBlank()) appendLine("Active Broker / Depository: $activeBroker")
            if (activeColumnHeaders.isNotEmpty()) {
                appendLine("Active Column Headers: ${activeColumnHeaders.joinToString(" | ")}")
            }
            if (incompleteTailText.isNotBlank()) {
                appendLine("Incomplete Tail Text from Previous Batch (complete this split row if continued below):")
                appendLine(incompleteTailText)
            } else if (fallbackTailLines.isNotBlank()) {
                appendLine("Trailing Lines from Previous Page (use ONLY if the first lines below continue an unextracted split row; do NOT re-emit complete rows):")
                appendLine(fallbackTailLines)
            }
            if (recentBoundaryTransactions.isNotEmpty()) {
                appendLine("Already Extracted Boundary Rows from Previous Batch (DO NOT duplicate these):")
                recentBoundaryTransactions.forEach { summary ->
                    appendLine("- $summary")
                }
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
                val boundaryArray = obj.optJSONArray("recentBoundaryTransactions")
                val boundaryRows = buildList {
                    if (boundaryArray != null) {
                        for (i in 0 until boundaryArray.length()) {
                            val row = boundaryArray.optString(i, "").trim()
                            if (row.isNotEmpty()) add(row)
                        }
                    }
                }
                AiCarryOverContext(
                    activeAmc = obj.optString("activeAmc", "").trim(),
                    activeScheme = obj.optString("activeScheme", "").trim(),
                    activeIsin = obj.optString("activeIsin", "").trim(),
                    activeFolio = obj.optString("activeFolio", "").trim(),
                    activeBroker = obj.optString("activeBroker", "").trim(),
                    activeAssetClass = obj.optString("activeAssetClass", "").trim(),
                    activeAccountId = obj.optString("activeAccountId", "").trim(),
                    activeColumnHeaders = headers,
                    incompleteTailText = obj.optString("incompleteTailText", "").trim(),
                    fallbackTailLines = obj.optString("fallbackTailLines", "").trim(),
                    recentBoundaryTransactions = boundaryRows,
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
