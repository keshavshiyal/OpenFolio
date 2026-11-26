package com.openfolio.core.utils

import com.openfolio.core.data.local.entity.DraftTransactionEntity
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

data class CasParseState(
    var currentAmc: String = "",
    var currentScheme: String = "",
    var currentIsin: String? = null,
    var currentFolio: String = "",
    var hasSeenCasSignal: Boolean = false,
)

/**
 * Deterministic offline parser for CAMS, KFintech, MFCentral Consolidated Account Statements (CAS),
 * and individual AMC Mutual Fund Statements of Account (SOA).
 * Operates on layout-sorted text extracted via PdfBox, streaming page-by-page with preserved
 * AMC/Scheme/ISIN state across page breaks and zero Double arithmetic for monetary values.
 */
@Singleton
class CasStatementParser @Inject constructor() {

    private val amcRegex = Regex(
        """^([A-Za-z0-9\s&.,'-]+(?:Mutual Fund|Fund House|Asset Management|Investments|MF))$""",
        RegexOption.IGNORE_CASE,
    )

    private val folioRegex = Regex("""Folio\s*(?:No|Number)?\s*[:\-]?\s*([A-Za-z0-9/\-]+)""", RegexOption.IGNORE_CASE)

    private val datePrefixRegex = Regex(
        """^\s*(\d{1,2}[-/](?:[A-Za-z]{3}|\d{1,2})[-/]\d{2,4}|\d{4}-\d{2}-\d{2})\b""",
    )

    private val numberRegex = Regex("""-?\(?₹?\s*[\d,]+(?:\.\d+)?\)?|\b\d+\b""")

    fun hasCasSignal(sampleText: String): Boolean {
        if (sampleText.isBlank()) return false
        return sampleText.contains("Consolidated Account Statement", ignoreCase = true) ||
            sampleText.contains("CAMS", ignoreCase = true) ||
            sampleText.contains("KFintech", ignoreCase = true) ||
            sampleText.contains("Computer Age Management Services", ignoreCase = true) ||
            sampleText.contains("Karvy", ignoreCase = true) ||
            sampleText.contains("MFCentral", ignoreCase = true) ||
            sampleText.contains("MF Central", ignoreCase = true) ||
            sampleText.contains("Mutual Fund", ignoreCase = true) ||
            sampleText.contains("Statement of Account", ignoreCase = true) ||
            sampleText.contains("Folio No", ignoreCase = true) ||
            sampleText.contains("Folio Number", ignoreCase = true) ||
            sampleText.contains("Unit Balance", ignoreCase = true) ||
            sampleText.contains("ISIN: INF", ignoreCase = true) ||
            sampleText.contains("ISIN:INF", ignoreCase = true) ||
            sampleText.contains("ISIN : INF", ignoreCase = true)
    }

    fun isCasStatement(rawText: String): Boolean {
        if (rawText.isBlank()) return false
        val sample = rawText.take(15_000)
        if (!hasCasSignal(sample)) return false

        val state = CasParseState()
        var foundFirst = false
        streamLines(rawText.lineSequence(), "probe", state, sourcePage = null) {
            foundFirst = true
            false // stop immediately on first valid transaction
        }
        return foundFirst
    }

    fun parsePage(
        pageText: String,
        pageNumber: Int,
        documentId: String,
        state: CasParseState,
    ): List<DraftTransactionEntity> {
        if (pageText.isBlank()) return emptyList()
        if (!state.hasSeenCasSignal && hasCasSignal(pageText)) {
            state.hasSeenCasSignal = true
        }
        val drafts = mutableListOf<DraftTransactionEntity>()
        streamLines(pageText.lineSequence(), documentId, state, sourcePage = pageNumber) { draft ->
            drafts.add(draft)
            true
        }
        return drafts
    }

    fun parse(rawText: String, documentId: String): List<DraftTransactionEntity> {
        if (rawText.isBlank()) return emptyList()
        val drafts = mutableListOf<DraftTransactionEntity>()
        val state = CasParseState()
        streamLines(rawText.lineSequence(), documentId, state, sourcePage = null) { draft ->
            drafts.add(draft)
            true
        }
        return drafts
    }

