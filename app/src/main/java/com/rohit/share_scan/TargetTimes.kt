package com.rohit.share_scan

import kotlin.math.max
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * About how long a [TradePlan] target takes after the entry, from how many ATRs above the entry it sits. Free of
 * Android types so it can be unit tested.
 *
 * Fitted on real NSE charts (October 2026). On 800 random stocks a plan was drawn up three months before the chart's
 * end and each target watched for 40 sessions after the entry. Among the targets reached, the sessions taken grew as
 * ATRs^[EXPONENT], half of them between [FAST] and [SLOW] times that. On 400 other stocks the same range held 45–60% of
 * the targets reached. A stock's own past speed predicted no better, so only the distance counts. It says nothing of
 * whether a target is reached: within 40 sessions about half the first targets were, a third of the second and a
 * sixth of the third, the stop often coming first.
 */
object TargetTimes {
    const val EXPONENT = 0.77
    const val FAST = 2.0
    const val SLOW = 6.6

    /** The sessions after the entry within which a target [atrs] ATRs above it is typically reached. */
    fun sessions(atrs: Double): IntRange {
        val scale = atrs.coerceAtLeast(0.0).pow(EXPONENT)
        val fast = max(1, (FAST * scale).roundToInt())
        val slow = max(fast + 1, (SLOW * scale).roundToInt())
        return fast..slow
    }
}
