package com.rohit.share_scan

import android.content.Context
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButton
import com.rohit.share_scan.databinding.ItemHelpBinding
import com.rohit.share_scan.databinding.SheetListBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.format.DateTimeFormatter

/** How an intraday call ([IntradayPlan]) reads, in the detail sheet and the intraday list. */
object IntradayText {
    private val TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
    private val DAY: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM")

    /** When the deciding candle closed, "10:20" IST. */
    fun time(plan: IntradayPlan): String =
        plan.triggeredAt?.let { MarketClock.timeOf(it + IntradayPlan.CANDLE_MINUTES * 60).format(TIME) }.orEmpty()

    /** The call in full: what to do, at what price, with its stop and targets, and what has happened since. */
    fun describe(context: Context, plan: IntradayPlan, today: java.time.LocalDate = MarketClock.now().toLocalDate()): String {
        val p = StockFormat::price
        val past = if (plan.day != today) context.getString(R.string.horizon_past, plan.day.format(DAY)) + " " else ""
        val body = when (plan.side) {
            IntradayPlan.Side.FORMING -> context.getString(R.string.horizon_forming, p(plan.rangeLow), p(plan.rangeHigh))
            IntradayPlan.Side.WAIT -> context.getString(R.string.horizon_wait, p(plan.price), p(plan.rangeLow), p(plan.rangeHigh))
            IntradayPlan.Side.BUY, IntradayPlan.Side.SELL -> {
                val call = context.getString(
                    if (plan.side == IntradayPlan.Side.BUY) R.string.horizon_buy else R.string.horizon_sell,
                    p(plan.entry ?: 0.0),
                    time(plan),
                    p(plan.stop ?: 0.0),
                    p(plan.targets[0]),
                    p(plan.targets[1]),
                )
                (listOf(call) + notes(context, plan)).joinToString("\n")
            }
        }
        return past + body
    }

    /** Too late to enter, and the first level reached since. */
    fun notes(context: Context, plan: IntradayPlan): List<String> = listOfNotNull(
        context.getString(R.string.horizon_late).takeIf { plan.late },
        when (plan.outcome) {
            IntradayPlan.Outcome.TARGET_1 -> R.string.horizon_target1
            IntradayPlan.Outcome.TARGET_2 -> R.string.horizon_target2
            IntradayPlan.Outcome.STOPPED -> R.string.horizon_stopped
            IntradayPlan.Outcome.OPEN -> null
        }?.let(context::getString),
    )
}

/**
 * The app's own intraday picks ([IntradayPicks]): among the latest scan's most traded stocks, those whose
 * opening-range breakout today calls a buy and those that call a sell, strongest first, each with its entry, stop
 * and targets; a tap opens the stock. "फिर से जाँचें" reads the candles again (each kept a minute).
 */
