package com.rohit.share_scan

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZonedDateTime

/** [MarketClock], [VolumeCurve] and how [BreakoutSignals] treats a session that is still trading. */
class LiveSessionTest {
    private fun ist(day: String, time: String): ZonedDateTime =
        ZonedDateTime.of(LocalDate.parse(day), java.time.LocalTime.parse(time), MarketClock.INDIA)

    @Test
    fun phasesFollowTheIndianSession() {
        // 19 Oct 2026 is a Monday.
        assertEquals(MarketClock.Phase.BEFORE_OPEN, MarketClock.phase(ist("2026-10-19", "09:14")))
        assertEquals(MarketClock.Phase.OPEN, MarketClock.phase(ist("2026-10-19", "09:15")))
        assertEquals(MarketClock.Phase.OPEN, MarketClock.phase(ist("2026-10-19", "15:29")))
        assertEquals(MarketClock.Phase.AFTER_CLOSE, MarketClock.phase(ist("2026-10-19", "15:30")))
        assertEquals(MarketClock.Phase.WEEKEND, MarketClock.phase(ist("2026-10-18", "11:00")))
        // A phone set to another zone still reads Indian time.
        val london = ZonedDateTime.of(LocalDate.parse("2026-10-19"), java.time.LocalTime.of(5, 0), java.time.ZoneId.of("Europe/London"))
        assertEquals(MarketClock.Phase.OPEN, MarketClock.phase(london))
        assertEquals(60.0, MarketClock.minutesSinceOpen(ist("2026-10-19", "10:15")), 1e-9)
    }

    @Test
    fun volumeCurveRisesFromTheFloorToTheWholeDay() {
        val floor = VolumeCurve.shareAt(VolumeCurve.FLOOR_MINUTES)
        assertEquals(floor, VolumeCurve.shareAt(0.0), 1e-12)
        assertEquals(floor, VolumeCurve.shareAt(-30.0), 1e-12)
        assertEquals(1.0, VolumeCurve.shareAt(375.0), 1e-12)
        assertEquals(1.0, VolumeCurve.shareAt(500.0), 1e-12)
        var previous = 0.0
        for (minute in 15..375 step 5) {
            val share = VolumeCurve.shareAt(minute.toDouble())
            assertTrue("$minute: $share", share >= previous && share in 0.0..1.0)
            previous = share
        }
        // The morning is busier than a straight line from the open would say.
        assertTrue(VolumeCurve.shareAt(60.0) > 60.0 / 375.0)
    }

    /** [sessions] dated weekdays of steady volume, the last of them [lastVolume]; the latest is on [lastDay]. */
    private fun candles(sessions: Int, lastVolume: Double, lastDay: Long): BreakoutSignals.Signals? {
        val closes = List(sessions) { 100.0 + it * 0.2 }
        val volumes = List(sessions) { if (it == sessions - 1) lastVolume else 1_000_000.0 }
        val days = List(sessions) { lastDay - (sessions - 1 - it) }
        return BreakoutSignals.analyse(
            closes = closes,
            highs = closes.map { it + 1.0 },
            lows = closes.map { it - 1.0 },
            volumes = volumes,
            days = days,
            liveDay = lastDay,
            liveShare = 0.25,
        )
    }

    @Test
    fun liveSessionVolumeIsScaledToAFullDay() {
        val today = LocalDate.parse("2026-10-19").toEpochDay()
        // A quarter of the day done and already half a normal day traded: on course for twice the usual volume.
        val live = candles(60, 500_000.0, today)!!
        assertTrue(live.live)
        assertEquals(2.0, live.rvol, 1e-9)
        // The volume shown is what really traded so far.
        assertEquals(500_000.0, live.volume, 1e-9)
    }

    @Test
    fun sessionOfAnotherDayIsNotLive() {
        val today = LocalDate.parse("2026-10-19").toEpochDay()
        val closes = List(30) { 100.0 }
        val signals = BreakoutSignals.analyse(
            closes = closes,
            highs = closes.map { it + 1.0 },
            lows = closes.map { it - 1.0 },
            volumes = List(30) { 1_000_000.0 },
            days = List(30) { today - 30 + it },
            liveDay = today,
            liveShare = 0.25,
        )!!
        assertFalse(signals.live)
        assertEquals(1.0, signals.rvol, 1e-9)
    }

    @Test
    fun pastScoresGoBackAsFarAsTheFiftyDayAverageAllows() {
        val today = LocalDate.parse("2026-10-19").toEpochDay()
        val signals = candles(60, 1_000_000.0, today)!!
        // 60 sessions leave 10 earlier closes with 50 sessions behind them.
        assertEquals(10, signals.pastScores.size)
        assertEquals(today - 1, signals.pastScores.first().day)
        assertEquals(today - 10, signals.pastScores.last().day)
        assertEquals(60, signals.structure.closeDays.size)
        assertEquals(today, signals.structure.closeDays.last())

        // Without dates there is nothing to date the past scores by.
        val closes = List(60) { 100.0 + it * 0.2 }
        val undated = BreakoutSignals.analyse(closes, closes.map { it + 1.0 }, closes.map { it - 1.0 }, List(60) { 1e6 })!!
        assertTrue(undated.pastScores.isEmpty())
        assertTrue(undated.structure.closeDays.isEmpty())
    }
}

class LiveChartSessionTest {
    private fun epoch(time: String) = ZonedDateTime.of(LocalDate.parse("2026-10-19"), java.time.LocalTime.parse(time), MarketClock.INDIA)
        .toEpochSecond()

