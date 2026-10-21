package com.rohit.share_scan

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** [LiveAlerts], [WatchAlerts] and [PickJournal]. */
class AlertsTest {
    /** A base under a 20-day high of 100 with a 2-rupee ATR: trigger 100.2, stop 98 or lower, targets above. */
    private fun structure(closes: List<Double> = listOf(95.0, 96.0, 97.0), days: List<Long> = emptyList()) = PriceStructure(
        closes = closes,
        high20 = 100.0,
        low10 = 97.0,
        atr14 = 2.0,
        sma20 = 96.0,
        sma50 = 90.0,
        closeDays = days,
    )

    private fun stock(symbol: String, price: Double, score: Double = 80.0, structure: PriceStructure? = structure()) =
        ScanResult(symbol = symbol, price = price, score = score, rvol = 1.0, change5d = 1.0, structure = structure)

    @Test
    fun liveAlertsFindNewStrongStocksAndBreakouts() {
        val before = listOf(stock("UP", 99.0, score = 70.0), stock("STAY", 99.0, score = 90.0), stock("BREAK", 99.5, score = 70.0))
        val now = listOf(stock("UP", 99.0, score = 80.0), stock("STAY", 99.0, score = 91.0), stock("BREAK", 101.0, score = 70.0), stock("NEW", 99.0, score = 85.0))
        val finds = LiveAlerts.fresh(before, now, ScanFilters(limit = 0)).associateBy { it.symbol }

        assertEquals(setOf("UP", "BREAK", "NEW"), finds.keys)
        assertTrue(finds.getValue("UP").newStrong)
        assertTrue(finds.getValue("NEW").newStrong)
        assertTrue(finds.getValue("BREAK").breakout)
        assertEquals(1.0, finds.getValue("BREAK").rvol, 1e-9)
        // Nothing to compare with on the very first scan.
        assertTrue(LiveAlerts.fresh(emptyList(), now, ScanFilters()).isEmpty())
        // Stocks outside the filters do not count.
        val cheapOnly = ScanFilters(bounds = mapOf(Metric.PRICE to Bound(max = 50.0)))
        assertTrue(LiveAlerts.fresh(before, now, cheapOnly).isEmpty())
    }

    @Test
    fun watchAlertsFollowABreakoutToItsTargets() {
        val start = stock("W", 98.0)
        val (waiting, none) = WatchAlerts.step(null, start, 1L)
        assertNull(none)
        assertEquals(WatchAlerts.Phase.WAITING, waiting.phase)
        val trigger = waiting.entry!!
        assertEquals(100.2, trigger, 1e-9)

        val (trade, breakout) = WatchAlerts.step(waiting, stock("W", 101.0), 2L)
        breakout as WatchAlerts.Event.Breakout
        assertEquals(trigger, breakout.entry, 1e-9)
        assertEquals(1.0, breakout.rvol, 1e-9)
        assertEquals(WatchAlerts.Phase.IN_TRADE, trade.phase)
        assertEquals(2L, trade.since)

        // The fixed plan's first two targets in one jump: one alert naming the higher.
        val (after, target) = WatchAlerts.step(trade, stock("W", trade.targets[1] + 0.1), 3L)
        target as WatchAlerts.Event.Target
        assertEquals(2, target.number)
        assertEquals(2, after.hits)
        // Already counted: no second alert at the same level.
        assertNull(WatchAlerts.step(after, stock("W", trade.targets[1] + 0.2), 4L).second)

        val (ended, last) = WatchAlerts.step(after, stock("W", trade.targets[2] + 1.0), 5L)
        assertEquals(3, (last as WatchAlerts.Event.Target).number)
        assertEquals(WatchAlerts.Phase.WAITING, ended.phase)
    }

