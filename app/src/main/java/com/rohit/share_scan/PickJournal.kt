package com.rohit.share_scan

import org.json.JSONArray
import org.json.JSONObject

/**
 * The track record: for each finished session, the scan's best STRONG stocks at that day's close, and how they did
 * 1, 5 and 10 sessions later against Nifty 50 over the same days. A scan rebuilds every session its charts reach back
 * to ([BreakoutSignals.PAST_SESSIONS] before the latest, from the same score at each past close), so the record has
 * days in it from the very first scan and grows with every day after; older days stay as they were last measured.
 * Free of Android types so it can be unit tested.
 */
class PickJournal(days: List<Day> = emptyList()) {
    /** Newest first. */
    val days: List<Day> = days.sortedByDescending { it.day }

    data class Pick(
        val symbol: String,
        /** Score at the day's close. */
        val score: Double,
        /** The day's close: the price the pick is measured from. */
        val close: Double,
        /** Percent change after 1, 5 and 10 sessions ([HORIZONS]), null until that many have passed. */
        val returns: List<Double?> = List(HORIZONS.size) { null },
        /** Percent change to the latest close the journal has seen. */
        val latest: Double? = null,
    )

    data class Day(
        /** Epoch day (IST) of the session whose close picked these. */
        val day: Long,
        val picks: List<Pick>,
        /** Nifty 50's percent change over the same 1, 5 and 10 sessions; null until known. */
        val nifty: List<Double?> = List(HORIZONS.size) { null },
    )

    /** One horizon's summary over every pick that has reached it. */
    data class Summary(
        val sessions: Int,
        val picks: Int,
        /** Mean percent change of the picks. */
        val average: Double,
        /** Share of picks that were up, 0–1. */
        val winRate: Double,
        /** Mean percent change of Nifty 50 over the same days, pick for pick; null without Nifty data. */
        val nifty: Double?,
    )

    /** The record after a finished, complete scan: its sessions picked afresh, every pick measured again. */
    fun update(output: ScanOutput): PickJournal {
        val results = output.results
        if (results.isEmpty() || output.stopped) return this
        val bySymbol = results.associateBy { it.symbol }
        val updated = days.associateBy { it.day }.toMutableMap()
        for ((day, picks) in pickDays(results)) updated[day] = Day(day, picks)
        // Today's session, while it still trades; it has no close to measure from or to yet.
        val liveDay = results.firstOrNull { it.live }?.structure?.closeDays?.lastOrNull()
        val measured = updated.values.map { day -> measure(day, bySymbol, output.benchmark, liveDay) }
        return PickJournal(measured.sortedByDescending { it.day }.take(KEEP_DAYS))
    }

    /** For each session the scan can score, its best STRONG stocks at that close, best first. */
    private fun pickDays(results: List<ScanResult>): Map<Long, List<Pick>> {
        val scored = HashMap<Long, MutableList<Pick>>()
        for (stock in results) {
            val structure = stock.structure ?: continue
            if (structure.closeDays.size != structure.closes.size) continue
            val closeOn = structure.closeDays.zip(structure.closes).toMap()
            val sessions = stock.pastScores.map { it.day to it.score }.toMutableList()
            // A finished latest session is a pick day too; one still trading is not.
            if (!stock.live) structure.closeDays.lastOrNull()?.let { sessions += it to stock.score }
            for ((day, score) in sessions) {
                if (StockFormat.tier(score) != StockFormat.Tier.STRONG) continue
                val close = closeOn[day] ?: continue
                scored.getOrPut(day) { ArrayList() } += Pick(stock.symbol, score, close)
            }
        }
        return scored.mapValues { (_, picks) -> picks.sortedByDescending { it.score }.take(PICKS_PER_DAY) }
    }

