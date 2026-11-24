package com.rohit.share_scan

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.StringReader

class OrderFlowDataTest {
    @Test
    fun aggregatesExplicitBuyAndSellTradesIntoPriceLevels() {
        val data = OrderFlowCsv.decode(
            StringReader(
                "symbol,timestamp,price,quantity,aggressor,trade_id\n" +
                    "RELIANCE,2026-11-24T09:15:01+05:30,1500.00,12,buy,1\n" +
                    "RELIANCE,2026-11-24T09:15:10+05:30,1500.00,7,sell,2\n" +
                    "RELIANCE,2026-11-24T09:16:02+05:30,1501.00,5,buy,3\n",
            ),
            "sample.csv",
        )
        assertEquals(3, data.tradeCount)
        val first = data.barsBySymbol.getValue("RELIANCE").first()
        assertEquals(12L, first.askVolume)
        assertEquals(7L, first.bidVolume)
        assertEquals(5L, first.delta)
        assertEquals(1500.0, first.poc!!.toDouble(), 0.0)
    }

    @Test
    fun duplicateTradeIdsAreIgnoredAndSymbolsStaySeparate() {
        val data = OrderFlowCsv.decode(
            StringReader(
                "symbol,timestamp,price,quantity,aggressor,trade_id\n" +
                    "ABC,2026-11-24T09:15:01Z,10,5,buy,dup\n" +
                    "ABC,2026-11-24T09:15:02Z,10,5,buy,dup\n" +
                    "XYZ,2026-11-24T09:15:03Z,20,4,sell,dup\n",
            ),
            "sample.csv",
        )
        assertEquals(2, data.tradeCount)
        assertEquals(listOf("ABC", "XYZ"), data.symbols)
    }

    @Test
    fun rejectsMissingTimezoneAndImplicitAggressorSide() {
        val noTimezone = "symbol,timestamp,price,quantity,aggressor\nABC,2026-11-24T09:15:01,10,5,buy"
        val noSide = "symbol,timestamp,price,quantity,aggressor\nABC,2026-11-24T09:15:01Z,10,5,unknown"
        assertTrue(runCatching { OrderFlowCsv.decode(StringReader(noTimezone), "bad.csv") }.isFailure)
        assertTrue(runCatching { OrderFlowCsv.decode(StringReader(noSide), "bad.csv") }.isFailure)
    }
}
