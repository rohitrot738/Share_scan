package com.rohit.share_scan

import java.util.Locale

/**
 * Industries from NSE's Nifty Total Market list, the 750 biggest NSE stocks, each with NSE's industry
 * ("Capital Goods", "Healthcare"…). Smaller stocks are not on it and stay without a sector. Free of Android types so
 * it can be unit tested.
 */
class SectorBook(private val bySymbol: Map<String, String>, private val byIsin: Map<String, String>) {
    val size: Int get() = bySymbol.size

    /** The industry of an NSE symbol, or of a BSE share through its [isin]; empty when neither is listed. */
    fun of(symbol: String, isin: String = ""): String =
        bySymbol[symbol.uppercase(Locale.US)] ?: byIsin[isin.uppercase(Locale.US)].orEmpty()

    companion object {
        val EMPTY = SectorBook(emptyMap(), emptyMap())

        /** Reads an NSE index constituents file: "Company Name,Industry,Symbol,Series,ISIN Code". */
        fun parse(csv: String): SectorBook {
            val lines = csv.trimStart(Char(0xFEFF)).lineSequence().filter { it.isNotBlank() }.iterator()
            if (!lines.hasNext()) return EMPTY
            val header = NseScanner.splitCsv(lines.next()).map { it.trim().uppercase(Locale.US) }
            val industryAt = header.indexOf("INDUSTRY")
            val symbolAt = header.indexOf("SYMBOL")
            val isinAt = header.indexOf("ISIN CODE")
            if (industryAt < 0 || symbolAt < 0) return EMPTY
            val bySymbol = HashMap<String, String>()
            val byIsin = HashMap<String, String>()
            for (line in lines) {
                val cells = NseScanner.splitCsv(line)
                val industry = cells.getOrNull(industryAt)?.trim().orEmpty()
                val symbol = cells.getOrNull(symbolAt)?.trim()?.uppercase(Locale.US).orEmpty()
                if (industry.isEmpty() || symbol.isEmpty()) continue
                bySymbol[symbol] = industry
                val isin = if (isinAt >= 0) cells.getOrNull(isinAt)?.trim()?.uppercase(Locale.US).orEmpty() else ""
                if (isin.isNotEmpty()) byIsin[isin] = industry
            }
            return SectorBook(bySymbol, byIsin)
        }
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
