package com.rohit.share_scan

import java.util.Random

/**
 * The time machine (the /trader-timemachine skill): [PATHS] made-up futures of a trade, each day's move drawn from the
 * stock's own past days, and how many reach each target before the stop. A day's move is its high, low and close as
 * ratios of the day before's close, so gaps and wicks are kept; when one day reaches both the stop and a target, the
 * stop is taken to come first. Free of Android types so it can be unit tested.
 */
object TimeMachine {
    const val PATHS = 10_000

    /** Fewer past days than this make no replay. */
    const val MIN_MOVES = 30

    /** One past day: its high, low and close over the close before it. */
    data class Move(val high: Double, val low: Double, val close: Double)

    data class Odds(
        val paths: Int,
        /** Days each path runs. */
        val steps: Int,
        /** Percent of paths that reached each target before the stop. */
        val targets: List<Double>,
        /** Percent that hit the stop before the first target. */
        val stop: Double,
        /** Percent that reached neither in [steps] days. */
        val neither: Double,
        /** How many past days the moves came from. */
        val moves: Int,
    )

    fun moves(candles: List<IntradayPlan.Candle>): List<Move> = candles.zipWithNext().mapNotNull { (before, day) ->
        val base = before.close
        if (!(base > 0.0) || !(day.low > 0.0)) null else Move(day.high / base, day.low / base, day.close / base)
    }

    /**
     * The odds of a trade from [entry] to [stop] and [targets] (nearest first) over [steps] days; a target above the
     * entry is a buy, below it a sell. Null with fewer than [MIN_MOVES] moves or no target.
     */
    fun odds(
        moves: List<Move>,
        entry: Double,
        stop: Double,
        targets: List<Double>,
        steps: Int,
        paths: Int = PATHS,
        seed: Long = 7L,
    ): Odds? {
        if (moves.size < MIN_MOVES || targets.isEmpty() || steps < 1 || !(entry > 0.0)) return null
        val buy = targets.first() > entry
        val random = Random(seed)
        val reached = IntArray(targets.size)
        var stopped = 0
        repeat(paths) {
            var price = entry
            var hits = 0
            for (step in 0 until steps) {
                val move = moves[random.nextInt(moves.size)]
                val high = price * move.high
                val low = price * move.low
                if (if (buy) low <= stop else high >= stop) {
                    if (hits == 0) stopped++
                    break
                }
                while (hits < targets.size && (if (buy) high >= targets[hits] else low <= targets[hits])) {
                    reached[hits]++
                    hits++
                }
                if (hits == targets.size) break
                price *= move.close
            }
        }
        fun pct(count: Int) = count * 100.0 / paths
        return Odds(
            paths = paths,
            steps = steps,
            targets = reached.map(::pct),
            stop = pct(stopped),
            neither = pct(paths - reached[0] - stopped),
            moves = moves.size,
        )
    }
}
