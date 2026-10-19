package com.rohit.share_scan

import org.json.JSONObject
import java.util.Locale

/**
 * The industry of every stock the app can scan, from data/sectors.json (build_sectors.py): NSE's classification
 * ("Capital Goods", "Healthcare", ...), from NSE's index files and BSE's scrip pages, by ISIN. A stock the file does
 * not know has no industry; the scan counts it under [OTHER]. Free of Android types so it can be unit tested.
 */
class SectorBook(
    /** Every industry, in name order. */
    val sectors: List<String>,
    private val byIsin: Map<String, String>,
    /** How many stocks of each exchange have each industry. */
    private val counts: Map<Market, Map<String, Int>>,
    /** How many stocks each exchange listed when the file was built, with or without an industry. */
    private val totals: Map<Market, Int>,
    /** The day the file was built (yyyy-mm-dd); empty when unknown. */
    val updated: String = "",
) {
    val size: Int get() = byIsin.size

    /** The industry of the stock with [isin]; empty when the file does not know it. */
    fun of(isin: String): String = byIsin[isin.uppercase(Locale.US)].orEmpty()

    /** Stocks of [market] in each industry, with [OTHER] for those without one, biggest first. */
    fun counts(market: Market): List<Pair<String, Int>> {
        val known = counts[market].orEmpty()
        val other = (totals[market] ?: 0) - known.values.sum()
        val rows = known.entries.sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
            .map { it.key to it.value }
        return if (other > 0) rows + (OTHER to other) else rows
    }

    companion object {
        /** The scan's key for stocks without an industry. */
        const val OTHER = "-"

        val EMPTY = SectorBook(emptyList(), emptyMap(), emptyMap(), emptyMap())

        private val MARKET_KEYS = mapOf(Market.NSE to "nse", Market.NSE_SME to "sme", Market.BSE to "bse")

        /** Reads data/sectors.json; [EMPTY] for text that is not one. */
        fun parse(json: String?): SectorBook = runCatching {
            val root = JSONObject(json ?: return EMPTY)
            if (root.optInt("v") != 1) return EMPTY
            val names = root.getJSONArray("sectors").let { array -> List(array.length()) { array.getString(it) } }
            val byIsin = HashMap<String, String>()
            val counts = HashMap<Market, Map<String, Int>>()
            for ((market, key) in MARKET_KEYS) {
                val stocks = root.optJSONObject(key) ?: continue
                val count = HashMap<String, Int>()
                for (isin in stocks.keys()) {
                    val sector = names.getOrNull(stocks.getInt(isin)) ?: continue
                    byIsin[isin.uppercase(Locale.US)] = sector
                    count[sector] = (count[sector] ?: 0) + 1
                }
                counts[market] = count
            }
            val totals = root.optJSONObject("totals")?.let { t ->
                MARKET_KEYS.mapNotNull { (market, key) -> if (t.has(key)) market to t.getInt(key) else null }.toMap()
            }.orEmpty()
            SectorBook(names, byIsin, counts, totals, root.optString("updated"))
        }.getOrDefault(EMPTY)
    }
}

/**
 * Which industries the scan covers on each exchange: a set per exchange, or null for every industry, the default.
 * [SectorBook.OTHER] stands for stocks without one. Free of Android types so it can be unit tested.
 */
data class ScanSectors(val picked: Map<Market, Set<String>> = emptyMap()) {
    /** Every industry of [market] is in. */
    fun all(market: Market): Boolean = market !in picked

    fun includes(market: Market, sector: String): Boolean =
        picked[market]?.contains(sector.ifEmpty { SectorBook.OTHER }) ?: true

    /** [market] covers [sectors] only, or every industry when [sectors] is null. */
    fun with(market: Market, sectors: Set<String>?): ScanSectors =
        ScanSectors(if (sectors == null) picked - market else picked + (market to sectors))

    /**
     * Names the stocks a scan of [markets] covers, "NSE;BSE=Capital Goods|Healthcare", so a scan is only compared with
     * one of the same stocks: a scan of one industry is not "new" or "up 30 ranks" against a scan of every industry.
     */
    fun key(markets: Collection<Market>): String =
        Market.entries.filter { it in markets }.joinToString(";") { market ->
            picked[market]?.let { "${market.name}=${encode(it)}" } ?: market.name
        }

    companion object {
        private const val SEPARATOR = "|"

        /** Whether the scan [key] names left some industry out on some exchange. */
        fun narrowed(key: String): Boolean = '=' in key

        /** "Capital Goods|Healthcare|-"; the empty text is a valid "no industry at all". */
        fun encode(sectors: Set<String>): String = sectors.sorted().joinToString(SEPARATOR)

        fun decode(text: String): Set<String> = text.split(SEPARATOR).filter { it.isNotEmpty() }.toSet()
    }
}

/** How one industry is doing across a scan's stocks. */
data class SectorStrength(
    val sector: String,
    val stocks: Int,
    /** Mean score of its stocks. */
    val averageScore: Double,
    /** Mean 5-day change, percent. */
    val averageChange: Double,
    /** Share of its stocks above their 50-day average, 0–1; null when none had one. */
    val aboveSma50: Double?,
    /** How many of its stocks are STRONG. */
    val strong: Int,
) {
    companion object {
        /** Industries need this many scanned stocks to be ranked, so one lucky stock cannot top the table. */
        const val MIN_STOCKS = 3

        /** Every industry with at least [MIN_STOCKS] stocks in [results], strongest (by mean score) first. */
        fun rank(results: List<ScanResult>): List<SectorStrength> =
            results.filter { it.sector.isNotEmpty() }
                .groupBy { it.sector }
                .filterValues { it.size >= MIN_STOCKS }
                .map { (sector, stocks) ->
                    val trends = stocks.mapNotNull { it.vsSma50Pct }
                    SectorStrength(
                        sector = sector,
                        stocks = stocks.size,
                        averageScore = stocks.map { it.score }.average(),
                        averageChange = stocks.map { it.change5d }.average(),
                        aboveSma50 = if (trends.isEmpty()) null else trends.count { it >= 0.0 }.toDouble() / trends.size,
                        strong = stocks.count { StockFormat.tier(it.score) == StockFormat.Tier.STRONG },
                    )
                }
                .sortedWith(compareByDescending<SectorStrength> { it.averageScore }.thenBy { it.sector })
    }
}
