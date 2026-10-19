package com.rohit.share_scan

import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.ColorRes
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.rohit.share_scan.databinding.ItemDetailStatBinding
import com.rohit.share_scan.databinding.ItemHelpBinding
import com.rohit.share_scan.databinding.ItemRecordDayBinding
import com.rohit.share_scan.databinding.ItemRecordPickBinding
import com.rohit.share_scan.databinding.ItemSectorBinding
import com.rohit.share_scan.databinding.SheetListBinding
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * How the scan's daily picks went: a summary for 1, 5 and 10 sessions after the pick against Nifty 50, then each
 * session's picks with their returns.
 */
class TrackRecordSheet(
    private val activity: AppCompatActivity,
    private val journal: PickJournal,
    private val onBack: () -> Unit,
) {
    private val ui = SheetListBinding.inflate(activity.layoutInflater)

    fun show(): BottomSheetDialog {
        ui.listTitle.setText(R.string.record_title)
        ui.listSubtitle.text = activity.getString(R.string.record_subtitle, PickJournal.PICKS_PER_DAY)
        if (journal.days.isEmpty()) {
            note(activity.getString(R.string.record_empty_title), activity.getString(R.string.record_empty_body))
        } else {
            summary()
            journal.days.forEach(::day)
            note(activity.getString(R.string.record_how_title), activity.getString(R.string.record_how_body))
        }
        return activity.showSheet(ui.root, ui.sheetTopBar, onBack, fullHeight = true)
    }

    private fun summary() {
        val row = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            isBaselineAligned = false
        }
        ui.listItems.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        PickJournal.HORIZONS.forEachIndexed { index, sessions ->
            val tile = ItemDetailStatBinding.inflate(activity.layoutInflater, row, true)
            tile.statLabel.text = activity.getString(R.string.record_after, sessions)
            tile.statNote.isVisible = true
            val summary = journal.summary(index)
            if (summary == null) {
                tile.statValue.setText(R.string.stat_empty)
                tile.statNote.text = activity.getString(R.string.record_pending)
            } else {
                tile.statValue.text = TradePlan.formatPercent(summary.average)
                tile.statValue.setTextColor(color(if (summary.average >= 0) R.color.bull else R.color.bear))
                tile.statNote.text = activity.getString(
                    R.string.record_summary_note,
                    (summary.winRate * 100).toInt(),
                    summary.nifty?.let(TradePlan::formatPercent) ?: activity.getString(R.string.stat_empty),
                    summary.picks,
                )
            }
            tile.root.contentDescription = listOf(tile.statLabel.text, tile.statValue.text, tile.statNote.text).joinToString(", ")
        }
    }

    private fun day(day: PickJournal.Day) {
        val card = ItemRecordDayBinding.inflate(activity.layoutInflater, ui.listItems, true)
        card.dayDate.text = DATE.format(LocalDate.ofEpochDay(day.day))
        val nifty = day.nifty.getOrNull(1)
        card.dayNifty.isVisible = nifty != null
        card.dayNifty.text = nifty?.let { activity.getString(R.string.record_day_nifty, TradePlan.formatPercent(it)) }.orEmpty()
        with(card.dayHeader) {
            pickSymbol.setText(R.string.record_column_stock)
            pickOne.text = activity.getString(R.string.record_column, PickJournal.HORIZONS[0])
            pickFive.text = activity.getString(R.string.record_column, PickJournal.HORIZONS[1])
            pickTen.text = activity.getString(R.string.record_column, PickJournal.HORIZONS[2])
            listOf(pickSymbol, pickOne, pickFive, pickTen).forEach { it.setTextColor(color(R.color.text_secondary)); it.textSize = 11f }
        }
        for (pick in day.picks) {
            val row = ItemRecordPickBinding.inflate(activity.layoutInflater, card.dayPicks, true)
            row.pickSymbol.text = activity.getString(R.string.record_pick, pick.symbol, StockFormat.score(pick.score))
            bindReturn(row.pickOne, pick.returns.getOrNull(0))
            bindReturn(row.pickFive, pick.returns.getOrNull(1))
            bindReturn(row.pickTen, pick.returns.getOrNull(2))
        }
    }

    private fun bindReturn(view: TextView, value: Double?) {
        if (value == null) {
            view.setText(R.string.stat_empty)
            view.setTextColor(color(R.color.text_secondary))
        } else {
            view.text = TradePlan.formatPercent(value)
            view.setTextColor(color(if (value >= 0) R.color.bull else R.color.bear))
        }
    }

    private fun note(title: String, body: String) {
        val card = ItemHelpBinding.inflate(activity.layoutInflater, ui.listItems, true)
        card.helpTitle.text = title
        card.helpBody.text = body
    }

    private fun color(@ColorRes id: Int) = ContextCompat.getColor(activity, id)

    private companion object {
        val DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("EEE, d MMM", Locale.forLanguageTag("hi-IN"))
    }
}

