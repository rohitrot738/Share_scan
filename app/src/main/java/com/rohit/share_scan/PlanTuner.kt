package com.rohit.share_scan

import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Learns where a trade plan's stop, Target 1 and time limit did best ([PlanShape]) from what became of the market's own
 * past plans (the daily learner, learner/Learn.kt, feeds it). Every plan is judged under all [CELLS] shapes: each stop
 * in [STOPS_ATR], each Target 1 in [TARGETS_R], and each of [DAYS] times the sessions that target usually takes
 * ([TargetTimes]), the way [PlanOutcome] judges the app's own plan. Its result in R: plus the target when Target 1
 * came in time and before the stop, minus one when the stop came first, and the close at the end of its time otherwise.
 *
 * A shape is judged by its average R, not its success rate alone: a near target under a far stop is reached most
 * often, yet one stop takes back several wins. With this many shapes one can come out ahead by luck, so a shape is
 * scored by the average R of its neighbourhood too (one step either way in stop, target and time): only a shape
 * whose neighbours also did well wins. The plans are split by date as in [SetupMiner]. The shape in use ([choose]'s
 * fallback, else the one chosen before) gives way only to one that scored [MIN_R_GAIN] R a plan more before the holdout
 * and no less in it; a learned shape the fallback beats by as much in the holdout is dropped again. Free of Android
 * types so it can be unit tested.
 */
object PlanTuner {
    /** Stops in hundredths of an ATR under the entry: 0 is the app's own stop under the 10-session low. */
    private val STOP_STEPS = intArrayOf(0) + IntArray(49) { 100 + 5 * it }

    /** Target 1 in hundredths of R above the entry. */
    private val TARGET_STEPS = IntArray(100) { 50 + 5 * it }

    /** Stops in ATR under the entry, 0 for the app's own, then 1 to 3.4. */
    val STOPS_ATR: List<Double> = STOP_STEPS.map { it / 100.0 }

    /** Target 1 in R: 0.5 to 5.45. */
    val TARGETS_R: List<Double> = TARGET_STEPS.map { it / 100.0 }

    /** Each target gets this many times the sessions it usually takes. */
    val DAYS: List<Double> = listOf(0.5, 0.75, 1.0, 1.5, 2.0, 3.0)

    /** Every shape tried: 50 stops × 100 targets × 6 time limits = 30,000. */
    val CELLS = STOP_STEPS.size * TARGET_STEPS.size * DAYS.size

    const val MIN_R_GAIN = 0.05

    /** A result for a shape the plan was not decided under (the chart ends first). */
    const val UNDECIDED = Int.MIN_VALUE

    private const val MILLI = 1000.0
    private const val STOP_MILLI = -1000

    /** A target's usual sessions under each learned stop (in ATRs from the entry, so the same for every plan). */
    private val USUAL = Array(STOP_STEPS.size) { s -> IntArray(TARGET_STEPS.size) { t -> TargetTimes.sessions(STOP_STEPS[s] / 100.0 * TARGET_STEPS[t] / 100.0).last } }

    /** The most sessions any shape gives Target 1 (the app's own stop is at most [TradePlan.MAX_STOP_ATR] away). */
    private val MAX_WINDOW = (TargetTimes.sessions(max(STOP_STEPS.last() / 100.0, TradePlan.MAX_STOP_ATR) * TARGET_STEPS.last() / 100.0).last * DAYS.max()).roundToInt()

    fun cell(stop: Int, target: Int, days: Int): Int = (stop * TARGET_STEPS.size + target) * DAYS.size + days

    fun shape(cell: Int): PlanShape {
        val days = cell % DAYS.size
        val target = cell / DAYS.size % TARGET_STEPS.size
        val stop = cell / DAYS.size / TARGET_STEPS.size
        return PlanShape(STOPS_ATR[stop], TARGETS_R[target], DAYS[days])
    }

