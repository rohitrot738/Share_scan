package com.rohit.share_scan

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.random.Random

class PlanTunerTest {
    private val own = PlanTuner.cell(PlanShape.OWN)

    @Test
    fun thirtyThousandShapesWithTheAppsOwnAmongThem() {
        assertEquals(30_000, PlanTuner.CELLS)
        assertEquals(PlanShape.OWN, PlanTuner.shape(own))
        for (cell in listOf(0, 1, 599, 600, 12_345, 29_999)) assertEquals(cell, PlanTuner.cell(PlanTuner.shape(cell)))
        assertEquals(PlanShape(3.4, 5.45, 3.0), PlanTuner.shape(PlanTuner.CELLS - 1))
        assertEquals(-1, PlanTuner.cell(PlanShape(1.03, 1.5)))
    }

    /** Every shape is judged exactly as the app's plan drawn with it ([TradePlan], [PlanOutcome]) would be. */
    @Test
    fun everyShapeIsJudgedAsTheAppsPlanWouldBe() {
        val random = Random(7)
        var checked = 0
        repeat(60) {
            val n = 300
            val close = DoubleArray(n)
            val high = DoubleArray(n)
            val low = DoubleArray(n)
            var price = 100.0
            for (i in 0 until n) {
                price *= 1 + random.nextDouble(-0.03, 0.032)
                close[i] = price
                high[i] = price * (1 + random.nextDouble(0.0, 0.02))
                low[i] = price * (1 - random.nextDouble(0.0, 0.02))
            }
            // Late plans too, so some shapes run past the chart's end.
            val at = random.nextInt(20, n - 5)
            val atr = close[at] * random.nextDouble(0.015, 0.04)
            val high20 = close[at] * random.nextDouble(0.97, 1.03)
            val low10 = close[at] * random.nextDouble(0.9, 0.98)
            val plan = TradePlan.of(close[at], high20, low10, atr) ?: return@repeat
            val results = IntArray(PlanTuner.CELLS)
            val entered = PlanTuner.judge(plan, high, low, close, at, results)
            val breakout = plan.entryKind == TradePlan.EntryKind.BREAKOUT
            repeat(400) { k ->
                val cell = if (k == 0) own else random.nextInt(PlanTuner.CELLS)
                val shape = PlanTuner.shape(cell)
                val shaped = TradePlan.of(close[at], high20, low10, atr, shape)!!
                val outcome = PlanOutcome.of(shaped, high, low, at, shaped.targets.first(), shaped.targetSessions.first().last)
                val target = (shape.targetR * 1000).roundToInt()
                val expected = when (outcome.result) {
                    PlanOutcome.Result.WIN -> target
                    PlanOutcome.Result.STOPPED -> -1000
                    PlanOutcome.Result.EXPIRED -> ((close[outcome.exit] - shaped.entry) / shaped.risk * 1000).roundToInt().coerceIn(-999, target - 1)
                    else -> null
                }
                if (entered) assertEquals("cell $cell", expected ?: PlanTuner.UNDECIDED, results[cell]) else assertNull(expected)
                assertEquals(expected, PlanTuner.result(cell, plan.entry, breakout, plan.stop, plan.atr, high, low, close, at))
                checked++
            }
        }
        assertTrue(checked > 10_000)
    }

    @Test
    fun aPlanNeverEnteredIsNotJudged() {
        // Entry 104.2 above every high that follows.
        val plan = TradePlan.of(100.0, 104.0, 98.0, 2.0)!!
        val high = DoubleArray(10) { 101.0 }
        val low = DoubleArray(10) { 99.0 }
        val results = IntArray(PlanTuner.CELLS) { 7 }
        assertFalse(PlanTuner.judge(plan, high, low, high, 0, results))
        assertEquals(7, results[own])
    }

    private fun indices(cell: Int) = Triple(cell / 6 / 100, cell / 6 % 100, cell % 6)

    /** Within one step of [center] in stop, target and time. */
    private fun near(cell: Int, center: Int): Boolean {
        val (s, t, d) = indices(cell)
        val (s0, t0, d0) = indices(center)
        return abs(s - s0) <= 1 && abs(t - t0) <= 1 && abs(d - d0) <= 1
    }

