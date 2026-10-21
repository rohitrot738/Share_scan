package com.rohit.share_scan

import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * A long breakout trade plan built only from price and volatility, ported from the essence of
 * `entry_risk_engine.py` (`trade_plan`). Kept free of Android types so it can be unit tested.
 *
 * Inputs are the latest close ([price]), the 20-session high (resistance), the 10-session low (support)
 * and the 14-session ATR. The rules:
 *
 * - **ATR**: the given ATR when it is a positive number, otherwise 1% of the price (the Python fallback).
 * - **Trigger** = 20D high + [TRIGGER_ATR] × ATR, the Python `confirmation_entry`: a move a tenth of an ATR
 *   above resistance confirms the breakout.
 * - **Entry** = the trigger while the price is still below it ([EntryKind.BREAKOUT]: wait, buy only on
 *   the breakout), or the current price once it is at or above the trigger ([EntryKind.MARKET]).
 *   The Python "early entry" inside the base is deliberately left out: the app scans for breakouts.
 * - **Stop-loss** starts at 10D low − [STOP_BUFFER_ATR] × ATR, the Python invalidation just under support,
 *   and is then kept between [MIN_STOP_ATR] and [MAX_STOP_ATR] ATR under the entry: never so close that a
 *   normal day's range hits it ([StopKind.MIN_ATR]; Python keeps at least 0.8 ATR), never further than the
 *   ATR stop, so the tighter of the structure stop and the 2-ATR stop wins ([StopKind.MAX_ATR]).
 * - **Risk** (1R) = entry − stop. **Targets** = entry + [TARGET_R] × R, i.e. 1.5R, 2.5R and 4R as in Python.
 * - **Time** to each target: the sessions it typically takes after the entry, from its distance in ATRs ([TargetTimes]).
 * - **Quick target** = entry + [QUICK_TARGET_R] × R and **wide stop** = entry − [WIDE_STOP_R] × R: the quick target
 *   came before the stop far more often than Target 1, and more often still before the wide stop ([BreakoutSetup]).
 *
 * [of] returns null when an input is missing or not positive, or when the stop would not be above zero.
 */
data class TradePlan(
    /** The latest close the plan was built from. */
    val price: Double,
    /** ATR used for every distance, after the fallback. */
    val atr: Double,
    val high20: Double,
    /** Breakout confirmation level: 20D high + 0.1 ATR. */
    val trigger: Double,
    val entry: Double,
    val entryKind: EntryKind,
    val stop: Double,
    val stopKind: StopKind,
    /** Ascending: 1.5R, 2.5R and 4R above the entry. */
    val targets: List<Double>,
    /** About how many sessions after the entry each of [targets] takes when it is reached ([TargetTimes.sessions]). */
    val targetSessions: List<IntRange>,
) {
    enum class EntryKind {
        /** Price is below the trigger: the entry is a buy-stop at the trigger. */
        BREAKOUT,
        /** Price is already at or above the trigger: the entry is the current price. */
        MARKET,
    }

    enum class StopKind {
        /** Just under the 10-session low. */
        STRUCTURE,
        /** The 10-session low was too far away; capped at 2 ATR under the entry. */
        MAX_ATR,
        /** The 10-session low was too close; pushed down to 1 ATR under the entry. */
        MIN_ATR,
    }

    /** Rupees at risk per share (1R). Always positive. */
    val risk: Double get() = entry - stop

    /** [risk] as a percentage of the entry. */
    val riskPercent: Double get() = risk / entry * 100.0

    /** Nearer than Target 1, and reached before the stop far more often. */
    val quickTarget: Double get() = entry + QUICK_TARGET_R * risk

    /** About how many sessions after the entry [quickTarget] takes when it is reached. */
    val quickSessions: IntRange get() = TargetTimes.sessions(QUICK_TARGET_R * risk / atr)

    /** Further than [stop]: the quick target comes first more often, but a loss is [WIDE_STOP_R] times as big. */
    val wideStop: Double get() = entry - WIDE_STOP_R * risk

    /** How many ATRs the price already is above the 20D high (negative below it). */
    val extensionAtr: Double get() = (price - high20) / atr

    /** True when the price has run so far past the breakout that buying now means chasing it. */
    val isExtended: Boolean get() = extensionAtr > EXTENDED_ATR

    /** Distance of [level] from the entry in units of risk: the stop is −1R, the first target +1.5R. */
    fun rMultiple(level: Double): Double = (level - entry) / risk

    /** Percent distance of [level] from the current price. */
    fun fromPrice(level: Double): Double = (level / price - 1.0) * 100.0

    companion object {
        const val TRIGGER_ATR = 0.10
        const val STOP_BUFFER_ATR = 0.15
        const val MIN_STOP_ATR = 1.0
        const val MAX_STOP_ATR = 2.0
        const val FALLBACK_ATR_FRACTION = 0.01
        const val EXTENDED_ATR = 2.0
        val TARGET_R = listOf(1.5, 2.5, 4.0)
        const val QUICK_TARGET_R = 0.75
        const val WIDE_STOP_R = 1.5

        private const val MINUS = "−"

        fun of(price: Double, structure: PriceStructure): TradePlan? =
            of(price, structure.high20, structure.low10, structure.atr14)

        fun of(price: Double, high20: Double, low10: Double, atr14: Double): TradePlan? {
            if (!price.isUsable() || !high20.isUsable() || !low10.isUsable()) return null
            val atr = if (atr14.isUsable()) atr14 else price * FALLBACK_ATR_FRACTION

            val trigger = high20 + TRIGGER_ATR * atr
            val broken = price >= trigger
            val entry = if (broken) price else trigger

            val structureStop = low10 - STOP_BUFFER_ATR * atr
            val widest = entry - MAX_STOP_ATR * atr
            val closest = entry - MIN_STOP_ATR * atr
            val (stop, stopKind) = when {
                structureStop < widest -> widest to StopKind.MAX_ATR
                structureStop > closest -> closest to StopKind.MIN_ATR
                else -> structureStop to StopKind.STRUCTURE
            }
            if (!(stop > 0.0 && stop < entry)) return null

            val risk = entry - stop
            val targets = TARGET_R.map { entry + it * risk }
            return TradePlan(
                price = price,
                atr = atr,
                high20 = high20,
                trigger = trigger,
                entry = entry,
                entryKind = if (broken) EntryKind.MARKET else EntryKind.BREAKOUT,
                stop = stop,
                stopKind = stopKind,
                targets = targets,
                targetSessions = targets.map { TargetTimes.sessions((it - entry) / atr) },
            )
        }

        /** "+1.5R", "−1.0R", "0.0R": one decimal, with a real minus sign and no "−0.0". */
        fun formatR(r: Double): String {
            val tenths = (r * 10.0).roundToLong()
            return sign(tenths) + String.format(Locale.US, "%d.%dR", abs(tenths) / 10, abs(tenths) % 10)
        }

        /** "+2.15%", "−6.30%", "0.00%". */
        fun formatPercent(percent: Double): String {
            val hundredths = (percent * 100.0).roundToLong()
            return sign(hundredths) + String.format(Locale.US, "%d.%02d%%", abs(hundredths) / 100, abs(hundredths) % 100)
        }

        private fun sign(rounded: Long): String = when {
            rounded > 0 -> "+"
            rounded < 0 -> MINUS
            else -> ""
        }

        private fun Double.isUsable(): Boolean = isFinite() && this > 0.0
    }
}
