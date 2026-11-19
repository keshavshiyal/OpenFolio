package com.openfolio.core.data.local.export

import com.openfolio.core.data.local.OpenFolioDatabase
import com.openfolio.core.data.local.entity.AccountEntity
import com.openfolio.core.data.local.entity.AssetClass
import com.openfolio.core.data.local.entity.AssetEntity
import com.openfolio.core.data.local.entity.DocumentEntity
import com.openfolio.core.ui.components.formatIndianRupees
import com.openfolio.feature.assets.toDisplayTitle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.OutputStream
import java.math.BigDecimal
import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class LegalDossierGenerator @Inject constructor(
    private val database: OpenFolioDatabase,
) {
    private val dateFormatter = DateTimeFormatter.ofPattern("dd MMMM yyyy, HH:mm:ss z")
        .withZone(ZoneId.systemDefault())

    suspend fun generateDossierText(): String = withContext(Dispatchers.IO) {
        val assets = database.assetDao().getAllOnce()
        val accounts = database.accountDao().getAllOnce()
        val documents = database.documentDao().getAllDocumentsOnce()
        val timestamp = dateFormatter.format(Instant.now())

        buildString {
            appendLine("# OPENFOLIO ESTATE & ASSET MANIFEST")
            appendLine("### Confidential & Official Asset Record for Legal Succession & Heir Transmission")
            appendLine("**Generated on:** $timestamp")
            appendLine("**Purpose:** Primary source of truth for portfolio holdings, legal asset identifiers, account folios, and source document provenance.")
            appendLine()
            appendLine("---")
            appendLine()

            // 1. Executive Estate Summary
            val totalInvested = assets.fold(BigDecimal.ZERO) { acc, a -> acc.add(a.totalInvested) }
            val activeAssets = assets.filter { it.status.name == "ACTIVE" }
            appendLine("## 1. Executive Summary of Wealth")
            appendLine("- **Total Recorded Assets:** ${assets.size}")
            appendLine("- **Active Holdings:** ${activeAssets.size}")
            appendLine("- **Total Book Value (Invested Capital):** ${formatIndianRupees(totalInvested)}")
            appendLine()

            // 2. Accounts & Financial Institutions
            appendLine("## 2. Financial Accounts & Custodians")
            if (accounts.isEmpty()) {
                appendLine("_No explicit broker or bank accounts configured._")
            } else {
                appendLine("| Account / Custodian | Type | Institution | Account / Demat ID |")
                appendLine("| :--- | :--- | :--- | :--- |")
                accounts.forEach { acc ->
                    val accNumber = acc.accountNumberEncrypted?.takeIf(String::isNotBlank) ?: "On File"
                    appendLine("| ${acc.name} | ${acc.type.name} | ${acc.institution ?: "—"} | $accNumber |")
                }
            }
            appendLine()

            // 3. Asset Schedule with Legal Identifiers
            appendLine("## 3. Comprehensive Asset Schedule")
            val groupedAssets = assets.groupBy { it.assetClass }
            AssetClass.entries.forEach { assetClass ->
                val classAssets = groupedAssets[assetClass].orEmpty()
                if (classAssets.isNotEmpty()) {
                    appendLine("### ${assetClass.toDisplayTitle()} (${classAssets.size})")
                    appendLine("| Asset Name | Units / Quantity | Avg Cost | Total Invested | Identifiers & Folios | Status |")
                    appendLine("| :--- | :--- | :--- | :--- | :--- | :--- |")
                    classAssets.forEach { asset ->
                        val identifiers = formatIdentifiersSummary(asset)
                        val qty = asset.totalQuantity.toPlainString()
                        val avgCost = formatIndianRupees(asset.averageCost)
                        val invested = formatIndianRupees(asset.totalInvested)
                        appendLine("| ${asset.displayName} | $qty | $avgCost | $invested | $identifiers | ${asset.status.name} |")
                    }
                    appendLine()
                }
            }

            // 4. Source Document Provenance & Verification Hashes
            appendLine("## 4. Document Provenance & Source Audit Trail")
            appendLine("The following institutional statements were verified, parsed, and imported into this ledger:")
            if (documents.isEmpty()) {
                appendLine("_No external statements imported._")
            } else {
                appendLine("| Document Name | Category | Import Date | SHA-256 Digest (Proof of Record) |")
                appendLine("| :--- | :--- | :--- | :--- |")
                documents.forEach { doc ->
                    val importDateStr = runCatching {
                        dateFormatter.format(Instant.ofEpochMilli(doc.importDate))
                    }.getOrDefault("Unknown")
                    appendLine("| ${doc.fileName} | ${doc.assetClass} | $importDateStr | `${doc.fileHash}` |")
                }
            }
            appendLine()

            // 5. Legal Heir Claim Procedure
            appendLine("## 5. Transmission & Claim Guidelines for Nominees")
            appendLine("In the event of holder incapacitation or estate settlement, nominees and legal heirs should follow these procedures:")
            appendLine("1. **Mutual Funds:** Submit Form ISR-4 with the respective AMC or RTAs (CAMS / KFintech) along with death certificate and nominee KYC.")
            appendLine("2. **Demat Equities:** Contact the Depository Participant (Broker) citing the Demat BO ID / DP ID for transmission of securities.")
            appendLine("3. **Fixed Deposits & Banks:** Submit death claim notification with Form DA-2 to the home branch citing deposit certificate numbers.")
            appendLine("4. **Physical Gold / Property:** Refer to the survey numbers, deed numbers, and hallmarking IDs listed above.")
            appendLine()

            // 6. Cryptographic Seal
            appendLine("---")
            appendLine("### Cryptographic Verification Seal")
            val manifestContent = this.toString()
            val sha256Digest = calculateSha256(manifestContent)
            appendLine("**SHA-256 Manifest Fingerprint:** `$sha256Digest`")
            appendLine("_This document is generated by OpenFolio Private Ledger. Any alteration of data invalidates the cryptographic hash above._")
        }
    }

    suspend fun writeDossierToStream(outputStream: OutputStream) = withContext(Dispatchers.IO) {
        val content = generateDossierText()
        outputStream.write(content.toByteArray(Charsets.UTF_8))
        outputStream.flush()
    }

    private fun formatIdentifiersSummary(asset: AssetEntity): String {
        val parts = mutableListOf<String>()
        val idObj = runCatching { JSONObject(asset.identifiersJson) }.getOrNull()
        val detObj = runCatching { JSONObject(asset.detailsJson) }.getOrNull()

        idObj?.keys()?.forEach { key ->
            val value = idObj.optString(key)
            if (value.isNotBlank()) parts.add("$key: $value")
        }
        detObj?.keys()?.forEach { key ->
            val value = detObj.optString(key)
            if (value.isNotBlank()) parts.add("$key: $value")
        }

        return if (parts.isEmpty()) "—" else parts.joinToString("; ")
    }

    private fun calculateSha256(text: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val bytes = digest.digest(text.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }
}
