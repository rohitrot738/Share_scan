package com.rohit.share_scan

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * Fetches stock news on the phone, from the sources the app was checked against: NSE's corporate announcements (BSE's
 * for a BSE-only stock) and Google News' headlines. Answers are kept in memory for a few minutes, so a sheet opened
 * again or a list drawn again asks nothing new. Blocking: call it off the main thread.
 */
object NewsRepository {
    /**
     * A stock's news for its detail sheet; [failed] when no source answered (the list is then empty); [wide] when it
     * reaches back a year, the stock's last month and week being too quiet.
     */
    data class Result(val items: List<NewsItem>, val failed: Boolean, val wide: Boolean = false)

    private const val NSE_API = "https://www.nseindia.com/api/corporate-announcements"
    private const val NSE_HOME = "https://www.nseindia.com/"
    private const val NSE_REFERER = "https://www.nseindia.com/companies-listing/corporate-filings-announcements"
    private const val BSE_API = "https://api.bseindia.com/BseIndiaAPI/api/AnnSubCategoryGetData/w"
    private const val GOOGLE_NEWS = "https://news.google.com/rss/search"
    /** A phone browser's: NSE and BSE answer browsers, and BSE refuses a desktop one. */
    private const val AGENT = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/129.0 Mobile Safari/537.36"

    /** How far back a stock's filings and articles go. */
    private const val FILING_DAYS = 30L
    private const val ARTICLE_DAYS = 7
    /** How far back a quiet stock's go, so that its tab has [StockNews.TAB_MIN] items. */
    private const val WIDE_DAYS = 365L
    private const val STOCK_FRESH_MS = 10L * 60 * 1000
    private const val MARKET_FRESH_MS = 15L * 60 * 1000

    private val NSE_DAY: DateTimeFormatter = DateTimeFormatter.ofPattern("dd-MM-yyyy", Locale.US)
    private val BSE_DAY: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd", Locale.US)

    /** NSE's cookies, once it asked for them; most answers come without. */
    @Volatile private var nseCookie: String? = null
    private val stocks = ConcurrentHashMap<String, Pair<Long, Result>>()
    @Volatile private var market: Pair<Long, List<NewsItem>>? = null

    /**
     * [stock]'s filings of the last 30 days and the articles of the last week that name it, newest first; for a quiet
     * stock (fewer than [StockNews.TAB_MIN] that are not routine notices) those of the past year. Filings come from
     * NSE, or BSE for a BSE-only stock; articles from Google News, in English and Hindi.
     */
    fun stock(stock: ScanResult, now: Long = System.currentTimeMillis()): Result {
        stocks[stock.symbol]?.let { (at, kept) -> if (now - at < STOCK_FRESH_MS && !kept.failed) return kept }
        val today = MarketClock.now(now).toLocalDate()
        val filings = official(stock.symbol, stock.market, stock.bseCode, today.minusDays(FILING_DAYS), today)
        val articles = articles(stock)
        var result = Result(StockNews.merge(filings.orEmpty(), articles.orEmpty()), failed = filings == null && articles == null)
        if (!result.failed && StockNews.thin(result.items)) {
            val yearAgo = now - WIDE_DAYS * StockNews.DAY_MS
            val older = official(stock.symbol, stock.market, stock.bseCode, today.minusDays(WIDE_DAYS), today)
            // Google News' search without a time limit, its most fitting articles of any age; the past year's stay.
            val olderArticles = articles(stock, days = null)?.filter { it.at >= yearAgo }
            if (older != null || olderArticles != null) {
                result = Result(StockNews.merge(older ?: filings.orEmpty(), articles.orEmpty(), olderArticles.orEmpty()), failed = false, wide = true)
            }
        }
        stocks[stock.symbol] = now to result
        return result
    }

    /** One stock's filings from [from] to [to]: NSE's, or BSE's for a BSE-only stock. Null when the exchange did not answer. */
    fun official(symbol: String, market: Market, bseCode: String, from: LocalDate, to: LocalDate): List<NewsItem>? = when (market) {
        Market.BSE -> bseCode.takeIf { it.isNotBlank() }?.let { code ->
            get(
                "$BSE_API?pageno=1&strCat=-1&strPrevDate=${from.format(BSE_DAY)}&strScrip=$code&strSearch=P" +
                    "&strToDate=${to.format(BSE_DAY)}&strType=C&subcategory=-1",
                mapOf("Referer" to "https://www.bseindia.com/", "Origin" to "https://www.bseindia.com", "Accept" to "application/json"),
            )?.let { StockNews.bse(it, symbol) }
        }
        else -> nse(
            "index=${if (market == Market.NSE_SME) "sme" else "equities"}&symbol=${encode(symbol)}" +
                "&from_date=${from.format(NSE_DAY)}&to_date=${to.format(NSE_DAY)}",
        )?.let(StockNews::nse)
    }