    /** Returns after each horizon from the stocks' latest finished closes; a stock missing from the scan keeps its own. */
    private fun measure(day: Day, bySymbol: Map<String, ScanResult>, benchmark: DailySeries?, liveDay: Long?): Day {
        val picks = day.picks.map { pick ->
            val stock = bySymbol[pick.symbol] ?: return@map pick
            val structure = stock.structure ?: return@map pick
            val days = structure.closeDays
            val closes = structure.closes
            // A session still trading has no close yet.
            val finished = if (stock.live) closes.size - 1 else closes.size
            val index = days.indexOf(day.day)
            if (index < 0 || index >= finished || !(pick.close > 0.0)) return@map pick
            val returns = HORIZONS.mapIndexed { i, ahead ->
                val at = index + ahead
                if (at < finished) change(pick.close, closes[at]) else pick.returns.getOrNull(i)
            }
            pick.copy(returns = returns, latest = if (finished > index) change(pick.close, closes[finished - 1]) else pick.latest)
        }
        val nifty = benchmark?.let { series ->
            // Nifty's chart includes today's session while it trades; only finished sessions count.
            val finished = if (liveDay != null && series.days.lastOrNull() == liveDay) series.closes.size - 1 else series.closes.size
            val index = series.days.indexOf(day.day)
            if (index < 0 || index >= finished) return@let day.nifty
            HORIZONS.mapIndexed { i, ahead ->
                val at = index + ahead
                if (at < finished) change(series.closes[index], series.closes[at]) else day.nifty.getOrNull(i)
            }
        } ?: day.nifty
        return day.copy(picks = picks, nifty = nifty)
    }

    /** Every pick that has reached [HORIZONS] [horizon], summed up; null when none has. */
    fun summary(horizon: Int): Summary? {
        var sessions = 0
        val changes = ArrayList<Double>()
        val nifty = ArrayList<Double>()
        for (day in days) {
            val reached = day.picks.mapNotNull { it.returns.getOrNull(horizon) }
            if (reached.isEmpty()) continue
            sessions++
            changes += reached
            day.nifty.getOrNull(horizon)?.let { market -> repeat(reached.size) { nifty += market } }
        }
        if (changes.isEmpty()) return null
        return Summary(
            sessions = sessions,
            picks = changes.size,
            average = changes.average(),
            winRate = changes.count { it > 0.0 }.toDouble() / changes.size,
            nifty = if (nifty.size == changes.size) nifty.average() else null,
        )
    }

    fun encode(): String = JSONArray().apply {
        for (day in days) {
            put(JSONObject().apply {
                put("d", day.day)
                put("n", JSONArray().apply { day.nifty.forEach { put(it ?: JSONObject.NULL) } })
                put("p", JSONArray().apply {
                    for (pick in day.picks) {
                        put(JSONObject().apply {
                            put("s", pick.symbol)
                            put("sc", pick.score)
                            put("c", pick.close)
                            put("r", JSONArray().apply { pick.returns.forEach { put(it ?: JSONObject.NULL) } })
                            pick.latest?.let { put("l", it) }
                        })
                    }
                })
            })
        }
    }.toString()

    companion object {
        /** Sessions ahead each return is measured at. */
        val HORIZONS = listOf(1, 5, 10)
        const val PICKS_PER_DAY = 10

        /** About three months of sessions. */
        const val KEEP_DAYS = 60

        private fun change(from: Double, to: Double): Double = (to / from - 1.0) * 100.0

        fun decode(text: String?): PickJournal {
            if (text.isNullOrEmpty()) return PickJournal()
            return runCatching {
                val json = JSONArray(text)
                PickJournal(List(json.length()) { i ->
                    val o = json.getJSONObject(i)
                    val picks = o.getJSONArray("p")
                    Day(
                        day = o.getLong("d"),
                        nifty = optionals(o.optJSONArray("n")),
                        picks = List(picks.length()) { j ->
                            val p = picks.getJSONObject(j)
                            Pick(
                                symbol = p.getString("s"),
                                score = p.getDouble("sc"),
                                close = p.getDouble("c"),
                                returns = optionals(p.optJSONArray("r")),
                                latest = if (p.has("l")) p.getDouble("l") else null,
                            )
                        },
                    )
                })
            }.getOrDefault(PickJournal())
        }

        private fun optionals(array: JSONArray?): List<Double?> = List(HORIZONS.size) { i ->
            if (array == null || i >= array.length() || array.isNull(i)) null else array.getDouble(i)
        }
    }
}
