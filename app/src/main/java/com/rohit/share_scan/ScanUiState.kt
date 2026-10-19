package com.rohit.share_scan

import java.time.LocalDate

enum class ScanPhase { IDLE, LOADING, SCANNING, DONE, FAILED }

enum class SortMode { SCORE, RVOL, CHANGE, PRICE }

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
    /** Show only starred stocks. */
    val watchlistOnly: Boolean = false,
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
    /** Live mode's background scan is under way (the list still shows the previous scan). */
    val runningAuto: Boolean = false,
    /** The latest run was stopped before any stock was scored; [results] are the scan before it. */
    val earlyStop: Boolean = false,
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

    /** The list before the search: [visible], or in watchlist mode [starredStocks]. */
    val listed: List<RankedStock> by lazy {
        if (watchlistOnly) starredStocks else visible
    }

    /**
     * Every starred stock, best score first, whatever the filters, the limit or the scan's exchanges and industries:
     * a watchlist always shows what the user saved. Those inside the filters keep their rank; the rest, outside the
     * filters or not in this scan ([watchExtras]), are [RankedStock.UNRANKED].
     */
    val starredStocks: List<RankedStock> by lazy {
        val ranked = matching.filter { it.starred }
        val shownSymbols = ranked.mapTo(HashSet()) { it.stock.symbol }
        val others = (results + watchExtras)
            .filter { it.symbol in watchlist && shownSymbols.add(it.symbol) }
            .map { RankedStock(RankedStock.UNRANKED, it, rankBadges[it.symbol], starred = true) }
        (ranked + others).sortedByDescending { it.stock.score }
    }

    /**
     * [listed] narrowed by the search; ranks keep their numbers. A search looks past the Top limit, through every
     * stock inside the filters, so any scanned stock can be found by its symbol or name.
     */
    val shown: List<RankedStock> by lazy {
        if (query.isBlank()) {
            listed
        } else {
            val pool = if (watchlistOnly) starredStocks else matching
            pool.filter { StockSearch.matches(it.stock, query) }
        }
    }

    val volumeSpikes: Int get() = visible.count { it.stock.rvol >= StockFormat.SPIKE_RVOL }

    /** [shown] in the chosen order; this is what the list displays. */
    val sortedResults: List<RankedStock>
        get() {
            val highFirst = when (sort) {
                SortMode.SCORE -> shown
                SortMode.RVOL -> shown.sortedByDescending { it.stock.rvol }
                SortMode.CHANGE -> shown.sortedByDescending { it.stock.change5d }
                SortMode.PRICE -> shown.sortedByDescending { it.stock.price }
            }
            return if (ascending) highFirst.reversed() else highFirst
        }

    fun elapsedAt(now: Long): Long = when {
        isRunning -> now - startedAt
        finishedAt > startedAt -> finishedAt - startedAt
        else -> 0L
    }
}
