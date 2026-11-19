package com.openfolio.core.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.math.BigDecimal

class AssetMetadataModelsTest {

    @Test
    fun stockMetadataSerializesAndDeserializesAccurately() {
        val original = StockMetadata(
            isin = "INE009A01021",
            ticker = "INFY",
            exchange = "NSE",
            series = "EQ",
            brokerName = "Zerodha Broking Ltd",
            dpId = "12081600",
            dematAccountNumber = "1208160012345678",
        )

        val idJson = original.toIdentifiersJson()
        val detJson = original.toDetailsJson()

        val parsed = StockMetadata.fromJson(idJson, detJson)

        assertEquals("INE009A01021", parsed.isin)
        assertEquals("INFY", parsed.ticker)
        assertEquals("NSE", parsed.exchange)
        assertEquals("EQ", parsed.series)
        assertEquals("Zerodha Broking Ltd", parsed.brokerName)
        assertEquals("12081600", parsed.dpId)
        assertEquals("1208160012345678", parsed.dematAccountNumber)
    }

    @Test
    fun mutualFundMetadataSerializesAndDeserializesAccurately() {
        val original = MutualFundMetadata(
            folioNumber = "12345678/90",
            amfiCode = "120503",
            isin = "INF209K01157",
            amcName = "HDFC Mutual Fund",
            planType = "Direct",
            option = "Growth",
            rta = "CAMS",
        )

        val idJson = original.toIdentifiersJson()
        val detJson = original.toDetailsJson()

        val parsed = MutualFundMetadata.fromJson(idJson, detJson)

        assertEquals("12345678/90", parsed.folioNumber)
        assertEquals("120503", parsed.amfiCode)
        assertEquals("INF209K01157", parsed.isin)
        assertEquals("HDFC Mutual Fund", parsed.amcName)
        assertEquals("Direct", parsed.planType)
        assertEquals("Growth", parsed.option)
        assertEquals("CAMS", parsed.rta)
    }

    @Test
    fun fixedDepositMetadataSerializesAndDeserializesAccurately() {
        val original = FixedDepositMetadata(
            certificateNumber = "FD-998877",
            accountNumber = "987654321012",
            bankName = "State Bank of India",
            branchIfsc = "SBIN0001234",
            interestRate = BigDecimal("7.10"),
            maturityDate = 1750000000000L,
            maturityAmount = BigDecimal("550000.00"),
            tenureMonths = 36,
        )

        val idJson = original.toIdentifiersJson()
        val detJson = original.toDetailsJson()

        val parsed = FixedDepositMetadata.fromJson(idJson, detJson)

        assertEquals("FD-998877", parsed.certificateNumber)
        assertEquals("987654321012", parsed.accountNumber)
        assertEquals("State Bank of India", parsed.bankName)
        assertEquals("SBIN0001234", parsed.branchIfsc)
        assertEquals(BigDecimal("7.10"), parsed.interestRate)
        assertEquals(1750000000000L, parsed.maturityDate)
        assertEquals(BigDecimal("550000.00"), parsed.maturityAmount)
        assertEquals(36, parsed.tenureMonths)
    }

    @Test
    fun handlesNullAndEmptyJsonGracefully() {
        val emptyStock = StockMetadata.fromJson(null, null)
        assertNull(emptyStock.isin)
        assertNull(emptyStock.ticker)
        assertNull(emptyStock.brokerName)

        val emptyMf = MutualFundMetadata.fromJson("{}", "{}")
        assertNull(emptyMf.folioNumber)
        assertNull(emptyMf.amcName)
    }
}
