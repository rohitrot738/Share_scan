package com.rohit.share_scan

/** A stock attribute the user can limit, with the slider stops offered for it. */
enum class Metric(val key: String, val stops: List<Double>) {
    MARKET_CAP("mcap", listOf(100.0, 250.0, 500.0, 1_000.0, 2_500.0, 5_000.0, 10_000.0, 20_000.0, 50_000.0, 100_000.0, 200_000.0, 500_000.0)),
    PRICE("price", listOf(10.0, 20.0, 50.0, 100.0, 200.0, 500.0, 1_000.0, 2_000.0, 5_000.0, 10_000.0)),
    VOLUME("volume", listOf(10_000.0, 50_000.0, 100_000.0, 500_000.0, 1_000_000.0, 5_000_000.0, 10_000_000.0)),
    TURNOVER("turnover", listOf(0.5, 1.0, 5.0, 10.0, 25.0, 50.0, 100.0, 500.0)),
    RVOL("rvol", listOf(0.5, 1.0, 1.5, 2.0, 3.0, 5.0, 10.0)),
    /** Five days mostly move a few percent, so stops run 1% apart within ±10% and widen beyond. */
    CHANGE("change", listOf(-20.0, -15.0) + (-10..10).map { it.toDouble() } + listOf(15.0, 20.0, 30.0, 50.0)),

    /** Percent below the 20-day high; negative values are breakouts above it. "≤ 3%" keeps stocks near resistance. */
    FROM_HIGH("high20", listOf(-10.0, -5.0, -2.0, 0.0, 1.0, 2.0, 3.0, 5.0, 8.0, 10.0, 15.0, 25.0)),

    /**
     * Width of the latest 1–4 weeks ([ScanFilters.rangeWeeks]) in percent, highest high to lowest low; "≤ 5%" keeps
     * calm stocks that went neither far up nor far down. Stops run 1% apart up to 10%.
     */
    BASE_RANGE("base", (1..10).map { it.toDouble() } + listOf(12.0, 15.0, 20.0, 25.0, 30.0)),

    /** Percent above (+) or below (-) the 50-day average; "≥ 0%" keeps stocks in an uptrend. */
    VS_SMA50("sma50", listOf(-20.0, -10.0, -5.0, 0.0, 5.0, 10.0, 20.0, 30.0, 50.0)),

    /** RSI (14): above 70 overbought, below 30 oversold; "50–70" keeps stocks with momentum that are not stretched. */
    RSI("rsi", listOf(20.0, 30.0, 40.0, 50.0, 60.0, 70.0, 80.0)),

    /** MACD line minus its signal, as a percent of the price; "≥ 0%" keeps stocks whose MACD is above its signal. */
    MACD("macd", listOf(-2.0, -1.0, -0.5, -0.25, 0.0, 0.25, 0.5, 1.0, 2.0)),

    /**
     * Sessions since the MACD crossed above its signal (0 = the latest); "≤ 3" keeps fresh bullish crossovers. A stock
     * whose MACD is below its signal has no such crossover and fails any limit here.
     */
    MACD_CROSS("macdx", listOf(0.0, 1.0, 2.0, 3.0, 5.0, 10.0, 20.0)),

    /** Sessions since the support floor was first tested ([SupportFloor.ageSessions]); stops are whole months. */
    SUPPORT_AGE("supage", List(6) { (it + 1) * SupportFloors.SESSIONS_PER_MONTH.toDouble() }),

    /** Percent the price sits above its support floor; "≤ 5%" keeps stocks close to it. */
    SUPPORT_GAP("supgap", listOf(0.0, 2.0, 5.0, 10.0, 15.0, 20.0, 30.0, 50.0)),
    SCORE("score", listOf(20.0, 30.0, 40.0, 50.0, 60.0, 70.0, 80.0, 90.0));

    /** This metric's value for [stock]; [BASE_RANGE] spans [rangeWeeks] weeks. */
    fun read(stock: ScanResult, rangeWeeks: Int = BreakoutSignals.BASE_WEEKS): Double? = when (this) {
        MARKET_CAP -> stock.marketCapCr
        PRICE -> stock.price
        VOLUME -> stock.volume
        TURNOVER -> stock.turnoverCr
        RVOL -> stock.rvol
        CHANGE -> stock.change5d
        FROM_HIGH -> stock.belowHigh20Pct
        BASE_RANGE -> stock.rangePct(rangeWeeks)
        VS_SMA50 -> stock.vsSma50Pct
        RSI -> stock.rsi14
        MACD -> stock.macdGapPct
        MACD_CROSS -> stock.macdCrossAge?.toDouble()
        SUPPORT_AGE -> stock.support?.ageSessions?.toDouble()
        SUPPORT_GAP -> stock.support?.distancePct(stock.price)
        SCORE -> stock.score
    }

