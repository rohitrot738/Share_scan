package com.rohit.share_scan

import android.graphics.Typeface
import android.view.Gravity
import android.view.ViewGroup
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButton
import com.rohit.share_scan.databinding.SheetListBinding
import java.util.Locale
import kotlin.math.abs

/** Footprint and order-flow views over a user-imported normalized trade file. No live feed is implied. */
internal class OrderFlowSheet(
    private val activity: AppCompatActivity,
    private val dataset: OrderFlowDataset?,
    private val onImport: () -> Unit,
    private val onBack: (() -> Unit)?,
) {
    private val ui = SheetListBinding.inflate(activity.layoutInflater)
    private var scannerTab = false
    private var selectedSymbol = dataset?.symbols?.firstOrNull()

    fun show(): BottomSheetDialog {
        ui.listTitle.setText(R.string.orderflow_title)
        ui.listSubtitle.setText(R.string.orderflow_subtitle)
        render()
        return activity.showSheet(ui.root, ui.sheetTopBar, onBack, fullHeight = true)
    }

    private fun render() {
        val content = ui.listItems
        content.removeAllViews()
        val actions = row()
        actions.addView(button(activity.getString(R.string.orderflow_import), onImport))
        content.addView(actions, matchWrap())

        val status = text(
            if (dataset == null) activity.getString(R.string.orderflow_feed_unavailable)
            else activity.getString(R.string.orderflow_loaded, dataset.sourceName, dataset.symbols.size, dataset.tradeCount),
            13f,
        )
        status.setTextColor(ContextCompat.getColor(activity, if (dataset == null) R.color.warn else R.color.text_secondary))
        status.setPadding(dp(14), dp(10), dp(14), dp(10))
        content.addView(status, matchWrap())

        val tabs = row()
        tabs.addView(button(activity.getString(R.string.orderflow_tab_footprint), { scannerTab = false; render() }, !scannerTab))
        tabs.addView(button(activity.getString(R.string.orderflow_tab_scanner), { scannerTab = true; render() }, scannerTab))
        content.addView(tabs, matchWrap())

        if (dataset == null) {
            content.addView(text(activity.getString(R.string.orderflow_empty_title), 18f, true), matchWrap())
            content.addView(text(activity.getString(R.string.orderflow_empty_body), 14f), matchWrap())
            content.addView(text("CSV columns: symbol,timestamp,price,quantity,aggressor,trade_id", 12f), matchWrap())
            return
        }
        if (scannerTab) renderScanner(content) else renderFootprint(content)
    }

    private fun renderFootprint(content: LinearLayout) {
        val data = dataset ?: return
        val symbols = data.symbols
        if (selectedSymbol !in symbols) selectedSymbol = symbols.firstOrNull()
        val picker = HorizontalScrollView(activity).apply { isHorizontalScrollBarEnabled = false }
        val choices = row()
        symbols.take(80).forEach { symbol ->
            choices.addView(button(symbol, { selectedSymbol = symbol; render() }, selectedSymbol == symbol))
        }
        picker.addView(choices)
        content.addView(picker, matchWrap())

        val bars = data.barsBySymbol[selectedSymbol].orEmpty()
        val latest = bars.lastOrNull()
        content.addView(
            text(
                if (latest == null) activity.getString(R.string.orderflow_no_trades)
                else activity.getString(
                    R.string.orderflow_chart_summary,
                    latest.delta,
                    String.format(Locale.US, "%+.1f%%", latest.deltaPercent),
                    latest.totalVolume,
                    latest.poc?.stripTrailingZeros()?.toPlainString() ?: "—",
                ),
                14f,
                true,
            ),
            matchWrap(),
        )
        val chart = FootprintCanvasView(activity).apply {
            setBars(bars)
            contentDescription = "Bid and ask executed volume by price level; yellow outline marks POC"
        }
        content.addView(chart, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(310)))
        content.addView(
            text(activity.getString(R.string.orderflow_chart_note), 12f),
            matchWrap(),
        )
    }

    private fun renderScanner(content: LinearLayout) {
        val data = dataset ?: return
        content.addView(text(activity.getString(R.string.orderflow_scanner_source), 13f, true), matchWrap())
        val ranked = data.barsBySymbol.mapNotNull { (symbol, bars) -> bars.lastOrNull()?.let { symbol to it } }
            .sortedByDescending { abs(it.second.deltaPercent) }
        ranked.forEachIndexed { index, (symbol, bar) ->
            val side = when {
                bar.delta > 0 -> "BUY +"
                bar.delta < 0 -> "SELL −"
                else -> "BALANCED"
            }
            val line = text(
                String.format(
                    Locale.US,
                    "%02d  %-14s  %s  %+.1f%%   Δ %d   Vol %d",
                    index + 1, symbol.take(14), side, bar.deltaPercent, bar.delta, bar.totalVolume,
                ),
                13f,
                index == 0,
            )
            line.setPadding(dp(10), dp(12), dp(8), dp(12))
            content.addView(line, matchWrap())
        }
        if (ranked.isEmpty()) content.addView(text(activity.getString(R.string.orderflow_no_trades), 14f), matchWrap())
    }

    private fun row() = LinearLayout(activity).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }

    private fun button(label: String, action: () -> Unit, selected: Boolean = false) =
        MaterialButton(activity).apply {
            text = label
            isAllCaps = false
            textSize = 12f
            setOnClickListener { action() }
            if (selected) setTextColor(ContextCompat.getColor(activity, R.color.bull))
        }

    private fun text(value: String, size: Float, bold: Boolean = false) = TextView(activity).apply {
        text = value
        textSize = size
        setTextColor(ContextCompat.getColor(activity, R.color.text_primary))
        if (bold) setTypeface(typeface, Typeface.BOLD)
        setPadding(dp(8), dp(6), dp(8), dp(6))
    }

    private fun matchWrap() = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT,
    ).apply { bottomMargin = dp(8) }

    private fun dp(value: Int) = (value * activity.resources.displayMetrics.density).toInt()
}
