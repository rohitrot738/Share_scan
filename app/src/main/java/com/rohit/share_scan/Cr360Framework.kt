package com.rohit.share_scan

import com.rohit.share_scan.Cr360Book.Fact
import java.time.LocalDate

/**
 * The sector-wise valuation framework's verdict on a stock (cr360/valuation.py), read from its R/S facts: the model
 * that valued it, the intrinsic value before and after the red-flag penalties, the buy price under the margin of
 * safety, and the universal screen's flags. The operator red flag (a volume spike with no news) is the app's own, as
 * only the app has today's volume and the stock's news. Free of Android types so it can be unit tested.
 */
class Cr360Framework private constructor(private val facts: Map<Fact, Double>) {
    /** How the company was valued; [code] as in the file. */
    enum class Model(val code: Int) {
        /** The sector's P/E and P/B with the company's own P/E (the blend 360CR used before). */
        GENERAL(0),

        /** Banks, NBFCs and other financial services: adjusted book value at the company's own P/B. */
        BANK(1),

        /** Capital goods, autos and other makers: the company's own EV/EBITDA. */
        ASSET(2),

        /** Metals, mining, oil and gas: EV/EBITDA on mid-cycle EBITDA. */
        CYCLICAL(3),

        /** IT, FMCG, pharma: PEG. */
        CASH(4),
    }

    /** The screen's and the chart's findings; [bit] as in the file's "flg". */
    enum class Flag(val bit: Int) {
        REJECT(1),
        HIGH_RISK(2),
        MANIPULATION(4),
        BUY_ZONE(8),
        CYCLICAL_WARNING(16),
        WEEKLY_SWEEP(32),
        BEST(64),
        LOW_ROA(128),
        PEG_LOW(256),
        PEG_HIGH(512),
    }

    /** What cut the intrinsic value, and by how much; [bit] as in the file's "pf". */
    enum class Penalty(val bit: Int, val cutPct: Int) {
        AUDITOR(1, 40),
        DEBT(2, 20),
        INSTITUTIONS(4, 10),
        ROCE(8, 20),
    }

    /** The company's size by market value, as its margin of safety says it. */
    enum class Size { LARGE, MID, SMALL }

    val model: Model = modelOf(facts[Fact.MODEL]) ?: Model.GENERAL

    /** The sector's own model; differs from [model] when the sector's had too little data and the blend stood in. */
    val sectorModel: Model = modelOf(facts[Fact.SECTOR_MODEL]) ?: model

    /** The intrinsic value after the penalties, ₹ a share. */
    val value: Double? = facts[Fact.FAIR_VALUE]

    /** The model's own value before the penalties (a low ROCE's cut is the model's). */
    val rawValue: Double? = facts[Fact.RAW_VALUE]
    val buyPrice: Double? = facts[Fact.BUY_PRICE]
    val marginPct: Double? = facts[Fact.MARGIN_OF_SAFETY]
    val flags: Set<Flag> = bits(facts[Fact.FLAGS], Flag.entries) { it.bit }
    val penalties: Set<Penalty> = bits(facts[Fact.PENALTIES], Penalty.entries) { it.bit }

    /** The penalties applied after the model ([Penalty.ROCE] is inside [rawValue]): their multiplier. */
    val penaltyFactor: Double = penalties.filter { it != Penalty.ROCE }.fold(1.0) { f, p -> f * (1 - p.cutPct / 100.0) }

    val auditorResigned: LocalDate? = facts[Fact.AUDITOR_RESIGNED]?.let { date(it) }

    val size: Size? = marginPct?.let { m -> if (m <= 22.5) Size.LARGE else if (m <= 31.25) Size.MID else Size.SMALL }

    operator fun get(fact: Fact): Double? = facts[fact]

    fun has(flag: Flag): Boolean = flag in flags

    /** Whether the framework forbids buying: rejected, flagged, a lender earning too little, or [redFlag]. */
    fun blocked(redFlag: Boolean): Boolean = redFlag || flags.any { it in BLOCKING }

    /** The best set-up: under the buy price while sweeping a weekly demand zone, nothing blocking it. */
    fun best(redFlag: Boolean): Boolean = has(Flag.BEST) && !redFlag

    /** [price] against the buy price, %: negative under it. */
    fun fromBuy(price: Double): Double? = buyPrice?.takeIf { it > 0 && price > 0 }?.let { (price / it - 1) * 100 }

    companion object {
        val BLOCKING = setOf(Flag.REJECT, Flag.HIGH_RISK, Flag.MANIPULATION, Flag.LOW_ROA)

        /** A session's volume this many times its 20-session average is a spike. */
        const val RED_FLAG_RVOL = 3.0

        /** A spike needs an exchange filing or news within these days to explain it. */
        const val RED_FLAG_DAYS = 3

        /** The framework's verdict in a stock's R/S [facts]; null for a file built before the framework. */
        fun of(facts: Map<Fact, Double>): Cr360Framework? = if (Fact.MODEL in facts) Cr360Framework(facts) else null

        /**
         * The operator red flag: volume [rvol] times its average with no filing or news (routine notices aside) in the
         * last [RED_FLAG_DAYS] days, items [news] given by when they came (epoch ms). Not raised without the news.
         */
        fun operatorRedFlag(rvol: Double, news: List<NewsItem>?, now: Long): Boolean {
            if (news == null || !rvol.isFinite() || rvol < RED_FLAG_RVOL) return false
            val since = now - RED_FLAG_DAYS * StockNews.DAY_MS
            return news.none { !it.routine && it.at >= since }
        }

        private fun modelOf(code: Double?): Model? = code?.let { c -> Model.entries.firstOrNull { it.code.toDouble() == c } }

        private fun <T> bits(value: Double?, all: List<T>, bit: (T) -> Int): Set<T> {
            val mask = value?.toInt() ?: return emptySet()
            return all.filterTo(LinkedHashSet()) { mask and bit(it) != 0 }
        }

        private fun date(value: Double): LocalDate? = value.toInt().let { d ->
            runCatching { LocalDate.of(d / 10000, d / 100 % 100, d % 100) }.getOrNull()
        }
    }
}
