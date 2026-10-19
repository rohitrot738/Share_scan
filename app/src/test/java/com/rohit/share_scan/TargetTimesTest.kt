package com.rohit.share_scan

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TargetTimesTest {
    @Test
    fun fartherTargetsTakeLonger() {
        // 2.0 and 6.6 times 3^0.77 ≈ 2.33: 4.7 and 15.4 sessions.
        assertEquals(5..15, TargetTimes.sessions(3.0))
        assertEquals(7..23, TargetTimes.sessions(5.0))
        assertEquals(10..33, TargetTimes.sessions(8.0))
        val ranges = (1..8).map { TargetTimes.sessions(it.toDouble()) }
        assertTrue(ranges.zipWithNext().all { (near, far) -> near.first <= far.first && near.last < far.last })
    }

    @Test
    fun aRangeAlwaysSpansAtLeastOneSessionPastTheFirst() {
        assertEquals(1..2, TargetTimes.sessions(0.0))
        assertEquals(1..2, TargetTimes.sessions(-1.0))
        val close = TargetTimes.sessions(0.3)
        assertTrue(close.first >= 1 && close.last > close.first)
    }
}
