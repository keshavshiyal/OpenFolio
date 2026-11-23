package com.openfolio.core.utils

import com.openfolio.core.data.local.entity.DraftTransactionEntity
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Deterministic offline parser for CAMS, KFintech, MFCentral Consolidated Account Statements (CAS),
 * and individual AMC Mutual Fund Statements of Account (SOA).
 * Operates on layout-sorted text extracted via PdfBox.
 * Extracts AMC, Folio, Scheme names, ISINs, and transaction rows (Buy, Sell, SIP, Dividend/IDCW)
 * directly into [DraftTransactionEntity] records with zero Double arithmetic for monetary values.
 */
@Singleton
class CasStatementParser @Inject constructor() {

    private val txnLineRegex = Regex(
        """^\s*(\d{1,2}[-/](?:[A-Za-z]{3}|\d{1,2})[-/]\d{2,4}|\d{4}-\d{2}-\d{2})\s+(.+?)\s+(-?\(?[\d,]+(?:\.\d+)?\)?)\s+(-?\(?[\d,]+(?:\.\d+)?\)?)\s+([\d,]+(?:\.\d+)?)(?:\s+([\d,]+(?:\.\d+)?))?\s*$""",
        RegexOption.MULTILINE,
    )

    private val amcRegex = Regex(
        """^([A-Za-z0-9\s&.,'-]+(?:Mutual Fund|Fund House|Asset Management|Investments|MF))$""",
        RegexOption.IGNORE_CASE,
    )

    private val folioRegex = Regex("""Folio\s*(?:No|Number)?\s*[:\-]?\s*([A-Za-z0-9/\-]+)""", RegexOption.IGNORE_CASE)

    private val datePrefixRegex = Regex(
        """^\s*(\d{1,2}[-/](?:[A-Za-z]{3}|\d{1,2})[-/]\d{2,4}|\d{4}-\d{2}-\d{2})\b"""
    )

    fun isCasStatement(rawText: String): Boolean {
        if (rawText.isBlank()) return false
        val sample = rawText.take(15000)
        val hasMfSignal = sample.contains("Consolidated Account Statement", ignoreCase = true) ||
            sample.contains("CAMS", ignoreCase = true) ||
            sample.contains("KFintech", ignoreCase = true) ||
            sample.contains("Computer Age Management Services", ignoreCase = true) ||
            sample.contains("Karvy", ignoreCase = true) ||
            sample.contains("MFCentral", ignoreCase = true) ||
            sample.contains("MF Central", ignoreCase = true) ||
            sample.contains("Mutual Fund", ignoreCase = true) ||
            sample.contains("Statement of Account", ignoreCase = true) ||
            sample.contains("Folio No", ignoreCase = true) ||
            sample.contains("Folio Number", ignoreCase = true) ||
            sample.contains("Unit Balance", ignoreCase = true) ||
            sample.contains("ISIN: INF", ignoreCase = true) ||
            sample.contains("ISIN:INF", ignoreCase = true) ||
            sample.contains("ISIN : INF", ignoreCase = true)

        if (!hasMfSignal) return false

        // Fast probe check using parse
        return parse(rawText, "probe").isNotEmpty()
    }

    fun parse(rawText: String, documentId: String): List<DraftTransactionEntity> {
        val drafts = mutableListOf<DraftTransactionEntity>()
        var currentAmc = ""
        var currentScheme = ""
        var currentIsin: String? = null

        val lines = rawText.lines().map { it.trim() }.filter { it.isNotBlank() }

        var i = 0
        while (i < lines.size) {
            val line = lines[i]

            // 1. Skip noise / header / footer rows
            if (isNoiseRow(line)) {
                i++
                continue
            }

            // 2. Detect AMC header (e.g. "SBI Mutual Fund", "Zerodha Fund House")
            if (amcRegex.matches(line) && line.length < 90 && !line.contains("Growth", ignoreCase = true) && !line.contains("Direct", ignoreCase = true)) {
                currentAmc = line
                currentScheme = ""
                currentIsin = null
                i++
                continue
            }

            // 3. Detect Scheme + ISIN line (in digital CAS PDFs, scheme lines contain ISIN: INF...)
            val foundIsin = AssetNameNormalizer.extractIsin(line)
            if (foundIsin != null && !datePrefixRegex.containsMatchIn(line)) {
                currentIsin = foundIsin
                val cleanScheme = extractCleanScheme(line)
                if (cleanScheme.isNotBlank() && cleanScheme.length > 4 && !cleanScheme.startsWith("(")) {
                    currentScheme = cleanScheme
                }
                i++
                continue
            }

            // 4. Check for standalone Scheme name line without ISIN on the same line
            if (isLikelySchemeLine(line)) {
                currentScheme = extractCleanScheme(line)
                currentIsin = null
                i++
                continue
            }

            // 5. Match Transaction Row
            val parsedTxn = tryParseTransactionRow(lines, i, documentId, currentScheme, currentAmc, currentIsin)
            if (parsedTxn != null) {
                drafts.add(parsedTxn.draft)
                i += parsedTxn.linesConsumed
                continue
            }

            i++
        }

        return drafts
    }

