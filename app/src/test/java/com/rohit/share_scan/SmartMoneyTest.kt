package com.rohit.share_scan

import com.rohit.share_scan.SmartMoney.Kind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SmartMoneyTest {
    private fun c(i: Int, o: Double, h: Double, l: Double, close: Double) = IntradayPlan.Candle(i * 86_400L, o, h, l, close, 1000.0)

    @Test
    fun aGapUpLeavesABullishGapUntilPriceComesBackThroughIt() {
        val candles = listOf(
            c(0, 100.0, 101.0, 99.0, 100.5),
            c(1, 101.0, 106.0, 100.8, 105.5),
            c(2, 105.5, 108.0, 103.0, 107.0), // 101 (first's high) < 103 (third's low): gap 101–103
            c(3, 107.0, 108.0, 102.0, 104.0), // into the gap, not through it
        )
        val gaps = SmartMoney.fairValueGaps(candles)
        assertEquals(1, gaps.size)
        assertEquals(Kind.BULLISH_FVG, gaps[0].kind)
        assertEquals(101.0, gaps[0].low, 0.0)
        assertEquals(103.0, gaps[0].high, 0.0)
        assertTrue(gaps[0].open)
        val filled = SmartMoney.fairValueGaps(candles + c(4, 104.0, 104.5, 100.5, 101.0))
        assertFalse(filled[0].open)
    }

    @Test
    fun aDownCandleBeforeABigUpCandleIsABullishOrderBlock() {
        val candles = listOf(
            c(0, 100.0, 100.5, 99.0, 99.2),
            c(1, 99.2, 99.5, 97.0, 97.5), // down candle, range 2.5
            c(2, 97.6, 101.5, 97.4, 101.0), // body 3.4 > 2.5
            c(3, 101.0, 102.0, 100.0, 101.5),
        )
        val blocks = SmartMoney.orderBlocks(candles)
        assertEquals(1, blocks.size)
        assertEquals(Kind.BULLISH_OB, blocks[0].kind)
        assertEquals(97.0, blocks[0].low, 0.0)
        assertEquals(99.5, blocks[0].high, 0.0)
        assertTrue(blocks[0].open)
        val broken = SmartMoney.orderBlocks(candles + c(4, 100.0, 100.0, 95.0, 96.0))
        assertFalse(broken[0].open)
    }

    @Test
    fun nearbyPicksTheClosestOpenZoneOnEachSide() {
        val zones = listOf(
            SmartMoney.Zone(Kind.BULLISH_FVG, 90.0, 92.0, 1, open = true),
            SmartMoney.Zone(Kind.BULLISH_OB, 95.0, 97.0, 2, open = true),
            SmartMoney.Zone(Kind.BULLISH_OB, 98.0, 99.0, 3, open = false),
            SmartMoney.Zone(Kind.BEARISH_FVG, 110.0, 112.0, 4, open = true),
            SmartMoney.Zone(Kind.BEARISH_OB, 104.0, 106.0, 5, open = true),
        )
        val near = SmartMoney.nearby(zones, 100.0)
        assertEquals(95.0, near.support!!.low, 0.0)
        assertEquals(104.0, near.resistance!!.low, 0.0)
        assertNull(SmartMoney.nearby(zones.filter { it.kind.bullish }, 100.0).resistance)
    }
}
