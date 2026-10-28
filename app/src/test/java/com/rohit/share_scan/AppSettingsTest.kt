package com.rohit.share_scan

import org.junit.Assert.assertEquals
import org.junit.Test

class AppSettingsTest {
    @Test
    fun unknownKeysFallBackToDefaults() {
        assertEquals(ThemeChoice.GALAXY, ThemeChoice.fromKey(null))
        assertEquals(ThemeChoice.GALAXY, ThemeChoice.fromKey("solarized"))
        assertEquals(ChartSite.TRADINGVIEW, ChartSite.fromKey(null))
        assertEquals(ChartSite.TRADINGVIEW, ChartSite.fromKey("kite"))
    }

    @Test
    fun keysRoundTrip() {
        ThemeChoice.entries.forEach { assertEquals(it, ThemeChoice.fromKey(it.key)) }
        ChartSite.entries.forEach { assertEquals(it, ChartSite.fromKey(it.key)) }
    }

    @Test
    fun onlySpaceGlassPaintsItsOwnBackground() {
        assertEquals(ThemeChoice.SPACE_GLASS, ThemeChoice.fromKey("space_glass"))
        assertEquals(listOf(BackgroundChoice.SPACE_GLASS), BackgroundChoice.entries.filter { it.ownSky })
    }

    @Test
    fun theBackgroundFollowsTheThemeUnlessOneIsPicked() {
        assertEquals(BackgroundChoice.THEME, BackgroundChoice.fromKey(null))
        assertEquals(BackgroundChoice.THEME, BackgroundChoice.fromKey("plasma"))
        BackgroundChoice.entries.forEach { assertEquals(it, BackgroundChoice.fromKey(it.key)) }
        // Every theme has a background of its own to follow.
        ThemeChoice.entries.forEach { assertEquals(it.key, BackgroundChoice.THEME.shownWith(it).key) }
        // A picked one stays whatever the theme.
        assertEquals(BackgroundChoice.AURORA, BackgroundChoice.AURORA.shownWith(ThemeChoice.SPACE_GLASS))
        assertEquals(BackgroundChoice.SPACE_GLASS, BackgroundChoice.SPACE_GLASS.shownWith(ThemeChoice.GALAXY))
    }

    @Test
    fun chartLinksPerSite() {
        assertEquals(StockFormat.chartUrl("TCS"), ChartSite.TRADINGVIEW.url("TCS"))
        assertEquals("https://chartink.com/stocks/tcs.html", ChartSite.CHARTINK.url("TCS"))
        assertEquals("https://www.google.com/finance/quote/TCS:NSE", ChartSite.GOOGLE_FINANCE.url("TCS"))
        assertEquals("https://www.screener.in/company/TCS/", ChartSite.SCREENER.url("TCS"))
    }

    @Test
    fun tradingAppsOpenThroughTheirBroker() {
        assertEquals(Broker.GROWW, ChartSite.GROWW.broker)
        assertEquals(Broker.ANGEL_ONE, ChartSite.ANGEL_ONE.broker)
        assertEquals(Broker.ZERODHA, ChartSite.ZERODHA.broker)
        assertEquals(null, ChartSite.TRADINGVIEW.broker)
        assertEquals(ChartSite.ZERODHA, ChartSite.ZERODHA.forMarket(Market.BSE))
    }

    @Test
    fun bseOnlyStocksUseBseAddresses() {
        val bse = Market.BSE
        assertEquals(StockFormat.chartUrl("MUZALI", "BSE"), ChartSite.TRADINGVIEW.url("MUZALI", bse, "539410"))
        // Chartink has no BSE pages, so it falls back to TradingView.
        assertEquals(ChartSite.TRADINGVIEW, ChartSite.CHARTINK.forMarket(bse))
        assertEquals(ChartSite.CHARTINK, ChartSite.CHARTINK.forMarket(Market.NSE_SME))
        assertEquals(StockFormat.chartUrl("MUZALI", "BSE"), ChartSite.CHARTINK.url("MUZALI", bse, "539410"))
        assertEquals("https://www.google.com/finance/quote/539410:BOM", ChartSite.GOOGLE_FINANCE.url("MUZALI", bse, "539410"))
        assertEquals("https://www.screener.in/company/539410/", ChartSite.SCREENER.url("MUZALI", bse, "539410"))
    }

    @Test
    fun symbolsWithAmpersandAreEscaped() {
        assertEquals("https://chartink.com/stocks/m%26m.html", ChartSite.CHARTINK.url("M&M"))
        assertEquals("https://www.screener.in/company/M%26M/", ChartSite.SCREENER.url("M&M"))
        assertEquals("https://www.google.com/finance/quote/BAJAJ-AUTO:NSE", ChartSite.GOOGLE_FINANCE.url("BAJAJ-AUTO"))
    }
}
