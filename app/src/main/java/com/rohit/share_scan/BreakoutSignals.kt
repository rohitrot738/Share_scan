package com.rohit.share_scan

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.log10
import kotlin.math.log2
import kotlin.math.max
import kotlin.math.min

/** A stock's score at the close of one past session; [day] is that session's epoch day (IST). */
data class DayScore(val day: Long, val score: Double)

/**
 * Pre-breakout signals from daily candles. This is the Kotlin port of the Python scanner's whole-market prefilter
 * (`live_scan.daily_prefilter_score` and `scanner.baseline_daily`) together with the base and trend features of
 * `pattern_engine`. It is pure Kotlin so it can be unit tested without a device.
 *
 * Every part of the score is 0–100 and the score is their weighted sum (the weights add up to 1):
 * - [ScoreKeys.BREAKOUT] 35%: how close price sits under the 20-day high, how tight and calm the latest
 *   10-session base is and where price closes inside it. Distances are measured in the stock's own average daily true range, so a
 *   volatile small cap and a quiet large cap are judged alike.
 * - [ScoreKeys.TREND] 20%: price above rising 20- and 50-day averages, with the 20-day above the 50-day.
 * - [ScoreKeys.VOLUME] 20%: the latest session's relative volume plus how quiet volume was in the five sessions
 *   before it (dry-up, then expansion).
 * - [ScoreKeys.LIQUIDITY] 15%: median rupee value traded over the latest 20 sessions, on a log scale.
 * - [ScoreKeys.MOMENTUM] 10%: 5- and 20-day returns, with the Python prefilter's formula. It is light because
 *   [ScoreKeys.TREND] already rewards the same up-move.
 *
 * On top of those, [ScoreKeys.SUPPORT] adds up to [SUPPORT_BONUS] × 100 = 5 points for a support floor that has
 * stood long and been tested often ([SupportFloor.strength]); a stock without one loses nothing.
 *
 * Raw points go through [soft], which is linear between 15 and 85 and eases towards 0 and 100 beyond, so the best
 * stocks keep their order instead of tying at a hard cap.
 *
 * During market hours the latest candle is today's, still forming. Its volume so far is scaled up to a full day with
 * [VolumeCurve] before it is compared with full past days, so relative volume means the same at 10:00 as at the close.
 */
object BreakoutSignals {
    /** The latest session plus the 20 before it, which the 20-day high and the volume average need. */
    const val MIN_SESSIONS = 21

    /** Score parts and their weights, heaviest first. */
    val WEIGHTS: Map<String, Double> = linkedMapOf(
        ScoreKeys.BREAKOUT to 0.35,
        ScoreKeys.TREND to 0.20,
        ScoreKeys.VOLUME to 0.20,
        ScoreKeys.LIQUIDITY to 0.15,
        ScoreKeys.MOMENTUM to 0.10,
    )

    /** The support floor's bonus: its strength (0–100) times this, added to the weighted parts. */
    const val SUPPORT_BONUS = 0.05

    /** Sessions in a trading week. */
    const val WEEK_SESSIONS = 5

    /** The base the score judges spans this many weeks, ten sessions. */
    const val BASE_WEEKS = 2

    /** The longest span [PriceStructure.weekRanges] covers; [MIN_SESSIONS] candles always reach back that far. */
    const val MAX_RANGE_WEEKS = 4

    private const val CHART_CLOSES = 60

    /** How many past sessions get a score of their own, for the track record; the 50-day average limits it too. */
    const val PAST_SESSIONS = 12
    private const val SMA50_SESSIONS = 50
    private const val BASE_SESSIONS = BASE_WEEKS * WEEK_SESSIONS
    private const val VOLATILITY_SESSIONS = 50
    private const val LOW_KNEE = 15.0
    private const val HIGH_KNEE = 85.0

    /** What one stock's daily candles say; [score] is the sum of value × weight over [parts]. */
    data class Signals(
        /** Latest close, or the latest price while the session is [live]. */
        val price: Double,
        /** Shares traded in the latest session; while it is [live], those traded so far today. */
        val volume: Double,
        /** Latest volume ÷ the average of the 20 sessions before it; a [live] session's volume is first scaled to a full day. */
        val rvol: Double,
        /** Percent change over the latest 5 sessions. */
        val change5d: Double,
        val structure: PriceStructure,
        val score: Double,
        val parts: List<ScorePart>,
        /** The latest session is today's and still trading. */
        val live: Boolean = false,
        /** Scores at the close of the sessions before the latest, newest first; empty without bar dates. */
        val pastScores: List<DayScore> = emptyList(),
        /** The longest-standing floor under the price, over the whole chart; null without one. */
        val support: SupportFloor? = null,
        /** Volume of the 5 sessions before the latest ÷ the 20-session average: below 1 it dried up. */
        val quietVolume: Double = 1.0,
        /** Percent change over the latest 20 sessions. */
        val change20: Double = 0.0,
        /** Median rupee value traded a session over the latest 20, in crore. */
        val medianTurnoverCr: Double = 0.0,
    )

