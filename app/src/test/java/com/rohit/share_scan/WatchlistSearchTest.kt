package com.rohit.share_scan

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WatchlistSearchTest {
    // Best score first, as the view model stores them.
    private val results = listOf(
        ScanResult("TECHM", 1_790.0, 88.8, 8.05, 3.0),
        ScanResult("TCS", 3_900.0, 80.0, 1.2, 1.0),
        ScanResult("M&M", 2_900.0, 70.0, 2.4, -1.0),
        ScanResult("TATAPOWER", 410.0, 60.0, 3.1, 5.0),
        ScanResult("INFY", 1_500.0, 50.0, 0.9, 2.0),
    )

    private fun ranks(list: List<RankedStock>) = list.map { it.stock.symbol to it.rank }

    @Test
    fun defaultsLeaveTheListAsTheFilteredView() {
        val state = ScanUiState(results = results, filters = ScanFilters(limit = 0))
        assertEquals(state.visible, state.sortedResults)
        assertEquals(5, state.visible.size)
        assertFalse(state.visible.any { it.starred || it.badge != null })
    }

    @Test
    fun searchIsCaseInsensitiveAndKeepsRanks() {
        val state = ScanUiState(results = results, filters = ScanFilters(limit = 0), query = " ta ")
        assertEquals(listOf("TATAPOWER" to 4), ranks(state.sortedResults))
        assertEquals(5, state.visible.size)

        assertEquals(listOf("TECHM" to 1, "TCS" to 2), ranks(state.copy(query = "c").sortedResults))
        assertEquals(listOf("M&M" to 3), ranks(state.copy(query = "m&").sortedResults))
        assertTrue(state.copy(query = "zzz").sortedResults.isEmpty())
    }

    @Test
    fun searchLooksPastTheTopLimit() {
        val state = ScanUiState(results = results, filters = ScanFilters(limit = 2), query = "infy")
        assertEquals(listOf("TECHM" to 1, "TCS" to 2), ranks(state.visible))
        assertEquals(listOf("INFY" to 5), ranks(state.sortedResults))
        // Filters still apply: INFY is under the price floor here.
        val priced = state.copy(filters = ScanFilters(limit = 2).with(Metric.PRICE, Bound(min = 2_000.0)))
        assertTrue(priced.sortedResults.isEmpty())
    }

    @Test
    fun watchlistShowsEveryStarredStock() {
        val filters = ScanFilters(limit = 2).with(Metric.PRICE, Bound(min = 1_000.0))
        val gone = ScanResult("GONE", 250.0, 65.0, 1.5, 4.0, market = Market.BSE)
        val state = ScanUiState(
            results = results,
            filters = filters,
            watchlist = setOf("INFY", "TATAPOWER", "GONE", "NODATA"),
            watchlistOnly = true,
            watchExtras = listOf(gone),
        )
        // INFY is below the limit of 2 but keeps its real rank. TATAPOWER fails the price filter and GONE is not in
        // this scan, yet both show, without a rank. NODATA was never charted: it shows last, by its symbol alone.
        assertEquals(listOf("TECHM" to 1, "TCS" to 2), ranks(state.visible))
        val none = RankedStock.UNRANKED
        assertEquals(
            listOf("GONE" to none, "TATAPOWER" to none, "INFY" to 4, "NODATA" to none),
            ranks(state.sortedResults),
        )
        assertTrue(state.sortedResults.all { it.starred })
        assertEquals(listOf(true, true, true, false), state.sortedResults.map { it.stock.charted })
        assertEquals(listOf("GONE" to none), ranks(state.copy(query = "gone").sortedResults))
        assertTrue(state.copy(query = "tcs").sortedResults.isEmpty())
        assertTrue(state.copy(watchlist = emptySet()).listed.isEmpty())
        // Unstarred, a stock outside the scan leaves the watchlist; the main list never shows one.
        assertEquals(listOf("INFY" to 4), ranks(state.copy(watchlist = setOf("INFY")).sortedResults))
        val main = state.copy(watchlistOnly = false).sortedResults.map { it.stock.symbol }
        assertTrue(main.none { it == "GONE" || it == "NODATA" || it == "TATAPOWER" })
    }

    @Test
    fun sortingAppliesToTheSearchedList() {
        val state = ScanUiState(results = results, filters = ScanFilters(limit = 0), query = "t", sort = SortMode.RVOL)
        assertEquals(
            listOf("TECHM" to 1, "TATAPOWER" to 4, "TCS" to 2),
            ranks(state.sortedResults),
        )
    }

    @Test
    fun everySortRunsEitherWay() {
        val state = ScanUiState(results = results, filters = ScanFilters(limit = 0))
        fun order(sort: SortMode, ascending: Boolean) =
            state.copy(sort = sort, ascending = ascending).sortedResults.map { it.stock.symbol }

        assertEquals(listOf("TECHM", "TCS", "M&M", "TATAPOWER", "INFY"), order(SortMode.SCORE, false))
        assertEquals(listOf("INFY", "TATAPOWER", "M&M", "TCS", "TECHM"), order(SortMode.SCORE, true))
        assertEquals(listOf("TECHM", "TATAPOWER", "M&M", "TCS", "INFY"), order(SortMode.RVOL, false))
        assertEquals(listOf("INFY", "TCS", "M&M", "TATAPOWER", "TECHM"), order(SortMode.RVOL, true))
        assertEquals(listOf("M&M", "TCS", "INFY", "TECHM", "TATAPOWER"), order(SortMode.CHANGE, true))
        assertEquals(listOf("TATAPOWER", "INFY", "TECHM", "M&M", "TCS"), order(SortMode.PRICE, true))
        // Ranks stay the score's, whichever way the list runs.
        assertEquals(5, state.copy(ascending = true).sortedResults.first().rank)
    }

    @Test
    fun badgesAndStarsReachTheRankedStocks() {
        val state = ScanUiState(
            results = results,
            rankBadges = mapOf("TCS" to RankBadge.Moved(3), "INFY" to RankBadge.New),
            watchlist = setOf("M&M"),
        )
        val bySymbol = state.visible.associateBy { it.stock.symbol }
        assertEquals(RankBadge.Moved(3), bySymbol.getValue("TCS").badge)
        assertEquals(RankBadge.New, bySymbol.getValue("INFY").badge)
        assertEquals(null, bySymbol.getValue("TECHM").badge)
        assertTrue(bySymbol.getValue("M&M").starred)
        assertFalse(bySymbol.getValue("TCS").starred)
    }
}
