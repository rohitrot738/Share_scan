package com.rohit.share_scan

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.BitSet

class PlanTunerTest {
    private val own = PlanTuner.cell(PlanShape.OWN)
    private val wide = PlanTuner.cell(PlanShape(2.0, 2.0))
    private val far = PlanTuner.cell(PlanShape(3.0, 3.0))

    @Test
    fun everyShapeIsJudgedInR() {
        // Entry 104.2 at ATR 2, the app's own stop 100.2 (1R = 4) and Target 1 110.2.
        val structure = PriceStructure(closes = listOf(100.0), high20 = 104.0, low10 = 98.0, atr14 = 2.0, sma20 = 100.0, sma50 = null)
        val high = doubleArrayOf(100.0, 105.0, 107.0, 111.0)
        val low = doubleArrayOf(99.0, 103.0, 102.0, 106.0)
        val close = doubleArrayOf(100.0, 104.5, 106.0, 110.5)
        val judged = PlanTuner.judge(100.0, structure, high, low, close, 0)
        assertEquals(1500, judged[own].toInt()) // Target 1 at 110.2 on the third session
        assertEquals(-1000, judged[PlanTuner.cell(PlanShape(1.0, 1.5))].toInt()) // 102.2 stop, hit at 102 first
        // Stop 1.5 ATR under (101.2), Target 1 at 3R (113.2) not reached: the chart ends before its sessions do.
        assertEquals(PlanTuner.SHAPES.size, judged.size)

        val grid = PlanTuner.Grid()
        assertTrue(grid.add(5L, 20_000L, judged))
        assertFalse(grid.add(5L, 20_000L, ShortArray(PlanTuner.SHAPES.size) { Short.MIN_VALUE })) // never entered
        assertEquals(1, grid.size)
        val sample = grid.samples(own).single()
        assertTrue(sample.win)
        assertFalse(grid.samples(PlanTuner.cell(PlanShape(1.0, 1.5))).single().win)
        assertTrue(grid.samples(PlanTuner.cell(PlanShape(1.0, 1.5))).single().stopped)
    }

    /**
     * 300 sessions × 2 plans. The app's own shape makes 0.1R a plan; the 2-ATR stop with Target 1 at 2R makes
     * [wideR] (wins and stops alternating); every other shape loses 0.2R.
     */
    private fun grid(wideR: Double = 0.5, wideHoldoutR: Double = wideR, farR: Double = -0.2): PlanTuner.Grid {
        val grid = PlanTuner.Grid()
        for (day in 0 until 300) {
            for (k in 0 until 2) {
                val r = ShortArray(PlanTuner.SHAPES.size) { -200 }
                val win = (day + k) % 2 == 0
                r[own] = if (win) 1500 else -1300 // 0.1R a plan
                val w = if (day >= 240) wideHoldoutR else wideR
                // Wins at +2R and stops at -1R, mixed to make w on average: a share (w + 1) / 3 of them win, spread
                // evenly (37 and 100 share no factor, so every 100 plans in a row hold that share).
                r[wide] = if ((day * 2 + k) * 37 % 100 < ((w + 1.0) / 3.0 * 100).toInt()) 2000 else -1000
                r[far] = (farR * 1000).toInt().toShort()
                grid.add(0L, 20_000L + day, r)
            }
        }
        return grid
    }

    private fun choose(grid: PlanTuner.Grid, previous: PlanTuner.Choice?): PlanTuner.Choice {
        val from = SetupMiner.holdoutFrom((0 until grid.size).map { grid.day(it) })
        val (train, holdout) = grid.split(from)
        val every = BitSet(grid.size).apply { set(0, grid.size) }
        return PlanTuner.choose(grid, every, train, holdout, previous, PlanShape.OWN, 20_400L)
    }

    @Test
    fun theShapeThatMadeMostAPlanIsChosen() {
        val choice = choose(grid(), null)
        assertEquals(PlanShape(2.0, 2.0), choice.shape)
        assertEquals(20_400L, choice.since)
        assertEquals(0.5, choice.train.averageR, 0.05)
        assertTrue(choice.train.wins > 0 && choice.train.stops > 0)
    }

    @Test
    fun aShapeInUseStaysUnlessAnotherIsClearlyBetterAndGoesWhenItStopsPaying() {
        val inUse = PlanTuner.Choice(PlanShape(3.0, 3.0), PlanTuner.Record.EMPTY, PlanTuner.Record.EMPTY, 20_100L)
        // "Far" makes 0.48R, "wide" 0.5R: not enough better, so the shape in use keeps its day.
        val kept = choose(grid(farR = 0.48), inUse)
        assertEquals(PlanShape(3.0, 3.0), kept.shape)
        assertEquals(20_100L, kept.since)
        // Far now loses while wide still pays: wide takes over.
        assertEquals(PlanShape(2.0, 2.0), choose(grid(farR = -0.2), inUse).shape)
        // A learned shape that lost in the holdout, with nothing else better: back to the app's own.
        val lost = choose(grid(wideR = 0.5, wideHoldoutR = -0.5), PlanTuner.Choice(PlanShape(2.0, 2.0), PlanTuner.Record.EMPTY, PlanTuner.Record.EMPTY, 20_100L))
        assertEquals(PlanShape.OWN, lost.shape)
    }

    @Test
    fun withoutAnyBetterShapeTheAppKeepsItsOwn() {
        assertEquals(PlanShape.OWN, choose(grid(wideR = 0.1), null).shape)
    }
}
