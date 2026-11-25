package com.openfolio.core.utils

import com.openfolio.core.data.local.entity.AssetClass
import com.openfolio.core.data.local.entity.DraftTransactionEntity
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

data class BrokerParseState(
    var brokerName: String = "Broker",
    var hasDetectedBroker: Boolean = false,
    var hasBrokerSignal: Boolean = false,
) {
    internal var columnIndices: BrokerColumnPositions? = null
}

internal data class BrokerColumnPositions(
    val dateCol: Int,
    val assetCol: Int,
    val typeCol: Int,
    val qtyCol: Int,
    val priceCol: Int,
    val amountCol: Int,
    val isinCol: Int,
    val delimiter: String,
)

/**
 * Deterministic offline parser for broker tradebooks, contract notes, and transaction statements
 * (e.g., Zerodha, Groww, Angel One, Upstox, ICICI Direct, HDFC Securities, Kotak Securities, Dhan).
 * Operates on layout-sorted text extracted offline from PDFs without remote AI, streaming
 * page-by-page while preserving broker identity and column layouts across page breaks.
 */
@Singleton
class BrokerStatementParser @Inject constructor() {

    fun hasBrokerSignal(sampleText: String): Boolean {
        if (sampleText.isBlank()) return false
        return sampleText.contains("Zerodha", ignoreCase = true) ||
            sampleText.contains("Groww", ignoreCase = true) ||
            sampleText.contains("Angel One", ignoreCase = true) ||
            sampleText.contains("Angel Broking", ignoreCase = true) ||
            sampleText.contains("Upstox", ignoreCase = true) ||
            sampleText.contains("ICICI Direct", ignoreCase = true) ||
            sampleText.contains("ICICI Securities", ignoreCase = true) ||
            sampleText.contains("HDFC Securities", ignoreCase = true) ||
            sampleText.contains("HDFC Sky", ignoreCase = true) ||
            sampleText.contains("Kotak Securities", ignoreCase = true) ||
            sampleText.contains("Kotak Neo", ignoreCase = true) ||
            sampleText.contains("Dhan", ignoreCase = true) ||
            sampleText.contains("Tradebook", ignoreCase = true) ||
            sampleText.contains("Trade Book", ignoreCase = true) ||
            sampleText.contains("Contract Note", ignoreCase = true) ||
            sampleText.contains("Order History", ignoreCase = true) ||
            sampleText.contains("Trade Details", ignoreCase = true) ||
            sampleText.contains("Executed Orders", ignoreCase = true)
    }

    fun isBrokerStatement(rawText: String): Boolean {
        if (rawText.isBlank()) return false
        val sample = rawText.take(10_000)
        val brandOrTitleSignal = hasBrokerSignal(sample)
        val probeState = BrokerParseState()
        var foundHeader = false
        var foundDraft = false

        var scannedLines = 0
        for (rawLine in rawText.lineSequence()) {
            val line = rawLine.trim()
            if (line.isBlank()) continue
            scannedLines++
            if (scannedLines > 120 && !brandOrTitleSignal && !foundHeader) break

            if (!probeState.hasDetectedBroker) {
                val detected = detectBrokerNameOrNull(line)
                if (detected != null) {
                    probeState.brokerName = detected
                    probeState.hasDetectedBroker = true
                }
            }

            if (isNoiseOrFooterLine(line)) continue

            if (isHeaderLine(line)) {
                foundHeader = true
                if (brandOrTitleSignal) return true
                probeState.columnIndices = resolveColumnIndices(line) ?: probeState.columnIndices
                continue
            }

            if (foundHeader) {
                val draft = parseLine(line, "probe", probeState, AssetClass.STOCK, sourcePage = null)
                if (draft != null) {
                    foundDraft = true
                    break
                }
            }
        }

        return (brandOrTitleSignal && foundHeader) || (foundHeader && foundDraft)
    }

    fun parsePage(
        pageText: String,
        pageNumber: Int,
        documentId: String,
        state: BrokerParseState,
        defaultAssetClass: AssetClass = AssetClass.STOCK,
    ): List<DraftTransactionEntity> {
        if (pageText.isBlank()) return emptyList()
        val drafts = mutableListOf<DraftTransactionEntity>()
        streamLines(
            lines = pageText.lineSequence(),
            documentId = documentId,
            state = state,
            defaultAssetClass = defaultAssetClass,
            sourcePage = pageNumber,
        ) { draft ->
            drafts.add(draft)
        }
        return drafts
    }

