package com.rohit.share_scan

import java.util.BitSet
import kotlin.math.roundToInt

/**
 * Learns where a trade plan's stop and Target 1 did best ([PlanShape]) from what became of the market's own past plans
 * (the daily learner, learner/Learn.kt, feeds it). Every plan is judged once for each stop in [STOPS_ATR] and each
 * Target 1 in [TARGETS_R], given the sessions its distance usually takes ([TargetTimes]) the way [PlanOutcome] judges
 * the app's own plan. Its result in R: plus the target when Target 1 came in time and before the stop, minus one when
 * the stop came first, and the close at the end of its sessions otherwise.
 *
 * A shape is judged by its average R, not its success rate alone: a near target under a far stop is reached most
 * often, yet one stop takes back several wins. The plans are split by date as in [SetupMiner]. The shape in use
 * ([choose]'s fallback, else the one chosen before) gives way only to one that made [MIN_R_GAIN] R a plan more before
 * the holdout and no less in it; a learned shape the fallback beats by as much in the holdout is dropped again. Free
 * of Android types so it can be unit tested.
 */
object PlanTuner {
    /** Stops in ATR under the entry; 0 is the app's own stop under the 10-session low. */
    val STOPS_ATR = listOf(0.0, 1.0, 1.5, 2.0, 2.5, 3.0)

    /** Target 1 in R above the entry. */
    val TARGETS_R = listOf(1.0, 1.5, 2.0, 2.5, 3.0)

    val SHAPES: List<PlanShape> = STOPS_ATR.flatMap { stop -> TARGETS_R.map { target -> PlanShape(stop, target) } }

    const val MIN_R_GAIN = 0.05

    /** Results are kept in thousandths of R; this marks a plan not decided under a shape. */
    private const val UNDECIDED = Short.MIN_VALUE
    private const val MILLI = 1000.0

    /** [shape]'s place in [SHAPES]; -1 when it is not one of them. */
    fun cell(shape: PlanShape): Int = SHAPES.indexOf(shape)

    /**
     * What became of the plan drawn at the close of session [at] (its [price] and [structure]) under every shape in
     * [SHAPES], with the chart's [high], [low] and [close] after it.
     */
    fun judge(price: Double, structure: PriceStructure, high: DoubleArray, low: DoubleArray, close: DoubleArray, at: Int): ShortArray =
        ShortArray(SHAPES.size) { i ->
            val shape = SHAPES[i]
            val plan = TradePlan.of(price, structure, shape) ?: return@ShortArray UNDECIDED
            val outcome = PlanOutcome.of(plan, high, low, at, plan.targets.first(), plan.targetSessions.first().last)
            val target = (shape.targetR * MILLI).roundToInt()
            when (outcome.result) {
                PlanOutcome.Result.WIN -> target
                PlanOutcome.Result.STOPPED -> -MILLI.toInt()
                // Neither came, so the last close sits between the stop and the target.
                PlanOutcome.Result.EXPIRED ->
                    ((close[outcome.exit] - plan.entry) / plan.risk * MILLI).roundToInt().coerceIn(-MILLI.toInt() + 1, target - 1)
                else -> UNDECIDED.toInt()
            }.toShort()
        }

    /** Every judged plan: its conditions, its session and its result under each shape. Safe to add to from threads. */
    class Grid {
        private var masks = LongArray(4096)
        private var days = LongArray(4096)
        private var results = ShortArray(4096 * SHAPES.size)

        var size = 0
            private set

        /** Adds a plan judged by [judge]; one decided under no shape (never entered) is left out, and false. */
        @Synchronized
        fun add(mask: Long, day: Long, judged: ShortArray): Boolean {
            if (judged.all { it == UNDECIDED }) return false
            if (size == masks.size) {
                masks = masks.copyOf(size * 2)
                days = days.copyOf(size * 2)
                results = results.copyOf(size * 2 * SHAPES.size)
            }
            masks[size] = mask
            days[size] = day
            judged.copyInto(results, size * SHAPES.size)
            size++
            return true
        }

        fun mask(row: Int): Long = masks[row]

