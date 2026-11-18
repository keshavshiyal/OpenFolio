package com.openfolio.core.domain.calculator

import com.openfolio.core.data.local.entity.AssetStatus
import com.openfolio.core.data.local.entity.LotEntity
import com.openfolio.core.data.local.entity.TransactionEntity
import com.openfolio.core.data.local.entity.TransactionType
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.UUID

data class RecalculationResult(
    val assetId: String,
    val totalQuantity: BigDecimal,
    val totalInvested: BigDecimal,
    val averageCost: BigDecimal,
    val realizedPnL: BigDecimal,
    val status: AssetStatus,
    val lots: List<LotEntity>,
)

object FifoCostBasisCalculator {

    private data class MutableLot(
        val id: String = UUID.randomUUID().toString(),
        val assetId: String,
        val acquiredDate: java.time.LocalDate,
        val originalQuantity: BigDecimal,
        var remainingQuantity: BigDecimal,
        var costPerUnit: BigDecimal,
        val sourceTransactionId: String,
    ) {
        fun toEntity(): LotEntity = LotEntity(
            id = id,
            assetId = assetId,
            acquiredDate = acquiredDate,
            originalQuantity = originalQuantity,
            remainingQuantity = remainingQuantity,
            costPerUnit = costPerUnit,
            sourceTransactionId = sourceTransactionId,
        )
    }

    fun calculate(
        assetId: String,
        transactions: List<TransactionEntity>,
    ): RecalculationResult {
        if (transactions.isEmpty()) {
            return RecalculationResult(
                assetId = assetId,
                totalQuantity = BigDecimal.ZERO,
                totalInvested = BigDecimal.ZERO,
                averageCost = BigDecimal.ZERO,
                realizedPnL = BigDecimal.ZERO,
                status = AssetStatus.CLOSED,
                lots = emptyList(),
            )
        }

        // Strict chronological ordering: tradeDate ASC, then createdAt ASC, then id ASC
        val sortedTxns = transactions.sortedWith(
            compareBy<TransactionEntity> { it.tradeDate }
                .thenBy { it.createdAt }
                .thenBy { it.id }
        )

        val lots = mutableListOf<MutableLot>()
        var cumulativeRealizedPnL = BigDecimal.ZERO

        for (tx in sortedTxns) {
            when (tx.type) {
                TransactionType.BUY, TransactionType.TRANSFER_IN -> {
                    val qty = tx.quantity ?: if (tx.unitPrice != null && tx.unitPrice > BigDecimal.ZERO) {
                        tx.netAmount.divide(tx.unitPrice, 6, RoundingMode.HALF_UP)
                    } else {
                        BigDecimal.ZERO
                    }
                    val invested = tx.netAmount
                    val costPerUnit = if (qty > BigDecimal.ZERO) {
                        invested.divide(qty, 6, RoundingMode.HALF_UP)
                    } else {
                        BigDecimal.ZERO
                    }

                    if (qty > BigDecimal.ZERO) {
                        lots.add(
                            MutableLot(
                                assetId = assetId,
                                acquiredDate = tx.tradeDate,
                                originalQuantity = qty,
                                remainingQuantity = qty,
                                costPerUnit = costPerUnit,
                                sourceTransactionId = tx.id,
                            )
                        )
                    }
                }

                TransactionType.BONUS -> {
                    val qty = tx.quantity ?: BigDecimal.ZERO
                    if (qty > BigDecimal.ZERO) {
                        // Bonus shares are acquired at zero cost
                        lots.add(
                            MutableLot(
                                assetId = assetId,
                                acquiredDate = tx.tradeDate,
                                originalQuantity = qty,
                                remainingQuantity = qty,
                                costPerUnit = BigDecimal.ZERO,
                                sourceTransactionId = tx.id,
                            )
                        )
                    }
                }

                TransactionType.SPLIT -> {
                    val splitRatio = tx.quantity
                    if (splitRatio != null && splitRatio > BigDecimal.ZERO && splitRatio != BigDecimal.ONE) {
                        for (lot in lots) {
                            if (lot.remainingQuantity > BigDecimal.ZERO) {
                                lot.remainingQuantity = lot.remainingQuantity.multiply(splitRatio)
                                lot.costPerUnit = lot.costPerUnit.divide(splitRatio, 6, RoundingMode.HALF_UP)
                            }
                        }
                    }
                }

                TransactionType.SELL, TransactionType.TRANSFER_OUT -> {
                    val sellQty = tx.quantity ?: if (tx.unitPrice != null && tx.unitPrice > BigDecimal.ZERO) {
                        tx.netAmount.divide(tx.unitPrice, 6, RoundingMode.HALF_UP)
                    } else {
                        BigDecimal.ZERO
                    }
                    val proceeds = tx.netAmount
                    if (sellQty > BigDecimal.ZERO) {
                        var remainingToSell = sellQty
                        var costOfSoldShares = BigDecimal.ZERO

                        // FIFO lot matching: consume from earliest active lot
                        for (lot in lots) {
                            if (remainingToSell <= BigDecimal.ZERO) break
                            if (lot.remainingQuantity <= BigDecimal.ZERO) continue

                            val matchQty = lot.remainingQuantity.min(remainingToSell)
                            val lotCost = matchQty.multiply(lot.costPerUnit)
                            costOfSoldShares = costOfSoldShares.add(lotCost)

                            lot.remainingQuantity = lot.remainingQuantity.subtract(matchQty)
                            remainingToSell = remainingToSell.subtract(matchQty)
                        }

                        // Realized capital gain on this sale = sale proceeds - cost basis of matched units
                        val saleGain = proceeds.subtract(costOfSoldShares)
                        cumulativeRealizedPnL = cumulativeRealizedPnL.add(saleGain)
                    }
                }

                TransactionType.DIVIDEND,
                TransactionType.INTEREST,
                TransactionType.FEE,
                TransactionType.TAX,
                TransactionType.CHARGE,
                TransactionType.MERGER,
                TransactionType.VALUATION,
                TransactionType.OTHER -> {
                    // Non-holding-altering transactions
                }
            }
        }

        val activeLots = lots.filter { it.remainingQuantity > BigDecimal.ZERO }
        val finalQuantity = activeLots.fold(BigDecimal.ZERO) { acc, lot -> acc.add(lot.remainingQuantity) }
        val finalInvested = activeLots.fold(BigDecimal.ZERO) { acc, lot ->
            acc.add(lot.remainingQuantity.multiply(lot.costPerUnit))
        }.setScale(2, RoundingMode.HALF_UP)

        val averageCost = if (finalQuantity > BigDecimal.ZERO) {
            finalInvested.divide(finalQuantity, 4, RoundingMode.HALF_UP)
        } else {
            BigDecimal.ZERO
        }

        val finalRealizedPnL = cumulativeRealizedPnL.setScale(2, RoundingMode.HALF_UP)
        val status = when {
            finalQuantity > BigDecimal.ZERO -> AssetStatus.ACTIVE
            transactions.isNotEmpty() -> AssetStatus.SOLD
            else -> AssetStatus.CLOSED
        }

        return RecalculationResult(
            assetId = assetId,
            totalQuantity = finalQuantity,
            totalInvested = finalInvested,
            averageCost = averageCost,
            realizedPnL = finalRealizedPnL,
            status = status,
            lots = lots.map { it.toEntity() },
        )
    }

