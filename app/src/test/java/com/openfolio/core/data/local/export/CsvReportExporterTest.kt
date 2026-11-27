package com.openfolio.core.data.local.export

import com.openfolio.core.data.local.entity.AssetClass
import com.openfolio.core.data.local.entity.AssetEntity
import com.openfolio.core.data.local.entity.AssetStatus
import com.openfolio.core.data.local.entity.DocumentEntity
import com.openfolio.core.data.local.entity.LotEntity
import com.openfolio.core.data.local.entity.TransactionEntity
import com.openfolio.core.data.local.entity.TransactionStatus
import com.openfolio.core.data.local.entity.TransactionType
import com.opencsv.CSVReader
import java.io.ByteArrayOutputStream
import java.io.StringReader
import java.math.BigDecimal
import java.nio.charset.StandardCharsets
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.mock

class CsvReportExporterTest {

    private val exporter = CsvReportExporterImpl(mock())

    private val activeAsset = AssetEntity(
        id = "asset-tcs",
        displayName = "TCS Ltd",
        assetClass = AssetClass.STOCK,
        currency = "INR",
        totalQuantity = BigDecimal("10"),
        totalInvested = BigDecimal("35000.00"),
        averageCost = BigDecimal("3500.00"),
        currentPrice = BigDecimal("4000.00"),
        realizedPnL = BigDecimal("2500.00"),
        identifiersJson = """{"isin":"INE467B01029","ticker":"TCS"}""",
        status = AssetStatus.ACTIVE,
        updatedAt = 1_700_000_000_000L,
    )

    private val soldAsset = AssetEntity(
        id = "asset-infy",
        displayName = "Infosys Ltd",
        assetClass = AssetClass.STOCK,
        currency = "INR",
        totalQuantity = BigDecimal.ZERO,
        totalInvested = BigDecimal.ZERO,
        averageCost = BigDecimal.ZERO,
        realizedPnL = BigDecimal("1500.00"),
        identifiersJson = """{"ticker":"INFY"}""",
        status = AssetStatus.SOLD,
        updatedAt = 1_700_000_000_000L,
    )

    private val doc = DocumentEntity(
        id = "doc-contract-1",
        fileName = "zerodha_contract.pdf",
        localPath = "/docs/zerodha_contract.pdf",
        fileHash = "hash-1",
        fileSize = 2048L,
        importDate = 1_700_000_000_000L,
    )

    private val txns = listOf(
        TransactionEntity(
            id = "txn-buy",
            accountId = "acc-1",
            assetId = "asset-tcs",
            type = TransactionType.BUY,
            tradeDate = LocalDate.of(2025, 1, 10),
            quantity = BigDecimal("15"),
            unitPrice = BigDecimal("3500.00"),
            grossAmount = BigDecimal("52500.00"),
            fees = BigDecimal("25.00"),
            taxes = BigDecimal("12.50"),
            netAmount = BigDecimal("52537.50"),
            currency = "INR",
            broker = "Zerodha",
            fingerprint = "fp-1",
            documentId = "doc-contract-1",
            status = TransactionStatus.CONFIRMED,
        ),
        TransactionEntity(
            id = "txn-sell",
            accountId = "acc-1",
            assetId = "asset-tcs",
            type = TransactionType.SELL,
            tradeDate = LocalDate.of(2025, 3, 15),
            quantity = BigDecimal("5"),
            unitPrice = BigDecimal("4000.00"),
            grossAmount = BigDecimal("20000.00"),
            fees = BigDecimal("10.00"),
            taxes = BigDecimal("5.00"),
            netAmount = BigDecimal("19985.00"),
            currency = "INR",
            broker = "Zerodha",
            fingerprint = "fp-2",
            documentId = "doc-contract-1",
            status = TransactionStatus.CONFIRMED,
        ),
        TransactionEntity(
            id = "txn-div",
            accountId = "acc-1",
            assetId = "asset-tcs",
            type = TransactionType.DIVIDEND,
            tradeDate = LocalDate.of(2025, 5, 1),
            quantity = BigDecimal.ZERO,
            unitPrice = BigDecimal.ZERO,
            grossAmount = BigDecimal("600.00"),
            netAmount = BigDecimal("600.00"),
            currency = "INR",
            broker = "Zerodha",
            fingerprint = "fp-3",
            status = TransactionStatus.CONFIRMED,
        ),
        TransactionEntity(
            id = "txn-fee",
            accountId = "acc-1",
            assetId = "asset-tcs",
            type = TransactionType.FEE,
            tradeDate = LocalDate.of(2025, 6, 1),
            quantity = BigDecimal.ZERO,
            unitPrice = BigDecimal.ZERO,
            grossAmount = BigDecimal("354.00"),
            netAmount = BigDecimal("354.00"),
            currency = "INR",
            broker = "Zerodha",
            notes = "Annual AMC",
            fingerprint = "fp-4",
            status = TransactionStatus.CONFIRMED,
        ),
    )

