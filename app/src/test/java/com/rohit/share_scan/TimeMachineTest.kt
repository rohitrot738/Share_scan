package com.rohit.share_scan

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TimeMachineTest {
    @Test
    fun movesAreEachDaysHighLowAndCloseOverTheCloseBefore() {
        val candles = listOf(
            IntradayPlan.Candle(0, 100.0, 101.0, 99.0, 100.0, 1.0),
            IntradayPlan.Candle(1, 100.0, 104.0, 98.0, 102.0, 1.0),
        )
        assertEquals(listOf(TimeMachine.Move(1.04, 0.98, 1.02)), TimeMachine.moves(candles))
    }

    @Test
    fun aRisingStockReachesItsTargetMoreOftenThanItsStop() {
        // Days that rise 1% with a 0.5% dip: a buy at 100 with its stop at 95 reaches 105 well before the stop.
        val up = List(40) { TimeMachine.Move(1.012, 0.995, 1.01) }
        val odds = TimeMachine.odds(up, entry = 100.0, stop = 95.0, targets = listOf(105.0, 110.0), steps = 20)!!
        assertEquals(100.0, odds.targets[0], 1e-9)
        assertEquals(100.0, odds.targets[1], 1e-9)
        assertEquals(0.0, odds.stop, 1e-9)
        assertEquals(TimeMachine.PATHS, odds.paths)
    }

    @Test
    fun theStopComesFirstWhenOneDayReachesBothAndTheShareAddsUp() {
        val wild = List(40) { TimeMachine.Move(1.10, 0.90, 1.0) }
        val odds = TimeMachine.odds(wild, entry = 100.0, stop = 95.0, targets = listOf(105.0), steps = 5)!!
        assertEquals(100.0, odds.stop, 1e-9)
        val mixed = List(20) { TimeMachine.Move(1.02, 0.99, 1.015) } + List(20) { TimeMachine.Move(1.005, 0.97, 0.98) }
        val m = TimeMachine.odds(mixed, entry = 100.0, stop = 96.0, targets = listOf(104.0), steps = 10)!!
        assertEquals(100.0, m.targets[0] + m.stop + m.neither, 1e-6)
        assertTrue(m.targets[0] > 0.0 && m.stop > 0.0)
    }

    @Test
    fun aSellWinsOnTheWayDownAndTooFewDaysGiveNothing() {
        val down = List(40) { TimeMachine.Move(1.004, 0.985, 0.99) }
        val odds = TimeMachine.odds(down, entry = 100.0, stop = 103.0, targets = listOf(97.0), steps = 10)!!
        assertEquals(100.0, odds.targets[0], 1e-9)
        assertNull(TimeMachine.odds(down.take(10), 100.0, 103.0, listOf(97.0), 10))
    }
}
