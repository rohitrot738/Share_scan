package com.rohit.share_scan

import android.app.Application
import android.content.Context
import androidx.core.content.edit
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.updateAndGet
import kotlinx.coroutines.launch

/**
 * The home screen's state: the scan from [ScanEngine] (running or kept) together with the user's filters, sort,
 * watchlist and search. The scan itself lives in the engine, so it survives the screen and live mode's background
 * scans show here as they finish.
 */
class ScanViewModel(application: Application) : AndroidViewModel(application) {
    private val prefs = application.getSharedPreferences(Prefs.FILE, Context.MODE_PRIVATE)
    private val state = MutableStateFlow(
        ScanUiState(
            filters = ScanFilters.decode(prefs.getString(Prefs.FILTERS, null)),
            supportFilters = prefs.getString(Prefs.SUPPORT_FILTERS, null)?.let(ScanFilters::decode)
                ?: ScanFilters(limit = 0),
            watchlist = prefs.getStringSet(Prefs.WATCHLIST, null).orEmpty().toSet(),
        ).withScanMode(
            if (prefs.getString(Prefs.SCAN_MODE, null) == ScanMode.SUPPORT.name) ScanMode.SUPPORT else ScanMode.BREAKOUT,
        ),
    )
    val uiState: StateFlow<ScanUiState> = state.asStateFlow()

    init {
        ScanEngine.load(application)
        // 360CR: the kept file first, then a fresh one when it is half a day old.
        // Learned setups: the same, from the daily learner's file.
        viewModelScope.launch(Dispatchers.IO) {
            val setups = SetupRepository.book(application)
            state.update { it.copy(setups = setups) }
            if (runCatching { SetupRepository.refreshIfStale(application) }.getOrDefault(false)) {
                state.update { it.copy(setups = SetupRepository.current()) }
            }
        }
        viewModelScope.launch(Dispatchers.IO) {
            val book = Cr360Repository.book(application)
            state.update { it.copy(cr360 = book) }
            if (runCatching { Cr360Repository.refreshIfStale(application) }.getOrDefault(false)) {
                state.update { it.copy(cr360 = Cr360Repository.current()) }
            }
        }
        viewModelScope.launch {
            combine(
                ScanEngine.progress,
                ScanEngine.latest,
                ScanEngine.failure,
                ScanEngine.read,
            ) { progress, latest, failure, read -> show(progress, latest, failure, reading = !read) }.collect {}
        }
    }

    private fun show(progress: ScanEngine.Progress, latest: SavedScan?, failure: ScanEngine.Failure?, reading: Boolean) {
        val results = latest?.output?.results.orEmpty()
        val lastRun = failure?.takeIf { it.runId == progress.runId }
        val phase = when {
            progress.running -> if (progress.loading) ScanPhase.LOADING else ScanPhase.SCANNING
            lastRun != null && !lastRun.stoppedEarly -> ScanPhase.FAILED
            latest != null || lastRun != null -> ScanPhase.DONE
            else -> ScanPhase.IDLE
        }
        // A scan run since the app started reports its own count; a kept scan from disk reports what it holds.
        val (done, total) = if (progress.runId > 0 && (progress.running || progress.total > 0)) {
            progress.done to progress.total
        } else {
            results.size to results.size
        }
        state.update { current ->
            current.copy(
                phase = phase,
                done = done,
                total = total,
                startedAt = progress.startedAt,
                finishedAt = if (progress.running) 0L else progress.endedAt,
                results = results,
                marketCapDate = latest?.output?.marketCapDate,
                error = lastRun?.message,
                rankBadges = latest?.badges.orEmpty(),
                scanId = progress.runId,
                stopping = progress.stopping,
                stopped = when {
                    progress.running -> false
                    lastRun?.stoppedEarly == true -> true
                    // Live mode out of time is not a stop the user made.
                    else -> latest != null && latest.output.stopped && !latest.auto
                },
                savedAt = latest?.finishedAt ?: 0L,
                autoResults = latest?.auto == true,
                watchExtras = latest?.watchExtras.orEmpty(),
                narrowed = latest != null && ScanSectors.narrowed(latest.scope),
                runningAuto = progress.running && progress.trigger == ScanEngine.Trigger.AUTO,
                earlyStop = !progress.running && lastRun?.stoppedEarly == true,
                reading = reading && latest == null,
                // A scan may have brought a fresh 360CR file (ScanEngine).
                cr360 = Cr360Repository.current().takeIf { it !== Cr360Book.EMPTY } ?: current.cr360,
                setups = SetupRepository.current().takeIf { it !== SetupBook.EMPTY } ?: current.setups,
            )
        }
    }

