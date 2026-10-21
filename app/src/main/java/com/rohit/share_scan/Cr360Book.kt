package com.rohit.share_scan

import org.json.JSONObject

/**
 * Every stock's 360CR (360 Degree Conviction Research), from the file the repository's daily job builds
 * (cr360/build_app_cache.py): the company's results, cash flow, debt, shareholding, insider and bulk/block deals,
 * valuation and three years of price, scored by the repository's Python 360CR engine. Free of Android types so it can
 * be unit tested.
 */
class Cr360Book(
    /** When the file was built (ISO date and time, IST); empty when unknown. */
    val updated: String,
    private val entries: Map<String, Entry>,
) {
    val size: Int get() = entries.size

    /** The stock's 360CR; null when the file does not have it. */
    fun of(symbol: String): Entry? = entries[symbol]

    /** 360CR's verdict, from its score; [INSUFFICIENT_DATA] when too little of the company's data was found. */
    enum class State(val key: String) {
        HIGH_CONVICTION("HIGH_CONVICTION"),
        POSITIVE("POSITIVE"),
        NEUTRAL("NEUTRAL"),
        CAUTION("CAUTION"),
        AVOID("AVOID"),
        INSUFFICIENT_DATA("INSUFFICIENT_DATA"),
    }

    /** 360CR's ten parts, in its order, with each part's weight out of 100. */
    enum class Part(val key: String, val weight: Int) {
        FINANCIAL_QUALITY("fq", 18),
        GROWTH("gr", 12),
        CASHFLOW("cf", 10),
        BALANCE_SHEET("bs", 10),
        OWNERSHIP("ow", 12),
        INSIDER_DEALS("ev", 8),
        VALUATION("va", 12),
        PRICE("pr", 8),
        STABILITY("sb", 5),
        TECHNICAL_RISK("tr", 5),
    }

    data class Entry(
        /** 0 to 100; null when nothing could be scored. */
        val score: Double?,
        val state: State,
        /** Share of 360CR's weight its data covered, 0 to 100. */
        val confidence: Double,
        /** End of the latest quarter with results ("2026-06-30"); empty when unknown. */
        val quarter: String,
        /** The day the company's page was last read; empty when unknown. */
        val checked: String,
        /** Each part's score, 0 to 100; a part without data is missing. */
        val parts: Map<Part, Int>,
        /** The figures behind the score ([Fact]); a figure without data is missing. */
        val facts: Map<Fact, Double>,
    )

    /** The figures the daily job keeps with each score; keys as in the file. */
    enum class Fact(val key: String) {
        QUARTERS("nq"),
        PROFITABLE_QUARTERS("pq"),
        REVENUE_CHANGE("rv"),
        PROFIT_CHANGE("np"),
        NET_MARGIN("nm"),
        PROMOTER("pm"),
        PROMOTER_CHANGE("pmc"),
        FII_CHANGE("fic"),
        DII_CHANGE("dic"),
        INSIDER_BUY_CR("ib"),
        INSIDER_SELL_CR("is"),
        DEAL_BUY_CR("bb"),
        DEAL_SELL_CR("bsl"),
        PE("pe"),
        FAIR_VALUE("fv"),
        UPSIDE("up"),
        POSITION_3Y("pos"),
        VOLATILITY("vol"),
        DEBT_TO_EQUITY("de"),

        /** 1 when the company reports half-yearly (most SMEs): [QUARTERS] and [PROFITABLE_QUARTERS] count half-years. */
        HALF_YEARLY("hy"),
    }

    companion object {
        val EMPTY = Cr360Book("", emptyMap())

        private val STATES = State.entries.associateBy { it.key }
        private val PARTS = Part.entries.associateBy { it.key }
        private val FACTS = Fact.entries.associateBy { it.key }

        /** The book in [json]; [EMPTY] for null, broken or unexpected input. */
        fun parse(json: String?): Cr360Book {
            if (json.isNullOrBlank()) return EMPTY
            return runCatching {
                val root = JSONObject(json)
                if (root.optInt("v") != 1) return EMPTY
                val stocks = root.optJSONObject("stocks") ?: return EMPTY
                val entries = HashMap<String, Entry>(stocks.length() * 2)
                for (symbol in stocks.keys()) {
                    val row = stocks.optJSONObject(symbol) ?: continue
                    entries[symbol] = Entry(
                        score = row.optDouble("s").takeIf { it.isFinite() },
                        state = STATES[row.optString("st")] ?: State.INSUFFICIENT_DATA,
                        confidence = row.optDouble("c").takeIf { it.isFinite() } ?: 0.0,
                        quarter = row.optString("q").takeIf { it != "null" }.orEmpty(),
                        checked = row.optString("d").takeIf { it != "null" }.orEmpty(),
                        parts = numbers(row.optJSONObject("sec"), PARTS).mapValues { (_, v) -> Math.round(v).toInt() },
                        facts = numbers(row.optJSONObject("f"), FACTS),
                    )
                }
                Cr360Book(root.optString("updated"), entries)
            }.getOrDefault(EMPTY)
        }

        private fun <K> numbers(obj: JSONObject?, keys: Map<String, K>): Map<K, Double> {
            obj ?: return emptyMap()
            val out = LinkedHashMap<K, Double>()
            for (name in obj.keys()) {
                val key = keys[name] ?: continue
                val value = obj.optDouble(name)
                if (value.isFinite()) out[key] = value
            }
            return out
        }
    }
}
