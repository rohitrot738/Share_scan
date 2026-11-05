package com.rohit.share_scan

import com.rohit.share_scan.BigMoneyBook.Signal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

class BigMoneyAlertsTest {
    private val zone = ZoneId.of("Asia/Kolkata")
    private val wednesday = LocalDate.of(2026, 11, 4)

    private fun at(day: Int, hour: Int, minute: Int = 0) = ZonedDateTime.of(2026, 11, day, hour, minute, 0, 0, zone)

    private fun stock(symbol: String, flags: String, delivery: Double = 40.0) =
        """"$symbol":{"s":40,"p":[0,0,0,0,0,0],"f":[$flags],"d":$delivery,"da":20.0,"dq":[],"vq":[]}"""

    private val book = BigMoneyBook.parse(
        """{"v":1,"date":"2026-11-04","dates":[],"record":{},"stocks":{
            ${stock("A", "\"acc\",\"deal_buy\"", 60.0)},
            ${stock("B", "\"acc\"", 50.0)},
            ${stock("C", "\"acc\"", 45.0)},
            ${stock("D", "\"acc\"", 41.0)},
            ${stock("E", "\"churn\"")},
            ${stock("F", "\"promoter_buy\"")}}}""",
    )!!

    @Test
    fun eveningWindow() {
        val watch = setOf("A")
        assertNotNull(BigMoneyAlerts.plan(book, watch, null, emptyMap(), at(4, 21)))
        assertNull(BigMoneyAlerts.plan(book, watch, null, emptyMap(), at(4, 18, 30)))
        assertNull(BigMoneyAlerts.plan(book, watch, null, emptyMap(), at(4, 23)))
        assertNotNull(BigMoneyAlerts.plan(book, watch, null, emptyMap(), at(5, 8, 30)))
        assertNull(BigMoneyAlerts.plan(book, watch, null, emptyMap(), at(5, 9, 30)))
        // A week-old session is not news any more.
        assertNull(BigMoneyAlerts.plan(book, watch, null, emptyMap(), at(11, 8, 30)))
    }

    @Test
    fun oncePerSession() {
        assertNull(BigMoneyAlerts.plan(book, setOf("A"), wednesday, emptyMap(), at(4, 21)))
        assertNotNull(BigMoneyAlerts.plan(book, setOf("A"), wednesday.minusDays(1), emptyMap(), at(4, 21)))
    }

    @Test
    fun dealFlagNotRepeated() {
        val plan = BigMoneyAlerts.plan(book, setOf("A", "F"), null, mapOf("A" to setOf(Signal.DEAL_BUY), "F" to setOf(Signal.PROMOTER_BUY)), at(4, 21))!!
        assertEquals(listOf("A" to setOf(Signal.ACCUMULATION)), plan.watch)
        assertEquals(mapOf("A" to setOf(Signal.ACCUMULATION, Signal.DEAL_BUY), "F" to setOf(Signal.PROMOTER_BUY)), plan.flags)
    }

    @Test
    fun accumulationAlwaysNew() {
        val plan = BigMoneyAlerts.plan(book, setOf("B", "E", "Z"), null, mapOf("B" to setOf(Signal.ACCUMULATION)), at(4, 21))!!
        assertEquals(listOf("B" to setOf(Signal.ACCUMULATION), "E" to setOf(Signal.CHURN)), plan.watch)
    }

    @Test
    fun digestTopThree() {
        val plan = BigMoneyAlerts.plan(book, emptySet(), null, emptyMap(), at(4, 21))!!
        assertEquals(4, plan.count)
        assertEquals(listOf("A", "B", "C"), plan.top)
        assertEquals(wednesday, plan.session)
    }

    @Test
    fun flagsSurviveEncoding() {
        val flags = mapOf("A" to setOf(Signal.ACCUMULATION, Signal.DEAL_BUY), "F" to setOf(Signal.PROMOTER_BUY), "G" to emptySet())
        assertEquals(flags, BigMoneyAlerts.decode(BigMoneyAlerts.encode(flags)))
        assertEquals(emptyMap<String, Set<Signal>>(), BigMoneyAlerts.decode(null))
    }
}
