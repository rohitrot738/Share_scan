package com.rohit.share_scan

import com.rohit.share_scan.Cr360Book.Fact

/**
 * A long-term checklist of a company from its own numbers in the R/S file (the /long-moat and /long-screener skills):
 * returns on capital, debt, interest cover, cash behind the profit, steady profits and margins, the promoters' stake and
 * whether they sell or pledge, and no auditor walking out. Each check passes, fails, or has no data. Free of Android
 * types so it can be unit tested.
 */
object Moat {
    enum class Check {
        ROCE,
        DEBT,
        INTEREST_COVER,
        CASH,
        STEADY_PROFIT,
        MARGIN,
        PROMOTER,
        PROMOTER_HOLDING,
        PLEDGE,
        AUDITOR,
    }

    /** A check's result: [pass] null when the file has no figure for it; [value] the figure it looked at. */
    data class Line(val check: Check, val pass: Boolean?, val value: Double?)

    const val MIN_ROCE = 15.0
    const val MAX_DEBT_TO_EQUITY = 0.5
    const val MIN_INTEREST_COVER = 3.0
    const val MIN_CASH_TO_PROFIT = 0.8
    const val MIN_MARGIN = 10.0
    const val MIN_PROMOTER = 50.0

    /** Profitable in at least this share of the quarters on file. */
    const val MIN_PROFITABLE_SHARE = 0.875

    /** The quality screen's bar (/long-screener) for a growth figure, in percent. */
    const val MIN_GROWTH = 12.0

    fun lines(facts: Map<Fact, Double>): List<Line> {
        fun line(check: Check, value: Double?, pass: (Double) -> Boolean) = Line(check, value?.let(pass), value)
        val quarters = facts[Fact.QUARTERS]?.takeIf { it > 0.0 }
        val profitable = facts[Fact.PROFITABLE_QUARTERS]
        return listOf(
            line(Check.ROCE, facts[Fact.ROCE]) { it >= MIN_ROCE },
            line(Check.DEBT, facts[Fact.DEBT_TO_EQUITY]) { it <= MAX_DEBT_TO_EQUITY },
            line(Check.INTEREST_COVER, facts[Fact.INTEREST_COVER]) { it >= MIN_INTEREST_COVER },
            line(Check.CASH, facts[Fact.CASH_TO_PROFIT]) { it >= MIN_CASH_TO_PROFIT },
            line(Check.STEADY_PROFIT, if (quarters != null && profitable != null) profitable / quarters else null) { it >= MIN_PROFITABLE_SHARE },
            line(Check.MARGIN, facts[Fact.NET_MARGIN]) { it >= MIN_MARGIN },
            line(Check.PROMOTER, facts[Fact.PROMOTER]) { it >= MIN_PROMOTER },
            line(Check.PROMOTER_HOLDING, facts[Fact.PROMOTER_CHANGE]) { it >= 0.0 },
            line(Check.PLEDGE, facts[Fact.PLEDGE]) { it <= 0.0 },
            // A resignation's day is on file only when there was one; otherwise the company kept its auditor.
            Line(Check.AUDITOR, facts[Fact.AUDITOR_RESIGNED] == null, facts[Fact.AUDITOR_RESIGNED]),
        )
    }

    /** How many checks passed, out of those with data. */
    fun score(lines: List<Line>): Pair<Int, Int> = lines.count { it.pass == true } to lines.count { it.pass != null }

    /**
     * The quality screen (/long-screener) for the list's filter: ROCE of [MIN_ROCE]% or more, debt-to-equity of
     * [MAX_DEBT_TO_EQUITY] or less, promoters holding [MIN_PROMOTER]% or more, no pledge, and EPS growing [MIN_GROWTH]%
     * or more where the file has it. A company without ROCE, debt or promoter figures does not pass.
     */
    fun quality(facts: Map<Fact, Double>): Boolean {
        val roce = facts[Fact.ROCE] ?: return false
        val debt = facts[Fact.DEBT_TO_EQUITY] ?: return false
        val promoter = facts[Fact.PROMOTER] ?: return false
        return roce >= MIN_ROCE && debt <= MAX_DEBT_TO_EQUITY && promoter >= MIN_PROMOTER &&
            (facts[Fact.PLEDGE] ?: 0.0) <= 0.0 && (facts[Fact.EPS_GROWTH]?.let { it >= MIN_GROWTH } ?: true)
    }
}