    /**
     * Reads daily candles (oldest first) and scores them, or returns null when fewer than [MIN_SESSIONS] complete
     * sessions remain. Yahoo leaves nulls for holidays and for a session that is still forming; a session missing
     * any of close, high, low or volume is dropped from all four series so they stay aligned.
     *
     * [days] holds each candle's epoch day (IST), when known; it dates the chart and the [Signals.pastScores]. When the
     * latest kept candle is [liveDay]'s, the session is still trading and [liveShare] of a normal day's volume has
     * traded so far ([VolumeCurve]); its volume is scaled up by that before relative volume is measured.
     */
    fun analyse(
        closes: List<Double?>,
        highs: List<Double?>,
        lows: List<Double?>,
        volumes: List<Double?>,
        days: List<Long?> = emptyList(),
        liveDay: Long? = null,
        liveShare: Double = 1.0,
    ): Signals? {
        val size = minOf(closes.size, highs.size, lows.size, volumes.size)
        val dated = days.size >= size
        val close = ArrayList<Double>(size)
        val high = ArrayList<Double>(size)
        val low = ArrayList<Double>(size)
        val volume = ArrayList<Double>(size)
        val day = ArrayList<Long>(size)
        for (i in 0 until size) {
            val c = closes[i] ?: continue
            val h = highs[i] ?: continue
            val l = lows[i] ?: continue
            val v = volumes[i] ?: continue
            if (!c.isFinite() || !h.isFinite() || !l.isFinite() || !v.isFinite()) continue
            if (c <= 0.0 || h <= 0.0 || l <= 0.0 || v < 0.0) continue
            val d = if (dated) days[i] ?: continue else 0L
            close += c
            // Keep the candle consistent even when a feed reports a high below the close or swapped extremes.
            high += maxOf(h, l, c)
            low += minOf(h, l, c)
            volume += v
            day += d
        }
        if (close.size < MIN_SESSIONS) return null
        val c = close.toDoubleArray()
        val h = high.toDoubleArray()
        val l = low.toDoubleArray()
        val v = volume.toDoubleArray()
        val n = c.size
        val live = dated && liveDay != null && day.last() == liveDay && liveShare > 0.0 && liveShare < 1.0
        val signals = score(c, h, l, v, if (live) 1.0 / liveShare else 1.0, if (dated) day else emptyList())
        val past = if (dated) {
            (1..PAST_SESSIONS).mapNotNull { back ->
                val until = n - back
                if (until < SMA50_SESSIONS) return@mapNotNull null
                val earlier = score(c.copyOf(until), h.copyOf(until), l.copyOf(until), v.copyOf(until), 1.0, emptyList())
                DayScore(day[until - 1], earlier.score)
            }
        } else {
            emptyList()
        }
        return signals.copy(live = live, pastScores = past)
    }

