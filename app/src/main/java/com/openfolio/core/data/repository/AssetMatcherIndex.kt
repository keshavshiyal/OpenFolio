package com.openfolio.core.data.repository

import com.openfolio.core.data.local.entity.AssetEntity
import com.openfolio.core.utils.AssetNameNormalizer
import org.json.JSONObject

/**
 * O(1) in-memory lookup index over existing [AssetEntity] rows, preserving the exact matching
 * priority and conflict guards of [AssetNameNormalizer.findMatchingAsset].
 */
internal class AssetMatcherIndex(
    private val existingAssets: MutableList<AssetEntity>,
) {
    private val byId = HashMap<String, AssetEntity>(existingAssets.size * 2 + 16)
    private val byIsin = HashMap<String, AssetEntity>(existingAssets.size * 2 + 16)
    private val byTicker = HashMap<String, AssetEntity>(existingAssets.size * 2 + 16)
    private val byNormalizedName = HashMap<String, MutableList<AssetEntity>>(existingAssets.size * 2 + 16)

    init {
        for (asset in existingAssets) {
            indexAsset(asset)
        }
    }

    fun getById(id: String): AssetEntity? = byId[id.lowercase()]

    fun register(asset: AssetEntity) {
        val existingIdx = existingAssets.indexOfFirst { it.id == asset.id }
        if (existingIdx >= 0) {
            existingAssets[existingIdx] = asset
        } else {
            existingAssets.add(asset)
        }
        indexAsset(asset)
    }

    fun findMatch(
        rawName: String,
        identifier: String? = null,
        isin: String? = null,
        ticker: String? = null,
    ): AssetEntity? {
        if (existingAssets.isEmpty()) return null

        val queryIsin = isin?.takeIf(String::isNotBlank)?.let(AssetNameNormalizer::extractIsin)
            ?: identifier?.takeIf(String::isNotBlank)?.let(AssetNameNormalizer::extractIsin)
            ?: AssetNameNormalizer.extractIsin(rawName)

        if (queryIsin != null) {
            byIsin[queryIsin.uppercase()]?.let { return it }
        }

        val cleanTicker = ticker?.trim()?.uppercase()?.takeIf(String::isNotBlank)
            ?: identifier?.takeIf { AssetNameNormalizer.extractIsin(it) == null }
                ?.trim()
                ?.uppercase()
                ?.takeIf(String::isNotBlank)

        if (cleanTicker != null) {
            byTicker[cleanTicker]?.let { return it }
        }

        val normalized = AssetNameNormalizer.normalize(rawName)
        if (normalized.isBlank()) return null

        val exactCandidates = buildList {
            byId[normalized.lowercase()]?.let(::add)
            byNormalizedName[normalized]?.let(::addAll)
        }
        val exactMatch = exactCandidates.firstOrNull { asset ->
            !hasConflictingIdentifiers(asset, queryIsin, cleanTicker)
        }
        if (exactMatch != null) return exactMatch

        // Never fuzzy-match across distinct explicit ISINs or tickers
        if (queryIsin != null || cleanTicker != null) {
            return null
        }

        return AssetNameNormalizer.findMatchingAsset(
            rawName = rawName,
            existingAssets = existingAssets,
            identifier = identifier,
            isin = isin,
            ticker = ticker,
        )
    }

    private fun hasConflictingIdentifiers(
        asset: AssetEntity,
        queryIsin: String?,
        cleanTicker: String?,
    ): Boolean {
        if (queryIsin != null) {
            val existingIsin = AssetNameNormalizer.extractIsin(asset.id)
                ?: AssetNameNormalizer.extractIsin(asset.displayName)
                ?: AssetNameNormalizer.extractIsin(asset.identifiersJson)
            if (existingIsin != null && !existingIsin.equals(queryIsin, ignoreCase = true)) {
                return true
            }
        }
        if (cleanTicker != null) {
            val existingTicker = runCatching {
                JSONObject(asset.identifiersJson).optString("ticker").takeIf(String::isNotBlank)
            }.getOrNull()
            if (existingTicker != null && !existingTicker.equals(cleanTicker, ignoreCase = true)) {
                return true
            }
        }
        return false
    }

    private fun indexAsset(asset: AssetEntity) {
        byId[asset.id.lowercase()] = asset

        AssetNameNormalizer.extractIsin(asset.id)?.uppercase()?.let { byIsin[it] = asset }
        AssetNameNormalizer.extractIsin(asset.displayName)?.uppercase()?.let { byIsin[it] = asset }
        AssetNameNormalizer.extractIsin(asset.identifiersJson)?.uppercase()?.let { byIsin[it] = asset }

        val idTrimmedUpper = asset.id.trim().uppercase()
        if (idTrimmedUpper.isNotBlank()) {
            byTicker.putIfAbsent(idTrimmedUpper, asset)
        }
        val displayTrimmedUpper = asset.displayName.trim().uppercase()
        if (displayTrimmedUpper.isNotBlank()) {
            byTicker.putIfAbsent(displayTrimmedUpper, asset)
        }
        runCatching {
            val obj = JSONObject(asset.identifiersJson)
            obj.optString("isin").trim().uppercase().takeIf(String::isNotBlank)?.let { byIsin[it] = asset }
            obj.optString("ticker").trim().uppercase().takeIf(String::isNotBlank)?.let { byTicker[it] = asset }
            obj.optString("tokenSymbol").trim().uppercase().takeIf(String::isNotBlank)?.let { byTicker[it] = asset }
        }

        val normDisplay = AssetNameNormalizer.normalize(asset.displayName)
        if (normDisplay.isNotBlank()) {
            val bucket = byNormalizedName.getOrPut(normDisplay) { mutableListOf() }
            bucket.removeAll { it.id == asset.id }
            bucket.add(asset)
        }
        val normId = AssetNameNormalizer.normalize(asset.id)
        if (normId.isNotBlank() && normId != normDisplay) {
            val bucket = byNormalizedName.getOrPut(normId) { mutableListOf() }
            bucket.removeAll { it.id == asset.id }
            bucket.add(asset)
        }
    }
}