    private val openLot = LotEntity(
        id = "lot-1",
        assetId = "asset-tcs",
        acquiredDate = LocalDate.of(2025, 1, 10),
        originalQuantity = BigDecimal("15"),
        remainingQuantity = BigDecimal("10"),
        costPerUnit = BigDecimal("3500.00"),
        sourceTransactionId = "txn-buy",
    )

    private fun parseCsv(type: CsvReportType): Pair<CsvReportExportResult, List<Array<String>>> {
        val out = ByteArrayOutputStream()
        val result = exporter.exportFromSnapshot(
            reportType = type,
            assets = listOf(activeAsset, soldAsset),
            transactions = txns,
            lots = listOf(openLot),
            documents = listOf(doc),
            outputStream = out,
        )
        val rows = CSVReader(StringReader(out.toString(StandardCharsets.UTF_8.name()))).use { it.readAll() }
        return result to rows
    }

    @Test
    fun currentHoldingsExcludesSoldAssetsAndComputesCurrentValueAndUnrealizedPnl() {
        val (result, rows) = parseCsv(CsvReportType.CURRENT_HOLDINGS)
        assertEquals(1, result.rowCount)
        assertEquals(2, rows.size)
        val tcsRow = rows[1]
        assertEquals("TCS Ltd", tcsRow[1])
        assertEquals("10", tcsRow[5])
        assertEquals("3500.00", tcsRow[6])
        assertEquals("35000.00", tcsRow[7])
        assertEquals("4000.00", tcsRow[8])
        assertEquals("40000.00", tcsRow[9])
        assertEquals("5000.00", tcsRow[10])
    }

    @Test
    fun assetSummaryIncludesActiveAndSoldAssets() {
        val (result, rows) = parseCsv(CsvReportType.ASSET_SUMMARY)
        assertEquals(2, result.rowCount)
        assertEquals(3, rows.size)
        val names = rows.drop(1).map { it[1] }
        assertTrue(names.contains("TCS Ltd"))
        assertTrue(names.contains("Infosys Ltd"))
    }

    @Test
    fun transactionsExportIncludesAllTransactionsAndDocumentFilenames() {
        val (result, rows) = parseCsv(CsvReportType.TRANSACTIONS)
        assertEquals(4, result.rowCount)
        assertEquals(5, rows.size)
        assertTrue(rows.drop(1).any { it.contains("zerodha_contract.pdf") })
        assertTrue(rows.drop(1).any { it.contains("FEE") && it.contains("Annual AMC") })
    }

    @Test
    fun liabilitiesAndChargesIncludesFeeTransactionsAndTradeCharges() {
        val (result, rows) = parseCsv(CsvReportType.LIABILITIES_CHARGES)
        // 1 standalone FEE transaction + 4 trade rows (fees & taxes for txn-buy & txn-sell) = 5 rows
        assertEquals(5, result.rowCount)
        assertFalse(rows.drop(1).any { it.contains("DIVIDEND") })
        assertTrue(rows.drop(1).any { it.contains("354.00") })
    }

    @Test
    fun fifoLotsExportIncludesOpenLotDetails() {
        val (result, rows) = parseCsv(CsvReportType.FIFO_LOTS)
        assertEquals(1, result.rowCount)
        val row = rows[1]
        assertTrue(row.contains("TCS Ltd"))
        assertTrue(row.contains("10"))
        assertTrue(row.contains("35000.00"))
    }

    @Test
    fun realizedGainsAndIncomeExportsSalesAndDividends() {
        val (result, rows) = parseCsv(CsvReportType.REALIZED_GAINS_INCOME)
        assertEquals(2, result.rowCount)
        val types = rows.drop(1).map { it.joinToString("|") }
        assertTrue(types.any { it.contains("SELL") })
        assertTrue(types.any { it.contains("DIVIDEND") && it.contains("600.00") })
    }
}
