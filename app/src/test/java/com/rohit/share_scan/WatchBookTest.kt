package com.rohit.share_scan

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class WatchBookTest {
    private fun stock(symbol: String, price: Double, closes: List<Double> = emptyList()) = ScanResult(
        symbol,
        price,
        score = 70.0,
        rvol = 1.0,
        change5d = 0.0,
        structure = PriceStructure(closes = closes.ifEmpty { listOf(price) }, high20 = 110.0, low10 = 90.0, atr14 = 2.0, sma20 = 100.0, sma50 = 95.0),
    )

    @Test
    fun returnSinceAddedIsMeasuredFromTheAddedPrice() {
        assertEquals(10.0, WatchEntry(100.0, 0L).returnPct(110.0)!!, 1e-9)
        assertEquals(-5.0, WatchEntry(200.0, 0L).returnPct(190.0)!!, 1e-9)
        assertNull(WatchEntry(100.0, 0L).returnPct(0.0))
        // A starred stock with no chart yet has no return.
        assertNull(WatchRow(ScanResult("X", 0.0, 0.0, 0.0, 0.0), WatchEntry(100.0, 0L), null, null).returnPct)
    }

    @Test
    fun entriesRoundTripAndBadOnesAreDropped() {
        val entries = mapOf("TCS" to WatchEntry(3_900.5, 1_700_000_000_000L), "M&M" to WatchEntry(2_900.0, 5L, estimated = true))
        assertEquals(entries, WatchBook.decode(WatchBook.encode(entries)))
        assertEquals(emptyMap<String, WatchEntry>(), WatchBook.decode(null))
        assertEquals(emptyMap<String, WatchEntry>(), WatchBook.decode("not json"))
        assertEquals(setOf("A"), WatchBook.decode("""{"A":{"p":10,"at":1},"B":{"p":0,"at":1},"C":3}""").keys)
    }

    @Test
    fun backfillFillsMissingStocksAndDropsRemovedOnes() {
        val kept = mapOf("TCS" to WatchEntry(3_900.0, 1L))
        // Nothing missing: the very same map.
        assertSame(kept, WatchBook.backfill(kept, setOf("TCS"), mapOf("TCS" to 4_000.0), now = 9L))
        val filled = WatchBook.backfill(kept, setOf("TCS", "INFY", "NEW"), mapOf("INFY" to 1_500.0, "TCS" to 4_000.0), now = 9L)
        assertEquals(WatchEntry(3_900.0, 1L), filled["TCS"])
        assertEquals(WatchEntry(1_500.0, 9L, estimated = true), filled["INFY"])
        // No price for it yet: it waits for one.
        assertTrue("NEW" !in filled)
        assertEquals(emptyMap<String, WatchEntry>(), WatchBook.backfill(kept, emptySet(), emptyMap(), now = 9L))
    }

    @Test
    fun levelsFollowTheAlertStateElseThePlan() {
        val waiting = WatchAlerts.State(WatchAlerts.Phase.WAITING, 100.0, entry = 110.2, stop = 95.0, targets = listOf(120.0, 130.0, 140.0))
        // Waiting: from the base's low (the 10-session low, 90) up to the trigger.
        assertEquals(WatchLevels(90.0, 110.2, 0), WatchBook.levels(stock("A", 100.0), waiting))
        val trade = waiting.copy(phase = WatchAlerts.Phase.IN_TRADE, hits = 1)
        assertEquals(WatchLevels(95.0, 130.0, 2), WatchBook.levels(stock("A", 125.0), trade))
        // No state yet: the plan's trigger, since the price is under it.
        val fromPlan = WatchBook.levels(stock("A", 100.0), null)!!
        assertEquals(0, fromPlan.target)
        assertTrue(fromPlan.next > 110.0 && fromPlan.floor == 90.0)
        // Already above the trigger: the plan's first target.
        val above = WatchBook.levels(stock("A", 115.0), null)!!
        assertEquals(1, above.target)
        assertTrue(above.floorIsStop && above.floor < 115.0 && above.next > 115.0)
        assertNull(WatchBook.levels(ScanResult("X", 0.0, 0.0, 0.0, 0.0), null))
    }

    @Test
    fun positionAndDistanceStayInRange() {
        val levels = WatchLevels(floor = 90.0, next = 110.0, target = 0)
        assertEquals(0.5f, levels.position(100.0), 1e-6f)
        assertEquals(0f, levels.position(80.0), 1e-6f)
        assertEquals(1f, levels.position(120.0), 1e-6f)
        assertEquals(10.0, levels.distancePct(100.0), 1e-9)
        assertEquals(0.0, levels.distancePct(120.0), 1e-9)
    }

    @Test
    fun rowsSortByReturnRecencyOrNearnessWithUnchartedLast() {
        val stocks = listOf(stock("UP", 120.0), stock("DOWN", 90.0), stock("FLAT", 100.0), ScanResult("NONE", 0.0, 0.0, 0.0, 0.0))
        val entries = mapOf(
            "UP" to WatchEntry(100.0, at = 1L),
            "DOWN" to WatchEntry(100.0, at = 3L),
            "FLAT" to WatchEntry(100.0, at = 2L),
        )
        fun order(sort: WatchSort) = WatchBook.rows(stocks, entries, emptyMap(), sort).map { it.stock.symbol }
        assertEquals(listOf("UP", "FLAT", "DOWN", "NONE"), order(WatchSort.RETURN))
        assertEquals(listOf("DOWN", "FLAT", "UP", "NONE"), order(WatchSort.RECENT))
        val nearest = order(WatchSort.NEAREST)
        assertEquals("NONE", nearest.last())
    }

    @Test
    fun summaryAveragesTheBasketAndCountsAlerts() {
        val rows = listOf(
            WatchRow(stock("A", 110.0), WatchEntry(100.0, 0L), WatchAlerts.State(entry = 112.0, stop = 95.0), null),
            WatchRow(stock("B", 95.0), WatchEntry(100.0, 0L), WatchAlerts.State(), null),
            WatchRow(stock("C", 50.0), null, null, null),
        )
        val summary = WatchBook.summary(rows)
        assertEquals(2.5, summary.averageReturn!!, 1e-9)
        assertEquals(1, summary.gaining)
        assertEquals(1, summary.losing)
        assertEquals(1, summary.activeAlerts)
        assertNull(WatchBook.summary(emptyList()).averageReturn)
    }

    @Test
    fun curveAveragesEachStocksPathFromItsFirstClose() {
        val a = stock("A", 110.0, closes = listOf(50.0, 100.0, 110.0))
        val b = stock("B", 90.0, closes = listOf(100.0, 90.0))
        // Over the last two sessions: A goes 100 → 110 (+10%), B 100 → 90 (−10%).
        WatchBook.curve(listOf(a, b), sessions = 2).forEach { assertEquals(0.0, it, 1e-9) }
        // Over three only A has them all.
        assertEquals(listOf(0.0, 100.0, 120.0), WatchBook.curve(listOf(a, b), sessions = 3).map { Math.round(it).toDouble() })
        assertTrue(WatchBook.curve(listOf(b), sessions = 3).isEmpty())
    }
}
