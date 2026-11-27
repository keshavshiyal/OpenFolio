package com.openfolio.core.data.local.export

import com.openfolio.core.data.local.OpenFolioDatabase
import com.openfolio.core.data.local.entity.AssetEntity
import com.openfolio.core.data.local.entity.AssetStatus
import com.openfolio.core.data.local.entity.DocumentEntity
import com.openfolio.core.data.local.entity.LotEntity
import com.openfolio.core.data.local.entity.TransactionEntity
import com.openfolio.core.data.local.entity.TransactionType
import com.openfolio.core.domain.calculator.FifoCostBasisCalculator
import com.openfolio.feature.assets.toDisplayTitle
import com.opencsv.CSVWriter
import java.io.BufferedWriter
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.math.BigDecimal
import java.math.RoundingMode
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

enum class CsvReportType(
    val title: String,
    val description: String,
    val filePrefix: String,
) {
    CURRENT_HOLDINGS(
        title = "Current Holdings",
        description = "Active assets with units held, average cost, invested value, and P&L",
        filePrefix = "openfolio_current_holdings",
    ),
    ASSET_SUMMARY(
        title = "Asset-Wise Summary",
        description = "Per-asset totals for buys, sells, dividends, fees, holdings, and realized P&L",
        filePrefix = "openfolio_assets_summary",
    ),
    TRANSACTIONS(
        title = "All Transactions",
        description = "Complete chronological ledger with dates, quantities, prices, and provenance",
        filePrefix = "openfolio_transactions",
    ),
    LIABILITIES_CHARGES(
        title = "Liabilities, Fees & Taxes",
        description = "All recorded fees, taxes, charges, and liability outflows across assets",
        filePrefix = "openfolio_liabilities_charges",
    ),
    FIFO_LOTS(
        title = "FIFO Cost-Basis Lots",
        description = "Open acquisition tax lots with remaining units and per-unit cost basis",
        filePrefix = "openfolio_fifo_lots",
    ),
    REALIZED_GAINS_INCOME(
        title = "Realized Gains & Income",
        description = "Capital gains on sales (FIFO) plus dividends and interest income",
        filePrefix = "openfolio_realized_gains_income",
    ),
}

data class CsvReportExportResult(
    val reportType: CsvReportType,
    val rowCount: Int,
    val fileName: String,
)

interface CsvReportExporter {
    fun getDefaultFileName(reportType: CsvReportType): String

    suspend fun exportReport(
        reportType: CsvReportType,
        outputStream: OutputStream,
    ): CsvReportExportResult
}