    @Test
    fun liveOnlyWhileTodaysSessionRuns() {
        val start = epoch("09:15")
        val end = epoch("15:30")
        val (day, share) = NseScanner.liveSession(start, end, epoch("10:15") * 1000)!!
        assertEquals(LocalDate.parse("2026-10-19").toEpochDay(), day)
        assertEquals(VolumeCurve.shareAt(60.0), share, 1e-12)
        assertEquals(null, NseScanner.liveSession(start, end, epoch("09:00") * 1000))
        assertEquals(null, NseScanner.liveSession(start, end, epoch("15:30") * 1000))
        // On a holiday the chart names the next session, which is not today's.
        assertEquals(null, NseScanner.liveSession(start + 86_400, end + 86_400, epoch("11:00") * 1000))
        assertEquals(null, NseScanner.liveSession(0, 0, epoch("11:00") * 1000))
    }
}

class SectorBookTest {
    private val json = """{"v":1,"updated":"2026-10-19","sectors":["Capital Goods","Healthcare","Power"],
        "totals":{"nse":5,"sme":2,"bse":1},
        "nse":{"INE117A01022":0,"INE089A01031":1,"INE000000001":0},"sme":{"INE000000002":2},"bse":{"INE000000003":1}}"""

    @Test
    fun readsIndustriesByIsinAndCountsThemPerExchange() {
        val book = SectorBook.parse(json)
        assertEquals(5, book.size)
        assertEquals("2026-10-19", book.updated)
        assertEquals("Capital Goods", book.of("INE117A01022"))
        assertEquals("Healthcare", book.of("ine089a01031"))
        assertEquals("Power", book.of("INE000000002"))
        assertEquals("", book.of("INE999999999"))
        // Two NSE stocks of five have no industry: they count as OTHER, which comes last.
        assertEquals(listOf("Capital Goods" to 2, "Healthcare" to 1, SectorBook.OTHER to 2), book.counts(Market.NSE))
        assertEquals(listOf("Power" to 1, SectorBook.OTHER to 1), book.counts(Market.NSE_SME))
        assertEquals(0, SectorBook.parse("<html>").size)
        assertEquals(0, SectorBook.parse(null).size)
    }

    @Test
    fun scanSectorsPickIndustriesPerExchange() {
        val all = ScanSectors()
        assertTrue(all.includes(Market.BSE, "Power"))
        assertTrue(all.includes(Market.BSE, ""))
        val picked = all.with(Market.NSE, setOf("Power", SectorBook.OTHER)).with(Market.BSE, emptySet())
        assertTrue(picked.includes(Market.NSE, "Power"))
        assertTrue(picked.includes(Market.NSE, ""))
        assertFalse(picked.includes(Market.NSE, "Healthcare"))
        assertFalse(picked.includes(Market.BSE, "Power"))
        assertTrue(picked.includes(Market.NSE_SME, "Healthcare"))
        assertTrue(picked.with(Market.NSE, null).all(Market.NSE))
        assertEquals(setOf("Power", SectorBook.OTHER), ScanSectors.decode(ScanSectors.encode(setOf("Power", SectorBook.OTHER))))
        assertEquals(emptySet<String>(), ScanSectors.decode(ScanSectors.encode(emptySet())))
    }

    private fun stock(symbol: String, sector: String, score: Double, change: Double) =
        ScanResult(symbol = symbol, price = 100.0, score = score, rvol = 1.0, change5d = change, sector = sector)

    @Test
    fun ranksIndustriesWithEnoughStocksByMeanScore() {
        val rows = listOf(
            stock("A1", "Power", 80.0, 4.0), stock("A2", "Power", 70.0, 2.0), stock("A3", "Power", 60.0, 0.0),
            stock("B1", "Realty", 90.0, 1.0), stock("B2", "Realty", 50.0, -1.0), stock("B3", "Realty", 40.0, -3.0),
            stock("C1", "Textiles", 99.0, 9.0), stock("C2", "Textiles", 98.0, 9.0),
            stock("D1", "", 99.0, 9.0),
        )
        val ranked = SectorStrength.rank(rows)
        assertEquals(listOf("Power", "Realty"), ranked.map { it.sector })
        val power = ranked.first()
        assertEquals(3, power.stocks)
        assertEquals(70.0, power.averageScore, 1e-9)
        assertEquals(2.0, power.averageChange, 1e-9)
        assertEquals(1, power.strong)
        assertEquals(null, power.aboveSma50)
    }
}

class SectorFilterTest {
    private fun stock(symbol: String, sector: String) =
        ScanResult(symbol = symbol, price = 100.0, score = 70.0, rvol = 1.0, change5d = 1.0, sector = sector)

    @Test
    fun keepsOnlyThePickedIndustries() {
        val filters = ScanFilters(sectors = setOf("Power", "Metals & Mining"))
        assertTrue(filters.accepts(stock("A", "Power")))
        assertTrue(filters.accepts(stock("B", "Metals & Mining")))
        assertFalse(filters.accepts(stock("C", "Realty")))
        assertFalse(filters.accepts(stock("D", "")))
        assertTrue(ScanFilters().accepts(stock("D", "")))
        assertEquals(1, filters.activeCount)
        val withPrice = filters.copy(bounds = mapOf(Metric.PRICE to Bound(min = 50.0)))
        assertEquals(withPrice, ScanFilters.decode(withPrice.encode()))
    }
}
