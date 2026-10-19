package com.rohit.share_scan

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * About how long a [TradePlan] target may take, from the stock's own chart: how many sessions it typically took to
 * rise as many ATRs as the target sits above the entry. Free of Android types so it can be unit tested.
 */
object TargetTimes {
    /** Rises measured, in ATRs. Targets sit 1.5 to 8 ATRs above the entry: 1.5R to 4R, with R 1 to 2 ATR. */
    const val MAX_ATRS = 8

    /** A chart needs this many starting closes for a typical time to mean anything: about two months. */
    const val MIN_STARTS = 40

    private const val ATR_SESSIONS = 14

    /** How far past the last rise measured a target may sit and still count as on it, in ATRs. */
    private const val EDGE = 0.01

    /**
     * For a rise of 1, 2 … [MAX_ATRS] ATRs, the sessions after which half the chart's closes had seen a high that far
     * above them, each close's 14-session ATR as its unit; null when fewer than half got that far. Empty when the
     * chart is too short. A close near the chart's end, watched only a few sessions, counts for as long as it was
     * watched (Kaplan–Meier), so a short chart does not make the stock look slower than it is.
     */
    fun measure(close: DoubleArray, high: DoubleArray, low: DoubleArray): List<Int?> {
        val n = minOf(close.size, high.size, low.size)
        if (n - 1 - ATR_SESSIONS < MIN_STARTS) return emptyList()
        val trueRange = DoubleArray(n) { i ->
            if (i == 0) high[0] - low[0]
            else maxOf(high[i] - low[i], abs(high[i] - close[i - 1]), abs(low[i] - close[i - 1]))
        }
        val waits = List(MAX_ATRS) { ArrayList<Wait>() }
        for (start in ATR_SESSIONS until n - 1) {
            var atr = 0.0
            for (i in start - ATR_SESSIONS + 1..start) atr += trueRange[i]
            atr /= ATR_SESSIONS
            if (!(atr > 0.0)) continue
            // One walk forward per close: the highest high so far passes each rise in turn.
            var reached = 0
            var best = Double.NEGATIVE_INFINITY
            for (j in start + 1 until n) {
                best = max(best, high[j])
                while (reached < MAX_ATRS && best >= close[start] + (reached + 1) * atr) {
                    waits[reached] += Wait(j - start, arrived = true)
                    reached++
                }
                if (reached == MAX_ATRS) break
            }
            for (rise in reached until MAX_ATRS) waits[rise] += Wait(n - 1 - start, arrived = false)
        }
        return waits.map(::median)
    }

    /**
     * Sessions a rise of [atrs] ATRs typically takes on [rises] ([measure]), between whole ATRs in proportion; null
     * when unknown or slower than the chart shows.
     */
    fun sessions(rises: List<Int?>, atrs: Double): Int? {
        if (rises.isEmpty() || !(atrs > 0.0)) return null
        // Rounding can leave the 4R target a hair past the last rise measured.
        val at = if (atrs > rises.size && atrs < rises.size + EDGE) rises.size.toDouble() else atrs
        if (at > rises.size) return null
        val below = floor(at).toInt()
        val above = ceil(at).toInt()
        val from = if (below == 0) 0 else rises[below - 1] ?: return null
        val to = rises[above - 1] ?: return null
        val sessions = if (above == below) to.toDouble() else from + (to - from) * (at - below)
        return max(1, sessions.roundToInt())
    }

    private class Wait(val sessions: Int, val arrived: Boolean)

    /** The sessions by which half the closes had arrived, those not yet arrived counted while watched. */
    private fun median(waits: List<Wait>): Int? {
        var watched = waits.size
        var notYet = 1.0
        for ((sessions, group) in waits.groupBy { it.sessions }.toSortedMap()) {
            val arrived = group.count { it.arrived }
            notYet *= 1.0 - arrived.toDouble() / watched
            if (notYet <= 0.5) return sessions
            watched -= group.size
        }
        return null
    }
}
