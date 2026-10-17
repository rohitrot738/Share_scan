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
import java.time.LocalDate
import java.util.concurrent.Executors

enum class ScanPhase { IDLE, LOADING, SCANNING, DONE, FAILED }

enum class SortMode { SCORE, RVOL, CHANGE, PRICE }

data class RankedStock(val rank: Int, val stock: ScanResult)

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
) {
    val isRunning: Boolean get() = phase == ScanPhase.LOADING || phase == ScanPhase.SCANNING

    val percent: Int get() = if (total > 0) (done * 100 / total).coerceIn(0, 100) else 0

    /** Stocks inside the filters, ranked by score and cut to the chosen limit. */
    val visible: List<RankedStock> by lazy {
        val matching = results.filter(filters::accepts)
        val limited = if (filters.limit > 0) matching.take(filters.limit) else matching
        limited.mapIndexed { index, stock -> RankedStock(index + 1, stock) }
    }

    val volumeSpikes: Int get() = visible.count { it.stock.rvol >= StockFormat.SPIKE_RVOL }

    val sortedResults: List<RankedStock>
        get() = when (sort) {
            SortMode.SCORE -> visible
            SortMode.RVOL -> visible.sortedByDescending { it.stock.rvol }
            SortMode.CHANGE -> visible.sortedByDescending { it.stock.change5d }
            SortMode.PRICE -> visible.sortedByDescending { it.stock.price }
        }

    fun elapsedAt(now: Long): Long = when {
        isRunning -> now - startedAt
        finishedAt > startedAt -> finishedAt - startedAt
        else -> 0L
    }
}

/** Owns the running scan so it survives rotation and theme changes, and remembers the user's filters. */
class ScanViewModel(application: Application) : AndroidViewModel(application) {
    private val prefs = application.getSharedPreferences("scan", Context.MODE_PRIVATE)
    private val worker = Executors.newSingleThreadExecutor()
    private val state = MutableStateFlow(ScanUiState(filters = ScanFilters.decode(prefs.getString(KEY_FILTERS, null))))
    val uiState: StateFlow<ScanUiState> = state.asStateFlow()

    fun startScan() {
        val previous = state.value
        if (previous.isRunning) return
        state.value = ScanUiState(
            phase = ScanPhase.LOADING,
            startedAt = SystemClock.elapsedRealtime(),
            sort = previous.sort,
            filters = previous.filters,
        )
        worker.execute {
            val outcome = runCatching {
                NseScanner.scan { finished, total ->
                    // Progress arrives from several pool threads, so keep the highest count seen.
                    state.update { it.copy(phase = ScanPhase.SCANNING, done = maxOf(it.done, finished), total = total) }
                }
            }
            val finishedAt = SystemClock.elapsedRealtime()
            state.update { current ->
                outcome.fold(
                    onSuccess = { output ->
                        current.copy(
                            phase = ScanPhase.DONE,
                            finishedAt = finishedAt,
                            results = output.results.sortedByDescending { it.score },
                            marketCapDate = output.marketCapDate,
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
        prefs.edit { putString(KEY_FILTERS, filters.encode()) }
    }

    override fun onCleared() {
        worker.shutdownNow()
    }

    private companion object {
        const val KEY_FILTERS = "filters"
    }
}
