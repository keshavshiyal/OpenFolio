package com.openfolio.core.domain.calculator

import com.openfolio.core.data.local.entity.AssetStatus
import com.openfolio.core.data.local.entity.TransactionEntity
import com.openfolio.core.data.local.entity.TransactionStatus
import com.openfolio.core.data.local.entity.TransactionType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal
import java.time.LocalDate

class FifoCostBasisCalculatorTest {

    private fun createTxn(
        id: String,
        type: TransactionType,
        tradeDate: LocalDate,
        quantity: BigDecimal?,
        unitPrice: BigDecimal?,
        netAmount: BigDecimal,
        createdAt: Long = 1000L,
    ): TransactionEntity {
        return TransactionEntity(
            id = id,
            accountId = "acc_default",
            assetId = "asset_rel",
            type = type,
            tradeDate = tradeDate,
            quantity = quantity,
            unitPrice = unitPrice,
            grossAmount = netAmount,
            netAmount = netAmount,
            currency = "INR",
            fingerprint = "fp_$id",
            status = TransactionStatus.CONFIRMED,
            createdAt = createdAt,
        )
    }

    @Test
    fun calculatesCorrectlyForEmptyTransactions() {
        val result = FifoCostBasisCalculator.calculate("asset_rel", emptyList())

        assertEquals("asset_rel", result.assetId)
        assertEquals(BigDecimal.ZERO, result.totalQuantity)
        assertEquals(BigDecimal.ZERO, result.totalInvested)
        assertEquals(BigDecimal.ZERO, result.averageCost)
        assertEquals(BigDecimal.ZERO, result.realizedPnL)
        assertEquals(AssetStatus.CLOSED, result.status)
        assertTrue(result.lots.isEmpty())
    }

    @Test
    fun calculatesCorrectlyForSingleBuy() {
        val buy = createTxn(
            id = "t1",
            type = TransactionType.BUY,
            tradeDate = LocalDate.of(2024, 1, 1),
            quantity = BigDecimal("100"),
            unitPrice = BigDecimal("50"),
            netAmount = BigDecimal("5000"),
        )

        val result = FifoCostBasisCalculator.calculate("asset_rel", listOf(buy))

        assertEquals(0, BigDecimal("100").compareTo(result.totalQuantity))
        assertEquals(0, BigDecimal("5000.00").compareTo(result.totalInvested))
        assertEquals(0, BigDecimal("50.0000").compareTo(result.averageCost))
        assertEquals(0, BigDecimal.ZERO.compareTo(result.realizedPnL))
        assertEquals(AssetStatus.ACTIVE, result.status)
        assertEquals(1, result.lots.size)
        assertEquals(0, BigDecimal("100").compareTo(result.lots[0].remainingQuantity))
    }

    @Test
    fun calculatesWeightedAverageCostForMultipleBuys() {
        val buy1 = createTxn(
            id = "t1",
            type = TransactionType.BUY,
            tradeDate = LocalDate.of(2024, 1, 1),
            quantity = BigDecimal("50"),
            unitPrice = BigDecimal("100"),
            netAmount = BigDecimal("5000"),
        )
        val buy2 = createTxn(
            id = "t2",
            type = TransactionType.BUY,
            tradeDate = LocalDate.of(2024, 1, 10),
            quantity = BigDecimal("50"),
            unitPrice = BigDecimal("120"),
            netAmount = BigDecimal("6000"),
        )

        val result = FifoCostBasisCalculator.calculate("asset_rel", listOf(buy1, buy2))

        assertEquals(0, BigDecimal("100").compareTo(result.totalQuantity))
        assertEquals(0, BigDecimal("11000.00").compareTo(result.totalInvested))
        assertEquals(0, BigDecimal("110.0000").compareTo(result.averageCost))
        assertEquals(0, BigDecimal.ZERO.compareTo(result.realizedPnL))
        assertEquals(AssetStatus.ACTIVE, result.status)
        assertEquals(2, result.lots.size)
    }

