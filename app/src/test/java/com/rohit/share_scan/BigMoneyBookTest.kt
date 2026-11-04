package com.rohit.share_scan

import com.rohit.share_scan.BigMoneyBook.Signal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

class BigMoneyBookTest {
    private val sample = """
        {"v":1,"date":"2026-11-04","dates":["2026-11-03","2026-11-04"],
         "record":{"acc":{"n":1240,"avg":1.9,"base":0.7,"up":56.0},"churn":{"n":12,"avg":null,"base":null,"up":null}},
         "stocks":{
          "AJMERA":{"s":72,"p":[22.5,12.0,25.0,10.0,0.0,0.0],"f":["acc","deal_buy","promoter_buy"],"d":62.1,"da":24.0,
            "d5":30.5,"v":3400000,"va":410000.0,"c":1.2,"dq":[20.0,62.1],"vq":[400000,3400000],
            "dn":[1440000,200960000.0,2,2],"cc":["GOPANI JIGNESH GUNVANT"],"pn":[20000000.0,1,0]},
          "SUZLON":{"s":0,"p":[0.0,0.0,0.0,0.0,-30.0,0.0],"f":["churn"],"d":12.0,"da":31.0,"d5":20.0,"v":500000000,
            "va":100000000.0,"c":4.6,"dq":[null,12.0],"vq":[null,500000000],"dn":null,"cc":[],"pn":null},
          "TCS":{"s":10,"p":[5.0,5.0,0.0,0.0,0.0,0.0],"f":["deal_sell"],"d":51.7,"da":50.0,"d5":50.0,"v":3142051,
            "va":3000000.0,"c":-2.5,"dq":[50.0,51.7],"vq":[3000000,3142051],"dn":[-500000,-1250000000.0,2,-2],"cc":[],"pn":null},
          "INFY":{"s":40,"p":[20.0,0.0,0.0,0.0,0.0,0.0],"f":["acc"],"d":45.0,"da":20.0,"d5":25.0,"v":200000,
            "va":100000.0,"c":0.5,"dq":[20.0,45.0],"vq":[100000,200000],"dn":null,"cc":[],"pn":[-30000000.0,0,2]}
         }}
    """.trimIndent()

    @Test
    fun parsesEntry() {
        val book = BigMoneyBook.parse(sample)!!
        assertEquals(LocalDate.of(2026, 11, 4), book.date)
        assertEquals(listOf(LocalDate.of(2026, 11, 3), LocalDate.of(2026, 11, 4)), book.dates)
        assertEquals(4, book.size)
        val a = book.of("AJMERA")!!
        assertEquals(72, a.score)
        assertEquals(listOf(22.5, 12.0, 25.0, 10.0, 0.0, 0.0), a.parts)
        assertEquals(setOf(Signal.ACCUMULATION, Signal.DEAL_BUY, Signal.PROMOTER_BUY), a.signals)
        assertEquals(62.1, a.delivery!!, 0.0)
        assertEquals(24.0, a.deliveryAvg!!, 0.0)
        assertEquals(3_400_000L, a.volume)
        assertEquals(BigMoneyBook.Deal(1_440_000, 200_960_000.0, 2, 2), a.deal)
        assertEquals(listOf("GOPANI JIGNESH GUNVANT"), a.circular)
        assertEquals(BigMoneyBook.Promoter(20_000_000.0, 1, 0), a.promoter)
        val s = book.of("SUZLON")!!
        assertEquals(listOf(null, 12.0), s.deliverySeries)
        assertEquals(listOf(null, 500_000_000L), s.volumeSeries)
        assertNull(s.deal)
        assertNull(book.of("RELIANCE"))
    }

    @Test
    fun tabsAreOrdered() {
        val book = BigMoneyBook.parse(sample)!!
        // Accumulation by today's delivery against its average: AJMERA 2.59x, INFY 2.25x.
        assertEquals(listOf("AJMERA", "INFY"), book.accumulation().map { it.symbol })
        assertEquals(listOf("SUZLON"), book.churn().map { it.symbol })
        // Net buyers by rupees first, then net sellers, the biggest sale first.
        assertEquals(listOf("AJMERA", "TCS"), book.deals().map { it.symbol })
        assertEquals(listOf("AJMERA", "INFY"), book.promoters().map { it.symbol })
    }

