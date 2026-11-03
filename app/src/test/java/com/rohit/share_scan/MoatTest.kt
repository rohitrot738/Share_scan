package com.rohit.share_scan

import com.rohit.share_scan.Cr360Book.Fact
import com.rohit.share_scan.Moat.Check
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MoatTest {
    private val strong = mapOf(
        Fact.ROCE to 24.0, Fact.DEBT_TO_EQUITY to 0.1, Fact.INTEREST_COVER to 12.0, Fact.CASH_TO_PROFIT to 1.1,
        Fact.QUARTERS to 8.0, Fact.PROFITABLE_QUARTERS to 8.0, Fact.NET_MARGIN to 18.0, Fact.PROMOTER to 62.0,
        Fact.PROMOTER_CHANGE to 0.0, Fact.PLEDGE to 0.0, Fact.EPS_GROWTH to 15.0,
    )

    @Test
    fun aStrongCompanyPassesEveryCheck() {
        val lines = Moat.lines(strong)
        assertTrue(lines.all { it.pass == true })
        assertEquals(10 to 10, Moat.score(lines))
        assertTrue(Moat.quality(strong))
    }

    @Test
    fun weakFiguresFailAndMissingOnesHaveNoVerdict() {
        val weak = strong + mapOf(Fact.ROCE to 9.0, Fact.PLEDGE to 12.0, Fact.AUDITOR_RESIGNED to 20260501.0) - Fact.INTEREST_COVER
        val lines = Moat.lines(weak).associateBy { it.check }
        assertEquals(false, lines.getValue(Check.ROCE).pass)
        assertEquals(false, lines.getValue(Check.PLEDGE).pass)
        assertEquals(false, lines.getValue(Check.AUDITOR).pass)
        assertNull(lines.getValue(Check.INTEREST_COVER).pass)
        assertEquals(6 to 9, Moat.score(lines.values.toList()))
        assertFalse(Moat.quality(weak))
        assertFalse(Moat.quality(strong - Fact.ROCE))
        assertTrue(Moat.quality(strong - Fact.EPS_GROWTH))
        assertFalse(Moat.quality(strong + (Fact.EPS_GROWTH to 5.0)))
    }
}
