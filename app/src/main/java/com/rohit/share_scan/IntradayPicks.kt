package com.rohit.share_scan

/**
 * The app's own intraday picks: of the most traded stocks of the latest scan ([universe]), those whose opening-range
 * breakout ([IntradayPlan]) calls a buy and those that call a sell, the strongest first. Free of Android types so it
 * can be unit tested.
 */
object IntradayPicks {
    /** How many stocks are checked: the scan's most traded NSE main-board ones. */
    const val UNIVERSE = 120

    /** Too cheap or too thinly traded a stock moves in jumps an intraday stop cannot hold. */
    const val MIN_PRICE = 50.0
    const val MIN_TURNOVER_CR = 5.0

    data class Pick(val stock: ScanResult, val plan: IntradayPlan)

    data class Picks(
        val buys: List<Pick>,
        val sells: List<Pick>,
        /** Called today but no longer to be taken: late, stopped out or already at the last target. */
        val done: List<Pick>,
        /** Inside the range, or not yet past 9:30. */
        val waiting: Int,
    )

    /** The stocks to check: NSE main board, at least [MIN_PRICE] and [MIN_TURNOVER_CR] traded, most traded first. */
    fun universe(results: List<ScanResult>, limit: Int = UNIVERSE): List<ScanResult> =
        results.asSequence()
            .filter { it.market == Market.NSE && it.charted && it.price >= MIN_PRICE && it.turnoverCr >= MIN_TURNOVER_CR }
            .sortedByDescending { it.turnoverCr }
            .take(limit)
            .toList()

    fun of(checked: List<Pick>): Picks {
        val (open, closed) = checked.filter { it.plan.side == IntradayPlan.Side.BUY || it.plan.side == IntradayPlan.Side.SELL }
            .partition { it.plan.actionable }
        return Picks(
            buys = open.filter { it.plan.side == IntradayPlan.Side.BUY }.sortedByDescending { it.plan.strength },
            sells = open.filter { it.plan.side == IntradayPlan.Side.SELL }.sortedByDescending { it.plan.strength },
            done = closed.sortedByDescending { it.plan.triggeredAt ?: 0L },
            waiting = checked.count { it.plan.side == IntradayPlan.Side.WAIT || it.plan.side == IntradayPlan.Side.FORMING },
        )
    }
}