        fun day(row: Int): Long = days[row]

        /** The plan's result in thousandths of R under shape [cell], or null when not decided under it. */
        fun result(row: Int, cell: Int): Int? = results[row * SHAPES.size + cell].takeIf { it != UNDECIDED }?.toInt()

        /** The rows whose conditions include all of [mask]. */
        fun rows(mask: Long): BitSet = BitSet(size).apply { for (row in 0 until size) if (masks[row] and mask == mask) set(row) }

        /** The rows before [holdoutFrom] and from it. */
        fun split(holdoutFrom: Long): Pair<BitSet, BitSet> {
            val train = BitSet(size)
            val holdout = BitSet(size)
            for (row in 0 until size) if (days[row] >= holdoutFrom) holdout.set(row) else train.set(row)
            return train to holdout
        }

        /** The plans decided under shape [cell], as [SetupMiner] learns from them. */
        fun samples(cell: Int): List<SetupMiner.Sample> {
            val target = (SHAPES[cell].targetR * MILLI).roundToInt()
            return (0 until size).mapNotNull { row ->
                val r = result(row, cell) ?: return@mapNotNull null
                SetupMiner.Sample(masks[row], days[row], win = r >= target, stopped = r <= -MILLI.toInt())
            }
        }
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

    /** A shape in use: its record under it before and in the holdout, and the day it was chosen. */
    data class Choice(val shape: PlanShape, val train: Record, val holdout: Record, val since: Long) {
        val all: Record get() = train + holdout
    }

    /** The plans of [rows] that [part] holds, under shape [cell]. */
    fun record(grid: Grid, cell: Int, rows: BitSet, part: BitSet): Record {
        val target = (SHAPES[cell].targetR * MILLI).roundToInt()
        var n = 0
        var wins = 0
        var stops = 0
        var sum = 0L
        var row = rows.nextSetBit(0)
        while (row >= 0) {
            if (part[row]) {
                val r = grid.result(row, cell)
                if (r != null) {
                    n++
                    sum += r
                    if (r >= target) wins++ else if (r <= -MILLI.toInt()) stops++
                }
            }
            row = rows.nextSetBit(row + 1)
        }
        return Record(n, wins, stops, sum / MILLI)
    }

    /**
     * The shape for the plans of [rows]: [previous]'s, or [fallback] before there is one, unless another did better
     * (see the class). [allowed] can rule shapes out by their record before the holdout. [day] dates a new choice.
     */
    fun choose(
        grid: Grid,
        rows: BitSet,
        train: BitSet,
        holdout: BitSet,
        previous: Choice?,
        fallback: PlanShape,
        day: Long,
        allowed: (cell: Int, train: Record) -> Boolean = { _, _ -> true },
    ): Choice {
        val records = SHAPES.indices.map { record(grid, it, rows, train) to record(grid, it, rows, holdout) }
        val fallbackCell = cell(fallback)
        val start = previous?.shape?.let(::cell)?.takeIf { it >= 0 } ?: fallbackCell
        fun choice(cell: Int, since: Long) = Choice(SHAPES[cell], records[cell].first, records[cell].second, since)
        if (fallbackCell < 0) return choice(start, previous?.since ?: day)

        var inUse = start
        // A learned shape that the fallback now beats in the holdout is dropped.
        if (inUse != fallbackCell && records[fallbackCell].second.averageR > records[inUse].second.averageR + MIN_R_GAIN) {
            inUse = fallbackCell
        }
        val best = SHAPES.indices
            .filter { records[it].first.n >= SetupMiner.MIN_TRAIN && records[it].second.n >= SetupMiner.MIN_HOLDOUT }
            .filter { allowed(it, records[it].first) }
            .maxByOrNull { records[it].first.averageR }
        if (best != null && best != inUse &&
            records[best].first.averageR >= records[inUse].first.averageR + MIN_R_GAIN &&
            records[best].second.averageR >= records[inUse].second.averageR
        ) {
            inUse = best
        }
        return choice(inUse, if (inUse == start) previous?.since ?: day else day)
    }
}
