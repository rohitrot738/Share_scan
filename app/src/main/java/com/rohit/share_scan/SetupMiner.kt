package com.rohit.share_scan

import java.util.BitSet
import kotlin.math.sqrt

/**
 * Learns which setups work, and which mistakes to avoid, from what happened to every trade plan across the market (the
 * daily learner, learner/Learn.kt, feeds it). A setup is one to [MAX_CONDITIONS] [SetupCondition]s together; its
 * success rate is the share of its plans whose Target 1 came within the plan's sessions and before the stop
 * ([PlanOutcome]); a failure either hit the stop first or saw the target too late.
 *
 * The plans are split by date: the latest [HOLDOUT_SESSIONS] sessions are kept out of the search, so a setup must hold
 * up on plans it was not picked on too. A new winning setup is added when, with enough plans on both sides, it beats
 * the market's own rate by [ADD_MARGIN] (on the lower bound of its rate before the holdout, and outright in the
 * holdout); a mistake ([Result.avoid]) is the same the other way round: it falls short of the market by [ADD_MARGIN],
 * on the upper bound before the holdout and outright in it. Each condition of a new setup must also add [MIN_GAIN] to
 * the rest, so a setup is not the same trades sliced by chance. A setup in use stays while its holdout rate still
 * beats (or for a mistake, still trails) the market by [KEEP_MARGIN], so one odd week does not throw it out; otherwise
 * it is removed. Setups that mostly pick the same plans as a better one are left out; at most [MAX_RULES] winners and
 * [MAX_AVOID] mistakes are kept. Free of Android types so it can be unit tested.
 */
object SetupMiner {
    const val MAX_CONDITIONS = 3
    const val MIN_TRAIN = 150
    const val MIN_HOLDOUT = 30
    const val ADD_MARGIN = 0.05
    const val KEEP_MARGIN = 0.02
    const val MAX_RULES = 12
    const val MAX_AVOID = 8

    /** How much each condition of a setup must add to the rest (see [learn]). */
    const val MIN_GAIN = 0.03
    const val HOLDOUT_SESSIONS = 60

    /** Two setups whose plans overlap this much are the same trades; only the better one is used. */
    const val OVERLAP = 0.7

    /** One decided plan: the conditions at its close, its session (epoch day), and whether it won or hit its stop. */
    data class Sample(val mask: Long, val day: Long, val win: Boolean, val stopped: Boolean = false)

    /** [n] plans, [wins] of them successes and [stops] of them failures at the stop (the rest came too late). */
    data class Stats(val n: Int, val wins: Int, val stops: Int = 0) {
        val rate: Double get() = if (n == 0) 0.0 else wins.toDouble() / n
        val stopRate: Double get() = if (n == 0) 0.0 else stops.toDouble() / n
        operator fun plus(other: Stats) = Stats(n + other.n, wins + other.wins, stops + other.stops)
    }

    /** A setup in use: its conditions, its record before and in the holdout, and the day it was added. */
    data class Rule(val mask: Long, val train: Stats, val holdout: Stats, val since: Long) {
        val all: Stats get() = train + holdout
        val conditions: List<SetupCondition> get() = SetupCondition.of(mask)
    }

    /** A setup added or removed on [day]; [replaced] when a better one took over its trades; [avoid] for a mistake. */
    data class Change(
        val day: Long,
        val added: Boolean,
        val mask: Long,
        val train: Stats,
        val holdout: Stats,
        val replaced: Boolean = false,
        val avoid: Boolean = false,
    )

    /**
     * How much more often a condition held among the successes (or, in [Result.loseLift], among the plans stopped out)
     * than among all plans; [rate] is its plans' success (or stop) rate.
     */
    data class Lift(val condition: SetupCondition, val lift: Double, val rate: Double, val n: Int)

    data class Result(
        /** Setups that won more often than the market, best first. */
        val rules: List<Rule>,
        /** Mistakes: setups whose plans failed more often than the market's, worst first. */
        val avoid: List<Rule>,
        val changes: List<Change>,
        val train: Stats,
        val holdout: Stats,
        /** The first session of the holdout. */
        val holdoutFrom: Long,
        /** Conditions most common among the successes. */
        val lift: List<Lift>,
        /** Conditions most common among the plans that hit their stop. */
        val loseLift: List<Lift>,
    )

