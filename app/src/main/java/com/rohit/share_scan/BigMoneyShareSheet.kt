package com.rohit.share_scan

import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.appcompat.app.AppCompatActivity
import com.rohit.share_scan.databinding.SheetListBinding

/**
 * A share from बड़े पैसे की चाल that the scan's list has no chart for: why (a new listing with fewer sessions than the
 * scan's chart needs, or no chart on Yahoo), then its बड़े पैसे की चाल card ([BigMoneyCard]), which needs NSE's files
 * alone.
 */
class BigMoneyShareSheet(
    private val activity: AppCompatActivity,
    private val book: BigMoneyBook,
    private val symbol: String,
) {
    fun show() {
        val ui = SheetListBinding.inflate(activity.layoutInflater)
        ui.listTitle.text = symbol
        val entry = book.of(symbol)
        ui.listSubtitle.text = if (entry?.tooNewToChart == true) {
            activity.getString(R.string.big_money_share_new, entry.sessions ?: 0, BreakoutSignals.MIN_SESSIONS)
        } else {
            activity.getString(R.string.big_money_share_no_chart)
        }
        val card = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        ui.listItems.addView(card, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        BigMoneyCard(activity, book, symbol).bind(card)
        activity.showSheet(ui.root, ui.sheetTopBar, onBack = {}, fullHeight = true)
    }
}
