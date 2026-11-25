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
    OTHER,
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
    AI_COMPLETE,
    COMPLETED,
    PENDING,
    PROCESSING,
    FAILED,
}
