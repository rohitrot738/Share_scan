package com.rohit.share_scan

import org.junit.Assert.assertEquals
import org.junit.Test

class AppSettingsTest {
    @Test
    fun unknownKeysFallBackToDefaults() {
        assertEquals(ThemeChoice.GALAXY, ThemeChoice.fromKey(null))
        assertEquals(ThemeChoice.GALAXY, ThemeChoice.fromKey("sunset"))
        assertEquals(ChartSite.TRADINGVIEW, ChartSite.fromKey(null))
        assertEquals(ChartSite.TRADINGVIEW, ChartSite.fromKey("kite"))
    }

    @Test
    fun keysRoundTrip() {
        ThemeChoice.entries.forEach { assertEquals(it, ThemeChoice.fromKey(it.key)) }
        ChartSite.entries.forEach { assertEquals(it, ChartSite.fromKey(it.key)) }
    }

    @Test
    fun chartLinksPerSite() {
        assertEquals(StockFormat.chartUrl("TCS"), ChartSite.TRADINGVIEW.url("TCS"))
        assertEquals("https://chartink.com/stocks/tcs.html", ChartSite.CHARTINK.url("TCS"))
        assertEquals("https://www.google.com/finance/quote/TCS:NSE", ChartSite.GOOGLE_FINANCE.url("TCS"))
        assertEquals("https://www.screener.in/company/TCS/", ChartSite.SCREENER.url("TCS"))
    }

    @Test
    fun symbolsWithAmpersandAreEscaped() {
        assertEquals("https://chartink.com/stocks/m%26m.html", ChartSite.CHARTINK.url("M&M"))
        assertEquals("https://www.screener.in/company/M%26M/", ChartSite.SCREENER.url("M&M"))
        assertEquals("https://www.google.com/finance/quote/BAJAJ-AUTO:NSE", ChartSite.GOOGLE_FINANCE.url("BAJAJ-AUTO"))
    }
}
