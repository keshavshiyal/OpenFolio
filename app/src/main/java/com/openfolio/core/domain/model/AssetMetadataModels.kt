package com.openfolio.core.domain.model

import org.json.JSONObject
import java.math.BigDecimal

data class StockMetadata(
    val isin: String? = null,
    val ticker: String? = null,
    val exchange: String? = null,
    val series: String? = null,
    val brokerName: String? = null,
    val dpId: String? = null,
    val dematAccountNumber: String? = null,
) {
    fun toIdentifiersJson(): String = JSONObject().apply {
        isin?.takeIf(String::isNotBlank)?.let { put("isin", it.trim().uppercase()) }
        ticker?.takeIf(String::isNotBlank)?.let { put("ticker", it.trim().uppercase()) }
        exchange?.takeIf(String::isNotBlank)?.let { put("exchange", it.trim().uppercase()) }
        series?.takeIf(String::isNotBlank)?.let { put("series", it.trim().uppercase()) }
    }.toString()

    fun toDetailsJson(): String = JSONObject().apply {
        brokerName?.takeIf(String::isNotBlank)?.let { put("brokerName", it.trim()) }
        dpId?.takeIf(String::isNotBlank)?.let { put("dpId", it.trim()) }
        dematAccountNumber?.takeIf(String::isNotBlank)?.let { put("dematAccountNumber", it.trim()) }
    }.toString()

    companion object {
        fun fromJson(identifiersJson: String?, detailsJson: String?): StockMetadata {
            val idObj = runCatching { JSONObject(identifiersJson ?: "{}") }.getOrDefault(JSONObject())
            val detObj = runCatching { JSONObject(detailsJson ?: "{}") }.getOrDefault(JSONObject())
            return StockMetadata(
                isin = idObj.optString("isin").takeIf(String::isNotBlank),
                ticker = idObj.optString("ticker").takeIf(String::isNotBlank),
                exchange = idObj.optString("exchange").takeIf(String::isNotBlank),
                series = idObj.optString("series").takeIf(String::isNotBlank),
                brokerName = detObj.optString("brokerName").takeIf(String::isNotBlank),
                dpId = detObj.optString("dpId").takeIf(String::isNotBlank),
                dematAccountNumber = detObj.optString("dematAccountNumber").takeIf(String::isNotBlank),
            )
        }
    }
}

data class MutualFundMetadata(
    val folioNumber: String? = null,
    val amfiCode: String? = null,
    val isin: String? = null,
    val amcName: String? = null,
    val planType: String? = null, // Direct, Regular
    val option: String? = null,   // Growth, IDCW
    val rta: String? = null,      // CAMS, KFintech
) {
    fun toIdentifiersJson(): String = JSONObject().apply {
        folioNumber?.takeIf(String::isNotBlank)?.let { put("folioNumber", it.trim()) }
        amfiCode?.takeIf(String::isNotBlank)?.let { put("amfiCode", it.trim()) }
        isin?.takeIf(String::isNotBlank)?.let { put("isin", it.trim().uppercase()) }
    }.toString()

    fun toDetailsJson(): String = JSONObject().apply {
        amcName?.takeIf(String::isNotBlank)?.let { put("amcName", it.trim()) }
        planType?.takeIf(String::isNotBlank)?.let { put("planType", it.trim()) }
        option?.takeIf(String::isNotBlank)?.let { put("option", it.trim()) }
        rta?.takeIf(String::isNotBlank)?.let { put("rta", it.trim()) }
    }.toString()

    companion object {
        fun fromJson(identifiersJson: String?, detailsJson: String?): MutualFundMetadata {
            val idObj = runCatching { JSONObject(identifiersJson ?: "{}") }.getOrDefault(JSONObject())
            val detObj = runCatching { JSONObject(detailsJson ?: "{}") }.getOrDefault(JSONObject())
            return MutualFundMetadata(
                folioNumber = idObj.optString("folioNumber").takeIf(String::isNotBlank),
                amfiCode = idObj.optString("amfiCode").takeIf(String::isNotBlank),
                isin = idObj.optString("isin").takeIf(String::isNotBlank),
                amcName = detObj.optString("amcName").takeIf(String::isNotBlank),
                planType = detObj.optString("planType").takeIf(String::isNotBlank),
                option = detObj.optString("option").takeIf(String::isNotBlank),
                rta = detObj.optString("rta").takeIf(String::isNotBlank),
            )
        }
    }
}