    @Test
    fun watchAlertsWarnOnceTheStopBreaks() {
        val (waiting, _) = WatchAlerts.step(null, stock("W", 98.0), 1L)
        val (trade, _) = WatchAlerts.step(waiting, stock("W", 100.5), 2L)
        val (after, event) = WatchAlerts.step(trade, stock("W", trade.stop!! - 0.5), 3L)
        assertTrue(event is WatchAlerts.Event.Stopped)
        assertEquals(WatchAlerts.Phase.WAITING, after.phase)
    }

    @Test
    fun aStockFirstSeenAboveItsTriggerRaisesNothing() {
        val (first, none) = WatchAlerts.step(null, stock("W", 105.0), 1L)
        assertNull(none)
        assertNull(first.entry)
        assertNull(WatchAlerts.step(first, stock("W", 106.0), 2L).second)
    }

    @Test
    fun watchStatesSurviveTheirText() {
        val states = mapOf(
            "A" to WatchAlerts.State(WatchAlerts.Phase.IN_TRADE, 101.0, 100.2, 98.0, listOf(103.5, 105.7, 109.0), 1, 7L),
            "B&C" to WatchAlerts.State(),
        )
        assertEquals(states, WatchAlerts.decode(WatchAlerts.encode(states)))
        assertTrue(WatchAlerts.decode("garbage").isEmpty())
    }

    @Test
    fun journalPicksEachSessionAndMeasuresItLater() {
        val days = (100L..111L).toList()
        // A stock rising 1% a day; STRONG at the close of day 100.
        val closes = days.indices.map { 100.0 * Math.pow(1.01, it.toDouble()) }
        val riser = stock("RISE", closes.last(), score = 70.0, structure = structure(closes, days))
            .copy(pastScores = listOf(DayScore(100L, 82.0), DayScore(101L, 60.0)))
        val faller = stock("FALL", 50.0, score = 60.0, structure = structure(List(12) { 60.0 - it }, days))
            .copy(pastScores = listOf(DayScore(100L, 78.0)))
        val nifty = DailySeries(days, days.indices.map { 1_000.0 + it * 10.0 })

        val journal = PickJournal().update(ScanOutput(listOf(riser, faller), null, benchmark = nifty))
        val day = journal.days.single { it.day == 100L }
        assertEquals(listOf("RISE", "FALL"), day.picks.map { it.symbol })
        val rise = day.picks[0]
        assertEquals(1.0, rise.returns[0]!!, 1e-9)
        assertEquals(Math.pow(1.01, 5.0) * 100.0 - 100.0, rise.returns[1]!!, 1e-9)
        assertEquals(Math.pow(1.01, 10.0) * 100.0 - 100.0, rise.returns[2]!!, 1e-9)
        assertEquals(1.0, day.nifty[0]!!, 1e-9)
        // Day 101 scored 60 for RISE and nothing for FALL: no STRONG pick that day.
        assertTrue(journal.days.none { it.day == 101L })

        val five = journal.summary(1)!!
        assertEquals(2, five.picks)
        assertEquals(0.5, five.winRate, 1e-9)
        assertEquals(5.0, five.nifty!!, 1e-9)

        // A stopped scan changes nothing; the record survives its text.
        assertEquals(journal, journal.update(ScanOutput(listOf(riser), null, stopped = true)))
        val back = PickJournal.decode(journal.encode())
        assertEquals(journal.days, back.days)
    }

    @Test
    fun aLiveSessionIsNeitherPickedNorMeasured() {
        val days = (200L..206L).toList()
        val closes = listOf(100.0, 101.0, 102.0, 103.0, 104.0, 105.0, 150.0)
        val live = stock("LIVE", 150.0, score = 90.0, structure = structure(closes, days))
            .copy(live = true, pastScores = listOf(DayScore(205L, 80.0)))
        val journal = PickJournal().update(ScanOutput(listOf(live), null))
        // Today (206) is still trading: not a pick day, and its price is not a close yet.
        assertEquals(listOf(205L), journal.days.map { it.day })
        assertNull(journal.days.single().picks.single().returns[0])
    }
}