    fun parse(
        rawText: String,
        documentId: String,
        defaultAssetClass: AssetClass = AssetClass.STOCK,
    ): List<DraftTransactionEntity> {
        if (rawText.isBlank()) return emptyList()
        val drafts = mutableListOf<DraftTransactionEntity>()
        val state = BrokerParseState()
        streamLines(
            lines = rawText.lineSequence(),
            documentId = documentId,
            state = state,
            defaultAssetClass = defaultAssetClass,
            sourcePage = null,
        ) { draft ->
            drafts.add(draft)
        }
        return drafts
    }

    private inline fun streamLines(
        lines: Sequence<String>,
        documentId: String,
        state: BrokerParseState,
        defaultAssetClass: AssetClass,
        sourcePage: Int?,
        onDraft: (DraftTransactionEntity) -> Unit,
    ) {
        for (rawLine in lines) {
            val line = rawLine.trim()
            if (line.isBlank()) continue

            if (!state.hasDetectedBroker) {
                val detected = detectBrokerNameOrNull(line)
                if (detected != null) {
                    state.brokerName = detected
                    state.hasDetectedBroker = true
                    state.hasBrokerSignal = true
                }
            }
            if (!state.hasBrokerSignal && hasBrokerSignal(line)) {
                state.hasBrokerSignal = true
            }

            if (isNoiseOrFooterLine(line)) continue

            if (isHeaderLine(line)) {
                state.hasBrokerSignal = true
                val resolved = resolveColumnIndices(line)
                if (resolved != null) {
                    state.columnIndices = resolved
                }
                continue
            }

            val draft = parseLine(
                line = line,
                documentId = documentId,
                state = state,
                defaultAssetClass = defaultAssetClass,
                sourcePage = sourcePage,
            )
            if (draft != null) {
                onDraft(draft)
            }
        }
    }

    private fun parseLine(
        line: String,
        documentId: String,
        state: BrokerParseState,
        defaultAssetClass: AssetClass,
        sourcePage: Int?,
    ): DraftTransactionEntity? {
        val columnIndices = state.columnIndices
        return if (columnIndices != null && columnIndices.delimiter != " ") {
            parseTradeRow(
                line = line,
                documentId = documentId,
                columns = columnIndices,
                brokerName = state.brokerName,
                defaultAssetClass = defaultAssetClass,
                sourcePage = sourcePage,
            ) ?: parseFromTokens(
                tokens = line.split(WHITESPACE_REGEX).filter { it.isNotBlank() },
                documentId = documentId,
                brokerName = state.brokerName,
                defaultAssetClass = defaultAssetClass,
                sourcePage = sourcePage,
            )
        } else {
            parseFromTokens(
                tokens = line.split(WHITESPACE_REGEX).filter { it.isNotBlank() },
                documentId = documentId,
                brokerName = state.brokerName,
                defaultAssetClass = defaultAssetClass,
                sourcePage = sourcePage,
            )
        }
    }

    private fun detectBrokerNameOrNull(text: String): String? {
        val sample = text.lowercase()
        return when {
            sample.contains("zerodha") -> "Zerodha"
            sample.contains("groww") -> "Groww"
            sample.contains("angel one") || sample.contains("angel broking") -> "Angel One"
            sample.contains("upstox") -> "Upstox"
            sample.contains("icici direct") || sample.contains("icici securities") -> "ICICI Direct"
            sample.contains("hdfc securities") || sample.contains("hdfc sky") -> "HDFC Securities"
            sample.contains("kotak securities") || sample.contains("kotak neo") -> "Kotak Securities"
            sample.contains("dhan") -> "Dhan"
            else -> null
        }
    }