    /**
     * Slider positions run from 0 to [lastPosition]: 0 means no lower limit, [lastPosition] means no upper
     * limit, and every position in between is one of [stops].
     */
    val lastPosition: Int get() = stops.size + 1

    fun valueAt(position: Int): Double? = stops.getOrNull(position - 1)

    fun positionOf(value: Double?, upper: Boolean): Int {
        if (value == null) return if (upper) lastPosition else 0
        val index = stops.indexOfFirst { it >= value }
        return if (index < 0) lastPosition else index + 1
    }
}

/** Inclusive limits; null means unlimited on that side. */
data class Bound(val min: Double? = null, val max: Double? = null) {
    val isActive: Boolean get() = min != null || max != null

    /** A stock with no value (for example an unknown market cap) fails any active limit, as in the Python scanner. */
    fun accepts(value: Double?): Boolean {
        if (!isActive) return true
        if (value == null) return false
        return (min == null || value >= min) && (max == null || value <= max)
    }
}

data class ScanFilters(
    val bounds: Map<Metric, Bound> = emptyMap(),
    /** How many of the best-scoring matches to show; 0 shows all of them. */
    val limit: Int = DEFAULT_LIMIT,
    /** Industries to keep ([ScanResult.sector]); empty keeps every stock, with or without one. */
    val sectors: Set<String> = emptySet(),
    /** Weeks [Metric.BASE_RANGE] spans, one of [RANGE_WEEKS]. */
    val rangeWeeks: Int = BreakoutSignals.BASE_WEEKS,
) {
    val activeCount: Int get() = bounds.values.count { it.isActive } + if (sectors.isEmpty()) 0 else 1

    fun bound(metric: Metric): Bound = bounds[metric] ?: Bound()

    fun with(metric: Metric, bound: Bound): ScanFilters =
        copy(bounds = if (bound.isActive) bounds + (metric to bound) else bounds - metric)

    fun accepts(stock: ScanResult): Boolean =
        (sectors.isEmpty() || stock.sector in sectors) &&
            bounds.all { (metric, bound) -> bound.accepts(metric.read(stock, rangeWeeks)) }

    fun encode(): String = buildString {
        append("limit=").append(limit)
        if (rangeWeeks != BreakoutSignals.BASE_WEEKS) append(";weeks=").append(rangeWeeks)
        for ((metric, bound) in bounds) {
            bound.min?.let { append(';').append(metric.key).append(".min=").append(it) }
            bound.max?.let { append(';').append(metric.key).append(".max=").append(it) }
        }
        // NSE's industry names hold letters, spaces, '&' and ',', never ';', '=' or '|'.
        if (sectors.isNotEmpty()) append(";sectors=").append(sectors.sorted().joinToString(SECTOR_SEPARATOR))
    }

    companion object {
        const val DEFAULT_LIMIT = 100
        private const val SECTOR_SEPARATOR = "|"
        val LIMITS = listOf(25, 50, 100, 200, 0)
        val RANGE_WEEKS = 1..BreakoutSignals.MAX_RANGE_WEEKS

        fun decode(text: String?): ScanFilters {
            var filters = ScanFilters()
            for (part in text.orEmpty().split(';')) {
                val (key, raw) = part.split('=', limit = 2).takeIf { it.size == 2 } ?: continue
                if (key == "limit") {
                    raw.toIntOrNull()?.takeIf { it in LIMITS }?.let { filters = filters.copy(limit = it) }
                    continue
                }
                if (key == "weeks") {
                    raw.toIntOrNull()?.takeIf { it in RANGE_WEEKS }?.let { filters = filters.copy(rangeWeeks = it) }
                    continue
                }
                if (key == "sectors") {
                    filters = filters.copy(sectors = raw.split(SECTOR_SEPARATOR).filter { it.isNotBlank() }.toSet())
                    continue
                }
                val metric = Metric.entries.firstOrNull { key.startsWith(it.key + ".") } ?: continue
                val value = raw.toDoubleOrNull()?.takeIf { it.isFinite() } ?: continue
                val bound = filters.bound(metric)
                filters = filters.with(metric, if (key.endsWith(".min")) bound.copy(min = value) else bound.copy(max = value))
            }
            return filters
        }
    }
}
