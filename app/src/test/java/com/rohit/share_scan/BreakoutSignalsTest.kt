package com.rohit.share_scan

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BreakoutSignalsTest {
    /** Daily candles, oldest first; nulls stand in for Yahoo's JSON nulls. */
    private class Candles {
        val closes = ArrayList<Double?>()
        val highs = ArrayList<Double?>()
        val lows = ArrayList<Double?>()
        val volumes = ArrayList<Double?>()

        fun add(close: Double?, high: Double?, low: Double?, volume: Double?) {
            closes += close
            highs += high
            lows += low
            volumes += volume
        }

        fun analyse() = BreakoutSignals.analyse(closes, highs, lows, volumes)
    }

    /**
     * Forty sessions of steady advance into a 19-session base that holds within about 3% just under 141.6, on
     * drying volume, then a close a hair under that resistance on [lastVolume] shares.
     */
    private fun tightBase(lastVolume: Double = 1_600_000.0) = Candles().apply {
        for (i in 0 until 40) add(100.0 + i, 101.5 + i, 98.5 + i, 1_000_000.0)
        val base = listOf(139.0, 140.0, 140.5, 139.5, 140.8, 141.0, 139.8, 140.2, 140.9, 139.6)
        for (i in 0 until 19) {
            val close = base[i % base.size]
            add(close, close + 0.6, close - 0.6, 700_000.0 - i * 10_000.0)
        }
        add(141.2, 141.5, 140.4, lastVolume)
    }

    /** Sixty sessions sliding from 200 to about 141 with 8% daily ranges and erratic volume. */
    private fun sloppyDowntrend() = Candles().apply {
        for (i in 0 until 60) {
            val close = 200.0 - i + if (i % 2 == 0) 3.0 else -3.0
            add(close, close * 1.04, close * 0.96, if (i % 3 == 0) 1_500_000.0 else 600_000.0)
        }
    }

    private fun BreakoutSignals.Signals.part(key: String) = parts.first { it.key == key }.value

    @Test
    fun tightBaseUnderResistanceOutscoresSloppyDowntrend() {
        val setup = tightBase().analyse()!!
        val falling = sloppyDowntrend().analyse()!!
        assertTrue("setup ${setup.score} vs falling ${falling.score}", setup.score > falling.score + 30.0)
        for (key in listOf(ScoreKeys.BREAKOUT, ScoreKeys.TREND, ScoreKeys.VOLUME, ScoreKeys.MOMENTUM)) {
            assertTrue(key, setup.part(key) > falling.part(key))
        }
        assertEquals(StockFormat.Tier.STRONG, StockFormat.tier(setup.score))
        assertEquals(StockFormat.Tier.WEAK, StockFormat.tier(falling.score))
    }

    @Test
    fun scoreIsTheWeightedSumOfSpreadParts() {
        val signals = tightBase().analyse()!!
        assertEquals(BreakoutSignals.WEIGHTS.keys.toList() + ScoreKeys.SUPPORT, signals.parts.map { it.key })
        // The weighted parts add up to 1; the support bonus comes on top.
        val weighted = signals.parts.filter { it.key != ScoreKeys.SUPPORT }
        assertEquals(1.0, weighted.sumOf { it.weight }, 1e-9)
        for (part in weighted) assertTrue(part.key, part.value > 0.0 && part.value < 100.0)
        val bonus = signals.parts.single { it.key == ScoreKeys.SUPPORT }
        assertEquals(BreakoutSignals.SUPPORT_BONUS, bonus.weight, 1e-12)
        assertEquals((signals.support?.strength ?: 0.0), bonus.value, 1e-9)
        assertEquals(signals.parts.sumOf { it.value * it.weight }, signals.score, 1e-9)

        // Stronger confirmation still ranks higher instead of tying at a cap.
        val louder = tightBase(lastVolume = 6_000_000.0).analyse()!!
        val loudest = tightBase(lastVolume = 20_000_000.0).analyse()!!
        assertTrue(louder.score > signals.score)
        assertTrue(loudest.score > louder.score)
        assertTrue(loudest.score < 100.0)
        assertEquals(1_600_000.0 / 629_500.0, signals.rvol, 1e-9)
        assertEquals((141.2 / 140.8 - 1.0) * 100.0, signals.change5d, 1e-9)
    }

    @Test
    fun structureFollowsItsDefinition() {
        val candles = Candles()
        for (i in 0 until 70) {
            val close = 100.0 + i
            // The latest high must not count towards the 20-day high, and an old low must not count towards low10.
            val high = if (i == 69) 500.0 else close + 2.0
            val low = if (i == 50) 1.0 else close - 2.0
            candles.add(close, high, low, 1_000.0)
        }
        val structure = candles.analyse()!!.structure
        assertEquals((10 until 70).map { 100.0 + it }, structure.closes)
        assertEquals(170.0, structure.high20, 1e-9)
        assertEquals(500.0, structure.high10!!, 1e-9)
        assertEquals(158.0, structure.low10, 1e-9)
        // Thirteen sessions with a true range of 4, then the latest: a 500 high over a 167 low.
        assertEquals((13 * 4.0 + 333.0) / 14.0, structure.atr14, 1e-9)
        assertEquals(159.5, structure.sma20, 1e-9)
        assertEquals(144.5, structure.sma50!!, 1e-9)

        val stock = ScanResult("LINEAR", 169.0, 50.0, 1.0, 0.0, structure = structure)
        assertEquals(1.0 / 170.0 * 100.0, stock.belowHigh20Pct!!, 1e-9)
        assertEquals((500.0 - 158.0) / 329.0 * 100.0, stock.baseRangePct!!, 1e-9)
        // 1–4 weeks back the lows are 163, 158, 153 and the old 1, under the latest 500 high.
        val weeks = listOf(163.0, 158.0, 153.0, 1.0).map { BreakoutSignals.rangePct(500.0, it) }
        assertEquals(weeks, structure.weekRanges)
        assertEquals(stock.baseRangePct!!, stock.rangePct(BreakoutSignals.BASE_WEEKS)!!, 1e-9)
        assertEquals((169.0 / 144.5 - 1.0) * 100.0, stock.vsSma50Pct!!, 1e-9)
    }

    @Test
    fun shortHistoryHasNoFiftyDayAverage() {
        val candles = Candles()
        for (i in 0 until 40) candles.add(100.0 + i, 101.0 + i, 99.0 + i, 5_000.0)
        val signals = candles.analyse()!!
        assertNull(signals.structure.sma50)
        assertEquals(40, signals.structure.closes.size)
        assertNull(ScanResult("NEW", 139.0, 50.0, 1.0, 0.0, structure = signals.structure).vsSma50Pct)
    }

    @Test
    fun sessionsWithMissingValuesAreDroppedFromEverySeries() {
        val clean = tightBase()
        val dirty = Candles()
        for (i in clean.closes.indices) {
            when (i) {
                10 -> dirty.add(null, null, null, null)
                25 -> dirty.add(130.0, null, 128.0, 900_000.0)
                41 -> dirty.add(140.0, 141.0, 139.0, null)
                50 -> dirty.add(Double.NaN, 141.0, 139.0, 800_000.0)
            }
            dirty.add(clean.closes[i], clean.highs[i], clean.lows[i], clean.volumes[i])
        }
        // A session still forming: no close yet, but Yahoo already reports some volume.
        dirty.add(null, 142.0, 140.0, 50_000.0)
        assertEquals(clean.analyse(), dirty.analyse())

        val short = Candles()
        for (i in 0 until 20) short.add(100.0, 101.0, 99.0, 1_000.0)
        short.add(null, 101.0, 99.0, 1_000.0)
        assertNull(short.analyse())
        short.add(100.0, 101.0, 99.0, 1_000.0)
        assertNotNull(short.analyse())
    }

    @Test
    fun badBarsAreRepairedOrSkipped() {
        val candles = Candles()
        for (i in 0 until 25) candles.add(100.0, 101.0, 99.0, 1_000.0)
        candles.add(-1.0, 101.0, 99.0, 1_000.0)
        candles.add(100.0, 101.0, 99.0, -5.0)
        // High and low swapped: still read as a 99–101 candle.
        candles.add(100.0, 99.0, 101.0, 1_000.0)
        val signals = candles.analyse()!!
        assertEquals(26, signals.structure.closes.size)
        assertEquals(101.0, signals.structure.high10!!, 1e-9)
        assertEquals(99.0, signals.structure.low10, 1e-9)
    }

    @Test
    fun softCapKeepsOrderWithoutReachingTheEdges() {
        assertEquals(50.0, BreakoutSignals.soft(50.0), 0.0)
        assertEquals(85.0, BreakoutSignals.soft(85.0), 1e-12)
        assertEquals(15.0, BreakoutSignals.soft(15.0), 1e-12)
        assertTrue(BreakoutSignals.soft(120.0) < BreakoutSignals.soft(160.0))
        assertTrue(BreakoutSignals.soft(160.0) < 100.0)
        assertTrue(BreakoutSignals.soft(-40.0) > 0.0)
        assertTrue(BreakoutSignals.soft(-40.0) < BreakoutSignals.soft(0.0))
        assertEquals(0.0, BreakoutSignals.soft(Double.NaN), 0.0)
    }

    /** Forty quiet sessions between 99 and 101, closing at 100. */
    private fun flat() = Candles().apply { repeat(40) { add(100.0, 101.0, 99.0, 1_000_000.0) } }

    @Test
    fun aDipUnderTheLowsThatClosesBackUpIsASweep() {
        val swept = flat().apply { add(100.6, 101.0, 97.5, 1_800_000.0) }.analyse()!!
        assertTrue(swept.sweptLow)
        assertTrue(!swept.weakBreakout && !swept.recentFakeout)
        // Closing under the old low is a breakdown, not a sweep; so is a close in the lower half of the day.
        assertTrue(!flat().apply { add(98.0, 100.0, 97.5, 1_800_000.0) }.analyse()!!.sweptLow)
        assertTrue(!flat().apply { add(99.2, 101.0, 97.5, 1_800_000.0) }.analyse()!!.sweptLow)
    }

    @Test
    fun aBreakoutThatCannotHoldIsAFakeout() {
        // Over the 20-day high of 101 during the day, closing back under it: a weak breakout today.
        val weak = flat().apply { add(100.5, 103.0, 99.5, 1_500_000.0) }.analyse()!!
        assertTrue(weak.weakBreakout)
        // A strong close over it is no warning.
        assertTrue(!flat().apply { add(102.8, 103.0, 100.5, 1_500_000.0) }.analyse()!!.weakBreakout)
        // A close over the high two sessions ago, given back today: a recent fakeout.
        val given = flat().apply {
            add(102.5, 103.0, 100.5, 1_500_000.0)
            add(101.5, 102.6, 101.0, 900_000.0)
            add(100.2, 101.4, 99.8, 900_000.0)
        }.analyse()!!
        assertTrue(given.recentFakeout)
        // Held over it, no fakeout.
        val held = flat().apply {
            add(102.5, 103.0, 100.5, 1_500_000.0)
            add(103.2, 103.8, 102.4, 900_000.0)
        }.analyse()!!
        assertTrue(!held.recentFakeout)
    }

    @Test
    fun aDipUnderATestedSupportThatClosesBackUpIsAStopHunt() {
        // Swings between 100 and 110 for four months: the dips to 100 make a floor, then a dip to 98.5 closes at 104.
        val candles = Candles().apply {
            for (i in 0 until 84) {
                val phase = i % 21
                val close = if (phase < 11) 100.0 + phase else 120.0 - phase
                add(close, close + 0.8, close - 0.8, 1_000_000.0)
            }
            add(104.0, 104.5, 98.5, 2_000_000.0)
        }
        assertTrue(candles.analyse()!!.stopHunt)
        // The same dip closing under the floor broke it; a dip that stays above it hunted nothing.
        val broke = Candles().apply {
            candles.closes.dropLast(1).indices.forEach { add(candles.closes[it], candles.highs[it], candles.lows[it], candles.volumes[it]) }
            add(98.8, 101.0, 98.5, 2_000_000.0)
        }
        assertTrue(!broke.analyse()!!.stopHunt)
        val above = Candles().apply {
            candles.closes.dropLast(1).indices.forEach { add(candles.closes[it], candles.highs[it], candles.lows[it], candles.volumes[it]) }
            add(104.0, 104.5, 101.0, 2_000_000.0)
        }
        assertTrue(!above.analyse()!!.stopHunt)
    }
}
