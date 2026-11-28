package com.openfolio.core.data.local.entity

enum class AccountType {
    BROKER,
    BANK,
    WALLET,
    MF_FOLIO,
    MANUAL,
}

enum class AssetClass {
    STOCK,
    MUTUAL_FUND,
    BOND,
    SGB,
    FD,
    HOUSE,
    LAND,
    VEHICLE,
    CRYPTO,
    GOLD,
    CASH,
    OTHER,
}

enum class AssetStatus {
    ACTIVE,
    SOLD,
    MATURED,
    CLOSED,
}

enum class TransactionType {
    BUY,
    SELL,
    DIVIDEND,
    INTEREST,
    FEE,
    TAX,
    CHARGE,
    TRANSFER_IN,
    TRANSFER_OUT,
    SPLIT,
    BONUS,
    MERGER,
    VALUATION,
    OTHER;

    companion object {
        private val SYNONYM_MAP: Map<String, TransactionType> = mapOf(
            "SIP" to BUY,
            "SIP_PURCHASE" to BUY,
            "SYSTEMATIC_INVESTMENT" to BUY,
            "STP_IN" to BUY,
            "PURCHASE" to BUY,
            "SUBSCRIPTION" to BUY,
            "ALLOTMENT" to BUY,
            "SWITCH_IN" to BUY,
            "NFO" to BUY,
            "REDEMPTION" to SELL,
            "REDEEM" to SELL,
            "SALE" to SELL,
            "WITHDRAWAL" to SELL,
            "SWITCH_OUT" to SELL,
            "STP_OUT" to SELL,
            "SWP" to SELL,
            "DIV" to DIVIDEND,
            "DIVIDEND_PAYOUT" to DIVIDEND,
            "IDCW" to DIVIDEND,
            "IDCW_PAYOUT" to DIVIDEND,
            "IDCW_REINVESTMENT" to DIVIDEND,
            "INT" to INTEREST,
            "STT" to TAX,
            "GST" to TAX,
            "STAMP_DUTY" to TAX,
            "BROKERAGE" to FEE,
            "EXIT_LOAD" to FEE,
            "CHARGES" to CHARGE,
            "BONUS_ISSUE" to BONUS,
            "STOCK_SPLIT" to SPLIT,
            "DEMERGER" to MERGER,
        )

        /**
         * Normalizes an external or AI-provided transaction type label into a safe [TransactionType].
         * Returns the normalized type and `true` if recognized as an exact enum or financial synonym,
         * or `(OTHER, false)` when unrecognized.
         */
        fun normalizeOrOther(raw: String?): Pair<TransactionType, Boolean> {
            val token = raw?.trim()
                ?.uppercase()
                ?.replace(Regex("[\\s\\-/]+"), "_")
                ?.takeIf(String::isNotBlank)
                ?: return OTHER to false
            entries.firstOrNull { it.name == token }?.let { return it to true }
            SYNONYM_MAP[token]?.let { return it to true }
            return OTHER to false
        }
    }
}

enum class TransactionStatus {
    DRAFT,
    REVIEWED,
    CONFIRMED,
}

enum class ExtractionStatus {
    PENDING_OCR,
    OCR_COMPLETE,
    OCR_FAILED,
    READY_FOR_MAPPING,
    AI_PAUSED,
    AI_COMPLETE,
    COMPLETED,
    PENDING,
    PROCESSING,
    FAILED,
}
