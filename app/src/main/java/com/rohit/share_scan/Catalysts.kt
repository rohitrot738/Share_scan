package com.rohit.share_scan

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Upcoming events from a company's own filings (the /short-catalysts skill): a board meeting for results, a record date
 * for a dividend, an AGM or EGM, a bonus, a split, a buyback or a rights issue, each with the day its filing names.
 * Only official filings of the last [RECENT_DAYS] days count, and only days from today to [AHEAD_DAYS] ahead. Free of
 * Android types so it can be unit tested.
 */
object Catalysts {
    enum class Kind { RESULTS, DIVIDEND, MEETING, BONUS, SPLIT, BUYBACK, RIGHTS, BOARD }

    data class Event(val day: LocalDate, val kind: Kind, val title: String)

    const val RECENT_DAYS = 60L
    const val AHEAD_DAYS = 120L

    private val MONTHS = listOf("jan", "feb", "mar", "apr", "may", "jun", "jul", "aug", "sep", "oct", "nov", "dec")

    // 12-11-2026, 12/11/2026, 12.11.2026
    private val NUMERIC = Regex("""\b(\d{1,2})[-/.](\d{1,2})[-/.](20\d{2})\b""")

    // 12 November 2026, 12th Nov, 2026, 12-Nov-2026
    private val DAY_MONTH = Regex("""\b(\d{1,2})(?:st|nd|rd|th)?[\s-]+([A-Za-z]{3,9})[\s,.-]+(20\d{2})\b""")

    // November 12, 2026
    private val MONTH_DAY = Regex("""\b([A-Za-z]{3,9})\s+(\d{1,2})(?:st|nd|rd|th)?,?\s+(20\d{2})\b""")

    // A board meeting on results and a dividend reads as results; a record date alone as a dividend.
    private val KINDS = listOf(
        Kind.RESULTS to Regex("""result|financial statements|quarter ended|half year ended""", RegexOption.IGNORE_CASE),
        Kind.BUYBACK to Regex("""buy[\s-]?back""", RegexOption.IGNORE_CASE),
        Kind.BONUS to Regex("""\bbonus\b""", RegexOption.IGNORE_CASE),
        Kind.SPLIT to Regex("""\bsplit|sub-?division""", RegexOption.IGNORE_CASE),
        Kind.RIGHTS to Regex("""\brights issue""", RegexOption.IGNORE_CASE),
        Kind.DIVIDEND to Regex("""dividend|record date""", RegexOption.IGNORE_CASE),
        Kind.MEETING to Regex("""\b(agm|egm|annual general meeting|extra-?ordinary general meeting)\b""", RegexOption.IGNORE_CASE),
        Kind.BOARD to Regex("""board meeting""", RegexOption.IGNORE_CASE),
    )

    /** Every day [text] names, in order. */
    fun dates(text: String): List<LocalDate> {
        val found = mutableListOf<Pair<Int, LocalDate>>()
        NUMERIC.findAll(text).forEach { m ->
            val (d, mo, y) = m.destructured
            runCatching { LocalDate.of(y.toInt(), mo.toInt(), d.toInt()) }.getOrNull()?.let { found += m.range.first to it }
        }
        DAY_MONTH.findAll(text).forEach { m ->
            val (d, name, y) = m.destructured
            month(name)?.let { mo -> runCatching { LocalDate.of(y.toInt(), mo, d.toInt()) }.getOrNull()?.let { found += m.range.first to it } }
        }
        MONTH_DAY.findAll(text).forEach { m ->
            val (name, d, y) = m.destructured
            month(name)?.let { mo -> runCatching { LocalDate.of(y.toInt(), mo, d.toInt()) }.getOrNull()?.let { found += m.range.first to it } }
        }
        return found.sortedBy { it.first }.map { it.second }.distinct()
    }

    private fun month(name: String): Int? = MONTHS.indexOf(name.take(3).lowercase()).takeIf { it >= 0 }?.plus(1)

    /** What a filing is about, or null when it names no event the app tracks. */
    fun kind(title: String): Kind? = KINDS.firstOrNull { it.second.containsMatchIn(title) }?.first

    /** The coming events in [items] (a stock's news), soonest first, one per day and kind. */
    fun upcoming(items: List<NewsItem>, today: LocalDate, zone: ZoneId = MarketClock.INDIA): List<Event> {
        val since = today.minusDays(RECENT_DAYS)
        val until = today.plusDays(AHEAD_DAYS)
        return items.asSequence()
            .filter { it.official }
            .filter { !Instant.ofEpochMilli(it.at).atZone(zone).toLocalDate().isBefore(since) }
            .flatMap { item ->
                val kind = kind(item.title) ?: return@flatMap emptySequence()
                dates(item.title).asSequence().filter { !it.isBefore(today) && !it.isAfter(until) }.map { Event(it, kind, item.title) }
            }
            .distinctBy { it.day to it.kind }
            .sortedBy { it.day }
            .toList()
    }
}
