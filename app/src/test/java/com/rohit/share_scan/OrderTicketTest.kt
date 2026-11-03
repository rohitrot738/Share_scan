package com.rohit.share_scan

import com.rohit.share_scan.OrderTicket.Kind
import com.rohit.share_scan.OrderTicket.Side
import org.junit.Assert.assertEquals
import org.junit.Test

class OrderTicketTest {
    @Test
    fun aBreakoutBuyWaitsAsAStopLimitThenOneGttForDelivery() {
        val orders = OrderTicket.orders(Side.BUY, PositionSize.Product.CNC, waitsForBreakout = true, entry = 1306.6, stop = 1284.3, target = 1350.0, qty = 25)
        assertEquals(listOf(Kind.SL_LIMIT, Kind.GTT_OCO), orders.map { it.kind })
        val entry = orders[0]
        assertEquals(Side.BUY, entry.side)
        assertEquals(1306.6, entry.trigger!!, 1e-9)
        assertEquals(1313.15, entry.price!!, 1e-9) // 0.5% above, on the 5-paisa tick
        val gtt = orders[1]
        assertEquals(Side.SELL, gtt.side)
        assertEquals(1284.3, gtt.trigger!!, 1e-9)
        assertEquals(1350.0, gtt.target!!, 1e-9)
        assertEquals(25, gtt.qty)
    }

    @Test
    fun anIntradaySellIsALimitThenAStopMarketAndATargetLimit() {
        val orders = OrderTicket.orders(Side.SELL, PositionSize.Product.MIS, waitsForBreakout = false, entry = 176.66, stop = 177.81, target = 174.94, qty = 100)
        assertEquals(listOf(Kind.LIMIT, Kind.SL_MARKET, Kind.LIMIT), orders.map { it.kind })
        assertEquals(176.65, orders[0].price!!, 1e-9)
        assertEquals(listOf(Side.SELL, Side.BUY, Side.BUY), orders.map { it.side })
        assertEquals(177.8, orders[1].trigger!!, 1e-9)
        assertEquals(174.95, orders[2].price!!, 1e-9)
    }
}