    private data class ParsedTransactionResult(
        val draft: DraftTransactionEntity,
        val linesConsumed: Int,
    )

    private fun tryParseTransactionRow(
        lines: List<String>,
        startIndex: Int,
        documentId: String,
        currentScheme: String,
        currentAmc: String,
        currentIsin: String?,
    ): ParsedTransactionResult? {
        val line = lines[startIndex]
        val dateMatch = datePrefixRegex.find(line) ?: return null
        val dateStr = dateMatch.value
        val tradeDateMillis = DateParser.parseToEpochMilli(dateStr) ?: return null

        val remainderOnLine = line.substring(dateMatch.range.last + 1).trim()
        val numbersOnLine = extractNumberTokens(remainderOnLine)

        // Case A: Row is complete on a single line
        if (numbersOnLine.size >= 2 || (numbersOnLine.size == 1 && isDividendLine(remainderOnLine))) {
            val draft = buildDraft(
                documentId = documentId,
                dateMillis = tradeDateMillis,
                textBeforeNumbers = extractDescription(remainderOnLine, numbersOnLine),
                numbers = numbersOnLine,
                currentScheme = currentScheme,
                currentAmc = currentAmc,
                currentIsin = currentIsin,
            )
            if (draft != null) {
                return ParsedTransactionResult(draft, linesConsumed = 1)
            }
        }

        // Case B: Multi-line wrapped row (description on lines 1..k, numbers on next line)
        for (offset in 1..4) {
            val nextIdx = startIndex + offset
            if (nextIdx >= lines.size) break
            val nextLine = lines[nextIdx]
            if (datePrefixRegex.containsMatchIn(nextLine)) break
            if (isNoiseRow(nextLine) || isLikelySchemeLine(nextLine) || amcRegex.matches(nextLine)) break

            val numbersOnNext = extractNumberTokens(nextLine)
            if (numbersOnNext.size >= 2 || (numbersOnNext.size == 1 && isDividendLine(remainderOnLine))) {
                val combinedDesc = StringBuilder(remainderOnLine)
                for (d in (startIndex + 1) until nextIdx) {
                    combinedDesc.append(" ").append(lines[d])
                }
                combinedDesc.append(" ").append(extractNonNumberText(nextLine, numbersOnNext))
                val draft = buildDraft(
                    documentId = documentId,
                    dateMillis = tradeDateMillis,
                    textBeforeNumbers = combinedDesc.toString().trim(),
                    numbers = numbersOnNext,
                    currentScheme = currentScheme,
                    currentAmc = currentAmc,
                    currentIsin = currentIsin,
                )
                if (draft != null) {
                    return ParsedTransactionResult(draft, linesConsumed = offset + 1)
                }
            }
        }

        return null
    }

    private fun buildDraft(
        documentId: String,
        dateMillis: Long,
        textBeforeNumbers: String,
        numbers: List<BigDecimal>,
        currentScheme: String,
        currentAmc: String,
        currentIsin: String?,
    ): DraftTransactionEntity? {
        if (numbers.isEmpty()) return null

        val (amount, units, nav) = when (numbers.size) {
            1 -> {
                // Dividend payout
                val amt = numbers[0]
                Triple(amt, BigDecimal.ZERO, BigDecimal.ZERO)
            }
            2 -> {
                // Amount and Units
                val amt = numbers[0]
                val u = numbers[1]
                val n = if (u.compareTo(BigDecimal.ZERO) != 0) {
                    amt.divide(u, 4, RoundingMode.HALF_UP).abs()
                } else BigDecimal.ZERO
                Triple(amt, u, n)
            }
            3 -> {
                // Amount, Units, NAV
                Triple(numbers[0], numbers[1], numbers[2])
            }
            else -> {
                // 4 or more numbers: Amount, Units, NAV, Balance
                Triple(numbers[0], numbers[1], numbers[2])
            }
        }

        val txnType = resolveTransactionType(textBeforeNumbers, amount, units)
        val assetName = currentScheme.ifBlank { currentAmc.ifBlank { "Mutual Fund" } }

        return DraftTransactionEntity(
            id = UUID.randomUUID().toString(),
            documentId = documentId,
            assetName = assetName,
            transactionType = txnType,
            quantity = units.abs().toPlainString(),
            price = nav.abs().toPlainString(),
            netAmount = amount.abs().toPlainString(),
            confidence = 98,
            isAccepted = true,
            isManual = false,
            tradeDate = dateMillis,
            assetClass = "MUTUAL_FUND",
            broker = "CAMS/KFintech",
            isin = currentIsin,
            ticker = null,
        )
    }

    private fun extractNumberTokens(text: String): List<BigDecimal> {
        val matches = Regex("""-?\(?₹?\s*[\d,]+(?:\.\d+)?\)?|\b\d+\b""").findAll(text)
        val list = mutableListOf<BigDecimal>()
        for (m in matches) {
            val bd = parseSignedBigDecimal(m.value)
            if (bd != null) {
                list.add(bd)
            }
        }
        return list
    }

