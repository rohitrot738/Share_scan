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
            RankedStock(1, ScanResult("AAA", 100.0, 80.0, 1.5, -2.0)),
            RankedStock(2, ScanResult("BBB", 900.0, 70.0, 3.0, 4.0)),
            RankedStock(3, ScanResult("CCC", 50.0, 60.0, 2.0, 1.0)),
        )
        val state = ScanUiState(phase = ScanPhase.DONE, done = 3, total = 4, results = rows)
        assertEquals(75, state.percent)
        assertEquals(2, state.volumeSpikes)
        assertEquals(listOf("BBB", "CCC", "AAA"), state.copy(sort = SortMode.RVOL).sortedResults.map { it.stock.symbol })
        assertEquals(listOf("BBB", "CCC", "AAA"), state.copy(sort = SortMode.CHANGE).sortedResults.map { it.stock.symbol })
        assertEquals(listOf("BBB", "AAA", "CCC"), state.copy(sort = SortMode.PRICE).sortedResults.map { it.stock.symbol })
        assertEquals(0, ScanUiState().percent)
    }
}
