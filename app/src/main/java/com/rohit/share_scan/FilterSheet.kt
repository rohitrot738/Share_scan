package com.rohit.share_scan

import android.content.res.ColorStateList
import android.graphics.Color
import android.view.View
import android.view.ViewGroup
import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.chip.Chip
import com.rohit.share_scan.databinding.ChipChoiceBinding
import com.rohit.share_scan.databinding.ItemFilterMetricBinding
import com.rohit.share_scan.databinding.SheetFiltersBinding
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Bottom sheet where every metric gets a min/max range slider; nothing changes until "लागू करें". It edits one
 * scan's [filters], counting matches among that scan's [pool] (every scanned stock for the Breakout scan, the stocks on
 * a long support floor for the Support scan); Reset goes back to [defaults]. With the compact list on, it wears the
 * list's [flat] look: thin sliders, outlined chips and an accent capsule for "लागू करें".
 */
class FilterSheet(
    private val activity: AppCompatActivity,
    private val state: ScanUiState,
    filters: ScanFilters,
    private val pool: List<ScanResult>,
    private val defaults: ScanFilters,
    private val onApply: (ScanFilters) -> Unit,
    private val flat: Boolean = false,
) {
    private val dialog = BottomSheetDialog(activity)
    private val ui = SheetFiltersBinding.inflate(activity.layoutInflater)
    private val rows = LinkedHashMap<Metric, ItemFilterMetricBinding>()
    private val presetChips = mutableListOf<Pair<Chip, Bound>>()
    private val weekChips = mutableMapOf<Int, Chip>()
    private val limitChips = mutableMapOf<Int, Int>()
    private var draft = filters

    fun show(): BottomSheetDialog {
        Metric.entries.forEach(::addRow)
        addLimitChips()
        ui.filterReset.setOnClickListener {
            draft = resetDraft()
            sync()
        }
        if (flat) {
            ui.filterApply.wearFlatAction()
            listOf(ui.limitChips).plus(rows.values.map { it.metricPresets })
                .flatMap { group -> (0 until group.childCount).map(group::getChildAt) }
                .filterIsInstance<Chip>()
                .forEach { it.wearFlat(accented = false) }
        } else {
            ui.filterApply.wearChipGlass(APPLY_CORNER_DP, tintBoost = 1.8f, ripple = true)
        }
        ui.filterApply.setOnClickListener {
            onApply(draft)
            dialog.dismiss()
        }
        val height = (activity.resources.displayMetrics.heightPixels * 0.88f).toInt()
        dialog.setContentView(ui.root, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, height))
        dialog.behavior.state = BottomSheetBehavior.STATE_EXPANDED
        dialog.behavior.skipCollapsed = true
        sync()
        dialog.show()
        return dialog
    }

    private fun addRow(metric: Metric) {
        val row = ItemFilterMetricBinding.inflate(activity.layoutInflater, ui.filterMetrics, true)
        rows[metric] = row
        if (flat) row.metricSlider.wearFlat()
        row.metricName.setText(nameOf(metric))
        hintOf(metric)?.let {
            row.metricHint.text = it
            row.metricHint.isVisible = true
        }
        if (metric == Metric.MARKET_CAP && state.phase == ScanPhase.DONE && state.marketCapDate == null) {
            row.metricHint.setTextColor(ContextCompat.getColor(activity, R.color.warn))
        }
        row.metricSlider.apply {
            valueFrom = 0f
            valueTo = metric.lastPosition.toFloat()
            stepSize = 1f
            setMinSeparationValue(1f)
            setLabelFormatter { position -> label(metric, position.roundToInt()) }
            addOnChangeListener { slider, _, fromUser ->
                if (!fromUser) return@addOnChangeListener
                val low = slider.values[0].roundToInt()
                val high = slider.values[1].roundToInt()
                draft = draft.with(
                    metric,
                    Bound(
                        min = if (low == 0) null else metric.valueAt(low),
                        max = if (high == metric.lastPosition) null else metric.valueAt(high),
                    ),
                )
                syncLabels()
            }
        }
        if (metric == Metric.MARKET_CAP) addMarketCapPresets(row)
        if (metric == Metric.BASE_RANGE) addWeekChips(row)
    }

    private fun addMarketCapPresets(row: ItemFilterMetricBinding) {
        row.metricPresets.isVisible = true
        for ((label, bound) in MARKET_CAP_PRESETS) {
            val chip = ChipChoiceBinding.inflate(activity.layoutInflater, row.metricPresets, true).root
            chip.setText(label)
            chip.isCheckable = true
            chip.setOnClickListener {
                val current = draft.bound(Metric.MARKET_CAP)
                draft = draft.with(Metric.MARKET_CAP, if (current == bound) Bound() else bound)
                sync()
            }
            presetChips += chip to bound
        }
    }

    /** One chip per span the range can cover, 1–4 weeks; one is always picked. */
    private fun addWeekChips(row: ItemFilterMetricBinding) {
        row.metricPresets.isVisible = true
        for (weeks in ScanFilters.RANGE_WEEKS) {
            val chip = ChipChoiceBinding.inflate(activity.layoutInflater, row.metricPresets, true).root
            chip.text = StockFormat.weeks(weeks)
            chip.isCheckable = true
            chip.setOnClickListener {
                draft = draft.copy(rangeWeeks = weeks)
                syncLabels()
            }
            weekChips[weeks] = chip
        }
    }

    /** [defaults] for what this sheet shows; the industries are picked with the main screen's sector chip and stay. */
    private fun resetDraft(): ScanFilters = defaults.copy(sectors = draft.sectors)

    private fun addLimitChips() {
        for (limit in ScanFilters.LIMITS) {
            val chip = ChipChoiceBinding.inflate(activity.layoutInflater, ui.limitChips, true).root
            chip.id = View.generateViewId()
            chip.text = if (limit == 0) activity.getString(R.string.filters_limit_all) else limit.toString()
            limitChips[chip.id] = limit
        }
        ui.limitChips.setOnCheckedStateChangeListener { _, checked ->
            val limit = checked.firstOrNull()?.let(limitChips::get) ?: return@setOnCheckedStateChangeListener
            if (limit != draft.limit) {
                draft = draft.copy(limit = limit)
                syncLabels()
            }
        }
    }

    /** Moves every control to match [draft]. */
    private fun sync() {
        for ((metric, row) in rows) {
            val bound = draft.bound(metric)
            val low = metric.positionOf(bound.min, upper = false).coerceIn(0, metric.lastPosition - 1)
            val high = metric.positionOf(bound.max, upper = true).coerceIn(low + 1, metric.lastPosition)
            row.metricSlider.values = listOf(low.toFloat(), high.toFloat())
        }
        limitChips.entries.firstOrNull { it.value == draft.limit }?.let { ui.limitChips.check(it.key) }
        syncLabels()
    }

    private fun syncLabels() {
        val any = activity.getString(R.string.filters_any)
        for ((metric, row) in rows) {
            val bound = draft.bound(metric)
            row.metricRange.text = StockFormat.range(metric, bound, any)
            val active = bound.isActive
            if (flat) {
                // The flat look's pill: the accent once a range is set, a faint grey while any value goes.
                val accent = activity.flatAccent()
                row.metricRange.backgroundTintList =
                    ColorStateList.valueOf(if (active) accent.withAlpha(0.18f) else Color.WHITE.withAlpha(0.06f))
                row.metricRange.setTextColor(if (active) accent else activity.flatMuted())
                continue
            }
            row.metricRange.backgroundTintList = ColorStateList.valueOf(
                ContextCompat.getColor(activity, if (active) R.color.primary_container else R.color.neutral_container),
            )
            row.metricRange.setTextColor(
                ContextCompat.getColor(activity, if (active) R.color.on_primary_container else R.color.text_secondary),
            )
        }
        val capBound = draft.bound(Metric.MARKET_CAP)
        for ((chip, bound) in presetChips) chip.isChecked = bound == capBound
        for ((weeks, chip) in weekChips) chip.isChecked = weeks == draft.rangeWeeks

        val scanned = pool
        if (state.results.isEmpty()) {
            ui.filterSummary.setText(R.string.filters_summary_idle)
            ui.filterApply.setText(R.string.filters_apply)
        } else {
            val matching = scanned.count(draft::accepts)
            val shown = if (draft.limit > 0) minOf(matching, draft.limit) else matching
            ui.filterSummary.text = activity.getString(
                R.string.filters_summary,
                StockFormat.count(matching),
                StockFormat.count(scanned.size),
            )
            ui.filterApply.text = activity.getString(R.string.filters_apply_count, StockFormat.count(shown))
        }
        ui.filterReset.isEnabled = draft != resetDraft()
    }

    private fun label(metric: Metric, position: Int): String = when (position) {
        0 -> activity.getString(R.string.slider_no_min)
        metric.lastPosition -> activity.getString(R.string.slider_no_max)
        else -> StockFormat.metricValue(metric, metric.valueAt(position) ?: 0.0)
    }

    @StringRes
    private fun nameOf(metric: Metric): Int = when (metric) {
        Metric.MARKET_CAP -> R.string.metric_market_cap
        Metric.PRICE -> R.string.metric_price
        Metric.VOLUME -> R.string.metric_volume
        Metric.TURNOVER -> R.string.metric_turnover
        Metric.RVOL -> R.string.metric_rvol
        Metric.CHANGE -> R.string.metric_change
        Metric.FROM_HIGH -> R.string.metric_from_high
        Metric.BASE_RANGE -> R.string.metric_base_range
        Metric.VS_SMA50 -> R.string.metric_vs_sma50
        Metric.RSI -> R.string.metric_rsi
        Metric.MACD -> R.string.metric_macd
        Metric.MACD_CROSS -> R.string.metric_macd_cross
        Metric.SUPPORT_AGE -> R.string.metric_support_age
        Metric.SUPPORT_GAP -> R.string.metric_support_gap
        Metric.SCORE -> R.string.metric_score
    }

    private fun hintOf(metric: Metric): String? = when (metric) {
        Metric.MARKET_CAP -> when {
            state.marketCapDate != null -> activity.getString(
                R.string.hint_market_cap_date,
                state.marketCapDate.format(DateTimeFormatter.ofPattern("d MMM yyyy", Locale.forLanguageTag("en-IN"))),
            )
            state.phase == ScanPhase.DONE -> activity.getString(R.string.hint_market_cap_missing)
            else -> activity.getString(R.string.hint_market_cap)
        }
        Metric.VOLUME -> activity.getString(R.string.hint_volume)
        Metric.TURNOVER -> activity.getString(R.string.hint_turnover)
        Metric.RVOL -> activity.getString(R.string.hint_rvol)
        Metric.FROM_HIGH -> activity.getString(R.string.hint_from_high)
        Metric.BASE_RANGE -> activity.getString(R.string.hint_base_range)
        Metric.VS_SMA50 -> activity.getString(R.string.hint_vs_sma50)
        Metric.RSI -> activity.getString(R.string.hint_rsi)
        Metric.MACD -> activity.getString(R.string.hint_macd)
        Metric.MACD_CROSS -> activity.getString(R.string.hint_macd_cross)
        Metric.SUPPORT_AGE -> activity.getString(R.string.hint_support_age)
        Metric.SUPPORT_GAP -> activity.getString(R.string.hint_support_gap)
        Metric.SCORE -> activity.getString(
            R.string.hint_score_mix,
            StockFormat.metricValue(Metric.SCORE, StockFormat.STRONG_SCORE),
            StockFormat.metricValue(Metric.SCORE, StockFormat.WATCH_SCORE),
        )
        else -> null
    }

    private companion object {
        /** The apply button's corners (sheet_filters.xml, app:cornerRadius). */
        const val APPLY_CORNER_DP = 16f

        val MARKET_CAP_PRESETS = listOf(
            R.string.cap_micro to Bound(max = 500.0),
            R.string.cap_small to Bound(min = 500.0, max = 5_000.0),
            R.string.cap_mid to Bound(min = 5_000.0, max = 20_000.0),
            R.string.cap_large to Bound(min = 20_000.0),
        )
    }
}
