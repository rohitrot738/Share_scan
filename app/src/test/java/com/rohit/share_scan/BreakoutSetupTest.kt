package com.rohit.share_scan

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BreakoutSetupTest {
    private val eps = 1e-9

    private fun stock(price: Double, rvol: Double, score: Double = 70.0, yesterday: Double? = null) = ScanResult(
        symbol = "S",
        price = price,
        score = score,
        rvol = rvol,
        change5d = 0.0,
        pastScores = listOfNotNull(yesterday?.let { DayScore(1L, it) }, DayScore(0L, 10.0)),
    )

    private fun kind(stock: ScanResult): BreakoutSetup.Kind =
        BreakoutSetup.of(stock, TradePlan.of(stock.price, high20 = 105.0, low10 = 104.0, atr14 = 2.0)!!)

    @Test
    fun underTheTriggerTheBreakoutIsStillAhead() {
        assertEquals(BreakoutSetup.Kind.WAITING, kind(stock(100.0, rvol = 5.0, yesterday = 90.0)))
    }

    @Test
    fun farAboveTheHighIsNoSetup() {
        assertEquals(BreakoutSetup.Kind.EXTENDED, kind(stock(110.0, rvol = 5.0, yesterday = 90.0)))
    }

    @Test
    fun aBreakoutIsJudgedByItsVolumeAndYesterdaysScore() {
        assertEquals(BreakoutSetup.Kind.LOW_VOLUME, kind(stock(106.0, rvol = 1.99, yesterday = 90.0)))
        assertEquals(BreakoutSetup.Kind.VOLUME, kind(stock(106.0, rvol = 2.0, yesterday = 74.9)))
        assertEquals(BreakoutSetup.Kind.HIGH_PROBABILITY, kind(stock(106.0, rvol = 2.0, yesterday = 75.0)))
        // Yesterday's score counts, not today's.
        assertEquals(BreakoutSetup.Kind.VOLUME, kind(stock(106.0, rvol = 3.0, score = 95.0, yesterday = 60.0)))
        // Without past scores, today's stands in.
        assertEquals(BreakoutSetup.Kind.HIGH_PROBABILITY, kind(stock(106.0, rvol = 3.0, score = 80.0).copy(pastScores = emptyList())))
    }

    @Test
    fun betterSetupsHitTheQuickTargetMoreOften() {
        val kinds = listOf(BreakoutSetup.Kind.HIGH_PROBABILITY, BreakoutSetup.Kind.VOLUME, BreakoutSetup.Kind.LOW_VOLUME)
        assertEquals(kinds, kinds.sortedByDescending { it.quickHits })
        BreakoutSetup.Kind.entries.forEach { assertTrue(it.quickHitsWide > it.quickHits) }
    }

    @Test
    fun quickTargetAndWideStopAreMultiplesOfTheRisk() {
        // Risk is 4: entry 105.2, stop 101.2.
        val plan = TradePlan.of(price = 100.0, high20 = 105.0, low10 = 95.0, atr14 = 2.0)!!
        assertEquals(108.2, plan.quickTarget, eps)
        assertEquals(99.2, plan.wideStop, eps)
        assertEquals(TargetTimes.sessions(1.5), plan.quickSessions)
        assertEquals(0.75, plan.rMultiple(plan.quickTarget), eps)
        assertEquals(-1.5, plan.rMultiple(plan.wideStop), eps)
    }
}
