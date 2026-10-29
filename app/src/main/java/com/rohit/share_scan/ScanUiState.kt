package com.rohit.share_scan

import java.time.LocalDate

enum class ScanPhase { IDLE, LOADING, SCANNING, DONE, FAILED }

/**
 * [SUPPORT] orders by the support floor's strength, then by how close the price sits to it; [CR360] by the 360CR score
 * ([Cr360Book]), stocks without one last.
 */
enum class SortMode { SCORE, RVOL, CHANGE, PRICE, SUPPORT, CR360 }

/**
 * Which scan the home screen shows. [BREAKOUT] is the main scan: pre-breakout stocks ranked by score, with the user's
 * filters. [SUPPORT] lists only stocks standing on a long support floor ([SupportFloor.long]). Both read the same
 * scanned charts, so one scan fills both.
 */
enum class ScanMode { BREAKOUT, SUPPORT }

/**
 * [rank] is the position among the stocks inside the filters, or [UNRANKED] for a starred stock outside them or
 * outside the scan; [badge] is the move since the previous scan and [starred] tells whether the stock is on the
 * watchlist.
 */
data class RankedStock(
    val rank: Int,
    val stock: ScanResult,
    val badge: RankBadge? = null,
    val starred: Boolean = false,
) {
    companion object {
        /** A starred stock the watchlist shows though the filters or the scan left it out. */
        const val UNRANKED = 0
    }
}

