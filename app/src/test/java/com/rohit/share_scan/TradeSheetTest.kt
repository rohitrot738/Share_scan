package com.rohit.share_scan

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TradeSheetTest {
    private val file = """
        {"v":1,"stocks":{"RELIANCE":{"q":[["2606",1,1,1]],"s":[],"y":[],
          "i":[["2026-02-13","sell",3294168.0,2320,"BALANADU NARAYAN","Other","Off Market","2026-02-18 19:06",0.0,null],
               ["2026-02-13","buy",4430088.0,3120,"Shah, \"Raj\"","Immediate relative","Off Market","2026-02-18 19:07",0.01,"2026-02-15"],
               ["2025-01-05","sell",1000000.0]],
          "d":[["2026-10-19","buy",60391588.31,235877,"ARIHANT CAPITAL","block"]]}}}
    """.trimIndent()

    private val history = Cr360History.parse(file, "RELIANCE")!!

    @Test
    fun everyInsiderTradeAndDealIsRead() {
        val sold = history.insider.first()
        assertEquals("2026-02-13", sold.day)
        assertEquals(false, sold.buy)
        assertEquals(2320L, sold.shares)
        assertEquals("BALANADU NARAYAN", sold.name)
        assertEquals("Other", sold.who)
        assertEquals("Off Market", sold.how)
        assertEquals("2026-02-18 19:06", sold.filed)
        assertEquals(1419.9, sold.price!!, 1e-9)
        assertNull(sold.lastDay)
        assertEquals("2026-02-15", history.insider[1].lastDay)
        // A trade the cache kept before it kept names: day, side and rupees only.
        val old = history.insider.last()
        assertNull(old.name)
        assertNull(old.price)
        assertEquals(1000000.0, old.rupees!!, 1e-9)
        val deal = history.deals.single()
        assertEquals("ARIHANT CAPITAL", deal.name)
        assertEquals("block", deal.who)
        assertNull(deal.filed)
    }

    @Test
    fun newestFirst() {
        assertEquals(listOf("2026-02-18 19:07", "2026-02-18 19:06", null), TradeSheet.newestFirst(history.insider).map { it.filed })
    }

    @Test
    fun cellsReadAsTheyDoInTheTable() {
        assertEquals("13 Feb 26", TradeSheet.days(history.insider.first()))
        assertEquals("13 Feb 26 – 15 Feb 26", TradeSheet.days(history.insider[1]))
        assertEquals("18 Feb 26, 19:06", TradeSheet.filed("2026-02-18 19:06"))
        assertEquals("19 Oct 26", TradeSheet.filed("2026-10-19"))
        assertEquals("₹32.94 लाख", TradeSheet.rupees(3294168.0))
        assertEquals("₹6.04 Cr", TradeSheet.rupees(60391588.31))
        assertEquals("₹85,000", TradeSheet.rupees(85000.0))
        assertEquals("2,320", TradeSheet.shares(2320))
    }

    @Test
    fun everyColumnOfARow() {
        assertEquals(
            listOf("13 Feb 26", "18 Feb 26, 19:06", "BALANADU NARAYAN", "Other", "बिक्री", "2,320", "1,419.90", "₹32.94 लाख", "Off Market", "0%"),
            TradeSheet.cells(history.insider.first(), deal = false, buy = "ख़रीद", sell = "बिक्री", dash = "—"),
        )
        // A trade kept before names were: only what is known.
        assertEquals(
            listOf("5 Jan 25", "—", "—", "—", "बिक्री", "—", "—", "₹10 लाख", "—", "—"),
            TradeSheet.cells(history.insider.last(), deal = false, buy = "ख़रीद", sell = "बिक्री", dash = "—"),
        )
        assertEquals(
            // Android groups lakhs ("2,35,877"), a plain JVM thousands: the test takes whichever it runs on.
            listOf("19 Oct 26", "Block", "ARIHANT CAPITAL", "ख़रीद", TradeSheet.shares(235877), "256.03", "₹6.04 Cr"),
            TradeSheet.cells(history.deals.single(), deal = true, buy = "ख़रीद", sell = "बिक्री", dash = "—"),
        )
        assertEquals("ख़रीद", TradeSheet.cells(history.deals.single(), true, "ख़रीद", "बिक्री", "—")[TradeSheet.DEALS_SIDE])
        assertEquals("बिक्री", TradeSheet.cells(history.insider.first(), false, "ख़रीद", "बिक्री", "—")[TradeSheet.INSIDER_SIDE])
    }
}
