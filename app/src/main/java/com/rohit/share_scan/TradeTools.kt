package com.rohit.share_scan

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import android.view.ViewGroup
import android.widget.Toast
import androidx.annotation.ColorRes
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.lifecycleScope
import com.google.android.material.chip.Chip
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.rohit.share_scan.databinding.CardTradeToolsBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.math.abs

/**
 * The trade window's tools under the plan:
 *
 * - **कितने शेयर** ([PositionSize]): the capital, the risk a trade may take and delivery or intraday give the quantity,
 *   what it costs, the loss at the stop and the gain at each target; delivery uses the swing plan, intraday today's
 *   intraday call when there is one.
 * - **Order ticket** ([OrderTicket]): the orders to place, to copy or share; nothing is ever sent.
 * - **Trade log**: "यह trade लिया" writes it down ([TradeStore]) after the risk guard ([RiskGuard]) has had its say.
 * - **Smart money and odds**: the open fair value gaps and order blocks nearest the price ([SmartMoney]), drawn on the
 *   chart through [onZones], and the plan's odds over [TimeMachine.PATHS] paths of the stock's own days.
 */
class TradeTools(
    private val activity: AppCompatActivity,
    private val stock: ScanResult,
    private val plan: TradePlan?,
    parent: ViewGroup,
    private val onZones: (List<PriceChartView.Level>) -> Unit,
) {
    private val ui = CardTradeToolsBinding.inflate(activity.layoutInflater, parent, true)
    private var ticket = ""
    private var loaded = false

    /** Today's intraday call, once read: intraday sizing uses its levels. */
    var intraday: IntradayPlan? = null
        set(value) {
            field = value
            refresh()
        }

    /** The levels a trade is sized and ticketed on. */
    private data class Basis(
        val side: OrderTicket.Side,
        val entry: Double,
        val stop: Double,
        val targets: List<Double>,
        val waitsForBreakout: Boolean,
        val fromIntraday: Boolean,
    )

    fun bind() {
        val capital = TradeStore.capital(activity)
        if (capital > 0.0) ui.sizeCapital.setText(String.format(Locale.US, "%.0f", capital))
        ui.sizeCapital.doAfterTextChanged { text ->
            val value = text?.toString()?.trim()?.toDoubleOrNull() ?: 0.0
            TradeStore.setCapital(activity, value)
            refresh()
        }
        val risk = TradeStore.risk(activity)
        for (choice in PositionSize.RISK_CHOICES) {
            val chip = activity.layoutInflater.inflate(R.layout.chip_choice, ui.sizeRisk, false) as Chip
            chip.id = android.view.View.generateViewId()
            chip.text = activity.getString(R.string.size_risk_choice, number(choice))
            chip.isCheckable = true
            chip.isChecked = choice == risk
            chip.setOnClickListener {
                TradeStore.setRisk(activity, choice)
                refresh()
            }
            ui.sizeRisk.addView(chip)
        }
        ui.sizeProduct.check(if (TradeStore.product(activity) == PositionSize.Product.MIS) R.id.sizeMis else R.id.sizeCnc)
        ui.sizeProduct.addOnButtonCheckedListener { _, id, checked ->
            if (!checked) return@addOnButtonCheckedListener
            TradeStore.setProduct(activity, if (id == R.id.sizeMis) PositionSize.Product.MIS else PositionSize.Product.CNC)
            refresh()
        }
        ui.sizeCopy.setOnClickListener { copy() }
        ui.sizeShare.setOnClickListener { share() }
        ui.sizeLog.setOnClickListener { log() }
        ui.sizePaper.setOnClickListener { paper() }
        refresh()
    }

    /** Reads the daily candles once, then fills the smart-money zones and the odds. */
    fun load() {
        if (loaded) return
        loaded = true
        activity.lifecycleScope.launch {
            val candles = withContext(Dispatchers.IO) { runCatching { IntradayRepository.daily(stock) }.getOrNull() }
            if (candles == null || candles.size < TimeMachine.MIN_MOVES + 1) {
                ui.toolsStatus.setText(if (candles == null) R.string.smc_failed else R.string.smc_short)
                return@launch
            }
            val zones = SmartMoney.openZones(candles)
            val near = SmartMoney.nearby(zones, stock.price)
            val plan = plan
            val odds = plan?.let {
                withContext(Dispatchers.Default) {
                    TimeMachine.odds(
                        TimeMachine.moves(candles),
                        entry = it.entry,
                        stop = it.stop,
                        targets = it.targets.take(2),
                        steps = it.targetSessions.getOrNull(1)?.last?.coerceIn(5, 40) ?: 20,
                    )
                }
            }
            ui.toolsStatus.text = activity.getString(R.string.smc_status, candles.size)
            ui.toolsCard.isVisible = true
            showSmc(zones, near)
            showOdds(odds)
        }
    }

    private fun showSmc(zones: List<SmartMoney.Zone>, near: SmartMoney.Nearby) {
        val gaps = zones.count { it.kind == SmartMoney.Kind.BULLISH_FVG || it.kind == SmartMoney.Kind.BEARISH_FVG }
        val lines = mutableListOf<CharSequence>(activity.getString(R.string.smc_count, gaps, zones.size - gaps))
        lines += near.support?.let { zone ->
            toned(
                activity.getString(R.string.smc_support, kind(zone.kind), StockFormat.price(zone.low), StockFormat.price(zone.high), awayPct(zone.high)),
                R.color.bull,
            )
        } ?: activity.getString(R.string.smc_no_support)
        lines += near.resistance?.let { zone ->
            toned(
                activity.getString(R.string.smc_resistance, kind(zone.kind), StockFormat.price(zone.low), StockFormat.price(zone.high), awayPct(zone.low)),
                R.color.bear,
            )
        } ?: activity.getString(R.string.smc_no_resistance)
        ui.smcLines.text = join(lines)
        onZones(
            listOfNotNull(
                near.support?.let { PriceChartView.Level(it.high, activity.getString(R.string.smc_chart_support, StockFormat.price(it.high)), color(R.color.bull)) },
                near.resistance?.let { PriceChartView.Level(it.low, activity.getString(R.string.smc_chart_resistance, StockFormat.price(it.low)), color(R.color.bear)) },
            ),
        )
    }

    private fun showOdds(odds: TimeMachine.Odds?) {
        val plan = plan
        if (plan == null || odds == null) {
            ui.oddsLines.setText(R.string.odds_none)
            ui.oddsNote.isVisible = false
            return
        }
        val lines = mutableListOf<CharSequence>()
        odds.targets.forEachIndexed { index, pct ->
            lines += toned(
                activity.getString(R.string.odds_target, index + 1, StockFormat.price(plan.targets[index]), number(pct)),
                if (pct >= odds.stop) R.color.bull else R.color.text_primary,
            )
        }
        lines += toned(activity.getString(R.string.odds_stop, StockFormat.price(plan.stop), number(odds.stop)), R.color.bear)
        lines += activity.getString(R.string.odds_neither, odds.steps, number(odds.neither))
        ui.oddsLines.text = join(lines)
        ui.oddsNote.isVisible = true
        ui.oddsNote.text = activity.getString(R.string.odds_note, odds.paths, odds.moves, StockFormat.price(plan.entry))
    }

    /** The levels to size on: intraday's call for MIS when there is one, else the swing plan. */
    private fun basis(product: PositionSize.Product): Basis? {
        val call = intraday
        val entry = call?.entry
        val stop = call?.stop
        if (product == PositionSize.Product.MIS && call != null && entry != null && stop != null &&
            (call.side == IntradayPlan.Side.BUY || call.side == IntradayPlan.Side.SELL)
        ) {
            val side = if (call.side == IntradayPlan.Side.BUY) OrderTicket.Side.BUY else OrderTicket.Side.SELL
            return Basis(side, entry, stop, call.targets, waitsForBreakout = false, fromIntraday = true)
        }
        val plan = plan ?: return null
        return Basis(
            OrderTicket.Side.BUY,
            plan.entry,
            plan.stop,
            plan.targets,
            waitsForBreakout = plan.entryKind == TradePlan.EntryKind.BREAKOUT,
            fromIntraday = false,
        )
    }

    private fun refresh() {
        val product = TradeStore.product(activity)
        val capital = TradeStore.capital(activity)
        val basis = basis(product)
        ui.sizeBasis.text = when {
            basis == null -> activity.getString(R.string.size_no_plan)
            basis.fromIntraday -> activity.getString(
                R.string.size_basis_intraday,
                side(basis.side),
                StockFormat.price(basis.entry),
                StockFormat.price(basis.stop),
            )
            product == PositionSize.Product.MIS -> activity.getString(R.string.size_basis_swing_mis, StockFormat.price(basis.entry), StockFormat.price(basis.stop))
            else -> activity.getString(R.string.size_basis_swing, StockFormat.price(basis.entry), StockFormat.price(basis.stop))
        }
        val risk = TradeStore.risk(activity)
        val size = basis?.let { PositionSize.of(capital, risk, it.entry, it.stop, it.targets, product) }
        ui.sizeResult.text = when {
            basis == null -> activity.getString(R.string.size_no_plan_short)
            capital <= 0.0 -> activity.getString(R.string.size_need_capital)
            size == null -> activity.getString(R.string.size_no_plan_short)
            size.qty == 0 -> activity.getString(R.string.size_zero, StockFormat.price(basis.entry), StockFormat.price(size.riskMoney))
            else -> sizeText(size, product, capital)
        }
        val qty = size?.qty ?: 0
        ticket = if (basis != null && qty > 0) ticketText(basis, product, qty) else ""
        ui.sizeTicket.text = ticket.ifEmpty { activity.getString(R.string.ticket_waiting) }
        ui.sizeCopy.isEnabled = ticket.isNotEmpty()
        ui.sizeShare.isEnabled = ticket.isNotEmpty()
        ui.sizeLog.isEnabled = qty > 0
        guard(capital, product)
    }

    private fun sizeText(size: PositionSize.Result, product: PositionSize.Product, capital: Double): CharSequence {
        val head = SpannableStringBuilder(activity.getString(R.string.size_qty, size.qty)).apply {
            setSpan(StyleSpan(android.graphics.Typeface.BOLD), 0, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            setSpan(RelativeSizeSpan(1.25f), 0, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        val lines = mutableListOf<CharSequence>(head)
        lines += if (product == PositionSize.Product.MIS) {
            activity.getString(R.string.size_cost_mis, StockFormat.price(size.cost), StockFormat.price(size.margin))
        } else {
            activity.getString(R.string.size_cost, StockFormat.price(size.cost), number(size.cost / capital * 100.0))
        }
        lines += toned(activity.getString(R.string.size_loss, StockFormat.price(size.loss), number(size.loss / capital * 100.0)), R.color.bear)
        size.gains.forEachIndexed { index, gain ->
            val r = if (size.loss > 0.0) gain / size.loss else 0.0
            lines += toned(activity.getString(R.string.size_gain, index + 1, StockFormat.price(gain), number(r)), R.color.bull)
        }
        if (size.limit == PositionSize.Limit.MONEY) {
            lines += toned(
                activity.getString(if (product == PositionSize.Product.MIS) R.string.size_limit_mis else R.string.size_limit_cnc),
                R.color.warn,
            )
        }
        return join(lines)
    }

    private fun ticketText(basis: Basis, product: PositionSize.Product, qty: Int): String {
        val target = basis.targets.firstOrNull() ?: return ""
        val orders = OrderTicket.orders(basis.side, product, basis.waitsForBreakout, basis.entry, basis.stop, target, qty)
        val lines = mutableListOf(
            activity.getString(
                R.string.ticket_head,
                stock.symbol,
                activity.getString(MarketStyle.of(stock.market).label),
                activity.getString(if (product == PositionSize.Product.MIS) R.string.size_mis else R.string.size_cnc),
            ),
        )
        orders.forEachIndexed { index, order ->
            val n = index + 1
            lines += when (order.kind) {
                OrderTicket.Kind.SL_LIMIT -> activity.getString(
                    R.string.ticket_sl_limit, n, side(order.side), order.qty, tick(order.trigger), tick(order.price),
                )
                OrderTicket.Kind.LIMIT -> activity.getString(
                    if (index == 0) R.string.ticket_limit else R.string.ticket_target_limit, n, side(order.side), order.qty, tick(order.price),
                )
                OrderTicket.Kind.SL_MARKET -> activity.getString(R.string.ticket_sl_market, n, side(order.side), order.qty, tick(order.trigger))
                OrderTicket.Kind.GTT_OCO -> activity.getString(
                    R.string.ticket_gtt, n, side(order.side), order.qty, tick(order.trigger), tick(order.target),
                )
            }
        }
        if (product == PositionSize.Product.MIS) lines += activity.getString(R.string.ticket_mis_note)
        lines += activity.getString(R.string.ticket_note)
        return lines.joinToString("\n")
    }

    /** Today's trades against the guard's rules, under the log button. */
    private fun guard(capital: Double, product: PositionSize.Product) {
        val trades = TradeStore.trades(activity)
        val day = TradeStore.today()
        val rules = TradeStore.rules(activity)
        val today = TradeLog.today(trades, day)
        val lines = mutableListOf<CharSequence>(
            activity.getString(
                R.string.guard_today,
                today.size,
                rules.maxTrades,
                signedRupees(TradeLog.realized(trades, day)),
            ),
        )
        if (capital > 0.0) lines += activity.getString(R.string.guard_loss_left, StockFormat.price(RiskGuard.lossLeft(trades, day, capital, rules)))
        RiskGuard.check(trades, day, MarketClock.now().toLocalTime(), capital, product, rules).forEach {
            lines += toned(breach(it, rules), R.color.warn)
        }
        ui.sizeGuard.text = join(lines)
    }

    private fun breach(breach: RiskGuard.Breach, rules: RiskGuard.Rules): String = when (breach) {
        RiskGuard.Breach.TRADES -> activity.getString(R.string.guard_trades, rules.maxTrades)
        RiskGuard.Breach.LOSS -> activity.getString(R.string.guard_loss, number(rules.dailyLossPct))
        RiskGuard.Breach.LATE -> activity.getString(R.string.guard_late)
    }

    /** Writes the trade down, asking first when the risk guard says no. */
    private fun log() {
        val product = TradeStore.product(activity)
        val capital = TradeStore.capital(activity)
        val basis = basis(product) ?: return
        val size = PositionSize.of(capital, TradeStore.risk(activity), basis.entry, basis.stop, basis.targets, product) ?: return
        if (size.qty <= 0) return
        val now = System.currentTimeMillis()
        val trade = LoggedTrade(
            id = now,
            day = TradeStore.today(),
            symbol = stock.symbol,
            side = basis.side,
            product = product,
            qty = size.qty,
            entry = basis.entry,
            stop = basis.stop,
            target = basis.targets.firstOrNull(),
        )
        val rules = TradeStore.rules(activity)
        val breaches = RiskGuard.check(TradeStore.trades(activity), trade.day, MarketClock.now().toLocalTime(), capital, product, rules)
        val write = {
            TradeStore.log(activity, trade)
            Toast.makeText(activity, activity.getString(R.string.log_added, stock.symbol), Toast.LENGTH_LONG).show()
            refresh()
        }
        if (breaches.isEmpty()) {
            write()
        } else {
            MaterialAlertDialogBuilder(activity)
                .setTitle(R.string.guard_title)
                .setMessage(breaches.joinToString("\n\n") { breach(it, rules) })
                .setNegativeButton(R.string.guard_skip, null)
                .setPositiveButton(R.string.guard_anyway) { _, _ -> write() }
                .show()
        }
    }

    /**
     * The trade on the paper account: an order filled in from the plan (or today's intraday call for MIS), sized at
     * the chosen risk of the paper account's worth, to check and place in [PaperOrderSheet].
     */
    private fun paper() {
        val product = TradeStore.product(activity)
        val basis = basis(product)
        val worth = PaperEngine.worth(PaperStore.load(activity))
        val size = basis?.let { PositionSize.of(worth, TradeStore.risk(activity), it.entry, it.stop, it.targets, product) }
        val breakout = basis?.waitsForBreakout == true
        val preset = PaperOrderSheet.Preset(
            stock = stock,
            side = basis?.side ?: OrderTicket.Side.BUY,
            product = product,
            type = when {
                basis == null -> PaperOrder.Type.MARKET
                breakout -> PaperOrder.Type.SL
                else -> PaperOrder.Type.LIMIT
            },
            qty = size?.qty?.takeIf { it > 0 },
            price = basis?.let { OrderTicket.tick(if (breakout) it.entry * (1.0 + OrderTicket.ENTRY_SLIPPAGE) else it.entry) },
            trigger = basis?.takeIf { breakout }?.let { OrderTicket.tick(it.entry) },
            stop = basis?.stop,
            target = basis?.targets?.firstOrNull(),
            source = when {
                basis == null -> PaperSource.MANUAL
                basis.fromIntraday -> PaperSource.INTRADAY
                else -> PaperSource.PLAN
            },
        )
        PaperOrderSheet(activity, listOf(stock), preset) {}.show()
    }

    private fun copy() {
        if (ticket.isEmpty()) return
        val clipboard = activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText(stock.symbol, ticket))
        Toast.makeText(activity, R.string.ticket_copied, Toast.LENGTH_SHORT).show()
    }

    private fun share() {
        if (ticket.isEmpty()) return
        val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, ticket)
        runCatching { activity.startActivity(Intent.createChooser(send, activity.getString(R.string.ticket_share))) }
    }

    private fun side(side: OrderTicket.Side): String =
        activity.getString(if (side == OrderTicket.Side.BUY) R.string.ticket_buy else R.string.ticket_sell)

    private fun kind(kind: SmartMoney.Kind): String = activity.getString(
        when (kind) {
            SmartMoney.Kind.BULLISH_FVG -> R.string.smc_bullish_fvg
            SmartMoney.Kind.BEARISH_FVG -> R.string.smc_bearish_fvg
            SmartMoney.Kind.BULLISH_OB -> R.string.smc_bullish_ob
            SmartMoney.Kind.BEARISH_OB -> R.string.smc_bearish_ob
        },
    )

    /** How far [level] is from the price, "2.4%"; inside the zone, "0%". */
    private fun awayPct(level: Double): String = number(abs(level / stock.price - 1.0) * 100.0) + "%"

    private fun tick(value: Double?): String = value?.let { String.format(Locale.US, "%.2f", it) } ?: "—"

    private fun signedRupees(value: Double): String = when {
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
        /** "1", "1.5", "12.3": one place where it has one. */
        fun number(value: Double): String =
            (Math.round(value * 10) / 10.0).toBigDecimal().stripTrailingZeros().toPlainString()
    }
}