    internal fun isHeaderLine(line: String): Boolean {
        val lower = line.lowercase()
        val hasDate = lower.contains("date") || lower.contains("trade date") || lower.contains("trd date") || lower.contains("order date")
        val hasAsset = lower.contains("symbol") || lower.contains("scrip") || lower.contains("stock") ||
            lower.contains("security") || lower.contains("instrument") || lower.contains("company")
        val hasType = lower.contains("type") || lower.contains("trade type") || lower.contains("buy/sell") ||
            lower.contains("b/s") || lower.contains("action")
        val hasFinancials = lower.contains("qty") || lower.contains("quantity") || lower.contains("shares") ||
            lower.contains("units") || lower.contains("price") || lower.contains("rate") ||
            lower.contains("amount") || lower.contains("value") || lower.contains("trade value")

        return (hasDate && hasAsset && (hasType || hasFinancials)) ||
            (hasAsset && hasType && hasFinancials)
    }

    private fun resolveColumnIndices(headerLine: String): BrokerColumnPositions? {
        val delimiter = when {
            headerLine.contains("|") -> "|"
            headerLine.contains("\t") -> "\t"
            headerLine.contains("  ") -> "  "
            headerLine.contains(",") -> ","
            else -> " "
        }

        val tokens = if (delimiter == "  ") {
            headerLine.split(MULTI_SPACE_REGEX).map { it.trim() }.filter { it.isNotBlank() }
        } else {
            headerLine.split(delimiter).map { it.trim() }.filter { it.isNotBlank() }
        }

        if (tokens.size < 3) return null

        var dateCol = -1
        var assetCol = -1
        var typeCol = -1
        var qtyCol = -1
        var priceCol = -1
        var amountCol = -1
        var isinCol = -1

        for (i in tokens.indices) {
            val t = tokens[i].lowercase()
            when {
                (t.contains("date") || t.contains("time")) && dateCol == -1 -> dateCol = i
                (t.contains("isin")) && isinCol == -1 -> isinCol = i
                (t.contains("symbol") || t.contains("scrip") || t.contains("stock") ||
                    t.contains("security") || t.contains("instrument") || t.contains("company")) && assetCol == -1 -> assetCol = i
                (t.contains("type") || t.contains("buy/sell") || t.equals("b/s") || t.contains("action")) && typeCol == -1 -> typeCol = i
                (t.contains("qty") || t.contains("quantity") || t.contains("shares") || t.contains("units")) && qtyCol == -1 -> qtyCol = i
                (t.contains("price") || t.contains("rate") || t.contains("nav")) && priceCol == -1 -> priceCol = i
                (t.contains("value") || t.contains("amount") || t.contains("total")) && amountCol == -1 -> amountCol = i
            }
        }

        if (assetCol == -1) {
            assetCol = if (dateCol == 0) 1 else 0
        }
        if (dateCol == -1) {
            dateCol = if (assetCol == 0) 1 else 0
        }

        return BrokerColumnPositions(
            dateCol = dateCol,
            assetCol = assetCol,
            typeCol = typeCol,
            qtyCol = qtyCol,
            priceCol = priceCol,
            amountCol = amountCol,
            isinCol = isinCol,
            delimiter = delimiter,
        )
    }

