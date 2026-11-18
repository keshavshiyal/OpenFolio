package com.openfolio.core.utils

import com.openfolio.core.data.local.entity.AssetClass
import com.openfolio.core.data.local.entity.AssetEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class AssetNameNormalizerTest {

    @Test
    fun stripsCorporateSuffixesAccurately() {
        assertEquals("reliance", AssetNameNormalizer.normalize("Reliance"))
        assertEquals("reliance", AssetNameNormalizer.normalize("Reliance Ltd"))
        assertEquals("reliance", AssetNameNormalizer.normalize("Reliance Ltd."))
        assertEquals("reliance", AssetNameNormalizer.normalize("Reliance Limited"))
        assertEquals("reliance", AssetNameNormalizer.normalize("Reliance Industries"))
        assertEquals("reliance", AssetNameNormalizer.normalize("Reliance Industries Ltd"))
        assertEquals("reliance", AssetNameNormalizer.normalize("Reliance Industries Limited"))
        assertEquals("reliance", AssetNameNormalizer.normalize("RELIANCE INDUSTRIES LTD."))
        assertEquals("reliance", AssetNameNormalizer.normalize("Reliance Corp"))
        assertEquals("reliance", AssetNameNormalizer.normalize("Reliance Corporation"))
        assertEquals("reliance", AssetNameNormalizer.normalize("Reliance Inc."))
        assertEquals("reliance", AssetNameNormalizer.normalize("Reliance Pvt. Ltd."))
        assertEquals("reliance", AssetNameNormalizer.normalize("Reliance Co."))
    }

    @Test
    fun removesPunctuationAndExtraSpaces() {
        assertEquals("tata_motors", AssetNameNormalizer.normalize("Tata   Motors,  Ltd."))
        assertEquals("tata_motors", AssetNameNormalizer.normalize("Tata - Motors Limited"))
        assertEquals("hdfc_bank", AssetNameNormalizer.normalize("HDFC   Bank  (Holdings)  Ltd."))
        assertEquals("l_and_t", AssetNameNormalizer.normalize("L&T Ltd"))
        assertEquals("l_and_t", AssetNameNormalizer.normalize("L & T Limited"))
    }

    @Test
    fun preservesSingleTokenWhenItIsSuffix() {
        // Does not strip down to empty if the name itself is just the word
        assertEquals("limited", AssetNameNormalizer.normalize("Limited"))
        assertEquals("industries", AssetNameNormalizer.normalize("Industries"))
        assertEquals("corp", AssetNameNormalizer.normalize("Corp"))
    }

    @Test
    fun matchesExistingAssetWithNamingVariations() {
        val relianceAsset = AssetEntity(
            id = "reliance",
            displayName = "Reliance",
            assetClass = AssetClass.STOCK,
        )
        val hdfcAsset = AssetEntity(
            id = "hdfc_bank",
            displayName = "HDFC Bank",
            assetClass = AssetClass.STOCK,
        )
        val assets = listOf(relianceAsset, hdfcAsset)

        // Matching "Reliance Ltd" should resolve to the existing "reliance" entity
        val match1 = AssetNameNormalizer.findMatchingAsset("Reliance Ltd", assets)
        assertNotNull(match1)
        assertEquals("reliance", match1?.id)

        // Matching "Reliance Industries" should resolve to "reliance"
        val match2 = AssetNameNormalizer.findMatchingAsset("Reliance Industries Limited", assets)
        assertNotNull(match2)
        assertEquals("reliance", match2?.id)

        // Matching "HDFC Bank Ltd." should resolve to "hdfc_bank"
        val match3 = AssetNameNormalizer.findMatchingAsset("HDFC Bank Ltd.", assets)
        assertNotNull(match3)
        assertEquals("hdfc_bank", match3?.id)
    }

    @Test
    fun doesNotFalseMatchDifferentCompanies() {
        val tataMotors = AssetEntity(
            id = "tata_motors",
            displayName = "Tata Motors",
            assetClass = AssetClass.STOCK,
        )
        val assets = listOf(tataMotors)

        val match = AssetNameNormalizer.findMatchingAsset("Tata Steel Ltd", assets)
        assertNull(match)
    }

    @Test
    fun matchesWithMinorTypos() {
        val appleAsset = AssetEntity(
            id = "apple",
            displayName = "Apple Inc",
            assetClass = AssetClass.STOCK,
        )
        val assets = listOf(appleAsset)

        // Minor typo "Aple" or "Applee"
        val match = AssetNameNormalizer.findMatchingAsset("Applee Inc", assets)
        assertNotNull(match)
        assertEquals("apple", match?.id)
    }

    @Test
    fun matchesByIsinAndIdentifier() {
        val infyAsset = AssetEntity(
            id = "infy",
            displayName = "Infosys Limited",
            assetClass = AssetClass.STOCK,
            identifiersJson = """{"isin":"INE009A01021","ticker":"INFY"}""",
        )
        val assets = listOf(infyAsset)

        // Matching by raw text containing ISIN
        val match1 = AssetNameNormalizer.findMatchingAsset("INFOSYS - INE009A01021", assets)
        assertNotNull(match1)
        assertEquals("infy", match1?.id)

        // Matching by identifier argument
        val match2 = AssetNameNormalizer.findMatchingAsset("Infosys", assets, identifier = "INE009A01021")
        assertNotNull(match2)
        assertEquals("infy", match2?.id)

        // Matching by ticker
        val match3 = AssetNameNormalizer.findMatchingAsset("Unknown Description", assets, identifier = "INFY")
        assertNotNull(match3)
        assertEquals("infy", match3?.id)
    }

    @Test
    fun prioritizesIsinOverConflictingName() {
        val relAsset = AssetEntity(
            id = "rel",
            displayName = "Reliance Industries",
            assetClass = AssetClass.STOCK,
            identifiersJson = """{"isin":"INE002A01018","ticker":"RELIANCE"}""",
        )
        val tcsAsset = AssetEntity(
            id = "tcs",
            displayName = "Tata Consultancy Services",
            assetClass = AssetClass.STOCK,
            identifiersJson = """{"isin":"INE467B01029","ticker":"TCS"}""",
        )
        val assets = listOf(relAsset, tcsAsset)

        // Incoming name says "Reliance" but ISIN belongs to TCS -> ISIN takes absolute priority
        val match = AssetNameNormalizer.findMatchingAsset("Reliance Industries", assets, isin = "INE467B01029")
        assertNotNull(match)
        assertEquals("tcs", match?.id)
    }

    @Test
    fun bypassesFuzzyMatchingWhenExplicitIdentifierMismatchesExistingAssets() {
        val relianceAsset = AssetEntity(
            id = "rel",
            displayName = "Reliance Industries",
            assetClass = AssetClass.STOCK,
            identifiersJson = """{"isin":"INE002A01018","ticker":"RELIANCE"}""",
        )
        val assets = listOf(relianceAsset)

        // Incoming has same name "Reliance Industries" but explicitly different ISIN
        // Should NOT match existing asset, preventing duplicate asset collision
        val match = AssetNameNormalizer.findMatchingAsset("Reliance Industries", assets, isin = "INE999999999")
        assertNull(match)
    }

    @Test
    fun buildIdentifiersJsonMergesFieldsCorrectly() {
        val json1 = AssetNameNormalizer.buildIdentifiersJson(null, isin = "INE002A01018", ticker = "RELIANCE")
        assertNotNull(json1)
        org.junit.Assert.assertTrue(json1!!.contains("\"isin\":\"INE002A01018\""))
        org.junit.Assert.assertTrue(json1.contains("\"ticker\":\"RELIANCE\""))

        // Updating with new ticker preserves isin
        val json2 = AssetNameNormalizer.buildIdentifiersJson(json1, isin = null, ticker = "RIL")
        assertNotNull(json2)
        org.junit.Assert.assertTrue(json2!!.contains("\"isin\":\"INE002A01018\""))
        org.junit.Assert.assertTrue(json2.contains("\"ticker\":\"RIL\""))
    }
}
