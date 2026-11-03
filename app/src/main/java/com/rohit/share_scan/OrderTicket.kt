package com.rohit.share_scan

import kotlin.math.roundToLong

/**
 * Paper order tickets (the /trader-broker and /short-broker skills): the orders to place for a plan, as Zerodha Kite,
 * Angel One and Groww name them. The app never sends an order; it shows these to copy into the broker's app.
 *
 * - **Entry**: a buy above today's price waits for the breakout as a stop-limit order (trigger at the entry, limit a
 *   little above, [ENTRY_SLIPPAGE], so a fast move still fills); at or below the price it is a plain limit order.
 * - **Exits**, once the entry fills: for delivery (CNC) one GTT OCO with the stop's trigger and the target's; for
 *   intraday (MIS) a stop-loss market order at the stop and a limit order at the target, squared off by 15:15.
 *
 * Prices are rounded to the exchange's tick of [TICK]. Free of Android types so it can be unit tested.
 */
object OrderTicket {
    enum class Side { BUY, SELL }

    enum class Kind { LIMIT, SL_LIMIT, SL_MARKET, GTT_OCO }

    data class Order(
        val kind: Kind,
        val side: Side,
        val qty: Int,
        /** The limit price; null for a market order. */
        val price: Double?,
        /** The trigger of a stop order, and the stop's trigger of a GTT OCO. */
        val trigger: Double?,
        /** The target's trigger of a GTT OCO. */
        val target: Double? = null,
    )

    const val TICK = 0.05

    /** How far past its trigger a breakout's stop-limit entry may fill. */
    const val ENTRY_SLIPPAGE = 0.005

    /** [price] on the exchange's 5-paisa tick. */
    fun tick(price: Double): Double = (price / TICK).roundToLong() * TICK

    /**
     * The orders for a trade of [qty] shares: the entry, then the exits. [waitsForBreakout] says the entry is above the
     * price today (below it for a sell), so it waits for the move.
     */
    fun orders(
        side: Side,
        product: PositionSize.Product,
        waitsForBreakout: Boolean,
        entry: Double,
        stop: Double,
        target: Double,
        qty: Int,
    ): List<Order> {
        val exit = if (side == Side.BUY) Side.SELL else Side.BUY
        val slip = if (side == Side.BUY) 1.0 + ENTRY_SLIPPAGE else 1.0 - ENTRY_SLIPPAGE
        val open = if (waitsForBreakout) {
            Order(Kind.SL_LIMIT, side, qty, price = tick(entry * slip), trigger = tick(entry))
        } else {
            Order(Kind.LIMIT, side, qty, price = tick(entry), trigger = null)
        }
        val exits = when (product) {
            PositionSize.Product.CNC -> listOf(Order(Kind.GTT_OCO, exit, qty, price = tick(target), trigger = tick(stop), target = tick(target)))
            PositionSize.Product.MIS -> listOf(
                Order(Kind.SL_MARKET, exit, qty, price = null, trigger = tick(stop)),
                Order(Kind.LIMIT, exit, qty, price = tick(target), trigger = null),
            )
        }
        return listOf(open) + exits
    }
}
