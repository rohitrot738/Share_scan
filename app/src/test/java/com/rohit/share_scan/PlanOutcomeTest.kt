package com.rohit.share_scan

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlanOutcomeTest {
    // Price 100 under a 20D high of 104 (trigger 104.2 at ATR 2), 10D low 98: a breakout plan, entry 104.2, stop
    // 100.2 (2 ATR under: the structure stop is further), 1R = 4, Target 1 = 110.2.
    private val plan = TradePlan.of(100.0, 104.0, 98.0, 2.0)!!

    private fun run(highs: List<Double>, lows: List<Double>, within: Int = 10) =
        PlanOutcome.of(plan, (listOf(100.0) + highs).toDoubleArray(), (listOf(99.0) + lows).toDoubleArray(), 0, plan.targets.first(), within)

    @Test
    fun planReadsAsExpected() {
        assertEquals(TradePlan.EntryKind.BREAKOUT, plan.entryKind)
        assertEquals(104.2, plan.entry, 1e-9)
        assertEquals(100.2, plan.stop, 1e-9)
        assertEquals(110.2, plan.targets.first(), 1e-9)
    }

    @Test
    fun targetInTimeAfterTheBreakoutIsAWin() {
        val o = run(highs = listOf(103.0, 105.0, 107.0, 111.0), lows = listOf(101.0, 103.0, 104.0, 106.0))
        assertEquals(PlanOutcome.Result.WIN, o.result)
        assertEquals(2, o.sessions) // entered on the second session, target two sessions later
    }

    @Test
    fun stopFirstOrBothInOneSessionIsALoss() {
        assertEquals(PlanOutcome.Result.STOPPED, run(listOf(105.0, 104.0), listOf(103.0, 100.0)).result)
        assertEquals(PlanOutcome.Result.STOPPED, run(listOf(105.0, 111.0), listOf(103.0, 100.0)).result)
        // The breakout session itself dipping under the stop counts as the stop.
        assertEquals(PlanOutcome.Result.STOPPED, run(listOf(105.0), listOf(100.0)).result)
    }

    @Test
    fun targetTooLateIsAFailureAndNoBreakoutIsNoTrade() {
        val slow = run(listOf(105.0, 106.0, 107.0, 111.0), listOf(103.0, 104.0, 104.0, 106.0), within = 2)
        assertEquals(PlanOutcome.Result.EXPIRED, slow.result)
        assertEquals(3, slow.exit) // entered at session 1, two sessions later
        assertEquals(PlanOutcome.Result.NO_ENTRY, run(List(6) { 103.0 }, List(6) { 101.0 }).result)
        assertEquals(PlanOutcome.Result.OPEN, run(List(3) { 103.0 }, List(3) { 101.0 }).result)
        assertEquals(PlanOutcome.Result.OPEN, run(listOf(105.0, 106.0), listOf(103.0, 104.0)).result)
    }

    @Test
    fun aPlanAboveItsTriggerIsEnteredAtTheClose() {
        val market = TradePlan.of(105.0, 104.0, 98.0, 2.0)!!
        assertEquals(TradePlan.EntryKind.MARKET, market.entryKind)
        val high = doubleArrayOf(105.0, 106.0, market.targets.first() + 0.1)
        val low = doubleArrayOf(104.0, market.stop + 0.1, 105.0)
        assertEquals(PlanOutcome.Result.WIN, PlanOutcome.target1(market, high, low, 0).result)
    }

    @Test
    fun aRallyIsThreeAtrUpBeforeOneAndAHalfDown() {
        // Close 100 at ATR 2: a rally reaches 106 before 97.
        fun rally(highs: List<Double>, lows: List<Double>) =
            PlanOutcome.rally(100.0, 2.0, (listOf(100.0) + highs).toDoubleArray(), (listOf(99.0) + lows).toDoubleArray(), 0)

        val up = rally(listOf(103.0, 106.5), listOf(99.0, 101.0))
        assertEquals(PlanOutcome.Result.WIN, up.result)
        assertEquals(2, up.exit)
        assertEquals(PlanOutcome.Result.STOPPED, rally(listOf(101.0, 106.5), listOf(96.9, 101.0)).result)
        assertEquals(PlanOutcome.Result.STOPPED, rally(listOf(106.5), listOf(96.0)).result) // both in one session
        assertEquals(PlanOutcome.Result.EXPIRED, rally(List(20) { 104.0 }, List(20) { 98.0 }).result)
        assertEquals(PlanOutcome.Result.OPEN, rally(List(5) { 104.0 }, List(5) { 98.0 }).result)
    }

    @Test
    fun aCloseLockedAtTheUpperCircuitIsSeen() {
        // Up 5% (a hair under, as bands are rounded to the tick) and closed at the high: locked at the circuit.
        assertTrue(PlanOutcome.lockedUp(104.95, 104.95, 100.0))
        assertTrue(PlanOutcome.lockedUp(119.9, 119.9, 100.0))
        assertTrue(PlanOutcome.lockedUp(102.0, 102.0, 100.0))
        // Not at the high, or not at a band: tradable.
        assertFalse(PlanOutcome.lockedUp(104.95, 105.5, 100.0))
        assertFalse(PlanOutcome.lockedUp(104.0, 104.0, 100.0))
        assertFalse(PlanOutcome.lockedUp(95.0, 95.0, 100.0))
        assertTrue(PlanOutcome.untradable(110.0, 110.0))
        assertFalse(PlanOutcome.untradable(110.0, 109.5))
    }

    @Test
    fun aBreakoutIsNotEnteredInASessionLockedAtOnePrice() {
        // The first session gaps to 106 and stays there all day: no buy-stop at 104.2 fills.
        assertEquals(PlanOutcome.Result.OPEN, run(highs = listOf(106.0), lows = listOf(106.0)).result)
        // The next session trades, and the plan is entered there.
        val later = run(highs = listOf(106.0, 107.0, 111.0), lows = listOf(106.0, 104.5, 106.0))
        assertEquals(PlanOutcome.Result.WIN, later.result)
        assertEquals(1, later.sessions)
    }
}