    private fun parseTradeRow(
        line: String,
        documentId: String,
        columns: BrokerColumnPositions,
        brokerName: String,
        defaultAssetClass: AssetClass,
        sourcePage: Int?,
    ): DraftTransactionEntity? {
        val tokens = if (columns.delimiter == "  ") {
            line.split(MULTI_SPACE_REGEX).map { it.trim() }.filter { it.isNotBlank() }
        } else {
            line.split(columns.delimiter).map { it.trim() }.filter { it.isNotBlank() }
        }

        if (tokens.size < 3) {
            val spaceTokens = line.split(WHITESPACE_REGEX).map { it.trim() }.filter { it.isNotBlank() }
            if (spaceTokens.size < 3) return null
            return parseFromTokens(spaceTokens, documentId, brokerName, defaultAssetClass, sourcePage)
        }

        val rawDate = tokens.getOrNull(columns.dateCol).orEmpty()
        val rawAsset = tokens.getOrNull(columns.assetCol).orEmpty()
        val rawType = if (columns.typeCol >= 0) tokens.getOrNull(columns.typeCol).orEmpty() else ""
        val rawQty = if (columns.qtyCol >= 0) tokens.getOrNull(columns.qtyCol).orEmpty() else ""
        val rawPrice = if (columns.priceCol >= 0) tokens.getOrNull(columns.priceCol).orEmpty() else ""
        val rawAmount = if (columns.amountCol >= 0) tokens.getOrNull(columns.amountCol).orEmpty() else ""
        val rawIsin = if (columns.isinCol >= 0) tokens.getOrNull(columns.isinCol) else null

        val tradeDateMillis = DateParser.parseToEpochMilli(rawDate)
            ?: extractDateFromLine(line)
            ?: return null

        val isin = rawIsin?.let(AssetNameNormalizer::extractIsin) ?: AssetNameNormalizer.extractIsin(line)
        val cleanAsset = cleanAssetName(rawAsset, isin)
        if (cleanAsset.isBlank() || isNoiseWord(cleanAsset)) return null

        val qty = parsePositiveBigDecimal(rawQty)
        val price = parsePositiveBigDecimal(rawPrice)
        val netAmt = parsePositiveBigDecimal(rawAmount)

        val (finalQty, finalPrice, finalAmount) = when {
            qty != null && price != null && netAmt != null -> Triple(qty, price, netAmt)
            qty != null && price != null -> Triple(qty, price, qty.multiply(price).setScale(2, RoundingMode.HALF_UP))
            qty != null && netAmt != null && qty > BigDecimal.ZERO -> Triple(qty, netAmt.divide(qty, 4, RoundingMode.HALF_UP), netAmt)
            price != null && netAmt != null && price > BigDecimal.ZERO -> Triple(netAmt.divide(price, 4, RoundingMode.HALF_UP), price, netAmt)
            qty != null -> Triple(qty, BigDecimal.ZERO, BigDecimal.ZERO)
            netAmt != null -> Triple(BigDecimal.ONE, netAmt, netAmt)
            else -> return null
        }

        val txnType = resolveType(rawType, line)

        return DraftTransactionEntity(
            id = UUID.randomUUID().toString(),
            documentId = documentId,
            assetName = cleanAsset,
            transactionType = txnType,
            quantity = finalQty.toPlainString(),
            price = finalPrice.toPlainString(),
            netAmount = finalAmount.toPlainString(),
            confidence = 96,
            isAccepted = true,
            isManual = false,
            tradeDate = tradeDateMillis,
            assetClass = defaultAssetClass.name,
            broker = brokerName,
            isin = isin,
            ticker = if (isin == null && cleanAsset.length in 2..15 && !cleanAsset.contains(" ")) cleanAsset else null,
            sourcePage = sourcePage,
        )
    }

    private fun parseFromTokens(
        tokens: List<String>,
        documentId: String,
        brokerName: String,
        defaultAssetClass: AssetClass,
        sourcePage: Int?,
    ): DraftTransactionEntity? {
        var foundDate: Long? = null
        var foundType: String? = null
        var foundAsset = ""
        val foundNumbers = mutableListOf<BigDecimal>()
        var foundIsin: String? = null

        for (token in tokens) {
            val isin = AssetNameNormalizer.extractIsin(token)
            if (isin != null) {
                foundIsin = isin
                continue
            }

            if (TIME_TOKEN_REGEX.matches(token)) {
                continue
            }

            if (isDateToken(token)) {
                if (foundDate == null) {
                    foundDate = DateParser.parseToEpochMilli(token)
                }
                continue
            }

            val lower = token.lowercase()
            if (foundType == null) {
                if (lower == "buy" || lower == "b" || lower == "bought") {
                    foundType = "BUY"
                    continue
                } else if (lower == "sell" || lower == "s" || lower == "sold") {
                    foundType = "SELL"
                    continue
                }
            }

            val num = parsePositiveBigDecimal(token)
            if (num != null) {
                foundNumbers.add(num)
                continue
            }

            if (!isNoiseWord(token) && foundAsset.length < 50) {
                foundAsset = if (foundAsset.isEmpty()) token else "$foundAsset $token"
            }
        }

        if (foundDate == null || foundAsset.isBlank() || foundNumbers.isEmpty()) return null

        val candidateNumbers = if (foundNumbers.size > 3) {
            foundNumbers.takeLast(3)
        } else {
            foundNumbers
        }

        val (qty, price, amount) = when (candidateNumbers.size) {
            1 -> Triple(BigDecimal.ONE, candidateNumbers[0], candidateNumbers[0])
            2 -> Triple(candidateNumbers[0], candidateNumbers[1], candidateNumbers[0].multiply(candidateNumbers[1]).setScale(2, RoundingMode.HALF_UP))
            else -> Triple(candidateNumbers[0], candidateNumbers[1], candidateNumbers[2])
        }

        return DraftTransactionEntity(
            id = UUID.randomUUID().toString(),
            documentId = documentId,
            assetName = foundAsset.trim(),
            transactionType = foundType ?: "BUY",
            quantity = qty.toPlainString(),
            price = price.toPlainString(),
            netAmount = amount.toPlainString(),
            confidence = 94,
            isAccepted = true,
            isManual = false,
            tradeDate = foundDate,
            assetClass = defaultAssetClass.name,
            broker = brokerName,
            isin = foundIsin,
            ticker = if (foundIsin == null && foundAsset.length in 2..15 && !foundAsset.contains(" ")) foundAsset else null,
            sourcePage = sourcePage,
        )
    }

