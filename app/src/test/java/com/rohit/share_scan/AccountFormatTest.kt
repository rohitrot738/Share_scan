package com.rohit.share_scan

import org.junit.Assert.assertEquals
import org.junit.Test

class AccountFormatTest {
    private val months = listOf(
        "जन", "फ़र", "मार्च", "अप्रै", "मई", "जून", "जुला", "अग", "सितं", "अक्टू", "नवं", "दिसं",
    )

    @Test
    fun datesAreShownInIndianTime() {
        // 18 Oct 2026, 11:00 UTC is 4:30 PM in India.
        assertEquals("18 अक्टू 2026, 4:30 PM", AccountFormat.dateTime(1_792_321_200_000L, months))
        // 31 Jan 2026, 20:05 UTC is already 1 Feb in India.
        assertEquals("1 फ़र 2026, 1:35 AM", AccountFormat.dateTime(1_769_889_900_000L, months))
    }

    @Test
    fun initialComesFromNameThenEmail() {
        assertEquals("R", AccountFormat.initial("rohit kumar", "x@y.in"))
        assertEquals("A", AccountFormat.initial("  ", "abc@example.com"))
        assertEquals("र", AccountFormat.initial("रोहित", null))
        assertEquals("?", AccountFormat.initial(null, ""))
    }
}