    /** Starts the user's scan, run in a foreground service so it goes on with the app out of sight ([ScanService]). */
    fun startScan() {
        if (ScanEngine.start(getApplication(), ScanEngine.Trigger.USER)) ScanService.start(getApplication())
    }

    /**
     * Stops the running scan, the user's or live mode's. Mid-scan it winds down within about a second and the stocks
     * scored so far stay, ranked as usual; while the stock lists load nothing is scored yet and the kept scan stays.
     */
    fun stopScan() = ScanEngine.stop()

    /** Orders the list by [mode], highest first; picking the mode already in use turns its order round. */
    fun pickSort(mode: SortMode) {
        state.update {
            if (it.sort == mode) it.copy(ascending = !it.ascending) else it.copy(sort = mode, ascending = false)
        }
    }

    fun setFilters(filters: ScanFilters) {
        state.update { it.copy(filters = filters) }
        prefs.edit { putString(Prefs.FILTERS, filters.encode()) }
    }

    /** The Support scan's own filters, kept apart from the Breakout scan's. */
    fun setSupportFilters(filters: ScanFilters) {
        state.update { it.copy(supportFilters = filters) }
        prefs.edit { putString(Prefs.SUPPORT_FILTERS, filters.encode()) }
    }

    fun toggleStar(symbol: String) {
        val updated = state.updateAndGet { current ->
            val watchlist = if (symbol in current.watchlist) current.watchlist - symbol else current.watchlist + symbol
            current.copy(watchlist = watchlist)
        }
        saveWatchlist(updated.watchlist)
    }

    fun clearWatchlist() {
        state.update { it.copy(watchlist = emptySet()) }
        saveWatchlist(emptySet())
    }

    /** Takes a watchlist that changed elsewhere (another device, through the cloud), without sending it back. */
    fun showWatchlist(watchlist: Set<String>) {
        state.update { it.copy(watchlist = watchlist) }
    }

    private fun saveWatchlist(watchlist: Set<String>) {
        prefs.edit {
            if (watchlist.isEmpty()) remove(Prefs.WATCHLIST) else putStringSet(Prefs.WATCHLIST, HashSet(watchlist))
        }
        CloudWatchlist.push(getApplication(), watchlist)
    }

    /** Forgets the previous scan's ranks, so the next scan starts fresh without NEW or ▲▼ badges. */
    fun clearRankHistory() {
        state.update { it.copy(rankBadges = emptyMap()) }
        prefs.edit { remove(Prefs.LAST_RANKS) }
        ScanEngine.clearBadges(getApplication())
    }

    /** Clears the kept scan and its ranks ([ScanEngine.clear]); false while a scan runs. */
    fun clearScans(): Boolean = ScanEngine.clear(getApplication())

    fun setWatchlistOnly(only: Boolean) {
        state.update { it.copy(watchlistOnly = only) }
    }

    /** Switches between the Breakout and the Support scan; the app opens on the one last chosen. */
    fun setScanMode(mode: ScanMode) {
        state.update { it.withScanMode(mode) }
        prefs.edit { putString(Prefs.SCAN_MODE, mode.name) }
    }

    /** How far above its floor a stock may sit to stay in the Support scan; null for any distance. */
    fun setSupportReach(reach: Double?) {
        state.update { it.copy(supportReach = reach) }
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
}
