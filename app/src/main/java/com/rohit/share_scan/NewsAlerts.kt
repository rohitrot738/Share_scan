package com.rohit.share_scan

import android.content.Context
import androidx.core.content.edit
import java.time.LocalTime

/**
 * The watchlist's news in the background, from live mode's job ([LiveScanWorker]) every 15 minutes or so. Between
 * 8:00 and 9:15 IST, once a day, one notification rounds up what came for the starred stocks since the latest close:
 * their filings and the day's leading article. From 7:00 to 22:00 a big filing (results, an order, a dividend, a
 * resignation…) is notified the moment it comes. Between 22:00 and 7:00 nothing sounds; those filings wait for the
 * morning's round-up.
 */
object NewsAlerts {
    private val QUIET_FROM: LocalTime = LocalTime.of(22, 0)
    private val QUIET_TO: LocalTime = LocalTime.of(7, 0)
    private val DIGEST_FROM: LocalTime = LocalTime.of(8, 0)

    /** The most starred stocks asked about in one round, a request each. */
    private const val MAX_STOCKS = 25
    private const val GAP_MS = 250L
    /** How long a filing's id is kept as sent. */
    private const val SEEN_MS = 3 * StockNews.DAY_MS

    /**
     * One round. [force] ignores the hours, the setting and the day's round-up already sent, and rounds up the last
     * two days: the review's way to see both notifications at any hour.
     */
    fun check(context: Context, now: Long = System.currentTimeMillis(), force: Boolean = false) {
        if (!force && !AppSettings(context).newsAlerts) return
        val prefs = context.getSharedPreferences(Prefs.FILE, Context.MODE_PRIVATE)
        val watchlist = prefs.getStringSet(Prefs.WATCHLIST, null).orEmpty()
        if (watchlist.isEmpty()) return
        val time = MarketClock.now(now)
        val clock = time.toLocalTime()
        if (!force && (clock >= QUIET_FROM || clock < QUIET_TO)) return
        val today = time.toLocalDate()
        val since = if (force) now - 2 * StockNews.DAY_MS else StockNews.latestClose(time)
        val stocks = watched(context, watchlist).take(MAX_STOCKS)
        val filings = stocks.flatMap { stock ->
            Thread.sleep(GAP_MS)
            NewsRepository.official(stock.symbol, stock.market, stock.bseCode, MarketClock.now(since).toLocalDate(), today).orEmpty()
        }
        val seen = seen(prefs.getString(Prefs.NEWS_SEEN, null), now)
        val digestDue = force ||
            (clock >= DIGEST_FROM && clock < MarketClock.OPEN && prefs.getLong(Prefs.NEWS_DIGEST_DAY, -1L) != today.toEpochDay())
        if (digestDue) {
            val fresh = filings.filter { it.at > since && it.kind != NewsKind.ROUTINE && it.kind != NewsKind.INVESTOR }
            // The leading article of the night for each stock, after its filings.
            val articles = stocks.mapNotNull { stock ->
                NewsRepository.articles(stock, days = 1).orEmpty().filter { it.at > since }.maxByOrNull { it.at }
            }
            val byStock = (fresh + articles).groupBy { it.symbol }
                .mapValues { (_, news) -> news.sortedWith(compareByDescending<NewsItem> { it.official }.thenBy { it.kind }.thenByDescending { it.at }) }
                .toList()
                .sortedBy { (_, news) -> news.first().kind }
            if (byStock.isNotEmpty()) Notifier.newsDigest(context, byStock)
            prefs.edit {
                if (!force) putLong(Prefs.NEWS_DIGEST_DAY, today.toEpochDay())
                putString(Prefs.NEWS_SEEN, encode(seen + fresh.associate { it.id to it.at }))
            }
            return
        }
        val alerts = StockNews.alerts(filings, watchlist, seen.keys, now)
        if (alerts.isEmpty()) return
        val names = stocks.associate { it.symbol to it.name }
        alerts.forEach { Notifier.newsAlert(context, it, names[it.symbol].orEmpty()) }
        prefs.edit { putString(Prefs.NEWS_SEEN, encode(seen + alerts.associate { it.id to it.at })) }
    }

    /** The starred stocks as the kept scan knows them (exchange, BSE code, name); others as NSE stocks. */
    private fun watched(context: Context, watchlist: Set<String>): List<ScanResult> {
        val kept = ScanEngine.loaded(context)
        val known = kept?.let { it.output.results + it.watchExtras }.orEmpty().associateBy { it.symbol }
        return watchlist.sorted().map { known[it] ?: ScanResult(symbol = it, price = 0.0, score = 0.0, rvol = 0.0, change5d = 0.0) }
    }

    /** The ids sent and when their filings came, those older than three days dropped. */
    internal fun seen(text: String?, now: Long): Map<String, Long> = text.orEmpty().lineSequence().mapNotNull { line ->
        val at = line.substringAfterLast('@', "").toLongOrNull() ?: return@mapNotNull null
        (line.substringBeforeLast('@') to at).takeIf { now - at < SEEN_MS }
    }.toMap()

    internal fun encode(seen: Map<String, Long>): String = seen.entries.joinToString("\n") { "${it.key}@${it.value}" }
}