    /**
     * The whole market's filings on NSE (main board and SME) from the latest trading day before today on, kept for 15
     * minutes; the list's 📰 marks come from them. Null when NSE did not answer and nothing was kept.
     */
    fun market(now: Long = System.currentTimeMillis()): List<NewsItem>? {
        market?.let { (at, kept) -> if (now - at < MARKET_FRESH_MS) return kept }
        val today = MarketClock.now(now).toLocalDate()
        var from = today.minusDays(1)
        while (!MarketClock.isWeekday(from)) from = from.minusDays(1)
        val dates = "&from_date=${from.format(NSE_DAY)}&to_date=${today.format(NSE_DAY)}"
        val main = nse("index=equities$dates")?.let(StockNews::nse)
        val sme = nse("index=sme$dates")?.let(StockNews::nse)
        if (main == null && sme == null) return market?.second
        val items = main.orEmpty() + sme.orEmpty()
        market = now to items
        return items
    }

    /** The 📰 marks for the list's cards: each stock's filings of the last day ([StockNews.badges]). */
    fun badges(now: Long = System.currentTimeMillis()): Map<String, NewsBadge> = StockNews.badges(market(now).orEmpty(), now)

    /**
     * Google News' articles of the last [days] naming [stock] (any age when null), English and Hindi together; null when
     * neither answered.
     */
    fun articles(stock: ScanResult, days: Int? = ARTICLE_DAYS): List<NewsItem>? {
        val short = StockSearch.shortName(stock.name).ifBlank { stock.symbol }
        // The symbol counts as a name when it reads as a word (TCS, SSWL), not a BSE code.
        val names = listOf(short, stock.symbol).filter { name -> name.any { it.isLetter() } }.distinct()
        val within = days?.let { " when:${it}d" }.orEmpty()
        val english = rss("\"$short\"$within", "en-IN", "IN:en")?.let { StockNews.googleNews(it, stock.symbol, names) }
        val hindi = rss("\"$short\" शेयर$within", "hi", "IN:hi")?.let { StockNews.googleNews(it, stock.symbol, names) }
        if (english == null && hindi == null) return null
        return english.orEmpty() + hindi.orEmpty()
    }

    private fun rss(query: String, language: String, edition: String): String? =
        get("$GOOGLE_NEWS?q=${encode(query)}&hl=$language&gl=IN&ceid=${encode(edition)}", mapOf("Accept" to "application/rss+xml, */*"))

    /** NSE's announcements API; asks for NSE's cookies (its home page) once a plain request is turned away. */
    private fun nse(query: String): String? {
        val url = "$NSE_API?$query"
        val headers = mapOf("Referer" to NSE_REFERER, "Accept" to "application/json, text/plain, */*")
        fun ask(): String? = get(url, headers + listOfNotNull(nseCookie?.let { "Cookie" to it }))?.takeIf { it.trimStart().startsWith("[") }
        ask()?.let { return it }
        nseCookie = cookies(NSE_HOME) ?: return null
        return ask()
    }

    /** The name=value pairs a page sets, as one Cookie header. */
    private fun cookies(address: String): String? = runCatching {
        val connection = open(address, mapOf("Accept" to "text/html"))
        try {
            connection.inputStream.use { it.readBytes() }
            connection.headerFields.entries.filter { it.key.equals("Set-Cookie", ignoreCase = true) }
                .flatMap { it.value }.map { it.substringBefore(';') }.filter { '=' in it }
                .joinToString("; ").ifEmpty { null }
        } finally {
            connection.disconnect()
        }
    }.getOrNull()

    /** The body of a 200 answer, or null for any other answer or a network failure. */
    private fun get(address: String, headers: Map<String, String>): String? = try {
        val connection = open(address, headers)
        try {
            if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                connection.errorStream?.use { it.readBytes() }
                null
            } else {
                connection.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
            }
        } finally {
            connection.disconnect()
        }
    } catch (failure: IOException) {
        null
    }

    private fun open(address: String, headers: Map<String, String>): HttpURLConnection =
        (URL(address).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 25_000
            setRequestProperty("User-Agent", AGENT)
            setRequestProperty("Accept-Language", "en-IN,en;q=0.9,hi;q=0.8")
            headers.forEach { (name, value) -> setRequestProperty(name, value) }
        }

    private fun encode(text: String): String = URLEncoder.encode(text, "UTF-8")
}
