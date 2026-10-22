package com.rohit.share_scan

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TradePlanTest {
    private val eps = 1e-9

    @Test
    fun belowTheHighEntryWaitsForTheBreakoutTrigger() {
        // Wide base: the 10-day low is far away, so the stop is capped at 2 ATR under the entry.
        val plan = TradePlan.of(price = 100.0, high20 = 105.0, low10 = 95.0, atr14 = 2.0)!!
        assertEquals(TradePlan.EntryKind.BREAKOUT, plan.entryKind)
        assertEquals(105.2, plan.trigger, eps)
        assertEquals(105.2, plan.entry, eps)
        assertEquals(TradePlan.StopKind.MAX_ATR, plan.stopKind)
        assertEquals(101.2, plan.stop, eps)
        assertEquals(4.0, plan.risk, eps)
        assertEquals(listOf(111.2, 115.2, 121.2), plan.targets.map { Math.round(it * 1e6) / 1e6 })
    }

    @Test
    fun targetsAreTimedByTheirDistanceInAtrs() {
        // Risk is 2 ATR, so the targets sit 3, 5 and 8 ATRs above the entry.
        val plan = TradePlan.of(price = 100.0, high20 = 105.0, low10 = 95.0, atr14 = 2.0)!!
        assertEquals(listOf(3.0, 5.0, 8.0).map(TargetTimes::sessions), plan.targetSessions)
        assertEquals(5..15, plan.targetSessions[0])
    }

    @Test
    fun aboveTheTriggerEntryIsTheCurrentPrice() {
        val plan = TradePlan.of(price = 110.0, high20 = 105.0, low10 = 108.5, atr14 = 2.0)!!
        assertEquals(TradePlan.EntryKind.MARKET, plan.entryKind)
        assertEquals(110.0, plan.entry, eps)
        // Structure stop 108.5 - 0.3 = 108.2 is closer than 1 ATR, so it is pushed down to 108.0.
        assertEquals(TradePlan.StopKind.MIN_ATR, plan.stopKind)
        assertEquals(108.0, plan.stop, eps)
        assertEquals(0.0, plan.fromPrice(plan.entry), eps)
    }

    @Test
    fun priceExactlyAtTheTriggerCountsAsBrokenOut() {
        val trigger = 105.0 + TradePlan.TRIGGER_ATR * 2.0
        val plan = TradePlan.of(price = trigger, high20 = 105.0, low10 = 100.0, atr14 = 2.0)!!
        assertEquals(TradePlan.EntryKind.MARKET, plan.entryKind)
        assertEquals(trigger, plan.entry, eps)
    }

    @Test
    fun stopSitsUnderTheTenDayLowWhenThatIsBetweenOneAndTwoAtr() {
        val plan = TradePlan.of(price = 100.0, high20 = 101.0, low10 = 98.5, atr14 = 2.0)!!
        assertEquals(101.2, plan.entry, eps)
        assertEquals(TradePlan.StopKind.STRUCTURE, plan.stopKind)
        assertEquals(98.2, plan.stop, eps)
        assertEquals(3.0, plan.risk, eps)
    }

    @Test
    fun levelsAreReportedAsRMultiplesAndPercentFromPrice() {
        val plan = TradePlan.of(price = 100.0, high20 = 105.0, low10 = 95.0, atr14 = 2.0)!!
        assertEquals(0.0, plan.rMultiple(plan.entry), eps)
        assertEquals(-1.0, plan.rMultiple(plan.stop), eps)
        assertEquals(listOf(1.5, 2.5, 4.0), plan.targets.map { Math.round(plan.rMultiple(it) * 1e6) / 1e6 })
        assertEquals(5.2, plan.fromPrice(plan.entry), 1e-6)
        assertEquals(1.2, plan.fromPrice(plan.stop), 1e-6)
        assertEquals(4.0 / 105.2 * 100.0, plan.riskPercent, 1e-9)
    }

    @Test
    fun missingAtrFallsBackToOnePercentOfPrice() {
        for (atr in listOf(0.0, -1.0, Double.NaN, Double.POSITIVE_INFINITY)) {
            val plan = TradePlan.of(price = 200.0, high20 = 210.0, low10 = 190.0, atr14 = atr)!!
            assertEquals(2.0, plan.atr, eps)
            assertEquals(210.2, plan.entry, eps)
        }
    }

    @Test
    fun invalidInputsGiveNoPlan() {
        assertNull(TradePlan.of(price = 0.0, high20 = 10.0, low10 = 9.0, atr14 = 0.5))
        assertNull(TradePlan.of(price = Double.NaN, high20 = 10.0, low10 = 9.0, atr14 = 0.5))
        assertNull(TradePlan.of(price = 10.0, high20 = Double.NaN, low10 = 9.0, atr14 = 0.5))
        assertNull(TradePlan.of(price = 10.0, high20 = 11.0, low10 = 0.0, atr14 = 0.5))
        // A stop at or below zero is not a usable plan.
        assertNull(TradePlan.of(price = 1.0, high20 = 1.2, low10 = 0.05, atr14 = 0.8))
    }

    @Test
    fun planFromPriceStructure() {
        val structure = PriceStructure(
            closes = listOf(96.0, 98.0, 100.0),
            high20 = 105.0,
            low10 = 95.0,
            atr14 = 2.0,
            sma20 = 99.0,
            sma50 = null,
        )
        assertEquals(TradePlan.of(100.0, 105.0, 95.0, 2.0), TradePlan.of(100.0, structure))
    }

    @Test
    fun extendedOnlyWellAboveTheHigh() {
        assertFalse(TradePlan.of(price = 100.0, high20 = 105.0, low10 = 95.0, atr14 = 2.0)!!.isExtended)
        assertFalse(TradePlan.of(price = 108.9, high20 = 105.0, low10 = 104.0, atr14 = 2.0)!!.isExtended)
        val extended = TradePlan.of(price = 110.0, high20 = 105.0, low10 = 104.0, atr14 = 2.0)!!
        assertTrue(extended.isExtended)
        assertEquals(2.5, extended.extensionAtr, eps)
    }

    @Test
    fun stopIsAlwaysBetweenOneAndTwoAtrUnderAnEntryBelowTheTargets() {
        val prices = listOf(12.0, 98.0, 100.0, 104.0, 250.0)
        val lows = listOf(5.0, 90.0, 97.0, 99.5)
        val atrs = listOf(0.3, 1.0, 2.5, 6.0)
        for (price in prices) for (low in lows) for (atr in atrs) {
            val plan = TradePlan.of(price, high20 = 101.0, low10 = low, atr14 = atr) ?: continue
            assertTrue(plan.entry >= price)
            assertTrue(plan.stop > 0.0)
            assertTrue(plan.risk >= atr - eps && plan.risk <= 2 * atr + eps)
            assertEquals(3, plan.targets.size)
            assertTrue(plan.targets.zipWithNext().all { (a, b) -> b > a })
            assertTrue(plan.targets.first() > plan.entry)
        }
    }

    @Test
    fun rMultiplesAndPercentagesAreSigned() {
        assertEquals("+1.5R", TradePlan.formatR(1.5))
        assertEquals("−1.0R", TradePlan.formatR(-1.0))
        assertEquals("0.0R", TradePlan.formatR(0.0))
        assertEquals("0.0R", TradePlan.formatR(-0.04))
        assertEquals("+4.0R", TradePlan.formatR(3.999))
        assertEquals("+2.15%", TradePlan.formatPercent(2.153))
        assertEquals("−6.30%", TradePlan.formatPercent(-6.3))
        assertEquals("0.00%", TradePlan.formatPercent(-0.001))
    }

    @Test
    fun aLearnedShapeMovesTheStopAndTargets() {
        // Entry 104.2 at ATR 2; a learned stop 1.5 ATR under it and Target 1 at 2R.
        val plan = TradePlan.of(100.0, 104.0, 98.0, 2.0, PlanShape(1.5, 2.0))!!
        assertEquals(TradePlan.StopKind.LEARNED, plan.stopKind)
        assertEquals(101.2, plan.stop, 1e-9)
        assertEquals(listOf(110.2, 113.2, 117.7), plan.targets.map { Math.round(it * 100) / 100.0 })
        // The app's own stop with a learned Target 1.
        val own = TradePlan.of(100.0, 104.0, 98.0, 2.0, PlanShape(0.0, 1.0))!!
        assertEquals(TradePlan.StopKind.MAX_ATR, own.stopKind)
        assertEquals(104.2 + 4.0, own.targets.first(), 1e-9)
        assertEquals(TradePlan.of(100.0, 104.0, 98.0, 2.0), TradePlan.of(100.0, 104.0, 98.0, 2.0, PlanShape.OWN))
        // A learned time limit stretches every target's sessions.
        val usual = TradePlan.of(100.0, 104.0, 98.0, 2.0, PlanShape(1.5, 2.0))!!.targetSessions.first()
        val longer = TradePlan.of(100.0, 104.0, 98.0, 2.0, PlanShape(1.5, 2.0, 2.0))!!.targetSessions.first()
        assertEquals(usual.first * 2..usual.last * 2, longer)
    }
}
