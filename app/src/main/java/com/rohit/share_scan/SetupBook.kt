package com.rohit.share_scan

import org.json.JSONArray
import org.json.JSONObject

/**
 * The learned setups the app uses, from the daily learner's setups.json ([SetupMiner]): each setup's conditions and
 * record, the mistakes learned (setups whose trades went the wrong way far more often than the market's), the setups
 * added and removed lately, which conditions the successes and the stopped trades shared most, and how often the trade
 * plan's quick target came in time for each kind of breakout ([BreakoutSetup.Kind]). They take the place of the
 * fixed setups and figures the app shipped with; without the file the app keeps those. Free of Android types so it can
 * be unit tested.
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
) {
    data class KindStats(val quick: SetupMiner.Stats, val quickWide: SetupMiner.Stats)

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

        /** The file the learner writes: [result], [kinds], and the changes before today's from [previous]. */
        fun encode(
            result: SetupMiner.Result,
            kinds: Map<BreakoutSetup.Kind, KindStats>,
            previous: SetupBook,
            day: Long,
            stocks: Int,
            plans: Int,
        ): String {
            val changes = (result.changes + previous.changes.filter { it.day != day }).take(LOG_SIZE)
            return JSONObject().apply {
                put("v", VERSION)
                put("day", day)
                put("stocks", stocks)
                put("plans", plans)
                put("holdoutFrom", result.holdoutFrom)
                put("base", stats(result.train, result.holdout))
                put("rules", rules(result.rules))
                put("avoid", rules(result.avoid))
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
                )
            }.getOrDefault(EMPTY)
        }

        private fun rules(rules: List<SetupMiner.Rule>) = JSONArray().apply {
            for (r in rules) put(stats(r.train, r.holdout).put("m", r.mask).put("since", r.since))
        }

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