    private fun extractDescription(fullText: String, numbers: List<BigDecimal>): String {
        var desc = fullText
        val numberRegex = Regex("""-?\(?₹?\s*[\d,]+(?:\.\d+)?\)?|\b\d+\b""")
        val lastMatches = numberRegex.findAll(fullText).toList().takeLast(numbers.size)
        if (lastMatches.isNotEmpty()) {
            val firstNumIndex = lastMatches.first().range.first
            desc = fullText.substring(0, firstNumIndex).trim()
        }
        return desc.ifBlank { fullText }
    }

    private fun extractNonNumberText(line: String, numbers: List<BigDecimal>): String {
        var text = line
        val numberRegex = Regex("""-?\(?₹?\s*[\d,]+(?:\.\d+)?\)?|\b\d+\b""")
        val matches = numberRegex.findAll(line).toList()
        for (m in matches) {
            text = text.replace(m.value, " ")
        }
        return text.trim()
    }

    private fun isDividendLine(text: String): Boolean {
        val lower = text.lowercase()
        return lower.contains("dividend") || lower.contains("idcw")
    }

    private fun isNoiseRow(line: String): Boolean {
        if (line.startsWith("***") ||
            line.contains("Stamp Duty", ignoreCase = true) ||
            line.contains("STT Paid", ignoreCase = true) ||
            line.contains("Securities Transaction Tax", ignoreCase = true) ||
            line.contains("Address Updated", ignoreCase = true) ||
            line.startsWith("Opening Unit Balance", ignoreCase = true) ||
            line.startsWith("Closing Unit Balance", ignoreCase = true) ||
            line.startsWith("Page ", ignoreCase = true) ||
            line.contains("Total Cost Value", ignoreCase = true) ||
            line.contains("Total Market Value", ignoreCase = true) ||
            line.contains("Investor Charter", ignoreCase = true) ||
            line.contains("SCORES portal", ignoreCase = true) ||
            line.contains("Standard Terms and Conditions", ignoreCase = true) ||
            line.contains("Glossary of Terms", ignoreCase = true) ||
            line.contains("Guidelines for Investors", ignoreCase = true)
        ) {
            return true
        }
        return false
    }

    private fun isLikelySchemeLine(line: String): Boolean {
        if (line.length < 5 || line.length > 130) return false
        if (datePrefixRegex.containsMatchIn(line) || amcRegex.matches(line)) return false
        if (folioRegex.containsMatchIn(line)) return false
        val lower = line.lowercase()
        if (lower.contains("systematic") || lower.contains("sip") || lower.contains("purchase") ||
            lower.contains("redemption") || lower.contains("switch") || lower.contains("dividend") ||
            lower.contains("idcw") || lower.contains("payout") || lower.contains("reinvestment")
        ) {
            return false
        }
        val hasSchemeWord = lower.contains("fund") || lower.contains("growth") || lower.contains("plan") ||
            lower.contains("idcw") || lower.contains("option") || lower.contains("index") ||
            lower.contains("etf") || lower.contains("direct") || lower.contains("regular") ||
            lower.contains("bluechip") || lower.contains("cap") || lower.contains("opportunities")

        val hasDisallowedWord = lower.contains("folio") || lower.contains("registrar") ||
            lower.contains("total") || lower.contains("balance") || lower.contains("disclaimer") ||
            lower.contains("closing") || lower.contains("opening")

        return hasSchemeWord && !hasDisallowedWord
    }

    private fun extractCleanScheme(line: String): String {
        return line
            .split(Regex("""(?i)\bISIN\b|(?i)\(Advisor"""))
            .firstOrNull()
            .orEmpty()
            .replace(Regex("""^[A-Z0-9]+\s*-\s*"""), "")
            .replace(Regex("""\(.*?\)"""), "")
            .trim()
            .replace(Regex("""[\s(\[-]+$"""), "")
    }

    private fun parseSignedBigDecimal(raw: String): BigDecimal? {
        val clean = raw.replace(",", "").replace("₹", "").trim()
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

    private fun resolveTransactionType(description: String, amount: BigDecimal, units: BigDecimal): String {
        val lower = description.lowercase()
        return when {
            lower.contains("redemption") || lower.contains("switch out") || lower.contains("switch-out") ||
                lower.contains("repurchase") || lower.contains("swp") ||
                amount < BigDecimal.ZERO || units < BigDecimal.ZERO -> "SELL"
            lower.contains("dividend") || lower.contains("idcw") -> "DIVIDEND"
            lower.contains("purchase") || lower.contains("systematic") || lower.contains("switch in") ||
                lower.contains("switch-in") || lower.contains("sip") || lower.contains("subscription") ||
                lower.contains("allotment") || lower.contains("additional") -> "BUY"
            else -> "BUY"
        }
    }
}
