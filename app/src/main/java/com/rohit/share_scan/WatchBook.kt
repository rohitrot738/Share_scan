package com.rohit.share_scan

import org.json.JSONObject

/**
 * When a stock went on the watchlist and at what [price]: its return since is measured from there. [estimated] marks a
 * price the app filled in itself, from the first scan after it began keeping them, for a stock starred before that.
 */
data class WatchEntry(val price: Double, val at: Long, val estimated: Boolean = false) {
    /** Percent from the price it was added at to [now]; null without a usable price either side. */
    fun returnPct(now: Double): Double? =
        if (price > 0.0 && now > 0.0 && price.isFinite() && now.isFinite()) (now - price) / price * 100.0 else null
}

/** The Watchlist page's order: best return since added first, newest first, or nearest its next level first. */
enum class WatchSort { RETURN, RECENT, NEAREST }

/**
 * The two levels a watchlisted stock moves between, for the page's bar. Waiting under its breakout trigger ([target]
 * 0), it sits between the base's [floor] (the 10-session low) and the trigger; in a trade it sits between its stop
 * ([floor]) and the target it heads for next ([target] 1 to 3).
 */
data class WatchLevels(val floor: Double, val next: Double, val target: Int) {
    /** The bar's left end is the trade's stop rather than the base's low. */
    val floorIsStop: Boolean get() = target > 0

    /** Where [price] sits from the floor (0) to the next level (1), held inside that span. */
    fun position(price: Double): Float =
        if (next > floor) ((price - floor) / (next - floor)).toFloat().coerceIn(0f, 1f) else 0.5f

    /** How far the next level is above [price], as a percent of it; 0 once it is reached. */
    fun distancePct(price: Double): Double = if (price > 0.0) ((next - price) / price * 100.0).coerceAtLeast(0.0) else Double.MAX_VALUE
}

/** One watchlisted stock on the Watchlist page. */
data class WatchRow(
    val stock: ScanResult,
    /** When and at what price it was added; null until a price for it came in. */
    val entry: WatchEntry?,
    /** Its alerts' state ([WatchAlerts]); null before its first check. */
    val alert: WatchAlerts.State?,
    val levels: WatchLevels?,
) {
    val returnPct: Double? get() = if (stock.charted) entry?.returnPct(stock.price) else null
}

/** Free of Android types so it can be unit tested. */
object WatchBook {
    /** The page's top card: the watchlist as one basket, every stock counted the same. */
    data class Summary(
        /** Average return since each stock was added; null while none has a price to measure from. */
        val averageReturn: Double?,
        val gaining: Int,
        val losing: Int,
        /** Stocks with an alert set: waiting under a breakout trigger, or in a trade heading for a target. */
        val activeAlerts: Int,
        /** The basket over the last [CURVE_SESSIONS] sessions, in percent from the first; empty without the closes. */
        val curve: List<Double>,
    )

    const val CURVE_SESSIONS = 20

    /**
     * The watchlist's rows in [sort]'s order. Each stock's levels come from its alert state where it has one, else from
     * the plan the detail sheet shows ([TradePlan], with [shape]'s stop and targets). A stock no chart has come in for
     * yet goes last whatever the order.
     */
    fun rows(
        stocks: List<ScanResult>,
        entries: Map<String, WatchEntry>,
        alerts: Map<String, WatchAlerts.State>,
        sort: WatchSort,
        shape: (ScanResult) -> PlanShape = { PlanShape.OWN },
    ): List<WatchRow> {
        val rows = stocks.map { stock ->
            val alert = alerts[stock.symbol]
            WatchRow(stock, entries[stock.symbol], alert, levels(stock, alert, shape(stock)))
        }
        val (charted, waiting) = rows.partition { it.stock.charted }
        val ordered = when (sort) {
            WatchSort.RETURN -> charted.sortedWith(compareByDescending<WatchRow> { it.returnPct ?: -Double.MAX_VALUE }.thenBy { it.stock.symbol })
            WatchSort.RECENT -> charted.sortedWith(compareByDescending<WatchRow> { it.entry?.at ?: 0L }.thenBy { it.stock.symbol })
            WatchSort.NEAREST -> charted.sortedWith(
                compareBy<WatchRow> { it.levels?.distancePct(it.stock.price) ?: Double.MAX_VALUE }.thenBy { it.stock.symbol },
            )
        }
        return ordered + waiting.sortedBy { it.stock.symbol }
    }