@Singleton
class CsvReportExporterImpl @Inject constructor(
    private val database: OpenFolioDatabase,
) : CsvReportExporter {

    private val timestampFormatter = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss")
        .withZone(ZoneId.systemDefault())
    private val displayInstantFormatter = DateTimeFormatter.ISO_LOCAL_DATE
        .withZone(ZoneId.systemDefault())

    override fun getDefaultFileName(reportType: CsvReportType): String {
        val stamp = timestampFormatter.format(Instant.now())
        return "${reportType.filePrefix}_${stamp}.csv"
    }

    override suspend fun exportReport(
        reportType: CsvReportType,
        outputStream: OutputStream,
    ): CsvReportExportResult = withContext(Dispatchers.IO) {
        val assets = database.assetDao().getAllOnce()
        val transactions = database.transactionDao().getAllOnce()
        val lots = database.lotDao().getAllOnce()
        val documents = database.documentDao().getAllDocumentsOnce()

        exportFromSnapshot(
            reportType = reportType,
            assets = assets,
            transactions = transactions,
            lots = lots,
            documents = documents,
            outputStream = outputStream,
        )
    }

    internal fun exportFromSnapshot(
        reportType: CsvReportType,
        assets: List<AssetEntity>,
        transactions: List<TransactionEntity>,
        lots: List<LotEntity>,
        documents: List<DocumentEntity>,
        outputStream: OutputStream,
    ): CsvReportExportResult {
        val assetsById = assets.associateBy { it.id }
        val docsById = documents.associateBy { it.id }

        val writer = BufferedWriter(OutputStreamWriter(outputStream, StandardCharsets.UTF_8))
        var rowCount = 0

        CSVWriter(writer).use { csv ->
            when (reportType) {
                CsvReportType.CURRENT_HOLDINGS -> {
                    csv.writeNext(
                        arrayOf(
                            "Asset ID",
                            "Asset Name",
                            "Asset Class",
                            "Status",
                            "Currency",
                            "Quantity Held",
                            "Avg Cost Basis",
                            "Total Invested",
                            "Current Price",
                            "Current Value",
                            "Unrealized P&L",
                            "Realized P&L",
                            "Identifiers",
                            "Last Updated",
                        ),
                    )
                    val activeHoldings = assets
                        .filter { it.totalQuantity > BigDecimal.ZERO || it.status == AssetStatus.ACTIVE }
                        .sortedWith(compareBy<AssetEntity> { it.assetClass.name }.thenBy { it.displayName.lowercase() })

                    for (asset in activeHoldings) {
                        val currentValue = asset.currentPrice?.let {
                            asset.totalQuantity.multiply(it).setScale(2, RoundingMode.HALF_UP)
                        }
                        val unrealizedPnl = currentValue?.subtract(asset.totalInvested)
                        csv.writeNext(
                            arrayOf(
                                asset.id,
                                asset.displayName,
                                asset.assetClass.toDisplayTitle(),
                                asset.status.name,
                                asset.currency,
                                formatQty(asset.totalQuantity),
                                formatMoney(asset.averageCost),
                                formatMoney(asset.totalInvested),
                                asset.currentPrice?.let(::formatMoney).orEmpty(),
                                currentValue?.let(::formatMoney).orEmpty(),
                                unrealizedPnl?.let(::formatMoney).orEmpty(),
                                formatMoney(asset.realizedPnL),
                                formatIdentifiers(asset.identifiersJson),
                                displayInstantFormatter.format(Instant.ofEpochMilli(asset.updatedAt)),
                            ),
                        )
                        rowCount++
                    }
                }

                CsvReportType.ASSET_SUMMARY -> {
                    csv.writeNext(
                        arrayOf(
                            "Asset ID",
                            "Asset Name",
                            "Asset Class",
                            "Status",
                            "Transactions Count",
                            "Total Bought Qty",
                            "Total Bought Amount",
                            "Total Sold Qty",
                            "Total Sold Proceeds",
                            "Dividends & Interest",
                            "Fees & Taxes",
                            "Current Qty",
                            "Avg Cost Basis",
                            "Current Invested",
                            "Realized P&L",
                        ),
                    )
                    val txnsByAsset = transactions.groupBy { it.assetId }
                    for (asset in assets.sortedBy { it.displayName.lowercase() }) {
                        val assetTxns = txnsByAsset[asset.id].orEmpty()
                        var boughtQty = BigDecimal.ZERO
                        var boughtAmt = BigDecimal.ZERO
                        var soldQty = BigDecimal.ZERO
                        var soldAmt = BigDecimal.ZERO
                        var incomeAmt = BigDecimal.ZERO
                        var chargesAmt = BigDecimal.ZERO

                        for (tx in assetTxns) {
                            when (tx.type) {
                                TransactionType.BUY, TransactionType.TRANSFER_IN -> {
                                    boughtQty = boughtQty.add(tx.quantity ?: BigDecimal.ZERO)
                                    boughtAmt = boughtAmt.add(tx.netAmount)
                                }
                                TransactionType.SELL, TransactionType.TRANSFER_OUT -> {
                                    soldQty = soldQty.add(tx.quantity ?: BigDecimal.ZERO)
                                    soldAmt = soldAmt.add(tx.netAmount)
                                }
                                TransactionType.DIVIDEND, TransactionType.INTEREST -> {
                                    incomeAmt = incomeAmt.add(tx.netAmount)
                                }
                                TransactionType.FEE, TransactionType.TAX, TransactionType.CHARGE -> {
                                    chargesAmt = chargesAmt.add(tx.netAmount)
                                }
                                else -> Unit
                            }
                            chargesAmt = chargesAmt
                                .add(tx.fees ?: BigDecimal.ZERO)
                                .add(tx.taxes ?: BigDecimal.ZERO)
                        }

                        csv.writeNext(
                            arrayOf(
                                asset.id,
                                asset.displayName,
                                asset.assetClass.toDisplayTitle(),
                                asset.status.name,
                                assetTxns.size.toString(),
                                formatQty(boughtQty),
                                formatMoney(boughtAmt),
                                formatQty(soldQty),
                                formatMoney(soldAmt),
                                formatMoney(incomeAmt),
                                formatMoney(chargesAmt),
                                formatQty(asset.totalQuantity),
                                formatMoney(asset.averageCost),
                                formatMoney(asset.totalInvested),
                                formatMoney(asset.realizedPnL),
                            ),
                        )
                        rowCount++
                    }
                }

                CsvReportType.TRANSACTIONS -> {
                    csv.writeNext(
                        arrayOf(
                            "Transaction ID",
                            "Trade Date",
                            "Asset Name",
                            "Asset Class",
                            "Type",
                            "Quantity",
                            "Unit Price",
                            "Gross Amount",
                            "Fees",
                            "Taxes",
                            "Net Amount",
                            "Currency",
                            "Broker",
                            "Source Document",
                            "Confidence",
                            "Status",
                            "Notes",
                        ),
                    )
                    val sorted = transactions.sortedWith(
                        compareByDescending<TransactionEntity> { it.tradeDate }
                            .thenByDescending { it.createdAt },
                    )
                    for (tx in sorted) {
                        val asset = assetsById[tx.assetId]
                        val docName = tx.documentId?.let { docsById[it]?.fileName } ?: "Manual"
                        csv.writeNext(
                            arrayOf(
                                tx.id,
                                tx.tradeDate.toString(),
                                asset?.displayName ?: tx.assetId,
                                asset?.assetClass?.toDisplayTitle().orEmpty(),
                                tx.type.name,
                                tx.quantity?.let(::formatQty).orEmpty(),
                                tx.unitPrice?.let(::formatMoney).orEmpty(),
                                formatMoney(tx.grossAmount),
                                tx.fees?.let(::formatMoney) ?: "0.00",
                                tx.taxes?.let(::formatMoney) ?: "0.00",
                                formatMoney(tx.netAmount),
                                tx.currency,
                                tx.broker.orEmpty(),
                                docName,
                                tx.confidence?.let { "${(it * 100).toInt()}%" }.orEmpty(),
                                tx.status.name,
                                tx.notes ?: tx.description.orEmpty(),
                            ),
                        )
                        rowCount++
                    }
                }

                CsvReportType.LIABILITIES_CHARGES -> {
                    csv.writeNext(
                        arrayOf(
                            "Trade Date",
                            "Asset Name",
                            "Asset Class",
                            "Category",
                            "Amount",
                            "Currency",
                            "Broker",
                            "Related Transaction Type",
                            "Source Document",
                            "Notes",
                            "Transaction ID",
                        ),
                    )
                    val sorted = transactions.sortedWith(
                        compareByDescending<TransactionEntity> { it.tradeDate }
                            .thenByDescending { it.createdAt },
                    )
                    for (tx in sorted) {
                        val asset = assetsById[tx.assetId]
                        val docName = tx.documentId?.let { docsById[it]?.fileName } ?: "Manual"
                        val assetName = asset?.displayName ?: tx.assetId
                        val assetClassTitle = asset?.assetClass?.toDisplayTitle().orEmpty()

                        if (tx.type in LIABILITY_TRANSACTION_TYPES) {
                            csv.writeNext(
                                arrayOf(
                                    tx.tradeDate.toString(),
                                    assetName,
                                    assetClassTitle,
                                    tx.type.name,
                                    formatMoney(tx.netAmount),
                                    tx.currency,
                                    tx.broker.orEmpty(),
                                    tx.type.name,
                                    docName,
                                    tx.notes ?: tx.description.orEmpty(),
                                    tx.id,
                                ),
                            )
                            rowCount++
                        } else {
                            val fee = tx.fees ?: BigDecimal.ZERO
                            if (fee > BigDecimal.ZERO) {
                                csv.writeNext(
                                    arrayOf(
                                        tx.tradeDate.toString(),
                                        assetName,
                                        assetClassTitle,
                                        "TRADE_FEE",
                                        formatMoney(fee),
                                        tx.currency,
                                        tx.broker.orEmpty(),
                                        tx.type.name,
                                        docName,
                                        tx.notes.orEmpty(),
                                        tx.id,
                                    ),
                                )
                                rowCount++
                            }
                            val tax = tx.taxes ?: BigDecimal.ZERO
                            if (tax > BigDecimal.ZERO) {
                                csv.writeNext(
                                    arrayOf(
                                        tx.tradeDate.toString(),
                                        assetName,
                                        assetClassTitle,
                                        "TRADE_TAX",
                                        formatMoney(tax),
                                        tx.currency,
                                        tx.broker.orEmpty(),
                                        tx.type.name,
                                        docName,
                                        tx.notes.orEmpty(),
                                        tx.id,
                                    ),
                                )
                                rowCount++
                            }
                        }
                    }
                }

                CsvReportType.FIFO_LOTS -> {
                    csv.writeNext(
                        arrayOf(
                            "Lot ID",
                            "Asset Name",
                            "Asset Class",
                            "Acquired Date",
                            "Original Quantity",
                            "Remaining Quantity",
                            "Cost Per Unit",
                            "Remaining Cost Basis",
                            "Currency",
                            "Source Transaction ID",
                        ),
                    )
                    val openLots = lots
                        .filter { it.remainingQuantity > BigDecimal.ZERO }
                        .sortedWith(
                            compareBy<LotEntity> { assetsById[it.assetId]?.displayName?.lowercase() ?: it.assetId }
                                .thenBy { it.acquiredDate },
                        )
                    for (lot in openLots) {
                        val asset = assetsById[lot.assetId]
                        val remainingCost = lot.remainingQuantity.multiply(lot.costPerUnit)
                            .setScale(2, RoundingMode.HALF_UP)
                        csv.writeNext(
                            arrayOf(
                                lot.id,
                                asset?.displayName ?: lot.assetId,
                                asset?.assetClass?.toDisplayTitle().orEmpty(),
                                lot.acquiredDate.toString(),
                                formatQty(lot.originalQuantity),
                                formatQty(lot.remainingQuantity),
                                formatMoney(lot.costPerUnit),
                                formatMoney(remainingCost),
                                asset?.currency ?: "INR",
                                lot.sourceTransactionId,
                            ),
                        )
                        rowCount++
                    }
                }

                CsvReportType.REALIZED_GAINS_INCOME -> {
                    csv.writeNext(
                        arrayOf(
                            "Trade Date",
                            "Asset Name",
                            "Asset Class",
                            "Event Type",
                            "Quantity",
                            "Unit Price",
                            "Proceeds / Income",
                            "FIFO Cost Basis",
                            "Realized Gain / Loss",
                            "Currency",
                            "Broker",
                            "Source Document",
                            "Transaction ID",
                        ),
                    )
                    val gainsByTxnId = mutableMapOf<String, BigDecimal>()
                    transactions.groupBy { it.assetId }.forEach { (_, assetTxns) ->
                        gainsByTxnId.putAll(FifoCostBasisCalculator.calculateTransactionGains(assetTxns))
                    }
                    val events = transactions
                        .filter {
                            it.type in setOf(
                                TransactionType.SELL,
                                TransactionType.TRANSFER_OUT,
                                TransactionType.DIVIDEND,
                                TransactionType.INTEREST,
                                TransactionType.BONUS,
                                TransactionType.SPLIT,
                            )
                        }
                        .sortedWith(
                            compareByDescending<TransactionEntity> { it.tradeDate }
                                .thenByDescending { it.createdAt },
                        )

                    for (tx in events) {
                        val asset = assetsById[tx.assetId]
                        val docName = tx.documentId?.let { docsById[it]?.fileName } ?: "Manual"
                        val gain = gainsByTxnId[tx.id]
                        val costBasis = if (gain != null) {
                            tx.netAmount.subtract(gain).setScale(2, RoundingMode.HALF_UP)
                        } else {
                            BigDecimal.ZERO
                        }
                        val effectiveGain = when (tx.type) {
                            TransactionType.DIVIDEND, TransactionType.INTEREST -> tx.netAmount
                            else -> gain ?: BigDecimal.ZERO
                        }
                        csv.writeNext(
                            arrayOf(
                                tx.tradeDate.toString(),
                                asset?.displayName ?: tx.assetId,
                                asset?.assetClass?.toDisplayTitle().orEmpty(),
                                tx.type.name,
                                tx.quantity?.let(::formatQty).orEmpty(),
                                tx.unitPrice?.let(::formatMoney).orEmpty(),
                                formatMoney(tx.netAmount),
                                formatMoney(costBasis),
                                formatMoney(effectiveGain),
                                tx.currency,
                                tx.broker.orEmpty(),
                                docName,
                                tx.id,
                            ),
                        )
                        rowCount++
                    }
                }
            }
            csv.flush()
        }

        return CsvReportExportResult(
            reportType = reportType,
            rowCount = rowCount,
            fileName = getDefaultFileName(reportType),
        )
    }

    private fun formatQty(value: BigDecimal): String =
        value.stripTrailingZeros().toPlainString()

    private fun formatMoney(value: BigDecimal): String =
        value.setScale(2, RoundingMode.HALF_UP).toPlainString()

    private fun formatIdentifiers(identifiersJson: String): String {
        val obj = runCatching { JSONObject(identifiersJson) }.getOrNull() ?: return ""
        return obj.keys().asSequence()
            .mapNotNull { key ->
                val v = obj.optString(key).trim()
                if (v.isNotBlank()) "$key=$v" else null
            }
            .joinToString("; ")
    }

    private companion object {
        val LIABILITY_TRANSACTION_TYPES = setOf(
            TransactionType.FEE,
            TransactionType.TAX,
            TransactionType.CHARGE,
        )
    }
}
