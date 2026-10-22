package com.rohit.share_scan

import org.json.JSONArray
import org.json.JSONObject

/**
 * The learned setups the app uses, from the daily learner's setups.json ([SetupMiner]): each setup's conditions and
 * record, the mistakes learned (setups whose trades went the wrong way far more often than the market's), the setups
 * added and removed lately, which conditions the successes and the stopped trades shared most, and how often the trade
 * plan's quick target came in time for each kind of breakout ([BreakoutSetup.Kind]). It also holds the stop and
 * Target 1 that did best ([PlanTuner]), for every plan and for each setup that did better with its own, and the setups
 * after which stocks most often rallied ([PlanOutcome.rally]), with the conditions rallies shared. They take the place
 * of the fixed setups, plan and figures the app shipped with; without the file the app keeps those. Free of Android
 * types so it can be unit tested.
 */
class SetupBook(
    /** Best first. */
    val rules: List<SetupMiner.Rule>,
    /** Newest first. */
    val changes: List<SetupMiner.Change>,
    /** Every plan's success rate, before and in the holdout. */
    val train: SetupMiner.Stats,
    val holdout: SetupMiner.Stats,
    val lift: List<SetupMiner.Lift>,
    /** Per breakout kind: how often the quick target came in time, before the stop and before the wide stop. */
    val kinds: Map<BreakoutSetup.Kind, KindStats>,
    /** The day (epoch day) the learner last ran, and the stocks and plans it learned from. */
    val day: Long,
    val stocks: Int,
    val plans: Int,
    /** Mistakes: setups whose trades failed far more often than the market's, worst first. */
    val avoid: List<SetupMiner.Rule> = emptyList(),
    /** Conditions most common among the trades that hit their stop. */
    val loseLift: List<SetupMiner.Lift> = emptyList(),
    /** The stop and Target 1 for every plan; null keeps the app's own. [rules] and [train] are judged under it. */
    val marketPlan: PlanTuner.Choice? = null,
    /** The app's own plan's record, to compare. */
    val ownPlan: PlanTuner.Choice? = null,
    /** Setups (by mask) that did better with a stop and Target 1 of their own. */
    val setupPlans: Map<Long, PlanTuner.Choice> = emptyMap(),
    /** Setups after which stocks most often rallied, best first, and every close's rally rate. */
    val rally: List<SetupMiner.Rule> = emptyList(),
    val rallyBase: SetupMiner.Stats = SetupMiner.Stats(0, 0),
    /** Conditions most common before a rally. */
    val whyUp: List<SetupMiner.Lift> = emptyList(),
    /** Stops and targets changed lately, newest first. */
    val planLog: List<PlanChange> = emptyList(),
) {
    data class KindStats(val quick: SetupMiner.Stats, val quickWide: SetupMiner.Stats)

    /** On [day] the plan of setup [mask] (0 for every plan) went from [from] to [to]. */
    data class PlanChange(val day: Long, val mask: Long, val from: PlanShape, val to: PlanShape)

    /** The learned plan for a stock with conditions [mask]: its best setup's own, else every plan's; null when not learned. */
    fun planFor(mask: Long): PlanTuner.Choice? = best(mask)?.let { setupPlans[it.mask] } ?: marketPlan

    /** Where the plan of a stock with conditions [mask] puts its stop and targets. */
    fun shape(mask: Long): PlanShape = planFor(mask)?.shape ?: PlanShape.OWN

    /** [rule]'s record under the plan the app shows for it. */
    fun shown(rule: SetupMiner.Rule): SetupMiner.Stats = setupPlans[rule.mask]?.all?.stats ?: rule.all

    /** The best rally setup whose every condition holds in [mask]; null when none does. */
    fun rallySetup(mask: Long): SetupMiner.Rule? = rally.firstOrNull { it.mask != 0L && it.mask and mask == it.mask }

    /** The best setup whose every condition holds in [mask]; null when none does. */
    fun best(mask: Long): SetupMiner.Rule? = rules.firstOrNull { it.mask != 0L && it.mask and mask == it.mask }

    /** The worst mistake whose every condition holds in [mask]; null when none does. */
    fun mistake(mask: Long): SetupMiner.Rule? = avoid.firstOrNull { it.mask != 0L && it.mask and mask == it.mask }

    /** The quick target's rates for [kind] in percent, before the stop and before the wide stop; null when not learned. */
    fun quickHits(kind: BreakoutSetup.Kind): Pair<Int, Int>? {
        val s = kinds[kind] ?: return null
        if (s.quick.n < MIN_KIND_PLANS) return null
        return percent(s.quick) to percent(s.quickWide)
    }

    companion object {
        const val VERSION = 1

        /** Stocks trading less than this on a typical session (₹ crore) are neither learned from nor given a setup. */
        const val MIN_TURNOVER_CR = 0.5

        /** Kinds learned from fewer plans keep the app's own figures. */
        const val MIN_KIND_PLANS = 100

        /** Changes kept in the file. */
        const val LOG_SIZE = 100

        val EMPTY = SetupBook(emptyList(), emptyList(), SetupMiner.Stats(0, 0), SetupMiner.Stats(0, 0), emptyList(), emptyMap(), 0, 0, 0)

        fun percent(s: SetupMiner.Stats): Int = Math.round(s.rate * 100).toInt()

        fun percentOf(part: Int, n: Int): Int = if (n == 0) 0 else Math.round(part * 100.0 / n).toInt()

        /**
         * The file the learner writes: [result], [kinds], the plans ([marketPlan], [ownPlan], [setupPlans]), the
         * rallies ([rally]), and the changes before today's from [previous].
         */
        fun encode(
            result: SetupMiner.Result,
            kinds: Map<BreakoutSetup.Kind, KindStats>,
            previous: SetupBook,
            day: Long,
            stocks: Int,
            plans: Int,
            marketPlan: PlanTuner.Choice? = null,
            ownPlan: PlanTuner.Choice? = null,
            setupPlans: Map<Long, PlanTuner.Choice> = emptyMap(),
            rally: SetupMiner.Result? = null,
        ): String {
            val changes = (result.changes + previous.changes.filter { it.day != day }).take(LOG_SIZE)
            val planChanges = ArrayList<PlanChange>()
            val market = marketPlan?.shape ?: PlanShape.OWN
            val before = previous.marketPlan?.shape ?: PlanShape.OWN
            if (market != before) planChanges += PlanChange(day, 0L, before, market)
            // A setup's plan was its own or every plan's; a setup that went is in the setup log instead.
            val setups = result.rules.map { it.mask }.toSet()
            for (mask in (setupPlans.keys + previous.setupPlans.keys).filter { it in setups }) {
                val was = previous.setupPlans[mask]?.shape ?: before
                val now = setupPlans[mask]?.shape ?: market
                if (now != was) planChanges += PlanChange(day, mask, was, now)
            }
            val planLog = (planChanges + previous.planLog.filter { it.day != day }).take(LOG_SIZE)
            return JSONObject().apply {
                put("v", VERSION)
                put("day", day)
                put("stocks", stocks)
                put("plans", plans)
                put("holdoutFrom", result.holdoutFrom)
                put("base", stats(result.train, result.holdout))
                put("rules", rules(result.rules, setupPlans))
                put("avoid", rules(result.avoid, emptyMap()))
                put("log", JSONArray().apply {
                    for (c in changes) {
                        val o = stats(c.train, c.holdout).put("d", c.day).put("a", if (c.added) 1 else 0).put("m", c.mask).put("r", if (c.replaced) 1 else 0)
                        if (c.avoid) o.put("x", 1)
                        put(o)
                    }
                })
                put("why", lifts(result.lift))
                put("whyLost", lifts(result.loseLift))
                put("kinds", JSONObject().apply {
                    for ((kind, s) in kinds) put(kind.name, stats(s.quick, s.quickWide))
                })
                marketPlan?.let { put("plan", choice(it)) }
                ownPlan?.let { put("own", choice(it)) }
                put("planLog", JSONArray().apply {
                    for (c in planLog) {
                        put(JSONObject().put("d", c.day).put("m", c.mask).put("fs", c.from.stopAtr).put("ft", c.from.targetR)
                            .put("ts", c.to.stopAtr).put("tt", c.to.targetR))
                    }
                })
                if (rally != null) {
                    put("rally", rules(rally.rules, emptyMap()))
                    put("rallyBase", stats(rally.train, rally.holdout))
                    put("whyUp", lifts(rally.lift))
                }
            }.toString()
        }

        /** The book in [json]; [EMPTY] when the file is missing, broken or of another version. */
        fun parse(json: String?): SetupBook {
            if (json.isNullOrBlank()) return EMPTY
            return runCatching {
                val root = JSONObject(json)
                if (root.optInt("v") != VERSION) return EMPTY
                val (train, holdout) = statsOf(root.optJSONObject("base") ?: JSONObject())
                SetupBook(
                    rules = rulesOf(root.optJSONArray("rules")),
                    changes = objects(root.optJSONArray("log")).map { o ->
                        val (t, h) = statsOf(o)
                        SetupMiner.Change(o.optLong("d"), o.optInt("a") == 1, o.getLong("m"), t, h, o.optInt("r") == 1, o.optInt("x") == 1)
                    },
                    train = train,
                    holdout = holdout,
                    lift = liftsOf(root.optJSONArray("why")),
                    kinds = root.optJSONObject("kinds")?.let { k ->
                        BreakoutSetup.Kind.entries.mapNotNull { kind ->
                            k.optJSONObject(kind.name)?.let { o -> statsOf(o).let { (q, w) -> kind to KindStats(q, w) } }
                        }.toMap()
                    }.orEmpty(),
                    day = root.optLong("day"),
                    stocks = root.optInt("stocks"),
                    plans = root.optInt("plans"),
                    avoid = rulesOf(root.optJSONArray("avoid")),
                    loseLift = liftsOf(root.optJSONArray("whyLost")),
                    marketPlan = root.optJSONObject("plan")?.let(::choiceOf),
                    ownPlan = root.optJSONObject("own")?.let(::choiceOf),
                    setupPlans = objects(root.optJSONArray("rules")).mapNotNull { o ->
                        o.optJSONObject("plan")?.let(::choiceOf)?.let { o.getLong("m") to it }
                    }.toMap(),
                    rally = rulesOf(root.optJSONArray("rally")),
                    rallyBase = (root.optJSONObject("rallyBase") ?: JSONObject()).let(::statsOf).let { (t, h) -> t + h },
                    whyUp = liftsOf(root.optJSONArray("whyUp")),
                    planLog = objects(root.optJSONArray("planLog")).mapNotNull { o ->
                        val from = shapeOf(o.optDouble("fs"), o.optDouble("ft")) ?: return@mapNotNull null
                        val to = shapeOf(o.optDouble("ts"), o.optDouble("tt")) ?: return@mapNotNull null
                        PlanChange(o.optLong("d"), o.optLong("m"), from, to)
                    },
                )
            }.getOrDefault(EMPTY)
        }

        private fun rules(rules: List<SetupMiner.Rule>, plans: Map<Long, PlanTuner.Choice>) = JSONArray().apply {
            for (r in rules) {
                val o = stats(r.train, r.holdout).put("m", r.mask).put("since", r.since)
                plans[r.mask]?.let { o.put("plan", choice(it)) }
                put(o)
            }
        }

        /** A plan's stop and Target 1, the day it was chosen, and its record before (n, w, s, r) and in the holdout. */
        private fun choice(c: PlanTuner.Choice) = JSONObject()
            .put("stop", c.shape.stopAtr).put("target", c.shape.targetR).put("since", c.since)
            .put("n", c.train.n).put("w", c.train.wins).put("s", c.train.stops).put("r", round(c.train.sumR))
            .put("hn", c.holdout.n).put("hw", c.holdout.wins).put("hs", c.holdout.stops).put("hr", round(c.holdout.sumR))

        /** Null for a stop or target this app could not draw. */
        private fun choiceOf(o: JSONObject): PlanTuner.Choice? {
            val shape = shapeOf(o.optDouble("stop"), o.optDouble("target")) ?: return null
            return PlanTuner.Choice(
                shape,
                PlanTuner.Record(o.optInt("n"), o.optInt("w"), o.optInt("s"), o.optDouble("r", 0.0)),
                PlanTuner.Record(o.optInt("hn"), o.optInt("hw"), o.optInt("hs"), o.optDouble("hr", 0.0)),
                o.optLong("since"),
            )
        }

        private fun shapeOf(stop: Double, target: Double): PlanShape? =
            if (stop.isFinite() && stop >= 0.0 && stop <= 10.0 && target.isFinite() && target > 0.0 && target <= 20.0) PlanShape(stop, target) else null

        /** Setups with a condition this app does not know are left out. */
        private fun rulesOf(array: JSONArray?) = objects(array).map { o ->
            val (t, h) = statsOf(o)
            SetupMiner.Rule(o.getLong("m"), t, h, o.optLong("since"))
        }.filter { it.conditions.isNotEmpty() && it.conditions.size == java.lang.Long.bitCount(it.mask) }

        private fun lifts(lifts: List<SetupMiner.Lift>) = JSONArray().apply {
            for (l in lifts) put(JSONObject().put("c", l.condition.bit).put("l", round(l.lift)).put("r", round(l.rate)).put("n", l.n))
        }

        private fun liftsOf(array: JSONArray?) = objects(array).mapNotNull { o ->
            SetupCondition.ofBit(o.optInt("c", -1))?.let { SetupMiner.Lift(it, o.optDouble("l"), o.optDouble("r"), o.optInt("n")) }
        }

        /** Plans, wins and stops before the holdout (n, w, s) and in it (hn, hw, hs). */
        private fun stats(a: SetupMiner.Stats, b: SetupMiner.Stats) =
            JSONObject().put("n", a.n).put("w", a.wins).put("s", a.stops).put("hn", b.n).put("hw", b.wins).put("hs", b.stops)

        private fun statsOf(o: JSONObject) =
            SetupMiner.Stats(o.optInt("n"), o.optInt("w"), o.optInt("s")) to SetupMiner.Stats(o.optInt("hn"), o.optInt("hw"), o.optInt("hs"))

        private fun objects(array: JSONArray?): List<JSONObject> =
            if (array == null) emptyList() else (0 until array.length()).mapNotNull { array.optJSONObject(it) }

        private fun round(v: Double) = Math.round(v * 1000) / 1000.0
    }
}
