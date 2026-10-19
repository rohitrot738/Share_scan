package com.rohit.share_scan

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SupportFloorTest {
    /** Closes and lows (oldest first) from [lows], each close [lift] above its low. */
    private fun find(lows: List<Double>, lift: Double = 3.0, zonePct: Double = 1.0): SupportFloor? =
        SupportFloors.find(lows.map { it + lift }.toDoubleArray(), lows.toDoubleArray(), zonePct)

    /** Six months in a 100–110 range: every 20th session dips to 100, the rest of the time lows sit at 103–107. */
    private val range = List(124) { i -> if (i % 20 == 0) 100.0 else 103.0 + (i % 20) % 5 }

    @Test
    fun aRangeBottomTestedForSixMonthsIsALongFloor() {
        val floor = find(range)!!
        // 103 is tested more often, but the dips to 100 pierce its zone: 100 is the range's true bottom.
        assertEquals(100.0, floor.level, 1e-9)
        assertEquals(7, floor.touches)
        assertEquals(124, floor.ageSessions)
        assertTrue(floor.unbroken)
        assertTrue(floor.sinceChartStart)
        assertTrue(floor.long)
        assertEquals(3.0, floor.distancePct(103.0), 1e-9)
        assertEquals(100.0, floor.strength, 1e-9)
    }

    @Test
    fun aFreshLowIsUnbrokenButNotLong() {
        // A slide from 200 to 101 over 100 sessions, closing near each low, then two dips to 100 in the last 24.
        val lows = List(100) { i -> 200.0 - i } + List(24) { i -> if (i == 4 || i == 16) 100.0 else 104.0 + i % 3 }
        val closes = lows.mapIndexed { i, low -> low + if (i < 100) 0.5 else 3.0 }
        val floor = SupportFloors.find(closes.toDoubleArray(), lows.toDoubleArray(), 1.0)!!
        assertEquals(100.0, floor.level, 1e-9)
        assertTrue(floor.unbroken)
        assertTrue(floor.ageSessions < SupportFloors.LONG_AGE)
        assertFalse(floor.long)
    }

    @Test
    fun aCloseBelowTheZoneRestartsTheFloor() {
        // The 100 floor holds for 80 sessions, a close at 95 breaks it, then price climbs and holds 110 twice.
        val before = List(80) { i -> if (i % 20 == 0) 100.0 else 104.0 }
        val lows = before + listOf(93.0) + List(43) { i -> if (i == 10 || i == 30) 110.0 else 114.0 + i % 4 }
        val floor = find(lows, lift = 2.0)!!
        assertEquals(110.0, floor.level, 1e-9)
        assertEquals(2, floor.touches)
        assertFalse(floor.unbroken)
        assertFalse(floor.long)
        assertEquals(33, floor.ageSessions)
    }

    @Test
    fun aSteadyClimbHasNoFloor() {
        assertNull(find(List(124) { i -> 100.0 + i }))
        // Too short a chart for two separate tests.
        assertNull(find(listOf(100.0, 101.0, 100.0, 102.0)))
    }

    @Test
    fun theZoneFollowsTheDailySwingWithinLimits() {
        assertEquals(1.0, SupportFloors.zonePct(0.8), 1e-9)
        assertEquals(1.5, SupportFloors.zonePct(3.0), 1e-9)
        assertEquals(3.0, SupportFloors.zonePct(9.0), 1e-9)
    }
}
