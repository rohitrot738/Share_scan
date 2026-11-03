package com.rohit.share_scan

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate

class IntradayPicksTest {
    private fun stock(symbol: String, price: Double, volume: Double, market: Market = Market.NSE) =
        ScanResult(symbol = symbol, price = price, score = 50.0, rvol = 1.0, change5d = 0.0, volume = volume, market = market)

    private fun plan(side: IntradayPlan.Side, price: Double, late: Boolean = false, outcome: IntradayPlan.Outcome = IntradayPlan.Outcome.OPEN) =
        IntradayPlan(
            day = LocalDate.of(2026, 11, 3), rangeHigh = 103.0, rangeLow = 99.0, vwap = 101.0, price = price, asOf = 0L,
            side = side, entry = if (side == IntradayPlan.Side.BUY) 103.5 else 98.5, stop = 101.0, targets = listOf(1.0, 2.0),
            triggeredAt = 1L, late = late, outcome = outcome,
        )

    @Test
    fun theUniverseIsTheMostTradedLiquidNseStocks() {
        val results = listOf(
            stock("BIG", 1000.0, 1_000_000.0), // ₹100 crore traded
            stock("MID", 500.0, 200_000.0), // ₹10 crore
            stock("THIN", 500.0, 50_000.0), // ₹2.5 crore: too thin
            stock("PENNY", 20.0, 50_000_000.0), // under ₹50
            stock("SMEX", 1000.0, 1_000_000.0, Market.NSE_SME),
        )
        assertEquals(listOf("BIG", "MID"), IntradayPicks.universe(results).map { it.symbol })
        assertEquals(listOf("BIG"), IntradayPicks.universe(results, limit = 1).map { it.symbol })
    }

    @Test
    fun buysAndSellsAreSortedByStrengthAndFinishedCallsSetApart() {
        val s = stock("X", 100.0, 1.0)
        val picks = IntradayPicks.of(
            listOf(
                IntradayPicks.Pick(s, plan(IntradayPlan.Side.BUY, 104.0)),
                IntradayPicks.Pick(s, plan(IntradayPlan.Side.BUY, 106.0)),
                IntradayPicks.Pick(s, plan(IntradayPlan.Side.SELL, 97.0)),
                IntradayPicks.Pick(s, plan(IntradayPlan.Side.BUY, 105.0, late = true)),
                IntradayPicks.Pick(s, plan(IntradayPlan.Side.SELL, 102.0, outcome = IntradayPlan.Outcome.STOPPED)),
                IntradayPicks.Pick(s, plan(IntradayPlan.Side.WAIT, 101.0)),
            ),
        )
        assertEquals(listOf(106.0, 104.0), picks.buys.map { it.plan.price })
        assertEquals(listOf(97.0), picks.sells.map { it.plan.price })
        assertEquals(2, picks.done.size)
        assertEquals(1, picks.waiting)
    }
}
