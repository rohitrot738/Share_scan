package com.rohit.share_scan

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PositionSizeTest {
    @Test
    fun theRiskSetsTheQuantity() {
        // ₹5 lakh, 1% risk = ₹5,000; entry 100, stop 95 = ₹5 a share → 1,000 shares, but CNC caps at 25%: 1,250 shares.
        val r = PositionSize.of(500_000.0, 1.0, 100.0, 95.0, listOf(110.0, 115.0), PositionSize.Product.CNC)!!
        assertEquals(1000, r.qty)
        assertEquals(PositionSize.Limit.RISK, r.limit)
        assertEquals(5_000.0, r.loss, 1e-9)
        assertEquals(100_000.0, r.cost, 1e-9)
        assertEquals(100_000.0, r.margin, 1e-9)
        assertEquals(listOf(10_000.0, 15_000.0), r.gains)
    }

    @Test
    fun aTightStopIsHeldDownByTheMoney() {
        // ₹1 lakh, 2% = ₹2,000; ₹0.5 a share would be 4,000 shares = ₹4 lakh: CNC allows ₹25,000 (250), MIS ₹5 lakh (5,000).
        val cnc = PositionSize.of(100_000.0, 2.0, 100.0, 99.5, listOf(101.0), PositionSize.Product.CNC)!!
        assertEquals(250, cnc.qty)
        assertEquals(PositionSize.Limit.MONEY, cnc.limit)
        val mis = PositionSize.of(100_000.0, 2.0, 100.0, 99.5, listOf(101.0), PositionSize.Product.MIS)!!
        assertEquals(4000, mis.qty)
        assertEquals(PositionSize.Limit.RISK, mis.limit)
        assertEquals(80_000.0, mis.margin, 1e-9)
    }

    @Test
    fun aSellSizesTheSameWayAndNonsenseGivesNothing() {
        val sell = PositionSize.of(200_000.0, 1.0, 98.0, 100.0, listOf(95.0), PositionSize.Product.MIS)!!
        assertEquals(1000, sell.qty)
        assertEquals(listOf(3_000.0), sell.gains)
        assertNull(PositionSize.of(0.0, 1.0, 100.0, 95.0, emptyList(), PositionSize.Product.CNC))
        assertNull(PositionSize.of(100_000.0, 1.0, 100.0, 100.0, emptyList(), PositionSize.Product.CNC))
        assertEquals(0, PositionSize.of(1_000.0, 0.5, 5_000.0, 4_900.0, emptyList(), PositionSize.Product.CNC)!!.qty)
    }
}
