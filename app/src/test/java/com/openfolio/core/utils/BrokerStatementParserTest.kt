package com.openfolio.core.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class BrokerStatementParserTest {

    private lateinit var parser: BrokerStatementParser

    @Before
    fun setUp() {
        parser = BrokerStatementParser()
    }

    @Test
    fun isBrokerStatementIdentifiesBrokerKeywords() {
        val zerodhaSample = """
            Zerodha Broking Limited
            Tradebook for Equity segment
            Date Symbol Type Quantity Price Net Total
        """.trimIndent()

        assertTrue(parser.isBrokerStatement(zerodhaSample))

        val casSample = """
            Consolidated Account Statement
            CAMS Investor Services
            Folio No: 12345
        """.trimIndent()

        assertFalse(parser.isBrokerStatement(casSample))
    }

    @Test
    fun parseExtractsZerodhaTradebookTransactions() {
        val text = """
            Zerodha Broking Limited
            Tradebook from 01-01-2024 to 31-01-2024
            
            Symbol | ISIN | Trade Date | Exchange | Segment | Series | Trade Type | Quantity | Price | Trade Value
            INFY | INE009A01021 | 15-01-2024 | NSE | EQ | EQ | BUY | 10 | 1,500.50 | 15,005.00
            TCS | INE467B01029 | 18-01-2024 | NSE | EQ | EQ | SELL | 5 | 3,850.00 | 19,250.00
            RELIANCE | INE002A01018 | 20-01-2024 | NSE | EQ | EQ | BUY | 20 | 2,450.00 | 49,000.00
        """.trimIndent()

        val drafts = parser.parse(text, "doc_zerodha_1")

        assertEquals(3, drafts.size)

        // 1. INFY Buy
        val d1 = drafts[0]
        assertEquals("INFY", d1.assetName)
        assertEquals("BUY", d1.transactionType)
        assertEquals("10", d1.quantity)
        assertEquals("1500.50", d1.price)
        assertEquals("15005.00", d1.netAmount)
        assertEquals("INE009A01021", d1.isin)
        assertEquals("STOCK", d1.assetClass)
        assertEquals("Zerodha", d1.broker)
        assertEquals(96, d1.confidence)
        assertEquals(true, d1.isAccepted)
        assertEquals(false, d1.isManual)
        assertNotNull(d1.tradeDate)

        // 2. TCS Sell
        val d2 = drafts[1]
        assertEquals("TCS", d2.assetName)
        assertEquals("SELL", d2.transactionType)
        assertEquals("5", d2.quantity)
        assertEquals("3850.00", d2.price)
        assertEquals("19250.00", d2.netAmount)
        assertEquals("INE467B01029", d2.isin)

        // 3. RELIANCE Buy
        val d3 = drafts[2]
        assertEquals("RELIANCE", d3.assetName)
        assertEquals("BUY", d3.transactionType)
        assertEquals("20", d3.quantity)
        assertEquals("2450.00", d3.price)
        assertEquals("49000.00", d3.netAmount)
    }

    @Test
    fun parseExtractsGrowwOrderReportTransactions() {
        val text = """
            Groww Invest Tech Pvt Ltd
            Order Report
            
            Stock Name Date Type Quantity Price Amount
            TATA MOTORS 12/02/2024 BUY 25 920.40 23,010.00
            HDFC BANK 14/02/2024 SELL 15 1,420.00 21,300.00
        """.trimIndent()

        val drafts = parser.parse(text, "doc_groww_1")

        assertEquals(2, drafts.size)

        assertEquals("TATA MOTORS", drafts[0].assetName)
        assertEquals("BUY", drafts[0].transactionType)
        assertEquals("25", drafts[0].quantity)
        assertEquals("920.40", drafts[0].price)
        assertEquals("23010.00", drafts[0].netAmount)
        assertEquals("Groww", drafts[0].broker)

        assertEquals("HDFC BANK", drafts[1].assetName)
        assertEquals("SELL", drafts[1].transactionType)
        assertEquals("15", drafts[1].quantity)
        assertEquals("1420.00", drafts[1].price)
        assertEquals("21300.00", drafts[1].netAmount)
    }

    @Test
    fun parseExtractsAngelOneTradeReport() {
        val text = """
            Angel One Limited
            Contract Note / Trade Confirmation
            
            Order No Trade No Trade Time Scrip Name Buy/Sell Qty Price Net Amount
            1001 5001 10:15:20 WIPRO BUY 50 480.00 24000.00 12-Jan-2024
        """.trimIndent()

        val drafts = parser.parse(text, "doc_angel_1")

        assertEquals(1, drafts.size)
        assertEquals("WIPRO", drafts[0].assetName)
        assertEquals("BUY", drafts[0].transactionType)
        assertEquals("50", drafts[0].quantity)
        assertEquals("480.00", drafts[0].price)
        assertEquals("24000.00", drafts[0].netAmount)
        assertEquals("Angel One", drafts[0].broker)
    }

    @Test
    fun parseHandlesRegexFallbackLine() {
        val text = """
            Broker Trade Summary
            Account: 12345
            
            2024-03-01 BUY ITC 100 410.50 41050.00
            2024-03-05 SELL SBIN 50 750.00 37500.00
        """.trimIndent()

        val drafts = parser.parse(text, "doc_fallback")

        assertEquals(2, drafts.size)
        assertEquals("ITC", drafts[0].assetName)
        assertEquals("BUY", drafts[0].transactionType)
        assertEquals("100", drafts[0].quantity)
        assertEquals("410.50", drafts[0].price)
        assertEquals("41050.00", drafts[0].netAmount)

        assertEquals("SBIN", drafts[1].assetName)
        assertEquals("SELL", drafts[1].transactionType)
        assertEquals("50", drafts[1].quantity)
        assertEquals("750.00", drafts[1].price)
        assertEquals("37500.00", drafts[1].netAmount)
    }
}
