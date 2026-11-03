package com.rohit.share_scan

import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.LocalTime

/** A trade the user took and wrote down: what, how many, at what price, and how it ended once they say so. */
data class LoggedTrade(
    /** When it was written down (epoch millis); also its identity. */
    val id: Long,
    val day: LocalDate,
    val symbol: String,
    val side: OrderTicket.Side,
    val product: PositionSize.Product,
    val qty: Int,
    val entry: Double,
    val stop: Double,
    val target: Double?,
    /** The price it was closed at; null while it is open. */
    val exit: Double? = null,
    val exitAt: Long? = null,
) {
    val open: Boolean get() = exit == null

    /** Profit (or, below zero, loss) were it closed at [price]. */
    fun pnl(price: Double): Double = (price - entry) * qty * if (side == OrderTicket.Side.BUY) 1 else -1

    /** The profit or loss it closed with; null while open. */
    val realized: Double? get() = exit?.let(::pnl)
}

/**
 * The trade log (the /trader-risk skill's journal): trades the user took, kept on the phone as JSON for [KEEP_DAYS]
 * days. Free of Android types so it can be unit tested.
 */
object TradeLog {
    const val KEEP_DAYS = 90L

    fun today(trades: List<LoggedTrade>, day: LocalDate): List<LoggedTrade> = trades.filter { it.day == day }

    /** What the trades of [day] closed with, together. */
    fun realized(trades: List<LoggedTrade>, day: LocalDate): Double = today(trades, day).sumOf { it.realized ?: 0.0 }

    /** Intraday trades of [day] still open: the ones to square off. */
    fun openIntraday(trades: List<LoggedTrade>, day: LocalDate): List<LoggedTrade> =
        today(trades, day).filter { it.open && it.product == PositionSize.Product.MIS }

    /** [trades] with [trade] added, newest first, and none older than [KEEP_DAYS] before [day]. */
    fun add(trades: List<LoggedTrade>, trade: LoggedTrade, day: LocalDate): List<LoggedTrade> =
        prune(listOf(trade) + trades.filter { it.id != trade.id }, day)

    fun close(trades: List<LoggedTrade>, id: Long, price: Double, at: Long): List<LoggedTrade> =
        trades.map { if (it.id == id) it.copy(exit = price, exitAt = at) else it }

    fun remove(trades: List<LoggedTrade>, id: Long): List<LoggedTrade> = trades.filter { it.id != id }

    fun prune(trades: List<LoggedTrade>, day: LocalDate): List<LoggedTrade> =
        trades.filter { !it.day.isBefore(day.minusDays(KEEP_DAYS)) }

    fun encode(trades: List<LoggedTrade>): String = JSONArray().apply {
        for (t in trades) {
            put(
                JSONObject()
                    .put("id", t.id)
                    .put("d", t.day.toString())
                    .put("s", t.symbol)
                    .put("side", t.side.name)
                    .put("p", t.product.name)
                    .put("q", t.qty)
                    .put("e", t.entry)
                    .put("sl", t.stop)
                    .apply {
                        t.target?.let { put("t", it) }
                        t.exit?.let { put("x", it) }
                        t.exitAt?.let { put("xa", it) }
                    },
            )
        }
    }.toString()

    /** The trades in [text]; a broken entry is left out, and broken text reads as none. */
    fun decode(text: String?): List<LoggedTrade> {
        val array = runCatching { JSONArray(text ?: return emptyList()) }.getOrNull() ?: return emptyList()
        return (0 until array.length()).mapNotNull { i ->
            runCatching {
                val o = array.getJSONObject(i)
                LoggedTrade(
                    id = o.getLong("id"),
                    day = LocalDate.parse(o.getString("d")),
                    symbol = o.getString("s"),
                    side = OrderTicket.Side.valueOf(o.getString("side")),
                    product = PositionSize.Product.valueOf(o.getString("p")),
                    qty = o.getInt("q"),
                    entry = o.getDouble("e"),
                    stop = o.getDouble("sl"),
                    target = if (o.has("t")) o.getDouble("t") else null,
                    exit = if (o.has("x")) o.getDouble("x") else null,
                    exitAt = if (o.has("xa")) o.getLong("xa") else null,
                )
            }.getOrNull()
        }
    }
}

/**
 * The pre-trade risk guard (the /trader-risk skill): no more than [Rules.maxTrades] trades a day, none once the day's
 * closed trades have lost [Rules.dailyLossPct] percent of the capital, and no new intraday entry from
 * [Rules.lastEntry]. Free of Android types so it can be unit tested.
 */
object RiskGuard {
    data class Rules(
        val maxTrades: Int = DEFAULT_MAX_TRADES,
        val dailyLossPct: Double = DEFAULT_DAILY_LOSS_PCT,
        val lastEntry: LocalTime = IntradayPlan.LAST_ENTRY,
    )

    const val DEFAULT_MAX_TRADES = 3
    const val DEFAULT_DAILY_LOSS_PCT = 2.0
    val MAX_TRADES_CHOICES = listOf(1, 2, 3, 5, 10)
    val DAILY_LOSS_CHOICES = listOf(1.0, 2.0, 3.0, 5.0)

    enum class Breach { TRADES, LOSS, LATE }

    /** The rules a new [product] trade at [time] on [day] would break; empty when it is within them. */
    fun check(
        trades: List<LoggedTrade>,
        day: LocalDate,
        time: LocalTime,
        capital: Double,
        product: PositionSize.Product,
        rules: Rules = Rules(),
    ): List<Breach> = listOfNotNull(
        Breach.TRADES.takeIf { TradeLog.today(trades, day).size >= rules.maxTrades },
        Breach.LOSS.takeIf { capital > 0.0 && TradeLog.realized(trades, day) <= -capital * rules.dailyLossPct / 100.0 },
        Breach.LATE.takeIf { product == PositionSize.Product.MIS && !time.isBefore(rules.lastEntry) },
    )

    /** How much more the day may lose before [Breach.LOSS]: the limit less what closed trades lost. */
    fun lossLeft(trades: List<LoggedTrade>, day: LocalDate, capital: Double, rules: Rules = Rules()): Double =
        (capital * rules.dailyLossPct / 100.0 + TradeLog.realized(trades, day)).coerceAtLeast(0.0)
}
