package com.rohit.share_scan

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TargetTimesTest {
    /** [n] sessions of a stock whose close moves by [step] a session, the high and low half a rupee either side. */
    private fun chart(n: Int, step: Double): List<Int?> {
        val close = DoubleArray(n) { 1_000.0 + it * step }
        return TargetTimes.measure(close, DoubleArray(n) { close[it] + 0.5 }, DoubleArray(n) { close[it] - 0.5 })
    }

    @Test
    fun aSteadyClimbReachesEachRiseOnTime() {
        // Rising ₹1 a session, the true range and so the ATR is ₹1.5: k ATRs above a close is reached once its high,
        // ₹0.5 over the close, has climbed 1.5k − 0.5 sessions.
        assertEquals(listOf(1, 3, 4, 6, 7, 9, 10, 12), chart(100, step = 1.0))
    }

    @Test
    fun aFallingStockNeverGetsThere() {
        assertEquals(List(TargetTimes.MAX_ATRS) { null }, chart(100, step = -1.0))
    }

    @Test
    fun aShortChartIsNotTimed() {
        assertTrue(chart(14 + TargetTimes.MIN_STARTS, step = 1.0).isEmpty())
        assertEquals(TargetTimes.MAX_ATRS, chart(15 + TargetTimes.MIN_STARTS, step = 1.0).size)
    }

    @Test
    fun sessionsAreInterpolatedBetweenWholeAtrs() {
        val rises = listOf(1, 3, 4, 6, 7, 9, 10, 12)
        assertEquals(2, TargetTimes.sessions(rises, 1.5))
        assertEquals(6, TargetTimes.sessions(rises, 4.0))
        // Below one ATR the time shrinks towards nothing, but never under a session.
        assertEquals(1, TargetTimes.sessions(rises, 0.3))
        // The 4R target can land a hair past the last rise; anything further is not timed.
        assertEquals(12, TargetTimes.sessions(rises, 8.005))
        assertNull(TargetTimes.sessions(rises, 9.0))
        assertNull(TargetTimes.sessions(rises.dropLast(1) + null, 7.5))
        assertNull(TargetTimes.sessions(emptyList(), 2.0))
        assertNull(TargetTimes.sessions(rises, Double.NaN))
    }
}