    private fun isDateToken(token: String): Boolean {
        val hasSeparator = token.contains("-") || token.contains("/") || (token.contains(".") && token.count { it == '.' } == 2)
        val hasMonthWord = MONTH_WORD_REGEX.containsMatchIn(token)
        return (hasSeparator || hasMonthWord) && DateParser.parseToLocalDate(token) != null
    }

    private fun extractDateFromLine(line: String): Long? {
        val match = LINE_DATE_REGEX.find(line) ?: return null
        return DateParser.parseToEpochMilli(match.value)
    }

    private fun cleanAssetName(raw: String, isin: String?): String {
        var clean = raw.trim()
        if (isin != null) {
            clean = clean.replace(isin, "").trim()
        }
        return clean
            .replace(EXCHANGE_SUFFIX_REGEX, "")
            .replace(TRAILING_SEP_REGEX, "")
            .trim()
    }

    private fun resolveType(rawType: String, fullLine: String): String {
        val lower = "$rawType $fullLine".lowercase()
        return when {
            lower.contains("sell") || lower.contains(" sold") || lower.contains(" s ") || lower.endsWith(" s") -> "SELL"
            lower.contains("buy") || lower.contains(" bought") || lower.contains(" b ") || lower.endsWith(" b") -> "BUY"
            else -> "BUY"
        }
    }

    private fun parsePositiveBigDecimal(raw: String): BigDecimal? {
        val clean = raw.replace(",", "").replace("₹", "").trim()
        return runCatching {
            val bd = BigDecimal(clean)
            if (bd > BigDecimal.ZERO) bd else null
        }.getOrNull()
    }

    private fun isNoiseWord(token: String): Boolean {
        val lower = token.lowercase()
        return lower in NOISE_WORDS || lower.startsWith("total") || lower.startsWith("page")
    }

    private fun isNoiseOrFooterLine(line: String): Boolean {
        val lower = line.lowercase()
        return lower.startsWith("total") ||
            lower.startsWith("grand total") ||
            lower.startsWith("sub total") ||
            lower.startsWith("page ") ||
            lower.startsWith("***") ||
            lower.contains("disclaimer") ||
            lower.contains("sebi reg") ||
            lower.contains("terms and conditions") ||
            lower.contains("registered office")
    }

    private companion object {
        val WHITESPACE_REGEX = Regex("""\s+""")
        val MULTI_SPACE_REGEX = Regex("""\s{2,}""")
        val TIME_TOKEN_REGEX = Regex("""\d{1,2}:\d{2}(?::\d{2})?""")
        val MONTH_WORD_REGEX = Regex("""(?i)\b(jan|feb|mar|apr|may|jun|jul|aug|sep|oct|nov|dec)\b""")
        val LINE_DATE_REGEX = Regex("""\b(\d{1,2}[-/](?:[A-Za-z]{3}|\d{1,2})[-/]\d{2,4}|\d{4}-\d{2}-\d{2})\b""")
        val EXCHANGE_SUFFIX_REGEX = Regex("""(?i)\b(EQ|NSE|BSE|BE|SM)\b""")
        val TRAILING_SEP_REGEX = Regex("""[|\-:]+$""")
        val NOISE_WORDS = setOf(
            "nse", "bse", "eq", "be", "sm", "trade", "exchange", "segment", "series",
            "order", "no", "tradebook", "statement", "brokerage", "stt", "gst",
        )
    }
}