    /**
     * Streams non-blank trimmed lines through a bounded 5-line lookahead window without
     * allocating full-document line lists. [onDraft] returns true to continue or false to stop early.
     */
    private inline fun streamLines(
        lines: Sequence<String>,
        documentId: String,
        state: CasParseState,
        sourcePage: Int?,
        onDraft: (DraftTransactionEntity) -> Boolean,
    ) {
        val iterator = lines
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .iterator()
        val window = ArrayDeque<String>(LOOKAHEAD_WINDOW)

        while (true) {
            fillLookaheadWindow(window, iterator)
            if (window.isEmpty()) break
            val line = window.first()

            if (!state.hasSeenCasSignal && hasCasSignal(line)) {
                state.hasSeenCasSignal = true
            }

            // 1. Skip noise / header / footer rows
            if (isNoiseRow(line)) {
                window.removeFirst()
                continue
            }

            // 2. Detect AMC header (e.g. "SBI Mutual Fund", "Zerodha Fund House")
            if (amcRegex.matches(line) &&
                line.length < 90 &&
                !line.contains("Growth", ignoreCase = true) &&
                !line.contains("Direct", ignoreCase = true)
            ) {
                state.currentAmc = line
                state.currentScheme = ""
                state.currentIsin = null
                window.removeFirst()
                continue
            }

            // 3. Track Folio Number without emitting a draft
            val folioMatch = folioRegex.find(line)
            if (folioMatch != null && !datePrefixRegex.containsMatchIn(line)) {
                state.currentFolio = folioMatch.groupValues.getOrElse(1) { "" }.trim()
                state.hasSeenCasSignal = true
            }

            // 4. Detect Scheme + ISIN line (in digital CAS PDFs, scheme lines contain ISIN: INF...)
            val foundIsin = AssetNameNormalizer.extractIsin(line)
            if (foundIsin != null && !datePrefixRegex.containsMatchIn(line)) {
                state.currentIsin = foundIsin
                val cleanScheme = extractCleanScheme(line)
                if (cleanScheme.isNotBlank() && cleanScheme.length > 4 && !cleanScheme.startsWith("(")) {
                    state.currentScheme = cleanScheme
                }
                window.removeFirst()
                continue
            }

            // 4. Check for standalone Scheme name line without ISIN on the same line
            if (isLikelySchemeLine(line)) {
                state.currentScheme = extractCleanScheme(line)
                state.currentIsin = null
                window.removeFirst()
                continue
            }

            // 5. Match Transaction Row using bounded lookahead window
            val parsedTxn = tryParseTransactionRow(
                window = window,
                documentId = documentId,
                currentScheme = state.currentScheme,
                currentAmc = state.currentAmc,
                currentIsin = state.currentIsin,
                sourcePage = sourcePage,
            )
            if (parsedTxn != null) {
                repeat(parsedTxn.linesConsumed.coerceAtMost(window.size)) {
                    window.removeFirst()
                }
                if (!onDraft(parsedTxn.draft)) {
                    return
                }
                continue
            }

            window.removeFirst()
        }
    }

    @PublishedApi
    internal fun fillLookaheadWindow(window: ArrayDeque<String>, iterator: Iterator<String>) {
        while (window.size < LOOKAHEAD_WINDOW && iterator.hasNext()) {
            window.addLast(iterator.next())
        }
    }

    private data class ParsedTransactionResult(
        val draft: DraftTransactionEntity,
        val linesConsumed: Int,
    )

    private fun tryParseTransactionRow(
        window: ArrayDeque<String>,
        documentId: String,
        currentScheme: String,
        currentAmc: String,
        currentIsin: String?,
        sourcePage: Int?,
    ): ParsedTransactionResult? {
        val line = window.firstOrNull() ?: return null
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
                sourcePage = sourcePage,
            )
            if (draft != null) {
                return ParsedTransactionResult(draft, linesConsumed = 1)
            }
        }

        // Case B: Multi-line wrapped row (description on lines 1..k, numbers on next line)
        for (offset in 1 until minOf(LOOKAHEAD_WINDOW, window.size)) {
            val nextLine = window[offset]
            if (datePrefixRegex.containsMatchIn(nextLine)) break
            if (isNoiseRow(nextLine) || isLikelySchemeLine(nextLine) || amcRegex.matches(nextLine)) break

            val numbersOnNext = extractNumberTokens(nextLine)
            if (numbersOnNext.size >= 2 || (numbersOnNext.size == 1 && isDividendLine(remainderOnLine))) {
                val combinedDesc = StringBuilder(remainderOnLine)
                for (d in 1 until offset) {
                    combinedDesc.append(' ').append(window[d])
                }
                combinedDesc.append(' ').append(extractNonNumberText(nextLine))
                val draft = buildDraft(
                    documentId = documentId,
                    dateMillis = tradeDateMillis,
                    textBeforeNumbers = combinedDesc.toString().trim(),
                    numbers = numbersOnNext,
                    currentScheme = currentScheme,
                    currentAmc = currentAmc,
                    currentIsin = currentIsin,
                    sourcePage = sourcePage,
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
        sourcePage: Int?,
    ): DraftTransactionEntity? {
        if (numbers.isEmpty()) return null

        val (amount, units, nav) = when (numbers.size) {
            1 -> Triple(numbers[0], BigDecimal.ZERO, BigDecimal.ZERO)
            2 -> {
                val amt = numbers[0]
                val u = numbers[1]
                val n = if (u.compareTo(BigDecimal.ZERO) != 0) {
                    amt.divide(u, 4, RoundingMode.HALF_UP).abs()
                } else BigDecimal.ZERO
                Triple(amt, u, n)
            }
            else -> Triple(numbers[0], numbers[1], numbers[2])
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
            sourcePage = sourcePage,
        )
    }

    private fun extractNumberTokens(text: String): List<BigDecimal> {
        val matches = numberRegex.findAll(text)
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
        val lastMatches = numberRegex.findAll(fullText).toList().takeLast(numbers.size)
        if (lastMatches.isNotEmpty()) {
            val firstNumIndex = lastMatches.first().range.first
            desc = fullText.substring(0, firstNumIndex).trim()
        }
        return desc.ifBlank { fullText }
    }

    private fun extractNonNumberText(line: String): String {
        var text = line
        for (m in numberRegex.findAll(line)) {
            text = text.replace(m.value, " ")
        }
        return text.trim()
    }

    private fun isDividendLine(text: String): Boolean {
        val lower = text.lowercase()
        return lower.contains("dividend") || lower.contains("idcw")
    }

    private fun isNoiseRow(line: String): Boolean {
        return line.startsWith("***") ||
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

    private companion object {
        const val LOOKAHEAD_WINDOW = 5
    }
}
