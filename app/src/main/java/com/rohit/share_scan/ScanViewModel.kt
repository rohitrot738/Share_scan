package com.rohit.share_scan

import android.app.Application
import android.content.Context
import android.os.SystemClock
import androidx.core.content.edit
import androidx.lifecycle.AndroidViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.updateAndGet
import java.time.LocalDate
import java.util.concurrent.Executors

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
) {
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

/** Owns the running scan so it survives rotation and theme changes, and remembers the user's filters. */
class ScanViewModel(application: Application) : AndroidViewModel(application) {
    private val prefs = application.getSharedPreferences(Prefs.FILE, Context.MODE_PRIVATE)
    private val settings = AppSettings(application)
    private val worker = Executors.newSingleThreadExecutor()
    private val state = MutableStateFlow(
        ScanUiState(
            filters = ScanFilters.decode(prefs.getString(Prefs.FILTERS, null)),
            watchlist = prefs.getStringSet(Prefs.WATCHLIST, null).orEmpty().toSet(),
        ),
    )
    val uiState: StateFlow<ScanUiState> = state.asStateFlow()

    fun startScan() {
        val previous = state.value
        if (previous.isRunning) return
        state.value = ScanUiState(
            phase = ScanPhase.LOADING,
            startedAt = SystemClock.elapsedRealtime(),
            sort = previous.sort,
            filters = previous.filters,
            watchlist = previous.watchlist,
            watchlistOnly = previous.watchlistOnly,
            searchOpen = previous.searchOpen,
            query = previous.query,
        )
        worker.execute {
            val outcome = runCatching {
                NseScanner.scan(includeSme = settings.includeSme, includeBse = settings.includeBse) { finished, total ->
                    // Progress arrives from several pool threads, so keep the highest count seen.
                    state.update { it.copy(phase = ScanPhase.SCANNING, done = maxOf(it.done, finished), total = total) }
                }
            }
            val finishedAt = SystemClock.elapsedRealtime()
            val ranked = outcome.getOrNull()?.results?.sortedByDescending { it.score }.orEmpty()
            val badges = if (outcome.isSuccess) trackRanks(ranked) else emptyMap()
            state.update { current ->
                outcome.fold(
                    onSuccess = { output ->
                        current.copy(
                            phase = ScanPhase.DONE,
                            finishedAt = finishedAt,
                            results = ranked,
                            marketCapDate = output.marketCapDate,
                            rankBadges = badges,
                        )
                    },
                    onFailure = { error ->
                        current.copy(phase = ScanPhase.FAILED, finishedAt = finishedAt, error = error.message)
                    },
                )
            }
        }
    }

    fun setSort(mode: SortMode) {
        state.update { it.copy(sort = mode) }
    }

    fun setFilters(filters: ScanFilters) {
        state.update { it.copy(filters = filters) }
        prefs.edit { putString(Prefs.FILTERS, filters.encode()) }
    }

    fun toggleStar(symbol: String) {
        val updated = state.updateAndGet { current ->
            val watchlist = if (symbol in current.watchlist) current.watchlist - symbol else current.watchlist + symbol
            current.copy(watchlist = watchlist)
        }
        prefs.edit { putStringSet(Prefs.WATCHLIST, HashSet(updated.watchlist)) }
    }

    fun clearWatchlist() {
        state.update { it.copy(watchlist = emptySet()) }
        prefs.edit { remove(Prefs.WATCHLIST) }
    }

    /** Forgets the previous scan's ranks, so the next scan starts fresh without NEW or ▲▼ badges. */
    fun clearRankHistory() {
        state.update { it.copy(rankBadges = emptyMap()) }
        prefs.edit { remove(Prefs.LAST_RANKS) }
    }

    fun setWatchlistOnly(only: Boolean) {
        state.update { it.copy(watchlistOnly = only) }
    }

    fun openSearch() {
        state.update { it.copy(searchOpen = true) }
    }

    fun setQuery(query: String) {
        state.update { it.copy(query = query) }
    }

    fun closeSearch() {
        state.update { it.copy(searchOpen = false, query = "") }
    }

    /**
     * Badges for a finished scan against the previous completed scan, then remembers this scan's ranks.
     * Runs on the worker thread; an empty scan leaves the previous ranks in place.
     */
    private fun trackRanks(results: List<ScanResult>): Map<String, RankBadge> {
        if (results.isEmpty()) return emptyMap()
        val symbols = results.map { it.symbol }
        val previous = RankHistory.decode(prefs.getString(Prefs.LAST_RANKS, null))
        prefs.edit { putString(Prefs.LAST_RANKS, RankHistory.encode(symbols)) }
        return RankHistory.badges(symbols, previous)
    }

    override fun onCleared() {
        worker.shutdownNow()
    }
}
