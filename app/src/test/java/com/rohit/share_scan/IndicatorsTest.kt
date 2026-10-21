package com.rohit.share_scan

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class IndicatorsTest {
    @Test
    fun rsiMatchesWildersWorkedExample() {
        // StockCharts' RSI example (its 70.53 and 66.32 round the averages to two places along the way).
        val closes = doubleArrayOf(
            44.34, 44.09, 44.15, 43.61, 44.33, 44.83, 45.10, 45.42, 45.84, 46.08, 45.89, 46.03, 45.61, 46.28, 46.28,
        )
        assertEquals(70.46, Indicators.rsi(closes)!!, 0.01)
        assertEquals(66.25, Indicators.rsi(closes + 46.00)!!, 0.01)
    }

    @Test
    fun rsiEdges() {
        assertEquals(100.0, Indicators.rsi(DoubleArray(30) { 100.0 + it })!!, 1e-9)
        assertEquals(50.0, Indicators.rsi(DoubleArray(30) { 100.0 })!!, 1e-9)
        assertEquals(0.0, Indicators.rsi(DoubleArray(30) { 100.0 - it })!!, 1e-9)
        assertNull(Indicators.rsi(DoubleArray(14) { 100.0 }))
    }

    @Test
    fun macdOfASteadyRiseIsTheGapBetweenTheAveragesLags() {
        // On a straight line each average trails by (period − 1) / 2 sessions: 5.5 and 12.5, so MACD is 7.
        val macd = Indicators.macd(DoubleArray(80) { it.toDouble() })!!
        assertEquals(7.0, macd.line, 1e-9)
        assertEquals(7.0, macd.signal, 1e-9)
        assertNull(macd.sessionsSinceBullishCross) // level with its signal, not above it
    }

    @Test
    fun aJumpIsABullishCrossoverDatedFromItsSession() {
        val flat = DoubleArray(40) { 100.0 }
        val jumped = flat + DoubleArray(5) { 110.0 }
        val macd = Indicators.macd(jumped)!!
        assertEquals(4, macd.sessionsSinceBullishCross)
        assertEquals(0, Indicators.macd(flat + 110.0)!!.sessionsSinceBullishCross)
        val fell = Indicators.macd(flat + DoubleArray(5) { 90.0 })!!
        assertNull(fell.sessionsSinceBullishCross)
        assert(fell.line < fell.signal)
    }

    @Test
    fun macdNeedsTheSlowAverageAndThenItsSignal() {
        assertNull(Indicators.macd(DoubleArray(33) { 100.0 + it }))
        Indicators.macd(DoubleArray(34) { 100.0 + it })!!
    }
}