    /**
     * [volumeScale] scales the latest session's volume up to a full day (1 for a finished session); [days] dates the
     * candles for the chart, or is empty.
     */
    private fun score(
        close: DoubleArray,
        high: DoubleArray,
        low: DoubleArray,
        traded: DoubleArray,
        volumeScale: Double,
        days: List<Long>,
    ): Signals {
        val n = close.size
        val last = n - 1
        val price = close[last]
        // A live session is judged on the full day its volume so far points to.
        val volume = if (volumeScale == 1.0) traded else traded.copyOf().also { it[last] *= volumeScale }

        // Price structure, exactly as PriceStructure documents it.
        val high20 = highest(high, n - 21, last)
        val high10 = highest(high, n - BASE_SESSIONS, n)
        val low10 = lowest(low, n - BASE_SESSIONS, n)
        val trueRange = DoubleArray(n) { i ->
            if (i == 0) high[0] - low[0]
            else maxOf(high[i] - low[i], abs(high[i] - close[i - 1]), abs(low[i] - close[i - 1]))
        }
        val atr14 = mean(trueRange, n - 14, n)
        val sma20 = mean(close, n - 20, n)
        val sma50 = if (n >= 50) mean(close, n - 50, n) else null
        // Over the whole chart (six months), so the averages behind them have settled by the latest close.
        val macd = Indicators.macd(close)
        val structure = PriceStructure(
            closes = close.copyOfRange(max(0, n - CHART_CLOSES), n).toList(),
            closeDays = if (days.size == n) days.subList(max(0, n - CHART_CLOSES), n).toList() else emptyList(),
            high20 = high20,
            low10 = low10,
            atr14 = atr14,
            sma20 = sma20,
            sma50 = sma50,
            high10 = high10,
            weekRanges = (1..MAX_RANGE_WEEKS).map { weeks ->
                val from = n - weeks * WEEK_SESSIONS
                rangePct(highest(high, from, n), lowest(low, from, n))
            },
            rsi14 = Indicators.rsi(close),
            macd = macd?.line,
            macdSignal = macd?.signal,
            macdCrossAge = macd?.sessionsSinceBullishCross,
        )

        // The stock's normal daily swing: mean true range as a percent of price over up to 50 sessions. It is the
        // yardstick for distances, so a 3% gap means more for a quiet large cap than for a volatile small cap.
        val swingPct = max(meanSwingPct(trueRange, close, max(1, n - VOLATILITY_SESSIONS), n), 0.25)
        val unit = swingPct / 100.0 * price
        // Daily ranges of the 10 sessions before the latest against that normal swing; below 1 they have contracted.
        val calm = meanSwingPct(trueRange, close, n - 11, last) / swingPct

        // Volume: the latest session against the 20 before it, and the 5 sessions before it against the same 20.
        val average20 = mean(volume, n - 21, last)
        val rvol = if (average20 > 0.0) volume[last] / average20 else 0.0
        val quiet = if (average20 > 0.0) mean(volume, n - 6, last) / average20 else 1.0

        val change5d = (price / close[last - 5] - 1.0) * 100.0
        val change20 = (price / close[last - 20] - 1.0) * 100.0

        // The floor under the price over the whole chart; its zone is half a normal daily swing either side.
        val support = SupportFloors.find(close, low, SupportFloors.zonePct(swingPct))

        val turnoverCr = DoubleArray(20) { k -> close[n - 20 + k] * volume[n - 20 + k] / CRORE }
        turnoverCr.sort()
        val medianTurnoverCr = (turnoverCr[9] + turnoverCr[10]) / 2.0

        val parts = listOf(
            ScorePart(ScoreKeys.BREAKOUT, breakoutPart(price, high20, high10, low10, unit, swingPct, calm), WEIGHTS.getValue(ScoreKeys.BREAKOUT)),
            ScorePart(ScoreKeys.TREND, trendPart(close, price, sma20, sma50, unit), WEIGHTS.getValue(ScoreKeys.TREND)),
            ScorePart(ScoreKeys.VOLUME, volumePart(rvol, quiet), WEIGHTS.getValue(ScoreKeys.VOLUME)),
            // ₹10 lakh a day scores 0 raw points, ₹1 Cr 33, ₹10 Cr 67 and ₹100 Cr 100.
            ScorePart(ScoreKeys.LIQUIDITY, soft(100.0 / 3.0 * (log10(max(medianTurnoverCr, 1e-6)) + 1.0)), WEIGHTS.getValue(ScoreKeys.LIQUIDITY)),
            ScorePart(ScoreKeys.MOMENTUM, soft(50.0 + change5d * 4.0 + change20 * 1.5), WEIGHTS.getValue(ScoreKeys.MOMENTUM)),
            // The bonus: its weight is on top of the others', which add up to 1.
            ScorePart(ScoreKeys.SUPPORT, support?.strength ?: 0.0, SUPPORT_BONUS),
        )
        val total = parts.sumOf { it.value * it.weight }.coerceIn(0.0, 100.0)
        return Signals(
            price, traded[last], rvol, change5d, structure, total, parts, support = support,
            quietVolume = quiet, change20 = change20, medianTurnoverCr = medianTurnoverCr,
        )
    }

