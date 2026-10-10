package com.rohit.share_scan

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.view.View
import android.view.animation.LinearInterpolator
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.annotation.DrawableRes
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.rohit.share_scan.databinding.ActivityMainBinding
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {
    private val scan: ScanViewModel by viewModels()
    private lateinit var ui: ActivityMainBinding
    private val adapter = StockAdapter(::openChart)
    private var shownResults: List<RankedStock>? = null
    private var shownSort: SortMode? = null
    private var shownRing = -1
    private var radarSpin: ObjectAnimator? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        ui = ActivityMainBinding.inflate(layoutInflater)
        setContentView(ui.root)
        ui.hero.clipToOutline = true
        ui.results.adapter = adapter
        applyWindowInsets()

        ui.startButton.setOnClickListener {
            ui.appBar.setExpanded(true, true)
            scan.startScan()
        }
        ui.sortChips.setOnCheckedStateChangeListener { _, checked ->
            scan.setSort(
                when (checked.firstOrNull()) {
                    R.id.sortRvol -> SortMode.RVOL
                    R.id.sortChange -> SortMode.CHANGE
                    R.id.sortPrice -> SortMode.PRICE
                    else -> SortMode.SCORE
                }
            )
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { scan.uiState.collect { render(it) } }
                launch {
                    // Keeps the elapsed-time clock in the hero card ticking during a scan.
                    while (true) {
                        delay(1_000)
                        val state = scan.uiState.value
                        if (state.isRunning) renderHero(state)
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        radarSpin?.cancel()
        super.onDestroy()
    }

    private fun render(state: ScanUiState) {
        renderHero(state)
        renderStats(state)
        renderResults(state)
        ui.root.keepScreenOn = state.isRunning
    }

    private fun renderHero(state: ScanUiState) {
        val clock = StockFormat.duration(state.elapsedAt(SystemClock.elapsedRealtime()))
        when (state.phase) {
            ScanPhase.IDLE -> showStatus(
                getString(R.string.status_ready_title),
                getString(R.string.status_ready_body),
                R.drawable.ic_radar,
                0,
            )
            ScanPhase.LOADING -> showStatus(
                getString(R.string.status_loading_title),
                getString(R.string.status_loading_body, clock),
                R.drawable.ic_radar,
                0,
            )
            ScanPhase.SCANNING -> showStatus(
                getString(R.string.status_scanning_title),
                getString(
                    R.string.status_scanning_body,
                    StockFormat.count(state.done),
                    StockFormat.count(state.total),
                    clock,
                ),
                null,
                state.percent,
            )
            ScanPhase.DONE -> showStatus(
                getString(R.string.status_done_title),
                getString(R.string.status_done_body, state.results.size, clock),
                R.drawable.ic_check,
                100,
            )
            ScanPhase.FAILED -> showStatus(
                getString(R.string.status_failed_title),
                state.error?.let { getString(R.string.status_failed_detail, it) }
                    ?: getString(R.string.status_failed_body),
                R.drawable.ic_error,
                0,
            )
        }

        val (label, icon) = when (state.phase) {
            ScanPhase.IDLE -> R.string.action_start to R.drawable.ic_play
            ScanPhase.LOADING, ScanPhase.SCANNING -> R.string.action_scanning to R.drawable.ic_radar
            ScanPhase.DONE -> R.string.action_rescan to R.drawable.ic_refresh
            ScanPhase.FAILED -> R.string.action_retry to R.drawable.ic_refresh
        }
        ui.startButton.setText(label)
        ui.startButton.setIconResource(icon)
        ui.startButton.isEnabled = !state.isRunning
    }

    /** [icon] replaces the percentage in the middle of the progress ring when it is not null. */
    private fun showStatus(title: String, body: String, @DrawableRes icon: Int?, ring: Int) {
        ui.statusTitle.text = title
        ui.statusBody.text = body
        ui.ringIcon.isVisible = icon != null
        if (icon != null) ui.ringIcon.setImageResource(icon)
        ui.ringPercent.isVisible = icon == null
        ui.ringPercent.text = getString(R.string.percent, ring)
        if (ring != shownRing) {
            ui.progressRing.setProgressCompat(ring, shownRing >= 0)
            shownRing = ring
        }
    }

    private fun renderStats(state: ScanUiState) {
        val empty = getString(R.string.stat_empty)
        ui.statScannedValue.text = if (state.total > 0) StockFormat.count(state.done) else empty
        ui.statTopValue.text = state.results.firstOrNull()?.let { StockFormat.score(it.stock.score) } ?: empty
        ui.statSpikesValue.text = if (state.results.isEmpty()) empty else state.volumeSpikes.toString()
    }

    private fun renderResults(state: ScanUiState) {
        val hasResults = state.results.isNotEmpty()
        ui.resultCount.isVisible = hasResults
        ui.resultCount.text = state.results.size.toString()
        ui.resultsHint.isVisible = hasResults
        for (index in 0 until ui.sortChips.childCount) ui.sortChips.getChildAt(index).isEnabled = hasResults
        val sortChip = when (state.sort) {
            SortMode.SCORE -> R.id.sortScore
            SortMode.RVOL -> R.id.sortRvol
            SortMode.CHANGE -> R.id.sortChange
            SortMode.PRICE -> R.id.sortPrice
        }
        if (ui.sortChips.checkedChipId != sortChip) ui.sortChips.check(sortChip)

        ui.results.isVisible = hasResults
        ui.emptyScroll.isVisible = !hasResults
        renderEmpty(state)

        if (state.results !== shownResults || state.sort != shownSort) {
            val freshResults = state.results !== shownResults
            shownResults = state.results
            shownSort = state.sort
            adapter.submitList(state.sortedResults) {
                ui.results.scrollToPosition(0)
                if (freshResults && hasResults) ui.results.scheduleLayoutAnimation()
            }
        }
    }

    private fun renderEmpty(state: ScanUiState) {
        val (title, body, icon) = when {
            state.isRunning -> Triple(R.string.empty_scanning_title, R.string.empty_scanning_body, R.drawable.ic_radar)
            state.phase == ScanPhase.IDLE -> Triple(R.string.empty_title, R.string.empty_body, R.drawable.ic_trending_up)
            else -> Triple(R.string.empty_failed_title, R.string.empty_failed_body, R.drawable.ic_error)
        }
        ui.emptyTitle.setText(title)
        ui.emptyBody.setText(body)
        ui.emptyIcon.setImageResource(icon)

        val spin = state.isRunning && ui.emptyScroll.isVisible
        if (spin && radarSpin == null) {
            radarSpin = ObjectAnimator.ofFloat(ui.emptyIcon, View.ROTATION, 0f, 360f).apply {
                duration = 2_400
                repeatCount = ValueAnimator.INFINITE
                interpolator = LinearInterpolator()
                start()
            }
        } else if (!spin && radarSpin != null) {
            radarSpin?.cancel()
            radarSpin = null
            ui.emptyIcon.rotation = 0f
        }
    }

    private fun applyWindowInsets() {
        val listBottom = ui.results.paddingBottom
        val emptyBottom = ui.emptyState.paddingBottom
        val bars = WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
        ViewCompat.setOnApplyWindowInsetsListener(ui.appBar) { view, insets ->
            val inset = insets.getInsets(bars)
            view.updatePadding(left = inset.left, top = inset.top, right = inset.right)
            insets
        }
        ViewCompat.setOnApplyWindowInsetsListener(ui.listContainer) { _, insets ->
            val inset = insets.getInsets(bars)
            ui.results.updatePadding(left = inset.left, right = inset.right, bottom = listBottom + inset.bottom)
            ui.emptyState.updatePadding(bottom = emptyBottom + inset.bottom)
            insets
        }
    }

    private fun openChart(stock: ScanResult) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(StockFormat.chartUrl(stock.symbol))))
        } catch (error: ActivityNotFoundException) {
            Toast.makeText(this, R.string.open_chart_failed, Toast.LENGTH_SHORT).show()
        }
    }
}
