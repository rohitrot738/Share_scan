package com.rohit.share_scan

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SetupConditionsTest {
    private val structure = PriceStructure(
        closes = listOf(100.0, 105.0),
        high20 = 104.0,
        low10 = 98.0,
        atr14 = 2.0,
        sma20 = 101.0,
        sma50 = 97.0,
        weekRanges = listOf(4.5, 7.5, 9.0, 12.0),
        rsi14 = 64.0,
        macd = 1.2,
        macdSignal = 0.9,
        macdCrossAge = 2,
    )

    private fun signals(price: Double = 105.0, rvol: Double = 2.4) = BreakoutSignals.Signals(
        price = price,
        volume = 1e6,
        rvol = rvol,
        change5d = 6.0,
        structure = structure,
        score = 78.0,
        parts = emptyList(),
        support = SupportFloor(level = 102.0, ageSessions = 90, touches = 3, unbroken = true, window = 124),
        quietVolume = 0.7,
        change20 = 9.0,
        medianTurnoverCr = 12.0,
    )

    @Test
    fun aVolumeBreakoutInAnUptrendHasItsConditions() {
        val s = signals()
        val mask = SetupCondition.mask(s, TradePlan.of(s.price, structure), previousScore = 80.0)
        val held = SetupCondition.of(mask).toSet()
        val expected = setOf(
            SetupCondition.BREAKOUT_TODAY, SetupCondition.VOLUME_2X, SetupCondition.VOLUME_1_5X, SetupCondition.QUIET_BEFORE,
            SetupCondition.TIGHT_WEEK, SetupCondition.TIGHT_TWO_WEEKS, SetupCondition.ABOVE_SMA50, SetupCondition.SMA20_OVER_SMA50,
            SetupCondition.UP_20_DAYS, SetupCondition.UP_5_PERCENT_WEEK, SetupCondition.RSI_50_70, SetupCondition.MACD_ABOVE_SIGNAL,
            SetupCondition.MACD_FRESH_CROSS, SetupCondition.MACD_ABOVE_ZERO, SetupCondition.NEAR_SUPPORT, SetupCondition.LONG_SUPPORT,
            SetupCondition.LIQUID_10CR,
            SetupCondition.SCORE_STRONG, SetupCondition.SCORE_WATCH, SetupCondition.PREV_STRONG,
        )
        assertEquals(expected, held)
    }

    @Test
    fun underTheTriggerItIsNearTheTriggerNotABreakout() {
        val s = signals(price = 103.0, rvol = 1.0)
        val held = SetupCondition.of(SetupCondition.mask(s, TradePlan.of(s.price, structure), previousScore = null))
        assertTrue(SetupCondition.NEAR_TRIGGER in held)
        assertTrue(SetupCondition.BREAKOUT_TODAY !in held)
        assertTrue(SetupCondition.VOLUME_1_5X !in held)
        assertTrue(SetupCondition.PREV_STRONG !in held)
    }

    @Test
    fun whatComesBeforeARallyHasItsConditions() {
        val s = signals().copy(fromHighPct = 2.0, change1d = 4.5, dayPosition = 0.8, calm = 0.6)
        val held = SetupCondition.of(SetupCondition.mask(s, TradePlan.of(s.price, structure), previousScore = null))
        assertTrue(
            held.containsAll(
                listOf(SetupCondition.NEAR_6M_HIGH, SetupCondition.UP_4_PERCENT_TODAY, SetupCondition.CLOSE_NEAR_DAY_HIGH, SetupCondition.CALM_RANGES),
            ),
        )
        val quiet = SetupCondition.of(SetupCondition.mask(s.copy(fromHighPct = 3.5, change1d = 3.9, dayPosition = 0.7, calm = 0.75), null, null))
        assertTrue(quiet.none { it.bit in 23..26 })
    }

    @Test
    fun bitsAreUniqueAndFitALong() {
        assertEquals(SetupCondition.entries.size, SetupCondition.entries.map { it.bit }.toSet().size)
        assertTrue(SetupCondition.entries.all { it.bit in 0..62 })
        assertEquals(SetupCondition.RSI_50_70, SetupCondition.ofBit(11))
    }
}
