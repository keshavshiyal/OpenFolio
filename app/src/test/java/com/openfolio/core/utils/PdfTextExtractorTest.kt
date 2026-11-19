package com.openfolio.core.utils

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PdfTextExtractorTest {

    private val extractor = PdfTextExtractor()

    @Test
    fun identifiesPureLegalDisclaimerPagesAsNotRelevant() {
        val disclaimerText = """
            MUTUAL FUND INVESTMENTS ARE SUBJECT TO MARKET RISKS, READ ALL SCHEME RELATED DOCUMENTS CAREFULLY.
            Disclaimer: The information contained in this statement is provided on an 'as is' basis.
            Glossary of Terms: NAV means Net Asset Value.
            Investor Charter: Rights of investors are protected under SEBI guidelines.
            For grievance redressal please contact SCORES portal.
        """.trimIndent()

        assertFalse(extractor.isRelevantPage(disclaimerText))
    }

    @Test
    fun identifiesTransactionPagesAsRelevant() {
        val transactionPage = """
            Consolidated Account Statement
            Folio No: 12345678 / HDFC Top 100 Fund - Direct Plan - Growth
            ISIN: INF209K01157
            Date        Transaction    Amount (INR)   Units      Price (NAV)
            12-Jan-2024 Purchase       10,000.00      12.456     802.82
            Balance: 12.456 units
        """.trimIndent()

        assertTrue(extractor.isRelevantPage(transactionPage))
    }

    @Test
    fun rejectsVeryShortOrEmptyPages() {
        assertFalse(extractor.isRelevantPage(""))
        assertFalse(extractor.isRelevantPage("   \n   "))
        assertFalse(extractor.isRelevantPage("Page 12 of 50"))
    }
}
