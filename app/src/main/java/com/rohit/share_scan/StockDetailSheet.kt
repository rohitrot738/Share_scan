package com.rohit.share_scan

import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.text.SpannableString
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TableLayout
import android.widget.TableRow
import android.widget.TextView
import android.widget.Toast
import androidx.annotation.ColorInt
import androidx.annotation.ColorRes
import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.rohit.share_scan.Cr360Book.Fact
import com.rohit.share_scan.databinding.ItemCr360HistoryChartBinding
import com.rohit.share_scan.databinding.ItemCr360HistoryTableBinding
import com.rohit.share_scan.databinding.ItemDetailLevelBinding
import com.rohit.share_scan.databinding.ItemDetailScoreBinding
import com.rohit.share_scan.databinding.ItemDetailStatBinding
import com.rohit.share_scan.databinding.SheetStockDetailBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Bottom sheet with everything one scan knows about a stock: header, price chart with the trade-plan
 * levels, the [TradePlan], how the score was built and the key numbers, plus a chart button and buttons that open the
 * stock in a trading app ([BrokerOpener]).
 * Sections whose data the scan did not provide (no [PriceStructure], no score parts) stay hidden.
 */
class StockDetailSheet(
    private val activity: AppCompatActivity,
    private val stock: ScanResult,
    private val chartSite: ChartSite = ChartSite.TRADINGVIEW,
    /** On the watchlist, so its plan drives breakout, target and stop alerts. */
    private val starred: Boolean = false,
    /** Watchlist alerts are on in Settings. */
    private val alerts: Boolean = false,
    /** Where the stock's alerts stand ([WatchAlerts]); null before its first check. */
    private val alert: WatchAlerts.State? = null,
    /** The stock's 360CR from the daily file; null when the file does not have it (yet). */
    private val cr360: Cr360Book.Entry? = null,
) {
    private val dialog = BottomSheetDialog(activity)
    private val ui = SheetStockDetailBinding.inflate(activity.layoutInflater)
    private val structure: PriceStructure? = stock.structure
    private val plan: TradePlan? = structure?.let { TradePlan.of(stock.price, it) }
    private val brokers = BrokerOpener(activity)

    fun show(): BottomSheetDialog {
        bindHeader()
        bindChart()
        bindPlan()
        bindScore()
        bindCr360()
        bindCr360History()
        bindStats()
        ui.detailNoStructure.isVisible = structure == null
        val site = chartSite.forMarket(stock.market)
        val siteBroker = site.broker
        ui.detailOpenChart.text = when {
            site == ChartSite.SCREENER -> activity.getString(R.string.detail_open_screener)
            siteBroker != null -> activity.getString(R.string.broker_open_description, activity.getString(siteBroker.label))
            else -> activity.getString(R.string.detail_open_chart_on, activity.getString(site.label))
        }
        if (siteBroker != null) {
            ui.detailOpenChart.icon = siteBroker.icon(activity)
            ui.detailOpenChart.iconTint = null
            ui.detailOpenChart.iconSize = activity.resources.getDimensionPixelSize(R.dimen.broker_icon)
        }
        ui.detailOpenChart.setOnClickListener { openChart(siteBroker) }
        // The other trading apps, one tap away; the one the chart button already opens is left out.
        listOf(
            ui.detailOpenGroww to Broker.GROWW,
            ui.detailOpenAngelOne to Broker.ANGEL_ONE,
            ui.detailOpenZerodha to Broker.ZERODHA,
        ).forEach { (button, broker) ->
            button.isVisible = broker != siteBroker
            button.icon = broker.icon(activity)
            button.contentDescription = activity.getString(R.string.broker_open_description, activity.getString(broker.label))
            button.setOnClickListener { brokers.open(broker, stock) }
        }

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
        ui.detailExchange.setText(
            when (stock.market) {
                Market.NSE -> R.string.detail_exchange
                Market.NSE_SME -> R.string.detail_exchange_sme
                Market.BSE -> R.string.detail_exchange_bse
            },
        )
        val name = listOf(StockSearch.shortName(stock.name), stock.sector).filter { it.isNotBlank() }.joinToString(" · ")
        ui.detailName.isVisible = name.isNotEmpty()
        ui.detailName.text = name
        ui.detailPrice.text = rupees(stock.price)
        val change = activity.getString(R.string.item_change, StockFormat.change(stock.change5d))
        ui.detailChange.text = if (stock.live) activity.getString(R.string.detail_live_change, change) else change
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
            stock.support?.let { add(level(it.level, R.string.detail_chart_support, R.color.neutral)) }
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
        val setup = BreakoutSetup.of(stock, plan)
        bindSetup(setup)

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
        if (plan.wideStop > 0.0) {
            addLevel(
                plan,
                plan.wideStop,
                activity.getString(R.string.detail_plan_wide_stop, multiple(TradePlan.WIDE_STOP_R)),
                activity.getString(R.string.detail_plan_wide_stop_note),
                accent = R.color.warn,
                container = R.color.warn_container,
                onContainer = R.color.warn,
            )
        }
        addLevel(
            plan,
            plan.quickTarget,
            activity.getString(R.string.detail_plan_quick_target, multiple(TradePlan.QUICK_TARGET_R)),
            activity.getString(R.string.detail_plan_target_note, StockFormat.price(plan.quickTarget - plan.entry)),
            accent = R.color.bull,
            container = R.color.bull_container,
            onContainer = R.color.bull,
            time = activity.getString(
                R.string.detail_plan_quick_time,
                plan.quickSessions.first,
                plan.quickSessions.last,
                setup.quickHits,
                setup.quickHitsWide,
            ),
            multiple = "+" + multiple(TradePlan.QUICK_TARGET_R),
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
                time = plan.targetSessions[index].let { days ->
                    activity.getString(R.string.detail_plan_target_time, days.first, days.last)
                },
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
        bindAlert()
    }

    /** How breakouts like this one did: on volume, STRONG the day before, or still to come. */
    private fun bindSetup(setup: BreakoutSetup.Kind) {
        val rvol = StockFormat.rvol(stock.rvol)
        val (text, tone) = when (setup) {
            BreakoutSetup.Kind.HIGH_PROBABILITY ->
                activity.getString(R.string.detail_setup_high, rvol, setup.quickHits, setup.quickHitsWide) to R.color.bull
            BreakoutSetup.Kind.VOLUME ->
                activity.getString(R.string.detail_setup_volume, rvol, setup.quickHits, setup.quickHitsWide) to R.color.bull
            BreakoutSetup.Kind.LOW_VOLUME ->
                activity.getString(R.string.detail_setup_low, rvol, setup.quickHits, setup.quickHitsWide) to R.color.warn
            BreakoutSetup.Kind.WAITING -> activity.getString(
                R.string.detail_setup_waiting,
                BreakoutSetup.Kind.VOLUME.quickHits,
                BreakoutSetup.Kind.VOLUME.quickHitsWide,
            ) to R.color.text_secondary
            BreakoutSetup.Kind.EXTENDED -> null to R.color.text_secondary
        }
        ui.detailPlanSetup.isVisible = text != null
        ui.detailPlanSetup.text = text.orEmpty()
        ui.detailPlanSetup.setTextColor(color(tone))
    }

    /** "0.75R", "1.5R": a multiple of the risk to two places where it has them, unlike [TradePlan.formatR]. */
    private fun multiple(r: Double): String = r.toBigDecimal().stripTrailingZeros().toPlainString() + "R"

    /** What the watchlist alert will do for this stock, or how to get one. */
    private fun bindAlert() {
        val state = alert
        val entry = state?.entry
        val stop = state?.stop
        val text = when {
            !alerts -> null
            !starred -> activity.getString(R.string.detail_alert_star)
            state != null && state.phase == WatchAlerts.Phase.IN_TRADE && entry != null && stop != null ->
                activity.getString(
                    R.string.detail_alert_trade,
                    StockFormat.price(entry),
                    StockFormat.price(stop),
                    state.hits,
                    state.targets.size,
                )
            entry != null -> activity.getString(R.string.detail_alert_waiting, StockFormat.price(entry))
            else -> activity.getString(R.string.detail_alert_soon)
        }
        ui.detailAlert.isVisible = text != null
        ui.detailAlert.text = text.orEmpty()
        if (text != null) {
            ui.detailAlert.setCompoundDrawablesRelativeWithIntrinsicBounds(R.drawable.ic_notifications_small, 0, 0, 0)
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
        time: String? = null,
        multiple: String = TradePlan.formatR(plan.rMultiple(value)),
    ) {
        val row = ItemDetailLevelBinding.inflate(activity.layoutInflater, ui.detailLevels, true)
        val fromPrice = TradePlan.formatPercent(plan.fromPrice(value))
        row.levelDot.backgroundTintList = ColorStateList.valueOf(color(accent))
        row.levelName.text = name
        row.levelNote.text = note
        row.levelTime.isVisible = time != null
        row.levelTime.text = time.orEmpty()
        row.levelTime.setTextColor(color(onContainer))
        row.levelPrice.text = rupees(value)
        row.levelPercent.text = fromPrice
        row.levelR.text = multiple
        row.levelR.setTextColor(color(onContainer))
        row.levelR.backgroundTintList = ColorStateList.valueOf(color(container))
        val description = activity.getString(
            R.string.detail_plan_level_description,
            name,
            StockFormat.price(value),
            fromPrice,
            multiple,
        )
        row.root.contentDescription =
            if (time == null) description else activity.getString(R.string.detail_plan_level_time_description, description, time)
    }

    private fun bindScore() {
        val parts = stock.parts.filter { it.value.isFinite() && it.weight.isFinite() }
        if (parts.isEmpty()) {
            ui.detailScoreSection.isVisible = false
            return
        }
        // The support bonus comes on top of the weighted parts, which share 100%; it shows how much it can add.
        val totalWeight = parts.filter { it.key != ScoreKeys.SUPPORT }.sumOf { it.weight.coerceAtLeast(0.0) }
        for (part in parts.sortedByDescending { it.weight }) {
            val bonus = part.key == ScoreKeys.SUPPORT
            val row = ItemDetailScoreBinding.inflate(activity.layoutInflater, ui.detailScoreParts, true)
            val value = part.value.coerceIn(0.0, 100.0)
            val shown = String.format(Locale.US, "%.0f", value)
            val weight = when {
                bonus -> (part.weight * 100.0).roundToInt()
                totalWeight > 0.0 -> (part.weight.coerceAtLeast(0.0) / totalWeight * 100.0).roundToInt()
                else -> 0
            }
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
            row.partWeight.text =
                activity.getString(if (bonus) R.string.detail_part_bonus else R.string.detail_part_weight, weight)
            row.root.contentDescription = activity.getString(R.string.detail_part_description, name, shown, weight)
        }
    }

    /** The 360CR card: score and verdict, how much data it had, its ten parts and the figures behind them. */
    private fun bindCr360() {
        val entry = cr360
        if (entry == null) {
            ui.detailCr360Score.text = activity.getString(R.string.stat_empty)
            ui.detailCr360State.isVisible = false
            ui.detailCr360Data.text = activity.getString(R.string.detail_cr360_missing)
            ui.detailCr360Facts.isVisible = false
            return
        }
        val stateLabel = entry.state.label
        val tone = entry.state.tone
        ui.detailCr360Score.text = entry.score?.let { activity.getString(R.string.detail_cr360_score, String.format(Locale.US, "%.0f", it)) }
            ?: activity.getString(R.string.stat_empty)
        ui.detailCr360Score.setTextColor(color(tone))
        ui.detailCr360State.text = activity.getString(stateLabel)
        ui.detailCr360State.setTextColor(color(tone))
        ui.detailCr360Data.text = activity.getString(
            R.string.detail_cr360_data,
            entry.confidence.roundToInt(),
            Cr360Format.month(entry.quarter),
            Cr360Format.day(entry.checked),
        )
        for (part in Cr360Book.Part.entries) {
            val value = entry.parts[part] ?: continue
            val row = ItemDetailScoreBinding.inflate(activity.layoutInflater, ui.detailCr360Parts, true)
            val name = activity.getString(part.label)
            val partTone = when (StockFormat.tier(value.toDouble())) {
                StockFormat.Tier.STRONG -> R.color.bull
                StockFormat.Tier.WATCH -> R.color.warn
                StockFormat.Tier.WEAK -> R.color.neutral
            }
            row.partName.text = name
            row.partValue.text = value.toString()
            row.partValue.setTextColor(color(partTone))
            row.partBar.setIndicatorColor(color(partTone))
            row.partBar.setProgressCompat(value.coerceIn(0, 100), false)
            row.partWeight.text = activity.getString(R.string.detail_part_weight, part.weight)
            row.root.contentDescription = activity.getString(R.string.detail_part_description, name, value.toString(), part.weight)
        }
        val facts = cr360Facts(entry.facts)
        ui.detailCr360Facts.isVisible = facts.isNotEmpty()
        ui.detailCr360Facts.text = facts.joinToString("\n") { "• $it" }
    }

    private fun cr360Facts(f: Map<Fact, Double>): List<String> {
        fun s(key: Fact, digits: Int = 1) = f[key]?.let { Cr360Format.number(it, digits) }
        fun signed(key: Fact, digits: Int = 1) = f[key]?.let { Cr360Format.signed(it, digits) }
        val lines = ArrayList<String>()
        val quarters = f[Fact.QUARTERS]?.roundToInt()
        val periods = activity.getString(if (f[Fact.HALF_YEARLY] == 1.0) R.string.cr360_half_years else R.string.cr360_quarters)
        f[Fact.PROFITABLE_QUARTERS]?.let { pq -> quarters?.let { lines += activity.getString(R.string.cr360_fact_profit, it, pq.roundToInt(), periods) } }
        if (quarters != null && (f[Fact.REVENUE_CHANGE] != null || f[Fact.PROFIT_CHANGE] != null)) {
            val dash = activity.getString(R.string.stat_empty)
            lines += activity.getString(R.string.cr360_fact_growth,
                signed(Fact.REVENUE_CHANGE, 0)?.let { "$it%" } ?: dash, signed(Fact.PROFIT_CHANGE, 0)?.let { "$it%" } ?: dash, quarters, periods)
        }
        listOfNotNull(
            s(Fact.NET_MARGIN)?.let { activity.getString(R.string.cr360_fact_margin, "$it%") },
            s(Fact.DEBT_TO_EQUITY, 2)?.let { activity.getString(R.string.cr360_fact_debt, it) },
        ).takeIf { it.isNotEmpty() }?.let { lines += it.joinToString(" · ") }
        listOfNotNull(
            s(Fact.PROMOTER, 2)?.let { activity.getString(R.string.cr360_fact_promoter, it, signed(Fact.PROMOTER_CHANGE, 2) ?: "0") },
            if (f[Fact.FII_CHANGE] != null || f[Fact.DII_CHANGE] != null) {
                val fii = signed(Fact.FII_CHANGE, 2) ?: "0"
                val dii = signed(Fact.DII_CHANGE, 2) ?: "0"
                val span = f[Fact.HOLDING_QUARTERS]?.roundToInt()
                if (span != null) activity.getString(R.string.cr360_fact_institutions_span, fii, dii, span)
                else activity.getString(R.string.cr360_fact_institutions, fii, dii)
            } else {
                null
            },
        ).takeIf { it.isNotEmpty() }?.let { lines += it.joinToString(" · ") }
        if (f[Fact.INSIDER_BUY_CR] != null || f[Fact.INSIDER_SELL_CR] != null) {
            lines += activity.getString(R.string.cr360_fact_insider, s(Fact.INSIDER_BUY_CR) ?: "0", s(Fact.INSIDER_SELL_CR) ?: "0")
        }
        if ((f[Fact.DEAL_BUY_CR] ?: 0.0) + (f[Fact.DEAL_SELL_CR] ?: 0.0) > 0.0) {
            lines += activity.getString(R.string.cr360_fact_deals, s(Fact.DEAL_BUY_CR) ?: "0", s(Fact.DEAL_SELL_CR) ?: "0")
        }
        listOfNotNull(
            s(Fact.PE)?.let { activity.getString(R.string.cr360_fact_pe, it) },
            s(Fact.HISTORY_PE, 0)?.let { activity.getString(R.string.cr360_fact_history_pe, it) },
        ).takeIf { it.isNotEmpty() }?.let { lines += it.joinToString(" · ") }
        // Fair value two ways, weighed alike in the score: from the sector's multiples and from the company's own.
        listOfNotNull(
            f[Fact.FAIR_VALUE]?.let { activity.getString(R.string.cr360_fair_sector, StockFormat.price(it), signed(Fact.UPSIDE, 0)?.let { u -> "$u%" } ?: "") },
            f[Fact.HISTORY_FAIR_VALUE]?.let {
                activity.getString(R.string.cr360_fair_history, StockFormat.price(it), signed(Fact.HISTORY_UPSIDE, 0)?.let { u -> "$u%" } ?: "")
            },
        ).takeIf { it.isNotEmpty() }?.let { lines += activity.getString(R.string.cr360_fact_fair_value, it.joinToString(" · ")) }
        listOfNotNull(
            s(Fact.POSITION_3Y, 0)?.let { activity.getString(R.string.cr360_fact_position, it) },
            s(Fact.VOLATILITY, 0)?.let { activity.getString(R.string.cr360_fact_volatility, it) },
        ).takeIf { it.isNotEmpty() }?.let { lines += it.joinToString(" · ") }
        return lines
    }

    /** The R/S history, fetched off the main thread: its file comes the first time a stock in it is opened. */
    private fun bindCr360History() {
        if (cr360 == null) {
            ui.detailCr360HistorySection.isVisible = false
            return
        }
        activity.lifecycleScope.launch {
            val history = withContext(Dispatchers.IO) { runCatching { Cr360Repository.history(activity, stock.symbol) }.getOrNull() }
            showCr360History(history)
        }
    }

    private fun showCr360History(history: Cr360History?) {
        if (history == null || (history.quarters.isEmpty() && history.holders.isEmpty() && history.years.isEmpty())) {
            ui.detailCr360HistoryStatus.setText(R.string.detail_cr360_history_missing)
            return
        }
        ui.detailCr360HistoryStatus.setText(R.string.detail_cr360_history_subtitle)
        if (history.holders.isNotEmpty()) addHolders(history.holders)
        if (history.quarters.isNotEmpty()) addQuarters(history.quarters)
        if (history.years.isNotEmpty()) addYears(history.years)
        history.chart?.let { addHistoryChart(it) }
    }

    /** Shareholding by quarter, newest first; FII and DII show their step from the quarter before (▲ bought, ▼ sold). */
    private fun addHolders(holders: List<Cr360History.Holding>) {
        val card = historyCard(R.string.cr360_history_holders_title)
        historyRow(
            card.historyTable, header = true,
            activity.getString(R.string.cr360_history_quarter),
            activity.getString(R.string.cr360_history_promoter),
            "FII",
            "DII",
            activity.getString(R.string.cr360_history_public),
        )
        for (i in holders.indices.reversed()) {
            val row = holders[i]
            val before = holders.getOrNull(i - 1)
            historyRow(
                card.historyTable, header = false,
                Cr360History.label(row.period),
                holding(row.promoter, null),
                holding(row.fii, before?.fii),
                holding(row.dii, before?.dii),
                holding(row.public, null),
            )
        }
        val first = holders.first()
        val last = holders.last()
        val summary = if (holders.size >= 2 && first.fii != null && last.fii != null && first.dii != null && last.dii != null) {
            "\n" + activity.getString(
                R.string.cr360_history_holders_summary,
                Cr360History.label(first.period), Cr360History.label(last.period),
                Cr360Format.number(first.fii, 2), Cr360Format.number(last.fii, 2), Cr360Format.signed(last.fii - first.fii, 2),
                Cr360Format.number(first.dii, 2), Cr360Format.number(last.dii, 2), Cr360Format.signed(last.dii - first.dii, 2),
            )
        } else {
            ""
        }
        card.historyNote.text = activity.getString(R.string.cr360_history_holders_note) + summary
        card.historyNote.isVisible = true
    }

    /** A holding in %, with its step from the quarter before under it when there is one. */
    private fun holding(value: Double?, before: Double?): CharSequence {
        if (value == null) return activity.getString(R.string.stat_empty)
        val text = SpannableStringBuilder(Cr360Format.number(value, 2))
        if (before != null) {
            val step = value - before
            val label = Cr360Format.step(step)
            val start = text.length
            text.append("\n").append(label)
            val tone = when {
                label == "0" -> R.color.text_secondary
                step > 0 -> R.color.bull
                else -> R.color.bear
            }
            text.setSpan(ForegroundColorSpan(color(tone)), start, text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            text.setSpan(RelativeSizeSpan(0.85f), start, text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        return text
    }

    /** Results by quarter (or half-year), newest first, in ₹ crore; a loss in red. */
    private fun addQuarters(quarters: List<Cr360History.Quarter>) {
        val halfYearly = cr360?.facts?.get(Fact.HALF_YEARLY) == 1.0
        val card = historyCard(if (halfYearly) R.string.cr360_history_halves_title else R.string.cr360_history_quarters_title)
        historyRow(
            card.historyTable, header = true,
            activity.getString(if (halfYearly) R.string.cr360_history_half else R.string.cr360_history_quarter),
            activity.getString(R.string.cr360_history_sales),
            activity.getString(R.string.cr360_history_profit),
            activity.getString(R.string.cr360_history_eps),
        )
        for (row in quarters.asReversed()) {
            historyRow(
                card.historyTable, header = false,
                Cr360History.label(row.period),
                amount(row.sales),
                amount(row.profit, signedTone = true),
                row.eps?.let { Cr360Format.number(it, 2) } ?: activity.getString(R.string.stat_empty),
            )
        }
    }

    /** Yearly results, cash flow and debt, newest first, in ₹ crore. */
    private fun addYears(years: List<Cr360History.Year>) {
        val card = historyCard(R.string.cr360_history_years_title)
        historyRow(
            card.historyTable, header = true,
            activity.getString(R.string.cr360_history_year),
            activity.getString(R.string.cr360_history_sales),
            activity.getString(R.string.cr360_history_profit),
            activity.getString(R.string.cr360_history_operating_cash),
            activity.getString(R.string.cr360_history_free_cash),
            activity.getString(R.string.cr360_history_debt),
        )
        for (row in years.asReversed()) {
            historyRow(
                card.historyTable, header = false,
                Cr360History.yearLabel(row.period),
                amount(row.sales),
                amount(row.profit, signedTone = true),
                amount(row.operatingCash, signedTone = true),
                amount(row.freeCash, signedTone = true),
                amount(row.debt),
            )
        }
    }

    /** Three years of month-end closes, with the highest and lowest month-end and today's. */
    private fun addHistoryChart(chart: Cr360History.Chart) {
        val months = chart.closes.mapIndexedNotNull { i, close ->
            close?.takeIf { it.isFinite() && it > 0.0 }?.let { Cr360History.monthAfter(chart.firstMonth, i) to it }
        }
        if (months.size < 2) return
        val card = ItemCr360HistoryChartBinding.inflate(activity.layoutInflater, ui.detailCr360History, true)
        val closes = months.map { it.second }
        val high = months.maxBy { it.second }
        val low = months.minBy { it.second }
        val trend = if (closes.last() >= closes.first()) R.color.bull else R.color.bear
        card.historyChart.setData(closes, emptyList(), color(trend))
        card.historyChartNote.text = activity.getString(
            R.string.cr360_history_chart_note,
            StockFormat.price(closes.last()),
            StockFormat.price(high.second), Cr360History.label(high.first),
            StockFormat.price(low.second), Cr360History.label(low.first),
        )
        card.historyChart.contentDescription = activity.getString(
            R.string.cr360_history_chart_description,
            Cr360History.label(months.first().first), Cr360History.label(months.last().first),
            StockFormat.price(low.second), StockFormat.price(high.second), StockFormat.price(closes.last()),
        )
    }

    private fun historyCard(@StringRes title: Int): ItemCr360HistoryTableBinding =
        ItemCr360HistoryTableBinding.inflate(activity.layoutInflater, ui.detailCr360History, true).also { it.historyTitle.setText(title) }

    /** One table row: the first cell is the period, the rest are figures aligned to the right. */
    private fun historyRow(table: TableLayout, header: Boolean, vararg cells: CharSequence) {
        val row = TableRow(activity)
        cells.forEachIndexed { index, cell ->
            row.addView(
                TextView(activity).apply {
                    text = cell
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, if (header) 11f else 12f)
                    setTextColor(color(if (header) R.color.text_secondary else R.color.text_primary))
                    if (header) setTypeface(typeface, Typeface.BOLD)
                    fontFeatureSettings = "tnum"
                    gravity = if (index == 0) Gravity.START or Gravity.CENTER_VERTICAL else Gravity.END or Gravity.CENTER_VERTICAL
                    val pad = (4 * resources.displayMetrics.density).roundToInt()
                    setPadding(pad, pad, pad, pad)
                },
            )
        }
        table.addView(row)
    }

    /** A ₹ crore figure; with [signedTone], a negative one (a loss, cash going out) in red. */
    private fun amount(value: Double?, signedTone: Boolean = false): CharSequence {
        if (value == null) return activity.getString(R.string.stat_empty)
        val text = Cr360Format.amount(value)
        if (!signedTone || value >= 0) return text
        return SpannableString(text).apply { setSpan(ForegroundColorSpan(color(R.color.bear)), 0, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE) }
    }

    private fun partName(key: String): String = when (key) {
        ScoreKeys.LIQUIDITY -> activity.getString(R.string.detail_part_liquidity)
        ScoreKeys.VOLUME -> activity.getString(R.string.detail_part_volume)
        ScoreKeys.MOMENTUM -> activity.getString(R.string.detail_part_momentum)
        ScoreKeys.BREAKOUT -> activity.getString(R.string.detail_part_breakout)
        ScoreKeys.TREND -> activity.getString(R.string.detail_part_trend)
        ScoreKeys.SUPPORT -> activity.getString(R.string.detail_part_support)
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
                        activity.getString(if (stock.live) R.string.detail_stat_volume_live_note else R.string.detail_stat_volume_note),
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
                        activity.getString(if (stock.live) R.string.detail_stat_rvol_live_note else R.string.detail_stat_rvol_note),
                        if (stock.rvol >= StockFormat.SPIKE_RVOL) R.color.warn else R.color.text_secondary,
                    ),
                )
            }
            stock.support?.let { floor ->
                val note = activity.getString(
                    if (floor.long) R.string.detail_stat_support_long_note else R.string.detail_stat_support_note,
                    StockFormat.supportAge(floor),
                    floor.touches,
                    percent(maxOf(0.0, floor.distancePct(stock.price))),
                )
                add(
                    Stat(
                        activity.getString(R.string.detail_stat_support),
                        rupees(floor.level),
                        note,
                        if (floor.long) R.color.bull else R.color.text_secondary,
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
        structure.rsi14?.takeIf { it.isFinite() }?.let { stats += rsiStat(it) }
        val macd = structure.macd?.takeIf { it.isFinite() }
        val signal = structure.macdSignal?.takeIf { it.isFinite() }
        if (macd != null && signal != null) stats += macdStat(macd, signal, structure.macdCrossAge)
        return stats
    }

    /** RSI (14) with the zone it is in: overbought, rising, weak or oversold. */
    private fun rsiStat(rsi: Double): Stat {
        val (note, tone) = when {
            rsi >= 70.0 -> R.string.detail_stat_rsi_overbought to R.color.warn
            rsi >= 50.0 -> R.string.detail_stat_rsi_strong to R.color.bull
            rsi >= 30.0 -> R.string.detail_stat_rsi_weak to R.color.text_secondary
            else -> R.string.detail_stat_rsi_oversold to R.color.warn
        }
        return Stat(activity.getString(R.string.detail_stat_rsi), StockFormat.score(rsi).removeSuffix(".0"), activity.getString(note), tone)
    }

    /** MACD against its signal line: rising or falling, when it last crossed above, and which side of zero it is. */
    private fun macdStat(macd: Double, signal: Double, crossAge: Int?): Stat {
        val above = macd > signal
        val cross = if (above && crossAge != null) {
            activity.getString(R.string.detail_stat_macd_crossed, StockFormat.sessionsAgo(crossAge))
        } else {
            activity.getString(R.string.detail_stat_macd_below)
        }
        val zero = activity.getString(if (macd >= 0.0) R.string.detail_stat_macd_zero_above else R.string.detail_stat_macd_zero_below)
        return Stat(
            activity.getString(R.string.detail_stat_macd),
            activity.getString(if (above) R.string.detail_stat_macd_bull else R.string.detail_stat_macd_bear),
            "$cross · $zero",
            if (above) R.color.bull else R.color.bear,
        )
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

    private fun openChart(broker: Broker?) {
        if (broker != null) {
            brokers.open(broker, stock)
            return
        }
        try {
            activity.startActivity(Intent(Intent.ACTION_VIEW, chartSite.url(stock.symbol, stock.market, stock.bseCode).toUri()))
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
