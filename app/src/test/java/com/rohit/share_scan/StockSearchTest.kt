package com.rohit.share_scan

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StockSearchTest {
    private fun stock(symbol: String, name: String) = ScanResult(symbol, 100.0, 50.0, 1.0, 0.0, name = name)

    private val tcs = stock("TCS", "Tata Consultancy Services Limited")
    private val bajaj = stock("BAJAJ-AUTO", "Bajaj Auto Limited")
    private val mm = stock("M&M", "Mahindra & Mahindra Limited")

    @Test
    fun matchesSymbolOrName() {
        assertTrue(StockSearch.matches(tcs, "tcs"))
        assertTrue(StockSearch.matches(tcs, "  consultancy "))
        assertTrue(StockSearch.matches(tcs, "TATA CON"))
        assertTrue(StockSearch.matches(mm, "mahindra"))
        assertTrue(StockSearch.matches(mm, "m&m"))
        assertFalse(StockSearch.matches(tcs, "infosys"))
        assertTrue(StockSearch.matches(tcs, ""))
    }

    @Test
    fun ignoresSpacesAndPunctuationForLongerQueries() {
        assertTrue(StockSearch.matches(bajaj, "bajajauto"))
        assertTrue(StockSearch.matches(bajaj, "bajaj auto"))
        assertTrue(StockSearch.matches(tcs, "tataconsul"))
        // Two letters are too few to match across words: "ta" would otherwise hit nearly every name.
        assertFalse(StockSearch.matches(stock("INFY", "Infosys Limited"), "y l"))
    }

    @Test
    fun shortNameDropsTheCompanySuffix() {
        assertEquals("Tata Consultancy Services", StockSearch.shortName("Tata Consultancy Services Limited"))
        assertEquals("Hind Rectifiers", StockSearch.shortName("Hind Rectifiers Ltd."))
        assertEquals("Ltd Group", StockSearch.shortName("Ltd Group"))
        assertEquals("", StockSearch.shortName(""))
    }

    @Test
    fun parsesListingsWithNames() {
        val csv = Char(0xFEFF) + "SYMBOL,NAME OF COMPANY, SERIES, DATE OF LISTING\n" +
            "20MICRONS,20 Microns Limited,EQ,06-OCT-2008\n" +
            "M&M,\"Mahindra & Mahindra, Limited\",EQ,01-JAN-1990\n" +
            "bad symbol,Broken,EQ,01-JAN-2000\n" +
            "20MICRONS,Duplicate,EQ,06-OCT-2008\n\n"
        assertEquals(
            listOf(
                Listing("20MICRONS", "20 Microns Limited", sme = true),
                Listing("M&M", "Mahindra & Mahindra, Limited", sme = true),
            ),
            NseScanner.parseListings(csv, sme = true),
        )
        assertTrue(NseScanner.parseListings("", sme = false).isEmpty())

        // The SME file names its columns with underscores and a trailing comma.
        val smeCsv = "SYMBOL,NAME_OF_COMPANY,SERIES,DATE_OF_LISTING,\nRKFAL,R.K. Fashion Accessories Limited,ST,12-Oct-26,\n"
        assertEquals(
            listOf(Listing("RKFAL", "R.K. Fashion Accessories Limited", sme = true)),
            NseScanner.parseListings(smeCsv, sme = true),
        )
    }
}
