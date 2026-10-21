package com.rohit.share_scan

import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/** How 360CR's figures and dates read on the card and the detail sheet. Free of Android types so it can be unit tested. */
object Cr360Format {
    private const val MINUS = "−"
    private val MONTH = DateTimeFormatter.ofPattern("MMM yyyy", Locale.US)
    private val DAY = DateTimeFormatter.ofPattern("d MMM", Locale.US)

    /** "21.8", "0.45"; trailing zeros dropped. */
    fun number(value: Double, digits: Int = 1): String {
        val text = String.format(Locale.US, "%.${digits}f", value)
        val trimmed = if ('.' in text) text.trimEnd('0').trimEnd('.') else text
        return if (trimmed == "-0") "0" else trimmed.replace("-", MINUS)
    }

    /** "+1.2", "−0.4", "0". */
    fun signed(value: Double, digits: Int = 1): String {
        val text = number(value, digits)
        return if (text == "0" || text.startsWith(MINUS)) text else "+$text"
    }

    /** "2026-06-30" -> "Jun 2026"; the input as it is when it is not a date. */
    fun month(iso: String): String = runCatching { LocalDate.parse(iso).format(MONTH) }.getOrDefault(iso)

    /** "2026-10-21" -> "21 Oct"; the input as it is when it is not a date. */
    fun day(iso: String): String = runCatching { LocalDate.parse(iso.take(10)).format(DAY) }.getOrDefault(iso)
}