data class FixedDepositMetadata(
    val certificateNumber: String? = null,
    val accountNumber: String? = null,
    val bankName: String? = null,
    val branchIfsc: String? = null,
    val interestRate: BigDecimal? = null,
    val maturityDate: Long? = null,
    val maturityAmount: BigDecimal? = null,
    val tenureMonths: Int? = null,
) {
    fun toIdentifiersJson(): String = JSONObject().apply {
        certificateNumber?.takeIf(String::isNotBlank)?.let { put("certificateNumber", it.trim()) }
        accountNumber?.takeIf(String::isNotBlank)?.let { put("accountNumber", it.trim()) }
    }.toString()

    fun toDetailsJson(): String = JSONObject().apply {
        bankName?.takeIf(String::isNotBlank)?.let { put("bankName", it.trim()) }
        branchIfsc?.takeIf(String::isNotBlank)?.let { put("branchIfsc", it.trim()) }
        interestRate?.let { put("interestRate", it.toPlainString()) }
        maturityDate?.let { put("maturityDate", it) }
        maturityAmount?.let { put("maturityAmount", it.toPlainString()) }
        tenureMonths?.let { put("tenureMonths", it) }
    }.toString()

    companion object {
        fun fromJson(identifiersJson: String?, detailsJson: String?): FixedDepositMetadata {
            val idObj = runCatching { JSONObject(identifiersJson ?: "{}") }.getOrDefault(JSONObject())
            val detObj = runCatching { JSONObject(detailsJson ?: "{}") }.getOrDefault(JSONObject())
            return FixedDepositMetadata(
                certificateNumber = idObj.optString("certificateNumber").takeIf(String::isNotBlank),
                accountNumber = idObj.optString("accountNumber").takeIf(String::isNotBlank),
                bankName = detObj.optString("bankName").takeIf(String::isNotBlank),
                branchIfsc = detObj.optString("branchIfsc").takeIf(String::isNotBlank),
                interestRate = detObj.optString("interestRate").takeIf(String::isNotBlank)?.let { runCatching { BigDecimal(it) }.getOrNull() },
                maturityDate = if (detObj.has("maturityDate")) detObj.optLong("maturityDate") else null,
                maturityAmount = detObj.optString("maturityAmount").takeIf(String::isNotBlank)?.let { runCatching { BigDecimal(it) }.getOrNull() },
                tenureMonths = if (detObj.has("tenureMonths")) detObj.optInt("tenureMonths") else null,
            )
        }
    }
}

data class RealEstateMetadata(
    val surveyPlotNumber: String? = null,
    val deedNumber: String? = null,
    val propertyType: String? = null,
    val sroOffice: String? = null,
    val fullAddress: String? = null,
    val areaSqFt: Double? = null,
    val ownershipSharePercent: BigDecimal? = null,
) {
    fun toIdentifiersJson(): String = JSONObject().apply {
        surveyPlotNumber?.takeIf(String::isNotBlank)?.let { put("surveyPlotNumber", it.trim()) }
        deedNumber?.takeIf(String::isNotBlank)?.let { put("deedNumber", it.trim()) }
    }.toString()

    fun toDetailsJson(): String = JSONObject().apply {
        propertyType?.takeIf(String::isNotBlank)?.let { put("propertyType", it.trim()) }
        sroOffice?.takeIf(String::isNotBlank)?.let { put("sroOffice", it.trim()) }
        fullAddress?.takeIf(String::isNotBlank)?.let { put("fullAddress", it.trim()) }
        areaSqFt?.let { put("areaSqFt", it) }
        ownershipSharePercent?.let { put("ownershipSharePercent", it.toPlainString()) }
    }.toString()

    companion object {
        fun fromJson(identifiersJson: String?, detailsJson: String?): RealEstateMetadata {
            val idObj = runCatching { JSONObject(identifiersJson ?: "{}") }.getOrDefault(JSONObject())
            val detObj = runCatching { JSONObject(detailsJson ?: "{}") }.getOrDefault(JSONObject())
            return RealEstateMetadata(
                surveyPlotNumber = idObj.optString("surveyPlotNumber").takeIf(String::isNotBlank),
                deedNumber = idObj.optString("deedNumber").takeIf(String::isNotBlank),
                propertyType = detObj.optString("propertyType").takeIf(String::isNotBlank),
                sroOffice = detObj.optString("sroOffice").takeIf(String::isNotBlank),
                fullAddress = detObj.optString("fullAddress").takeIf(String::isNotBlank),
                areaSqFt = if (detObj.has("areaSqFt")) detObj.optDouble("areaSqFt") else null,
                ownershipSharePercent = detObj.optString("ownershipSharePercent").takeIf(String::isNotBlank)?.let { runCatching { BigDecimal(it) }.getOrNull() },
            )
        }
    }
}

