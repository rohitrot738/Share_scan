package com.rohit.share_scan

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BseListingTest {
    private val header = "TradDt,BizDt,Sgmt,Src,FinInstrmTp,FinInstrmId,ISIN,TckrSymb,SctySrs,XpryDt,FininstrmActlXpryDt," +
        "StrkPric,OptnTp,FinInstrmNm,OpnPric"

    private fun row(code: String, isin: String, symbol: String, group: String, name: String) =
        "2026-10-16,2026-10-16,CM,BSE,STK,$code,$isin,$symbol,$group,,,,,$name,100.00"

    @Test
    fun keepsSharesAndDropsEtfsAndDebt() {
        val csv = listOf(
            header,
            row("500002", "INE117A01022", "ABB", "A", "ABB INDIA LIMITED"),
            row("539410", "INE00GH01011", "MUZALI", "XT", "MUZALI ARTS LTD."),
            row("543592", "INF179KC1GA0", "HDFCSILVER", "E", "HDFC SILVER ETF"),
            row("590148", "INF769K01FX3", "MOSMALL250", "B", "MIRAE ASSET NIFTY SMALLCAP 250 ETF"),
            row("590000", "INF204KB14I2", "GOLDBEES", "B", "NIPPON INDIA GOLD BEES"),
            row("961234", "INE001A07ZZ1", "HDFC30", "F", "HDFC 9% 2030 NCD"),
            row("500002", "INE117A01022", "ABB", "A", "ABB INDIA LIMITED"),
        ).joinToString("\n")
        assertEquals(
            listOf(
                Listing("ABB", "Abb India Limited", Market.BSE, "INE117A01022", "500002"),
                Listing("MUZALI", "Muzali Arts Ltd.", Market.BSE, "INE00GH01011", "539410"),
            ),
            NseScanner.parseBseBhavcopy(csv),
        )
        // On holidays BSE answers with a web page instead of a file.
        assertTrue(NseScanner.parseBseBhavcopy("<!DOCTYPE html><html lang=\"en\">").isEmpty())
    }

    @Test
    fun bseOnlySkipsStocksAlreadyOnNse() {
        val nse = listOf(Listing("ABB", "ABB India Limited", Market.NSE, "INE117A01022"))
        val bse = listOf(
            Listing("ABBIND", "Abb India Limited", Market.BSE, "INE117A01022", "500002"),
            Listing("ABB", "Some Other Co", Market.BSE, "INE999Z01011", "512345"),
            Listing("MUZALI", "Muzali Arts Ltd.", Market.BSE, "INE00GH01011", "539410"),
        )
        assertEquals(listOf("MUZALI"), NseScanner.bseOnly(bse, nse).map { it.symbol })
    }

    @Test
    fun combineScansDualListedSharesOnce() {
        val main = listOf(Listing("ABB", "ABB India Limited", Market.NSE, "INE117A01022"))
        val sme = listOf(Listing("AMIABLE", "Amiable Logistics", Market.NSE_SME, "INE0RV101012"))
        val bse = listOf(
            Listing("ABB", "Abb India Limited", Market.BSE, "INE117A01022", "500002"),
            Listing("MUZALI", "Muzali Arts Ltd.", Market.BSE, "INE00GH01011", "539410"),
        )
        // NSE on: the dual-listed ABB stays NSE, BSE adds only MUZALI.
        assertEquals(
            listOf("ABB" to Market.NSE, "AMIABLE" to Market.NSE_SME, "MUZALI" to Market.BSE),
            NseScanner.combine(main, sme, bse).map { it.symbol to it.market },
        )
        // NSE off: BSE brings ABB itself.
        assertEquals(
            listOf("AMIABLE" to Market.NSE_SME, "ABB" to Market.BSE, "MUZALI" to Market.BSE),
            NseScanner.combine(emptyList(), sme, bse).map { it.symbol to it.market },
        )
    }

    @Test
    fun bseSharesTakeTheirMarketCapFromNseByIsin() {
        val nseSymbolByIsin = mapOf("INE117A01022" to "ABB")
        val bseAbb = ScanResult("ABB", 6_816.0, 60.0, 1.0, 0.0, market = Market.BSE, bseCode = "500002")
        val bseOnly = ScanResult("MUZALI", 10.0, 50.0, 1.0, 0.0, market = Market.BSE, bseCode = "539410")
        val nse = ScanResult("TCS", 3_900.0, 70.0, 1.0, 0.0)
        assertEquals("ABB", NseScanner.sharesKey(bseAbb, "INE117A01022", nseSymbolByIsin))
        assertEquals(null, NseScanner.sharesKey(bseOnly, "INE00GH01011", nseSymbolByIsin))
        assertEquals("TCS", NseScanner.sharesKey(nse, "INE467B01029", nseSymbolByIsin))
    }

    @Test
    fun yahooTickersPerMarket() {
        assertEquals(listOf("TCS.NS"), NseScanner.yahooTickers(Listing("TCS", "", Market.NSE)))
        assertEquals(listOf("AMIABLE.NS"), NseScanner.yahooTickers(Listing("AMIABLE", "", Market.NSE_SME)))
        assertEquals(
            listOf("MUZALI.BO", "539410.BO"),
            NseScanner.yahooTickers(Listing("MUZALI", "", Market.BSE, bseCode = "539410")),
        )
    }

    @Test
    fun titleCaseOnlyTouchesAllCapitals() {
        assertEquals("Aegis Logistics Ltd.", NseScanner.titleCase("AEGIS LOGISTICS LTD."))
        assertEquals("ABB India Limited", NseScanner.titleCase("ABB India Limited"))
        assertEquals("", NseScanner.titleCase(""))
    }
}
