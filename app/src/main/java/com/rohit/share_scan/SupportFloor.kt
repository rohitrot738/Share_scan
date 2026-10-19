package com.rohit.share_scan

import kotlin.math.max
import kotlin.math.min

/**
 * A price a stock keeps coming down to without closing below it: under it, sellers have not been able to push the
 * price. Found from the daily candles alone ([SupportFloors.find]).
 */
data class SupportFloor(
    /** The floor: the deepest low inside its zone, so overlapping candidates name the same bottom. */
    val level: Double,
    /** Sessions since the first dip into the floor's zone (after any close below it): how long it has stood. */
    val ageSessions: Int,
    /** Separate dips into the zone that turned back up, the first included. */
    val touches: Int,
    /** No close below the zone anywhere in the [window] sessions the chart covers. */
    val unbroken: Boolean,
    /** Sessions the chart covers, about six months. */
    val window: Int,
) {
    /**
     * Not closed below for about six months, and tested from at least three months ago: the floors the Support chip
     * lists. A floor that only formed lately (a fresh low) does not count, however unbroken.
     */
    val long: Boolean
        get() = unbroken && window >= SupportFloors.LONG_WINDOW && ageSessions >= SupportFloors.LONG_AGE

    /** Reaches back to the start of the chart, so it may be older still. */
    val sinceChartStart: Boolean get() = ageSessions >= window - SupportFloors.CHART_START_SLACK

    /** Percent [price] sits above the floor. */
    fun distancePct(price: Double): Double = (price / level - 1.0) * 100.0

    /**
     * Up to 100 points: how long it has stood (70, full at six months) and how often it held (30, full at five
     * dips). Feeds the score's support bonus.
     */
    val strength: Double
        get() = min(ageSessions.toDouble() / SupportFloors.FULL_AGE, 1.0) * 70.0 +
            min(touches.toDouble() / SupportFloors.FULL_TOUCHES, 1.0) * 30.0
}

/** Finds a stock's [SupportFloor]. Free of Android types so it can be unit tested. */
object SupportFloors {
    /** NSE trades about 21 days a month. */
    const val SESSIONS_PER_MONTH = 21

    /** Sessions in about six months: NSE trades about 123 days in half a year; a few are allowed for holidays. */
    const val LONG_WINDOW = 115

    /** A long floor was first tested at least three months ago. */
    const val LONG_AGE = 3 * SESSIONS_PER_MONTH

    /** A floor this old gets full marks for age. */
    const val FULL_AGE = 120.0

    /** A floor tested this often gets full marks for its tests. */
    const val FULL_TOUCHES = 5.0

    /** A floor needs at least this many separate dips into its zone. */
    const val MIN_TOUCHES = 2

    /** Two dips count separately only this many sessions apart. */
    const val TOUCH_GAP = 5

    /** A floor first tested this close to the chart's start may be older than the chart. */
    const val CHART_START_SLACK = 3

    /** A dip's low is the lowest of this many sessions either side. */
    private const val PIVOT_SPAN = 3

    /** The zone's half-width is half the stock's normal daily swing, kept between these percents. */
    private const val MIN_ZONE_PCT = 1.0
    private const val MAX_ZONE_PCT = 3.0

    /** Half the stock's normal daily swing ([swingPct], percent), kept between 1% and 3%. */
    fun zonePct(swingPct: Double): Double = (swingPct / 2.0).coerceIn(MIN_ZONE_PCT, MAX_ZONE_PCT)

    /**
     * The longest-standing floor under the latest close, from daily closes and lows (oldest first), or null when no
     * zone took at least [MIN_TOUCHES] separate dips. Each dip (a low that is the lowest of [PIVOT_SPAN] sessions
     * either side) is a candidate level; its zone reaches [zonePct] percent either side. A low inside the zone, or
     * poking below it for a session, is a test the buyers won; a close below the zone is one the sellers won, and
     * the floor's age counts from the first test after it. Of floors as old, the one whose zone lows pierced least
     * wins (the true bottom of a range rather than a level inside it), then the one tested most, then the higher.
     */
    fun find(close: DoubleArray, low: DoubleArray, zonePct: Double): SupportFloor? {
        val n = min(close.size, low.size)
        if (n < MIN_TOUCHES * TOUCH_GAP) return null
        val zone = zonePct / 100.0
        var best: Tested? = null
        // A flat stretch makes many dips at one price; each price is tested once.
        val tried = HashSet<Double>()
        for (pivot in 0 until n) {
            if (!isDip(low, pivot, n)) continue
            val level = low[pivot]
            if (!(level > 0.0) || !tried.add(level)) continue
            val tested = test(close, low, n, level, zone) ?: continue
            if (best == null || tested.beats(best)) best = tested
        }
        return best?.floor
    }

    /** A candidate floor and how many sessions' lows went below its zone since its age began. */
    private class Tested(val floor: SupportFloor, val pierces: Int) {
        fun beats(other: Tested): Boolean = when {
            floor.ageSessions != other.floor.ageSessions -> floor.ageSessions > other.floor.ageSessions
            pierces != other.pierces -> pierces < other.pierces
            floor.touches != other.floor.touches -> floor.touches > other.floor.touches
            else -> floor.level > other.floor.level
        }
    }

    private fun isDip(low: DoubleArray, index: Int, n: Int): Boolean {
        for (j in max(0, index - PIVOT_SPAN) until min(n, index + PIVOT_SPAN + 1)) {
            if (low[j] < low[index]) return false
        }
        return true
    }

    /** The floor at [level] as the candles tested it, or null when it is broken now or took too few tests. */
    private fun test(close: DoubleArray, low: DoubleArray, n: Int, level: Double, zone: Double): Tested? {
        val bottom = level * (1.0 - zone)
        val top = level * (1.0 + zone)
        var lastBreak = -1
        for (i in 0 until n) if (close[i] < bottom) lastBreak = i
        if (lastBreak == n - 1) return null
        var touches = 0
        var first = -1
        var lastTouch = Int.MIN_VALUE / 2
        var inZone = false
        var pierces = 0
        var deepest = level
        for (i in lastBreak + 1 until n) {
            if (low[i] < bottom) pierces++ else if (low[i] < deepest) deepest = low[i]
            if (low[i] <= top) {
                // A stay in the zone is one test; a new one needs price to have left it, and some sessions to pass.
                if (!inZone && i - lastTouch >= TOUCH_GAP) {
                    touches++
                    lastTouch = i
                    if (first < 0) first = i
                }
                inZone = true
            } else {
                inZone = false
            }
        }
        if (touches < MIN_TOUCHES) return null
        val floor = SupportFloor(deepest, ageSessions = n - first, touches = touches, unbroken = lastBreak < 0, window = n)
        return Tested(floor, pierces)
    }
}