    /**
     * Where the stock stands: a trade under way between its stop and its next target, a stock waiting under its
     * trigger between the base's low and the trigger. Without an alert state yet the plan says the same; null when
     * there is no plan.
     */
    fun levels(stock: ScanResult, alert: WatchAlerts.State?, shape: PlanShape = PlanShape.OWN): WatchLevels? {
        val base = stock.structure?.low10?.takeIf { it > 0.0 }
        val stop = alert?.stop
        val entry = alert?.entry
        if (alert != null && stop != null && entry != null) {
            when (alert.phase) {
                WatchAlerts.Phase.IN_TRADE -> alert.targets.getOrNull(alert.hits)?.let { return WatchLevels(stop, it, alert.hits + 1) }
                WatchAlerts.Phase.WAITING -> return WatchLevels(minOf(base ?: stop, stop), entry, 0)
            }
        }
        if (!stock.charted) return null
        val plan = stock.structure?.let { TradePlan.of(stock.price, it, shape) } ?: return null
        return when (plan.entryKind) {
            TradePlan.EntryKind.BREAKOUT -> WatchLevels(minOf(base ?: plan.stop, plan.stop), plan.entry, 0)
            TradePlan.EntryKind.MARKET -> plan.targets.firstOrNull()?.let { WatchLevels(plan.stop, it, 1) }
        }
    }

    /** True while the stock has an alert to raise: waiting under a trigger, or in a trade. */
    fun alertActive(alert: WatchAlerts.State?): Boolean = alert?.entry != null && alert.stop != null

    fun summary(rows: List<WatchRow>): Summary {
        val returns = rows.mapNotNull { it.returnPct }
        return Summary(
            averageReturn = returns.takeIf { it.isNotEmpty() }?.average(),
            gaining = returns.count { it > 0.0 },
            losing = returns.count { it < 0.0 },
            activeAlerts = rows.count { alertActive(it.alert) },
            curve = curve(rows.map { it.stock }),
        )
    }

    /**
     * The basket's path over the last [sessions] closes: each stock's closes as a percent of its first one there,
     * averaged across the stocks that have them all. Empty with fewer than two sessions or no such stock.
     */
    fun curve(stocks: List<ScanResult>, sessions: Int = CURVE_SESSIONS): List<Double> {
        val paths = stocks.mapNotNull { stock ->
            val closes = stock.structure?.closes ?: return@mapNotNull null
            if (closes.size < sessions || sessions < 2) return@mapNotNull null
            val window = closes.takeLast(sessions)
            val first = window.first()
            if (!(first > 0.0)) null else window.map { it / first * 100.0 - 100.0 }
        }
        if (paths.isEmpty()) return emptyList()
        return List(sessions) { i -> paths.sumOf { it[i] } / paths.size }
    }

    fun encode(entries: Map<String, WatchEntry>): String = JSONObject().apply {
        for ((symbol, entry) in entries) {
            put(symbol, JSONObject().apply {
                put("p", entry.price)
                put("at", entry.at)
                if (entry.estimated) put("est", true)
            })
        }
    }.toString()

    fun decode(text: String?): Map<String, WatchEntry> {
        if (text.isNullOrEmpty()) return emptyMap()
        return runCatching {
            val json = JSONObject(text)
            json.keys().asSequence().mapNotNull { symbol ->
                val o = json.optJSONObject(symbol) ?: return@mapNotNull null
                val price = o.optDouble("p", Double.NaN)
                if (!(price > 0.0)) null else symbol to WatchEntry(price, o.optLong("at"), o.optBoolean("est"))
            }.toMap()
        }.getOrDefault(emptyMap())
    }

    /**
     * [entries] with an estimated entry for each watchlisted stock in [prices] that has none, at its price there and
     * [now]; the same map when nothing is missing. Entries of stocks no longer on [watchlist] go.
     */
    fun backfill(entries: Map<String, WatchEntry>, watchlist: Set<String>, prices: Map<String, Double>, now: Long): Map<String, WatchEntry> {
        val kept = entries.filterKeys { it in watchlist }
        val missing = watchlist.filter { it !in kept && (prices[it] ?: 0.0) > 0.0 }
        if (missing.isEmpty() && kept.size == entries.size) return entries
        return kept + missing.associateWith { WatchEntry(prices.getValue(it), now, estimated = true) }
    }
}