    fun calculateTransactionGains(
        transactions: List<TransactionEntity>,
    ): Map<String, BigDecimal> {
        if (transactions.isEmpty()) return emptyMap()

        val sortedTxns = transactions.sortedWith(
            compareBy<TransactionEntity> { it.tradeDate }
                .thenBy { it.createdAt }
                .thenBy { it.id }
        )

        val lots = mutableListOf<MutableLot>()
        val gainsMap = mutableMapOf<String, BigDecimal>()

        for (tx in sortedTxns) {
            when (tx.type) {
                TransactionType.BUY, TransactionType.TRANSFER_IN -> {
                    val qty = tx.quantity ?: if (tx.unitPrice != null && tx.unitPrice > BigDecimal.ZERO) {
                        tx.netAmount.divide(tx.unitPrice, 6, RoundingMode.HALF_UP)
                    } else {
                        BigDecimal.ZERO
                    }
                    val invested = tx.netAmount
                    val costPerUnit = if (qty > BigDecimal.ZERO) {
                        invested.divide(qty, 6, RoundingMode.HALF_UP)
                    } else {
                        BigDecimal.ZERO
                    }

                    if (qty > BigDecimal.ZERO) {
                        lots.add(
                            MutableLot(
                                assetId = tx.assetId,
                                acquiredDate = tx.tradeDate,
                                originalQuantity = qty,
                                remainingQuantity = qty,
                                costPerUnit = costPerUnit,
                                sourceTransactionId = tx.id,
                            )
                        )
                    }
                }

                TransactionType.BONUS -> {
                    val qty = tx.quantity ?: BigDecimal.ZERO
                    if (qty > BigDecimal.ZERO) {
                        lots.add(
                            MutableLot(
                                assetId = tx.assetId,
                                acquiredDate = tx.tradeDate,
                                originalQuantity = qty,
                                remainingQuantity = qty,
                                costPerUnit = BigDecimal.ZERO,
                                sourceTransactionId = tx.id,
                            )
                        )
                    }
                }

                TransactionType.SPLIT -> {
                    val splitRatio = tx.quantity
                    if (splitRatio != null && splitRatio > BigDecimal.ZERO && splitRatio != BigDecimal.ONE) {
                        for (lot in lots) {
                            if (lot.remainingQuantity > BigDecimal.ZERO) {
                                lot.remainingQuantity = lot.remainingQuantity.multiply(splitRatio)
                                lot.costPerUnit = lot.costPerUnit.divide(splitRatio, 6, RoundingMode.HALF_UP)
                            }
                        }
                    }
                }

                TransactionType.SELL, TransactionType.TRANSFER_OUT -> {
                    val sellQty = tx.quantity ?: if (tx.unitPrice != null && tx.unitPrice > BigDecimal.ZERO) {
                        tx.netAmount.divide(tx.unitPrice, 6, RoundingMode.HALF_UP)
                    } else {
                        BigDecimal.ZERO
                    }
                    val proceeds = tx.netAmount
                    if (sellQty > BigDecimal.ZERO) {
                        var remainingToSell = sellQty
                        var costOfSoldShares = BigDecimal.ZERO

                        for (lot in lots) {
                            if (remainingToSell <= BigDecimal.ZERO) break
                            if (lot.remainingQuantity <= BigDecimal.ZERO) continue

                            val matchQty = lot.remainingQuantity.min(remainingToSell)
                            val lotCost = matchQty.multiply(lot.costPerUnit)
                            costOfSoldShares = costOfSoldShares.add(lotCost)

                            lot.remainingQuantity = lot.remainingQuantity.subtract(matchQty)
                            remainingToSell = remainingToSell.subtract(matchQty)
                        }

                        val saleGain = proceeds.subtract(costOfSoldShares).setScale(2, RoundingMode.HALF_UP)
                        gainsMap[tx.id] = saleGain
                    }
                }

                else -> {
                    // Non-holding-altering transactions
                }
            }
        }

        return gainsMap
    }
}
