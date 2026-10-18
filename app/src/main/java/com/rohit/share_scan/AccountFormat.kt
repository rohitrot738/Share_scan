package com.rohit.share_scan

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Plain-text pieces of the Account sheet, kept free of Android and Firebase so unit tests cover them. */
object AccountFormat {
    private val IST: ZoneId = ZoneId.of("Asia/Kolkata")

    // English AM/PM on purpose: the Hindi locale's "अपराह्न" reads oddly next to the app's other times.
    private val CLOCK: DateTimeFormatter = DateTimeFormatter.ofPattern("h:mm a", Locale.ENGLISH)

    /** "18 अक्टू 2026, 4:30 PM" in Indian time; [months] are the 12 short Hindi month names, January first. */
    fun dateTime(millis: Long, months: List<String>): String {
        val at = Instant.ofEpochMilli(millis).atZone(IST)
        return "${at.dayOfMonth} ${months[at.monthValue - 1]} ${at.year}, ${CLOCK.format(at)}"
    }

    /** The letter in the photo-less avatar: the name's (else the email's) first character, upper-cased. */
    fun initial(name: String?, email: String?): String {
        val source = name?.trim().takeUnless { it.isNullOrEmpty() } ?: email?.trim().orEmpty()
        if (source.isEmpty()) return "?"
        return source.substring(0, source.offsetByCodePoints(0, 1)).uppercase(Locale.ROOT)
    }
}
