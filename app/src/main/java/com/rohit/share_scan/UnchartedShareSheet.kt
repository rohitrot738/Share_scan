package com.rohit.share_scan

import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.rohit.share_scan.databinding.SheetListBinding
import com.rohit.share_scan.databinding.ViewOpenAppsBinding
import kotlin.math.roundToInt

/**
 * A share no chart could be had for (one the scan listed but Yahoo did not chart, or one from बड़े पैसे की चाल the
 * list lacks): its name, why (a new listing with fewer sessions than the scan's chart needs, or no chart on Yahoo), a
 * new listing's closes so far as a small chart with its first, latest, highest and lowest close, the buttons that
 * open it in a trading app or on the chart site ([OpenApps]), then its बड़े पैसे की चाल card ([BigMoneyCard]) when
 * the evening file has it; the closes and the card from NSE's files alone.
 */
class UnchartedShareSheet(
    private val activity: AppCompatActivity,
    private val book: BigMoneyBook,
    private val stock: ScanResult,
    private val chartSite: ChartSite,
) {
    fun show() {
        val ui = SheetListBinding.inflate(activity.layoutInflater)
        val symbol = stock.symbol
        ui.listTitle.text = symbol
        val entry = book.of(symbol)
        val reason = when {
            entry?.tooNewToChart == true -> activity.getString(R.string.big_money_share_new, entry.sessions ?: 0, BreakoutSignals.MIN_SESSIONS)
            entry != null -> activity.getString(R.string.big_money_share_no_chart)
            else -> activity.getString(R.string.uncharted_share_reason)
        }
        ui.listSubtitle.text = listOfNotNull(stock.name.takeIf { it.isNotBlank() }, reason).joinToString("\n")
        // A listing too new for the scan's chart: its closes so far, drawn from NSE's own files.
        val closes = entry?.closes.orEmpty().filterNotNull()
        if (closes.size >= 2) {
            val dp = activity.resources.displayMetrics.density
            val up = closes.last() >= closes.first()
            ui.listItems.addView(
                SparklineView(activity).apply {
                    values = closes
                    color = ContextCompat.getColor(activity, if (up) R.color.bull else R.color.bear)
                },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (96 * dp).roundToInt()).apply { topMargin = (12 * dp).roundToInt() },
            )
            ui.listItems.addView(
                TextView(activity).apply {
                    text = activity.getString(
                        R.string.big_money_share_prices,
                        closes.size,
                        StockFormat.price(closes.first()),
                        StockFormat.price(closes.last()),
                        StockFormat.signedChange((closes.last() / closes.first() - 1.0) * 100.0),
                        StockFormat.price(closes.max()),
                        StockFormat.price(closes.min()),
                    )
                    textSize = 13f
                    setTextColor(ContextCompat.getColor(activity, R.color.text_primary))
                },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = (8 * dp).roundToInt() },
            )
        }
        // The share in a trading app or on the chart site, as the detail of a charted share has it.
        val apps = ViewOpenAppsBinding.inflate(activity.layoutInflater, ui.listItems, false)
        OpenApps.bind(activity, stock, chartSite, apps.openChart, apps.openGroww, apps.openAngelOne, apps.openZerodha)
        val dp = activity.resources.displayMetrics.density
        ui.listItems.addView(apps.root, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = (14 * dp).roundToInt() })
        if (entry != null) {
            val card = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
            ui.listItems.addView(card, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            BigMoneyCard(activity, book, symbol).bind(card)
        }
        activity.showSheet(ui.root, ui.sheetTopBar, onBack = {}, fullHeight = true)
    }
}