    /**
     * The setups and mistakes to use from [samples], given the ones in use ([previous] and [previousAvoid]: mask to
     * the day it was added) and today's epoch [day].
     */
    fun learn(samples: List<Sample>, previous: Map<Long, Long>, day: Long, previousAvoid: Map<Long, Long> = emptyMap()): Result {
        val days = samples.map { it.day }.distinct().sorted()
        val holdoutFrom = if (days.size > HOLDOUT_SESSIONS * 2) days[days.size - HOLDOUT_SESSIONS] else Long.MAX_VALUE
        val n = samples.size
        val train = BitSet(n)
        val holdout = BitSet(n)
        val outcome = Outcomes(BitSet(n), BitSet(n))
        val byCondition = SetupCondition.entries.associateWith { BitSet(n) }
        samples.forEachIndexed { i, s ->
            if (s.day >= holdoutFrom) holdout.set(i) else train.set(i)
            if (s.win) outcome.wins.set(i)
            if (s.stopped) outcome.stops.set(i)
            for ((condition, set) in byCondition) if (s.mask and condition.flag != 0L) set.set(i)
        }
        val trainBase = stats(train, train, outcome)
        val holdoutBase = stats(holdout, holdout, outcome)

        // Every setup with enough plans, with its record before and in the holdout.
        val found = LinkedHashMap<Long, Candidate>()
        val conditions = SetupCondition.entries
        fun note(mask: Long, set: BitSet): Boolean {
            if (set.cardinality() < MIN_TRAIN) return false
            found[mask] = Candidate(mask, stats(set, train, outcome), stats(set, holdout, outcome))
            return true
        }
        for (i in conditions.indices) {
            val a = byCondition.getValue(conditions[i])
            if (!note(conditions[i].flag, a) || MAX_CONDITIONS < 2) continue
            for (j in i + 1 until conditions.size) {
                val ab = (a.clone() as BitSet).apply { and(byCondition.getValue(conditions[j])) }
                if (!note(conditions[i].flag or conditions[j].flag, ab) || MAX_CONDITIONS < 3) continue
                for (k in j + 1 until conditions.size) {
                    val abc = (ab.clone() as BitSet).apply { and(byCondition.getValue(conditions[k])) }
                    note(conditions[i].flag or conditions[j].flag or conditions[k].flag, abc)
                }
            }
        }

        /**
         * Every condition of a new setup must pull its weight: without any one of them, the rest must have done
         * [MIN_GAIN] worse (for a winner) or better (for a mistake) before the holdout. Otherwise it is the broader
         * setup, sliced by chance.
         */
        fun earnsEach(c: Candidate, sign: Int): Boolean = SetupCondition.of(c.mask).size < 2 ||
            SetupCondition.of(c.mask).all { condition ->
                val parent = found[c.mask and condition.flag.inv()] ?: return@all true
                sign * (c.before.rate - parent.before.rate) >= MIN_GAIN
            }

        val good = ArrayList<Candidate>()
        val bad = ArrayList<Candidate>()
        for (c in found.values) {
            val winner = if (c.mask !in previous) {
                c.before.n >= MIN_TRAIN && c.inHoldout.n >= MIN_HOLDOUT &&
                    wilsonLower(c.before) >= trainBase.rate + ADD_MARGIN && c.inHoldout.rate >= holdoutBase.rate + ADD_MARGIN &&
                    earnsEach(c, +1)
            } else {
                c.inHoldout.n >= MIN_HOLDOUT / 2 && c.before.rate >= trainBase.rate && c.inHoldout.rate >= holdoutBase.rate + KEEP_MARGIN
            }
            if (winner) good += c
            val mistake = if (c.mask !in previousAvoid) {
                c.before.n >= MIN_TRAIN && c.inHoldout.n >= MIN_HOLDOUT &&
                    wilsonUpper(c.before) <= trainBase.rate - ADD_MARGIN && c.inHoldout.rate <= holdoutBase.rate - ADD_MARGIN &&
                    earnsEach(c, -1)
            } else {
                c.inHoldout.n >= MIN_HOLDOUT / 2 && c.before.rate <= trainBase.rate && c.inHoldout.rate <= holdoutBase.rate - KEEP_MARGIN
            }
            if (mistake) bad += c
        }

        val changes = ArrayList<Change>()
        val record = { mask: Long ->
            found[mask] ?: setOf(mask, byCondition, n).let { Candidate(mask, stats(it, train, outcome), stats(it, holdout, outcome)) }
        }
        val plansOf = { mask: Long -> setOf(mask, byCondition, n) }
        // Winners rank by how surely they win (the lower bound), mistakes by how surely they lose (the upper bound).
        val rules = choose(good, previous, MAX_RULES, day, changes, avoid = false, plansOf, record) { -wilsonLower(it.all) }
        val avoid = choose(bad, previousAvoid, MAX_AVOID, day, changes, avoid = true, plansOf, record) { wilsonUpper(it.all) }

        val all = BitSet(n).apply { set(0, n) }
        val allBase = stats(all, all, outcome)
        val lift = conditions.mapNotNull { condition ->
            val withIt = stats(byCondition.getValue(condition), all, outcome)
            if (withIt.n < MIN_TRAIN || allBase.rate == 0.0) null else Lift(condition, withIt.rate / allBase.rate, withIt.rate, withIt.n)
        }.sortedByDescending { it.lift }
        val loseLift = conditions.mapNotNull { condition ->
            val withIt = stats(byCondition.getValue(condition), all, outcome)
            if (withIt.n < MIN_TRAIN || allBase.stopRate == 0.0) null else Lift(condition, withIt.stopRate / allBase.stopRate, withIt.stopRate, withIt.n)
        }.sortedByDescending { it.lift }

        return Result(rules, avoid, changes, trainBase, holdoutBase, holdoutFrom, lift, loseLift)
    }

