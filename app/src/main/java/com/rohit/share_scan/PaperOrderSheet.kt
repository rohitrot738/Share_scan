package com.rohit.share_scan

import android.content.res.ColorStateList
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.lifecycleScope
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.chip.Chip
import com.rohit.share_scan.OrderTicket.Side
import com.rohit.share_scan.PaperOrder.Type
import com.rohit.share_scan.PositionSize.Product
import com.rohit.share_scan.databinding.SheetPaperOrderBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * A new paper order: pick a stock (or come with one from its trade window, [Preset]), buy or sell, intraday or
 * delivery, the order type, the shares and prices, and an optional stop-loss and target the position is watched with.
 * The form shows the stock's price now, what the order will take from the account and its charges, and why it cannot
 * be placed when it cannot ([PaperEngine.check]).
 */
class PaperOrderSheet(
    private val activity: AppCompatActivity,
    /** The stocks to pick from. */
    private val stocks: List<ScanResult>,
    private val preset: Preset?,
    private val onPlaced: () -> Unit,
) {
    /** An order filled in for the user, from a trade plan or an open position. */
    data class Preset(
        val stock: ScanResult,
        val side: Side = Side.BUY,
        val product: Product = Product.CNC,
        val type: Type = Type.MARKET,
        val qty: Int? = null,
        val price: Double? = null,
        val trigger: Double? = null,
        val stop: Double? = null,
        val target: Double? = null,
        val source: PaperSource = PaperSource.MANUAL,
    )

    private val ui = SheetPaperOrderBinding.inflate(activity.layoutInflater)
    private lateinit var dialog: BottomSheetDialog
    private var stock: ScanResult? = preset?.stock
    private var ltp: Double? = null
    private var type: Type = preset?.type ?: Type.MARKET
    private val source: PaperSource = preset?.source ?: PaperSource.MANUAL

    fun show(): BottomSheetDialog {
        bindStock()
        ui.orderSide.check(if (preset?.side == Side.SELL) R.id.orderSell else R.id.orderBuy)
        ui.orderProduct.check(if (preset?.product == Product.MIS) R.id.orderMis else R.id.orderCnc)
        ui.orderSide.addOnButtonCheckedListener { _, _, checked -> if (checked) update() }
        ui.orderProduct.addOnButtonCheckedListener { _, _, checked -> if (checked) update() }
        for (choice in Type.entries) {
            val chip = activity.layoutInflater.inflate(R.layout.chip_choice, ui.orderType, false) as Chip
            chip.id = android.view.View.generateViewId()
            chip.text = activity.getString(
                when (choice) {
                    Type.MARKET -> R.string.paper_type_market_chip
                    Type.LIMIT -> R.string.paper_type_limit_chip
                    Type.SL -> R.string.paper_type_sl_chip
                    Type.SL_M -> R.string.paper_type_slm_chip
                },
            )
            chip.isCheckable = true
            chip.isChecked = choice == type
            chip.setOnClickListener {
                type = choice
                update()
            }
            ui.orderType.addView(chip)
        }
        preset?.qty?.let { ui.orderQty.setText(it.toString()) }
        preset?.price?.let { ui.orderPrice.setText(plain(it)) }
        preset?.trigger?.let { ui.orderTrigger.setText(plain(it)) }
        preset?.stop?.let { ui.orderStop.setText(plain(OrderTicket.tick(it))) }
        preset?.target?.let { ui.orderTarget.setText(plain(OrderTicket.tick(it))) }
        listOf(ui.orderQty, ui.orderPrice, ui.orderTrigger, ui.orderStop, ui.orderTarget).forEach { it.doAfterTextChanged { update() } }
        ui.orderPlace.setOnClickListener { place() }
        dialog = activity.showSheet(ui.root, ui.sheetTopBar, fullHeight = true)
        stock?.let(::quote)
        update()
        return dialog
    }

    private fun bindStock() {
        val fixed = preset?.stock
        if (fixed != null) {
            ui.orderStock.setText(label(fixed))
            ui.orderStock.isEnabled = false
            return
        }
        val labels = stocks.map(::label)
        ui.orderStock.setAdapter(ArrayAdapter(activity, android.R.layout.simple_dropdown_item_1line, labels))
        ui.orderStock.setOnItemClickListener { parent, _, position, _ ->
            val picked = parent.getItemAtPosition(position) as String
            pick(stocks.firstOrNull { label(it) == picked })
        }
        ui.orderStock.doAfterTextChanged { text ->
            val typed = text?.toString()?.trim().orEmpty()
            val match = stocks.firstOrNull { label(it) == typed || it.symbol.equals(typed, ignoreCase = true) }
            if (match?.symbol != stock?.symbol) pick(match)
        }
    }

    private fun pick(picked: ScanResult?) {
        stock = picked
        ltp = null
        picked?.let(::quote)
        update()
    }

    /** Reads the stock's price now (its latest 5-minute candle) for the order to go by. */
    private fun quote(stock: ScanResult) {
        ui.orderLtp.setText(R.string.paper_order_ltp_loading)
        activity.lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                runCatching { PaperStore.refresh(activity, mapOf(stock.symbol to PaperStore.ticker(stock))) }
            }
            if (this@PaperOrderSheet.stock?.symbol != stock.symbol) return@launch
            val quote = PaperStore.load(activity).quotes[stock.symbol]
            ltp = quote?.price ?: stock.price.takeIf { it > 0.0 }
            ui.orderLtp.text = when {
                quote != null -> activity.getString(R.string.paper_order_ltp, StockFormat.price(quote.price), candleTime(activity, quote.at))
                ltp != null -> activity.getString(R.string.paper_order_ltp_scan, StockFormat.price(ltp!!))
                else -> activity.getString(R.string.paper_order_ltp_failed)
            }
            update()
        }
    }

    private fun side(): Side = if (ui.orderSide.checkedButtonId == R.id.orderSell) Side.SELL else Side.BUY

    private fun product(): Product = if (ui.orderProduct.checkedButtonId == R.id.orderMis) Product.MIS else Product.CNC

    private fun number(field: com.google.android.material.textfield.TextInputEditText): Double? =
        field.text?.toString()?.trim()?.replace(",", "")?.toDoubleOrNull()?.takeIf { it > 0.0 }

    private fun draft(): PaperEngine.Draft? {
        val stock = stock ?: return null
        return PaperEngine.Draft(
            symbol = stock.symbol,
            ticker = PaperStore.ticker(stock),
            bse = stock.market == Market.BSE,
            side = side(),
            product = product(),
            type = type,
            qty = ui.orderQty.text?.toString()?.trim()?.toIntOrNull() ?: 0,
            price = if (type == Type.LIMIT || type == Type.SL) number(ui.orderPrice) else null,
            trigger = if (type == Type.SL || type == Type.SL_M) number(ui.orderTrigger) else null,
            stop = number(ui.orderStop),
            target = number(ui.orderTarget),
            source = source,
        )
    }

    /** The form after any change: which price boxes show, what the order takes, why it cannot go, the button. */
    private fun update() {
        ui.orderPriceBox.isVisible = type == Type.LIMIT || type == Type.SL
        ui.orderTriggerBox.isVisible = type == Type.SL || type == Type.SL_M
        val buy = side() == Side.BUY
        val tone = ContextCompat.getColor(activity, if (buy) R.color.bull else R.color.bear)
        ui.orderPlace.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(activity, if (buy) R.color.bull_container else R.color.bear_container))
        ui.orderPlace.setTextColor(tone)
        val draft = draft()
        val qty = draft?.qty ?: 0
        ui.orderPlace.text = activity.getString(
            if (buy) R.string.paper_order_place_buy else R.string.paper_order_place_sell,
            qty,
            stock?.symbol ?: "—",
        )
        if (draft == null) {
            ui.orderNeed.text = ""
            problem(activity.getString(R.string.paper_order_stock_missing))
            return
        }
        val book = PaperStore.load(activity)
        val now = System.currentTimeMillis()
        val reference = when (type) {
            Type.MARKET -> ltp
            Type.LIMIT -> draft.price
            else -> draft.trigger
        }
        val lines = mutableListOf<String>()
        if (reference != null && qty > 0) {
            val value = qty * reference
            val charges = PaperCharges.of(draft.product, draft.side, value, draft.bse).total
            val free = PaperEngine.available(book)
            lines += if (draft.product == Product.MIS) {
                activity.getString(R.string.paper_order_need_mis, StockFormat.price(value / PositionSize.MIS_LEVERAGE), StockFormat.price(value), StockFormat.price(charges), StockFormat.price(free))
            } else {
                activity.getString(R.string.paper_order_need, StockFormat.price(value), StockFormat.price(charges), StockFormat.price(free))
            }
            val stop = draft.stop
            if (stop != null) {
                val risk = kotlin.math.abs(reference - stop) * qty
                lines += activity.getString(R.string.paper_order_risk, StockFormat.price(risk), number2(risk / PaperEngine.worth(book).coerceAtLeast(1.0) * 100.0))
            }
        }
        // After hours an order waits for the next open; a new intraday position cannot, and says so below.
        if (!PaperEngine.inSession(now) && draft.product == Product.CNC) {
            lines += activity.getString(R.string.paper_order_amo, DAY.withLocale(activity.dateLocale()).format(PaperEngine.sessionFor(now)))
        }
        if (source != PaperSource.MANUAL) lines += activity.getString(R.string.paper_from_plan)
        ui.orderNeed.text = lines.joinToString("\n")
        problem(PaperEngine.check(book, draft, ltp, now)?.let { PaperText.problem(activity, it) })
    }

    private fun problem(text: String?) {
        ui.orderProblem.isVisible = text != null
        ui.orderProblem.text = text.orEmpty()
    }

    private fun place() {
        val draft = draft() ?: return
        val now = System.currentTimeMillis()
        val refused = PaperStore.update(activity) { PaperEngine.place(it, draft, ltp, now) }
        if (refused != null) {
            problem(PaperText.problem(activity, refused))
            return
        }
        PaperWorker.schedule(activity)
        val placed = PaperStore.load(activity).orders.firstOrNull { it.symbol == draft.symbol && it.placedAt == now }
        val message = when (placed?.status) {
            PaperOrder.Status.FILLED -> activity.getString(R.string.paper_order_filled_now, PaperText.side(activity, draft.side), draft.qty, draft.symbol, StockFormat.price(placed.fillPrice ?: 0.0))
            else -> activity.getString(R.string.paper_order_waiting, PaperText.side(activity, draft.side), draft.qty, draft.symbol)
        }
        Toast.makeText(activity, message, Toast.LENGTH_LONG).show()
        dialog.dismiss()
        onPlaced()
    }

    private fun label(stock: ScanResult): String =
        if (stock.name.isBlank()) stock.symbol else stock.symbol + " · " + StockSearch.shortName(stock.name)

    private companion object {
        val DAY: DateTimeFormatter = DateTimeFormatter.ofPattern("EEE, d MMM")

        fun plain(value: Double): String = String.format(Locale.US, "%.2f", value)

        /** "1", "0.05", "1.25": up to two decimals, so a small share does not read as 0. */
        fun number2(value: Double): String = (Math.round(value * 100) / 100.0).toBigDecimal().stripTrailingZeros().toPlainString()
    }
}
