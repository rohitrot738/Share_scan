package com.rohit.share_scan

/**
 * Smart Money Concepts on daily candles (the /trader-smc skill, same rules as tools/trader.py):
 *
 * - **Fair value gap (FVG)**: three candles where the first's high is below the third's low (bullish: price left a
 *   gap going up) or the first's low above the third's high (bearish). It is filled once a later candle trades back
 *   through it.
 * - **Order block (OB)**: the last down candle before an up candle whose body is bigger than that down candle's whole
 *   range (bullish), or the reverse (bearish). It is mitigated once a later close goes through it.
 *
 * Open zones below the price tend to act as support, those above as resistance; [nearby] picks the nearest of each.
 * Free of Android types so it can be unit tested.
 */
object SmartMoney {
    enum class Kind(val bullish: Boolean) {
        BULLISH_FVG(true),
        BEARISH_FVG(false),
        BULLISH_OB(true),
        BEARISH_OB(false),
    }

    data class Zone(
        val kind: Kind,
        val low: Double,
        val high: Double,
        /** When the zone formed: the third candle of a gap, the block's own candle (epoch seconds). */
        val at: Long,
        /** Not yet filled (a gap) or mitigated (a block). */
        val open: Boolean,
    )

    /** The nearest open bullish zone at or below [price] and the nearest open bearish zone at or above it. */
    data class Nearby(val support: Zone?, val resistance: Zone?)

    const val LOOKBACK = 60

    fun fairValueGaps(candles: List<IntradayPlan.Candle>, lookback: Int = LOOKBACK): List<Zone> {
        val out = mutableListOf<Zone>()
        val start = maxOf(0, candles.size - lookback)
        for (i in start + 2 until candles.size) {
            val first = candles[i - 2]
            val third = candles[i]
            val later = candles.subList(i + 1, candles.size)
            if (first.high < third.low) {
                out += Zone(Kind.BULLISH_FVG, first.high, third.low, third.at, open = later.none { it.low <= first.high })
            } else if (first.low > third.high) {
                out += Zone(Kind.BEARISH_FVG, third.high, first.low, third.at, open = later.none { it.high >= first.low })
            }
        }
        return out
    }

    fun orderBlocks(candles: List<IntradayPlan.Candle>, lookback: Int = LOOKBACK, push: Double = 1.0): List<Zone> {
        val out = mutableListOf<Zone>()
        val start = maxOf(1, candles.size - lookback)
        for (i in start until candles.size) {
            val current = candles[i]
            val before = candles[i - 1]
            val body = current.close - current.open
            val span = before.high - before.low
            if (span <= 0.0) continue
            val later = candles.subList(i + 1, candles.size)
            if (body > push * span && before.close < before.open) {
                out += Zone(Kind.BULLISH_OB, before.low, before.high, before.at, open = later.none { it.close < before.low })
            } else if (-body > push * span && before.close > before.open) {
                out += Zone(Kind.BEARISH_OB, before.low, before.high, before.at, open = later.none { it.close > before.high })
            }
        }
        return out
    }

    /** Every open gap and block. */
    fun openZones(candles: List<IntradayPlan.Candle>): List<Zone> =
        (fairValueGaps(candles) + orderBlocks(candles)).filter { it.open }

    /**
     * The open bullish zone closest under [price] (or holding it) and the open bearish zone closest over it (or
     * holding it).
     */
    fun nearby(zones: List<Zone>, price: Double): Nearby = Nearby(
        support = zones.filter { it.open && it.kind.bullish && it.low <= price }.maxByOrNull { minOf(it.high, price) },
        resistance = zones.filter { it.open && !it.kind.bullish && it.high >= price }.minByOrNull { maxOf(it.low, price) },
    )
}