    /** 300 sessions × 2 plans; [result] gives each shape's result for a plan (null: undecided), [late] in the holdout. */
    private fun totals(result: (cell: Int, plan: Int, late: Boolean) -> Int?): PlanTuner.Totals {
        val totals = PlanTuner.Totals()
        val results = IntArray(PlanTuner.CELLS)
        for (day in 0 until 300) {
            for (k in 0 until 2) {
                val late = day >= 240
                for (cell in 0 until PlanTuner.CELLS) results[cell] = result(cell, day * 2 + k, late) ?: PlanTuner.UNDECIDED
                totals.add(results, holdout = late)
            }
        }
        return totals
    }

    /** The app's own plan: +1.5R and -1.3R by turns, 0.1R a plan. */
    private fun ownResult(plan: Int) = if (plan % 2 == 0) 1500 else -1300

    private val good = PlanTuner.cell(PlanShape(2.0, 2.0, 1.0))
    private val lucky = PlanTuner.cell(PlanShape(3.0, 4.5, 2.0))

    @Test
    fun theShapeWhoseNeighboursAlsoDidWellIsChosenNotALuckyOne() {
        val totals = totals { cell, plan, _ ->
            when {
                cell == own -> ownResult(plan)
                cell == lucky -> 3000
                near(cell, lucky) -> -800
                near(cell, good) -> 500
                else -> -200
            }
        }
        val choice = PlanTuner.choose(totals, null, PlanShape.OWN, 20_400L)
        assertEquals(PlanShape(2.0, 2.0, 1.0), choice.shape)
        assertEquals(0.5, choice.train.averageR, 1e-9)
        assertEquals(20_400L, choice.since)
        assertEquals(480, choice.train.n)
        assertEquals(120, choice.holdout.n)
    }

    @Test
    fun aShapeInUseStaysUnlessAnotherIsClearlyBetterAndGoesWhenItStopsPaying() {
        val far = PlanTuner.cell(PlanShape(3.0, 3.0, 1.0))
        fun market(goodR: Int, goodLate: Int = goodR, farR: Int) = totals { cell, plan, late ->
            when {
                cell == own -> ownResult(plan)
                near(cell, good) -> if (late) goodLate else goodR
                near(cell, far) -> farR
                else -> -200
            }
        }
        val inUse = PlanTuner.Choice(PlanTuner.shape(far), PlanTuner.Record.EMPTY, PlanTuner.Record.EMPTY, 20_100L)
        // Far makes 0.48R, good 0.5R: not enough better, so the shape in use keeps its day.
        val kept = PlanTuner.choose(market(goodR = 500, farR = 480), inUse, PlanShape.OWN, 20_400L)
        assertEquals(PlanTuner.shape(far), kept.shape)
        assertEquals(20_100L, kept.since)
        // Far now loses while good still pays: good takes over.
        assertEquals(PlanTuner.shape(good), PlanTuner.choose(market(goodR = 500, farR = -200), inUse, PlanShape.OWN, 20_400L).shape)
        // A learned shape that lost in the holdout, with nothing else better: back to the app's own.
        val goodInUse = PlanTuner.Choice(PlanTuner.shape(good), PlanTuner.Record.EMPTY, PlanTuner.Record.EMPTY, 20_100L)
        val lost = PlanTuner.choose(market(goodR = 500, goodLate = -500, farR = -200), goodInUse, PlanShape.OWN, 20_400L)
        assertEquals(PlanShape.OWN, lost.shape)
        assertEquals(20_400L, lost.since)
    }

    @Test
    fun shapesCanBeRuledOutAndWithoutABetterOneTheAppKeepsItsOwn() {
        val totals = totals { cell, plan, _ ->
            when {
                cell == own -> ownResult(plan)
                near(cell, good) -> 500
                else -> -200
            }
        }
        // Ruling out every stop within two steps of the good one leaves nothing that saw it, even as a neighbour.
        val farFromGood = PlanTuner.choose(totals, null, PlanShape.OWN, 20_400L) { cell, _ -> abs(indices(cell).first - indices(good).first) > 2 }
        assertEquals(PlanShape.OWN, farFromGood.shape)
        val flat = totals { cell, plan, _ -> if (cell == own) ownResult(plan) else 100 }
        assertEquals(PlanShape.OWN, PlanTuner.choose(flat, null, PlanShape.OWN, 20_400L).shape)
    }
}