/** Industries ranked by their stocks' mean score in the latest scan; tapping one filters the list to it. */
class SectorSheet(
    private val activity: AppCompatActivity,
    private val results: List<ScanResult>,
    private val current: Set<String>,
    private val onPick: (String?) -> Unit,
    private val onBack: (() -> Unit)?,
) {
    private val ui = SheetListBinding.inflate(activity.layoutInflater)

    fun show(): BottomSheetDialog {
        ui.listTitle.setText(R.string.sector_title)
        ui.listSubtitle.text = activity.getString(R.string.sector_subtitle, SectorStrength.MIN_STOCKS)
        lateinit var dialog: BottomSheetDialog
        val ranked = SectorStrength.rank(results)
        if (ranked.isEmpty()) {
            val card = ItemHelpBinding.inflate(activity.layoutInflater, ui.listItems, true)
            card.helpTitle.setText(R.string.sector_empty_title)
            card.helpBody.setText(if (results.isEmpty()) R.string.sector_empty_no_scan else R.string.sector_empty_body)
        }
        if (current.isNotEmpty()) {
            val clear = ItemHelpBinding.inflate(activity.layoutInflater, ui.listItems, true)
            clear.helpTitle.text = activity.getString(R.string.sector_clear_title, current.joinToString(", "))
            clear.helpBody.setText(R.string.sector_clear_body)
            clear.root.isClickable = true
            clear.root.setOnClickListener {
                dialog.dismiss()
                onPick(null)
            }
        }
        ranked.forEachIndexed { index, sector ->
            val card = ItemSectorBinding.inflate(activity.layoutInflater, ui.listItems, true)
            val tone = when (StockFormat.tier(sector.averageScore)) {
                StockFormat.Tier.STRONG -> R.color.bull
                StockFormat.Tier.WATCH -> R.color.warn
                StockFormat.Tier.WEAK -> R.color.neutral
            }
            card.sectorRank.text = activity.getString(R.string.sector_rank, index + 1)
            card.sectorName.text = sector.sector
            card.sectorScore.text = StockFormat.score(sector.averageScore)
            card.sectorScore.setTextColor(color(tone))
            card.sectorBar.color = color(tone)
            card.sectorBar.fraction = (sector.averageScore / 100.0).toFloat()
            card.sectorDetail.text = activity.getString(
                R.string.sector_detail,
                sector.stocks,
                sector.strong,
                TradePlan.formatPercent(sector.averageChange),
                sector.aboveSma50?.let { "${(it * 100).toInt()}%" } ?: activity.getString(R.string.stat_empty),
            )
            card.root.isCheckable = true
            card.root.isChecked = sector.sector in current
            card.root.contentDescription = listOf(card.sectorRank.text, sector.sector, card.sectorScore.text, card.sectorDetail.text)
                .joinToString(", ")
            card.root.setOnClickListener {
                dialog.dismiss()
                onPick(sector.sector)
            }
        }
        dialog = activity.showSheet(ui.root, ui.sheetTopBar, onBack, fullHeight = true)
        return dialog
    }

    private fun color(@ColorRes id: Int) = ContextCompat.getColor(activity, id)
}