    /**
     * The setups to keep from [found]: those in use first, then by [order] (smallest first), leaving out any that
     * mostly picks the same plans as one already kept, up to [max]. Adds what came and went to [changes].
     */
    private fun choose(
        found: List<Candidate>,
        previous: Map<Long, Long>,
        max: Int,
        day: Long,
        changes: MutableList<Change>,
        avoid: Boolean,
        plansOf: (Long) -> BitSet,
        record: (Long) -> Candidate,
        order: (Candidate) -> Double,
    ): List<Rule> {
        val ordered = found.sortedWith(
            compareByDescending<Candidate> { it.mask in previous }.thenBy(order).thenBy { java.lang.Long.bitCount(it.mask) },
        )
        val chosen = ArrayList<Pair<Candidate, BitSet>>()
        for (c in ordered) {
            if (chosen.size >= max) break
            val plans = plansOf(c.mask)
            if (chosen.any { overlap(it.second, plans) >= OVERLAP }) continue
            chosen += c to plans
        }
        val rules = chosen.map { it.first }.sortedBy(order).map { Rule(it.mask, it.before, it.inHoldout, previous[it.mask] ?: day) }
        val kept = rules.map { it.mask }.toSet()
        val foundMasks = found.map { it.mask }.toSet()
        for (r in rules) if (r.mask !in previous) changes += Change(day, true, r.mask, r.train, r.holdout, avoid = avoid)
        for (mask in previous.keys) {
            if (mask in kept) continue
            // Still qualifying but covered by a better one, or no longer qualifying.
            val now = record(mask)
            changes += Change(day, false, mask, now.before, now.inHoldout, replaced = mask in foundMasks, avoid = avoid)
        }
        return rules
    }

    private class Outcomes(val wins: BitSet, val stops: BitSet)

    /** A setup found in the search, with its record before and in the holdout. */
    private class Candidate(val mask: Long, val before: Stats, val inHoldout: Stats) {
        val all: Stats get() = before + inHoldout
    }

    /** Lower bound of the 95% Wilson interval: a rate that small samples cannot fake. */
    fun wilsonLower(s: Stats): Double = wilson(s, -1)

    /** Upper bound of the 95% Wilson interval: a rate surely no higher than this. */
    fun wilsonUpper(s: Stats): Double = if (s.n == 0) 1.0 else wilson(s, +1)

    private fun wilson(s: Stats, side: Int): Double {
        if (s.n == 0) return 0.0
        val z = 1.96
        val p = s.rate
        val n = s.n.toDouble()
        val centre = p + z * z / (2 * n)
        val margin = z * sqrt(p * (1 - p) / n + z * z / (4 * n * n))
        return (centre + side * margin) / (1 + z * z / n)
    }

    private fun stats(set: BitSet, part: BitSet, outcome: Outcomes): Stats {
        val inPart = (set.clone() as BitSet).apply { and(part) }
        val n = inPart.cardinality()
        val won = (inPart.clone() as BitSet).apply { and(outcome.wins) }.cardinality()
        inPart.and(outcome.stops)
        return Stats(n, won, inPart.cardinality())
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
