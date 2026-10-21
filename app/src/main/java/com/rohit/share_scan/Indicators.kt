package com.rohit.share_scan

import kotlin.math.max

/**
 * Momentum indicators on daily closes (oldest first), drawn the way charting sites draw them: Wilder's RSI, and MACD
 * from exponential averages that start from a simple average. Free of Android types so it can be unit tested.
 */
object Indicators {
    const val RSI_SESSIONS = 14
    const val MACD_FAST = 12
    const val MACD_SLOW = 26
    const val MACD_SIGNAL = 9

    /** The latest RSI (0–100) over [period] sessions with Wilder's smoothing; null with [period] closes or fewer. */
    fun rsi(closes: DoubleArray, period: Int = RSI_SESSIONS): Double? {
        if (closes.size <= period) return null
        var gain = 0.0
        var loss = 0.0
        for (i in 1..period) {
            val change = closes[i] - closes[i - 1]
            gain += max(change, 0.0)
            loss += max(-change, 0.0)
        }
        gain /= period
        loss /= period
        for (i in period + 1 until closes.size) {
            val change = closes[i] - closes[i - 1]
            gain = (gain * (period - 1) + max(change, 0.0)) / period
            loss = (loss * (period - 1) + max(-change, 0.0)) / period
        }
        if (loss == 0.0) return if (gain == 0.0) 50.0 else 100.0
        return 100.0 - 100.0 / (1.0 + gain / loss)
    }

    /**
     * MACD at the latest close: [line] (fast minus slow average), its [signal] line, and [sessionsSinceBullishCross],
     * how many sessions ago the line last crossed above the signal (0 = at the latest close), or null while the line
     * is at or below the signal.
     */
    data class Macd(val line: Double, val signal: Double, val sessionsSinceBullishCross: Int?)

    /** Null until there are enough closes for the slow average and then the signal line. */
    fun macd(
        closes: DoubleArray,
        fast: Int = MACD_FAST,
        slow: Int = MACD_SLOW,
        signal: Int = MACD_SIGNAL,
    ): Macd? {
        if (closes.size < slow + signal - 1) return null
        val fastAverage = ema(closes, fast)
        val slowAverage = ema(closes, slow)
        val line = DoubleArray(closes.size - (slow - 1)) { fastAverage[slow - 1 + it] - slowAverage[slow - 1 + it] }
        val signalLine = ema(line, signal)
        val gaps = DoubleArray(line.size - (signal - 1)) { line[signal - 1 + it] - signalLine[signal - 1 + it] }
        val last = gaps.size - 1
        var since: Int? = null
        if (gaps[last] > 0.0) {
            var i = last
            while (i > 0 && gaps[i - 1] > 0.0) i--
            since = last - i
        }
        return Macd(line.last(), signalLine.last(), since)
    }

    /** Exponential average over [period], starting from the simple average of the first [period] values; NaN before. */
    private fun ema(values: DoubleArray, period: Int): DoubleArray {
        val out = DoubleArray(values.size) { Double.NaN }
        if (values.size < period) return out
        out[period - 1] = values.take(period).average()
        val k = 2.0 / (period + 1)
        for (i in period until values.size) out[i] = values[i] * k + out[i - 1] * (1 - k)
        return out
    }
}
