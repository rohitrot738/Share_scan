package com.rohit.share_scan

import android.text.InputType
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.annotation.ColorRes
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButton
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.rohit.share_scan.databinding.ItemHelpBinding
import com.rohit.share_scan.databinding.SheetListBinding
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

/**
 * The trade log (Menu → Trade log): the trades written down from the trade window ([TradeTools]), today's first, each
 * with its profit or loss (at the scan's latest price while open), and today's standing against the risk guard
 * ([RiskGuard]). A tap on a trade closes it at a price, opens its stock or deletes it; the rules and the capital are
 * set at the top.
 */
class TradeLogSheet(
    private val activity: AppCompatActivity,
    /** The scan's latest price for a symbol; null without one. */
    private val priceOf: (String) -> Double?,
    private val onStock: (String) -> Unit,
    private val onBack: () -> Unit,
) {
    private val ui = SheetListBinding.inflate(activity.layoutInflater)
    private lateinit var dialog: BottomSheetDialog

    fun show(): BottomSheetDialog {
        ui.listTitle.setText(R.string.log_title)
        ui.listSubtitle.setText(R.string.log_subtitle)
        dialog = activity.showSheet(ui.root, ui.sheetTopBar, onBack, fullHeight = true)
        render()
        return dialog
    }

    private fun render() {
        ui.listItems.removeAllViews()
        val trades = TradeStore.trades(activity)
        val day = TradeStore.today()
        val capital = TradeStore.capital(activity)
        val rules = TradeStore.rules(activity)

        // The rules and today's standing.
        val today = TradeLog.today(trades, day)
        val standing = mutableListOf<CharSequence>(
            activity.getString(
                R.string.log_rules,
                if (capital > 0.0) "₹" + StockFormat.price(capital) else activity.getString(R.string.log_capital_unset),
                number(TradeStore.risk(activity)),
                rules.maxTrades,
                number(rules.dailyLossPct),
            ),
            activity.getString(R.string.guard_today, today.size, rules.maxTrades, signed(TradeLog.realized(trades, day))),
        )
        if (capital > 0.0) standing += activity.getString(R.string.guard_loss_left, StockFormat.price(RiskGuard.lossLeft(trades, day, capital, rules)))
        val time = MarketClock.now().toLocalTime()
        RiskGuard.check(trades, day, time, capital, PositionSize.Product.MIS, rules).forEach {
            standing += toned(
                when (it) {
                    RiskGuard.Breach.TRADES -> activity.getString(R.string.guard_trades, rules.maxTrades)
                    RiskGuard.Breach.LOSS -> activity.getString(R.string.guard_loss, number(rules.dailyLossPct))
                    RiskGuard.Breach.LATE -> activity.getString(R.string.guard_late)
                },
                R.color.warn,
            )
        }
        val open = TradeLog.openIntraday(trades, day)
        if (open.isNotEmpty()) standing += toned(activity.getString(R.string.log_square_off, open.size), R.color.warn)
        card(activity.getString(R.string.log_today), join(standing)).root.setOnClickListener { settings() }
        button(R.string.log_settings) { settings() }

        if (trades.isEmpty()) {
            card(activity.getString(R.string.log_empty_title), activity.getString(R.string.log_empty_body))
            return
        }
        trades.groupBy { it.day }.toSortedMap(compareByDescending<java.time.LocalDate> { it }).forEach { (date, list) ->
            heading(if (date == day) activity.getString(R.string.log_day_today) else DAY.withLocale(activity.dateLocale()).format(date))
            list.forEach(::tradeCard)
        }
    }

    private fun tradeCard(trade: LoggedTrade) {
        val title = activity.getString(
            if (trade.side == OrderTicket.Side.BUY) R.string.log_trade_buy else R.string.log_trade_sell,
            trade.symbol,
            trade.qty,
            activity.getString(if (trade.product == PositionSize.Product.MIS) R.string.size_mis else R.string.size_cnc),
        )
        val lines = mutableListOf<CharSequence>(
            activity.getString(
                R.string.log_trade_levels,
                StockFormat.price(trade.entry),
                StockFormat.price(trade.stop),
                trade.target?.let(StockFormat::price) ?: "—",
            ),
        )
        val exit = trade.exit
        val price = priceOf(trade.symbol)
        lines += when {
            exit != null -> pnl(activity.getString(R.string.log_trade_closed, StockFormat.price(exit), signed(trade.pnl(exit))), trade.pnl(exit))
            price != null -> pnl(activity.getString(R.string.log_trade_open, StockFormat.price(price), signed(trade.pnl(price))), trade.pnl(price))
            else -> activity.getString(R.string.log_trade_open_no_price)
        }
        card(title, join(lines)).root.setOnClickListener { actions(trade) }
    }

    /** What a tap on a trade offers: close it at a price, open its stock, or delete it. */
    private fun actions(trade: LoggedTrade) {
        val items = buildList {
            if (trade.open) add(R.string.log_action_close)
            add(R.string.log_action_open)
            add(R.string.log_action_delete)
        }
        MaterialAlertDialogBuilder(activity)
            .setTitle(trade.symbol)
            .setItems(items.map(activity::getString).toTypedArray()) { _, which ->
                when (items[which]) {
                    R.string.log_action_close -> close(trade)
                    R.string.log_action_open -> {
                        dialog.dismiss()
                        onStock(trade.symbol)
                    }
                    R.string.log_action_delete -> {
                        TradeStore.saveTrades(activity, TradeLog.remove(TradeStore.trades(activity), trade.id))
                        render()
                    }
                }
            }
            .show()
    }

    private fun close(trade: LoggedTrade) {
        val field = numberField(priceOf(trade.symbol)?.let { String.format(Locale.US, "%.2f", it) }.orEmpty(), decimal = true)
        MaterialAlertDialogBuilder(activity)
            .setTitle(activity.getString(R.string.log_close_title, trade.symbol))
            .setMessage(R.string.log_close_body)
            .setView(padded(field))
            .setNegativeButton(R.string.settings_confirm_no, null)
            .setPositiveButton(R.string.log_close_save) { _, _ ->
                val price = field.text.toString().trim().replace(",", "").toDoubleOrNull()
                if (price == null || !(price > 0.0)) {
                    Toast.makeText(activity, R.string.watch_edit_invalid, Toast.LENGTH_SHORT).show()
                } else {
                    TradeStore.saveTrades(activity, TradeLog.close(TradeStore.trades(activity), trade.id, price, System.currentTimeMillis()))
                    render()
                }
            }
            .show()
    }

    /** The capital, the risk a trade takes, and the guard's two rules. */
    private fun settings() {
        val rules = TradeStore.rules(activity)
        val dp = activity.resources.displayMetrics.density
        val capital = numberField(TradeStore.capital(activity).takeIf { it > 0.0 }?.let { String.format(Locale.US, "%.0f", it) }.orEmpty(), decimal = false)
        capital.hint = activity.getString(R.string.size_capital_hint)
        val risk = chips(PositionSize.RISK_CHOICES, TradeStore.risk(activity)) { activity.getString(R.string.size_risk_choice, number(it)) }
        val trades = chips(RiskGuard.MAX_TRADES_CHOICES, rules.maxTrades) { it.toString() }
        val loss = chips(RiskGuard.DAILY_LOSS_CHOICES, rules.dailyLossPct) { activity.getString(R.string.size_risk_choice, number(it)) }
        val box = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            val side = (20 * dp).roundToInt()
            setPadding(side, (8 * dp).roundToInt(), side, 0)
            addView(capital)
            addView(label(R.string.size_risk_label))
            addView(risk)
            addView(label(R.string.log_rule_trades))
            addView(trades)
            addView(label(R.string.log_rule_loss))
            addView(loss)
        }
        if (activity.wearsFlat) box.keepFlat()
        MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.log_settings)
            .setView(android.widget.ScrollView(activity).apply { addView(box) })
            .setNegativeButton(R.string.settings_confirm_no, null)
            .setPositiveButton(R.string.log_close_save) { _, _ ->
                capital.text.toString().trim().toDoubleOrNull()?.let { TradeStore.setCapital(activity, it) }
                (risk.tagOfChecked() as? Double)?.let { TradeStore.setRisk(activity, it) }
                TradeStore.setRules(
                    activity,
                    trades.tagOfChecked() as? Int ?: rules.maxTrades,
                    loss.tagOfChecked() as? Double ?: rules.dailyLossPct,
                )
                render()
            }
            .show()
    }

    private fun <T> chips(choices: List<T>, chosen: T, label: (T) -> String): ChipGroup = ChipGroup(activity).apply {
        isSingleSelection = true
        isSelectionRequired = true
        for (choice in choices) {
            val chip = activity.layoutInflater.inflate(R.layout.chip_choice, this, false) as Chip
            chip.id = android.view.View.generateViewId()
            chip.tag = choice
            chip.text = label(choice)
            chip.isCheckable = true
            addView(chip)
            if (choice == chosen) check(chip.id)
        }
    }

    private fun ChipGroup.tagOfChecked(): Any? = findViewById<Chip>(checkedChipId)?.tag

    private fun label(text: Int): TextView = TextView(activity).apply {
        setText(text)
        setTextColor(color(R.color.text_secondary))
        textSize = 12.5f
        setPadding(0, (12 * resources.displayMetrics.density).roundToInt(), 0, 0)
    }

    private fun numberField(value: String, decimal: Boolean): EditText = EditText(activity).apply {
        inputType = InputType.TYPE_CLASS_NUMBER or if (decimal) InputType.TYPE_NUMBER_FLAG_DECIMAL else 0
        setText(value)
        setSelection(text.length)
        setTextColor(color(R.color.text_primary))
    }

    private fun padded(field: EditText) = android.widget.FrameLayout(activity).apply {
        val dp = activity.resources.displayMetrics.density
        setPadding((20 * dp).roundToInt(), (4 * dp).roundToInt(), (20 * dp).roundToInt(), 0)
        addView(field, android.widget.FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }

    private fun card(title: String, body: CharSequence): ItemHelpBinding {
        val card = ItemHelpBinding.inflate(activity.layoutInflater, ui.listItems, true)
        card.helpTitle.text = title
        card.helpBody.text = body
        return card
    }

    private fun heading(text: String) {
        ui.listItems.addView(
            TextView(activity).apply {
                this.text = text
                setTextColor(color(R.color.text_primary))
                textSize = 15f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
            },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = (18 * activity.resources.displayMetrics.density).roundToInt()
            },
        )
    }

    private fun button(text: Int, onClick: () -> Unit) {
        val button = MaterialButton(activity, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            setText(text)
            isAllCaps = false
            setOnClickListener { onClick() }
        }
        ui.listItems.addView(
            button,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = (8 * activity.resources.displayMetrics.density).roundToInt()
            },
        )
    }

    private fun pnl(text: String, value: Double): CharSequence = toned(text, if (value >= 0.0) R.color.bull else R.color.bear)

    private fun signed(value: Double): String = when {
        value > 0.0 -> "+₹" + StockFormat.price(value)
        value < 0.0 -> "−₹" + StockFormat.price(-value)
        else -> "₹0"
    }

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
        val DAY: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM yyyy")

        fun number(value: Double): String = (Math.round(value * 10) / 10.0).toBigDecimal().stripTrailingZeros().toPlainString()
    }
}
