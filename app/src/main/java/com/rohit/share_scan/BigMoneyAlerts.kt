package com.rohit.share_scan

import com.rohit.share_scan.BigMoneyBook.Signal
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit

/**
 * What the evening's बड़े पैसे की चाल file ([BigMoneyBook]) tells the user, once per session: each watchlisted share
 * with a new signal, and a round-up of how many shares are accumulating. A session's file that comes by 22:30 is told
 * that evening; one that comes later waits for the next morning, before the market opens. Accumulation and churn
 * are one day's signals and always new; the deals' and promoters' last 30 and 90 days, so they are told only when
 * they were not there the session before. Free of Android types; [BigMoneyNotices] posts the result.
 */
object BigMoneyAlerts {
    private val EVENING_FROM: LocalTime = LocalTime.of(19, 0)
    private val EVENING_UNTIL: LocalTime = LocalTime.of(22, 30)
    private val MORNING_FROM: LocalTime = LocalTime.of(8, 0)
    private val MORNING_UNTIL: LocalTime = LocalTime.of(9, 15)

    /** Past a long weekend and a holiday, a session's file is old news. */
    private const val MAX_DAYS_LATE = 4L
    const val TOP = 3

    private val LASTING = setOf(Signal.DEAL_BUY, Signal.DEAL_SELL, Signal.PROMOTER_BUY, Signal.PROMOTER_SELL)

    data class Plan(
        val session: LocalDate,
        /** Watchlisted shares and their new signals, in the watchlist's order. */
        val watch: List<Pair<String, Set<Signal>>>,
        /** Shares accumulating that session, and the strongest few. */
        val count: Int,
        val top: List<String>,
        /** Every watchlisted share's signals that session, kept for the next session's comparison. */
        val flags: Map<String, Set<Signal>>,
    )

    /** What to tell for [book]'s session at [now]; null outside the hours, or when [seen] already covers it. */
    fun plan(
        book: BigMoneyBook,
        watchlist: Set<String>,
        seen: LocalDate?,
        previous: Map<String, Set<Signal>>,
        now: ZonedDateTime,
    ): Plan? {
        val session = book.date ?: return null
        if (seen != null && seen >= session) return null
        val today = now.toLocalDate()
        val time = now.toLocalTime()
        val evening = today == session && time >= EVENING_FROM && time <= EVENING_UNTIL
        val late = ChronoUnit.DAYS.between(session, today)
        val morning = late in 1..MAX_DAYS_LATE && time >= MORNING_FROM && time <= MORNING_UNTIL
        if (!evening && !morning) return null
        val flags = watchlist.mapNotNull { symbol -> book.of(symbol)?.let { symbol to it.signals } }.toMap()
        val watch = flags.mapNotNull { (symbol, signals) ->
            val before = previous[symbol].orEmpty()
            signals.filter { it !in LASTING || it !in before }.toSet().takeIf { it.isNotEmpty() }?.let { symbol to it }
        }
        val accumulating = book.accumulation()
        return Plan(session, watch, accumulating.size, accumulating.take(TOP).map { it.symbol }, flags)
    }

    /** "A:acc,deal_buy;F:promoter_buy", for the app's preferences. */
    fun encode(flags: Map<String, Set<Signal>>): String =
        flags.entries.joinToString(";") { (symbol, signals) -> symbol + ":" + signals.joinToString(",") { it.key } }

    fun decode(text: String?): Map<String, Set<Signal>> = text.orEmpty().split(';').mapNotNull { part ->
        val (symbol, keys) = part.split(':', limit = 2).takeIf { it.size == 2 && it[0].isNotBlank() } ?: return@mapNotNull null
        symbol to keys.split(',').mapNotNull { key -> Signal.entries.firstOrNull { it.key == key } }.toSet()
    }.toMap()
}
