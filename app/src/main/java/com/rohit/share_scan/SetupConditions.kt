package com.rohit.share_scan

/**
 * The building blocks of the learned setups ([SetupBook]): plain yes/no facts about a stock at one session's close,
 * each one bit of a mask. The app works them out for every stock it scans and the daily learner (learner/Learn.kt)
 * for every stock and every past session from the very same [BreakoutSignals] and [TradePlan] code, so a rule learned
 * on the server means exactly the same thing on the phone. Free of Android types so it can be unit tested.
 *
 * Bits are stored in the learned file, so a condition keeps its [bit] for good; a new one takes a new bit.
 */
enum class SetupCondition(val bit: Int) {
    /** At or above the plan's trigger today, and not so far past it that buying means chasing. */
    BREAKOUT_TODAY(0),

    /** Still under the trigger, within one ATR of it. */
    NEAR_TRIGGER(1),

    /** Today's volume at least twice the 20-session average. */
    VOLUME_2X(2),

    /** Today's volume at least 1.5 times the average. */
    VOLUME_1_5X(3),

    /** Volume dried up in the five sessions before today (under 0.8 of the average). */
    QUIET_BEFORE(4),

    /** The latest week's high–low range within 5% of its midpoint. */
    TIGHT_WEEK(5),

    /** The latest two weeks' range within 8%. */
    TIGHT_TWO_WEEKS(6),

    /** Above the 50-day average. */
    ABOVE_SMA50(7),

    /** The 20-day average above the 50-day. */
    SMA20_OVER_SMA50(8),

    /** Up over the latest 20 sessions. */
    UP_20_DAYS(9),

    /** Up 5% or more over the latest 5 sessions. */
    UP_5_PERCENT_WEEK(10),

    /** RSI (14) from 50 to under 70. */
    RSI_50_70(11),

    /** RSI (14) 70 or more. */
    RSI_70_PLUS(12),

    /** RSI (14) under 50. */
    RSI_UNDER_50(13),

    /** MACD above its signal line. */
    MACD_ABOVE_SIGNAL(14),

    /** MACD crossed above its signal in the latest three sessions. */
    MACD_FRESH_CROSS(15),

    /** MACD above zero. */
    MACD_ABOVE_ZERO(16),

    /** Within 5% above a support floor. */
    NEAR_SUPPORT(17),

    /** A support floor that has stood for months ([SupportFloor.long]). */
    LONG_SUPPORT(18),

    /** ₹10 Cr or more traded on a typical session. */
    LIQUID_10CR(19),

    /** Score STRONG today. */
    SCORE_STRONG(20),

    /** Score at least WATCH today. */
    SCORE_WATCH(21),

    /** Score STRONG at the session before. */
    PREV_STRONG(22),

    /** Within 3% of the chart's six-month high. */
    NEAR_6M_HIGH(23),

    /** Up 4% or more on the day. */
    UP_4_PERCENT_TODAY(24),

    /** Closed in the top quarter of the day's range: buyers held it to the end. */
    CLOSE_NEAR_DAY_HIGH(25),

    /** Daily ranges of the 10 sessions before today at 70% of normal or less: volatility dried up. */
    CALM_RANGES(26),

    /** A liquidity sweep: under the 20-day low and back above it ([BreakoutSignals.Signals.sweptLow]). */
    SWEPT_20D_LOW(27),

    /** A stop hunt: under a tested support floor and back above it ([BreakoutSignals.Signals.stopHunt]). */
    STOP_HUNT_SUPPORT(28),

    /** Over the 20-day high but a weak close: fakeout risk ([BreakoutSignals.Signals.weakBreakout]). */
    WEAK_BREAKOUT(29),

    /** A breakout given back in the last five sessions ([BreakoutSignals.Signals.recentFakeout]). */
    RECENT_FAKEOUT(30),

    /** Today's volume under its 20-session average. */
    VOLUME_BELOW_AVERAGE(31),
    ;

