package com.rohit.share_scan

import kotlin.math.min

/**
 * What became of a [TradePlan] in the sessions after the close it was drawn up at, judged the way the plan reads: a
 * breakout plan is entered only when the price reaches its entry within [ENTRY_SESSIONS] sessions, a plan already
 * above its trigger is entered at that close; then it succeeds when the target is reached within the sessions the plan
 * gives it ([TradePlan.targetSessions], [TradePlan.quickSessions]) and before the stop. A stop touched first, or the
 * target not reached in time, is a failure. When a session touches both the stop and the target, its order is unknown
 * and it counts as the stop. Free of Android types so it can be unit tested.
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

    data class Outcome(val result: Result, val sessions: Int = 0) {
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
                if (high[d] >= plan.entry) {
                    entryAt = d
                    break
                }
            }
            if (entryAt < 0) return Outcome(if (at + ENTRY_SESSIONS > last) Result.OPEN else Result.NO_ENTRY)
            // The breakout session: a low under the stop may have come before or after the entry; it counts as the stop.
            if (low[entryAt] <= stop) return Outcome(Result.STOPPED)
            if (high[entryAt] >= target) return Outcome(Result.WIN)
        }
        for (k in 1..within) {
            val d = entryAt + k
            if (d > last) return Outcome(Result.OPEN, k - 1)
            if (low[d] <= stop) return Outcome(Result.STOPPED, k)
            if (high[d] >= target) return Outcome(Result.WIN, k)
        }
        return Outcome(Result.EXPIRED, within)
    }

    /** Target 1 within the sessions the plan gives it, before the plan's stop. */
    fun target1(plan: TradePlan, high: DoubleArray, low: DoubleArray, at: Int): Outcome =
        of(plan, high, low, at, plan.targets.first(), plan.targetSessions.first().last)

    /** The quick target within its sessions, before the plan's stop, or before the wide stop when [wide]. */
    fun quick(plan: TradePlan, high: DoubleArray, low: DoubleArray, at: Int, wide: Boolean = false): Outcome =
        of(plan, high, low, at, plan.quickTarget, plan.quickSessions.last, if (wide) plan.wideStop else plan.stop)
}