class IntradaySheet(
    private val activity: AppCompatActivity,
    private val results: List<ScanResult>,
    private val onStock: (ScanResult) -> Unit,
    private val onBack: () -> Unit,
) {
    private val ui = SheetListBinding.inflate(activity.layoutInflater)
    private var job: Job? = null

    fun show(): BottomSheetDialog {
        ui.listTitle.setText(R.string.intraday_title)
        val universe = IntradayPicks.universe(results)
        ui.listSubtitle.text = activity.getString(R.string.intraday_subtitle, universe.size)
        val dialog = activity.showSheet(ui.root, ui.sheetTopBar, onBack, fullHeight = true)
        dialog.setOnDismissListener { job?.cancel() }
        if (universe.isEmpty()) {
            note(activity.getString(R.string.intraday_title), activity.getString(R.string.intraday_no_scan))
        } else {
            load(universe)
        }
        return dialog
    }

    private fun load(universe: List<ScanResult>) {
        job?.cancel()
        ui.listItems.removeAllViews()
        val status = text(activity.getString(R.string.intraday_checking, 0, universe.size), R.color.text_secondary)
        job = activity.lifecycleScope.launch {
            val checked = mutableListOf<IntradayPicks.Pick>()
            var answered = 0
            universe.chunked(CONCURRENT).forEach { batch ->
                val answers = withContext(Dispatchers.IO) {
                    batch.map { stock -> async { stock to runCatching { IntradayRepository.plan(stock) }.getOrNull() } }.awaitAll()
                }
                answers.forEach { (stock, answer) ->
                    if (answer != null) answered++
                    answer?.plan?.let { checked += IntradayPicks.Pick(stock, it) }
                }
                status.text = activity.getString(R.string.intraday_checking, answered, universe.size)
            }
            ui.listItems.removeAllViews()
            show(universe, IntradayPicks.of(checked), answered, checked.firstOrNull()?.plan?.day)
        }
    }

    private fun show(universe: List<ScanResult>, picks: IntradayPicks.Picks, answered: Int, day: java.time.LocalDate?) {
        if (answered == 0) {
            note(activity.getString(R.string.intraday_title), activity.getString(R.string.intraday_failed))
        } else {
            val now = MarketClock.now()
            when {
                MarketClock.phase(now) == MarketClock.Phase.BEFORE_OPEN || (day == null && MarketClock.isOpen(now)) ->
                    text(activity.getString(R.string.intraday_before), R.color.warn)
                day != null && day != now.toLocalDate() ->
                    text(activity.getString(R.string.intraday_closed, day.format(DateTimeFormatter.ofPattern("d MMM"))), R.color.warn)
                now.toLocalTime() > IntradayPlan.LAST_ENTRY -> text(activity.getString(R.string.intraday_after_cutoff), R.color.warn)
            }
            section(activity.getString(R.string.intraday_buys, picks.buys.size), picks.buys, R.color.bull)
            section(activity.getString(R.string.intraday_sells, picks.sells.size), picks.sells, R.color.bear)
            if (picks.done.isNotEmpty()) section(activity.getString(R.string.intraday_done, picks.done.size), picks.done, R.color.text_secondary)
            if (picks.waiting > 0) text(activity.getString(R.string.intraday_waiting, picks.waiting), R.color.text_secondary)
        }
        val refresh = MaterialButton(activity, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            setText(R.string.intraday_refresh)
            setOnClickListener { load(universe) }
        }
        ui.listItems.addView(refresh, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = (16 * activity.resources.displayMetrics.density).toInt()
        })
    }

    private fun section(title: String, picks: List<IntradayPicks.Pick>, colour: Int) {
        text(title, colour, bold = true, top = 18)
        if (picks.isEmpty()) {
            text(activity.getString(R.string.intraday_none), R.color.text_secondary)
            return
        }
        picks.forEach { pick ->
            val plan = pick.plan
            val card = ItemHelpBinding.inflate(activity.layoutInflater, ui.listItems, true)
            card.helpTitle.text = activity.getString(
                if (plan.side == IntradayPlan.Side.BUY) R.string.intraday_row_buy else R.string.intraday_row_sell,
                pick.stock.symbol,
                StockFormat.price(plan.entry ?: 0.0),
                IntradayText.time(plan),
            )
            card.helpTitle.setTextColor(ContextCompat.getColor(activity, colour))
            card.helpBody.text = (
                listOf(
                    activity.getString(
                        R.string.intraday_row,
                        StockFormat.price(plan.stop ?: 0.0),
                        StockFormat.price(plan.targets[0]),
                        StockFormat.price(plan.targets[1]),
                        StockFormat.price(plan.price),
                    ),
                ) + IntradayText.notes(activity, plan)
                ).joinToString("\n")
            card.root.setOnClickListener { onStock(pick.stock) }
        }
    }

    private fun text(value: String, colour: Int, bold: Boolean = false, top: Int = 10): TextView {
        val view = TextView(activity).apply {
            text = value
            setTextColor(ContextCompat.getColor(activity, colour))
            textSize = if (bold) 16f else 13.5f
            if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
        }
        ui.listItems.addView(view, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = (top * activity.resources.displayMetrics.density).toInt()
        })
        return view
    }

    private fun note(title: String, body: String) {
        val card = ItemHelpBinding.inflate(activity.layoutInflater, ui.listItems, true)
        card.helpTitle.text = title
        card.helpBody.text = body
        card.helpBody.isVisible = true
    }

    private companion object {
        /** Stocks asked for at once; Yahoo's pace ([IntradayRepository]) still holds. */
        const val CONCURRENT = 6
    }
}