    /** [shape]'s cell; -1 when it is not one of the shapes tried. */
    fun cell(shape: PlanShape): Int {
        val stop = STOP_STEPS.indexOf((shape.stopAtr * 100).roundToInt())
        val target = TARGET_STEPS.indexOf((shape.targetR * 100).roundToInt())
        val days = DAYS.indexOfFirst { kotlin.math.abs(it - shape.days) < 1e-9 }
        return if (stop < 0 || target < 0 || days < 0) -1 else cell(stop, target, days)
    }

    /** Target 1 of [cell] in thousandths of R: a result this high is a win. */
    private fun targetMilli(cell: Int): Int = TARGET_STEPS[cell / DAYS.size % TARGET_STEPS.size] * 10

    /**
     * Judges the app's own [plan], drawn at the close of session [at] of a chart ([high], [low], [close], oldest
     * first), under every shape: [into] (of [CELLS]) gets each one's result in thousandths of R, or [UNDECIDED]. False,
     * leaving [into] alone, when the plan was never entered or the chart ends before it could be.
     */
    fun judge(plan: TradePlan, high: DoubleArray, low: DoubleArray, close: DoubleArray, at: Int, into: IntArray): Boolean =
        judge(plan.entry, plan.entryKind == TradePlan.EntryKind.BREAKOUT, plan.stop, plan.atr, high, low, close, at, into)

    /** [judge] from the plan's [entry], whether it waits for a [breakout], the app's own stop ([ownStop]) and [atr]. */
    fun judge(
        entry: Double,
        breakout: Boolean,
        ownStop: Double,
        atr: Double,
        high: DoubleArray,
        low: DoubleArray,
        close: DoubleArray,
        at: Int,
        into: IntArray,
    ): Boolean {
        val entryAt = entryAt(entry, breakout, high, at) ?: return false
        val last = min(min(high.size, low.size), close.size) - 1
        // Sessions after the entry the chart holds, and the lowest low and highest high up to each: a breakout's own
        // session counts (its low first, as PlanOutcome has it), a plan entered at a close starts the session after.
        val k = min(last - entryAt, MAX_WINDOW)
        val minLow = DoubleArray(k + 1)
        val maxHigh = DoubleArray(k + 1)
        var lo = Double.POSITIVE_INFINITY
        var hi = Double.NEGATIVE_INFINITY
        for (j in 0..k) {
            if (j > 0 || breakout) {
                lo = min(lo, low[entryAt + j])
                hi = max(hi, high[entryAt + j])
            }
            minLow[j] = lo
            maxHigh[j] = hi
        }
        for (s in STOP_STEPS.indices) {
            val stop = if (STOP_STEPS[s] == 0) ownStop else entry - STOP_STEPS[s] / 100.0 * atr
            val risk = entry - stop
            if (!(stop > 0.0 && risk > 0.0)) {
                for (t in TARGET_STEPS.indices) for (d in DAYS.indices) into[cell(s, t, d)] = UNDECIDED
                continue
            }
            val stopAt = firstAtOrBelow(minLow, stop)
            for (t in TARGET_STEPS.indices) {
                val targetMilli = TARGET_STEPS[t] * 10
                val targetAt = firstAtOrAbove(maxHigh, entry + TARGET_STEPS[t] / 100.0 * risk)
                val usual = if (STOP_STEPS[s] == 0) TargetTimes.sessions(TARGET_STEPS[t] / 100.0 * risk / atr).last else USUAL[s][t]
                for (d in DAYS.indices) {
                    val window = max(1, (usual * DAYS[d]).roundToInt())
                    val within = min(window, k)
                    into[cell(s, t, d)] = when {
                        // A session touching both counts as the stop.
                        stopAt <= within && stopAt <= targetAt -> STOP_MILLI
                        targetAt <= within -> targetMilli
                        window > k -> UNDECIDED
                        else -> ((close[entryAt + window] - entry) / risk * MILLI).roundToInt().coerceIn(STOP_MILLI + 1, targetMilli - 1)
                    }
                }
            }
        }
        return true
    }

