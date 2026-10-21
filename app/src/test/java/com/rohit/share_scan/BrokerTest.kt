package com.rohit.share_scan

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BrokerTest {
    private fun stock(symbol: String, market: Market = Market.NSE, bseCode: String = "", name: String = "") =
        ScanResult(symbol = symbol, price = 1.0, score = 0.0, rvol = 0.0, change5d = 0.0, name = name, market = market, bseCode = bseCode)

    /** Groww's search answer, cut down to the fields read: Reliance Power comes up for "RELIANCE" too. */
    private val answer = """
        {"content":[
          {"entity_type":"Stocks","nse_scrip_code":"RELIANCEPW","bse_scrip_code":"532939","id":"reliance-power-ltd","search_id":"reliance-power-ltd"},
          {"entity_type":"Mutual Funds","nse_scrip_code":"RELIANCE","id":"some-fund"},
          {"entity_type":"Stocks","nse_scrip_code":"RELIANCE","bse_scrip_code":"500325","id":"reliance-industries-ltd","search_id":"reliance-industries-ltd"},
          {"entity_type":"Stocks","nse_scrip_code":null,"bse_scrip_code":"539410","id":"muzali-arts-ltd","search_id":""}
        ]}
    """.trimIndent()

    @Test
    fun growwSearchFindsTheStockByItsSymbolOrBseCode() {
        assertEquals("reliance-industries-ltd", Broker.growwPage(answer, stock("RELIANCE")))
        assertEquals("reliance-power-ltd", Broker.growwPage(answer, stock("RELIANCEPW", Market.NSE_SME)))
        // BSE-only stocks go by their code; a missing search_id falls back to the id.
        assertEquals("muzali-arts-ltd", Broker.growwPage(answer, stock("MUZALI", Market.BSE, "539410")))
        assertNull(Broker.growwPage(answer, stock("TCS")))
        assertNull(Broker.growwPage(answer, stock("MUZALI", Market.BSE)))
        assertNull(Broker.growwPage("<html>blocked</html>", stock("RELIANCE")))
        assertNull(Broker.growwPage("{}", stock("RELIANCE")))
    }

    @Test
    fun growwIsSearchedBySymbolOrBseCode() {
        assertEquals(
            "https://groww.in/v1/api/search/v1/entity?app=false&page=0&size=10&q=M%26M",
            Broker.growwSearchUrl(stock("M&M")),
        )
        assertEquals(
            "https://groww.in/v1/api/search/v1/entity?app=false&page=0&size=10&q=539410",
            Broker.growwSearchUrl(stock("MUZALI", Market.BSE, "539410")),
        )
        assertEquals("https://groww.in/stocks/reliance-industries-ltd", Broker.growwUrl("reliance-industries-ltd"))
    }

    @Test
    fun growwPageIsGuessedFromTheCompanyName() {
        assertEquals("reliance-industries-ltd", Broker.growwGuess("Reliance Industries Limited"))
        assertEquals("mahindra-mahindra-ltd", Broker.growwGuess("Mahindra & Mahindra Limited"))
        assertEquals("dr-reddys-laboratories-ltd", Broker.growwGuess("Dr. Reddy's Laboratories Limited"))
        assertEquals("info-edge-india-ltd", Broker.growwGuess("Info Edge (India) Limited"))
        assertEquals("aegis-logistics-ltd", Broker.growwGuess("Aegis Logistics Ltd."))
        assertEquals("ganesh-housing-ltd", Broker.growwGuess("GANESH HOUSING LIMITED"))
        assertNull(Broker.growwGuess(""))
        assertNull(Broker.growwGuess(" ... "))
    }
}
