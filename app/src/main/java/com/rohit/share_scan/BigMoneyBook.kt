package com.rohit.share_scan

import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZonedDateTime

/**
 * बड़े पैसे की चाल: the evening file the repository's job writes from NSE's delivery file, the bulk and block deals
 * and the promoters' trades (bigmoney/build.py), read once a day ([BigMoneyRepository]).
 *
 * Each share traded in the latest session carries its signals ([Signal]), a 0–100 score with its six parts (delivery
 * spike, delivery building up, deals net buying, promoters net buying, churn, net selling), the figures behind them
 * and its last 20 sessions of delivery and volume. [records] say how each signal did over the past year: the move 10
 * sessions after it showed, against all shares' move. Free of Android types so it can be unit tested.
 */
class BigMoneyBook(
    /** The session the file is of; null only for [EMPTY]. */
    val date: LocalDate?,
    /** The sessions of [Entry.deliverySeries] and [Entry.volumeSeries], oldest first. */
    val dates: List<LocalDate>,
    /** Each signal's track record by its key ([Signal.key], and "score60" for a score of 60 or more). */
    val records: Map<String, Record>,
    private val entries: Map<String, Entry>,
    /** The newest promoter/insider filing the file knew of; NSE's filings come only when NSE answers the job. */
    val promoterUntil: LocalDate? = null,
) {
    /** A signal and whether it warns (the price is expected to do worse than the market) rather than invites. */
    enum class Signal(val key: String, val bearish: Boolean) {
        ACCUMULATION("acc", false),
        CHURN("churn", true),
        DEAL_BUY("deal_buy", false),
        DEAL_SELL("deal_sell", true),
        PROMOTER_BUY("promoter_buy", false),
        PROMOTER_SELL("promoter_sell", true),
    }

    /** The promoters' trades are a month or more behind the session (or of unknown age), so their tab may be empty. */
    val promotersStale: Boolean
        get() = date != null && (promoterUntil == null || promoterUntil < date.minusDays(STALE_DAYS))

    /** How often a signal showed in the past year ([n]) and, from 30 times on, its average move 10 sessions later,
     *  all shares' average move over the same sessions and the % of times the price was higher, all in %. */
    data class Record(val n: Int, val avg: Double?, val base: Double?, val up: Double?) {
        val ready: Boolean get() = avg != null && base != null && up != null

        /** Whether the signal held: an inviting one beat all shares' move, a warning ([bearish]) fell short of it; null before 30 cases. */
        fun worked(bearish: Boolean): Boolean? {
            if (!ready) return null
            val avg = avg ?: return null
            val base = base ?: return null
            return if (bearish) avg < base else avg > base
        }
    }

    /** The share's bulk and block deals over 30 sessions, netted: shares and rupees (negative when sold), the
     *  sessions with deals, and the run of net buying (positive) or selling (negative) on the latest of them. */
    data class Deal(val shares: Long, val rupees: Double, val days: Int, val streak: Int)

    /** The promoters' market buys less sells over 90 days, in rupees, and how many of each. */
    data class Promoter(val rupees: Double, val buys: Int, val sells: Int)

    data class Entry(
        val symbol: String,
        val score: Int,
        /** Delivery spike, building up, deals, promoters, churn (≤ 0), selling (≤ 0). */
        val parts: List<Double>,
        val signals: Set<Signal>,
        /** Today's delivery %, its average over the 20 sessions before, and the last 5 sessions' average. */
        val delivery: Double?,
        val deliveryAvg: Double?,
        val delivery5: Double?,
        /** Today's shares traded and the 20-session average. */
        val volume: Long?,
        val volumeAvg: Double?,
        /** Today's price change, %. */
        val change: Double?,
        val deliverySeries: List<Double?>,
        val volumeSeries: List<Long?>,
        val deal: Deal?,
        /** Clients who bought and sold the share within 5 sessions. */
        val circular: List<String>,
        val promoter: Promoter?,
        /** A new listing's sessions so far (it first traded inside the store's window); null for one there all along. */
        val sessions: Int? = null,
    ) {
        /** Too few sessions for the scan's chart ([BreakoutSignals.MIN_SESSIONS]), so the share is not in its list. */
        val tooNewToChart: Boolean get() = sessions != null && sessions < BreakoutSignals.MIN_SESSIONS

        /** Today's delivery over its average, the accumulation tab's order. */
        val deliveryRatio: Double? get() = if (delivery != null && deliveryAvg != null && deliveryAvg > 0) delivery / deliveryAvg else null

        /** Today's volume over its average, the churn tab's order. */
        val volumeRatio: Double? get() = if (volume != null && volumeAvg != null && volumeAvg > 0) volume / volumeAvg else null
    }

    val size: Int get() = entries.size

    fun of(symbol: String): Entry? = entries[symbol]

    fun accumulation(): List<Entry> =
        entries.values.filter { Signal.ACCUMULATION in it.signals }.sortedByDescending { it.deliveryRatio ?: 0.0 }

    fun churn(): List<Entry> = entries.values.filter { Signal.CHURN in it.signals }.sortedByDescending { it.volumeRatio ?: 0.0 }

    /** Shares with deals in the last 30 sessions: net buyers by rupees, the biggest first, then net sellers. */
    fun deals(): List<Entry> = netFirst(entries.values.mapNotNull { e -> e.deal?.takeIf { it.shares != 0L }?.let { e to it.rupees } })

    /** Shares whose promoters bought or sold in the market in 90 days, buyers first. */
    fun promoters(): List<Entry> =
        netFirst(entries.values.mapNotNull { e -> e.promoter?.takeIf { it.rupees != 0.0 }?.let { e to it.rupees } })

    private fun netFirst(rows: List<Pair<Entry, Double>>): List<Entry> {
        val (buys, sells) = rows.partition { it.second > 0 }
        return buys.sortedByDescending { it.second }.map { it.first } + sells.sortedBy { it.second }.map { it.first }
    }

    companion object {
        val EMPTY = BigMoneyBook(null, emptyList(), emptyMap(), emptyMap())

        /** Days past which the promoters' trades count as behind. */
        const val STALE_DAYS = 30L

        /** The book in [text]; null for a broken or unknown file, so the copy in use stays. */
        fun parse(text: String): BigMoneyBook? = runCatching {
            val root = JSONObject(text)
            if (root.optInt("v") != 1) return null
            val date = LocalDate.parse(root.getString("date"))
            val dates = root.getJSONArray("dates").let { a -> (0 until a.length()).map { LocalDate.parse(a.getString(it)) } }
            val recordJson = root.getJSONObject("record")
            val records = recordJson.keys().asSequence().associateWith { key ->
                val r = recordJson.getJSONObject(key)
                Record(r.getInt("n"), r.double("avg"), r.double("base"), r.double("up"))
            }
            val stocks = root.getJSONObject("stocks")
            val entries = stocks.keys().asSequence().associateWith { symbol -> entry(symbol, stocks.getJSONObject(symbol)) }
            val until = root.optString("pu").takeIf { it.isNotEmpty() && it != "null" }?.let(LocalDate::parse)
            BigMoneyBook(date, dates, records, entries, until)
        }.getOrNull()

        private fun entry(symbol: String, o: JSONObject): Entry {
            val flags = o.getJSONArray("f").strings().toSet()
            return Entry(
                symbol = symbol,
                score = o.getInt("s"),
                parts = o.getJSONArray("p").let { a -> (0 until a.length()).map { a.getDouble(it) } },
                signals = Signal.entries.filter { it.key in flags }.toSet(),
                delivery = o.double("d"),
                deliveryAvg = o.double("da"),
                delivery5 = o.double("d5"),
                volume = o.long("v"),
                volumeAvg = o.double("va"),
                change = o.double("c"),
                deliverySeries = o.getJSONArray("dq").let { a -> (0 until a.length()).map { if (a.isNull(it)) null else a.getDouble(it) } },
                volumeSeries = o.getJSONArray("vq").let { a -> (0 until a.length()).map { if (a.isNull(it)) null else a.getLong(it) } },
                deal = o.optJSONArray("dn")?.let { Deal(it.getLong(0), it.getDouble(1), it.getInt(2), it.getInt(3)) },
                circular = o.optJSONArray("cc")?.strings().orEmpty(),
                promoter = o.optJSONArray("pn")?.let { Promoter(it.getDouble(0), it.getInt(1), it.getInt(2)) },
                sessions = o.long("ns")?.toInt(),
            )
        }

        private fun JSONObject.double(key: String): Double? = if (!has(key) || isNull(key)) null else getDouble(key)

        private fun JSONObject.long(key: String): Long? = if (!has(key) || isNull(key)) null else getLong(key)

        private fun JSONArray.strings(): List<String> = (0 until length()).map { getString(it) }
    }
}

/** When the app fetches the file again ([BigMoneyRepository]). */
object BigMoneySchedule {
    /** The job's first try of the evening is at 19:15; NSE's file may come later still. */
    val EVENING: LocalTime = LocalTime.of(19, 15)
    const val REFRESH_MS = 3L * 60 * 60 * 1000
    const val RETRY_MS = 30L * 60 * 1000

    /**
     * Whether to fetch: with no file, with a file [REFRESH_MS] old, or on a weekday evening when the kept book
     * ([bookDate]) is of an earlier session and the last try was [RETRY_MS] ago.
     */
    fun due(bookDate: LocalDate?, fileAgeMs: Long, now: ZonedDateTime): Boolean {
        if (bookDate == null || fileAgeMs >= REFRESH_MS) return true
        val today = now.toLocalDate()
        return MarketClock.isWeekday(today) && now.toLocalTime() >= EVENING && bookDate < today && fileAgeMs >= RETRY_MS
    }
}
