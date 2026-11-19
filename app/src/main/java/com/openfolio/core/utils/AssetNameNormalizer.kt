package com.openfolio.core.utils

import com.openfolio.core.data.local.entity.AssetEntity
import kotlin.math.max

object AssetNameNormalizer {
    private val CORPORATE_SUFFIXES = setOf(
        "limited",
        "ltd",
        "corporation",
        "corp",
        "incorporated",
        "inc",
        "industries",
        "industry",
        "ind",
        "private",
        "pvt",
        "company",
        "co",
        "enterprises",
        "enterprise",
        "holdings",
        "holding",
        "group",
        "grp",
        "technologies",
        "technology",
        "tech",
        "services",
        "service",
        "solutions",
        "solution",
        "international",
        "intl",
        "llc",
        "llp",
        "plc",
        "sa",
        "ag",
        "nv",
    )

    private val NON_ALPHANUMERIC_REGEX = Regex("[^a-z0-9\\s]")
    private val WHITESPACE_REGEX = Regex("\\s+")
    private val ISIN_REGEX = Regex("\\b[A-Z]{2}[A-Z0-9]{9}[0-9]\\b")

    /**
     * Extracts an ISIN (12 alphanumeric characters starting with 2 letters and ending with a digit)
     * from a text string if present.
     */
    fun extractIsin(text: String): String? =
        ISIN_REGEX.find(text.uppercase())?.value

    /**
     * Normalizes a company or asset name by:
     * 1. Standardizing casing to lowercase.
     * 2. Removing punctuation and special characters (preserving '&' as 'and').
     * 3. Removing extra spaces.
     * 4. Stripping trailing corporate suffixes (e.g. Ltd, Limited, Industries, Corp).
     *
     * Returns a slug identifier (e.g. "reliance", "tata_motors", "hdfc_bank").
     */
    fun normalize(rawName: String): String {
        if (rawName.isBlank()) return ""

        val cleaned = rawName
            .lowercase()
            .replace("&", " and ")
            .replace(NON_ALPHANUMERIC_REGEX, " ")
            .trim()

        val tokens = cleaned
            .split(WHITESPACE_REGEX)
            .filter { it.isNotBlank() }
            .toMutableList()

        if (tokens.isEmpty()) return ""

        // Iteratively strip corporate suffixes from the end, ensuring at least one token remains
        while (tokens.size > 1 && tokens.last() in CORPORATE_SUFFIXES) {
            tokens.removeAt(tokens.lastIndex)
        }

        return tokens.joinToString("_")
    }

    /**
     * Attempts to find an existing AssetEntity in the database matching the given raw name
     * using exact identifier matching (ISIN, ticker), exact normalized matching, token containment,
     * and Levenshtein similarity.
     */
    fun findMatchingAsset(
        rawName: String,
        existingAssets: List<AssetEntity>,
        identifier: String? = null,
    ): AssetEntity? {
        if (existingAssets.isEmpty()) return null

        // 0. Primary check: ISIN match (100% precision)
        val queryIsin = identifier?.takeIf(String::isNotBlank)?.let(::extractIsin) ?: extractIsin(rawName)
        if (queryIsin != null) {
            val isinMatch = existingAssets.firstOrNull { asset ->
                asset.identifiersJson.contains(queryIsin, ignoreCase = true) ||
                    asset.id.equals(queryIsin, ignoreCase = true)
            }
            if (isinMatch != null) return isinMatch
        }

        // Ticker / symbol check
        val cleanIdentifier = identifier?.trim()?.uppercase()
        if (!cleanIdentifier.isNullOrBlank()) {
            val tickerMatch = existingAssets.firstOrNull { asset ->
                asset.identifiersJson.contains("\"ticker\":\"$cleanIdentifier\"", ignoreCase = true) ||
                    asset.id.equals(cleanIdentifier, ignoreCase = true)
            }
            if (tickerMatch != null) return tickerMatch
        }

        val normalized = normalize(rawName)
        if (normalized.isBlank()) return null

        // 1. Exact match on id or normalized displayName
        val exactMatch = existingAssets.firstOrNull { asset ->
            asset.id.equals(normalized, ignoreCase = true) ||
                normalize(asset.displayName).equals(normalized, ignoreCase = true) ||
                normalize(asset.id).equals(normalized, ignoreCase = true)
        }
        if (exactMatch != null) return exactMatch

        val inputTokens = normalized.split("_").filter { it.isNotBlank() }.toSet()

        // 2. Token overlap / Jaccard similarity (for cases with reordered or matching word sets)
        val tokenMatch = existingAssets.firstOrNull { asset ->
            val assetTokens = normalize(asset.displayName).split("_").filter { it.isNotBlank() }.toSet()
            if (assetTokens.isEmpty()) return@firstOrNull false
            if (assetTokens == inputTokens) return@firstOrNull true
            val union = assetTokens.union(inputTokens).size
            val intersection = assetTokens.intersect(inputTokens).size
            val jaccard = intersection.toDouble() / union
            jaccard >= 0.8
        }
        if (tokenMatch != null) return tokenMatch

        // 3. Levenshtein edit distance similarity for minor typo tolerance
        return existingAssets.firstOrNull { asset ->
            val assetNorm = normalize(asset.displayName)
            if (assetNorm.length >= 4 && normalized.length >= 4) {
                val dist = levenshteinDistance(normalized, assetNorm)
                val maxLen = max(normalized.length, assetNorm.length)
                val sim = 1.0 - (dist.toDouble() / maxLen)
                sim >= 0.8 || (dist <= 1 && maxLen >= 5)
            } else {
                false
            }
        }
    }

    internal fun levenshteinSimilarity(s1: String, s2: String): Double {
        if (s1 == s2) return 1.0
        if (s1.isEmpty() || s2.isEmpty()) return 0.0
        val maxLen = max(s1.length, s2.length)
        val distance = levenshteinDistance(s1, s2)
        return 1.0 - (distance.toDouble() / maxLen)
    }

    private fun levenshteinDistance(s1: String, s2: String): Int {
        val dp = Array(s1.length + 1) { IntArray(s2.length + 1) }
        for (i in 0..s1.length) dp[i][0] = i
        for (j in 0..s2.length) dp[0][j] = j

        for (i in 1..s1.length) {
            for (j in 1..s2.length) {
                val cost = if (s1[i - 1] == s2[j - 1]) 0 else 1
                dp[i][j] = minOf(
                    dp[i - 1][j] + 1, // deletion
                    dp[i][j - 1] + 1, // insertion
                    dp[i - 1][j - 1] + cost, // substitution
                )
            }
        }
        return dp[s1.length][s2.length]
    }
}
