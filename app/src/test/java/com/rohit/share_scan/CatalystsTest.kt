package com.rohit.share_scan

import com.rohit.share_scan.Catalysts.Kind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate

class CatalystsTest {
    private val today = LocalDate.of(2026, 11, 4)
    private val now = today.atStartOfDay(MarketClock.INDIA).toInstant().toEpochMilli()

    private fun filing(title: String, daysAgo: Long = 2, official: Boolean = true) =
        NewsItem("X", now - daysAgo * 86_400_000L, title, "BSE", "", NewsKind.MEETING, official, title)

    @Test
    fun datesAreReadInTheWaysFilingsWriteThem() {
        assertEquals(listOf(LocalDate.of(2026, 11, 12)), Catalysts.dates("to be held on 12-11-2026 inter alia"))
        assertEquals(listOf(LocalDate.of(2026, 11, 12)), Catalysts.dates("on Thursday, 12th November, 2026"))
        assertEquals(listOf(LocalDate.of(2026, 11, 20)), Catalysts.dates("Record date is November 20, 2026"))
        assertEquals(listOf(LocalDate.of(2026, 12, 1)), Catalysts.dates("on 01-Dec-2026"))
        assertEquals(emptyList<LocalDate>(), Catalysts.dates("on 31/02/2026"))
    }

    @Test
    fun theKindComesFromWhatTheFilingIsAbout() {
        assertEquals(Kind.RESULTS, Catalysts.kind("Board Meeting Intimation for Financial Results for the quarter ended September 2026"))
        assertEquals(Kind.DIVIDEND, Catalysts.kind("Record Date for Interim Dividend"))
        assertEquals(Kind.BONUS, Catalysts.kind("Board to consider Bonus issue"))
        assertEquals(Kind.MEETING, Catalysts.kind("Notice of AGM"))
        assertNull(Catalysts.kind("Trading window closure"))
    }

    @Test
    fun onlyComingEventsFromRecentOfficialFilingsSoonestFirst() {
        val events = Catalysts.upcoming(
            listOf(
                filing("Record date for dividend: 20/11/2026"),
                filing("Board Meeting Intimation for results on 12-11-2026"),
                filing("Board Meeting for results on 12-11-2026 (revised)"),
                filing("Results declared on 01-11-2026"), // already past
                filing("Dividend record date 10-12-2026", official = false), // a newspaper's, not the company's
                filing("Bonus record date 15-11-2026", daysAgo = 90), // an old filing
                filing("AGM on 15-06-2027"), // too far ahead
            ),
            today,
        )
        assertEquals(listOf(LocalDate.of(2026, 11, 12) to Kind.RESULTS, LocalDate.of(2026, 11, 20) to Kind.DIVIDEND), events.map { it.day to it.kind })
    }
}