    val flag: Long get() = 1L shl bit

    companion object {
        private val BY_BIT = entries.associateBy { it.bit }

        fun ofBit(bit: Int): SetupCondition? = BY_BIT[bit]

        /** The conditions in [mask], in bit order. */
        fun of(mask: Long): List<SetupCondition> = entries.filter { mask and it.flag != 0L }

        /**
         * The conditions that hold for [signals] at its latest close, with [plan] built from them and [previousScore]
         * the score at the session before (null when unknown).
         */
        fun mask(signals: BreakoutSignals.Signals, plan: TradePlan?, previousScore: Double?): Long {
            val s = signals.structure
            val price = signals.price
            var mask = 0L
            fun set(condition: SetupCondition, holds: Boolean) {
                if (holds) mask = mask or condition.flag
            }
            if (plan != null) {
                set(BREAKOUT_TODAY, plan.entryKind == TradePlan.EntryKind.MARKET && !plan.isExtended)
                set(NEAR_TRIGGER, plan.entryKind == TradePlan.EntryKind.BREAKOUT && (plan.trigger - price) <= plan.atr)
            }
            set(VOLUME_2X, signals.rvol >= 2.0)
            set(VOLUME_1_5X, signals.rvol >= 1.5)
            set(QUIET_BEFORE, signals.quietVolume < 0.8)
            set(TIGHT_WEEK, (s.weekRanges.getOrNull(0) ?: Double.MAX_VALUE) <= 5.0)
            set(TIGHT_TWO_WEEKS, (s.weekRanges.getOrNull(1) ?: Double.MAX_VALUE) <= 8.0)
            s.sma50?.let { sma50 ->
                set(ABOVE_SMA50, price > sma50)
                set(SMA20_OVER_SMA50, s.sma20 > sma50)
            }
            set(UP_20_DAYS, signals.change20 > 0.0)
            set(UP_5_PERCENT_WEEK, signals.change5d >= 5.0)
            s.rsi14?.takeIf { it.isFinite() }?.let { rsi ->
                set(RSI_50_70, rsi >= 50.0 && rsi < 70.0)
                set(RSI_70_PLUS, rsi >= 70.0)
                set(RSI_UNDER_50, rsi < 50.0)
            }
            val macd = s.macd?.takeIf { it.isFinite() }
            val signal = s.macdSignal?.takeIf { it.isFinite() }
            if (macd != null && signal != null) {
                set(MACD_ABOVE_SIGNAL, macd > signal)
                set(MACD_FRESH_CROSS, (s.macdCrossAge ?: Int.MAX_VALUE) <= 3)
                set(MACD_ABOVE_ZERO, macd > 0.0)
            }
            signals.support?.let { floor ->
                set(NEAR_SUPPORT, floor.distancePct(price) in 0.0..5.0)
                set(LONG_SUPPORT, floor.long)
            }
            set(LIQUID_10CR, signals.medianTurnoverCr >= 10.0)
            set(SCORE_STRONG, signals.score >= StockFormat.STRONG_SCORE)
            set(SCORE_WATCH, signals.score >= StockFormat.WATCH_SCORE)
            set(PREV_STRONG, (previousScore ?: 0.0) >= StockFormat.STRONG_SCORE)
            set(NEAR_6M_HIGH, signals.fromHighPct <= 3.0)
            set(UP_4_PERCENT_TODAY, signals.change1d >= 4.0)
            set(CLOSE_NEAR_DAY_HIGH, signals.dayPosition >= 0.75)
            set(CALM_RANGES, signals.calm <= 0.7)
            set(SWEPT_20D_LOW, signals.sweptLow)
            set(STOP_HUNT_SUPPORT, signals.stopHunt)
            set(WEAK_BREAKOUT, signals.weakBreakout)
            set(RECENT_FAKEOUT, signals.recentFakeout)
            set(VOLUME_BELOW_AVERAGE, signals.rvol < 1.0)
            return mask
        }
    }
}
