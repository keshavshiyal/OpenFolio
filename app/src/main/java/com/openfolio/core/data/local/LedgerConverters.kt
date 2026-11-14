package com.openfolio.core.data.local

import androidx.room.TypeConverter
import com.openfolio.core.domain.model.AssetClass
import java.math.BigDecimal

class LedgerConverters {
    @TypeConverter
    fun bigDecimalToString(value: BigDecimal?): String? = value?.toPlainString()

    @TypeConverter
    fun stringToBigDecimal(value: String?): BigDecimal? = value?.let { BigDecimal(it) }

    @TypeConverter
    fun assetClassToString(value: AssetClass): String = value.name

    @TypeConverter
    fun stringToAssetClass(value: String): AssetClass = AssetClass.valueOf(value)

    @TypeConverter
    fun transactionTypeToString(value: TransactionType): String = value.name

    @TypeConverter
    fun stringToTransactionType(value: String): TransactionType = TransactionType.valueOf(value)

    @TypeConverter
    fun transactionStatusToString(value: TransactionStatus): String = value.name

    @TypeConverter
    fun stringToTransactionStatus(value: String): TransactionStatus =
        TransactionStatus.valueOf(value)
}

enum class TransactionType {
    BUY,
    SELL,
    DIVIDEND,
    INTEREST,
    FEE,
    TAX,
    DEPOSIT,
    WITHDRAWAL,
    SPLIT,
    BONUS,
    MERGER,
}

enum class TransactionStatus {
    DRAFT,
    REVIEWED,
    CONFIRMED,
}
