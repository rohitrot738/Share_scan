package com.rohit.share_scan

import java.util.concurrent.atomic.AtomicIntegerArray

/**
 * How much of what it set out to check a scan got a chart for, and why the rest did not show: Yahoo had no chart for
 * the stock, it was listed too lately for a score, Yahoo kept refusing until it was given up, or the network failed.
 * The status card shows it, so a stock missing from the list is never a mystery. Free of Android types so it can be
 * unit tested.
 */
data class ScanCoverage(
    /** Stocks the scan set out to check: the exchanges and industries chosen. */
    val listed: Int,
    /** Stocks with a chart: the scan's results. */
    val charted: Int,
    val noData: Int = 0,
    val tooNew: Int = 0,
    val limited: Int = 0,
    val failed: Int = 0,
) {
    val missing: Int get() = noData + tooNew + limited + failed

    /** Why a stock has no chart, the clearest reason last: a stock with several tickers keeps its clearest. */
    enum class Miss {
        /** Yahoo has no chart for it. */
        NO_DATA,

        /** The network failed. */
        FAILED,

        /** Yahoo answered 429 every time it was asked. */
        LIMITED,

        /** Fewer sessions than a score needs ([BreakoutSignals.MIN_SESSIONS]): listed lately. */
        TOO_NEW,
    }

    /** Counts misses from the scan's threads. */
    class Tally {
        private val counts = AtomicIntegerArray(Miss.entries.size)

        fun add(miss: Miss) {
            counts.incrementAndGet(miss.ordinal)
        }

        fun coverage(listed: Int, charted: Int) = ScanCoverage(
            listed = listed,
            charted = charted,
            noData = counts[Miss.NO_DATA.ordinal],
            tooNew = counts[Miss.TOO_NEW.ordinal],
            limited = counts[Miss.LIMITED.ordinal],
            failed = counts[Miss.FAILED.ordinal],
        )
    }
}
