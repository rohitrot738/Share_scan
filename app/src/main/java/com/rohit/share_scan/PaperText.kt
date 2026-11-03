package com.rohit.share_scan

import android.content.Context
import com.rohit.share_scan.OrderTicket.Side
import com.rohit.share_scan.PositionSize.Product

/** The paper account's words: money, sides, order types, reasons and what happened, for the screen and notifications. */
object PaperText {
    fun money(value: Double): String = "₹" + StockFormat.price(value)

    /** "+₹1,234.50", "−₹80.00", "₹0". */
    fun signed(value: Double): String = when {
        value >= 0.005 -> "+₹" + StockFormat.price(value)
        value <= -0.005 -> "−₹" + StockFormat.price(-value)
        else -> "₹0"
    }

    fun side(context: Context, side: Side): String = context.getString(if (side == Side.BUY) R.string.ticket_buy else R.string.ticket_sell)

    fun product(context: Context, product: Product): String = context.getString(if (product == Product.MIS) R.string.size_mis else R.string.size_cnc)

    /** The order's type and prices: "Market", "Limit ₹990.00", "SL ₹1,010.00 (limit ₹1,015.00)", "SL-M ₹1,010.00". */
    fun type(context: Context, order: PaperOrder): String = when (order.type) {
        PaperOrder.Type.MARKET -> context.getString(R.string.paper_type_market)
        PaperOrder.Type.LIMIT -> context.getString(R.string.paper_type_limit, StockFormat.price(order.price ?: 0.0))
        PaperOrder.Type.SL -> context.getString(R.string.paper_type_sl, StockFormat.price(order.trigger ?: 0.0), StockFormat.price(order.price ?: 0.0))
        PaperOrder.Type.SL_M -> context.getString(R.string.paper_type_slm, StockFormat.price(order.trigger ?: 0.0))
    }

    fun reason(context: Context, reason: PaperReason?): String = context.getString(
        when (reason) {
            PaperReason.FUNDS -> R.string.paper_reason_funds
            PaperReason.NO_HOLDING -> R.string.paper_reason_no_holding
            PaperReason.CANCELLED, null -> R.string.paper_reason_cancelled
            PaperReason.EXPIRED -> R.string.paper_reason_expired
            PaperReason.MIS_CLOSED -> R.string.paper_reason_mis_closed
            PaperReason.STOP -> R.string.paper_reason_stop
            PaperReason.TARGET -> R.string.paper_reason_target
            PaperReason.SQUARE_OFF -> R.string.paper_reason_square_off
            PaperReason.EXIT -> R.string.paper_reason_exit
        },
    )

    /** What [problem] means, for the order form. */
    fun problem(context: Context, problem: PaperEngine.Problem): String = context.getString(
        when (problem) {
            PaperEngine.Problem.QTY -> R.string.paper_problem_qty
            PaperEngine.Problem.PRICE -> R.string.paper_problem_price
            PaperEngine.Problem.TRIGGER -> R.string.paper_problem_trigger
            PaperEngine.Problem.SL_PRICE -> R.string.paper_problem_sl_price
            PaperEngine.Problem.TRIGGER_CROSSED -> R.string.paper_problem_trigger_crossed
            PaperEngine.Problem.NO_PRICE -> R.string.paper_problem_no_price
            PaperEngine.Problem.MIS_HOURS -> R.string.paper_problem_mis_hours
            PaperEngine.Problem.NO_HOLDING -> R.string.paper_problem_no_holding
            PaperEngine.Problem.FUNDS -> R.string.paper_problem_funds
            PaperEngine.Problem.STOP_SIDE -> R.string.paper_problem_stop_side
            PaperEngine.Problem.TARGET_SIDE -> R.string.paper_problem_target_side
        },
    )

    /** One line for something the account did: "BUY 10 RELIANCE भरा ₹1,300.00 पर", "TCS: stop-loss लगा … −₹420.00". */
    fun event(context: Context, event: PaperEvent): String {
        val o = event.order
        val net = event.trade?.net?.let(::signed).orEmpty()
        return when (event.kind) {
            PaperEvent.Kind.FILLED -> context.getString(R.string.paper_event_filled, side(context, o.side), o.qty, o.symbol, StockFormat.price(o.fillPrice ?: 0.0)) +
                (event.trade?.let { " · " + signed(it.net) } ?: "")
            PaperEvent.Kind.STOP -> context.getString(R.string.paper_event_stop, o.symbol, StockFormat.price(o.fillPrice ?: 0.0), net)
            PaperEvent.Kind.TARGET -> context.getString(R.string.paper_event_target, o.symbol, StockFormat.price(o.fillPrice ?: 0.0), net)
            PaperEvent.Kind.SQUARE_OFF -> context.getString(R.string.paper_event_square_off, o.symbol, StockFormat.price(o.fillPrice ?: 0.0), net)
            PaperEvent.Kind.REJECTED -> context.getString(R.string.paper_event_rejected, side(context, o.side), o.qty, o.symbol, reason(context, o.reason))
            PaperEvent.Kind.EXPIRED -> context.getString(R.string.paper_event_expired, side(context, o.side), o.qty, o.symbol)
            PaperEvent.Kind.CANCELLED -> context.getString(R.string.paper_event_cancelled, side(context, o.side), o.qty, o.symbol, reason(context, o.reason))
        }
    }
}
