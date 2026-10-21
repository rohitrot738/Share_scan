package com.rohit.share_scan

import org.json.JSONArray
import org.json.JSONObject

/**
 * Price alerts for watchlisted stocks, from the [TradePlan] the detail sheet shows. While a stock waits under its
 * breakout trigger the plan is kept fresh from each scan; once a scan finds the price at or above the trigger, that
 * plan is held fixed as the trade: an alert for the breakout, then one for each target reached and one if the stop
 * breaks, after which the stock waits for its next breakout. A stock already above its trigger the first time it is
 * seen raises nothing until it dips under and breaks out again. Free of Android types so it can be unit tested.
 */
object WatchAlerts {
    enum class Phase { WAITING, IN_TRADE }

    data class State(
        val phase: Phase = Phase.WAITING,
        /** Price at the latest check. */
        val price: Double? = null,
        /** WAITING: the plan from the latest check that had the price under its trigger. IN_TRADE: the trade's plan. */
        val entry: Double? = null,
        val stop: Double? = null,
        val targets: List<Double> = emptyList(),
        /** IN_TRADE: how many targets have been reached. */
        val hits: Int = 0,
        /** IN_TRADE: epoch millis of the breakout. */
        val since: Long = 0L,
    )

    sealed interface Event {
        val symbol: String
        val price: Double

        data class Breakout(
            override val symbol: String,
            override val price: Double,
            val entry: Double,
            val stop: Double,
            val target: Double?,
            /** The breakout session's relative volume; [BreakoutSetup.VOLUME_RVOL] or more held more often. */
            val rvol: Double = 0.0,
        ) : Event

        data class Target(override val symbol: String, override val price: Double, val number: Int, val level: Double, val entry: Double) : Event

        data class Stopped(override val symbol: String, override val price: Double, val stop: Double, val entry: Double) : Event
    }

    /** The state after a check that found [stock] at its latest price, and the alert it raises, if any. */
    fun step(state: State?, stock: ScanResult, now: Long): Pair<State, Event?> {
        val price = stock.price
        if (!(price > 0.0)) return (state ?: State()) to null
        val plan = stock.structure?.let { TradePlan.of(price, it) }
        val current = state ?: return waiting(plan, price) to null
        return when (current.phase) {
            Phase.WAITING -> {
                val entry = current.entry
                val stop = current.stop
                val wasBelow = current.price != null && entry != null && current.price < entry
                if (wasBelow && entry != null && stop != null && price >= entry) {
                    val trade = State(Phase.IN_TRADE, price, entry, stop, current.targets, hits = 0, since = now)
                    trade to Event.Breakout(stock.symbol, price, entry, stop, current.targets.firstOrNull(), stock.rvol)
                } else {
                    waiting(plan, price) to null
                }
            }
            Phase.IN_TRADE -> {
                val entry = current.entry ?: return waiting(plan, price) to null
                val stop = current.stop ?: return waiting(plan, price) to null
                val reached = current.targets.count { price >= it }
                when {
                    price <= stop -> waiting(plan, price) to Event.Stopped(stock.symbol, price, stop, entry)
                    reached > current.hits -> {
                        val event = Event.Target(stock.symbol, price, reached, current.targets[reached - 1], entry)
                        // The last target ends the trade; the stock then waits for its next breakout.
                        val next = if (reached >= current.targets.size) waiting(plan, price) else current.copy(price = price, hits = reached)
                        next to event
                    }
                    else -> current.copy(price = price) to null
                }
            }
        }
    }

    /** Waiting with the levels of [plan] when its price is under the trigger; with no levels otherwise. */
    private fun waiting(plan: TradePlan?, price: Double): State =
        if (plan != null && plan.entryKind == TradePlan.EntryKind.BREAKOUT) {
            State(Phase.WAITING, price, plan.entry, plan.stop, plan.targets)
        } else {
            State(Phase.WAITING, price)
        }

    fun encode(states: Map<String, State>): String = JSONObject().apply {
        for ((symbol, state) in states) {
            put(symbol, JSONObject().apply {
                put("ph", state.phase.name)
                state.price?.let { put("p", it) }
                state.entry?.let { put("e", it) }
                state.stop?.let { put("s", it) }
                put("t", JSONArray().apply { state.targets.forEach { put(it) } })
                put("h", state.hits)
                put("since", state.since)
            })
        }
    }.toString()

    fun decode(text: String?): Map<String, State> {
        if (text.isNullOrEmpty()) return emptyMap()
        return runCatching {
            val json = JSONObject(text)
            json.keys().asSequence().associateWith { symbol ->
                val o = json.getJSONObject(symbol)
                val targets = o.optJSONArray("t")
                State(
                    phase = runCatching { Phase.valueOf(o.optString("ph")) }.getOrDefault(Phase.WAITING),
                    price = if (o.has("p")) o.getDouble("p") else null,
                    entry = if (o.has("e")) o.getDouble("e") else null,
                    stop = if (o.has("s")) o.getDouble("s") else null,
                    targets = if (targets == null) emptyList() else List(targets.length()) { targets.getDouble(it) },
                    hits = o.optInt("h"),
                    since = o.optLong("since"),
                )
            }
        }.getOrDefault(emptyMap())
    }
}
