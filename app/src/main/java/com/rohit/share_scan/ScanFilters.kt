package com.rohit.share_scan

/** A stock attribute the user can limit, with the slider stops offered for it. */
enum class Metric(val key: String, val stops: List<Double>) {
    MARKET_CAP("mcap", listOf(100.0, 250.0, 500.0, 1_000.0, 2_500.0, 5_000.0, 10_000.0, 20_000.0, 50_000.0, 100_000.0, 200_000.0, 500_000.0)),
    PRICE("price", listOf(10.0, 20.0, 50.0, 100.0, 200.0, 500.0, 1_000.0, 2_000.0, 5_000.0, 10_000.0)),
    VOLUME("volume", listOf(10_000.0, 50_000.0, 100_000.0, 500_000.0, 1_000_000.0, 5_000_000.0, 10_000_000.0)),
    TURNOVER("turnover", listOf(0.5, 1.0, 5.0, 10.0, 25.0, 50.0, 100.0, 500.0)),
    RVOL("rvol", listOf(0.5, 1.0, 1.5, 2.0, 3.0, 5.0, 10.0)),
    CHANGE("change", listOf(-20.0, -10.0, -5.0, 0.0, 5.0, 10.0, 20.0, 50.0)),

    /** Percent below the 20-day high; negative values are breakouts above it. "≤ 3%" keeps stocks near resistance. */
    FROM_HIGH("high20", listOf(-10.0, -5.0, -2.0, 0.0, 1.0, 2.0, 3.0, 5.0, 8.0, 10.0, 15.0, 25.0)),

    /** Width of the latest 10-session base in percent; "≤ 8%" keeps tight consolidations. */
    BASE_RANGE("base", listOf(2.0, 3.0, 4.0, 5.0, 6.0, 8.0, 10.0, 12.0, 15.0, 20.0, 30.0)),

    /** Percent above (+) or below (-) the 50-day average; "≥ 0%" keeps stocks in an uptrend. */
    VS_SMA50("sma50", listOf(-20.0, -10.0, -5.0, 0.0, 5.0, 10.0, 20.0, 30.0, 50.0)),
    SCORE("score", listOf(20.0, 30.0, 40.0, 50.0, 60.0, 70.0, 80.0, 90.0));

    fun read(stock: ScanResult): Double? = when (this) {
        MARKET_CAP -> stock.marketCapCr
        PRICE -> stock.price
        VOLUME -> stock.volume
        TURNOVER -> stock.turnoverCr
        RVOL -> stock.rvol
        CHANGE -> stock.change5d
        FROM_HIGH -> stock.belowHigh20Pct
        BASE_RANGE -> stock.baseRangePct
        VS_SMA50 -> stock.vsSma50Pct
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
) {
    val activeCount: Int get() = bounds.values.count { it.isActive } + if (sectors.isEmpty()) 0 else 1

    fun bound(metric: Metric): Bound = bounds[metric] ?: Bound()

    fun with(metric: Metric, bound: Bound): ScanFilters =
        copy(bounds = if (bound.isActive) bounds + (metric to bound) else bounds - metric)

    fun accepts(stock: ScanResult): Boolean =
        (sectors.isEmpty() || stock.sector in sectors) && bounds.all { (metric, bound) -> bound.accepts(metric.read(stock)) }

    fun encode(): String = buildString {
        append("limit=").append(limit)
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

        fun decode(text: String?): ScanFilters {
            var filters = ScanFilters()
            for (part in text.orEmpty().split(';')) {
                val (key, raw) = part.split('=', limit = 2).takeIf { it.size == 2 } ?: continue
                if (key == "limit") {
                    raw.toIntOrNull()?.takeIf { it in LIMITS }?.let { filters = filters.copy(limit = it) }
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