data class BondSgbMetadata(
    val isin: String? = null,
    val trancheSeries: String? = null,
    val issuer: String? = null,
    val couponRate: BigDecimal? = null,
    val maturityDate: Long? = null,
    val faceValue: BigDecimal? = null,
) {
    fun toIdentifiersJson(): String = JSONObject().apply {
        isin?.takeIf(String::isNotBlank)?.let { put("isin", it.trim().uppercase()) }
        trancheSeries?.takeIf(String::isNotBlank)?.let { put("trancheSeries", it.trim()) }
    }.toString()

    fun toDetailsJson(): String = JSONObject().apply {
        issuer?.takeIf(String::isNotBlank)?.let { put("issuer", it.trim()) }
        couponRate?.let { put("couponRate", it.toPlainString()) }
        maturityDate?.let { put("maturityDate", it) }
        faceValue?.let { put("faceValue", it.toPlainString()) }
    }.toString()

    companion object {
        fun fromJson(identifiersJson: String?, detailsJson: String?): BondSgbMetadata {
            val idObj = runCatching { JSONObject(identifiersJson ?: "{}") }.getOrDefault(JSONObject())
            val detObj = runCatching { JSONObject(detailsJson ?: "{}") }.getOrDefault(JSONObject())
            return BondSgbMetadata(
                isin = idObj.optString("isin").takeIf(String::isNotBlank),
                trancheSeries = idObj.optString("trancheSeries").takeIf(String::isNotBlank),
                issuer = detObj.optString("issuer").takeIf(String::isNotBlank),
                couponRate = detObj.optString("couponRate").takeIf(String::isNotBlank)?.let { runCatching { BigDecimal(it) }.getOrNull() },
                maturityDate = if (detObj.has("maturityDate")) detObj.optLong("maturityDate") else null,
                faceValue = detObj.optString("faceValue").takeIf(String::isNotBlank)?.let { runCatching { BigDecimal(it) }.getOrNull() },
            )
        }
    }
}

data class CryptoMetadata(
    val tokenSymbol: String? = null,
    val contractAddress: String? = null,
    val blockchainNetwork: String? = null,
    val walletOrExchangeName: String? = null,
) {
    fun toIdentifiersJson(): String = JSONObject().apply {
        tokenSymbol?.takeIf(String::isNotBlank)?.let { put("tokenSymbol", it.trim().uppercase()) }
        contractAddress?.takeIf(String::isNotBlank)?.let { put("contractAddress", it.trim()) }
    }.toString()

    fun toDetailsJson(): String = JSONObject().apply {
        blockchainNetwork?.takeIf(String::isNotBlank)?.let { put("blockchainNetwork", it.trim()) }
        walletOrExchangeName?.takeIf(String::isNotBlank)?.let { put("walletOrExchangeName", it.trim()) }
    }.toString()

    companion object {
        fun fromJson(identifiersJson: String?, detailsJson: String?): CryptoMetadata {
            val idObj = runCatching { JSONObject(identifiersJson ?: "{}") }.getOrDefault(JSONObject())
            val detObj = runCatching { JSONObject(detailsJson ?: "{}") }.getOrDefault(JSONObject())
            return CryptoMetadata(
                tokenSymbol = idObj.optString("tokenSymbol").takeIf(String::isNotBlank),
                contractAddress = idObj.optString("contractAddress").takeIf(String::isNotBlank),
                blockchainNetwork = detObj.optString("blockchainNetwork").takeIf(String::isNotBlank),
                walletOrExchangeName = detObj.optString("walletOrExchangeName").takeIf(String::isNotBlank),
            )
        }
    }
}
