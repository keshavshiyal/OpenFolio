package com.openfolio.core.data.local

import com.openfolio.core.data.local.entity.AccountType
import com.openfolio.core.data.local.entity.AssetClass
import com.openfolio.core.data.local.entity.AssetStatus
import com.openfolio.core.data.local.entity.ExtractionStatus
import com.openfolio.core.data.local.entity.TransactionStatus
import com.openfolio.core.data.local.entity.TransactionType
import java.math.BigDecimal
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ConvertersTest {
    private val converters = Converters()

    @Test
    fun bigDecimalConversions() {
        val original = BigDecimal("1234567.8901")
        val stringVal = converters.fromBigDecimal(original)
        assertEquals("1234567.8901", stringVal)
        val parsed = converters.toBigDecimal(stringVal)
        assertEquals(original, parsed)

        assertNull(converters.fromBigDecimal(null))
        assertNull(converters.toBigDecimal(null))
    }

    @Test
    fun localDateConversions() {
        val original = LocalDate.of(2026, 11, 15)
        val epochDay = converters.fromLocalDate(original)
        val parsed = converters.toLocalDate(epochDay)
        assertEquals(original, parsed)

        assertNull(converters.fromLocalDate(null))
        assertNull(converters.toLocalDate(null))
    }

    @Test
    fun enumConversions() {
        assertEquals("BROKER", converters.fromAccountType(AccountType.BROKER))
        assertEquals(AccountType.BROKER, converters.toAccountType("BROKER"))

        assertEquals("MUTUAL_FUND", converters.fromAssetClass(AssetClass.MUTUAL_FUND))
        assertEquals(AssetClass.MUTUAL_FUND, converters.toAssetClass("MUTUAL_FUND"))

        assertEquals("ACTIVE", converters.fromAssetStatus(AssetStatus.ACTIVE))
        assertEquals(AssetStatus.ACTIVE, converters.toAssetStatus("ACTIVE"))

        assertEquals("BUY", converters.fromTransactionType(TransactionType.BUY))
        assertEquals(TransactionType.BUY, converters.toTransactionType("BUY"))

        assertEquals("CONFIRMED", converters.fromTransactionStatus(TransactionStatus.CONFIRMED))
        assertEquals(TransactionStatus.CONFIRMED, converters.toTransactionStatus("CONFIRMED"))

        assertEquals("OCR_COMPLETE", converters.fromExtractionStatus(ExtractionStatus.OCR_COMPLETE))
        assertEquals(ExtractionStatus.OCR_COMPLETE, converters.toExtractionStatus("OCR_COMPLETE"))
        assertEquals(ExtractionStatus.PROCESSING, converters.toExtractionStatus("PROCESSING"))
        assertEquals(ExtractionStatus.FAILED, converters.toExtractionStatus("FAILED"))
        assertEquals(ExtractionStatus.READY_FOR_MAPPING, converters.toExtractionStatus("READY_FOR_MAPPING"))
        assertEquals(ExtractionStatus.PENDING_OCR, converters.toExtractionStatus("UNKNOWN_LEGACY_STATUS"))
    }

    @Test
    fun genericEnumHelpers() {
        assertEquals("STOCK", Converters.enumToString(AssetClass.STOCK))
        assertEquals(AssetClass.STOCK, Converters.stringToEnum<AssetClass>("STOCK"))
        assertNull(Converters.enumToString<AssetClass>(null))
        assertNull(Converters.stringToEnum<AssetClass>(null))
    }
}
