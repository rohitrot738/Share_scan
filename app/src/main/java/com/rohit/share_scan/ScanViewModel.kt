package com.rohit.share_scan

import android.os.SystemClock
import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
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
    val results: List<RankedStock> = emptyList(),
    val error: String? = null,
    val sort: SortMode = SortMode.SCORE,
) {
    val isRunning: Boolean get() = phase == ScanPhase.LOADING || phase == ScanPhase.SCANNING

    val percent: Int get() = if (total > 0) (done * 100 / total).coerceIn(0, 100) else 0

    val volumeSpikes: Int get() = results.count { it.stock.rvol >= StockFormat.SPIKE_RVOL }

    val sortedResults: List<RankedStock>
        get() = when (sort) {
            SortMode.SCORE -> results
            SortMode.RVOL -> results.sortedByDescending { it.stock.rvol }
            SortMode.CHANGE -> results.sortedByDescending { it.stock.change5d }
            SortMode.PRICE -> results.sortedByDescending { it.stock.price }
        }

    fun elapsedAt(now: Long): Long = when {
        isRunning -> now - startedAt
        finishedAt > startedAt -> finishedAt - startedAt
        else -> 0L
    }
}

/** Owns the running scan so it survives rotation and theme changes. */
class ScanViewModel : ViewModel() {
    private val worker = Executors.newSingleThreadExecutor()
    private val state = MutableStateFlow(ScanUiState())
    val uiState: StateFlow<ScanUiState> = state.asStateFlow()

    fun startScan() {
        val previous = state.value
        if (previous.isRunning) return
        state.value = ScanUiState(
            phase = ScanPhase.LOADING,
            startedAt = SystemClock.elapsedRealtime(),
            sort = previous.sort,
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
                    onSuccess = { rows ->
                        current.copy(
                            phase = ScanPhase.DONE,
                            finishedAt = finishedAt,
                            results = rows.sortedByDescending { it.score }
                                .mapIndexed { index, row -> RankedStock(index + 1, row) },
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

    override fun onCleared() {
        worker.shutdownNow()
    }
}
