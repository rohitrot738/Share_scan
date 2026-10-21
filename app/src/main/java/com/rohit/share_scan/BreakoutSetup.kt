package com.rohit.share_scan

/**
 * How a stock's breakout measures up to what held in the past, and how often the trade plan's quick target came
 * before the stop for breakouts like it. Probed on real NSE charts (October 2026): 1,800 stocks and about 5,400
 * plans, each entered on its breakout and watched 40 sessions. Breakouts on at least [VOLUME_RVOL] times the usual
 * volume reached their targets more often, and more often still when the stock was STRONG the session before. The
 * figures are one year's; they are no promise. Free of Android types so it can be unit tested.
 */
object BreakoutSetup {
    /** A breakout session's volume against the usual, at or above which breakouts held more often. */
    const val VOLUME_RVOL = 2.0

    enum class Kind(
        /** Percent of past plans whose quick target came before the plan's stop, and before the wide stop. */
        val quickHits: Int,
        val quickHitsWide: Int,
    ) {
        /** Broke out this session on at least [VOLUME_RVOL] times the usual volume, and was STRONG the session before. */
        HIGH_PROBABILITY(73, 80),

        /** Broke out this session on at least [VOLUME_RVOL] times the usual volume. */
        VOLUME(62, 70),

        /** Broke out this session on less than [VOLUME_RVOL] times the usual volume. */
        LOW_VOLUME(50, 61),

        /** Still under the trigger: the breakout's volume is not known yet; these are all breakouts' figures. */
        WAITING(57, 66),

        /** So far past the 20-day high that buying now means chasing it: no setup to speak of. */
        EXTENDED(57, 66),
    }

    /**
     * [stock]'s setup under [plan]. Above the trigger the latest session is the breakout: the trigger sits over the
     * 20 sessions before it. The score the session before is the plan's, as the probe had it.
     */
    fun of(stock: ScanResult, plan: TradePlan): Kind = when {
        plan.entryKind == TradePlan.EntryKind.BREAKOUT -> Kind.WAITING
        plan.isExtended -> Kind.EXTENDED
        stock.rvol < VOLUME_RVOL -> Kind.LOW_VOLUME
        (stock.pastScores.firstOrNull()?.score ?: stock.score) >= StockFormat.STRONG_SCORE -> Kind.HIGH_PROBABILITY
        else -> Kind.VOLUME
    }
}