    @Test
    fun calculatesFifoRealizedGainAndRemainingBasisOnPartialSell() {
        // Buy 50 @ 100 = 5000 (Lot 1)
        val buy1 = createTxn(
            id = "t1",
            type = TransactionType.BUY,
            tradeDate = LocalDate.of(2024, 1, 1),
            quantity = BigDecimal("50"),
            unitPrice = BigDecimal("100"),
            netAmount = BigDecimal("5000"),
        )
        // Buy 50 @ 120 = 6000 (Lot 2)
        val buy2 = createTxn(
            id = "t2",
            type = TransactionType.BUY,
            tradeDate = LocalDate.of(2024, 1, 10),
            quantity = BigDecimal("50"),
            unitPrice = BigDecimal("120"),
            netAmount = BigDecimal("6000"),
        )
        // Sell 60 @ 150 = 9000
        // FIFO: 50 from Lot 1 (cost 5000) + 10 from Lot 2 (cost 1200) = total cost 6200
        // Realized Gain = 9000 - 6200 = 2800.00
        // Remaining: 40 in Lot 2 @ 120 = 4800.00
        val sell = createTxn(
            id = "t3",
            type = TransactionType.SELL,
            tradeDate = LocalDate.of(2024, 1, 20),
            quantity = BigDecimal("60"),
            unitPrice = BigDecimal("150"),
            netAmount = BigDecimal("9000"),
        )

        val result = FifoCostBasisCalculator.calculate("asset_rel", listOf(buy1, buy2, sell))

        assertEquals(0, BigDecimal("40").compareTo(result.totalQuantity))
        assertEquals(0, BigDecimal("4800.00").compareTo(result.totalInvested))
        assertEquals(0, BigDecimal("120.0000").compareTo(result.averageCost))
        assertEquals(0, BigDecimal("2800.00").compareTo(result.realizedPnL))
        assertEquals(AssetStatus.ACTIVE, result.status)

        // Lot 1 fully consumed, Lot 2 has 40 remaining
        assertEquals(0, BigDecimal.ZERO.compareTo(result.lots[0].remainingQuantity))
        assertEquals(0, BigDecimal("40").compareTo(result.lots[1].remainingQuantity))
    }

    @Test
    fun calculatesFullLiquidationToZeroHoldings() {
        val buy = createTxn(
            id = "t1",
            type = TransactionType.BUY,
            tradeDate = LocalDate.of(2024, 1, 1),
            quantity = BigDecimal("100"),
            unitPrice = BigDecimal("10"),
            netAmount = BigDecimal("1000"),
        )
        val sell = createTxn(
            id = "t2",
            type = TransactionType.SELL,
            tradeDate = LocalDate.of(2024, 1, 15),
            quantity = BigDecimal("100"),
            unitPrice = BigDecimal("15"),
            netAmount = BigDecimal("1500"),
        )

        val result = FifoCostBasisCalculator.calculate("asset_rel", listOf(buy, sell))

        assertEquals(0, BigDecimal.ZERO.compareTo(result.totalQuantity))
        assertEquals(0, BigDecimal.ZERO.compareTo(result.totalInvested))
        assertEquals(0, BigDecimal.ZERO.compareTo(result.averageCost))
        assertEquals(0, BigDecimal("500.00").compareTo(result.realizedPnL))
        assertEquals(AssetStatus.SOLD, result.status)
        assertEquals(0, BigDecimal.ZERO.compareTo(result.lots[0].remainingQuantity))
    }

    @Test
    fun calculatesBonusSharesIssueWithZeroCostBasis() {
        val buy = createTxn(
            id = "t1",
            type = TransactionType.BUY,
            tradeDate = LocalDate.of(2024, 1, 1),
            quantity = BigDecimal("100"),
            unitPrice = BigDecimal("100"),
            netAmount = BigDecimal("10000"),
        )
        val bonus = createTxn(
            id = "t2",
            type = TransactionType.BONUS,
            tradeDate = LocalDate.of(2024, 2, 1),
            quantity = BigDecimal("100"),
            unitPrice = BigDecimal.ZERO,
            netAmount = BigDecimal.ZERO,
        )

        val afterBonus = FifoCostBasisCalculator.calculate("asset_rel", listOf(buy, bonus))
        assertEquals(0, BigDecimal("200").compareTo(afterBonus.totalQuantity))
        assertEquals(0, BigDecimal("10000.00").compareTo(afterBonus.totalInvested))
        assertEquals(0, BigDecimal("50.0000").compareTo(afterBonus.averageCost))

        // Sell 150 shares @ 80 = 12000
        // Consumes 100 from Buy (cost 10000) + 50 from Bonus (cost 0) = total cost 10000
        // Realized gain = 12000 - 10000 = 2000.00
        val sell = createTxn(
            id = "t3",
            type = TransactionType.SELL,
            tradeDate = LocalDate.of(2024, 3, 1),
            quantity = BigDecimal("150"),
            unitPrice = BigDecimal("80"),
            netAmount = BigDecimal("12000"),
        )

        val afterSell = FifoCostBasisCalculator.calculate("asset_rel", listOf(buy, bonus, sell))
        assertEquals(0, BigDecimal("50").compareTo(afterSell.totalQuantity))
        assertEquals(0, BigDecimal.ZERO.compareTo(afterSell.totalInvested))
        assertEquals(0, BigDecimal.ZERO.compareTo(afterSell.averageCost))
        assertEquals(0, BigDecimal("2000.00").compareTo(afterSell.realizedPnL))
    }