    @Test
    fun recordUnderThirtyIsNotReady() {
        val book = BigMoneyBook.parse(sample)!!
        assertTrue(book.records.getValue("acc").ready)
        assertEquals(BigMoneyBook.Record(1240, 1.9, 0.7, 56.0), book.records.getValue("acc"))
        assertFalse(book.records.getValue("churn").ready)
        assertNull(book.records["deal_buy"])
    }

    @Test
    fun aWarningWorksWhenThePriceDidWorse() {
        // A buying signal works when the price beat all shares; a warning (churn, selling) when it did worse.
        assertEquals(true, BigMoneyBook.Record(1240, 1.9, 0.7, 56.0).worked(Signal.ACCUMULATION.bearish))
        assertEquals(false, BigMoneyBook.Record(733, -0.3, -0.2, 44.0).worked(Signal.ACCUMULATION.bearish))
        assertEquals(true, BigMoneyBook.Record(4131, -1.51, -0.27, 36.0).worked(Signal.CHURN.bearish))
        assertEquals(false, BigMoneyBook.Record(132, 0.4, -0.3, 50.0).worked(Signal.PROMOTER_SELL.bearish))
        assertNull(BigMoneyBook.Record(12, null, null, null).worked(false))
        assertEquals(setOf(Signal.CHURN, Signal.DEAL_SELL, Signal.PROMOTER_SELL), Signal.entries.filter { it.bearish }.toSet())
    }

    @Test
    fun promoterDataAgeIsKnown() {
        val stale = BigMoneyBook.parse(sample.replaceFirst("\"record\"", "\"pu\":\"2026-04-30\",\"record\""))!!
        assertEquals(LocalDate.of(2026, 4, 30), stale.promoterUntil)
        assertTrue(stale.promotersStale)
        val fresh = BigMoneyBook.parse(sample.replaceFirst("\"record\"", "\"pu\":\"2026-10-20\",\"record\""))!!
        assertFalse(fresh.promotersStale)
        // A file without the date (older builds) reads as stale: nothing says the trades are recent.
        assertNull(BigMoneyBook.parse(sample)!!.promoterUntil)
        assertTrue(BigMoneyBook.parse(sample)!!.promotersStale)
        assertFalse(BigMoneyBook.EMPTY.promotersStale)
    }

    @Test
    fun truncatedFileReadsAsNull() {
        assertNull(BigMoneyBook.parse(sample.dropLast(40)))
        assertNull(BigMoneyBook.parse("""{"v":2,"date":"2026-11-04","dates":[],"record":{},"stocks":{}}"""))
        assertNull(BigMoneyBook.parse("<html>"))
    }

    @Test
    fun dueAfterEveningOnlyWhenBehind() {
        val zone = ZoneId.of("Asia/Kolkata")
        val wednesday = { h: Int, m: Int -> ZonedDateTime.of(2026, 11, 4, h, m, 0, 0, zone) }
        val tuesday = LocalDate.of(2026, 11, 3)
        val hour = 60 * 60 * 1000L
        assertTrue(BigMoneySchedule.due(tuesday, hour, wednesday(19, 20)))
        assertFalse(BigMoneySchedule.due(LocalDate.of(2026, 11, 4), hour, wednesday(19, 20)))
        assertFalse(BigMoneySchedule.due(tuesday, hour, wednesday(19, 0)))
        assertFalse(BigMoneySchedule.due(tuesday, 10 * 60 * 1000L, wednesday(19, 20))) // tried 10 minutes ago
        assertTrue(BigMoneySchedule.due(tuesday, 3 * hour, wednesday(12, 0))) // three hours old
        assertTrue(BigMoneySchedule.due(null, Long.MAX_VALUE, wednesday(12, 0))) // no file yet
        // Saturday evening: no new session, so only the 3-hour refresh applies.
        assertFalse(BigMoneySchedule.due(LocalDate.of(2026, 11, 6), hour, ZonedDateTime.of(2026, 11, 7, 20, 0, 0, 0, zone)))
    }
}
