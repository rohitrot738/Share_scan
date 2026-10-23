package com.rohit.share_scan

import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDateTime
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/** What a piece of news is about. [big] kinds raise a watchlist alert the moment they come; [ROUTINE] filings fold away. */
enum class NewsKind(val big: Boolean) {
    RESULTS(true),
    ORDER(true),
    DIVIDEND(true),
    FUNDRAISE(true),
    DEAL(true),
    EXPANSION(true),
    MANAGEMENT(true),
    RATING(true),
    LEGAL(true),
    MEETING(false),
    INVESTOR(false),
    OTHER(false),
    ROUTINE(false),
}

/**
 * One piece of news about a stock: a filing on NSE or BSE ([official]), or a newspaper's article found through Google
 * News. [at] is epoch millis; [id] stays the same each time the item is read, so a watchlist alert comes once.
 */
data class NewsItem(
    val symbol: String,
    val at: Long,
    val title: String,
    val source: String,
    val url: String,
    val kind: NewsKind,
    val official: Boolean,
    val id: String,
)

/** A card's 📰 mark: the weightiest kind among a stock's recent filings, how many there are and when the latest came. */
data class NewsBadge(val kind: NewsKind, val count: Int, val latest: Long)

/**
 * Reads stock news: NSE's and BSE's corporate announcements and Google News' RSS, each into [NewsItem]s, sorts out
 * what each is about ([classify]) and when it came against the session ([timing]). Free of Android types so it can be
 * unit tested; [NewsRepository] does the fetching.
 */
object StockNews {
    /** When an item came against the trading session it can move. */
    enum class Timing {
        /** After the latest close and before the next open: news the next session opens on. */
        PRE_OPEN,

        /** Today, while the market was open. */
        LIVE,
    }

    private val NSE_TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss", Locale.ENGLISH)
    private val NSE_SHOWN_TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("dd-MMM-yyyy HH:mm:ss", Locale.ENGLISH)
    private val RSS_TIME: DateTimeFormatter = DateTimeFormatter.RFC_1123_DATE_TIME
    private const val BSE_FILES = "https://www.bseindia.com/xml-data/corpfiling/AttachLive/"

    /** "<Company> has informed the Exchange about …", the opening most filings' text shares. */
    private val INFORMED = Regex(
        """^.*?\bhas\s+(?:informed|submitted|intimated)(?:\s+to)?\s+the\s+exchanges?(?:\s+a\s+copy(?:\s+of)?)?(?:\s+(?:about|regarding|that|of|on))?\s*""",
        RegexOption.IGNORE_CASE,
    )

    /** Rules in order: the first whose pattern the text matches decides the kind. */
    private val RULES: List<Pair<NewsKind, Regex>> = listOf(
        NewsKind.ROUTINE to """newspaper publication|trading window|74\s?\(5\)|depositories and participants|loss of share certificate|duplicate share|duplicate certificate|compliance certificate|regulation 7\s?\(3\)|reg\. 7\s?\(3\)|investor complaints|registrar and share transfer|\besop\b|\besos\b|s?c?rutini[sz]er|e-voting|voting results|postal ballot|shareholders meeting|book closure""",
        NewsKind.MEETING to """board meeting intimation|intimation of board meeting|board meeting to consider|prior intimation|meeting of the board .{0,40}(?:will|to) be held""",
        NewsKind.LEGAL to """\bsebi\b|penalt|\bfine\b|\bcourt\b|\bnclt\b|tribunal|litigation|orders? passed|insolvency|fraud|\braid\b|show.cause|income tax|gst demand|\bdefault""",
        NewsKind.RESULTS to """financial results?|quarterly results?|results? for the (?:quarter|half|year)|\bq[1-4]\b|\bq[1-4]fy|net profit|profit (?:rises|jumps|falls|drops|surges|declines|up|down)|\bearnings\b""",
        NewsKind.DIVIDEND to """dividend|\bbonus\b|stock split|sub-?division|buy-?back|record date""",
        NewsKind.FUNDRAISE to """\bqip\b|preferential|rights issue|fund ?rais|raising of funds|\bwarrants\b|\bncds?\b|debentures|\bipo\b|allotment of (?:equity|shares)""",
        NewsKind.DEAL to """acquisition|acquires?\b|\bacquired\b|merger|amalgamation|scheme of arrangement|joint venture|\bstake\b|divest|demerger""",
        NewsKind.EXPANSION to """\bplant\b|capacity|expansion|\bcapex\b|greenfield|brownfield|new facility|manufacturing facility|commissioning|commercial production""",
        NewsKind.ORDER to """bagging|receiving of orders?|receipt of orders?|\border win|\bbags?\b.{0,30}\border|secures?.{0,30}\b(?:order|contract)|wins?.{0,30}\b(?:order|contract)|orders? worth|order (?:from|of rs|valued)|\bcontracts?\b|letter of (?:award|intent)""",
        NewsKind.MANAGEMENT to """resignation|resigns?\b|appointment|appoints?\b|cessation|managing director|\bceo\b|\bcfo\b|\bkmp\b|key managerial|change in director""",
        NewsKind.RATING to """credit rating|\brating\b|\bupgrade|\bdowngrade""",
        NewsKind.INVESTOR to """analyst|investors? meet|institutional investor|con\. ?call|conference call|earnings call|investor presentation|\bpresentation\b""",
    ).map { (kind, pattern) -> kind to Regex(pattern, RegexOption.IGNORE_CASE) }

