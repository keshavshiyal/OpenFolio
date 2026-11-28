package com.openfolio.core.data.local

import androidx.room.TypeConverter
import com.openfolio.core.data.local.entity.AccountType
import com.openfolio.core.data.local.entity.AssetClass
import com.openfolio.core.data.local.entity.AssetStatus
import com.openfolio.core.data.local.entity.ExtractionStatus
import com.openfolio.core.data.local.entity.TransactionStatus
import com.openfolio.core.data.local.entity.TransactionType
import java.math.BigDecimal
import java.time.LocalDate

class Converters {
    @TypeConverter
    fun fromBigDecimal(value: BigDecimal?): String? = value?.toPlainString()

    @TypeConverter
    fun toBigDecimal(value: String?): BigDecimal? = value?.let { raw ->
        runCatching { BigDecimal(raw.trim()) }.getOrDefault(BigDecimal.ZERO)
    }

    @TypeConverter
    fun fromLocalDate(value: LocalDate?): Long? = value?.toEpochDay()

    @TypeConverter
    fun toLocalDate(value: Long?): LocalDate? = value?.let { LocalDate.ofEpochDay(it) }

    @TypeConverter
    fun fromAccountType(value: AccountType?): String? = value?.name

    @TypeConverter
    fun toAccountType(value: String?): AccountType? = value?.let { raw ->
        runCatching { AccountType.valueOf(raw.trim().uppercase()) }.getOrDefault(AccountType.MANUAL)
    }

    @TypeConverter
    fun fromAssetClass(value: AssetClass?): String? = value?.name

    @TypeConverter
    fun toAssetClass(value: String?): AssetClass? = value?.let { raw ->
        runCatching { AssetClass.valueOf(raw.trim().uppercase()) }.getOrDefault(AssetClass.OTHER)
    }

    @TypeConverter
    fun fromAssetStatus(value: AssetStatus?): String? = value?.name

    @TypeConverter
    fun toAssetStatus(value: String?): AssetStatus? = value?.let { raw ->
        runCatching { AssetStatus.valueOf(raw.trim().uppercase()) }.getOrDefault(AssetStatus.ACTIVE)
    }

    @TypeConverter
    fun fromTransactionType(value: TransactionType?): String? = value?.name

    @TypeConverter
    fun toTransactionType(value: String?): TransactionType? = value?.let { raw ->
        runCatching { TransactionType.valueOf(raw.trim().uppercase()) }.getOrDefault(TransactionType.OTHER)
    }

    @TypeConverter
    fun fromTransactionStatus(value: TransactionStatus?): String? = value?.name

    @TypeConverter
    fun toTransactionStatus(value: String?): TransactionStatus? = value?.let { raw ->
        runCatching { TransactionStatus.valueOf(raw.trim().uppercase()) }.getOrDefault(TransactionStatus.CONFIRMED)
    }

    @TypeConverter
    fun fromExtractionStatus(value: ExtractionStatus?): String? = value?.name

    @TypeConverter
    fun toExtractionStatus(value: String?): ExtractionStatus? = value?.let { raw ->
        runCatching { ExtractionStatus.valueOf(raw.trim().uppercase()) }.getOrDefault(ExtractionStatus.PENDING_OCR)
    }

    companion object {
        inline fun <reified T : Enum<T>> enumToString(value: T?): String? = value?.name

        inline fun <reified T : Enum<T>> stringToEnum(value: String?): T? =
            value?.let { raw ->
                runCatching { enumValueOf<T>(raw.trim().uppercase()) }.getOrNull()
            }
    }
}
