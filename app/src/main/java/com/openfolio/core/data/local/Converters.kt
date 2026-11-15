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
    fun toBigDecimal(value: String?): BigDecimal? = value?.let { BigDecimal(it) }

    @TypeConverter
    fun fromLocalDate(value: LocalDate?): Long? = value?.toEpochDay()

    @TypeConverter
    fun toLocalDate(value: Long?): LocalDate? = value?.let { LocalDate.ofEpochDay(it) }

    @TypeConverter
    fun fromAccountType(value: AccountType?): String? = value?.name

    @TypeConverter
    fun toAccountType(value: String?): AccountType? = value?.let { AccountType.valueOf(it) }

    @TypeConverter
    fun fromAssetClass(value: AssetClass?): String? = value?.name

    @TypeConverter
    fun toAssetClass(value: String?): AssetClass? = value?.let { AssetClass.valueOf(it) }

    @TypeConverter
    fun fromAssetStatus(value: AssetStatus?): String? = value?.name

    @TypeConverter
    fun toAssetStatus(value: String?): AssetStatus? = value?.let { AssetStatus.valueOf(it) }

    @TypeConverter
    fun fromTransactionType(value: TransactionType?): String? = value?.name

    @TypeConverter
    fun toTransactionType(value: String?): TransactionType? = value?.let { TransactionType.valueOf(it) }

    @TypeConverter
    fun fromTransactionStatus(value: TransactionStatus?): String? = value?.name

    @TypeConverter
    fun toTransactionStatus(value: String?): TransactionStatus? = value?.let { TransactionStatus.valueOf(it) }

    @TypeConverter
    fun fromExtractionStatus(value: ExtractionStatus?): String? = value?.name

    @TypeConverter
    fun toExtractionStatus(value: String?): ExtractionStatus? = value?.let { ExtractionStatus.valueOf(it) }

    companion object {
        inline fun <reified T : Enum<T>> enumToString(value: T?): String? = value?.name

        inline fun <reified T : Enum<T>> stringToEnum(value: String?): T? =
            value?.let { enumValueOf<T>(it) }
    }
}
