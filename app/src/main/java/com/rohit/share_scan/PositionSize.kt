package com.rohit.share_scan

import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * How many shares a trade takes (the /trader-budget and /short-risk skills): as many as keep a stop-out to [riskPct]
 * percent of the capital, but for delivery (CNC) no more than [MAX_CNC_SHARE] of the capital in one stock, and for
 * intraday (MIS) no more than the broker's margin of about [MIS_LEVERAGE] times the capital allows. Free of Android
 * types so it can be unit tested.
 */
object PositionSize {
    enum class Product { CNC, MIS }

    /** About what Indian brokers lend for an intraday trade in a liquid stock (20% margin). */
    const val MIS_LEVERAGE = 5.0

    /** At most a quarter of the capital in one delivery stock. */
    const val MAX_CNC_SHARE = 0.25

    /** The risk a trade can take, in percent of the capital: 1% is the usual, 2% the most. */
    val RISK_CHOICES = listOf(0.5, 1.0, 1.5, 2.0)
    const val DEFAULT_RISK = 1.0

    /** What held the quantity down: the risk, or the money (a quarter of it for CNC, the margin for MIS). */
    enum class Limit { RISK, MONEY }

    data class Result(
        val qty: Int,
        /** Rupees lost a share if the stop is hit: entry to stop. */
        val perShare: Double,
        /** The most the trade may lose: [riskPct] of the capital. */
        val riskMoney: Double,
        val limit: Limit,
        /** [qty] × entry. */
        val cost: Double,
        /** What the account needs: the whole [cost] for CNC, the margin for MIS. */
        val margin: Double,
        /** [qty] × [perShare]: the loss at the stop. */
        val loss: Double,
        /** The gain at each target. */
        val gains: List<Double>,
    )

    /** The size of a trade from [entry] to [stop] for these [targets]; null when the numbers make no trade. */
    fun of(
        capital: Double,
        riskPct: Double,
        entry: Double,
        stop: Double,
        targets: List<Double>,
        product: Product,
    ): Result? {
        if (!(capital > 0.0) || !(riskPct > 0.0) || !(entry > 0.0) || !(stop > 0.0)) return null
        val perShare = abs(entry - stop)
        if (!(perShare > 0.0)) return null
        val riskMoney = capital * riskPct / 100.0
        val byRisk = whole(riskMoney / perShare)
        val byMoney = when (product) {
            Product.CNC -> whole(capital * MAX_CNC_SHARE / entry)
            Product.MIS -> whole(capital * MIS_LEVERAGE / entry)
        }
        val qty = max(0, min(byRisk, byMoney))
        val cost = qty * entry
        return Result(
            qty = qty,
            perShare = perShare,
            riskMoney = riskMoney,
            limit = if (byMoney < byRisk) Limit.MONEY else Limit.RISK,
            cost = cost,
            margin = if (product == Product.MIS) cost / MIS_LEVERAGE else cost,
            loss = qty * perShare,
            gains = targets.map { qty * abs(it - entry) },
        )
    }

    /** Whole shares, forgiving the last bit of a division that should have come out even (1000 / 2.5). */
    private fun whole(value: Double): Int = floor(value + 1e-9).toInt().coerceAtLeast(0)
}