    @Test
    fun nonTradeTransactionsDoNotAlterHoldingsOrCostBasis() {
        val buy = createTxn(
            id = "t1",
            type = TransactionType.BUY,
            tradeDate = LocalDate.of(2024, 1, 1),
            quantity = BigDecimal("100"),
            unitPrice = BigDecimal("50"),
            netAmount = BigDecimal("5000"),
        )
        val dividend = createTxn(
            id = "t2",
            type = TransactionType.DIVIDEND,
            tradeDate = LocalDate.of(2024, 2, 1),
            quantity = null,
            unitPrice = null,
            netAmount = BigDecimal("400"),
        )
        val fee = createTxn(
            id = "t3",
            type = TransactionType.FEE,
            tradeDate = LocalDate.of(2024, 2, 5),
            quantity = null,
            unitPrice = null,
            netAmount = BigDecimal("20"),
        )

        val result = FifoCostBasisCalculator.calculate("asset_rel", listOf(buy, dividend, fee))
        assertEquals(0, BigDecimal("100").compareTo(result.totalQuantity))
        assertEquals(0, BigDecimal("5000.00").compareTo(result.totalInvested))
        assertEquals(0, BigDecimal("50.0000").compareTo(result.averageCost))
    }

    @Test
    fun handlesOversellGracefullyWithoutNegativeHoldings() {
        // Sell without prior buy
        val sell = createTxn(
            id = "t1",
            type = TransactionType.SELL,
            tradeDate = LocalDate.of(2024, 1, 1),
            quantity = BigDecimal("50"),
            unitPrice = BigDecimal("100"),
            netAmount = BigDecimal("5000"),
        )

        val result = FifoCostBasisCalculator.calculate("asset_rel", listOf(sell))
        assertEquals(0, BigDecimal.ZERO.compareTo(result.totalQuantity))
        assertEquals(0, BigDecimal.ZERO.compareTo(result.totalInvested))
        assertEquals(0, BigDecimal("5000.00").compareTo(result.realizedPnL))
    }

    @Test
    fun sortsTransactionsChronologicallyEvenIfDeliveredOutOfOrder() {
        val buy2 = createTxn(
            id = "t2",
            type = TransactionType.BUY,
            tradeDate = LocalDate.of(2024, 2, 1),
            quantity = BigDecimal("50"),
            unitPrice = BigDecimal("120"),
            netAmount = BigDecimal("6000"),
        )
        val sell = createTxn(
            id = "t3",
            type = TransactionType.SELL,
            tradeDate = LocalDate.of(2024, 3, 1),
            quantity = BigDecimal("60"),
            unitPrice = BigDecimal("150"),
            netAmount = BigDecimal("9000"),
        )
        val buy1 = createTxn(
            id = "t1",
            type = TransactionType.BUY,
            tradeDate = LocalDate.of(2024, 1, 1),
            quantity = BigDecimal("50"),
            unitPrice = BigDecimal("100"),
            netAmount = BigDecimal("5000"),
        )

        // Pass out of chronological order: buy2, sell, buy1
        val result = FifoCostBasisCalculator.calculate("asset_rel", listOf(buy2, sell, buy1))

        // Same result as chronological partial sell
        assertEquals(0, BigDecimal("40").compareTo(result.totalQuantity))
        assertEquals(0, BigDecimal("4800.00").compareTo(result.totalInvested))
        assertEquals(0, BigDecimal("2800.00").compareTo(result.realizedPnL))
    }
}