    /** What [text] (a filing's category and words, or a headline) is about. */
    fun classify(text: String): NewsKind = RULES.firstOrNull { (_, rule) -> rule.containsMatchIn(text) }?.first ?: NewsKind.OTHER

    /** NSE's corporate announcements (a JSON array), one stock's or the whole market's. */
    fun nse(json: String): List<NewsItem> {
        val array = runCatching { JSONArray(json) }.getOrNull() ?: return emptyList()
        return List(array.length()) { array.optJSONObject(it) }.mapNotNull { row ->
            row ?: return@mapNotNull null
            val symbol = row.optString("symbol").trim()
            val at = nseTime(row) ?: return@mapNotNull null
            if (symbol.isEmpty()) return@mapNotNull null
            val category = row.optString("desc").trim()
            val title = cleanFiling(row.optString("attchmntText"), category)
            NewsItem(
                symbol = symbol,
                at = at,
                title = title,
                source = "NSE",
                url = row.optString("attchmntFile").trim(),
                kind = classify("$category. $title"),
                official = true,
                id = "nse:" + row.optString("seq_id").ifBlank { "$symbol:$at:${title.hashCode()}" },
            )
        }
    }

    /** BSE's announcements for one scrip ({"Table": [...]}); [symbol] is the app's symbol for it. */
    fun bse(json: String, symbol: String): List<NewsItem> {
        val table = runCatching { JSONObject(json).optJSONArray("Table") }.getOrNull() ?: return emptyList()
        return List(table.length()) { table.optJSONObject(it) }.mapNotNull { row ->
            row ?: return@mapNotNull null
            val at = localTime(row.optString("NEWS_DT").ifBlank { row.optString("DissemDT") }) ?: return@mapNotNull null
            val category = listOf(row.optString("CATEGORYNAME"), row.optString("SUBCATNAME"), row.optString("NEWSSUB"))
                .filter { it.isNotBlank() && it != "null" }.joinToString(". ")
            val title = cleanFiling(row.optString("HEADLINE").ifBlank { row.optString("NEWSSUB") }, category)
            val file = row.optString("ATTACHMENTNAME").trim()
            NewsItem(
                symbol = symbol,
                at = at,
                title = title,
                source = "BSE",
                url = if (file.isNotEmpty() && file != "null") BSE_FILES + file else "https://www.bseindia.com/corporates/ann.html",
                kind = classify("$category. $title"),
                official = true,
                id = "bse:" + row.optString("NEWSID").ifBlank { "$symbol:$at:${title.hashCode()}" },
            )
        }
    }

    /**
     * Google News' RSS answer for a search, kept to the items whose headline names the company: one of [names] (its
     * short name, its symbol) as a whole word. The " - Publisher" end of each headline is left out.
     */
    fun googleNews(xml: String, symbol: String, names: List<String>): List<NewsItem> {
        val wanted = names.filter { it.length >= 2 }.map { Regex("""(?<![\p{L}\p{N}])${Regex.escape(it)}(?![\p{L}\p{N}])""", RegexOption.IGNORE_CASE) }
        return ITEM.findAll(xml).mapNotNull { match ->
            val item = match.groupValues[1]
            val source = tag(item, "source")
            var title = tag(item, "title")
            if (source.isNotEmpty() && title.endsWith(" - $source")) title = title.removeSuffix(" - $source")
            val link = tag(item, "link")
            val at = runCatching { ZonedDateTime.parse(tag(item, "pubDate"), RSS_TIME).toInstant().toEpochMilli() }.getOrNull()
            if (title.isEmpty() || link.isEmpty() || at == null) return@mapNotNull null
            if (wanted.isNotEmpty() && wanted.none { it.containsMatchIn(title) }) return@mapNotNull null
            NewsItem(
                symbol = symbol,
                at = at,
                title = title,
                source = source.ifEmpty { "Google News" },
                url = link,
                kind = classify(title),
                official = false,
                id = "g:" + link.hashCode(),
            )
        }.toList()
    }

    /**
     * Filings and articles together, newest first. A filing read twice is kept once; so is an article under the same
     * headline twice (Google News often lists one story from its English and Hindi feeds, or from two papers).
     */
    fun merge(vararg lists: List<NewsItem>): List<NewsItem> {
        val seen = HashSet<String>()
        return lists.flatMap { it }.sortedByDescending { it.at }.filter { item ->
            seen.add(item.id) && (item.official || seen.add("t:" + normalized(item.title)))
        }
    }