    /**
     * Proximity to the 20-day high (55%), tightness of the latest 10-session base (30%) and where price closes
     * inside that base (15%). Tightness is judged against the stock's normal daily swing: half the width of the
     * base, half how calm its daily ranges were.
     */
    private fun breakoutPart(
        price: Double,
        high20: Double,
        high10: Double,
        low10: Double,
        unit: Double,
        swingPct: Double,
        calm: Double,
    ): Double {
        // Full marks from the resistance itself up to one daily swing above it (a fresh breakout); 20 points off
        // for every swing below it, or for every swing it has run beyond that (chasing an extended move).
        val gap = (high20 - price) / unit
        val proximity = if (gap >= 0.0) 100.0 - 20.0 * gap else 100.0 - 20.0 * max(0.0, -gap - 1.0)
        // A random walk's 10-session range is about 3.4 daily swings wide; 2 or fewer is a tight base.
        val narrow = 100.0 - 30.0 * (rangePct(high10, low10) / swingPct - 2.0)
        // Daily ranges at 60% of normal or less mean volatility has dried up; at the normal swing it scores 60.
        val calmed = 100.0 - 100.0 * (calm - 0.6)
        // Capped at 110 so a stock that has simply stopped trading does not outrank a genuinely coiled base.
        val tightness = min(110.0, 0.5 * narrow + 0.5 * calmed)
        val position = if (high10 > low10) (price - low10) / (high10 - low10) * 100.0 else 50.0
        return soft(0.55 * proximity + 0.30 * tightness + 0.15 * position)
    }

    /**
     * Price against the 20- and 50-day averages and the 5-session slope of the 20-day average, all in daily swings.
     * Without 50 sessions the 20-day terms carry the whole weight.
     */
    private fun trendPart(close: DoubleArray, price: Double, sma20: Double, sma50: Double?, unit: Double): Double {
        val n = close.size
        val above20 = ((price - sma20) / unit).coerceIn(-3.0, 3.0)
        val slope20 = if (n >= 25) ((sma20 - mean(close, n - 25, n - 5)) / unit).coerceIn(-2.0, 2.0) else 0.0
        val points = if (sma50 != null) {
            val above50 = ((price - sma50) / unit).coerceIn(-4.0, 4.0)
            val stacked = ((sma20 - sma50) / unit).coerceIn(-2.0, 2.0)
            50.0 + 6.0 * above20 + 8.0 * slope20 + 4.0 * above50 + 5.0 * stacked
        } else {
            50.0 + 1.75 * (6.0 * above20 + 8.0 * slope20)
        }
        return soft(points)
    }

    /**
     * Relative volume on a log scale (1× is 50 points, 2× is 75, 4× is 100) for 60%, and the Python dry-up score
     * for 40%: the five sessions before the latest at half their 20-session average or less score 100, at 1.15× or
     * more score 0.
     */
    private fun volumePart(rvol: Double, quiet: Double): Double {
        val expansion = 50.0 + 25.0 * log2(max(rvol, 1.0 / 64.0))
        val dryUp = ((1.15 - quiet) / 0.65 * 100.0).coerceIn(0.0, 100.0)
        return soft(0.6 * expansion + 0.4 * dryUp)
    }

    /** Width of a high–low range as a percent of its midpoint, like the Python `consolidation_range_pct`. */
    fun rangePct(high: Double, low: Double): Double {
        val mid = (high + low) / 2.0
        return if (mid > 0.0) (high - low) / mid * 100.0 else 0.0
    }

    /**
     * Maps raw points onto 0–100 without a hard cap: unchanged between 15 and 85, then easing towards 0 and 100
     * with matching slope, so more is always a little better and no two different setups tie at the edge.
     */
    fun soft(points: Double): Double = when {
        points.isNaN() -> 0.0
        points > HIGH_KNEE -> HIGH_KNEE + (100.0 - HIGH_KNEE) * (1.0 - exp((HIGH_KNEE - points) / (100.0 - HIGH_KNEE)))
        points < LOW_KNEE -> LOW_KNEE * exp((points - LOW_KNEE) / LOW_KNEE)
        else -> points
    }

    /** Mean true range as a percent of the close over sessions [from] until [until]. */
    private fun meanSwingPct(trueRange: DoubleArray, close: DoubleArray, from: Int, until: Int): Double {
        var sum = 0.0
        for (i in from until until) sum += trueRange[i] / close[i] * 100.0
        return sum / (until - from)
    }

    private fun mean(values: DoubleArray, from: Int, until: Int): Double {
        var sum = 0.0
        for (i in from until until) sum += values[i]
        return sum / (until - from)
    }

    private fun highest(values: DoubleArray, from: Int, until: Int): Double {
        var best = values[from]
        for (i in from + 1 until until) best = max(best, values[i])
        return best
    }

    private fun lowest(values: DoubleArray, from: Int, until: Int): Double {
        var best = values[from]
        for (i in from + 1 until until) best = min(best, values[i])
        return best
    }
}
