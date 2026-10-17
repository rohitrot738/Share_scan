package com.rohit.share_scan

import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.res.ColorStateList
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.Toast
import androidx.annotation.ColorInt
import androidx.annotation.ColorRes
import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.core.view.isVisible
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.rohit.share_scan.databinding.ItemDetailLevelBinding
import com.rohit.share_scan.databinding.ItemDetailScoreBinding
import com.rohit.share_scan.databinding.ItemDetailStatBinding
import com.rohit.share_scan.databinding.SheetStockDetailBinding
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Bottom sheet with everything one scan knows about a stock: header, price chart with the trade-plan
 * levels, the [TradePlan], how the score was built and the key numbers, plus a TradingView button.
 * Sections whose data the scan did not provide (no [PriceStructure], no score parts) stay hidden.
 */
class StockDetailSheet(
    private val activity: AppCompatActivity,
    private val stock: ScanResult,
    private val chartSite: ChartSite = ChartSite.TRADINGVIEW,
) {
    private val dialog = BottomSheetDialog(activity)
    private val ui = SheetStockDetailBinding.inflate(activity.layoutInflater)
    private val structure: PriceStructure? = stock.structure
    private val plan: TradePlan? = structure?.let { TradePlan.of(stock.price, it) }

    fun show(): BottomSheetDialog {
        bindHeader()
        bindChart()
        bindPlan()
        bindScore()
        bindStats()
        ui.detailNoStructure.isVisible = structure == null
        ui.detailOpenChart.text = if (chartSite == ChartSite.SCREENER) {
            activity.getString(R.string.detail_open_screener)
        } else {
            activity.getString(R.string.detail_open_chart_on, activity.getString(chartSite.label))
        }
        ui.detailOpenChart.setOnClickListener { openChart() }

        val height = (activity.resources.displayMetrics.heightPixels * 0.9f).toInt()
        dialog.setContentView(ui.root, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, height))
        dialog.behavior.state = BottomSheetBehavior.STATE_EXPANDED
        dialog.behavior.skipCollapsed = true
        dialog.show()
        return dialog
    }

    private fun bindHeader() {
        val (tierLabel, tierColor, tierContainer) = when (StockFormat.tier(stock.score)) {
            StockFormat.Tier.STRONG -> Triple(R.string.tier_strong, R.color.bull, R.color.bull_container)
            StockFormat.Tier.WATCH -> Triple(R.string.tier_watch, R.color.warn, R.color.warn_container)
            StockFormat.Tier.WEAK -> Triple(R.string.tier_weak, R.color.neutral, R.color.neutral_container)
        }
        ui.detailSymbol.text = stock.symbol
        ui.detailExchange.setText(if (stock.sme) R.string.detail_exchange_sme else R.string.detail_exchange)
        ui.detailName.isVisible = stock.name.isNotBlank()
        ui.detailName.text = StockSearch.shortName(stock.name)
        ui.detailPrice.text = rupees(stock.price)
        ui.detailChange.text = activity.getString(R.string.item_change, StockFormat.change(stock.change5d))
        ui.detailChange.setTextColor(color(if (stock.change5d >= 0) R.color.bull else R.color.bear))

        ui.detailTier.setText(tierLabel)
        ui.detailTier.setTextColor(color(tierColor))
        ui.detailTier.backgroundTintList = ColorStateList.valueOf(color(tierContainer))
        ui.detailScoreBar.setIndicatorColor(color(tierColor))
        ui.detailScoreBar.setProgressCompat((stock.score.coerceIn(0.0, 100.0) * 10).roundToInt(), false)
        ui.detailScore.text = activity.getString(R.string.detail_score, StockFormat.score(stock.score))
        ui.detailScore.setTextColor(color(tierColor))
    }

    private fun bindChart() {
        val structure = structure
        val closes = structure?.closes.orEmpty().filter { it.isFinite() && it > 0.0 }
        if (structure == null || closes.size < 2) {
            ui.detailChartSection.isVisible = false
            return
        }
        val last = closes.last()
        val periodChange = (last / closes.first() - 1.0) * 100.0
        val trend = if (periodChange >= 0) R.color.bull else R.color.bear
        ui.detailChartPeriod.text = activity.getString(R.string.detail_chart_period, closes.size, StockFormat.change(periodChange))
        ui.detailChartPeriod.setTextColor(color(trend))

        // Most important first: the chart drops a later level that would sit on top of an earlier one, which
        // happens to the 20D high when the entry is the breakout trigger just above it. Target 3 stays in the
        // plan card only: at 4R it would squeeze the price line into a thin band.
        val plan = plan
        val levels = buildList {
            if (plan != null) {
                add(level(plan.entry, R.string.detail_chart_entry, R.color.brand_primary))
                add(level(plan.stop, R.string.detail_chart_stop, R.color.bear))
                plan.targets.take(CHART_TARGETS).forEachIndexed { index, target ->
                    add(
                        PriceChartView.Level(
                            target,
                            activity.getString(R.string.detail_chart_target, index + 1, chartPrice(target)),
                            color(R.color.bull),
                        ),
                    )
                }
            }
            if (structure.high20.isFinite() && structure.high20 > 0.0) {
                add(level(structure.high20, R.string.detail_chart_high20, R.color.warn))
            }
        }
        ui.detailChart.setData(closes, levels, color(trend))
        ui.detailChart.contentDescription = activity.getString(
            R.string.detail_chart_description,
            stock.symbol,
            closes.size,
            StockFormat.price(closes.min()),
            StockFormat.price(closes.max()),
            StockFormat.price(last),
        )
    }

    private fun level(value: Double, @StringRes label: Int, @ColorRes colorId: Int) =
        PriceChartView.Level(value, activity.getString(label, chartPrice(value)), color(colorId))

    private fun bindPlan() {
        val plan = plan
        if (plan == null) {
            ui.detailPlanSection.isVisible = false
            return
        }
        val breakout = plan.entryKind == TradePlan.EntryKind.BREAKOUT
        ui.detailPlanRule.setText(if (breakout) R.string.detail_plan_rule_breakout else R.string.detail_plan_rule_market)

        addLevel(
            plan,
            plan.entry,
            activity.getString(R.string.detail_plan_entry),
            activity.getString(if (breakout) R.string.detail_plan_entry_breakout else R.string.detail_plan_entry_market),
            accent = R.color.brand_primary,
            container = R.color.primary_container,
            onContainer = R.color.on_primary_container,
        )
        val stopNote = when (plan.stopKind) {
            TradePlan.StopKind.STRUCTURE -> R.string.detail_plan_stop_structure
            TradePlan.StopKind.MAX_ATR -> R.string.detail_plan_stop_max_atr
            TradePlan.StopKind.MIN_ATR -> R.string.detail_plan_stop_min_atr
        }
        addLevel(
            plan,
            plan.stop,
            activity.getString(R.string.detail_plan_stop),
            activity.getString(stopNote),
            accent = R.color.bear,
            container = R.color.bear_container,
            onContainer = R.color.bear,
        )
        plan.targets.forEachIndexed { index, target ->
            addLevel(
                plan,
                target,
                activity.getString(R.string.detail_plan_target, index + 1),
                activity.getString(R.string.detail_plan_target_note, StockFormat.price(target - plan.entry)),
                accent = R.color.bull,
                container = R.color.bull_container,
                onContainer = R.color.bull,
            )
        }

        ui.detailPlanRisk.text = activity.getString(R.string.detail_plan_risk, StockFormat.price(plan.risk), percent(plan.riskPercent))
        ui.detailPlanExtended.isVisible = plan.isExtended
        if (plan.isExtended) {
            ui.detailPlanExtended.text = activity.getString(
                R.string.detail_plan_extended,
                String.format(Locale.US, "%.1f", plan.extensionAtr),
            )
        }
    }

    private fun addLevel(
        plan: TradePlan,
        value: Double,
        name: String,
        note: String,
        @ColorRes accent: Int,
        @ColorRes container: Int,
        @ColorRes onContainer: Int,
    ) {
        val row = ItemDetailLevelBinding.inflate(activity.layoutInflater, ui.detailLevels, true)
        val fromPrice = TradePlan.formatPercent(plan.fromPrice(value))
        val multiple = TradePlan.formatR(plan.rMultiple(value))
        row.levelDot.backgroundTintList = ColorStateList.valueOf(color(accent))
        row.levelName.text = name
        row.levelNote.text = note
        row.levelPrice.text = rupees(value)
        row.levelPercent.text = fromPrice
        row.levelR.text = multiple
        row.levelR.setTextColor(color(onContainer))
        row.levelR.backgroundTintList = ColorStateList.valueOf(color(container))
        row.root.contentDescription = activity.getString(
            R.string.detail_plan_level_description,
            name,
            StockFormat.price(value),
            fromPrice,
            multiple,
        )
    }

    private fun bindScore() {
        val parts = stock.parts.filter { it.value.isFinite() && it.weight.isFinite() }
        if (parts.isEmpty()) {
            ui.detailScoreSection.isVisible = false
            return
        }
        val totalWeight = parts.sumOf { it.weight.coerceAtLeast(0.0) }
        for (part in parts.sortedByDescending { it.weight }) {
            val row = ItemDetailScoreBinding.inflate(activity.layoutInflater, ui.detailScoreParts, true)
            val value = part.value.coerceIn(0.0, 100.0)
            val shown = String.format(Locale.US, "%.0f", value)
            val weight = if (totalWeight > 0.0) (part.weight.coerceAtLeast(0.0) / totalWeight * 100.0).roundToInt() else 0
            val name = partName(part.key)
            val tone = when (StockFormat.tier(value)) {
                StockFormat.Tier.STRONG -> R.color.bull
                StockFormat.Tier.WATCH -> R.color.warn
                StockFormat.Tier.WEAK -> R.color.neutral
            }
            row.partName.text = name
            row.partValue.text = shown
            row.partValue.setTextColor(color(tone))
            row.partBar.setIndicatorColor(color(tone))
            row.partBar.setProgressCompat(value.roundToInt(), false)
            row.partWeight.isVisible = totalWeight > 0.0
            row.partWeight.text = activity.getString(R.string.detail_part_weight, weight)
            row.root.contentDescription = activity.getString(R.string.detail_part_description, name, shown, weight)
        }
    }

    private fun partName(key: String): String = when (key) {
        ScoreKeys.LIQUIDITY -> activity.getString(R.string.detail_part_liquidity)
        ScoreKeys.VOLUME -> activity.getString(R.string.detail_part_volume)
        ScoreKeys.MOMENTUM -> activity.getString(R.string.detail_part_momentum)
        ScoreKeys.BREAKOUT -> activity.getString(R.string.detail_part_breakout)
        ScoreKeys.TREND -> activity.getString(R.string.detail_part_trend)
        else -> key.replaceFirstChar { it.uppercaseChar() }
    }

    private class Stat(
        val label: String,
        val value: String,
        val note: String? = null,
        @ColorRes val noteColor: Int = R.color.text_secondary,
    )

    private fun bindStats() {
        val stats = buildList {
            stock.marketCapCr?.takeIf { it.isFinite() && it > 0.0 }?.let {
                add(Stat(activity.getString(R.string.detail_stat_market_cap), StockFormat.crore(it)))
            }
            if (stock.volume.isFinite() && stock.volume > 0.0) {
                add(
                    Stat(
                        activity.getString(R.string.detail_stat_volume),
                        StockFormat.shares(stock.volume),
                        activity.getString(R.string.detail_stat_volume_note),
                    ),
                )
                add(
                    Stat(
                        activity.getString(R.string.detail_stat_turnover),
                        StockFormat.crore(stock.turnoverCr),
                        activity.getString(R.string.detail_stat_turnover_note),
                    ),
                )
            }
            if (stock.rvol.isFinite() && stock.rvol > 0.0) {
                add(
                    Stat(
                        activity.getString(R.string.detail_stat_rvol),
                        activity.getString(R.string.detail_stat_rvol_value, StockFormat.rvol(stock.rvol)),
                        activity.getString(R.string.detail_stat_rvol_note),
                        if (stock.rvol >= StockFormat.SPIKE_RVOL) R.color.warn else R.color.text_secondary,
                    ),
                )
            }
            structure?.let { addAll(structureStats(it)) }
        }
        if (stats.isEmpty()) {
            ui.detailStatsSection.isVisible = false
            return
        }
        for (pair in stats.chunked(2)) {
            val row = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                isBaselineAligned = false
            }
            ui.detailStats.addView(
                row,
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT),
            )
            pair.forEach { addStat(row, it) }
            // An odd last tile keeps half the width, like the tiles above it.
            if (pair.size == 1) {
                ItemDetailStatBinding.inflate(activity.layoutInflater, row, true).root.visibility = View.INVISIBLE
            }
        }
    }

    /** 20D high, ATR and moving averages: only the ones the scanner filled with usable numbers. */
    private fun structureStats(structure: PriceStructure): List<Stat> {
        val price = stock.price
        if (!(price > 0.0)) return emptyList()
        val stats = mutableListOf<Stat>()
        if (structure.high20.isFinite() && structure.high20 > 0.0) {
            val distance = (price / structure.high20 - 1.0) * 100.0
            val above = distance >= 0.0
            stats += Stat(
                activity.getString(R.string.detail_stat_high20),
                rupees(structure.high20),
                activity.getString(
                    if (above) R.string.detail_stat_high20_above else R.string.detail_stat_high20_below,
                    percent(distance),
                ),
                if (above) R.color.bull else R.color.text_secondary,
            )
        }
        if (structure.atr14.isFinite() && structure.atr14 > 0.0) {
            stats += Stat(
                activity.getString(R.string.detail_stat_atr),
                rupees(structure.atr14),
                activity.getString(R.string.detail_stat_atr_note, percent(structure.atr14 / price * 100.0)),
            )
        }
        movingAverage(R.string.detail_stat_sma20, structure.sma20)?.let { stats += it }
        structure.sma50?.let { sma -> movingAverage(R.string.detail_stat_sma50, sma)?.let { stats += it } }
        return stats
    }

    private fun movingAverage(@StringRes label: Int, sma: Double): Stat? {
        if (!sma.isFinite() || sma <= 0.0) return null
        val distance = (stock.price / sma - 1.0) * 100.0
        val above = distance >= 0.0
        return Stat(
            activity.getString(label),
            rupees(sma),
            activity.getString(if (above) R.string.detail_stat_sma_above else R.string.detail_stat_sma_below, percent(distance)),
            if (above) R.color.bull else R.color.bear,
        )
    }

    private fun addStat(row: LinearLayout, stat: Stat) {
        val tile = ItemDetailStatBinding.inflate(activity.layoutInflater, row, true)
        tile.statLabel.text = stat.label
        tile.statValue.text = stat.value
        tile.statNote.isVisible = stat.note != null
        tile.statNote.text = stat.note.orEmpty()
        tile.statNote.setTextColor(color(stat.noteColor))
        tile.root.contentDescription = listOfNotNull(stat.label, stat.value, stat.note).joinToString(", ")
    }

    private fun openChart() {
        try {
            activity.startActivity(Intent(Intent.ACTION_VIEW, chartSite.url(stock.symbol).toUri()))
        } catch (error: ActivityNotFoundException) {
            Toast.makeText(activity, R.string.open_chart_failed, Toast.LENGTH_SHORT).show()
        }
    }

    @ColorInt
    private fun color(@ColorRes id: Int): Int = ContextCompat.getColor(activity, id)

    private fun rupees(value: Double): String = activity.getString(R.string.item_price, StockFormat.price(value))

    /** Short labels for the chart pills: no paise once the price reaches four digits. */
    private fun chartPrice(value: Double): String =
        if (value >= 1_000.0) StockFormat.count(value.roundToInt()) else StockFormat.price(value)

    /** Unsigned, one decimal: the surrounding text says above or below. */
    private fun percent(value: Double): String = String.format(Locale.US, "%.1f%%", abs(value))

    private companion object {
        /** Targets drawn on the chart; all of them are listed in the plan card. */
        const val CHART_TARGETS = 2
    }
}
