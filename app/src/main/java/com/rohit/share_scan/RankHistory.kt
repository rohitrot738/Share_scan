package com.rohit.share_scan

/** How a stock's score rank moved since the previous completed scan. */
sealed interface RankBadge {
    /** Entered the top [RankHistory.TOP] since the previous scan. */
    data object New : RankBadge

    /** Moved this many places up (positive) or down (negative). */
    data class Moved(val places: Int) : RankBadge
}

/**
 * Remembers the score ranks of one completed scan so the next scan can mark new entries and rank moves.
 * A rank is the position among every stock the scan scored (best score = 1), whatever the filters are.
 * Free of Android types so it can be unit tested; the view model reads and writes the preference.
 */
object RankHistory {
    /** Only the best ranks are kept, which keeps the preference to a few kilobytes. */
    const val KEEP = 300

    /** A stock that reaches this rank or better without having been there last time counts as new. */
    const val TOP = 100

    /** Format tag; text without it decodes as "no history". */
    private const val PREFIX = "r1:"
    private const val SEPARATOR = ','

    /**
     * The first [KEEP] symbols, best first, so a symbol's position is its rank: "r1:TECHM,TCS,M&M".
     * A symbol that cannot be stored safely leaves an empty slot, so the ranks after it stay right.
     */
    fun encode(symbols: List<String>): String =
        symbols.take(KEEP).joinToString(SEPARATOR.toString(), prefix = PREFIX) { if (storable(it)) it else "" }

    /** Symbol to rank; empty for missing, foreign or damaged text. A repeated symbol keeps its best rank. */
    fun decode(text: String?): Map<String, Int> {
        if (text == null || !text.startsWith(PREFIX)) return emptyMap()
        val ranks = HashMap<String, Int>()
        text.substring(PREFIX.length).split(SEPARATOR).forEachIndexed { index, symbol ->
            if (index < KEEP && storable(symbol) && symbol !in ranks) ranks[symbol] = index + 1
        }
        return ranks
    }

    /**
     * Badges for a scan's symbols (best score first) measured against the [previous] scan's ranks.
     * Without any previous ranks (the very first scan) nothing is marked.
     */
    fun badges(symbols: List<String>, previous: Map<String, Int>): Map<String, RankBadge> {
        if (previous.isEmpty()) return emptyMap()
        val badges = HashMap<String, RankBadge>()
        val seen = HashSet<String>()
        symbols.forEachIndexed { index, symbol ->
            if (seen.add(symbol)) badge(index + 1, previous[symbol])?.let { badges[symbol] = it }
        }
        return badges
    }

    /**
     * [RankBadge.New] when [rank] is inside the top [TOP] and [previousRank] was not (or was not remembered);
     * otherwise the move, or null when the previous rank is unknown or unchanged.
     */
    fun badge(rank: Int, previousRank: Int?): RankBadge? = when {
        rank <= TOP && (previousRank == null || previousRank > TOP) -> RankBadge.New
        previousRank == null || previousRank == rank -> null
        else -> RankBadge.Moved(previousRank - rank)
    }

    private fun storable(symbol: String): Boolean =
        symbol.isNotEmpty() && symbol.none { it == SEPARATOR || it.isWhitespace() }
}
