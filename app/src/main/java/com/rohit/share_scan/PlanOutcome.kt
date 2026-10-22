package com.rohit.share_scan

import kotlin.math.min

/**
 * What became of a [TradePlan] in the sessions after the close it was drawn up at, judged the way the plan reads: a
 * breakout plan is entered only when the price reaches its entry within [ENTRY_SESSIONS] sessions, a plan already
 * above its trigger is entered at that close; then it succeeds when the target is reached within the sessions the plan
 * gives it ([TradePlan.targetSessions], [TradePlan.quickSessions]) and before the stop. A stop touched first, or the
 * target not reached in time, is a failure. When a session touches both the stop and the target, its order is unknown
 * and it counts as the stop. A session that traded at one price ([untradable], locked at a circuit limit) fills no
 * breakout entry. Free of Android types so it can be unit tested.
 */
object PlanOutcome {
    /** A breakout plan not entered within this many sessions lapses; by then the next sessions draw their own. */
    const val ENTRY_SESSIONS = 5

    enum class Result {
        /** The target came within its sessions and before the stop. */
        WIN,

        /** The stop came first. */
        STOPPED,

        /** Neither the target within its sessions nor the stop. */
        EXPIRED,

        /** The price never reached the entry. */
        NO_ENTRY,

        /** The chart ends before the plan is decided. */
        OPEN,
    }

    /** [exit] is the session (index into the chart) that decided it, or -1. */
    data class Outcome(val result: Result, val sessions: Int = 0, val exit: Int = -1) {
        val decided: Boolean get() = result == Result.WIN || result == Result.STOPPED || result == Result.EXPIRED
    }

    /**
     * [plan], drawn at the close of session [at] of [high] and [low] (oldest first), aiming at [target] within
     * [within] sessions after the entry with its stop at [stop]. [Outcome.sessions] counts sessions after the entry.
     */
    fun of(
        plan: TradePlan,
        high: DoubleArray,
        low: DoubleArray,
        at: Int,
        target: Double,
        within: Int,
        stop: Double = plan.stop,
    ): Outcome {
        val last = min(high.size, low.size) - 1
        var entryAt = at
        if (plan.entryKind == TradePlan.EntryKind.BREAKOUT) {
            entryAt = -1
            for (d in at + 1..min(at + ENTRY_SESSIONS, last)) {
                if (high[d] >= plan.entry && !untradable(high[d], low[d])) {
                    entryAt = d
                    break
                }
            }
            if (entryAt < 0) return Outcome(if (at + ENTRY_SESSIONS > last) Result.OPEN else Result.NO_ENTRY)
            // The breakout session: a low under the stop may have come before or after the entry; it counts as the stop.
            if (low[entryAt] <= stop) return Outcome(Result.STOPPED, exit = entryAt)
            if (high[entryAt] >= target) return Outcome(Result.WIN, exit = entryAt)
        }
        for (k in 1..within) {
            val d = entryAt + k
            if (d > last) return Outcome(Result.OPEN, k - 1)
            if (low[d] <= stop) return Outcome(Result.STOPPED, k, d)
            if (high[d] >= target) return Outcome(Result.WIN, k, d)
        }
        return Outcome(Result.EXPIRED, within, entryAt + within)
    }

    /** Target 1 within the sessions the plan gives it, before the plan's stop. */
    fun target1(plan: TradePlan, high: DoubleArray, low: DoubleArray, at: Int): Outcome =
        of(plan, high, low, at, plan.targets.first(), plan.targetSessions.first().last)

    /** The quick target within its sessions, before the plan's stop, or before the wide stop when [wide]. */
    fun quick(plan: TradePlan, high: DoubleArray, low: DoubleArray, at: Int, wide: Boolean = false): Outcome =
        of(plan, high, low, at, plan.quickTarget, plan.quickSessions.last, if (wide) plan.wideStop else plan.stop)

    /** A session whose high and low lie within this share of each other traded at one price. */
    const val LOCK_TOLERANCE = 0.0005

    /** NSE and BSE price bands, in percent: a day's move stops at one of them. */
    val CIRCUIT_BANDS = listOf(2.0, 5.0, 10.0, 20.0)

    /**
     * A session that traded at one price: locked at a circuit limit from the open, or barely traded. No order could be
     * filled at a price of one's choosing in it.
     */
    fun untradable(high: Double, low: Double): Boolean = high - low <= high * LOCK_TOLERANCE

    /**
     * A close at the day's high, up by about a price band ([CIRCUIT_BANDS]) on [previousClose]: locked at the upper
     * circuit, with buyers queued and no sellers, so nobody could buy at that close. Bands are on the previous close
     * and rounded to the tick, so the move sits a little under the band.
     */
    fun lockedUp(close: Double, high: Double, previousClose: Double): Boolean {
        if (!(previousClose > 0.0) || close < high * (1 - LOCK_TOLERANCE)) return false
        val change = (close / previousClose - 1.0) * 100.0
        return CIRCUIT_BANDS.any { band -> change >= band - 0.3 && change <= band + 0.1 }
    }

    /** A rise of this many ATR above the close… */
    const val RALLY_ATR = 3.0

    /** …within this many sessions… */
    const val RALLY_SESSIONS = 20

    /** …before a fall of this many ATR under it makes a rally ([rally]). */
    const val RALLY_FALL_ATR = 1.5

    /**
     * Whether the stock rallied after the close of session [at], plan or no plan: its high reached [RALLY_ATR] ATR
     * above that close within [RALLY_SESSIONS] sessions, before its low fell [RALLY_FALL_ATR] ATR under it ([Result.WIN]).
     * A fall first is [Result.STOPPED]; neither in time is [Result.EXPIRED]; a session doing both counts as the fall.
     */
    fun rally(close: Double, atr: Double, high: DoubleArray, low: DoubleArray, at: Int): Outcome {
        val last = min(high.size, low.size) - 1
        val up = close + RALLY_ATR * atr
        val down = close - RALLY_FALL_ATR * atr
        for (k in 1..RALLY_SESSIONS) {
            val d = at + k
            if (d > last) return Outcome(Result.OPEN, k - 1)
            if (low[d] <= down) return Outcome(Result.STOPPED, k, d)
            if (high[d] >= up) return Outcome(Result.WIN, k, d)
        }
        return Outcome(Result.EXPIRED, RALLY_SESSIONS, at + RALLY_SESSIONS)
    }
}
