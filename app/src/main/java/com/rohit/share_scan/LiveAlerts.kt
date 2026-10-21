package com.rohit.share_scan

/**
 * What a scan found that the scan before it did not, for live mode's notification: stocks that turned STRONG and
 * stocks that broke above their breakout trigger. Only stocks inside the user's filters and near the top of the list
 * count, so the notification stays about the list the user actually reads. Free of Android types so it can be unit
 * tested.
 */
object LiveAlerts {
    /** Turning STRONG counts only this high up the list (inside the filters). */
    const val TOP = 25

    /** A breakout counts this far down, and only for STRONG or WATCH stocks. */
    const val BREAKOUT_TOP = 50

    data class Find(
        val symbol: String,
        val score: Double,
        val price: Double,
        /** Price crossed the breakout trigger since the previous scan. */
        val breakout: Boolean,
        /** Turned STRONG since the previous scan, or is new to the top of the list as a STRONG stock. */
        val newStrong: Boolean,
        /** The latest session's relative volume; a breakout on [BreakoutSetup.VOLUME_RVOL] or more held more often. */
        val rvol: Double = 0.0,
    )

    /** Finds in [current] against [previous], best score first; nothing without a previous scan to compare with. */
    fun fresh(previous: List<ScanResult>, current: List<ScanResult>, filters: ScanFilters): List<Find> {
        if (previous.isEmpty()) return emptyList()
        val before = previous.associateBy { it.symbol }
        val ranked = current.filter(filters::accepts).sortedByDescending { it.score }
        val finds = ArrayList<Find>()
        ranked.take(BREAKOUT_TOP).forEachIndexed { index, stock ->
            val old = before[stock.symbol]
            val tier = StockFormat.tier(stock.score)
            val newStrong = index < TOP && tier == StockFormat.Tier.STRONG &&
                (old == null || StockFormat.tier(old.score) != StockFormat.Tier.STRONG)
            val breakout = tier != StockFormat.Tier.WEAK && old != null && crossed(old, stock)
            if (newStrong || breakout) finds += Find(stock.symbol, stock.score, stock.price, breakout, newStrong, stock.rvol)
        }
        return finds
    }

    /** Below the breakout trigger in [old], at or above the trigger [old] set in [now]. */
    private fun crossed(old: ScanResult, now: ScanResult): Boolean {
        val plan = old.structure?.let { TradePlan.of(old.price, it) } ?: return false
        return plan.entryKind == TradePlan.EntryKind.BREAKOUT && now.price >= plan.trigger
    }
}
