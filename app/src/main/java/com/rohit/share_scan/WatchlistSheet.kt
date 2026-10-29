package com.rohit.share_scan

import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.drawable.ColorDrawable
import android.text.InputType
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.LinearInterpolator
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.Toast
import androidx.annotation.ColorInt
import androidx.annotation.ColorRes
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.ConcatAdapter
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.card.MaterialCardView
import com.google.android.material.chip.Chip
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.rohit.share_scan.databinding.ItemWatchBinding
import com.rohit.share_scan.databinding.ItemWatchSummaryBinding
import com.rohit.share_scan.databinding.SheetWatchlistBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

/**
 * The Watchlist & P&L page: every starred stock with the price it was added at and its return since, where it stands
 * between its floor and its next level ([WatchBook.levels]) and what its alert waits for, under a card for the whole
 * basket. Ordered by return, by when added or by how near the next level is. A swipe to the left takes a stock off
 * (with a way back), a long press sets the price it was added at, and a tap opens its detail. It follows the scan as
 * fresh prices come, and can fetch the watchlist's own prices at once ([ScanEngine.refreshWatchlist]).
 *
 * [flat] dresses its cards as the Space Glass background's plain ones rather than glass.
 */
class WatchlistSheet(
    private val activity: AppCompatActivity,
    private val scan: ScanViewModel,
    private val settings: AppSettings,
    private val flat: Boolean,
    private val onOpen: (ScanResult) -> Unit,
    private val onShowInList: () -> Unit,
) {
    private val ui = SheetWatchlistBinding.inflate(activity.layoutInflater)
    private val header = HeaderAdapter()
    private val rows = RowAdapter()
    private var sort = WatchSort.RETURN
    private var watching: Job? = null
    private var refreshing: Job? = null
    private var editDialog: AlertDialog? = null
    private lateinit var dialog: BottomSheetDialog
    private var summary: WatchBook.Summary? = null
    private var pricedAt = 0L
    private var count = 0

    fun show(): BottomSheetDialog {
        ui.watchList.layoutManager = LinearLayoutManager(activity)
        ui.watchList.adapter = ConcatAdapter(header, rows)
        ItemTouchHelper(Swipe()).attachToRecyclerView(ui.watchList)
        ui.watchRefresh.setOnClickListener { refresh() }
        ui.watchShowInList.setOnClickListener {
            dialog.dismiss()
            onShowInList()
        }
        ui.watchExport.setOnClickListener { share() }
        dialog = activity.showSheet(ui.root, ui.sheetTopBar, fullHeight = true)
        // Only what the page shows: a running scan's progress ticks leave it alone.
        watching = activity.lifecycleScope.launch {
            activity.repeatOnLifecycle(Lifecycle.State.STARTED) {
                scan.uiState.distinctUntilChanged { old, new ->
                    old.results === new.results && old.watchExtras === new.watchExtras && old.watchlist == new.watchlist &&
                        old.watchAdded == new.watchAdded && old.setups === new.setups && old.savedAt == new.savedAt
                }.collect { render(it) }
            }
        }
        dialog.setOnDismissListener {
            watching?.cancel()
            refreshing?.cancel()
            editDialog?.dismiss()
        }
        return dialog
    }

    private fun render(state: ScanUiState) {
        val stocks = state.starredStocks.map { it.stock }
        val alerts = ScanEngine.watchStates(activity)
        val list = WatchBook.rows(stocks, state.watchAdded, alerts, sort) { state.setups.shape(it.setupMask) }
        count = list.size
        summary = WatchBook.summary(list)
        pricedAt = state.savedAt
        ui.watchTitle.text = activity.getString(R.string.watch_title, count)
        ui.watchEmpty.isVisible = list.isEmpty()
        ui.watchExport.isEnabled = list.any { it.stock.charted }
        ui.watchExport.alpha = if (ui.watchExport.isEnabled) 1f else 0.38f
        val wasShown = header.shown
        header.shown = list.isNotEmpty()
        if (wasShown && header.shown) header.notifyItemChanged(0)
        rows.submitList(list)
    }

    /** Fetches the watchlist's prices now, between scans; a running scan brings them anyway. */
    private fun refresh() {
        val state = scan.uiState.value
        when {
            refreshing?.isActive == true -> return
            state.isRunning -> toast(R.string.watch_refresh_scanning)
            state.results.isEmpty() && state.watchExtras.isEmpty() -> toast(R.string.watch_refresh_no_scan)
            else -> {
                toast(R.string.watch_refreshing)
                val spin = android.animation.ObjectAnimator.ofFloat(ui.watchRefresh, View.ROTATION, 0f, 360f).apply {
                    duration = 900
                    repeatCount = android.animation.ValueAnimator.INFINITE
                    interpolator = LinearInterpolator()
                    start()
                }
                refreshing = activity.lifecycleScope.launch {
                    try {
                        withContext(Dispatchers.IO) { ScanEngine.refreshWatchlist(activity) }
                        toast(R.string.watch_refreshed)
                    } finally {
                        spin.cancel()
                        ui.watchRefresh.rotation = 0f
                    }
                }
            }
        }
    }

    /** Takes [row]'s stock off the watchlist, with a way to put it back as it was. */
    private fun remove(row: WatchRow) {
        val symbol = row.stock.symbol
        val entry = scan.unstar(symbol)
        Snackbar.make(ui.root, activity.getString(R.string.watch_removed, symbol), Snackbar.LENGTH_LONG)
            .setAction(R.string.watch_undo) { scan.restar(symbol, entry) }
            .show()
    }

    /** Asks for the price [row]'s return should be measured from: what the user bought or first saw it at. */
    private fun editAdded(row: WatchRow) {
        val dp = activity.resources.displayMetrics.density
        val field = EditText(activity).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            setText(row.entry?.price?.let { String.format(Locale.US, "%.2f", it) } ?: "")
            setSelection(text.length)
            setTextColor(color(R.color.text_primary))
        }
        val box = FrameLayout(activity).apply {
            setPadding((20 * dp).roundToInt(), (4 * dp).roundToInt(), (20 * dp).roundToInt(), 0)
            addView(field, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        editDialog?.dismiss()
        editDialog = MaterialAlertDialogBuilder(activity)
            .setTitle(activity.getString(R.string.watch_edit_title, row.stock.symbol))
            .setMessage(R.string.watch_edit_body)
            .setView(box)
            .setNegativeButton(R.string.settings_confirm_no, null)
            .setPositiveButton(R.string.watch_edit_save) { _, _ ->
                val price = field.text.toString().trim().replace(",", "").toDoubleOrNull()
                if (price != null && price > 0.0 && price.isFinite()) {
                    scan.setAddedPrice(row.stock.symbol, price)
                } else {
                    toast(R.string.watch_edit_invalid)
                }
            }
            .show()
    }

    /** The watchlist as text, in the page's order: each stock's price, return and the price it was added at. */
    private fun share() {
        val list = rows.currentList.filter { it.stock.charted }
        if (list.isEmpty()) return
        val lines = mutableListOf(activity.getString(R.string.watch_share_title, SHARE_DATE.withLocale(activity.dateLocale()).format(LocalDate.now(MarketClock.INDIA))))
        summary?.averageReturn?.let { lines += activity.getString(R.string.watch_share_summary, TradePlan.formatPercent(it), count) }
        lines += ""
        for (row in list) {
            val stock = row.stock
            val market = activity.getString(MarketStyle.of(stock.market).label)
            val ret = row.returnPct
            lines += if (row.entry != null && ret != null) {
                activity.getString(
                    R.string.watch_share_line,
                    stock.symbol,
                    market,
                    StockFormat.price(stock.price),
                    TradePlan.formatPercent(ret),
                    StockFormat.price(row.entry.price),
                )
            } else {
                activity.getString(R.string.watch_share_line_no_entry, stock.symbol, market, StockFormat.price(stock.price))
            }
        }
        val text = lines.joinToString("\n")
        val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
        try {
            activity.startActivity(Intent.createChooser(send, activity.getString(R.string.watch_share_open)))
        } catch (error: ActivityNotFoundException) {
            toast(R.string.list_share_failed)
        }
    }

    private fun MaterialCardView.dress() {
        if (flat) wearFlat() else wearCardGlass()
    }

    /** "आज 15:30" for today, else the day as well. */
    private fun priced(millis: Long): String {
        val at = Instant.ofEpochMilli(millis).atZone(MarketClock.INDIA)
        return if (at.toLocalDate() == LocalDate.now(MarketClock.INDIA)) {
            activity.getString(R.string.time_today, CLOCK.format(at))
        } else {
            DAY_TIME.withLocale(activity.dateLocale()).format(at)
        }
    }

    private fun day(millis: Long): String {
        val at = Instant.ofEpochMilli(millis).atZone(MarketClock.INDIA).toLocalDate()
        return if (at == LocalDate.now(MarketClock.INDIA)) activity.getString(R.string.watch_today) else DAY.withLocale(activity.dateLocale()).format(at)
    }

    private fun toast(id: Int) = Toast.makeText(activity, id, Toast.LENGTH_SHORT).show()

    @ColorInt
    private fun color(@ColorRes id: Int): Int = ContextCompat.getColor(activity, id)

    /** The basket's card and the order chips: one item, there while the watchlist has a stock. */
    private inner class HeaderAdapter : RecyclerView.Adapter<HeaderAdapter.Holder>() {
        inner class Holder(val ui: ItemWatchSummaryBinding) : RecyclerView.ViewHolder(ui.root)

        var shown = false
            set(value) {
                if (field == value) return
                field = value
                if (value) notifyItemInserted(0) else notifyItemRemoved(0)
            }

        override fun getItemCount() = if (shown) 1 else 0

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val holder = Holder(ItemWatchSummaryBinding.inflate(LayoutInflater.from(parent.context), parent, false))
            holder.ui.summaryCard.dress()
            for (choice in WatchSort.entries) {
                val chip = LayoutInflater.from(parent.context).inflate(R.layout.chip_choice, holder.ui.watchSortChips, false) as Chip
                chip.id = View.generateViewId()
                chip.tag = choice
                chip.setText(
                    when (choice) {
                        WatchSort.RETURN -> R.string.watch_sort_return
                        WatchSort.RECENT -> R.string.watch_sort_recent
                        WatchSort.NEAREST -> R.string.watch_sort_nearest
                    },
                )
                chip.isCheckable = true
                chip.isChecked = choice == sort
                if (flat) chip.wearFlat(accented = false)
                chip.setOnClickListener {
                    if (sort != choice) {
                        sort = choice
                        render(scan.uiState.value)
                        ui.watchList.scrollToPosition(0)
                    }
                }
                holder.ui.watchSortChips.addView(chip)
            }
            return holder
        }

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val ui = holder.ui
            val summary = summary ?: return
            val average = summary.averageReturn
            ui.summaryValue.text = average?.let(TradePlan::formatPercent) ?: activity.getString(R.string.stat_empty)
            ui.summaryValue.setTextColor(
                color(
                    when {
                        average == null -> R.color.text_secondary
                        average >= 0 -> R.color.bull
                        else -> R.color.bear
                    },
                ),
            )
            val priced = if (pricedAt > 0) priced(pricedAt) else activity.getString(R.string.stat_empty)
            ui.summaryNote.text = activity.getString(R.string.watch_summary_note, count, priced)
            ui.summaryCurveBox.isVisible = summary.curve.size >= 2
            ui.summaryCurve.values = summary.curve
            ui.summaryCurve.color = color(if ((summary.curve.lastOrNull() ?: 0.0) >= 0) R.color.bull else R.color.bear)
            ui.summaryGaining.text = activity.getString(R.string.watch_summary_gaining, summary.gaining)
            ui.summaryLosing.text = activity.getString(R.string.watch_summary_losing, summary.losing)
            ui.summaryAlerts.text = if (settings.watchAlerts) {
                activity.getString(R.string.watch_summary_alerts, summary.activeAlerts)
            } else {
                activity.getString(R.string.watch_summary_alerts_off)
            }
            ui.summaryCard.contentDescription = activity.getString(
                R.string.watch_summary_description,
                ui.summaryValue.text,
                summary.gaining,
                summary.losing,
            )
            val checked = (0 until ui.watchSortChips.childCount).map(ui.watchSortChips::getChildAt).firstOrNull { it.tag == sort }
            if (checked != null && ui.watchSortChips.checkedChipId != checked.id) ui.watchSortChips.check(checked.id)
        }
    }

    private inner class RowAdapter : ListAdapter<WatchRow, RowAdapter.Holder>(Diff) {
        inner class Holder(val ui: ItemWatchBinding) : RecyclerView.ViewHolder(ui.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val holder = Holder(ItemWatchBinding.inflate(LayoutInflater.from(parent.context), parent, false))
            holder.ui.root.dress()
            fun row(): WatchRow? = holder.bindingAdapterPosition.takeIf { it != RecyclerView.NO_POSITION }?.let(::getItem)
            holder.ui.root.setOnClickListener { row()?.let { onOpen(it.stock) } }
            holder.ui.root.setOnLongClickListener {
                val row = row() ?: return@setOnLongClickListener false
                editAdded(row)
                true
            }
            holder.ui.watchStar.setOnClickListener { row()?.let(::remove) }
            return holder
        }

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val row = getItem(position)
            val stock = row.stock
            val ui = holder.ui
            ui.watchSymbol.text = stock.symbol
            ui.watchSector.text = listOf(StockSearch.shortName(stock.name), stock.sector).firstOrNull { it.isNotEmpty() }.orEmpty()
            ui.watchStar.contentDescription = activity.getString(R.string.watch_star_remove, stock.symbol)
            val charted = stock.charted
            val exchange = MarketStyle.of(stock.market)
            ui.watchExchange.isVisible = charted
            ui.watchExchange.setText(exchange.label)
            ui.watchExchange.setTextColor(color(exchange.text))
            ui.watchExchange.backgroundTintList = ColorStateList.valueOf(color(exchange.container))
            ui.watchLevels.isVisible = charted && row.levels != null
            ui.watchScore.isVisible = charted
            if (!charted) {
                ui.watchPrice.text = activity.getString(R.string.stat_empty)
                ui.watchChange.setText(R.string.watch_no_data)
                ui.watchChange.setTextColor(color(R.color.text_secondary))
                ui.watchReturn.isVisible = false
                ui.watchAdded.text = row.entry?.let { added(it) } ?: activity.getString(R.string.watch_added_waiting)
                bindAlert(ui, row)
                ui.root.contentDescription = activity.getString(R.string.item_description_no_data, stock.symbol)
                return
            }
            ui.watchPrice.text = activity.getString(R.string.item_price, StockFormat.price(stock.price))
            ui.watchChange.text = activity.getString(R.string.watch_change_5d, StockFormat.signedChange(stock.change5d))
            ui.watchChange.setTextColor(color(if (stock.change5d >= 0) R.color.bull else R.color.bear))
            val ret = row.returnPct
            ui.watchReturn.isVisible = true
            ui.watchReturn.text = ret?.let(TradePlan::formatPercent) ?: activity.getString(R.string.stat_empty)
            val (text, container) = when {
                ret == null -> R.color.neutral to R.color.neutral_container
                ret >= 0 -> R.color.bull to R.color.bull_container
                else -> R.color.bear to R.color.bear_container
            }
            ui.watchReturn.setTextColor(color(text))
            ui.watchReturn.backgroundTintList = ColorStateList.valueOf(color(container))
            ui.watchAdded.text = row.entry?.let { added(it) } ?: activity.getString(R.string.watch_added_waiting)

            row.levels?.let { levels ->
                ui.watchBar.position = levels.position(stock.price)
                ui.watchFloor.text = activity.getString(
                    if (levels.floorIsStop) R.string.watch_level_stop else R.string.watch_level_support,
                    StockFormat.price(levels.floor),
                )
                ui.watchNext.text = if (levels.target == 0) {
                    activity.getString(R.string.watch_level_trigger, StockFormat.price(levels.next))
                } else {
                    activity.getString(R.string.watch_level_target, levels.target, StockFormat.price(levels.next))
                }
            }
            bindAlert(ui, row)
            val tier = StockFormat.tier(stock.score)
            ui.watchScore.text = activity.getString(R.string.watch_score, StockFormat.score(stock.score))
            ui.watchScore.setTextColor(
                color(
                    when (tier) {
                        StockFormat.Tier.STRONG -> R.color.bull
                        StockFormat.Tier.WATCH -> R.color.warn
                        StockFormat.Tier.WEAK -> R.color.neutral
                    },
                ),
            )
            ui.root.contentDescription = listOfNotNull(
                activity.getString(R.string.watch_item_description, stock.symbol, StockFormat.price(stock.price), ui.watchReturn.text),
                ui.watchAdded.text,
                if (ui.watchLevels.isVisible) "${ui.watchFloor.text}, ${ui.watchNext.text}" else null,
                ui.watchAlert.text,
            ).joinToString(", ")
        }

        private fun added(entry: WatchEntry): String = activity.getString(
            if (entry.estimated) R.string.watch_added_estimated else R.string.watch_added,
            StockFormat.price(entry.price),
            day(entry.at),
        )

        /** What the stock's alert waits for, or that alerts are off. */
        private fun bindAlert(ui: ItemWatchBinding, row: WatchRow) {
            val alert = row.alert
            val (text, tone) = when {
                !settings.watchAlerts -> activity.getString(R.string.watch_alert_off) to R.color.neutral
                alert?.phase == WatchAlerts.Phase.IN_TRADE && alert.hits > 0 ->
                    activity.getString(R.string.watch_alert_target, alert.hits) to R.color.bull
                alert?.phase == WatchAlerts.Phase.IN_TRADE -> activity.getString(R.string.watch_alert_broke_out) to R.color.bull
                alert?.entry != null -> activity.getString(R.string.watch_alert_waiting, StockFormat.price(alert.entry)) to R.color.warn
                else -> activity.getString(R.string.watch_alert_pending) to R.color.neutral
            }
            ui.watchAlert.text = text
            ui.watchAlert.setTextColor(color(tone))
            ui.watchAlert.backgroundTintList = ColorStateList.valueOf(
                color(
                    when (tone) {
                        R.color.bull -> R.color.bull_container
                        R.color.warn -> R.color.warn_container
                        else -> R.color.neutral_container
                    },
                ),
            )
        }
    }

    /** A swipe to the left takes a stock off the watchlist, over a red strip with a bin; the basket's card stays put. */
    private inner class Swipe : ItemTouchHelper.SimpleCallback(0, ItemTouchHelper.LEFT) {
        private val strip = ColorDrawable(color(R.color.bear_container))
        private val bin = ContextCompat.getDrawable(activity, R.drawable.ic_delete)!!.mutate().apply { setTint(color(R.color.bear)) }

        override fun getSwipeDirs(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder): Int =
            if (viewHolder is RowAdapter.Holder) ItemTouchHelper.LEFT else 0

        override fun onMove(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder, target: RecyclerView.ViewHolder) = false

        override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
            val position = viewHolder.bindingAdapterPosition
            if (position == RecyclerView.NO_POSITION) return
            remove(rows.currentList[position])
        }

        override fun onChildDraw(
            c: Canvas,
            recyclerView: RecyclerView,
            viewHolder: RecyclerView.ViewHolder,
            dX: Float,
            dY: Float,
            actionState: Int,
            isCurrentlyActive: Boolean,
        ) {
            val item = viewHolder.itemView
            if (dX < 0) {
                strip.setBounds((item.right + dX).roundToInt(), item.top, item.right, item.bottom)
                strip.draw(c)
                val size = bin.intrinsicWidth
                val margin = (20 * activity.resources.displayMetrics.density).roundToInt()
                val top = item.top + (item.height - size) / 2
                bin.setBounds(item.right - margin - size, top, item.right - margin, top + size)
                if (-dX > margin + size) bin.draw(c)
            }
            super.onChildDraw(c, recyclerView, viewHolder, dX, dY, actionState, isCurrentlyActive)
        }
    }

    private object Diff : DiffUtil.ItemCallback<WatchRow>() {
        override fun areItemsTheSame(oldItem: WatchRow, newItem: WatchRow) = oldItem.stock.symbol == newItem.stock.symbol

        override fun areContentsTheSame(oldItem: WatchRow, newItem: WatchRow) = oldItem == newItem
    }

    private companion object {
        val CLOCK: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm", Locale.US)
        // In the app's language ([dateLocale]) where they are used.
        val DAY_TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM HH:mm")
        val DAY: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM")
        val SHARE_DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM yyyy")
    }
}