    /**
     * [at] against the session, as of [now]: news since the latest close (evening, night, weekend, or the morning
     * before the open) is what the next session opens on; news while today's session runs is live. Older news gets
     * neither.
     */
    fun timing(at: Long, now: Long = System.currentTimeMillis()): Timing? {
        if (at > now) return null
        val item = MarketClock.now(at)
        val today = MarketClock.now(now)
        if (MarketClock.isOpen(item) && item.toLocalDate() == today.toLocalDate()) return Timing.LIVE
        return if (at > latestClose(today)) Timing.PRE_OPEN else null
    }

    /** The close of the latest session that ended by [now] (weekends skipped; holidays are not known here). */
    fun latestClose(now: ZonedDateTime): Long {
        var day = now.withZoneSameInstant(MarketClock.INDIA).toLocalDate()
        if (!MarketClock.isWeekday(day) || now.withZoneSameInstant(MarketClock.INDIA).toLocalTime() < MarketClock.CLOSE) {
            day = day.minusDays(1)
            while (!MarketClock.isWeekday(day)) day = day.minusDays(1)
        }
        return day.atTime(MarketClock.CLOSE).atZone(MarketClock.INDIA).toInstant().toEpochMilli()
    }

    /**
     * The 📰 marks for the list's cards: each stock's filings of the last [window] (to [now]), routine filings and
     * analyst meets left out. The kind shown is the weightiest one, in [NewsKind]'s order.
     */
    fun badges(items: List<NewsItem>, now: Long, window: Long = DAY_MS): Map<String, NewsBadge> =
        items.asSequence()
            .filter { it.official && it.kind != NewsKind.ROUTINE && it.kind != NewsKind.INVESTOR && it.at in (now - window)..now }
            .groupBy { it.symbol }
            .mapValues { (_, news) -> NewsBadge(news.minOf { it.kind }, news.size, news.maxOf { it.at }) }

    /**
     * The filings a watchlist alert goes out for: [big] kinds, on a stock in [watchlist], not alerted before ([seen]
     * holds the ids already sent), at most [maxAgeMs] old as of [now]. Oldest first, as they happened.
     */
    fun alerts(items: List<NewsItem>, watchlist: Set<String>, seen: Set<String>, now: Long, maxAgeMs: Long = DAY_MS): List<NewsItem> =
        items.filter { it.official && it.kind.big && it.symbol in watchlist && it.id !in seen && now - it.at in 0..maxAgeMs }
            .sortedBy { it.at }

    const val DAY_MS = 24L * 60 * 60 * 1000

    /** A filing's own words without the "<Company> has informed the Exchange about" opening; its category without words. */
    fun cleanFiling(text: String, category: String): String {
        val words = text.trim().replace(Regex("""\s+"""), " ")
        val cleaned = words.replace(INFORMED, "").trim().removeSuffix(".").trim('\'', '"', '‘', '’', '“', '”').removeSuffix(".").trim()
        val result = cleaned.ifEmpty { category.trim() }
        return result.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.ENGLISH) else it.toString() }
    }

    private fun nseTime(row: JSONObject): Long? =
        localTime(row.optString("sort_date"))
            ?: runCatching { LocalDateTime.parse(row.optString("an_dt"), NSE_SHOWN_TIME).atZone(MarketClock.INDIA).toInstant().toEpochMilli() }.getOrNull()

    /** "2026-10-19 11:30:07" or "2026-10-19T11:30:41.537", IST. */
    private fun localTime(text: String): Long? {
        val trimmed = text.trim()
        if (trimmed.isEmpty() || trimmed == "null") return null
        return runCatching { LocalDateTime.parse(trimmed, NSE_TIME) }
            .recoverCatching { LocalDateTime.parse(trimmed) }
            .getOrNull()?.atZone(MarketClock.INDIA)?.toInstant()?.toEpochMilli()
    }

    private val ITEM = Regex("""<item>(.*?)</item>""", RegexOption.DOT_MATCHES_ALL)

    private fun tag(xml: String, name: String): String {
        val match = Regex("""<$name(?:\s[^>]*)?>(.*?)</$name>""", RegexOption.DOT_MATCHES_ALL).find(xml) ?: return ""
        return unescape(match.groupValues[1].removePrefix("<![CDATA[").removeSuffix("]]>")).trim()
    }

    private fun unescape(text: String): String = text
        .replace(Regex("""&#(\d+);""")) { it.groupValues[1].toIntOrNull()?.let { code -> String(Character.toChars(code)) } ?: it.value }
        .replace(Regex("""&#x([0-9a-fA-F]+);""")) { it.groupValues[1].toIntOrNull(16)?.let { code -> String(Character.toChars(code)) } ?: it.value }
        .replace("&quot;", "\"").replace("&apos;", "'").replace("&lt;", "<").replace("&gt;", ">").replace("&nbsp;", " ")
        .replace("&amp;", "&")

    private fun normalized(title: String): String = title.lowercase(Locale.ROOT).filter { it.isLetterOrDigit() }.take(60)
}
