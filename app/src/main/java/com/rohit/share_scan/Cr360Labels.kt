package com.rohit.share_scan

import androidx.annotation.ColorRes
import androidx.annotation.StringRes

/** The verdict as the app names it. */
@get:StringRes
val Cr360Book.State.label: Int
    get() = when (this) {
        Cr360Book.State.HIGH_CONVICTION -> R.string.cr360_high_conviction
        Cr360Book.State.POSITIVE -> R.string.cr360_positive
        Cr360Book.State.NEUTRAL -> R.string.cr360_neutral
        Cr360Book.State.CAUTION -> R.string.cr360_caution
        Cr360Book.State.AVOID -> R.string.cr360_avoid
        Cr360Book.State.INSUFFICIENT_DATA -> R.string.cr360_insufficient
    }

/** Green for the strong verdicts, amber for the middle, red for the weak, grey without data. */
@get:ColorRes
val Cr360Book.State.tone: Int
    get() = when (this) {
        Cr360Book.State.HIGH_CONVICTION, Cr360Book.State.POSITIVE -> R.color.bull
        Cr360Book.State.NEUTRAL -> R.color.warn
        Cr360Book.State.CAUTION, Cr360Book.State.AVOID -> R.color.bear
        Cr360Book.State.INSUFFICIENT_DATA -> R.color.neutral
    }

@get:StringRes
val Cr360Book.Part.label: Int
    get() = when (this) {
        Cr360Book.Part.FINANCIAL_QUALITY -> R.string.cr360_part_financial_quality
        Cr360Book.Part.GROWTH -> R.string.cr360_part_growth
        Cr360Book.Part.CASHFLOW -> R.string.cr360_part_cashflow
        Cr360Book.Part.BALANCE_SHEET -> R.string.cr360_part_balance_sheet
        Cr360Book.Part.OWNERSHIP -> R.string.cr360_part_ownership
        Cr360Book.Part.INSIDER_DEALS -> R.string.cr360_part_insider_deals
        Cr360Book.Part.VALUATION -> R.string.cr360_part_valuation
        Cr360Book.Part.PRICE -> R.string.cr360_part_price
        Cr360Book.Part.STABILITY -> R.string.cr360_part_stability
        Cr360Book.Part.TECHNICAL_RISK -> R.string.cr360_part_technical_risk
    }