data class ScanUiState(
    val phase: ScanPhase = ScanPhase.IDLE,
    val done: Int = 0,
    val total: Int = 0,
    val startedAt: Long = 0L,
    val finishedAt: Long = 0L,
    /** Every stock the scan could score, best score first. */
    val results: List<ScanResult> = emptyList(),
    val marketCapDate: LocalDate? = null,
    val error: String? = null,
    val sort: SortMode = SortMode.SCORE,
    /** [sort] runs from the lowest value up instead of from the highest down. */
    val ascending: Boolean = false,
    val filters: ScanFilters = ScanFilters(),
    /** Symbols the user starred. */
    val watchlist: Set<String> = emptySet(),
    /** When and at what price each starred stock was added ([WatchBook]), what its return is measured from. */
    val watchAdded: Map<String, WatchEntry> = emptyMap(),
    /** Show only starred stocks. */
    val watchlistOnly: Boolean = false,
    val scanMode: ScanMode = ScanMode.BREAKOUT,
    /** The Support scan keeps stocks at most this many percent above their floor; null keeps them all. */
    val supportReach: Double? = null,
    /** The Support scan's own filters, apart from the Breakout scan's [filters]; no Top limit unless picked. */
    val supportFilters: ScanFilters = ScanFilters(limit = 0),
    val searchOpen: Boolean = false,
    /** Search text, matched against the symbol and the company name ([StockSearch]). */
    val query: String = "",
    /** Rank moves since the previous completed scan, by symbol; empty on the first scan. */
    val rankBadges: Map<String, RankBadge> = emptyMap(),
    /** Counts up with every scan, so progress a stopped scan's threads report late can be told apart and ignored. */
    val scanId: Int = 0,
    /** The user asked to stop; the scan is finishing the requests already under way. */
    val stopping: Boolean = false,
    /** With [ScanPhase.DONE]: the user stopped the scan, so [results] hold only the stocks scored before that. */
    val stopped: Boolean = false,
    /** Epoch millis when the scan in [results] finished; 0 without one. */
    val savedAt: Long = 0L,
    /** Live mode ran the scan in [results] in the background. */
    val autoResults: Boolean = false,
    /** Starred stocks the scan in [results] did not cover ([SavedScan.watchExtras]). */
    val watchExtras: List<ScanResult> = emptyList(),
    /** The scan in [results] left some industries out (Menu → स्कैन). */
    val narrowed: Boolean = false,
    /** Every stock's 360CR, from the daily file ([Cr360Repository]); empty until it is read. */
    val cr360: Cr360Book = Cr360Book.EMPTY,
    /** The learned setups, from the daily learner ([SetupRepository]); empty until read, when the app's own apply. */
    val setups: SetupBook = SetupBook.EMPTY,
    /** Live mode's background scan is under way (the list still shows the previous scan). */
    val runningAuto: Boolean = false,
    /** The latest run was stopped before any stock was scored; [results] are the scan before it. */
    val earlyStop: Boolean = false,
    /** The kept scan is still being read from disk, as on a fresh start; [results] are empty until it is in. */
    val reading: Boolean = false,
    /** Each stock's 📰 mark: its exchange filings of the last day ([NewsRepository.badges]); empty until read. */
    val news: Map<String, NewsBadge> = emptyMap(),
) {
    /** At least one price in [results] is from today's session, still trading. */
    val live: Boolean by lazy { results.any { it.live } }

    val isRunning: Boolean get() = phase == ScanPhase.LOADING || phase == ScanPhase.SCANNING

    val percent: Int get() = if (total > 0) (done * 100 / total).coerceIn(0, 100) else 0

    /** Every stock inside the filters, ranked by score, before the limit. */
    private val matching: List<RankedStock> by lazy {
        results.filter(filters::accepts).mapIndexed { index, stock ->
            RankedStock(index + 1, stock, rankBadges[stock.symbol], stock.symbol in watchlist)
        }
    }

    /** Stocks inside the filters, ranked by score and cut to the chosen limit. */
    val visible: List<RankedStock> by lazy {
        if (filters.limit > 0) matching.take(filters.limit) else matching
    }

    /**
     * Every scanned stock on a long support floor: not closed below it for about six months and tested since at least
     * three months ago. The Breakout scan's filters and limit do not apply; ranks are by score among all stocks.
     */
    val supportStocks: List<RankedStock> by lazy {
        results.mapIndexedNotNull { index, stock ->
            if (stock.support?.long != true) return@mapIndexedNotNull null
            RankedStock(index + 1, stock, rankBadges[stock.symbol], stock.symbol in watchlist)
        }
    }

    /** [supportStocks] within [supportReach] of their floor: what the Support scan's filters choose from. */
    val supportReached: List<RankedStock> by lazy {
        val reach = supportReach ?: return@lazy supportStocks
        supportStocks.filter { it.stock.support!!.distancePct(it.stock.price) <= reach }
    }

    /** The Support scan's list: [supportReached] inside [supportFilters], cut to their Top limit by score. */
    val supportListed: List<RankedStock> by lazy {
        val inside = supportReached.filter { supportFilters.accepts(it.stock) }
        if (supportFilters.limit > 0) inside.take(supportFilters.limit) else inside
    }

    /** The filters of the scan on screen. */
    val activeFilters: ScanFilters get() = if (scanMode == ScanMode.SUPPORT) supportFilters else filters

    /** The list before the search: the Support scan's, or the watchlist, or [visible]. */
    val listed: List<RankedStock> by lazy {
        when {
            scanMode == ScanMode.SUPPORT -> supportListed
            watchlistOnly -> starredStocks
            else -> visible
        }
    }

    /**
     * Every starred stock, whatever the filters, the limit or the scan's exchanges and industries: a watchlist always
     * shows what the user saved. Best score first; those inside the filters keep their rank, the rest, outside the
     * filters or not in this scan ([watchExtras]), are [RankedStock.UNRANKED]. A starred stock no chart has come in
     * for yet still shows, last, by its symbol alone ([ScanResult.charted]).
     */
    val starredStocks: List<RankedStock> by lazy {
        val ranked = matching.filter { it.starred }
        val shownSymbols = ranked.mapTo(HashSet()) { it.stock.symbol }
        val others = (results + watchExtras)
            .filter { it.symbol in watchlist && shownSymbols.add(it.symbol) }
            .map { RankedStock(RankedStock.UNRANKED, it, rankBadges[it.symbol], starred = true) }
        val uncharted = watchlist.filter { it !in shownSymbols }.sorted().map { symbol ->
            RankedStock(RankedStock.UNRANKED, ScanResult(symbol, price = 0.0, score = 0.0, rvol = 0.0, change5d = 0.0), starred = true)
        }
        (ranked + others).sortedByDescending { it.stock.score } + uncharted
    }

    /**
     * [listed] narrowed by the search; ranks keep their numbers. A search looks past the Top limit, through every
     * stock inside the filters, so any scanned stock can be found by its symbol or name.
     */
    val shown: List<RankedStock> by lazy {
        if (query.isBlank()) {
            listed
        } else {
            val pool = when {
                scanMode == ScanMode.SUPPORT -> supportListed
                watchlistOnly -> starredStocks
                else -> matching
            }
            pool.filter { StockSearch.matches(it.stock, query) }
        }
    }

    val volumeSpikes: Int get() = visible.count { it.stock.rvol >= StockFormat.SPIKE_RVOL }

    /** [symbol]'s latest price in the scan or among the watchlist's stocks outside it; null without a chart. */
    fun priceOf(symbol: String): Double? =
        (results.firstOrNull { it.symbol == symbol } ?: watchExtras.firstOrNull { it.symbol == symbol })?.price?.takeIf { it > 0.0 }

    /** [shown] in the chosen order; this is what the list displays. */
    val sortedResults: List<RankedStock>
        get() {
            if (sort == SortMode.CR360) {
                val (scored, unscored) = shown.partition { cr360.of(it.stock.symbol)?.score != null }
                val highFirst = scored.sortedByDescending { cr360.of(it.stock.symbol)?.score }
                return (if (ascending) highFirst.reversed() else highFirst) + unscored
            }
            val highFirst = when (sort) {
                SortMode.SCORE -> shown
                SortMode.RVOL -> shown.sortedByDescending { it.stock.rvol }
                SortMode.CHANGE -> shown.sortedByDescending { it.stock.change5d }
                SortMode.PRICE -> shown.sortedByDescending { it.stock.price }
                SortMode.SUPPORT -> shown.sortedWith(
                    compareByDescending<RankedStock> { it.stock.support?.strength ?: -1.0 }
                        .thenBy { it.stock.support?.distancePct(it.stock.price) ?: Double.MAX_VALUE },
                )
                SortMode.CR360 -> shown
            }
            return if (ascending) highFirst.reversed() else highFirst
        }

    /**
     * The same state on [mode]'s list: the Support scan has no watchlist view and opens ordered by the floor's
     * strength; back on the Breakout scan, that order gives way to the score's.
     */
    fun withScanMode(mode: ScanMode): ScanUiState = when (mode) {
        ScanMode.SUPPORT -> copy(scanMode = mode, watchlistOnly = false, sort = SortMode.SUPPORT, ascending = false)
        ScanMode.BREAKOUT -> copy(
            scanMode = mode,
            sort = if (sort == SortMode.SUPPORT) SortMode.SCORE else sort,
            ascending = if (sort == SortMode.SUPPORT) false else ascending,
        )
    }

    fun elapsedAt(now: Long): Long = when {
        isRunning -> now - startedAt
        finishedAt > startedAt -> finishedAt - startedAt
        else -> 0L
    }
}
