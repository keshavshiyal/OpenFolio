package com.openfolio.core.utils

import com.openfolio.core.data.local.entity.AssetClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BrokerStatementParserStreamingTest {

    private val parser = BrokerStatementParser()

    @Test
    fun parsePageSkipsRepeatedHeadersAndPreservesColumnLayoutAcross50Pages() {
        val state = BrokerParseState()
        val allDrafts = mutableListOf<com.openfolio.core.data.local.entity.DraftTransactionEntity>()

        for (page in 1..50) {
            val pageText = buildString {
                appendLine("Zerodha Broking Limited")
                appendLine("Tradebook for Equity Segment • Page $page of 50")
                appendLine("Symbol | ISIN | Trade Date | Exchange | Segment | Series | Trade Type | Quantity | Price | Trade Value")
                for (row in 1..5) {
                    val day = ((page + row) % 28) + 1
                    val dayStr = day.toString().padStart(2, '0')
                    val type = if (row % 2 == 0) "SELL" else "BUY"
                    appendLine("INFY | INE009A01021 | $dayStr-01-2024 | NSE | EQ | EQ | $type | 10 | 1,500.50 | 15,005.00")
                }
                appendLine("Total Trade Value: 75,025.00")
            }
            allDrafts.addAll(
                parser.parsePage(
                    pageText = pageText,
                    pageNumber = page,
                    documentId = "doc-broker-50",
                    state = state,
                    defaultAssetClass = AssetClass.STOCK,
                ),
            )
        }

        // 50 pages * 5 trades = 250; repeated headers and "Total" footers must be skipped
        assertEquals(250, allDrafts.size)
        assertTrue(state.hasBrokerSignal)
        assertEquals("Zerodha", state.brokerName)

        val page1Draft = allDrafts.first()
        assertEquals(1, page1Draft.sourcePage)
        assertEquals("INFY", page1Draft.assetName)
        assertEquals("INE009A01021", page1Draft.isin)
        assertEquals("1500.50", page1Draft.price)
        assertEquals("15005.00", page1Draft.netAmount)

        val page50Draft = allDrafts.last()
        assertEquals(50, page50Draft.sourcePage)
        assertEquals("Zerodha", page50Draft.broker)
    }
}
