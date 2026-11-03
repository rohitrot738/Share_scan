package com.rohit.share_scan

import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.annotation.ColorRes
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButton
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.tabs.TabLayout
import com.rohit.share_scan.PositionSize.Product
import com.rohit.share_scan.databinding.ItemHelpBinding
import com.rohit.share_scan.databinding.SheetPaperBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Paper Trading, a section of its own (the header's button, or Menu → Paper Trading): a pretend account
 * ([PaperEngine], [PaperStore]) that trades at real prices. On top, what it is worth, its cash and today's result;
 * then a new order ([PaperOrderSheet]) and four tabs: open and recent intraday trades, delivery shares, the orders, and
 * the reckoning (win rate, profit factor, charges, the worth day by day, and starting over). Prices are read again
 * every [AUTO_REFRESH_MS] while the market is open and the section is showing.
 */
class PaperSheet(
    private val activity: AppCompatActivity,
    /** The stocks a new order can be for: the scan's and the watchlist's. */
    private val stocks: () -> List<ScanResult>,
    private val onStock: (String) -> Unit,
    private val onBack: (() -> Unit)?,
) {
    private val ui = SheetPaperBinding.inflate(activity.layoutInflater)
    private lateinit var dialog: BottomSheetDialog
    private var tab = 0
    private var refreshing: Job? = null
    private var ticker: Job? = null
    private var failed = false

    fun show(): BottomSheetDialog {
        ui.paperTabs.addOnTabSelectedListener(
            object : TabLayout.OnTabSelectedListener {
                override fun onTabSelected(selected: TabLayout.Tab) {
                    tab = selected.position
                    render()
                }

                override fun onTabUnselected(unselected: TabLayout.Tab) = Unit

                override fun onTabReselected(reselected: TabLayout.Tab) = Unit
            },
        )
        ui.paperNewOrder.setOnClickListener { newOrder(null) }
        ui.paperRefresh.setOnClickListener { refresh() }
        ui.paperAccountCard.setOnClickListener { ui.paperTabs.getTabAt(3)?.select() }
        dialog = activity.showSheet(ui.root, ui.sheetTopBar, onBack, fullHeight = true)
        render()
        refresh()
        ticker = activity.lifecycleScope.launch {
            while (isActive) {
                delay(AUTO_REFRESH_MS)
                if (dialog.isShowing && MarketClock.isOpen(MarketClock.now())) refresh()
            }
        }
        dialog.setOnDismissListener {
            ticker?.cancel()
            refreshing?.cancel()
        }
        return dialog
    }

    /** Reads fresh prices for every stock with an order or a position, then shows what changed. */
    private fun refresh() {
        if (refreshing?.isActive == true) return
        refreshing = activity.lifecycleScope.launch {
            ui.paperUpdated.setText(R.string.paper_updating)
            val (events, complete) = withContext(Dispatchers.IO) {
                runCatching { PaperStore.refresh(activity) }.getOrDefault(emptyList<PaperEvent>() to false)
            }
            failed = !complete
            if (events.isNotEmpty()) {
                Toast.makeText(activity, events.take(3).joinToString("\n") { PaperText.event(activity, it) }, Toast.LENGTH_LONG).show()
            }
            render()
        }
    }

    private fun render() {
        val book = PaperStore.load(activity)
        account(book)
        ui.paperItems.removeAllViews()
        when (tab) {
            0 -> positions(book, Product.MIS)
            1 -> positions(book, Product.CNC)
            2 -> orders(book)
            else -> reckoning(book)
        }
    }

    /** The account card: worth, change since the start, the worth day by day, cash, margin, holdings, today. */
    private fun account(book: PaperBook) {
        val worth = PaperEngine.worth(book)
        val change = worth - book.start
        ui.paperWorth.text = PaperText.money(worth)
        ui.paperChange.text = activity.getString(R.string.paper_since_start, PaperText.signed(change), percent(change / book.start * 100.0), StockFormat.price(book.start))
        ui.paperChange.setTextColor(color(if (change >= 0.0) R.color.bull else R.color.bear))
        val curve = book.equity.map { it.second }
        ui.paperCurve.isVisible = curve.size >= 2
        ui.paperCurve.values = curve
        ui.paperCurve.color = color(if (change >= 0.0) R.color.bull else R.color.bear)
        val lines = mutableListOf<CharSequence>(activity.getString(R.string.paper_cash, StockFormat.price(PaperEngine.available(book))))
        PaperEngine.blocked(book).takeIf { it > 0.0 }?.let { lines += activity.getString(R.string.paper_blocked, StockFormat.price(it)) }
        PaperEngine.margin(book).takeIf { it > 0.0 }?.let { lines += activity.getString(R.string.paper_margin, StockFormat.price(it)) }
        PaperEngine.holdingsValue(book).takeIf { it > 0.0 }?.let { lines += activity.getString(R.string.paper_holdings, StockFormat.price(it)) }
        if (book.positions.isNotEmpty()) {
            val open = PaperEngine.unrealized(book)
            lines += toned(activity.getString(R.string.paper_open_pnl, PaperText.signed(open)), tone(open))
        }
        val today = PaperEngine.realizedOn(book, MarketClock.now().toLocalDate())
        if (book.trades.any { MarketClock.now(it.closedAt).toLocalDate() == MarketClock.now().toLocalDate() }) {
            lines += toned(activity.getString(R.string.paper_today_closed, PaperText.signed(today)), tone(today))
        }
        if (book.chargesPaid > 0.0) lines += activity.getString(R.string.paper_charges_paid, StockFormat.price(book.chargesPaid))
        ui.paperLines.text = join(lines)
        val watched = PaperEngine.watched(book).keys
        val newest = book.quotes.filterKeys { it in watched }.values.maxOfOrNull { it.at }
        ui.paperUpdated.text = when {
            refreshing?.isActive == true -> activity.getString(R.string.paper_updating)
            failed -> activity.getString(R.string.paper_update_failed)
            watched.isEmpty() -> activity.getString(R.string.paper_updated_idle)
            newest != null -> activity.getString(R.string.paper_updated, candleTime(activity, newest))
            else -> activity.getString(R.string.paper_updated_never)
        }
    }

    /** Open positions of [product], then its recent closed trades. */
    private fun positions(book: PaperBook, product: Product) {
        val open = book.positions.filter { it.product == product }.sortedBy { it.symbol }
        heading(activity.getString(if (product == Product.MIS) R.string.paper_mis_open_title else R.string.paper_cnc_title))
        if (open.isEmpty()) {
            card(
                activity.getString(R.string.paper_nothing_open),
                activity.getString(if (product == Product.MIS) R.string.paper_mis_none else R.string.paper_cnc_none),
            ).root.setOnClickListener { newOrder(null) }
        }
        open.forEach { positionCard(book, it) }
        val closed = book.trades.filter { it.product == product }.take(RECENT)
        if (closed.isNotEmpty()) {
            heading(activity.getString(if (product == Product.MIS) R.string.paper_mis_recent else R.string.paper_cnc_recent))
            closed.forEach(::tradeCard)
        }
    }

    private fun positionCard(book: PaperBook, p: PaperPosition) {
        val price = PaperEngine.price(book, p.symbol) ?: p.avg
        val pnl = p.pnl(price)
        val pct = (price / p.avg - 1.0) * 100.0 * if (p.qty > 0) 1 else -1
        val lines = mutableListOf<CharSequence>(
            activity.getString(R.string.paper_position_avg, StockFormat.price(p.avg), StockFormat.price(price)),
            toned(activity.getString(R.string.paper_position_pnl, PaperText.signed(pnl), percent(pct)), tone(pnl)),
            activity.getString(R.string.paper_position_levels, p.stop?.let(PaperText::money) ?: "—", p.target?.let(PaperText::money) ?: "—"),
        )
        lines += if (p.product == Product.MIS) {
            toned(activity.getString(R.string.paper_position_square_off), R.color.warn)
        } else {
            val days = java.time.temporal.ChronoUnit.DAYS.between(MarketClock.now(p.openedAt).toLocalDate(), MarketClock.now().toLocalDate())
            activity.getString(R.string.paper_position_held, days, StockFormat.price(p.qty * price))
        }
        lines += toned(activity.getString(R.string.paper_position_tap), R.color.text_secondary)
        val title = activity.getString(R.string.paper_position_title, p.symbol, PaperText.side(activity, p.side), abs(p.qty))
        card(title, join(lines)).root.setOnClickListener { positionActions(p) }
    }

    /** What a tap on a position offers: close it now, change its stop-loss and target, a new order, or the stock. */
    private fun positionActions(p: PaperPosition) {
        val items = listOf(R.string.paper_action_exit, R.string.paper_action_levels, R.string.paper_action_more, R.string.paper_action_open)
        MaterialAlertDialogBuilder(activity)
            .setTitle(p.symbol)
            .setItems(items.map(activity::getString).toTypedArray()) { _, which ->
                when (items[which]) {
                    R.string.paper_action_exit -> exit(p)
                    R.string.paper_action_levels -> levels(p)
                    R.string.paper_action_more -> {
                        val stock = stocks().firstOrNull { it.symbol == p.symbol }
                        if (stock == null) {
                            Toast.makeText(activity, R.string.paper_stock_not_in_scan, Toast.LENGTH_SHORT).show()
                        } else {
                            newOrder(PaperOrderSheet.Preset(stock, side = p.side, product = p.product, type = PaperOrder.Type.MARKET))
                        }
                    }
                    R.string.paper_action_open -> {
                        dialog.dismiss()
                        onStock(p.symbol)
                    }
                }
            }
            .show()
    }

    /** Closes [p] with a market order at the latest price (after hours, at the next open). */
    private fun exit(p: PaperPosition) {
        activity.lifecycleScope.launch {
            withContext(Dispatchers.IO) { runCatching { PaperStore.refresh(activity, mapOf(p.symbol to p.ticker)) } }
            val now = System.currentTimeMillis()
            val problem = PaperStore.update<PaperEngine.Problem?>(activity) { book ->
                val current = PaperEngine.position(book, p.symbol, p.product) ?: return@update book to null
                PaperEngine.exit(book, current, PaperEngine.price(book, p.symbol), now)
            }
            val message = when {
                problem != null -> PaperText.problem(activity, problem)
                PaperEngine.position(PaperStore.load(activity), p.symbol, p.product) == null -> {
                    val trade = PaperStore.load(activity).trades.firstOrNull { it.symbol == p.symbol }
                    activity.getString(R.string.paper_exit_done, p.symbol, trade?.net?.let(PaperText::signed).orEmpty())
                }
                else -> activity.getString(R.string.paper_exit_amo, p.symbol)
            }
            Toast.makeText(activity, message, Toast.LENGTH_LONG).show()
            render()
        }
    }

    /** Asks for a new stop-loss and target for [p]; an empty box takes it off. */
    private fun levels(p: PaperPosition) {
        val stop = field(R.string.paper_stop_hint, p.stop)
        val target = field(R.string.paper_target_hint, p.target)
        val dp = activity.resources.displayMetrics.density
        val box = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            val side = (20 * dp).roundToInt()
            setPadding(side, (8 * dp).roundToInt(), side, 0)
            addView(stop)
            addView(target)
        }
        MaterialAlertDialogBuilder(activity)
            .setTitle(activity.getString(R.string.paper_levels_title, p.symbol))
            .setView(box)
            .setNegativeButton(R.string.settings_confirm_no, null)
            .setPositiveButton(R.string.paper_levels_save) { _, _ ->
                val now = System.currentTimeMillis()
                val problem = PaperStore.update<PaperEngine.Problem?>(activity) { book ->
                    val current = PaperEngine.position(book, p.symbol, p.product) ?: return@update book to null
                    PaperEngine.setLevels(book, current, value(stop), value(target), null, now)
                }
                problem?.let { Toast.makeText(activity, PaperText.problem(activity, it), Toast.LENGTH_LONG).show() }
                render()
            }
            .show()
    }

    private fun tradeCard(t: PaperTrade) {
        val lines = listOf<CharSequence>(
            activity.getString(
                R.string.paper_trade_line,
                StockFormat.price(t.entry),
                StockFormat.price(t.exit),
                PaperText.reason(activity, t.reason ?: PaperReason.EXIT),
                WHEN.withLocale(activity.dateLocale()).format(Instant.ofEpochMilli(t.closedAt).atZone(MarketClock.INDIA)),
            ),
            toned(activity.getString(R.string.paper_trade_net, PaperText.signed(t.net), StockFormat.price(t.charges)), tone(t.net)),
        )
        card(activity.getString(R.string.paper_position_title, t.symbol, PaperText.side(activity, t.side), t.qty), join(lines))
    }

    /** Waiting orders (a tap cancels), then the finished ones, newest first. */
    private fun orders(book: PaperBook) {
        val waiting = book.orders.filter { it.open }
        heading(activity.getString(R.string.paper_orders_open))
        if (waiting.isEmpty()) card(activity.getString(R.string.paper_orders_none_open), activity.getString(R.string.paper_orders_none_open_body))
        waiting.forEach { order ->
            orderCard(order).root.setOnClickListener {
                MaterialAlertDialogBuilder(activity)
                    .setTitle(R.string.paper_cancel_title)
                    .setMessage(orderTitle(order))
                    .setNegativeButton(R.string.settings_confirm_no, null)
                    .setPositiveButton(R.string.paper_cancel_yes) { _, _ ->
                        PaperStore.update(activity) { PaperEngine.cancel(it, order.id, System.currentTimeMillis()) to Unit }
                        render()
                    }
                    .show()
            }
        }
        val done = book.orders.filterNot { it.open }.take(RECENT_ORDERS)
        if (done.isNotEmpty()) {
            heading(activity.getString(R.string.paper_orders_done))
            done.forEach(::orderCard)
        }
    }

    private fun orderTitle(o: PaperOrder): String = activity.getString(
        R.string.paper_order_title,
        PaperText.side(activity, o.side),
        o.qty,
        o.symbol,
        PaperText.type(activity, o),
        PaperText.product(activity, o.product),
    )

    private fun orderCard(o: PaperOrder): ItemHelpBinding {
        val lines = mutableListOf<CharSequence>()
        val placed = WHEN.withLocale(activity.dateLocale()).format(Instant.ofEpochMilli(o.placedAt).atZone(MarketClock.INDIA))
        lines += when (o.status) {
            PaperOrder.Status.PENDING -> toned(
                if (o.triggered) {
                    activity.getString(R.string.paper_status_triggered)
                } else {
                    activity.getString(R.string.paper_status_pending, DAY.withLocale(activity.dateLocale()).format(o.day))
                },
                R.color.warn,
            )
            PaperOrder.Status.FILLED -> toned(
                activity.getString(
                    R.string.paper_status_filled,
                    StockFormat.price(o.fillPrice ?: 0.0),
                    WHEN.withLocale(activity.dateLocale()).format(Instant.ofEpochMilli(o.doneAt ?: o.placedAt).atZone(MarketClock.INDIA)),
                    StockFormat.price(o.charges),
                ),
                R.color.bull,
            )
            else -> toned(activity.getString(R.string.paper_status_closed, PaperText.reason(activity, o.reason)), R.color.bear)
        }
        if (o.stop != null || o.target != null) {
            lines += activity.getString(R.string.paper_position_levels, o.stop?.let(PaperText::money) ?: "—", o.target?.let(PaperText::money) ?: "—")
        }
        lines += if (o.reason == PaperReason.STOP || o.reason == PaperReason.TARGET || o.reason == PaperReason.SQUARE_OFF) {
            activity.getString(R.string.paper_order_auto, PaperText.reason(activity, o.reason))
        } else {
            activity.getString(R.string.paper_order_placed_at, placed)
        }
        if (o.open) lines += toned(activity.getString(R.string.paper_order_tap_cancel), R.color.text_secondary)
        return card(orderTitle(o), join(lines))
    }

    /** The reckoning: how the closed trades went, by kind, the worst fall, and starting over. */
    private fun reckoning(book: PaperBook) {
        heading(activity.getString(R.string.paper_stats_title))
        if (book.trades.isEmpty()) {
            card(activity.getString(R.string.paper_stats_none_title), activity.getString(R.string.paper_stats_none))
        } else {
            val s = PaperEngine.stats(book.trades)
            val lines = mutableListOf<CharSequence>(
                activity.getString(R.string.paper_stats_count, s.trades, s.wins, s.losses, percent1(s.winRate)),
                toned(activity.getString(R.string.paper_stats_net, PaperText.signed(s.net)), tone(s.net)),
                activity.getString(R.string.paper_stats_gross, PaperText.signed(s.gross), StockFormat.price(s.charges)),
                activity.getString(R.string.paper_stats_avg, PaperText.signed(s.avgWin), PaperText.signed(s.avgLoss)),
                s.profitFactor?.let { activity.getString(R.string.paper_stats_pf, String.format(Locale.US, "%.2f", it)) }
                    ?: activity.getString(R.string.paper_stats_pf_none),
            )
            s.best?.let { lines += activity.getString(R.string.paper_stats_best, it.symbol, PaperText.signed(it.net)) }
            s.worst?.let { lines += activity.getString(R.string.paper_stats_worst, it.symbol, PaperText.signed(it.net)) }
            if (book.equity.size >= 2) lines += activity.getString(R.string.paper_stats_drawdown, percent1(PaperEngine.maxDrawdown(book.equity)))
            card(activity.getString(R.string.paper_stats_all), join(lines))
            val split = listOf(
                activity.getString(R.string.paper_split_mis) to book.trades.filter { it.product == Product.MIS },
                activity.getString(R.string.paper_split_cnc) to book.trades.filter { it.product == Product.CNC },
                activity.getString(R.string.paper_split_app) to book.trades.filter { it.source != PaperSource.MANUAL },
                activity.getString(R.string.paper_split_manual) to book.trades.filter { it.source == PaperSource.MANUAL },
            ).filter { it.second.isNotEmpty() }.map { (label, trades) ->
                val part = PaperEngine.stats(trades)
                toned(activity.getString(R.string.paper_stats_split_line, label, part.trades, percent1(part.winRate), PaperText.signed(part.net)), tone(part.net))
            }
            card(activity.getString(R.string.paper_stats_split_title), join(split))
        }
        card(
            activity.getString(R.string.paper_settings_title),
            activity.getString(
                R.string.paper_settings_body,
                StockFormat.price(book.start),
                DAY.withLocale(activity.dateLocale()).format(MarketClock.now(book.startedAt).toLocalDate()),
            ),
        )
        button(R.string.paper_reset) { reset() }
    }

    /** Starts the account over, with the starting money chosen. */
    private fun reset() {
        val current = PaperStore.load(activity).start
        val chips = ChipGroup(activity).apply {
            isSingleSelection = true
            isSelectionRequired = true
            for (choice in PaperEngine.CAPITAL_CHOICES) {
                val chip = activity.layoutInflater.inflate(R.layout.chip_choice, this, false) as Chip
                chip.id = android.view.View.generateViewId()
                chip.tag = choice
                chip.text = "₹" + StockFormat.count(choice.toInt())
                chip.isCheckable = true
                addView(chip)
                if (choice == current || (current !in PaperEngine.CAPITAL_CHOICES && choice == PaperEngine.DEFAULT_CAPITAL)) check(chip.id)
            }
        }
        val dp = activity.resources.displayMetrics.density
        val box = LinearLayout(activity).apply {
            val side = (20 * dp).roundToInt()
            setPadding(side, (8 * dp).roundToInt(), side, 0)
            addView(chips)
        }
        MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.paper_reset_title)
            .setMessage(R.string.paper_reset_body)
            .setView(box)
            .setNegativeButton(R.string.settings_confirm_no, null)
            .setPositiveButton(R.string.paper_reset_yes) { _, _ ->
                val capital = chips.findViewById<Chip>(chips.checkedChipId)?.tag as? Double ?: PaperEngine.DEFAULT_CAPITAL
                PaperStore.reset(activity, capital)
                Toast.makeText(activity, activity.getString(R.string.paper_reset_done, StockFormat.count(capital.toInt())), Toast.LENGTH_LONG).show()
                render()
            }
            .show()
    }

    private fun newOrder(preset: PaperOrderSheet.Preset?) {
        PaperOrderSheet(activity, stocks(), preset) {
            ui.paperTabs.getTabAt(2)?.let { if (tab != 2 && PaperStore.load(activity).orders.firstOrNull()?.open == true) it.select() }
            render()
        }.show()
    }

    /** A number box for the levels dialog. */
    private fun field(hint: Int, value: Double?): android.widget.EditText = android.widget.EditText(activity).apply {
        this.hint = activity.getString(hint)
        inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
        value?.let { setText(String.format(Locale.US, "%.2f", it)) }
        setTextColor(color(R.color.text_primary))
    }

    private fun value(edit: android.widget.EditText): Double? = edit.text?.toString()?.trim()?.replace(",", "")?.toDoubleOrNull()?.takeIf { it > 0.0 }

    private fun card(title: String, body: CharSequence): ItemHelpBinding {
        val card = ItemHelpBinding.inflate(activity.layoutInflater, ui.paperItems, true)
        card.helpTitle.text = title
        card.helpBody.text = body
        card.helpBody.setTextColor(color(R.color.text_primary))
        return card
    }

    private fun heading(text: String) {
        ui.paperItems.addView(
            TextView(activity).apply {
                this.text = text
                setTextColor(color(R.color.text_primary))
                textSize = 15f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
            },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = (16 * activity.resources.displayMetrics.density).roundToInt()
            },
        )
    }

    private fun button(text: Int, onClick: () -> Unit) {
        val button = MaterialButton(activity, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            setText(text)
            isAllCaps = false
            setOnClickListener { onClick() }
        }
        ui.paperItems.addView(
            button,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = (8 * activity.resources.displayMetrics.density).roundToInt()
            },
        )
    }

    @ColorRes
    private fun tone(value: Double): Int = if (value >= 0.0) R.color.bull else R.color.bear

    private fun toned(text: String, @ColorRes tone: Int): CharSequence = SpannableStringBuilder(text).apply {
        setSpan(ForegroundColorSpan(color(tone)), 0, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    }

    private fun join(lines: List<CharSequence>): CharSequence = SpannableStringBuilder().apply {
        lines.forEachIndexed { index, line ->
            if (index > 0) append('\n')
            append(line)
        }
    }

    private fun color(@ColorRes id: Int): Int = ContextCompat.getColor(activity, id)

    private companion object {
        const val AUTO_REFRESH_MS = 30_000L
        const val RECENT = 25
        const val RECENT_ORDERS = 40
        val DAY: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM yyyy")
        val WHEN: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM, HH:mm")

        /** "+1.23%", "−0.40%". */
        fun percent(value: Double): String = String.format(Locale.US, "%s%.2f%%", if (value >= 0) "+" else "−", abs(value))

        fun percent1(value: Double): String = String.format(Locale.US, "%.1f", value)
    }
}
