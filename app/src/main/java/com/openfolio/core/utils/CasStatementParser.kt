package com.openfolio.core.utils

import com.openfolio.core.data.local.entity.DraftTransactionEntity
import java.math.BigDecimal
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Deterministic offline parser for CAMS and KFintech Consolidated Account Statements (CAS).
 * Operates on layout-sorted text extracted via PdfBox with [sortByPosition = true].
 * Extracts AMC, Folio, Scheme names, ISINs, and transaction rows (Buy, Sell, SIP, Dividend/IDCW)
 * directly into [DraftTransactionEntity] records with zero Double arithmetic for monetary values.
 */
@Singleton
class CasStatementParser @Inject constructor() {

    private val txnLineRegex = Regex(
        """^(\d{1,2}-[A-Za-z]{3}-\d{4})\s+(.+?)\s+(-?\(?[\d,]+\.\d{2}\)?)\s+(-?\(?[\d,]+\.\d{2,4}\)?)\s+([\d,]+\.\d{2,4})\s+([\d,]+\.\d{2,4})\s*$""",
        RegexOption.MULTILINE,
    )

    private val amcRegex = Regex(
        """^([A-Za-z0-9\s&.-]+(?:Mutual Fund|Fund House|MF))$""",
        RegexOption.IGNORE_CASE,
    )

    private val folioRegex = Regex("""Folio\s*No\s*[:\-]?\s*([A-Za-z0-9/\-]+)""", RegexOption.IGNORE_CASE)

    fun isCasStatement(rawText: String): Boolean {
        if (rawText.isBlank()) return false
        val sample = rawText.take(5000)
        val hasCasSignal = sample.contains("Consolidated Account Statement", ignoreCase = true) ||
            sample.contains("CAMS", ignoreCase = true) ||
            sample.contains("KFintech", ignoreCase = true) ||
            sample.contains("Computer Age Management Services", ignoreCase = true) ||
            sample.contains("Karvy", ignoreCase = true)
        return hasCasSignal && txnLineRegex.containsMatchIn(rawText)
    }

    fun parse(rawText: String, documentId: String): List<DraftTransactionEntity> {
        val drafts = mutableListOf<DraftTransactionEntity>()
        var currentAmc = ""
        var currentScheme = ""
        var currentIsin: String? = null

        val lines = rawText.lines().map { it.trim() }.filter { it.isNotBlank() }

        for (line in lines) {
            // 1. Skip noise / header / footer rows
            if (line.startsWith("***") ||
                line.contains("Stamp Duty", ignoreCase = true) ||
                line.contains("STT Paid", ignoreCase = true) ||
                line.contains("Address Updated", ignoreCase = true) ||
                line.startsWith("Opening Unit Balance", ignoreCase = true) ||
                line.startsWith("Closing Unit Balance", ignoreCase = true) ||
                line.startsWith("Page ", ignoreCase = true) ||
                line.contains("Total Cost Value", ignoreCase = true) ||
                line.contains("Total Market Value", ignoreCase = true)
            ) {
                continue
            }

            // 2. Detect AMC header (e.g. "SBI Mutual Fund", "Zerodha Fund House")
            if (amcRegex.matches(line) && line.length < 80) {
                currentAmc = line
                currentScheme = ""
                currentIsin = null
                continue
            }

            // 3. Detect Scheme + ISIN line (in digital CAS PDFs, scheme lines contain ISIN: INF...)
            val foundIsin = AssetNameNormalizer.extractIsin(line)
            if (foundIsin != null && !txnLineRegex.matches(line)) {
                currentIsin = foundIsin
                // Clean scheme name by stripping ISIN, Advisor, Registrar metadata
                val cleanScheme = line
                    .split(Regex("""(?i)\bISIN\b|(?i)\(Advisor"""))
                    .firstOrNull()
                    .orEmpty()
                    .replace(Regex("""^[A-Z0-9]+\s*-\s*"""), "")
                    .replace(Regex("""\(.*?\)"""), "")
                    .trim()
                    .replace(Regex("""[\s(\[-]+$"""), "")
                if (cleanScheme.isNotBlank() && cleanScheme.length > 4 && !cleanScheme.startsWith("(")) {
                    currentScheme = cleanScheme
                }
                continue
            }

            // 4. Check for standalone Scheme name line without ISIN on the same line
            if (isLikelySchemeLine(line)) {
                currentScheme = line
                    .replace(Regex("""^[A-Z0-9]+\s*-\s*"""), "")
                    .trim()
                    .replace(Regex("""[\s(\[-]+$"""), "")
                currentIsin = null
                continue
            }

            // 5. Match Transaction Row
            val match = txnLineRegex.find(line) ?: continue
            val (dateStr, desc, rawAmt, rawUnits, rawNav, _) = match.destructured

            val amount = parseSignedBigDecimal(rawAmt) ?: continue
            val units = parseSignedBigDecimal(rawUnits) ?: continue
            val nav = parseSignedBigDecimal(rawNav) ?: BigDecimal.ZERO

            val txnType = resolveTransactionType(desc, amount)
            val tradeDateMillis = DateParser.parseToEpochMilli(dateStr)

            val assetName = currentScheme.ifBlank { currentAmc.ifBlank { "Mutual Fund" } }

            drafts.add(
                DraftTransactionEntity(
                    id = UUID.randomUUID().toString(),
                    documentId = documentId,
                    assetName = assetName,
                    transactionType = txnType,
                    quantity = units.abs().toPlainString(),
                    price = nav.abs().toPlainString(),
                    netAmount = amount.abs().toPlainString(),
                    confidence = 98, // Deterministic regex extraction
                    isAccepted = true,
                    isManual = false,
                    tradeDate = tradeDateMillis,
                    assetClass = "MUTUAL_FUND",
                    broker = "CAMS/KFintech",
                    isin = currentIsin,
                    ticker = null,
                )
            )
        }

        return drafts
    }

    private fun isLikelySchemeLine(line: String): Boolean {
        if (line.length < 5 || line.length > 120) return false
        if (txnLineRegex.matches(line) || amcRegex.matches(line)) return false
        if (folioRegex.containsMatchIn(line)) return false
        val lower = line.lowercase()
        return (lower.contains("fund") || lower.contains("growth") || lower.contains("plan") || lower.contains("idcw") || lower.contains("option")) &&
            !lower.contains("folio") && !lower.contains("registrar") && !lower.contains("total")
    }

    private fun parseSignedBigDecimal(raw: String): BigDecimal? {
        val clean = raw.replace(",", "").trim()
        val isParenNegative = clean.startsWith("(") && clean.endsWith(")")
        val isMinusNegative = clean.startsWith("-")
        val numeric = when {
            isParenNegative -> clean.substring(1, clean.length - 1).trim()
            isMinusNegative -> clean.substring(1).trim()
            else -> clean
        }
        return runCatching {
            val bd = BigDecimal(numeric)
            if (isParenNegative || isMinusNegative) bd.negate() else bd
        }.getOrNull()
    }

    private fun resolveTransactionType(description: String, amount: BigDecimal): String {
        val lower = description.lowercase()
        return when {
            lower.contains("redemption") || lower.contains("switch out") || lower.contains("switch-out") || amount < BigDecimal.ZERO -> "SELL"
            lower.contains("dividend") || lower.contains("idcw") -> "DIVIDEND"
            lower.contains("purchase") || lower.contains("systematic") || lower.contains("switch in") || lower.contains("switch-in") || lower.contains("sip") -> "BUY"
            else -> "BUY"
        }
    }
}
