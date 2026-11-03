package com.rohit.share_scan

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class AllocationTest {
    private fun stock(symbol: String, price: Double, atr: Double?) = ScanResult(
        symbol, price, score = 50.0, rvol = 1.0, change5d = 0.0,
        structure = atr?.let { PriceStructure(listOf(price), price, price, it, price, null) },
    )

    @Test
    fun aCalmerStockGetsMoreMoney() {
        // Volatility 1% and 2%: weights 2/3 and 1/3.
        val parts = Allocation.inverseVolatility(listOf(stock("WILD", 100.0, 2.0), stock("CALM", 200.0, 2.0), stock("NONE", 50.0, null)), 90_000.0)
        assertEquals(listOf("CALM", "WILD"), parts.map { it.stock.symbol })
        assertEquals(2.0 / 3.0, parts[0].weight, 1e-9)
        assertEquals(60_000.0, parts[0].amount, 1e-6)
        assertEquals(300, parts[0].qty)
        assertEquals(300, parts[1].qty)
        assertTrue(Allocation.inverseVolatility(emptyList(), 1000.0).isEmpty())
    }

    @Test
    fun overAYearIsLongTerm() {
        val today = LocalDate.of(2026, 11, 4)
        assertFalse(Allocation.longTerm(today.minusDays(365), today))
        assertTrue(Allocation.longTerm(today.minusDays(366), today))
        assertEquals(0, Allocation.daysHeld(today.plusDays(2), today))
    }
}
