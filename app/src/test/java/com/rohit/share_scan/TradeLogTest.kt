package com.rohit.share_scan

import com.rohit.share_scan.OrderTicket.Side
import com.rohit.share_scan.PositionSize.Product
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime

class TradeLogTest {
    private val day = LocalDate.of(2026, 11, 4)

    private fun trade(id: Long, side: Side = Side.BUY, product: Product = Product.MIS, exit: Double? = null, on: LocalDate = day) =
        LoggedTrade(id, on, "X$id", side, product, qty = 10, entry = 100.0, stop = 98.0, target = 104.0, exit = exit)

    @Test
    fun profitAndLossFollowTheSide() {
        assertEquals(30.0, trade(1, exit = 103.0).realized!!, 1e-9)
        assertEquals(-30.0, trade(2, side = Side.SELL, exit = 103.0).realized!!, 1e-9)
        assertEquals(-20.0, trade(3).pnl(98.0), 1e-9)
    }

    @Test
    fun theLogKeepsNinetyDaysAndReadsBackWhatItWrote() {
        val old = trade(1, on = day.minusDays(91))
        val trades = TradeLog.add(listOf(old, trade(2, exit = 101.0)), trade(3, product = Product.CNC), day)
        assertEquals(listOf(3L, 2L), trades.map { it.id })
        assertEquals(trades, TradeLog.decode(TradeLog.encode(trades)))
        assertTrue(TradeLog.decode("not json").isEmpty())
        val closed = TradeLog.close(trades, 3L, 105.0, 99L)
        assertEquals(105.0, closed.first().exit!!, 0.0)
        assertEquals(listOf(2L), TradeLog.remove(trades, 3L).map { it.id })
        assertEquals(emptyList<LoggedTrade>(), TradeLog.openIntraday(closed, day))
    }

    @Test
    fun theGuardStopsTheFourthTradeTheDaysLossAndLateIntradayEntries() {
        val capital = 100_000.0
        val noon = LocalTime.of(12, 0)
        assertTrue(RiskGuard.check(emptyList(), day, noon, capital, Product.MIS).isEmpty())
        val three = listOf(trade(1), trade(2), trade(3))
        assertEquals(listOf(RiskGuard.Breach.TRADES), RiskGuard.check(three, day, noon, capital, Product.MIS))
        // Two losers that closed ₹2,000 down: 2% of the capital.
        val losers = listOf(trade(1).copy(qty = 1000, exit = 99.0), trade(2).copy(qty = 1000, exit = 99.0))
        assertEquals(listOf(RiskGuard.Breach.LOSS), RiskGuard.check(losers, day, noon, capital, Product.CNC))
        assertEquals(0.0, RiskGuard.lossLeft(losers, day, capital), 1e-9)
        assertEquals(listOf(RiskGuard.Breach.LATE), RiskGuard.check(emptyList(), day, LocalTime.of(14, 45), capital, Product.MIS))
        assertTrue(RiskGuard.check(emptyList(), day, LocalTime.of(14, 45), capital, Product.CNC).isEmpty())
        // Yesterday's trades do not count today.
        assertTrue(RiskGuard.check(three.map { it.copy(day = day.minusDays(1)) }, day, noon, capital, Product.MIS).isEmpty())
    }
}
