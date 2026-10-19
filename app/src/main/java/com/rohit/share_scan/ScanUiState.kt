package com.rohit.share_scan

import java.time.LocalDate

enum class ScanPhase { IDLE, LOADING, SCANNING, DONE, FAILED }

enum class SortMode { SCORE, RVOL, CHANGE, PRICE }

/**
 * [rank] is the position among the stocks inside the filters; [badge] is the move since the previous scan
 * and [starred] tells whether the stock is on the watchlist.
 */
data class RankedStock(
    val rank: Int,
    val stock: ScanResult,
    val badge: RankBadge? = null,
    val starred: Boolean = false,
)

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

    /**
     * The list before the search: [visible], or in watchlist mode every starred stock inside the filters.
     * Watchlist mode ignores the limit so a starred stock that slipped below it still shows, with its real rank.
     */
    val listed: List<RankedStock> by lazy {
        if (watchlistOnly) matching.filter { it.starred } else visible
    }

    /**
     * [listed] narrowed by the search; ranks keep their numbers. A search looks past the Top limit, through every
     * stock inside the filters, so any scanned stock can be found by its symbol or name.
     */
    val shown: List<RankedStock> by lazy {
        if (query.isBlank()) {
            listed
        } else {
            val pool = if (watchlistOnly) matching.filter { it.starred } else matching
            pool.filter { StockSearch.matches(it.stock, query) }
        }
    }

    val volumeSpikes: Int get() = visible.count { it.stock.rvol >= StockFormat.SPIKE_RVOL }

    /** [shown] in the chosen order; this is what the list displays. */
    val sortedResults: List<RankedStock>
        get() = when (sort) {
            SortMode.SCORE -> shown
            SortMode.RVOL -> shown.sortedByDescending { it.stock.rvol }
            SortMode.CHANGE -> shown.sortedByDescending { it.stock.change5d }
            SortMode.PRICE -> shown.sortedByDescending { it.stock.price }
        }

    fun elapsedAt(now: Long): Long = when {
        isRunning -> now - startedAt
        finishedAt > startedAt -> finishedAt - startedAt
        else -> 0L
    }
}
