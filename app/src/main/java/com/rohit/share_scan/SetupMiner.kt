package com.rohit.share_scan

import java.util.BitSet
import kotlin.math.sqrt

/**
 * Learns which setups work from what happened to every trade plan across the market (the daily learner,
 * learner/Learn.kt, feeds it). A setup is one to [MAX_CONDITIONS] [SetupCondition]s together; its success rate is the
 * share of its plans whose Target 1 came within the plan's sessions and before the stop ([PlanOutcome]).
 *
 * The plans are split by date: the latest [HOLDOUT_SESSIONS] sessions are kept out of the search, so a setup must win
 * on plans it was not picked on too. A new setup is added when, with enough plans on both sides, it beats the market's
 * own rate by [ADD_MARGIN] (on the lower bound of its rate before the holdout, and outright in the holdout). A setup in
 * use stays while its holdout rate still beats the market by [KEEP_MARGIN], so one weak week does not throw it out;
 * otherwise it is removed. Setups that mostly pick the same plans as a better one are left out, and at most
 * [MAX_RULES] are in use. Free of Android types so it can be unit tested.
 */
object SetupMiner {
    const val MAX_CONDITIONS = 3
    const val MIN_TRAIN = 150
    const val MIN_HOLDOUT = 30
    const val ADD_MARGIN = 0.05
    const val KEEP_MARGIN = 0.02
    const val MAX_RULES = 12
    const val HOLDOUT_SESSIONS = 60

    /** Two setups whose plans overlap this much are the same trades; only the better one is used. */
    const val OVERLAP = 0.7

    /** One decided plan: the conditions at its close, its session (epoch day) and whether it succeeded. */
    data class Sample(val mask: Long, val day: Long, val win: Boolean)

    data class Stats(val n: Int, val wins: Int) {
        val rate: Double get() = if (n == 0) 0.0 else wins.toDouble() / n
    }

    /** A setup in use: its conditions, its record before and in the holdout, and the day it was added. */
    data class Rule(val mask: Long, val train: Stats, val holdout: Stats, val since: Long) {
        val all: Stats get() = Stats(train.n + holdout.n, train.wins + holdout.wins)
        val conditions: List<SetupCondition> get() = SetupCondition.of(mask)
    }

    /** A setup added or removed on [day]; [replaced] when a better setup took over its trades. */
    data class Change(val day: Long, val added: Boolean, val mask: Long, val train: Stats, val holdout: Stats, val replaced: Boolean = false)

    /** How much more often a condition held among the successes than among all plans; [rate] is its plans' success rate. */
    data class Lift(val condition: SetupCondition, val lift: Double, val rate: Double, val n: Int)

    data class Result(
        val rules: List<Rule>,
        val changes: List<Change>,
        val train: Stats,
        val holdout: Stats,
        /** The first session of the holdout. */
        val holdoutFrom: Long,
        val lift: List<Lift>,
    )

