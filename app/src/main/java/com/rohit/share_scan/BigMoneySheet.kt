package com.rohit.share_scan

import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.chip.ChipGroup
import com.rohit.share_scan.databinding.ChipChoiceBinding
import com.rohit.share_scan.databinding.ItemHelpBinding
import com.rohit.share_scan.databinding.SheetListBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Menu → बड़े पैसे की चाल: the evening's NSE data ([BigMoneyBook]) in four tabs: shares accumulating quietly, shares
 * churning, the deals netted over 30 sessions and the promoters' market trades over 90 days. Each tab says what it
 * counts and how its signal did over the past year; a tap on a share opens it. A fresh file is fetched as the sheet
 * opens when one is due.
 */
class BigMoneySheet(
    private val activity: AppCompatActivity,
    private var book: BigMoneyBook,
    private val onSymbol: (String) -> Unit,
    private val onBack: (() -> Unit)?,
) {
    enum class Tab(val title: Int, val body: Int, val records: List<String>) {
        ACCUMULATION(R.string.big_money_tab_acc, R.string.big_money_tab_acc_body, listOf("acc")),
        CHURN(R.string.big_money_tab_churn, R.string.big_money_tab_churn_body, listOf("churn")),
        DEALS(R.string.big_money_tab_deals, R.string.big_money_tab_deals_body, listOf("deal_buy", "deal_sell")),
        PROMOTER(R.string.big_money_tab_promoter, R.string.big_money_tab_promoter_body, listOf("promoter_buy", "promoter_sell")),
    }

    private val ui = SheetListBinding.inflate(activity.layoutInflater)
    private val dp = activity.resources.displayMetrics.density
    private var tab = Tab.ACCUMULATION
    private var checked = false

    fun show(): BottomSheetDialog {
        ui.listTitle.setText(R.string.big_money_title)
        val dialog = activity.showSheet(ui.root, ui.sheetTopBar, onBack, fullHeight = true)
        render()
        activity.lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                runCatching {
                    BigMoneyRepository.book(activity)
                    BigMoneyRepository.refreshIfStale(activity)
                }
            }
            checked = true
            book = BigMoneyRepository.current().takeIf { it.date != null } ?: book
            if (dialog.isShowing) render()
        }
        return dialog
    }

    private fun render() {
        ui.listItems.removeAllViews()
        val date = book.date
        if (date == null) {
            ui.listSubtitle.text = activity.getString(if (checked) R.string.big_money_no_file else R.string.big_money_loading)
            return
        }
        ui.listSubtitle.text = activity.getString(R.string.big_money_subtitle, BigMoneyText.day(date), StockFormat.count(book.size))
        addTabs()
        text(activity.getString(tab.body), R.color.text_secondary, 13f, top = 12)
        for (key in tab.records) {
            val signal = BigMoneyBook.Signal.entries.first { it.key == key }
            text(BigMoneyText.signal(activity, signal) + ": " + BigMoneyText.record(activity, book.records[key], signal.bearish), R.color.text_secondary, 12.5f, top = 6)
        }
        if (tab == Tab.PROMOTER) BigMoneyText.promotersStale(activity, book)?.let { text(it, R.color.warn, 12.5f, top = 8) }
        val rows = when (tab) {
            Tab.ACCUMULATION -> book.accumulation()
            Tab.CHURN -> book.churn()
            Tab.DEALS -> book.deals()
            Tab.PROMOTER -> book.promoters()
        }
        if (rows.isEmpty()) text(activity.getString(R.string.big_money_none), R.color.text_secondary, 13.5f, top = 16)
        rows.take(MAX_ROWS).forEach(::addRow)
        text(activity.getString(R.string.big_money_caution), R.color.text_secondary, 11f, top = 16)
    }

    private fun addTabs() {
        val group = ChipGroup(activity).apply {
            isSingleSelection = true
            isSelectionRequired = true
        }
        for (each in Tab.entries) {
            val chip = ChipChoiceBinding.inflate(activity.layoutInflater, group, true).root
            chip.id = View.generateViewId()
            chip.setText(each.title)
            chip.isChecked = each == tab
            chip.setOnClickListener {
                if (tab != each) {
                    tab = each
                    render()
                }
            }
        }
        ui.listItems.addView(group, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }

    private fun addRow(entry: BigMoneyBook.Entry) {
        val card = ItemHelpBinding.inflate(activity.layoutInflater, ui.listItems, true)
        val change = entry.change?.let(StockFormat::signedChange) ?: "—"
        val (title, body, tone) = when (tab) {
            Tab.ACCUMULATION -> Triple(
                activity.getString(
                    R.string.big_money_row_acc,
                    entry.symbol,
                    BigMoneyText.pct(entry.delivery),
                    BigMoneyText.pct(entry.deliveryAvg),
                    BigMoneyText.times(entry.deliveryRatio),
                ),
                activity.getString(R.string.big_money_row_acc_body, change, BigMoneyText.shares(entry.volume), entry.score),
                R.color.bull,
            )
            Tab.CHURN -> Triple(
                activity.getString(
                    R.string.big_money_row_churn,
                    entry.symbol,
                    BigMoneyText.times(entry.volumeRatio),
                    BigMoneyText.pct(entry.delivery),
                ),
                activity.getString(
                    R.string.big_money_row_churn_body,
                    change,
                    BigMoneyText.shares(entry.volume),
                    BigMoneyText.shares(entry.volumeAvg?.toLong()),
                    entry.score,
                ),
                R.color.bear,
            )
            Tab.DEALS -> {
                val deal = entry.deal!!
                val buying = deal.shares > 0
                Triple(
                    activity.getString(
                        if (buying) R.string.big_money_row_deal_buy else R.string.big_money_row_deal_sell,
                        entry.symbol,
                        BigMoneyText.rupees(deal.rupees),
                    ),
                    (
                        listOf(
                            activity.getString(
                                R.string.big_money_row_deal_body,
                                BigMoneyText.shares(deal.shares),
                                deal.days,
                                BigMoneyText.streak(activity, deal),
                            ),
                        ) + listOfNotNull(
                            entry.circular.takeIf { it.isNotEmpty() }
                                ?.let { activity.getString(R.string.big_money_circular, it.joinToString(", ")) },
                        )
                        ).joinToString("\n"),
                    if (buying) R.color.bull else R.color.bear,
                )
            }
            Tab.PROMOTER -> {
                val promoter = entry.promoter!!
                val buying = promoter.rupees > 0
                Triple(
                    activity.getString(
                        if (buying) R.string.big_money_row_promoter_buy else R.string.big_money_row_promoter_sell,
                        entry.symbol,
                        BigMoneyText.rupees(promoter.rupees),
                    ),
                    activity.getString(R.string.big_money_row_promoter_body, promoter.buys, promoter.sells),
                    if (buying) R.color.bull else R.color.bear,
                )
            }
        }
        card.helpTitle.text = title
        card.helpTitle.setTextColor(ContextCompat.getColor(activity, tone))
        card.helpBody.text = body
        card.helpBody.isVisible = true
        card.root.setOnClickListener { onSymbol(entry.symbol) }
    }

    private fun text(value: String, colour: Int, size: Float, top: Int = 10): TextView {
        val view = TextView(activity).apply {
            text = value
            setTextColor(ContextCompat.getColor(activity, colour))
            textSize = size
        }
        ui.listItems.addView(view, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = (top * dp).toInt()
        })
        return view
    }

    private companion object {
        /** Shares a tab lists; the strongest come first. */
        const val MAX_ROWS = 100
    }
}