    /** The session the plan was entered, or null when it never was within [PlanOutcome.ENTRY_SESSIONS] or the chart ended first. */
    private fun entryAt(entry: Double, breakout: Boolean, high: DoubleArray, at: Int): Int? {
        if (!breakout) return at
        for (d in at + 1..min(at + PlanOutcome.ENTRY_SESSIONS, high.size - 1)) if (high[d] >= entry) return d
        return null
    }

    /** The first index whose (falling) value is at or under [level]; past the end when none is. */
    private fun firstAtOrBelow(values: DoubleArray, level: Double): Int {
        var lo = 0
        var hi = values.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (values[mid] <= level) hi = mid else lo = mid + 1
        }
        return if (lo < values.size) lo else Int.MAX_VALUE
    }

    /** The first index whose (rising) value is at or over [level]; past the end when none is. */
    private fun firstAtOrAbove(values: DoubleArray, level: Double): Int {
        var lo = 0
        var hi = values.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (values[mid] >= level) hi = mid else lo = mid + 1
        }
        return if (lo < values.size) lo else Int.MAX_VALUE
    }

    /** [n] plans: [wins] reached Target 1 in time, [stops] hit the stop first, making [sumR] R in all. */
    data class Record(val n: Int, val wins: Int, val stops: Int, val sumR: Double) {
        val averageR: Double get() = if (n == 0) 0.0 else sumR / n
        val stats: SetupMiner.Stats get() = SetupMiner.Stats(n, wins, stops)
        operator fun plus(other: Record) = Record(n + other.n, wins + other.wins, stops + other.stops, sumR + other.sumR)

        companion object {
            val EMPTY = Record(0, 0, 0, 0.0)
        }
    }

    /** Every shape's record, before the holdout and in it, added up plan by plan from [judge]'s results. */
    class Totals {
        private val n = IntArray(CELLS * 2)
        private val wins = IntArray(CELLS * 2)
        private val stops = IntArray(CELLS * 2)
        private val sum = LongArray(CELLS * 2)

        /** Adds one plan's results, in the holdout or before it. */
        fun add(results: IntArray, holdout: Boolean) {
            val part = if (holdout) 1 else 0
            for (cell in 0 until CELLS) {
                val r = results[cell]
                if (r == UNDECIDED) continue
                val i = cell * 2 + part
                n[i]++
                sum[i] += r.toLong()
                if (r >= targetMilli(cell)) wins[i]++ else if (r <= STOP_MILLI) stops[i]++
            }
        }

        fun addAll(other: Totals) {
            for (i in n.indices) {
                n[i] += other.n[i]
                wins[i] += other.wins[i]
                stops[i] += other.stops[i]
                sum[i] += other.sum[i]
            }
        }

        fun record(cell: Int, holdout: Boolean): Record {
            val i = cell * 2 + if (holdout) 1 else 0
            return Record(n[i], wins[i], stops[i], sum[i] / MILLI)
        }

        /** [cell]'s average R with its neighbours', one step either way in stop, target and time, before or in the holdout. */
        fun smoothed(cell: Int, holdout: Boolean): Double {
            val d0 = cell % DAYS.size
            val t0 = cell / DAYS.size % TARGET_STEPS.size
            val s0 = cell / DAYS.size / TARGET_STEPS.size
            val part = if (holdout) 1 else 0
            var plans = 0L
            var total = 0L
            // The app's own stop is a kind of its own: its neighbours share it.
            val stops = if (s0 == 0) 0..0 else max(1, s0 - 1)..min(STOP_STEPS.size - 1, s0 + 1)
            for (s in stops) for (t in max(0, t0 - 1)..min(TARGET_STEPS.size - 1, t0 + 1)) for (d in max(0, d0 - 1)..min(DAYS.size - 1, d0 + 1)) {
                val i = cell(s, t, d) * 2 + part
                plans += n[i]
                total += sum[i]
            }
            return if (plans == 0L) 0.0 else total / MILLI / plans
        }
    }

    /** A shape in use: its record under it before and in the holdout, and the day it was chosen. */
    data class Choice(val shape: PlanShape, val train: Record, val holdout: Record, val since: Long) {
        val all: Record get() = train + holdout
    }

    /**
     * The shape for the plans [totals] adds up: [previous]'s, or [fallback] before there is one, unless another did
     * better (see the class). [allowed] can rule shapes out by their record before the holdout. [day] dates a new
     * choice.
     */
    fun choose(
        totals: Totals,
        previous: Choice?,
        fallback: PlanShape,
        day: Long,
        allowed: (cell: Int, train: Record) -> Boolean = { _, _ -> true },
    ): Choice {
        val fallbackCell = cell(fallback).takeIf { it >= 0 } ?: cell(PlanShape.OWN)
        val start = previous?.shape?.let(::cell)?.takeIf { it >= 0 } ?: fallbackCell
        var inUse = start
        // A learned shape that the fallback now beats in the holdout is dropped.
        if (inUse != fallbackCell && totals.smoothed(fallbackCell, true) > totals.smoothed(inUse, true) + MIN_R_GAIN) {
            inUse = fallbackCell
        }
        var best = -1
        var bestScore = Double.NEGATIVE_INFINITY
        for (cell in 0 until CELLS) {
            val train = totals.record(cell, false)
            if (train.n < SetupMiner.MIN_TRAIN || totals.record(cell, true).n < SetupMiner.MIN_HOLDOUT) continue
            if (!allowed(cell, train)) continue
            val score = totals.smoothed(cell, false)
            if (score > bestScore) {
                best = cell
                bestScore = score
            }
        }
        if (best >= 0 && best != inUse &&
            bestScore >= totals.smoothed(inUse, false) + MIN_R_GAIN &&
            totals.smoothed(best, true) >= totals.smoothed(inUse, true)
        ) {
            inUse = best
        }
        val since = if (inUse == start) previous?.since ?: day else day
        return Choice(shape(inUse), totals.record(inUse, false), totals.record(inUse, true), since)
    }

    /** One plan's result under shape [cell] alone, as [judge] gives it; null when never entered or undecided. */
    fun result(
        cell: Int,
        entry: Double,
        breakout: Boolean,
        ownStop: Double,
        atr: Double,
        high: DoubleArray,
        low: DoubleArray,
        close: DoubleArray,
        at: Int,
    ): Int? {
        val shape = shape(cell)
        val entryAt = entryAt(entry, breakout, high, at) ?: return null
        val stop = if (shape.stopAtr == 0.0) ownStop else entry - shape.stopAtr * atr
        val risk = entry - stop
        if (!(stop > 0.0 && risk > 0.0)) return null
        val target = entry + shape.targetR * risk
        val targetMilli = targetMilli(cell)
        val usual = TargetTimes.sessions(shape.targetR * risk / atr).last
        val window = max(1, (usual * shape.days).roundToInt())
        val last = min(min(high.size, low.size), close.size) - 1
        for (j in (if (breakout) 0 else 1)..window) {
            val d = entryAt + j
            if (d > last) return null
            if (low[d] <= stop) return STOP_MILLI
            if (high[d] >= target) return targetMilli
        }
        return ((close[entryAt + window] - entry) / risk * MILLI).roundToInt().coerceIn(STOP_MILLI + 1, targetMilli - 1)
    }

    /** Whether a [result] of shape [cell] reached Target 1, or hit the stop. */
    fun won(cell: Int, result: Int): Boolean = result >= targetMilli(cell)

    fun stopped(result: Int): Boolean = result <= STOP_MILLI
}
