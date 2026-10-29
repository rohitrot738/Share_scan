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
    fun aProductShowsTheCompaniesMakingItPastTheTopLimit() {
        val book = Cr360Book.parse(
            """{"v":1,"stocks":{"TECHM":{"st":"NEUTRAL","pr":["software"]},"INFY":{"st":"NEUTRAL","pr":["software","internet"]},
               "TATAPOWER":{"st":"NEUTRAL","pr":["power","solar"]}}}""",
        )
        val state = ScanUiState(results = results, filters = ScanFilters(limit = 2), cr360 = book, product = "software")
        assertEquals(listOf("TECHM" to 1, "INFY" to 5), ranks(state.sortedResults))
        assertEquals(listOf("TATAPOWER" to 4), ranks(state.copy(product = "solar").sortedResults))
        assertTrue(state.copy(product = "tyre").sortedResults.isEmpty())
        // With the search too, both must match.
        assertEquals(listOf("INFY" to 5), ranks(state.copy(query = "inf").sortedResults))
        assertEquals(listOf("TECHM" to 1, "TCS" to 2), ranks(state.copy(product = null).sortedResults))
        // The file the app carries comes first; the daily file only fills in a company it does not have.
        val carried = ProductBook.parse("""{"v":1,"stocks":{"TECHM":["telecom"],"TCS":["software"]}}""")
        assertEquals(listOf("TCS" to 2, "INFY" to 5), ranks(state.copy(products = carried).sortedResults))
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
    fun theSupportScanListsStocksOnALongFloor() {
        val long = SupportFloor(level = 380.0, ageSessions = 120, touches = 4, unbroken = true, window = 124)
        val young = long.copy(ageSessions = 30)
        val broken = long.copy(unbroken = false)
        val once = long.copy(touches = 2)
        val floors = mapOf("TATAPOWER" to long, "TCS" to young, "M&M" to broken, "INFY" to long, "TECHM" to once)
        val stocks = results.map { it.copy(support = floors[it.symbol]) }
        // The Breakout scan's filters and limit do not apply to the Support scan.
        val breakout = ScanUiState(
            results = stocks,
            filters = ScanFilters(limit = 2).with(Metric.PRICE, Bound(min = 1_000.0)),
            watchlist = setOf("INFY"),
            watchlistOnly = true,
        )
        val state = breakout.withScanMode(ScanMode.SUPPORT)
        assertFalse(state.watchlistOnly)
        assertEquals(SortMode.SUPPORT, state.sort)
        // Strongest floor first; equal floors go nearest first (TATAPOWER is 8% above 380, INFY far above).
        // Ranks are by score among all stocks; a young, broken or once-tested floor does not count.
        assertEquals(listOf("TATAPOWER" to 4, "INFY" to 5), ranks(state.sortedResults))
        assertEquals(2, state.supportStocks.size)
        assertEquals(listOf("TATAPOWER" to 4), ranks(state.copy(supportReach = 10.0).sortedResults))
        assertEquals(listOf("INFY" to 5), ranks(state.copy(query = "inf").sortedResults))
        assertEquals(listOf("INFY" to 5, "TATAPOWER" to 4), ranks(state.copy(sort = SortMode.SCORE, ascending = true).sortedResults))

        // The Support scan has filters of its own, Top limit included (by score), apart from the Breakout scan's.
        assertEquals(state.supportFilters, state.activeFilters)
        val dear = state.copy(supportFilters = ScanFilters(limit = 0).with(Metric.PRICE, Bound(min = 1_000.0)))
        assertEquals(listOf("INFY" to 5), ranks(dear.sortedResults))
        assertEquals(listOf("TATAPOWER" to 4), ranks(state.copy(supportFilters = ScanFilters(limit = 1)).sortedResults))
        assertEquals(breakout.filters, state.withScanMode(ScanMode.BREAKOUT).activeFilters)

        // Back on the Breakout scan the score's order and the filters return; the support filters read the floor.
        val back = state.withScanMode(ScanMode.BREAKOUT)
        assertEquals(SortMode.SCORE, back.sort)
        assertEquals(listOf("TECHM" to 1, "TCS" to 2), ranks(back.sortedResults))
        val near = back.copy(filters = ScanFilters(limit = 0).with(Metric.SUPPORT_GAP, Bound(max = 10.0)))
        assertEquals(listOf("TATAPOWER" to 1), ranks(near.sortedResults))
        val old = ScanFilters(limit = 0).with(Metric.SUPPORT_AGE, Bound(min = 63.0))
        assertEquals(listOf("TECHM", "M&M", "TATAPOWER", "INFY"), back.copy(filters = old).sortedResults.map { it.stock.symbol })
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
