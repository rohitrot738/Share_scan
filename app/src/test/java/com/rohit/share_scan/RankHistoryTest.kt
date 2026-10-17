package com.rohit.share_scan

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RankHistoryTest {
    @Test
    fun encodeAndDecodeKeepRanks() {
        val text = RankHistory.encode(listOf("TECHM", "M&M", "BAJAJ-AUTO"))
        assertEquals("r1:TECHM,M&M,BAJAJ-AUTO", text)
        assertEquals(mapOf("TECHM" to 1, "M&M" to 2, "BAJAJ-AUTO" to 3), RankHistory.decode(text))
    }

    @Test
    fun onlyTheBestRanksAreKept() {
        val symbols = (1..1_000).map { "S$it" }
        val ranks = RankHistory.decode(RankHistory.encode(symbols))
        assertEquals(RankHistory.KEEP, ranks.size)
        assertEquals(RankHistory.KEEP, ranks.getValue("S${RankHistory.KEEP}"))
        assertNull(ranks["S${RankHistory.KEEP + 1}"])
    }

    @Test
    fun unsafeSymbolsLeaveASlotSoLaterRanksStayRight() {
        val ranks = RankHistory.decode(RankHistory.encode(listOf("A", "B,C", "", "D E", "F")))
        assertEquals(mapOf("A" to 1, "F" to 5), ranks)
    }

    @Test
    fun missingForeignOrDamagedTextDecodesAsNoHistory() {
        assertTrue(RankHistory.decode(null).isEmpty())
        assertTrue(RankHistory.decode("").isEmpty())
        assertTrue(RankHistory.decode("TECHM,TCS").isEmpty())
        assertTrue(RankHistory.decode("r2:TECHM").isEmpty())
        assertTrue(RankHistory.decode("r1:").isEmpty())
        assertEquals(mapOf("A" to 1, "B" to 4), RankHistory.decode("r1:A,,A,B,"))
    }

    @Test
    fun firstScanHasNoBadges() {
        assertTrue(RankHistory.badges(listOf("A", "B"), emptyMap()).isEmpty())
    }

    @Test
    fun badgesMarkNewEntriesAndMoves() {
        val previous = mapOf("UP" to 9, "SAME" to 2, "DOWN" to 1, "WAS_OUT" to 150, "LOW" to 250)
        // Ranks: UP 1, SAME 2, DOWN 3, FRESH 4, WAS_OUT 5, F6..F200 6..200, LOW 201.
        val current = listOf("UP", "SAME", "DOWN", "FRESH", "WAS_OUT") + (6..200).map { "F$it" } + "LOW"
        val badges = RankHistory.badges(current, previous)

        assertEquals(RankBadge.Moved(8), badges["UP"])
        assertNull(badges["SAME"])
        assertEquals(RankBadge.Moved(-2), badges["DOWN"])
        assertEquals(RankBadge.New, badges["FRESH"])
        assertEquals(RankBadge.New, badges["WAS_OUT"])
        assertEquals(RankBadge.New, badges["F6"])
        // Outside the top 100 a stock is never "new"; with a remembered rank it still shows its move.
        assertNull(badges["F150"])
        assertEquals(RankBadge.Moved(49), badges["LOW"])
    }

    @Test
    fun badgeRules() {
        assertEquals(RankBadge.New, RankHistory.badge(1, null))
        assertEquals(RankBadge.New, RankHistory.badge(RankHistory.TOP, RankHistory.TOP + 1))
        assertEquals(RankBadge.Moved(1), RankHistory.badge(RankHistory.TOP - 1, RankHistory.TOP))
        assertNull(RankHistory.badge(5, 5))
        assertNull(RankHistory.badge(RankHistory.TOP + 1, null))
        assertEquals(RankBadge.Moved(-30), RankHistory.badge(130, 100))
    }

    @Test
    fun repeatedSymbolsKeepTheirFirstRank() {
        val badges = RankHistory.badges(listOf("A", "A"), mapOf("A" to 1))
        assertNull(badges["A"])
    }
}
