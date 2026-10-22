package com.rohit.share_scan

import android.content.Context
import androidx.annotation.StringRes

/** How each learned setup's condition ([SetupCondition]) reads in the app. */
object SetupLabels {
    @StringRes
    fun label(condition: SetupCondition): Int = when (condition) {
        SetupCondition.BREAKOUT_TODAY -> R.string.setup_breakout_today
        SetupCondition.NEAR_TRIGGER -> R.string.setup_near_trigger
        SetupCondition.VOLUME_2X -> R.string.setup_volume_2x
        SetupCondition.VOLUME_1_5X -> R.string.setup_volume_1_5x
        SetupCondition.QUIET_BEFORE -> R.string.setup_quiet_before
        SetupCondition.TIGHT_WEEK -> R.string.setup_tight_week
        SetupCondition.TIGHT_TWO_WEEKS -> R.string.setup_tight_two_weeks
        SetupCondition.ABOVE_SMA50 -> R.string.setup_above_sma50
        SetupCondition.SMA20_OVER_SMA50 -> R.string.setup_sma20_over_sma50
        SetupCondition.UP_20_DAYS -> R.string.setup_up_20_days
        SetupCondition.UP_5_PERCENT_WEEK -> R.string.setup_up_5_percent_week
        SetupCondition.RSI_50_70 -> R.string.setup_rsi_50_70
        SetupCondition.RSI_70_PLUS -> R.string.setup_rsi_70_plus
        SetupCondition.RSI_UNDER_50 -> R.string.setup_rsi_under_50
        SetupCondition.MACD_ABOVE_SIGNAL -> R.string.setup_macd_above_signal
        SetupCondition.MACD_FRESH_CROSS -> R.string.setup_macd_fresh_cross
        SetupCondition.MACD_ABOVE_ZERO -> R.string.setup_macd_above_zero
        SetupCondition.NEAR_SUPPORT -> R.string.setup_near_support
        SetupCondition.LONG_SUPPORT -> R.string.setup_long_support
        SetupCondition.LIQUID_10CR -> R.string.setup_liquid_10cr
        SetupCondition.SCORE_STRONG -> R.string.setup_score_strong
        SetupCondition.SCORE_WATCH -> R.string.setup_score_watch
        SetupCondition.PREV_STRONG -> R.string.setup_prev_strong
        SetupCondition.NEAR_6M_HIGH -> R.string.setup_near_6m_high
        SetupCondition.UP_4_PERCENT_TODAY -> R.string.setup_up_4_percent_today
        SetupCondition.CLOSE_NEAR_DAY_HIGH -> R.string.setup_close_near_day_high
        SetupCondition.CALM_RANGES -> R.string.setup_calm_ranges
    }

    /** "Breakout आज + volume 2×+ + RSI 50–70". */
    fun describe(context: Context, conditions: List<SetupCondition>): String =
        conditions.joinToString(" + ") { context.getString(label(it)) }
}
