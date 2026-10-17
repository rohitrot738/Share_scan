package com.rohit.share_scan

import org.junit.Assert.assertEquals
import org.junit.Test

class StockFormatTest {
    @Test
    fun tiersFollowScoreThresholds() {
        assertEquals(StockFormat.Tier.STRONG, StockFormat.tier(65.0))
        assertEquals(StockFormat.Tier.WATCH, StockFormat.tier(64.9))
        assertEquals(StockFormat.Tier.WATCH, StockFormat.tier(52.0))
        assertEquals(StockFormat.Tier.WEAK, StockFormat.tier(51.9))
    }

    @Test
    fun numbersAreFormattedForDisplay() {
        assertEquals("2,845.30", StockFormat.price(2845.3))
        assertEquals("12.50", StockFormat.price(12.5))
        assertEquals("82.4", StockFormat.score(82.44))
        assertEquals("2.31", StockFormat.rvol(2.314))
    }

    @Test
    fun changeShowsDirection() {
        assertEquals("▲ 3.42%", StockFormat.change(3.42))
        assertEquals("▼ 1.50%", StockFormat.change(-1.5))
    }

    @Test
    fun durationIsMinutesAndSeconds() {
        assertEquals("04:05", StockFormat.duration(245_000))
        assertEquals("00:00", StockFormat.duration(-5))
    }

    @Test
    fun chartUrlUsesTradingViewSymbols() {
        assertEquals("https://www.tradingview.com/chart/?symbol=NSE:M_M", StockFormat.chartUrl("M&M"))
        assertEquals("https://www.tradingview.com/chart/?symbol=NSE:BAJAJ_AUTO", StockFormat.chartUrl("BAJAJ-AUTO"))
    }

    @Test
    fun stateSortsAndCountsResults() {
        val rows = listOf(
            ScanResult("AAA", 100.0, 80.0, 1.5, -2.0),
            ScanResult("BBB", 900.0, 70.0, 3.0, 4.0),
            ScanResult("CCC", 50.0, 60.0, 2.0, 1.0),
        )
        val state = ScanUiState(phase = ScanPhase.DONE, done = 3, total = 4, results = rows)
        assertEquals(75, state.percent)
        assertEquals(2, state.volumeSpikes)
        assertEquals(listOf(1, 2, 3), state.visible.map { it.rank })
        assertEquals(listOf("BBB", "CCC", "AAA"), state.copy(sort = SortMode.RVOL).sortedResults.map { it.stock.symbol })
        assertEquals(listOf("BBB", "CCC", "AAA"), state.copy(sort = SortMode.CHANGE).sortedResults.map { it.stock.symbol })
        assertEquals(listOf("BBB", "AAA", "CCC"), state.copy(sort = SortMode.PRICE).sortedResults.map { it.stock.symbol })
        assertEquals(0, ScanUiState().percent)
    }

    @Test
    fun compactIndianUnits() {
        assertEquals("₹0.5 Cr", StockFormat.crore(0.5))
        assertEquals("₹850 Cr", StockFormat.crore(850.0))
        assertEquals("₹12,500 Cr", StockFormat.crore(12_500.0))
        assertEquals("₹1.2 लाख Cr", StockFormat.crore(123_456.0))
        assertEquals("850", StockFormat.shares(850.0))
        assertEquals("12.5K", StockFormat.shares(12_500.0))
        assertEquals("4.2 L", StockFormat.shares(420_000.0))
        assertEquals("1.3 Cr", StockFormat.shares(13_000_000.0))
    }

    @Test
    fun rangeLabels() {
        assertEquals("सभी", StockFormat.range(Metric.MARKET_CAP, Bound(), "सभी"))
        assertEquals("≥ ₹500 Cr", StockFormat.range(Metric.MARKET_CAP, Bound(min = 500.0), "सभी"))
        assertEquals("≤ 3×", StockFormat.range(Metric.RVOL, Bound(max = 3.0), "सभी"))
        assertEquals("-5% – +10%", StockFormat.range(Metric.CHANGE, Bound(-5.0, 10.0), "सभी"))
        assertEquals("₹50 – ₹1,000", StockFormat.range(Metric.PRICE, Bound(50.0, 1_000.0), "सभी"))
    }
}
