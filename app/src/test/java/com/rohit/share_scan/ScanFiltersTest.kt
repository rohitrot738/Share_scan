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
        assertEquals(filters, ScanFilters.decode(filters.encode()))
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
    }

    @Test
    fun securityFileKeepsLiveEquityRows() {
        val csv = """
            TckrSymb,SctyNm,SctySrs,IssdCptl,DelFlg
            BIG,"Big Industries, Ltd",EQ,20000000,N
            BIG2,Big Bond,BE,99999999,N
            GONE,Gone Ltd,EQ,30000000,Y
            BROKEN,Broken Ltd,EQ,not-a-number,N
        """.trimIndent()
        val shares = NseScanner.parseIssuedShares(ByteArrayInputStream(csv.toByteArray()))
        assertEquals(mapOf("BIG" to 20_000_000.0), shares)
    }

    @Test
    fun csvSplitHonoursQuotes() {
        assertEquals(listOf("a", "b, c", "d\"e", ""), NseScanner.splitCsv("a,\"b, c\",\"d\"\"e\","))
    }
}
