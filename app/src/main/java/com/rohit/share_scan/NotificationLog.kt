package com.rohit.share_scan

import org.json.JSONArray
import org.json.JSONObject

/**
 * The app's own record of what it told the user (Menu → सूचनाएँ): every alert [Notifier] posts (live finds, the
 * watchlist's breakouts, targets and stops, filings, बड़े पैसे की चाल, the paper account, square-off) and the report of
 * a crash, newest first, kept [KEEP_DAYS] days and at most [KEEP]. It is written whether or not Android may show the
 * alert, so a notification swiped away or never allowed is still here. Free of Android types so it can be unit tested.
 */
object NotificationLog {
    const val KEEP = 300
    const val KEEP_DAYS = 30L
    private const val DAY_MS = 24 * 3_600_000L

    enum class Kind { LIVE, WATCH, NEWS, BIG_MONEY, PAPER, SQUARE_OFF, CRASH }

    /** What a tap on the entry opens. */
    enum class Opens { STOCK, NEWS, PAPER, BIG_MONEY, CRASH, NOTHING }

    data class Entry(
        /** Epoch millis. */
        val at: Long,
        val kind: Kind,
        val title: String,
        val text: String,
        val symbol: String? = null,
        val opens: Opens = if (symbol != null) Opens.STOCK else Opens.NOTHING,
    )

    /** [entries] with [entry] in front, less what is older than [KEEP_DAYS] days at [now] or beyond [KEEP]. */
    fun add(entries: List<Entry>, entry: Entry, now: Long): List<Entry> =
        (listOf(entry) + entries)
            .filter { now - it.at < KEEP_DAYS * DAY_MS }
            .sortedByDescending { it.at }
            .take(KEEP)

    /** How many came after [seenAt], when the user last opened the list. */
    fun unread(entries: List<Entry>, seenAt: Long): Int = entries.count { it.at > seenAt }

    fun encode(entries: List<Entry>): String = JSONArray().apply {
        entries.forEach { e ->
            put(
                JSONObject().apply {
                    put("at", e.at)
                    put("k", e.kind.name)
                    put("t", e.title)
                    put("x", e.text)
                    e.symbol?.let { put("s", it) }
                    put("o", e.opens.name)
                },
            )
        }
    }.toString()

    /** The kept entries; an unreadable file, or an entry of a kind this version does not know, is left out. */
    fun decode(text: String?): List<Entry> {
        if (text.isNullOrBlank()) return emptyList()
        val array = runCatching { JSONArray(text) }.getOrNull() ?: return emptyList()
        return (0 until array.length()).mapNotNull { i ->
            runCatching {
                val o = array.getJSONObject(i)
                Entry(
                    at = o.getLong("at"),
                    kind = Kind.valueOf(o.getString("k")),
                    title = o.getString("t"),
                    text = o.optString("x"),
                    symbol = o.optString("s").takeIf { it.isNotEmpty() },
                    opens = runCatching { Opens.valueOf(o.optString("o")) }.getOrDefault(Opens.NOTHING),
                )
            }.getOrNull()
        }
    }
}
