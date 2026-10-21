package com.rohit.share_scan

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream

class ScanFiltersTest {
    private val big = ScanResult("BIG", 1_000.0, 70.0, 2.5, 4.0, volume = 2_000_000.0, marketCapCr = 50_000.0)
    private val small = ScanResult("SMALL", 40.0, 60.0, 1.2, -3.0, volume = 30_000.0, marketCapCr = 300.0)
    private val unknown = ScanResult("UNKNOWN", 500.0, 80.0, 4.0, 9.0, volume = 900_000.0)

    @Test
    fun boundsAreInclusiveAndFailClosedOnMissingValues() {
        assertTrue(Bound().accepts(null))
        assertTrue(Bound(min = 500.0).accepts(500.0))
        assertFalse(Bound(min = 500.0).accepts(499.9))
        assertTrue(Bound(max = 3.0).accepts(3.0))
        assertFalse(Bound(min = 500.0).accepts(null))
    }

    @Test
    fun everyActiveMetricMustMatch() {
        val filters = ScanFilters()
            .with(Metric.MARKET_CAP, Bound(min = 1_000.0))
            .with(Metric.VOLUME, Bound(min = 100_000.0))
        assertEquals(2, filters.activeCount)
        assertTrue(filters.accepts(big))
        assertFalse(filters.accepts(small))
        assertFalse(filters.accepts(unknown))
        assertEquals(200.0, big.turnoverCr, 1e-9)
    }

    @Test
    fun breakoutMetricsReadThePriceStructure() {
        val structure = PriceStructure(
            closes = listOf(95.0, 98.0, 97.0),
            high20 = 100.0,
            low10 = 90.0,
            atr14 = 2.0,
            sma20 = 96.0,
            sma50 = 80.0,
            high10 = 100.0,
        )
        val coiled = ScanResult("COILED", 97.0, 78.0, 1.8, 2.0, volume = 500_000.0, structure = structure)
        assertEquals(3.0, Metric.FROM_HIGH.read(coiled)!!, 1e-9)
        assertEquals(10.0 / 95.0 * 100.0, Metric.BASE_RANGE.read(coiled)!!, 1e-9)
        assertEquals(21.25, Metric.VS_SMA50.read(coiled)!!, 1e-9)
        val brokenOut = coiled.copy(price = 104.0)
        assertEquals(-4.0, brokenOut.belowHigh20Pct!!, 1e-9)

        val nearHigh = ScanFilters()
            .with(Metric.FROM_HIGH, Bound(max = 3.0))
            .with(Metric.BASE_RANGE, Bound(max = 12.0))
            .with(Metric.VS_SMA50, Bound(min = 0.0))
        assertTrue(nearHigh.accepts(coiled))
        assertTrue(nearHigh.accepts(brokenOut))
        assertFalse(nearHigh.accepts(coiled.copy(price = 90.0)))
        // Without candles (or without a 50-day average) the limits fail closed, like an unknown market cap.
        assertNull(big.belowHigh20Pct)
        assertFalse(nearHigh.accepts(big))
        assertFalse(nearHigh.accepts(coiled.copy(structure = structure.copy(sma50 = null))))
        assertNull(coiled.copy(structure = structure.copy(high10 = null)).baseRangePct)
    }

    @Test
    fun rsiAndMacdFiltersReadTheIndicators() {
        val structure = PriceStructure(
            closes = listOf(98.0, 100.0),
            high20 = 101.0,
            low10 = 95.0,
            atr14 = 2.0,
            sma20 = 97.0,
            sma50 = 94.0,
            rsi14 = 62.0,
            macd = 1.5,
            macdSignal = 1.0,
            macdCrossAge = 2,
        )
        val rising = ScanResult("RISING", 100.0, 70.0, 1.5, 3.0, structure = structure)
        assertEquals(62.0, Metric.RSI.read(rising)!!, 1e-9)
        assertEquals(0.5, Metric.MACD.read(rising)!!, 1e-9)
        assertEquals(2.0, Metric.MACD_CROSS.read(rising)!!, 1e-9)

        val freshCross = ScanFilters()
            .with(Metric.RSI, Bound(min = 50.0, max = 70.0))
            .with(Metric.MACD, Bound(min = 0.0))
            .with(Metric.MACD_CROSS, Bound(max = 3.0))
        assertTrue(freshCross.accepts(rising))
        assertFalse(freshCross.accepts(rising.copy(structure = structure.copy(rsi14 = 75.0))))
        assertFalse(freshCross.accepts(rising.copy(structure = structure.copy(macdCrossAge = 8))))
        // MACD under its signal: no crossover to date, so a crossover limit leaves it out.
        val falling = rising.copy(structure = structure.copy(macd = 0.5, macdSignal = 1.0, macdCrossAge = null))
        assertEquals(-0.5, Metric.MACD.read(falling)!!, 1e-9)
        assertNull(Metric.MACD_CROSS.read(falling))
        assertFalse(freshCross.accepts(falling))
        // A scan saved before these were recorded has none of them.
        assertNull(Metric.RSI.read(rising.copy(structure = structure.copy(rsi14 = null))))
    }