    /** The setups to use from [samples], given [previous] (mask to the day it was added) and today's epoch [day]. */
    fun learn(samples: List<Sample>, previous: Map<Long, Long>, day: Long): Result {
        val days = samples.map { it.day }.distinct().sorted()
        val holdoutFrom = if (days.size > HOLDOUT_SESSIONS * 2) days[days.size - HOLDOUT_SESSIONS] else Long.MAX_VALUE
        val n = samples.size
        val train = BitSet(n)
        val holdout = BitSet(n)
        val wins = BitSet(n)
        val byCondition = SetupCondition.entries.associateWith { BitSet(n) }
        samples.forEachIndexed { i, s ->
            if (s.day >= holdoutFrom) holdout.set(i) else train.set(i)
            if (s.win) wins.set(i)
            for ((condition, set) in byCondition) if (s.mask and condition.flag != 0L) set.set(i)
        }
        val trainBase = stats(train, train, wins)
        val holdoutBase = stats(holdout, holdout, wins)

        fun candidate(mask: Long, set: BitSet) = Candidate(mask, set, stats(set, train, wins), stats(set, holdout, wins))

        val conditions = SetupCondition.entries
        val found = ArrayList<Candidate>()
        fun consider(mask: Long, set: BitSet) {
            val c = candidate(mask, set)
            val isNew = mask !in previous
            val passes = if (isNew) {
                c.before.n >= MIN_TRAIN && c.inHoldout.n >= MIN_HOLDOUT &&
                    wilsonLower(c.before) >= trainBase.rate + ADD_MARGIN && c.inHoldout.rate >= holdoutBase.rate + ADD_MARGIN
            } else {
                c.inHoldout.n >= MIN_HOLDOUT / 2 && c.before.rate >= trainBase.rate && c.inHoldout.rate >= holdoutBase.rate + KEEP_MARGIN
            }
            if (passes) found += c
        }
        for (i in conditions.indices) {
            val a = byCondition.getValue(conditions[i])
            if (a.cardinality() < MIN_TRAIN) continue
            consider(conditions[i].flag, a)
            if (MAX_CONDITIONS < 2) continue
            for (j in i + 1 until conditions.size) {
                val ab = (a.clone() as BitSet).apply { and(byCondition.getValue(conditions[j])) }
                if (ab.cardinality() < MIN_TRAIN) continue
                consider(conditions[i].flag or conditions[j].flag, ab)
                if (MAX_CONDITIONS < 3) continue
                for (k in j + 1 until conditions.size) {
                    val abc = (ab.clone() as BitSet).apply { and(byCondition.getValue(conditions[k])) }
                    if (abc.cardinality() < MIN_TRAIN) continue
                    consider(conditions[i].flag or conditions[j].flag or conditions[k].flag, abc)
                }
            }
        }

        // Setups already in use come first, so a newcomer has to be clearly different to join them.
        val ordered = found.sortedWith(
            compareByDescending<Candidate> { it.mask in previous }
                .thenByDescending { it.rank }
                .thenBy { java.lang.Long.bitCount(it.mask) },
        )
        val chosen = ArrayList<Candidate>()
        for (c in ordered) {
            if (chosen.size >= MAX_RULES) break
            if (chosen.any { overlap(it.set, c.set) >= OVERLAP }) continue
            chosen += c
        }
        val rules = chosen.sortedByDescending { it.rank }.map { Rule(it.mask, it.before, it.inHoldout, previous[it.mask] ?: day) }

        val chosenMasks = rules.map { it.mask }.toSet()
        val foundByMask = found.associateBy { it.mask }
        val changes = ArrayList<Change>()
        for (r in rules) if (r.mask !in previous) changes += Change(day, true, r.mask, r.train, r.holdout)
        for (mask in previous.keys) {
            if (mask in chosenMasks) continue
            // Still good but covered by a better setup, or no longer good enough.
            val still = foundByMask[mask]
            val now = still ?: candidate(mask, setOf(mask, byCondition, n))
            changes += Change(day, false, mask, now.before, now.inHoldout, replaced = still != null)
        }

        val all = BitSet(n).apply { set(0, n) }
        val allBase = stats(all, all, wins)
        val lift = conditions.mapNotNull { condition ->
            val set = byCondition.getValue(condition)
            val withIt = stats(set, all, wins)
            if (withIt.n < MIN_TRAIN || allBase.rate == 0.0) null else Lift(condition, withIt.rate / allBase.rate, withIt.rate, withIt.n)
        }.sortedByDescending { it.lift }

        return Result(rules, changes, trainBase, holdoutBase, holdoutFrom, lift)
    }

    /** A setup found in the search, with the plans it picks ([set]) and its record before and in the holdout. */
    private class Candidate(val mask: Long, val set: BitSet, val before: Stats, val inHoldout: Stats) {
        val rank: Double = wilsonLower(Stats(before.n + inHoldout.n, before.wins + inHoldout.wins))
    }

    /** Lower bound of the 95% Wilson interval: a rate that small samples cannot fake. */
    fun wilsonLower(s: Stats): Double {
        if (s.n == 0) return 0.0
        val z = 1.96
        val p = s.rate
        val n = s.n.toDouble()
        val centre = p + z * z / (2 * n)
        val margin = z * sqrt(p * (1 - p) / n + z * z / (4 * n * n))
        return (centre - margin) / (1 + z * z / n)
    }

    private fun stats(set: BitSet, part: BitSet, wins: BitSet): Stats {
        val inPart = (set.clone() as BitSet).apply { and(part) }
        val n = inPart.cardinality()
        inPart.and(wins)
        return Stats(n, inPart.cardinality())
    }

    /** Shared plans as a share of the smaller setup's. */
    private fun overlap(a: BitSet, b: BitSet): Double {
        val smaller = minOf(a.cardinality(), b.cardinality())
        if (smaller == 0) return 0.0
        val both = (a.clone() as BitSet).apply { and(b) }.cardinality()
        return both.toDouble() / smaller
    }

    private fun setOf(mask: Long, byCondition: Map<SetupCondition, BitSet>, n: Int): BitSet {
        val set = BitSet(n).apply { set(0, n) }
        for (condition in SetupCondition.of(mask)) set.and(byCondition.getValue(condition))
        return set
    }
}
