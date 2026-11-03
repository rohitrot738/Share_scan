package com.rohit.share_scan

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class IntradayPlanTest {
    private val day = LocalDate.of(2026, 11, 3)
    private val open = day.atTime(MarketClock.OPEN).atZone(MarketClock.INDIA).toEpochSecond()

    /** Candles every five minutes from 9:15, each (open, high, low, close), volume 1000 unless given. */
    private fun candles(vararg ohlc: DoubleArray, volume: Double = 1000.0) = ohlc.mapIndexed { i, (o, h, l, c) ->
        IntradayPlan.Candle(open + i * 300L, o, h, l, c, volume)
    }

    private fun c(o: Double, h: Double, l: Double, c: Double) = doubleArrayOf(o, h, l, c)

    // 9:15-9:30: the range is 99 to 103.
    private val range = arrayOf(c(100.0, 102.0, 99.0, 101.0), c(101.0, 103.0, 100.0, 102.0), c(102.0, 102.5, 100.5, 101.0))

    @Test
    fun aBreakoutAboveTheRangeAndVwapIsABuyWithItsStopAndTargets() {
        val plan = IntradayPlan.of(candles(*range, c(101.0, 104.5, 101.0, 104.0), c(104.0, 104.5, 103.5, 104.2)))!!
        assertEquals(IntradayPlan.Side.BUY, plan.side)
        assertEquals(103.0, plan.rangeHigh, 0.0)
        assertEquals(99.0, plan.rangeLow, 0.0)
        assertEquals(104.0, plan.entry!!, 0.0)
        assertEquals(101.0, plan.stop!!, 0.0) // the range's middle
        assertEquals(listOf(108.5, 110.0), plan.targets) // 1.5 and 2 times the 3-rupee risk
        assertEquals(open + 900L, plan.triggeredAt)
        assertFalse(plan.late)
        assertTrue(plan.actionable)
        assertEquals(IntradayPlan.Outcome.OPEN, plan.outcome)
    }

    @Test
    fun aBreakdownBelowTheRangeIsASell() {
        val plan = IntradayPlan.of(candles(*range, c(101.0, 101.0, 97.5, 98.0)))!!
        assertEquals(IntradayPlan.Side.SELL, plan.side)
        assertEquals(98.0, plan.entry!!, 0.0)
        assertEquals(101.0, plan.stop!!, 0.0)
        assertEquals(listOf(93.5, 92.0), plan.targets)
    }

    @Test
    fun insideTheRangeItWaitsAndBeforeNineThirtyItIsForming() {
        assertEquals(IntradayPlan.Side.WAIT, IntradayPlan.of(candles(*range, c(101.0, 102.8, 100.0, 102.0)))!!.side)
        assertEquals(IntradayPlan.Side.FORMING, IntradayPlan.of(candles(*range.copyOfRange(0, 2)))!!.side)
        assertNull(IntradayPlan.of(emptyList()))
    }

    @Test
    fun aBreakoutUnderVwapIsNotABuy() {
        // A close just over the range after a long upper wick on heavy volume: rejected, VWAP sits above the close.
        val rejected = candles(*range) + IntradayPlan.Candle(open + 900L, 102.0, 110.0, 103.0, 103.2, 1_000_000.0)
        val plan = IntradayPlan.of(rejected)!!
        assertTrue(plan.vwap!! > 103.2)
        assertEquals(IntradayPlan.Side.WAIT, plan.side)
    }

    @Test
    fun theOutcomeIsTheFirstLevelReached() {
        val target1 = IntradayPlan.of(candles(*range, c(101.0, 104.5, 101.0, 104.0), c(104.0, 109.0, 103.5, 108.8)))!!
        assertEquals(IntradayPlan.Outcome.TARGET_1, target1.outcome)
        assertFalse(target1.actionable)
        val target2 = IntradayPlan.of(candles(*range, c(101.0, 104.5, 101.0, 104.0), c(104.0, 110.5, 103.5, 110.0)))!!
        assertEquals(IntradayPlan.Outcome.TARGET_2, target2.outcome)
        assertFalse(target2.actionable)
        val stopped = IntradayPlan.of(candles(*range, c(101.0, 104.5, 101.0, 104.0), c(104.0, 104.2, 100.5, 100.8)))!!
        assertEquals(IntradayPlan.Outcome.STOPPED, stopped.outcome)
        assertFalse(stopped.actionable)
    }

    @Test
    fun aBreakoutAfterQuarterToThreeIsLate() {
        val inside = Array(66) { c(101.0, 102.0, 100.0, 101.0) } // 9:30 to 14:55
        val plan = IntradayPlan.of(candles(*range, *inside, c(101.0, 104.5, 101.0, 104.0)))!!
        assertEquals(IntradayPlan.Side.BUY, plan.side)
        assertTrue(plan.late)
        assertFalse(plan.actionable)
    }

    @Test
    fun anEarlierCallIsNoLongerTakenFromQuarterToThree() {
        // A 9:30 breakout still between its stop and first target when the 14:45 candle comes in.
        val holding = Array(63) { c(104.0, 104.5, 103.5, 104.2) } // 9:35 to 14:45
        val plan = IntradayPlan.of(candles(*range, c(101.0, 104.5, 101.0, 104.0), *holding))!!
        assertEquals(IntradayPlan.Side.BUY, plan.side)
        assertFalse(plan.late)
        assertEquals(IntradayPlan.Outcome.OPEN, plan.outcome)
        assertTrue(plan.entriesClosed)
        assertFalse(plan.actionable)
        val before = IntradayPlan.of(candles(*range, c(101.0, 104.5, 101.0, 104.0), *holding.copyOfRange(0, 62)))!!
        assertFalse(before.entriesClosed)
        assertTrue(before.actionable)
    }

    @Test
    fun aCallThatRanFarPastItsEntryIsNotChased() {
        // Entry 104 on the close over the range, stop 101 at its middle: risk 3, first target 108.5.
        val near = IntradayPlan.of(candles(*range, c(101.0, 104.5, 101.0, 104.0), c(104.0, 105.0, 103.8, 104.9)))!!
        assertEquals(0.3, near.run, 1e-9)
        assertFalse(near.chased)
        assertTrue(near.actionable)
        val far = IntradayPlan.of(candles(*range, c(101.0, 104.5, 101.0, 104.0), c(104.0, 106.5, 103.8, 106.3)))!!
        assertEquals(IntradayPlan.Outcome.OPEN, far.outcome)
        assertTrue(far.run > IntradayPlan.MAX_CHASE_R)
        assertTrue(far.chased)
        assertFalse(far.actionable)
        val back = IntradayPlan.of(candles(*range, c(101.0, 104.5, 101.0, 104.0), c(104.0, 104.2, 102.5, 102.8)))!!
        assertTrue(back.run < 0.0)
        assertTrue(back.actionable)
    }
}
