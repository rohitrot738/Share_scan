package com.rohit.share_scan

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.time.LocalDate

class ScanStoreTest {
    private fun folder(): File = Files.createTempDirectory("scan-store").toFile()

    private val full = ScanResult(
        symbol = "M&M",
        price = 3_210.5,
        score = 81.25,
        rvol = 2.4,
        change5d = -1.5,
        volume = 1_234_567.0,
        marketCapCr = 399_000.0,
        structure = PriceStructure(
            closes = listOf(3_000.0, 3_100.0, 3_210.5),
            high20 = 3_250.0,
            low10 = 2_950.0,
            atr14 = 61.0,
            sma20 = 3_050.0,
            sma50 = null,
            high10 = 3_240.0,
            closeDays = listOf(20_740L, 20_741L, 20_742L),
            weekRanges = listOf(1.5, 2.25, 4.0, 6.75),
            rsi14 = 61.25,
            macd = 12.5,
            macdSignal = 10.0,
            macdCrossAge = 3,
        ),
        parts = listOf(ScorePart(ScoreKeys.BREAKOUT, 90.0, 0.35), ScorePart(ScoreKeys.VOLUME, Double.NaN, 0.2)),
        name = "Mahindra & Mahindra Ltd.",
        market = Market.NSE,
        sector = "Automobile and Auto Components",
        live = true,
        pastScores = listOf(DayScore(20_741L, 79.0), DayScore(20_740L, 70.5)),
        support = SupportFloor(level = 2_890.0, ageSessions = 118, touches = 4, unbroken = true, window = 124),
        setupMask = SetupCondition.BREAKOUT_TODAY.flag or SetupCondition.VOLUME_2X.flag,
        // Figures within the decimals the store keeps, so they come back exactly; no 50-day terms.
        factors = ScoreFactors(
            swingPct = 2.125, highGap = 0.4, proximity = 92.0, baseWidthPct = 3.5, narrow = 110.6, calm = 0.8125,
            calmed = 78.75, tightness = 94.675, position = 81.5, breakoutRaw = 89.1513, above20 = 1.25, slope20 = 0.5,
            above50 = null, stacked = null, trendRaw = 70.125, average20 = 512_345.0, quiet = 0.6125, expansion = 81.5,
            dryUp = 82.6923, volumeRaw = 81.9769, medianTurnoverCr = 164.75, liquidityRaw = 107.3887, change20 = 6.25,
            momentumRaw = 53.375, highs = listOf(3_220.55, 3_240.0), lows = listOf(3_150.1, 3_190.25),
            volumes = listOf(1_000_000.0, 1_234_567.0),
        ),
    )

    private val bare = ScanResult(symbol = "123ABC", price = 10.0, score = 40.0, rvol = 0.5, change5d = 2.0, market = Market.BSE, bseCode = "543210")

    @Test
    fun keepsEveryFieldOfAScan() {
        val store = ScanStore(File(folder(), "last_scan.gz"))
        val saved = SavedScan(
            output = ScanOutput(
                results = listOf(full, bare),
                marketCapDate = LocalDate.parse("2026-10-16"),
                stopped = false,
                benchmark = DailySeries(listOf(20_741L, 20_742L), listOf(23_000.0, 23_146.15)),
            ),
            finishedAt = 1_792_377_000_000L,
            auto = true,
            badges = mapOf("M&M" to RankBadge.New, "123ABC" to RankBadge.Moved(-4)),
            scope = "NSE;BSE=Capital Goods|-",
            watchExtras = listOf(bare.copy(symbol = "STARRED", sector = "Healthcare")),
        )
        store.save(saved)
        val back = store.load()!!

        assertEquals(saved.finishedAt, back.finishedAt)
        assertTrue(back.auto)
        assertEquals(saved.badges, back.badges)
        assertEquals(saved.scope, back.scope)
        assertEquals(saved.output.marketCapDate, back.output.marketCapDate)
        assertEquals(saved.output.benchmark, back.output.benchmark)
        assertEquals(bare, back.output.results[1])
        // Starred stocks outside the scan come back apart from its own rows.
        assertEquals(2, back.output.results.size)
        assertEquals(saved.watchExtras, back.watchExtras)
        // NaN has no JSON form; it comes back as NaN all the same.
        val first = back.output.results[0]
        assertTrue(first.parts[1].value.isNaN())
        assertEquals(full.copy(parts = emptyList()), first.copy(parts = emptyList()))
        assertEquals(full.parts[0], first.parts[0])
        assertTrue(back.output.live)
    }

    @Test
    fun missingOrDamagedFileReadsAsNoScan() {
        val dir = folder()
        assertNull(ScanStore(File(dir, "none.gz")).load())
        val damaged = File(dir, "damaged.gz").apply { writeText("not gzip") }
        assertNull(ScanStore(damaged).load())
    }

    @Test
    fun aLongListKeepsItsOrderAndOneRowIsFoundAlone() {
        val store = ScanStore(File(folder(), "last_scan.gz"))
        val rows = List(700) { bare.copy(symbol = "S$it", score = 700.0 - it) } + full
        store.save(SavedScan(ScanOutput(rows, null), 1L, watchExtras = listOf(bare.copy(symbol = "STARRED"))))
        val back = store.load()!!
        assertEquals(rows.map { it.symbol }, back.output.results.map { it.symbol })
        assertEquals(listOf("STARRED"), back.watchExtras.map { it.symbol })
        // One stock, read on its own: "S7" is not "S70", and a starred extra is found too.
        assertEquals("S7", store.find("S7")!!.symbol)
        assertEquals(full.structure, store.find("M&M")!!.structure)
        assertEquals("STARRED", store.find("STARRED")!!.symbol)
        assertNull(store.find("S"))
        assertNull(ScanStore(File(folder(), "none.gz")).find("S7"))
    }

    @Test
    fun aNewSaveReplacesTheOldOne() {
        val store = ScanStore(File(folder(), "last_scan.gz"))
        store.save(SavedScan(ScanOutput(listOf(full), null), 1L))
        store.save(SavedScan(ScanOutput(listOf(bare), null, stopped = true), 2L))
        val back = store.load()!!
        assertEquals(listOf("123ABC"), back.output.results.map { it.symbol })
        assertTrue(back.output.stopped)
        store.clear()
        assertNull(store.load())
    }
}
