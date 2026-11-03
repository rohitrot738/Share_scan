package com.rohit.share_scan

import java.time.LocalDate
import java.time.LocalTime

/**
 * An intraday (MIS) call for one stock from its 5-minute candles, the opening-range breakout of the /trader-entry and
 * /trader-exit skills:
 *
 * - **Opening range**: the high and low of the first [ORB_MINUTES] minutes (9:15 to 9:30 IST).
 * - **BUY** when a candle closes above the range's high on the right side of VWAP (the day's volume-weighted average
 *   price): above it for a buy; **SELL** (short, squared off the same day) when one closes below the range's low and
 *   below VWAP. The first such candle decides the day; its close is the entry.
 * - **Stop** at the range's middle; **targets** at [TARGET_R] times the risk (1:1.5 and 1:2).
 * - No new entry after [LAST_ENTRY] (a breakout after it is [late]); everything is squared off by [SQUARE_OFF].
 * - What happened since: the first of the stop or a target the candles after the entry reached ([outcome]).
 *
 * [of] returns null for a day without the opening range's candles. Free of Android types so it can be unit tested.
 */
data class IntradayPlan(
    val day: LocalDate,
    val rangeHigh: Double,
    val rangeLow: Double,
    /** Volume-weighted average price of the day so far; null when Yahoo gave no volumes. */
    val vwap: Double?,
    /** The latest candle's close, and when that candle started (epoch seconds). */
    val price: Double,
    val asOf: Long,
    val side: Side,
    /** Set for [Side.BUY] and [Side.SELL]. */
    val entry: Double? = null,
    val stop: Double? = null,
    val targets: List<Double> = emptyList(),
    /** When the deciding candle started (epoch seconds). */
    val triggeredAt: Long? = null,
    /** The breakout came after [LAST_ENTRY]: too late to enter today. */
    val late: Boolean = false,
    val outcome: Outcome = Outcome.OPEN,
) {
    enum class Side {
        /** The opening range's candles have not all closed yet. */
        FORMING,

        /** Price is still inside the range, or broke out on the wrong side of VWAP. */
        WAIT,
        BUY,
        SELL,
    }

    enum class Outcome { OPEN, TARGET_1, TARGET_2, STOPPED }

    /** Rupees at risk a share: the entry to the stop. */
    val risk: Double? get() = if (entry != null && stop != null) kotlin.math.abs(entry - stop) else null

    /**
     * How far price now stands past the entry, in units of [risk]: 0 at the entry, below 0 when it has come back
     * towards the stop, 1.5 at the first target. The picks rank by it, the nearest to the entry first.
     */
    val run: Double
        get() {
            val risk = risk?.takeIf { it > 0.0 } ?: return 0.0
            val entry = entry ?: return 0.0
            return when (side) {
                Side.BUY -> (price - entry) / risk
                Side.SELL -> (entry - price) / risk
                else -> 0.0
            }
        }

    /** Price has run more than [MAX_CHASE_R] past the entry: buying (or selling) it now risks too much for the reward left. */
    val chased: Boolean get() = (side == Side.BUY || side == Side.SELL) && outcome == Outcome.OPEN && run > MAX_CHASE_R

    /** The latest candle started at or after [LAST_ENTRY]: no new entry today, whatever was called earlier. */
    val entriesClosed: Boolean get() = MarketClock.timeOf(asOf) >= LAST_ENTRY

    /** Still worth taking now: called, not late, entries still open, nothing reached yet, price still near the entry. */
    val actionable: Boolean
        get() = (side == Side.BUY || side == Side.SELL) && !late && !entriesClosed && outcome == Outcome.OPEN && !chased

    /** One 5-minute candle; [at] is its start in epoch seconds. */
    data class Candle(val at: Long, val open: Double, val high: Double, val low: Double, val close: Double, val volume: Double)

    companion object {
        const val ORB_MINUTES = 15L
        const val CANDLE_MINUTES = 5L

        /** How far past the entry, in units of risk, a call may still be taken. */
        const val MAX_CHASE_R = 0.5
        val TARGET_R = listOf(1.5, 2.0)
        val LAST_ENTRY: LocalTime = LocalTime.of(14, 45)
        val SQUARE_OFF: LocalTime = LocalTime.of(15, 15)

        /** The call for the latest day in [candles] (any order; candles of earlier days are left out). */
        fun of(candles: List<Candle>): IntradayPlan? {
            if (candles.isEmpty()) return null
            val sorted = candles.sortedBy { it.at }
            val day = MarketClock.dayOf(sorted.last().at)
            val session = sorted.filter { MarketClock.dayOf(it.at) == day }
            val open = day.atTime(MarketClock.OPEN).atZone(MarketClock.INDIA).toEpochSecond()
            val rangeEnd = open + ORB_MINUTES * 60
            val range = session.filter { it.at in open until rangeEnd }
            if (range.isEmpty()) return null
            val high = range.maxOf { it.high }
            val low = range.minOf { it.low }
            val last = session.last()
            val vwap = vwap(session)
            // The range is complete once its last candle (starting 9:25) has closed, i.e. a later candle exists.
            val after = session.filter { it.at >= rangeEnd }
            if (after.isEmpty()) {
                return IntradayPlan(day, high, low, vwap, last.close, last.at, Side.FORMING)
            }
            var volume = range.sumOf { it.volume }
            var turnover = range.sumOf { it.volume * typical(it) }
            for ((index, candle) in after.withIndex()) {
                volume += candle.volume
                turnover += candle.volume * typical(candle)
                val vwapNow = if (volume > 0.0) turnover / volume else null
                val side = when {
                    candle.close > high && (vwapNow == null || candle.close > vwapNow) -> Side.BUY
                    candle.close < low && (vwapNow == null || candle.close < vwapNow) -> Side.SELL
                    else -> null
                } ?: continue
                val entry = candle.close
                val stop = (high + low) / 2.0
                val risk = kotlin.math.abs(entry - stop)
                val sign = if (side == Side.BUY) 1.0 else -1.0
                val targets = TARGET_R.map { entry + sign * it * risk }
                val time = MarketClock.timeOf(candle.at + CANDLE_MINUTES * 60)
                return IntradayPlan(
                    day = day,
                    rangeHigh = high,
                    rangeLow = low,
                    vwap = vwap,
                    price = last.close,
                    asOf = last.at,
                    side = side,
                    entry = entry,
                    stop = stop,
                    targets = targets,
                    triggeredAt = candle.at,
                    late = time > LAST_ENTRY,
                    outcome = outcome(after.drop(index + 1), side, stop, targets),
                )
            }
            return IntradayPlan(day, high, low, vwap, last.close, last.at, Side.WAIT)
        }

        private fun typical(c: Candle): Double = (c.high + c.low + c.close) / 3.0

        /** The day's volume-weighted average of each candle's typical price; null without volume. */
        fun vwap(session: List<Candle>): Double? {
            val volume = session.sumOf { it.volume }
            return if (volume > 0.0) session.sumOf { it.volume * typical(it) } / volume else null
        }

        /** The first level the candles after the entry reached; within one candle the stop is taken to come first. */
        private fun outcome(later: List<Candle>, side: Side, stop: Double, targets: List<Double>): Outcome {
            var reached = Outcome.OPEN
            for (c in later) {
                val stopped = if (side == Side.BUY) c.low <= stop else c.high >= stop
                if (stopped) return if (reached == Outcome.OPEN) Outcome.STOPPED else reached
                val best = if (side == Side.BUY) c.high else c.low
                val reach = { level: Double -> if (side == Side.BUY) best >= level else best <= level }
                if (reach(targets[1])) return Outcome.TARGET_2
                if (reach(targets[0])) reached = Outcome.TARGET_1
            }
            return reached
        }
    }
}
