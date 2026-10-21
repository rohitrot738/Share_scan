package com.rohit.share_scan

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class Cr360HistoryTest {
    private val file = """
        {"v":1,"updated":"2026-10-21T20:30+05:30","stocks":{
          "CDSL":{"q":[["2603",263,80,3.84],["2606",293,118,5.62]],
                  "s":[["2606",15.0,8.24,15.19,61.57],["2609",15.0,8.66,17.77,58.56]],
                  "y":[["2503",1082,526,543,388,3],["2603",1145,455,467,346,2]],
                  "p":{"m":"2607","c":[1333.0,null,1264.0,1237.2]}},
          "LOSS":{"q":[["2606",10.5,-2.25,null]],"s":[],"y":[]}
        }}
    """.trimIndent()

    @Test
    fun readsOneStocksTablesAndChart() {
        val h = Cr360History.parse(file, "CDSL")!!
        assertEquals(listOf("2603", "2606"), h.quarters.map { it.period })
        assertEquals(118.0, h.quarters.last().profit!!, 1e-9)
        assertEquals(8.66, h.holders.last().fii!!, 1e-9)
        assertEquals(17.77, h.holders.last().dii!!, 1e-9)
        assertEquals(346.0, h.years.last().freeCash!!, 1e-9)
        assertEquals(2.0, h.years.last().debt!!, 1e-9)
        assertEquals("2607", h.chart!!.firstMonth)
        assertEquals(listOf(1333.0, null, 1264.0, 1237.2), h.chart!!.closes)
        val loss = Cr360History.parse(file, "LOSS")!!
        assertEquals(-2.25, loss.quarters.single().profit!!, 1e-9)
        assertNull(loss.quarters.single().eps)
        assertNull(loss.chart)
    }

    @Test
    fun missingStocksAndBrokenFilesHaveNoHistory() {
        assertNull(Cr360History.parse(file, "TCS"))
        assertNull(Cr360History.parse(null, "CDSL"))
        assertNull(Cr360History.parse("<html>Not Found</html>", "CDSL"))
        assertNull(Cr360History.parse("""{"v":2,"stocks":{"CDSL":{}}}""", "CDSL"))
    }

    @Test
    fun aStockIsInTheSameFileTheCacheWroteItTo() {
        // The cache's own numbers (tests/test_cr360_app_cache.py, history_shard).
        assertEquals(31, Cr360History.shard("CDSL"))
        assertEquals(8, Cr360History.shard("RELIANCE"))
        assertEquals(3, Cr360History.shard("TSC"))
        assertEquals(8, Cr360History.shard("M&M"))
    }

    @Test
    fun periodsReadAsMonthsAndFinancialYears() {
        assertEquals("Jun 26", Cr360History.label("2606"))
        assertEquals("Dec 23", Cr360History.label("2312"))
        assertEquals("x", Cr360History.label("x"))
        assertEquals("FY26", Cr360History.yearLabel("2603"))
        assertEquals("Dec 25", Cr360History.yearLabel("2512"))
        assertEquals("2401", Cr360History.monthAfter("2310", 3))
        assertEquals("2607", Cr360History.monthAfter("2607", 0))
    }

    @Test
    fun tableFiguresAndShareholdingSteps() {
        assertEquals("1,082", Cr360Format.amount(1082.0))
        assertEquals("48.5", Cr360Format.amount(48.5))
        assertEquals("−1,200", Cr360Format.amount(-1200.0))
        assertEquals("−2.3", Cr360Format.amount(-2.25))
        assertEquals("▲ 0.42", Cr360Format.step(0.42))
        assertEquals("▼ 3.12", Cr360Format.step(8.24 - 11.36))
        assertEquals("0", Cr360Format.step(0.001))
    }
}