    @Test
    fun theRangeFilterSpansTheChosenWeeks() {
        val structure = PriceStructure(
            closes = listOf(99.0, 100.0),
            high20 = 104.0,
            low10 = 97.0,
            atr14 = 1.0,
            sma20 = 100.0,
            sma50 = 98.0,
            high10 = 101.0,
            weekRanges = listOf(2.0, 4.0, 7.0, 12.0),
        )
        val calm = ScanResult("CALM", 100.0, 60.0, 1.0, 0.5, structure = structure)
        val withinFive = ScanFilters().with(Metric.BASE_RANGE, Bound(max = 5.0))
        assertEquals(BreakoutSignals.BASE_WEEKS, withinFive.rangeWeeks)
        assertTrue(withinFive.accepts(calm))
        assertTrue(withinFive.copy(rangeWeeks = 1).accepts(calm))
        assertFalse(withinFive.copy(rangeWeeks = 3).accepts(calm))
        assertEquals(12.0, Metric.BASE_RANGE.read(calm, rangeWeeks = 4)!!, 0.0)
        // A scan saved before the weekly ranges knows only the two-week base.
        val older = calm.copy(structure = structure.copy(weekRanges = emptyList()))
        assertEquals(4.0 / 99.0 * 100.0, older.rangePct(2)!!, 1e-9)
        assertNull(older.rangePct(4))
        assertFalse(withinFive.copy(rangeWeeks = 4).accepts(older))
        // Range stops step 1% up to 10%.
        for (percent in 1..10) assertTrue(percent.toDouble() in Metric.BASE_RANGE.stops)

        val threeWeeks = withinFive.copy(rangeWeeks = 3)
        assertEquals(threeWeeks, ScanFilters.decode(threeWeeks.encode()))
        assertFalse("weeks" in withinFive.encode())
        assertEquals(BreakoutSignals.BASE_WEEKS, ScanFilters.decode("limit=50;weeks=9").rangeWeeks)
    }

    @Test
    fun clearingABoundRemovesIt() {
        val filters = ScanFilters().with(Metric.RVOL, Bound(min = 2.0)).with(Metric.RVOL, Bound())
        assertEquals(0, filters.activeCount)
        assertTrue(filters.accepts(small))
    }

    @Test
    fun visibleRanksFilteredStocksAndAppliesTheLimit() {
        val state = ScanUiState(
            results = listOf(unknown, big, small),
            filters = ScanFilters(limit = 25).with(Metric.SCORE, Bound(min = 65.0)),
        )
        assertEquals(listOf("UNKNOWN" to 1, "BIG" to 2), state.visible.map { it.stock.symbol to it.rank })
        val limited = ScanUiState(results = listOf(unknown, big, small), filters = ScanFilters(limit = 0))
        assertEquals(3, limited.visible.size)
    }

    @Test
    fun filtersSurviveEncodeAndDecode() {
        val filters = ScanFilters(limit = 50)
            .with(Metric.MARKET_CAP, Bound(min = 500.0, max = 20_000.0))
            .with(Metric.CHANGE, Bound(min = -5.0))
            .with(Metric.FROM_HIGH, Bound(min = -2.0, max = 3.0))
            .with(Metric.BASE_RANGE, Bound(max = 8.0))
            .with(Metric.VS_SMA50, Bound(min = 0.0))
            .with(Metric.RSI, Bound(min = 50.0, max = 70.0))
            .with(Metric.MACD, Bound(min = 0.0))
            .with(Metric.MACD_CROSS, Bound(max = 3.0))
        assertEquals(filters, ScanFilters.decode(filters.encode()))
        assertEquals(Metric.entries.size, Metric.entries.map { it.key }.toSet().size)
        assertEquals(ScanFilters(), ScanFilters.decode(null))
        assertEquals(ScanFilters(), ScanFilters.decode("limit=7;bogus.min=1;price.max=abc"))
    }

    @Test
    fun sliderPositionsMapToStops() {
        val metric = Metric.RVOL
        assertEquals(0, metric.positionOf(null, upper = false))
        assertEquals(metric.lastPosition, metric.positionOf(null, upper = true))
        assertEquals(4, metric.positionOf(2.0, upper = false))
        assertEquals(2.0, metric.valueAt(4)!!, 0.0)
        assertNull(metric.valueAt(0))
        assertNull(metric.valueAt(metric.lastPosition))
        // Negative stops work too: 2% above the 20-day high sits at the third stop.
        assertEquals(3, Metric.FROM_HIGH.positionOf(-2.0, upper = false))
        // 5D change steps 1% at a time within ±10%, and keeps the wider stops older saved filters used.
        for (percent in -10..10) assertTrue(percent.toDouble() in Metric.CHANGE.stops)
        assertTrue(Metric.CHANGE.stops.containsAll(listOf(-20.0, -5.0, 5.0, 20.0, 50.0)))
        assertEquals(1.0, Metric.CHANGE.valueAt(Metric.CHANGE.positionOf(0.0, upper = false) + 1)!!, 0.0)
        for (each in Metric.entries) assertEquals(each.stops.sorted(), each.stops)
    }

    @Test
    fun securityFileKeepsLiveEquityRows() {
        val csv = """
            TckrSymb,SctyNm,SctySrs,IssdCptl,DelFlg
            BIG,"Big Industries, Ltd",EQ,20000000,N
            BIG,"Big Industries, Ltd",BE,1,N
            T2T,Trade For Trade Ltd,BE,5000000,N
            SMALL,Small Emerge Ltd,SM,1000000,N
            BOND,Big Bond 2030,N1,99999999,N
            GONE,Gone Ltd,EQ,30000000,Y
            BROKEN,Broken Ltd,EQ,not-a-number,N
        """.trimIndent()
        val shares = NseScanner.parseIssuedShares(ByteArrayInputStream(csv.toByteArray()))
        assertEquals(mapOf("BIG" to 20_000_000.0, "T2T" to 5_000_000.0, "SMALL" to 1_000_000.0), shares)
        val withByteOrderMark = Char(0xFEFF) + csv
        assertEquals(shares, NseScanner.parseIssuedShares(ByteArrayInputStream(withByteOrderMark.toByteArray())))
    }

    @Test
    fun csvSplitHonoursQuotes() {
        assertEquals(listOf("a", "b, c", "d\"e", ""), NseScanner.splitCsv("a,\"b, c\",\"d\"\"e\","))
    }
}
