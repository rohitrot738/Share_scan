package com.rohit.share_scan

import com.rohit.share_scan.OrderTicket.Side
import com.rohit.share_scan.PositionSize.Product
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.LocalTime
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * What an order costs on top of the shares, as a discount broker (Zerodha's published rates) and the government take
 * them on NSE/BSE equity, so paper trades pay what real ones would:
 *
 * - **Brokerage**: delivery (CNC) free; intraday (MIS) 0.03% of the order or ₹20, whichever is less.
 * - **STT**: delivery 0.1% on the buy and the sell; intraday 0.025% on the sell.
 * - **Exchange charges**: NSE 0.00297%, BSE 0.00375% of the order. **SEBI fee**: ₹10 a crore.
 * - **GST**: 18% on the brokerage, exchange charges and SEBI fee.
 * - **Stamp duty**: on the buy only, delivery 0.015%, intraday 0.003%.
 * - **DP charge**: ₹15.34 when delivery shares are sold.
 *
 * Free of Android types so it can be unit tested.
 */
object PaperCharges {
    const val INTRADAY_BROKERAGE_PCT = 0.03
    const val BROKERAGE_CAP = 20.0
    const val STT_DELIVERY_PCT = 0.1
    const val STT_INTRADAY_SELL_PCT = 0.025
    const val NSE_EXCHANGE_PCT = 0.00297
    const val BSE_EXCHANGE_PCT = 0.00375
    const val SEBI_PCT = 0.0001
    const val GST_PCT = 18.0
    const val STAMP_DELIVERY_PCT = 0.015
    const val STAMP_INTRADAY_PCT = 0.003
    const val DP_CHARGE = 15.34

    data class Breakdown(
        val brokerage: Double,
        val stt: Double,
        val exchange: Double,
        val sebi: Double,
        val gst: Double,
        val stamp: Double,
        val dp: Double,
    ) {
        val total: Double get() = brokerage + stt + exchange + sebi + gst + stamp + dp
    }

    /** The charges on one filled order of [value] rupees. */
    fun of(product: Product, side: Side, value: Double, bse: Boolean = false): Breakdown {
        if (!(value > 0.0)) return Breakdown(0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0)
        val delivery = product == Product.CNC
        val brokerage = if (delivery) 0.0 else min(BROKERAGE_CAP, value * INTRADAY_BROKERAGE_PCT / 100.0)
        val stt = when {
            delivery -> value * STT_DELIVERY_PCT / 100.0
            side == Side.SELL -> value * STT_INTRADAY_SELL_PCT / 100.0
            else -> 0.0
        }
        val exchange = value * (if (bse) BSE_EXCHANGE_PCT else NSE_EXCHANGE_PCT) / 100.0
        val sebi = value * SEBI_PCT / 100.0
        val gst = (brokerage + exchange + sebi) * GST_PCT / 100.0
        val stamp = if (side == Side.BUY) value * (if (delivery) STAMP_DELIVERY_PCT else STAMP_INTRADAY_PCT) / 100.0 else 0.0
        val dp = if (delivery && side == Side.SELL) DP_CHARGE else 0.0
        return Breakdown(brokerage, stt, exchange, sebi, gst, stamp, dp)
    }
}

/** An order on the paper account: what was asked, and what became of it. */
data class PaperOrder(
    val id: Long,
    val symbol: String,
    /** Yahoo's name for the stock ("RELIANCE.NS"), which its candles are read by. */
    val ticker: String,
    val bse: Boolean,
    val side: Side,
    val product: Product,
    val type: Type,
    val qty: Int,
    /** The limit price of a LIMIT or SL order. */
    val price: Double? = null,
    /** The trigger of an SL or SL-M order. */
    val trigger: Double? = null,
    /** The stop-loss and target the position it opens is watched with. */
    val stop: Double? = null,
    val target: Double? = null,
    val source: PaperSource = PaperSource.MANUAL,
    /** When it was placed (epoch millis). */
    val placedAt: Long,
    /** The session it is for: the day it was placed, or the next one for an order placed after the close (AMO). */
    val day: LocalDate,
    val status: Status = Status.PENDING,
    /** An SL order whose trigger was reached: it now waits as a limit order. */
    val triggered: Boolean = false,
    /** A candle of its session has been seen; past that session it expires. */
    val sessionSeen: Boolean = false,
    val doneAt: Long? = null,
    val fillPrice: Double? = null,
    val charges: Double = 0.0,
    /** Why it was rejected, cancelled or expired, or what placed it when the account did (stop, target, square-off). */
    val reason: PaperReason? = null,
) {
    enum class Type { MARKET, LIMIT, SL, SL_M }

    enum class Status { PENDING, FILLED, CANCELLED, REJECTED, EXPIRED }

    val open: Boolean get() = status == Status.PENDING
}

/** Who chose the trade: the user, the stock's trade plan, or today's intraday call. */
enum class PaperSource { MANUAL, PLAN, INTRADAY }

enum class PaperReason {
    /** Not enough money in the account when it would have filled. */
    FUNDS,

    /** A delivery sell of more shares than are held. */
    NO_HOLDING,
    CANCELLED,

    /** Its session ended before price reached it. */
    EXPIRED,

    /** An intraday order still waiting at 15:15. */
    MIS_CLOSED,
    STOP,
    TARGET,
    SQUARE_OFF,
    EXIT,
}

/** Shares held on the paper account: one per stock and product, short (below zero) only for intraday. */
data class PaperPosition(
    val symbol: String,
    val ticker: String,
    val bse: Boolean,
    val product: Product,
    /** Above zero long, below zero short. */
    val qty: Int,
    val avg: Double,
    /** The money an intraday position holds as margin (a fifth of its value); 0 for delivery, paid in full. */
    val margin: Double,
    /** Charges of the buys (or short sells) not yet set against a closed trade. */
    val entryCharges: Double,
    val stop: Double?,
    val target: Double?,
    val openedAt: Long,
    /** The session it was opened in: an intraday position is squared off at 15:15 that day. */
    val day: LocalDate,
    /** The first candle (start, epoch seconds) its stop and target are checked on. */
    val watchFrom: Long,
    val source: PaperSource,
) {
    val side: Side get() = if (qty > 0) Side.BUY else Side.SELL

    /** Profit or loss at [price], before charges. */
    fun pnl(price: Double): Double = (price - avg) * qty
}

/** A finished round trip: bought and sold (or sold short and bought back). */
data class PaperTrade(
    val id: Long,
    val symbol: String,
    val product: Product,
    /** The opening side: BUY for a long trade, SELL for a short one. */
    val side: Side,
    val qty: Int,
    val entry: Double,
    val exit: Double,
    val openedAt: Long,
    val closedAt: Long,
    /** Both sides' charges on these shares. */
    val charges: Double,
    val reason: PaperReason?,
    val source: PaperSource,
) {
    val gross: Double get() = (exit - entry) * qty * if (side == Side.BUY) 1 else -1
    val net: Double get() = gross - charges
}

/** A stock's last price on the account and the start of the candle it came from (epoch seconds). */
data class PaperQuote(val price: Double, val at: Long)

/**
 * A held position read in full, for its detail: what it is worth now, the charges paid and still to pay, the price it
 * breaks even at, what its stop-loss and target would leave after charges, and its orders ([PaperEngine.holding]).
 */
data class PaperHolding(
    val position: PaperPosition,
    /** The last price; the average while none has been read. */
    val price: Double,
    /** The start of the candle [price] came from (epoch seconds); null while there is none. */
    val priceAt: Long?,
    /** The shares at their average price, and at [price]. */
    val invested: Double,
    val value: Double,
    /** Profit or loss at [price] before charges, in rupees and against [invested]. */
    val pnl: Double,
    val pnlPct: Double,
    /** What closing it at [price] would cost. */
    val exitCharges: Double,
    /** Profit or loss were it closed at [price], after both sides' charges. */
    val net: Double,
    /** The price it closes at no profit and no loss, both sides' charges paid. */
    val breakeven: Double,
    /** What closing at the stop-loss or the target would leave after charges; null without one or without a price. */
    val atStop: Double?,
    val atTarget: Double?,
    /** Its orders, newest first: those waiting, and those filled since it opened. */
    val orders: List<PaperOrder>,
)

/** The whole paper account. */
data class PaperBook(
    /** What it started with. */
    val start: Double,
    val cash: Double,
    val startedAt: Long,
    /** Newest first. */
    val orders: List<PaperOrder> = emptyList(),
    val positions: List<PaperPosition> = emptyList(),
    /** Newest first. */
    val trades: List<PaperTrade> = emptyList(),
    val quotes: Map<String, PaperQuote> = emptyMap(),
    /** Each day's latest worth, oldest first. */
    val equity: List<Pair<LocalDate, Double>> = emptyList(),
    /** Every charge paid since the start. */
    val chargesPaid: Double = 0.0,
    val nextId: Long = 1L,
)

/** Something the account did on its own, or did with an order: for the notification and the screen. */
data class PaperEvent(val kind: Kind, val order: PaperOrder, val trade: PaperTrade? = null) {
    enum class Kind { FILLED, REJECTED, EXPIRED, CANCELLED, STOP, TARGET, SQUARE_OFF }
}

/**
 * Paper trading: a pretend account that trades at real prices. Orders fill the way an exchange would fill them, read
 * from each stock's 5-minute candles:
 *
 * - **MARKET**: at the price now while the market is open; placed after hours (AMO), at the next session's open.
 * - **LIMIT**: a buy at or above today's price fills at once at that price; below it, once a candle's low reaches the
 *   limit, at the limit (at the open, when price opened below it). Sells the other way round.
 * - **SL-M**: a buy waits until a candle's high reaches the trigger and fills there (at the open on a gap up).
 * - **SL**: as SL-M, but fills only up to its limit; past it, it waits as a limit order.
 * - Orders are for one session (DAY): one not filled by the close expires. Intraday ones are cancelled at 15:15.
 * - Only candles that started after an order was placed count, so it never fills on a move that came before it.
 *
 * Positions: delivery (CNC) is paid in full and held across days; intraday (MIS) holds a fifth of its value as margin
 * ([PositionSize.MIS_LEVERAGE]), can be short, and is squared off at [SQUARE_OFF]. A position's stop-loss and target
 * close it at the first candle that reaches either (the stop first when one candle reaches both, at the open on a
 * gap). Every fill pays [PaperCharges]. Free of Android types so it can be unit tested.
 */
object PaperEngine {
    const val DEFAULT_CAPITAL = 1_000_000.0
    val CAPITAL_CHOICES = listOf(100_000.0, 500_000.0, 1_000_000.0, 2_500_000.0, 10_000_000.0)
    val SQUARE_OFF: LocalTime = IntradayPlan.SQUARE_OFF
    const val CANDLE_SECONDS = 300L
    private const val KEEP_ORDERS = 400
    private const val KEEP_TRADES = 1500
    private const val KEEP_EQUITY_DAYS = 400

    /** What a new order asks for, before the account checks it. */
    data class Draft(
        val symbol: String,
        val ticker: String,
        val bse: Boolean,
        val side: Side,
        val product: Product,
        val type: PaperOrder.Type,
        val qty: Int,
        val price: Double? = null,
        val trigger: Double? = null,
        val stop: Double? = null,
        val target: Double? = null,
        val source: PaperSource = PaperSource.MANUAL,
    )

    /** Why a draft cannot be placed. */
    enum class Problem {
        QTY,

        /** A LIMIT or SL order without a limit price. */
        PRICE,

        /** An SL or SL-M order without a trigger. */
        TRIGGER,

        /** An SL buy whose limit is below its trigger (a sell's above). */
        SL_PRICE,

        /** A stop buy's trigger at or below today's price (a sell's at or above): it would fill at once. */
        TRIGGER_CROSSED,

        /** No price for the stock yet, and the order needs one. */
        NO_PRICE,

        /** A new intraday position outside 9:15 to 15:15 on a market day. */
        MIS_HOURS,

        /** A delivery sell of more shares than are held (less those already being sold). */
        NO_HOLDING,
        FUNDS,

        /** A buy's stop-loss at or above its price, or a sell's at or below. */
        STOP_SIDE,

        /** A buy's target at or below its price, or a sell's at or above. */
        TARGET_SIDE,
    }

    fun newBook(capital: Double, now: Long): PaperBook = PaperBook(start = capital, cash = capital, startedAt = now)

    /** The session an order placed at [at] is for: today until 15:30 on a weekday, else the next weekday. */
    fun sessionFor(at: Long): LocalDate {
        val time = MarketClock.now(at)
        var day = time.toLocalDate()
        if (MarketClock.isWeekday(day) && time.toLocalTime() < MarketClock.CLOSE) return day
        do day = day.plusDays(1) while (!MarketClock.isWeekday(day))
        return day
    }

    /** True while the market is open at [at]. */
    fun inSession(at: Long): Boolean = MarketClock.isOpen(MarketClock.now(at))

    /** A new intraday position may be opened at [at]: a market day from 9:15 until 15:15. */
    fun misOpen(at: Long): Boolean {
        val time = MarketClock.now(at)
        return MarketClock.isOpen(time) && time.toLocalTime() < SQUARE_OFF
    }

    fun position(book: PaperBook, symbol: String, product: Product): PaperPosition? =
        book.positions.firstOrNull { it.symbol == symbol && it.product == product }

    /** The last price known for [symbol]. */
    fun price(book: PaperBook, symbol: String): Double? = book.quotes[symbol]?.price

    /** Shares of an order that close part of the current position, and shares that open or add to one. */
    private fun split(position: PaperPosition?, side: Side, qty: Int): Pair<Int, Int> {
        val held = position?.qty ?: 0
        val signed = if (side == Side.BUY) qty else -qty
        val closing = if (held != 0 && (held > 0) != (signed > 0)) min(abs(held), qty) else 0
        return closing to qty - closing
    }

    /** The money [opening] new shares at [price] take: the whole value for delivery, a fifth for intraday, plus charges. */
    fun required(product: Product, side: Side, opening: Int, price: Double, bse: Boolean): Double {
        if (opening <= 0) return 0.0
        val value = opening * price
        val charges = PaperCharges.of(product, side, value, bse).total
        return (if (product == Product.CNC) value else value / PositionSize.MIS_LEVERAGE) + charges
    }

    /** The price a pending order is reckoned at: its limit, its trigger, else the last price. */
    private fun reckon(book: PaperBook, order: PaperOrder): Double? = order.price ?: order.trigger ?: price(book, order.symbol)

    /** Money pending orders would take when they fill; held back from new orders. */
    fun blocked(book: PaperBook): Double = book.orders.filter { it.open }.sumOf { order ->
        val price = reckon(book, order) ?: return@sumOf 0.0
        required(order.product, order.side, split(position(book, order.symbol, order.product), order.side, order.qty).second, price, order.bse)
    }

    /** Cash free for a new order. */
    fun available(book: PaperBook): Double = book.cash - blocked(book)

    /** The money an intraday position holds, and the market value of the delivery shares. */
    fun margin(book: PaperBook): Double = book.positions.sumOf { it.margin }

    fun holdingsValue(book: PaperBook): Double =
        book.positions.filter { it.product == Product.CNC }.sumOf { it.qty * (price(book, it.symbol) ?: it.avg) }

    /** Open profit or loss at the last prices, before charges. */
    fun unrealized(book: PaperBook): Double = book.positions.sumOf { it.pnl(price(book, it.symbol) ?: it.avg) }

    /** What the account is worth now: cash, delivery shares at the last price, and intraday margin with its open P&L. */
    fun worth(book: PaperBook): Double =
        book.cash + holdingsValue(book) + book.positions.filter { it.product == Product.MIS }.sumOf { it.margin + it.pnl(price(book, it.symbol) ?: it.avg) }

    /** [p] in full at the account's last price ([PaperHolding]). */
    fun holding(book: PaperBook, p: PaperPosition): PaperHolding {
        val quote = book.quotes[p.symbol]
        val price = quote?.price ?: p.avg
        val shares = abs(p.qty)
        val exitSide = if (p.qty > 0) Side.SELL else Side.BUY
        fun exitCharges(at: Double) = PaperCharges.of(p.product, exitSide, shares * at, p.bse).total
        fun leaves(at: Double) = p.pnl(at) - p.entryCharges - exitCharges(at)
        // The charges barely move with the price: two steps from the average find the breakeven to a fraction of a paisa.
        val direction = if (p.qty > 0) 1 else -1
        var breakeven = p.avg
        repeat(2) { breakeven = p.avg + direction * (p.entryCharges + exitCharges(breakeven)) / shares }
        val invested = shares * p.avg
        val pnl = p.pnl(price)
        return PaperHolding(
            position = p,
            price = price,
            priceAt = quote?.at,
            invested = invested,
            value = shares * price,
            pnl = pnl,
            pnlPct = if (invested > 0.0) pnl / invested * 100.0 else 0.0,
            exitCharges = exitCharges(price),
            net = leaves(price),
            breakeven = breakeven,
            atStop = p.stop?.takeIf { quote != null }?.let(::leaves),
            atTarget = p.target?.takeIf { quote != null }?.let(::leaves),
            orders = book.orders.filter {
                it.symbol == p.symbol && it.product == p.product &&
                    (it.open || (it.status == PaperOrder.Status.FILLED && (it.doneAt ?: 0L) >= p.openedAt))
            },
        )
    }

    /** What is wrong with [draft] at [now] with the stock at [ltp]; null when it can be placed. */
    fun check(book: PaperBook, draft: Draft, ltp: Double?, now: Long): Problem? {
        if (draft.qty <= 0) return Problem.QTY
        val buy = draft.side == Side.BUY
        when (draft.type) {
            PaperOrder.Type.LIMIT -> if (!(draft.price != null && draft.price > 0.0)) return Problem.PRICE
            PaperOrder.Type.SL -> {
                if (!(draft.trigger != null && draft.trigger > 0.0)) return Problem.TRIGGER
                if (!(draft.price != null && draft.price > 0.0)) return Problem.PRICE
                if (if (buy) draft.price < draft.trigger else draft.price > draft.trigger) return Problem.SL_PRICE
            }
            PaperOrder.Type.SL_M -> if (!(draft.trigger != null && draft.trigger > 0.0)) return Problem.TRIGGER
            PaperOrder.Type.MARKET -> Unit
        }
        if (draft.type == PaperOrder.Type.MARKET && ltp == null) return Problem.NO_PRICE
        if (draft.trigger != null && draft.type != PaperOrder.Type.LIMIT && draft.type != PaperOrder.Type.MARKET && ltp != null) {
            if (if (buy) draft.trigger <= ltp else draft.trigger >= ltp) return Problem.TRIGGER_CROSSED
        }
        val position = position(book, draft.symbol, draft.product)
        val (closing, opening) = split(position, draft.side, draft.qty)
        if (draft.product == Product.CNC && !buy) {
            val selling = book.orders.filter { it.open && it.symbol == draft.symbol && it.product == Product.CNC && it.side == Side.SELL }.sumOf { it.qty }
            if (draft.qty > (position?.qty ?: 0) - selling) return Problem.NO_HOLDING
        }
        if (draft.product == Product.MIS && opening > 0 && !misOpen(now)) return Problem.MIS_HOURS
        val reference = when (draft.type) {
            PaperOrder.Type.MARKET -> ltp
            PaperOrder.Type.LIMIT -> draft.price
            else -> draft.trigger
        } ?: return Problem.NO_PRICE
        if (opening > 0 && required(draft.product, draft.side, opening, reference, draft.bse) > available(book) + 1e-6) return Problem.FUNDS
        if (closing == 0 || opening > 0) {
            draft.stop?.let { if (if (buy) it >= reference else it <= reference) return Problem.STOP_SIDE }
            draft.target?.let { if (if (buy) it <= reference else it >= reference) return Problem.TARGET_SIDE }
        }
        return null
    }

    /**
     * Places [draft] at [now] with the stock at [ltp]: checked by [check], then filled at once when the market is open
     * and the order can fill at today's price (a market order, or a limit at or past the price), else left waiting.
     */
    fun place(book: PaperBook, draft: Draft, ltp: Double?, now: Long): Pair<PaperBook, Problem?> {
        check(book, draft, ltp, now)?.let { return book to it }
        val order = PaperOrder(
            id = book.nextId,
            symbol = draft.symbol,
            ticker = draft.ticker,
            bse = draft.bse,
            side = draft.side,
            product = draft.product,
            type = draft.type,
            qty = draft.qty,
            price = draft.price?.let(OrderTicket::tick),
            trigger = draft.trigger?.let(OrderTicket::tick),
            stop = draft.stop?.let(OrderTicket::tick),
            target = draft.target?.let(OrderTicket::tick),
            source = draft.source,
            placedAt = now,
            day = sessionFor(now),
        )
        var next = book.copy(nextId = book.nextId + 1, orders = listOf(order) + book.orders)
        if (ltp != null && inSession(now)) {
            val fillsNow = when (order.type) {
                PaperOrder.Type.MARKET -> true
                PaperOrder.Type.LIMIT -> if (order.side == Side.BUY) order.price!! >= ltp else order.price!! <= ltp
                else -> false
            }
            if (fillsNow) next = fill(next, order, ltp, now, nextCandle(now / 1000), null).first
        }
        return trim(next) to null
    }

    /** Cancels a waiting order. */
    fun cancel(book: PaperBook, id: Long, now: Long): PaperBook = book.copy(
        orders = book.orders.map { if (it.id == id && it.open) it.copy(status = PaperOrder.Status.CANCELLED, doneAt = now, reason = PaperReason.CANCELLED) else it },
    )

    /**
     * Closes [position] with a market order: at [ltp] now while the market is open, else at the next session's open.
     * Waiting orders that would close it are cancelled first.
     */
    fun exit(book: PaperBook, position: PaperPosition, ltp: Double?, now: Long): Pair<PaperBook, Problem?> {
        var next = book
        book.orders.filter { it.open && it.symbol == position.symbol && it.product == position.product && it.side != position.side }
            .forEach { next = cancel(next, it.id, now) }
        return place(
            next,
            Draft(
                symbol = position.symbol,
                ticker = position.ticker,
                bse = position.bse,
                side = if (position.qty > 0) Side.SELL else Side.BUY,
                product = position.product,
                type = PaperOrder.Type.MARKET,
                qty = abs(position.qty),
                source = position.source,
            ),
            ltp ?: price(book, position.symbol),
            now,
        )
    }

    /**
     * Sets the stop-loss and target [position] is watched with, from the next candle on; a level price is already past
     * ([ltp]) is refused.
     */
    fun setLevels(book: PaperBook, position: PaperPosition, stop: Double?, target: Double?, ltp: Double?, now: Long): Pair<PaperBook, Problem?> {
        val reference = ltp ?: price(book, position.symbol) ?: position.avg
        val long = position.qty > 0
        stop?.let { if (if (long) it >= reference else it <= reference) return book to Problem.STOP_SIDE }
        target?.let { if (if (long) it <= reference else it >= reference) return book to Problem.TARGET_SIDE }
        val updated = position.copy(stop = stop?.let(OrderTicket::tick), target = target?.let(OrderTicket::tick), watchFrom = max(position.watchFrom, nextCandle(now / 1000)))
        return book.copy(positions = book.positions.map { if (it.symbol == position.symbol && it.product == position.product) updated else it }) to null
    }

    /** The start of the first 5-minute candle after [second]. */
    private fun nextCandle(second: Long): Long = (second / CANDLE_SECONDS + 1) * CANDLE_SECONDS

    /**
     * Fills [order] at [price] at [at]: the shares join or close the position, money and charges move, and a closed part
     * becomes a [PaperTrade]. [watchFrom] is where a new position's stop and target are first checked. Refused (and
     * returned rejected) when the cash is short.
     */
    private fun fill(book: PaperBook, order: PaperOrder, price: Double, at: Long, watchFrom: Long, reason: PaperReason?): Pair<PaperBook, PaperEvent> {
        val position = position(book, order.symbol, order.product)
        val (closing, opening) = split(position, order.side, order.qty)
        if (order.product == Product.CNC && order.side == Side.SELL && order.qty > (position?.qty ?: 0)) {
            return reject(book, order, at, PaperReason.NO_HOLDING)
        }
        if (opening > 0 && required(order.product, order.side, opening, price, order.bse) > book.cash + 1e-6) {
            return reject(book, order, at, PaperReason.FUNDS)
        }
        val charges = PaperCharges.of(order.product, order.side, order.qty * price, order.bse).total
        var cash = book.cash
        var trade: PaperTrade? = null
        var positions = book.positions.filterNot { it.symbol == order.symbol && it.product == order.product }
        var remaining: PaperPosition? = position
        var nextId = book.nextId
        if (closing > 0 && position != null) {
            val held = abs(position.qty)
            val share = closing.toDouble() / held
            val entryCharges = position.entryCharges * share
            val exitCharges = charges * closing / order.qty
            val gross = (price - position.avg) * closing * if (position.qty > 0) 1 else -1
            cash += if (order.product == Product.CNC) closing * price else position.margin * share + gross
            cash -= exitCharges
            trade = PaperTrade(
                id = nextId++,
                symbol = order.symbol,
                product = order.product,
                side = position.side,
                qty = closing,
                entry = position.avg,
                exit = price,
                openedAt = position.openedAt,
                closedAt = at,
                charges = entryCharges + exitCharges,
                reason = reason,
                source = position.source,
            )
            val left = held - closing
            remaining = if (left == 0) null else position.copy(
                qty = if (position.qty > 0) left else -left,
                margin = position.margin * (1.0 - share),
                entryCharges = position.entryCharges - entryCharges,
            )
        }
        if (opening > 0) {
            val openCharges = charges * opening / order.qty
            val value = opening * price
            val margin = if (order.product == Product.MIS) value / PositionSize.MIS_LEVERAGE else 0.0
            cash -= (if (order.product == Product.CNC) value else margin) + openCharges
            val signed = if (order.side == Side.BUY) opening else -opening
            val base = remaining
            remaining = if (base == null) {
                PaperPosition(
                    symbol = order.symbol,
                    ticker = order.ticker,
                    bse = order.bse,
                    product = order.product,
                    qty = signed,
                    avg = price,
                    margin = margin,
                    entryCharges = openCharges,
                    stop = order.stop,
                    target = order.target,
                    openedAt = at,
                    day = MarketClock.now(at).toLocalDate(),
                    watchFrom = watchFrom,
                    source = order.source,
                )
            } else {
                val total = abs(base.qty) + opening
                base.copy(
                    qty = base.qty + signed,
                    avg = (abs(base.qty) * base.avg + value) / total,
                    margin = base.margin + margin,
                    entryCharges = base.entryCharges + openCharges,
                    stop = order.stop ?: base.stop,
                    target = order.target ?: base.target,
                    watchFrom = max(base.watchFrom, watchFrom),
                )
            }
        }
        remaining?.let { positions = positions + it }
        val filled = order.copy(status = PaperOrder.Status.FILLED, doneAt = at, fillPrice = price, charges = charges, reason = reason ?: order.reason)
        val kind = when (reason) {
            PaperReason.STOP -> PaperEvent.Kind.STOP
            PaperReason.TARGET -> PaperEvent.Kind.TARGET
            PaperReason.SQUARE_OFF -> PaperEvent.Kind.SQUARE_OFF
            else -> PaperEvent.Kind.FILLED
        }
        val known = book.orders.any { it.id == order.id }
        val next = book.copy(
            cash = cash,
            positions = positions,
            orders = if (known) book.orders.map { if (it.id == order.id) filled else it } else listOf(filled) + book.orders,
            trades = if (trade != null) listOf(trade) + book.trades else book.trades,
            chargesPaid = book.chargesPaid + charges,
            nextId = nextId,
        )
        return next to PaperEvent(kind, filled, trade)
    }

    private fun reject(book: PaperBook, order: PaperOrder, at: Long, reason: PaperReason): Pair<PaperBook, PaperEvent> {
        val rejected = order.copy(status = PaperOrder.Status.REJECTED, doneAt = at, reason = reason)
        val known = book.orders.any { it.id == order.id }
        return book.copy(orders = if (known) book.orders.map { if (it.id == order.id) rejected else it } else listOf(rejected) + book.orders) to
            PaperEvent(PaperEvent.Kind.REJECTED, rejected)
    }

    /** An order the account places itself to close [position] (stop, target or square-off). */
    private fun closer(book: PaperBook, position: PaperPosition, at: Long, reason: PaperReason): PaperOrder = PaperOrder(
        id = book.nextId,
        symbol = position.symbol,
        ticker = position.ticker,
        bse = position.bse,
        side = if (position.qty > 0) Side.SELL else Side.BUY,
        product = position.product,
        type = if (reason == PaperReason.SQUARE_OFF) PaperOrder.Type.MARKET else if (reason == PaperReason.STOP) PaperOrder.Type.SL_M else PaperOrder.Type.LIMIT,
        qty = abs(position.qty),
        price = if (reason == PaperReason.TARGET) position.target else null,
        trigger = if (reason == PaperReason.STOP) position.stop else null,
        source = position.source,
        placedAt = at,
        day = MarketClock.now(at).toLocalDate(),
        reason = reason,
    )

    /**
     * Closes [position] at [price] for [reason]; the user's own waiting orders that would have closed it go, since
     * there is nothing left for them to close.
     */
    private fun closeBy(book: PaperBook, position: PaperPosition, price: Double, at: Long, reason: PaperReason): Pair<PaperBook, PaperEvent> {
        val order = closer(book, position, at, reason)
        val (filled, event) = fill(book.copy(nextId = book.nextId + 1), order, price, at, 0L, reason)
        val moot = filled.orders.filter { it.open && it.symbol == position.symbol && it.product == position.product && it.side == order.side }
        return moot.fold(filled) { b, o -> cancel(b, o.id, at) } to event
    }

    /**
     * [order]'s fill on candle [c], or null; an SL order whose trigger [c] reached but whose limit it did not comes back
     * marked [PaperOrder.triggered].
     */
    internal fun tryFill(order: PaperOrder, c: IntradayPlan.Candle): Pair<PaperOrder, Double?> {
        val buy = order.side == Side.BUY
        fun limitFill(limit: Double): Double? = if (buy) {
            if (c.low <= limit) min(limit, c.open) else null
        } else {
            if (c.high >= limit) max(limit, c.open) else null
        }
        return when (order.type) {
            PaperOrder.Type.MARKET -> order to c.open
            PaperOrder.Type.LIMIT -> order to limitFill(order.price!!)
            PaperOrder.Type.SL_M -> {
                val trigger = order.trigger!!
                order to if (buy) {
                    if (c.high >= trigger) max(trigger, c.open) else null
                } else {
                    if (c.low <= trigger) min(trigger, c.open) else null
                }
            }
            PaperOrder.Type.SL -> {
                val limit = order.price!!
                if (order.triggered) return order to limitFill(limit)
                val trigger = order.trigger!!
                val reached = if (buy) c.high >= trigger else c.low <= trigger
                if (!reached) return order to null
                val at = if (buy) max(trigger, c.open) else min(trigger, c.open)
                val within = if (buy) at <= limit else at >= limit
                when {
                    within -> order to at
                    (if (buy) c.low <= limit else c.high >= limit) -> order to limit
                    else -> order.copy(triggered = true) to null
                }
            }
        }
    }

    /**
     * Runs [symbol]'s 5-minute [candles] (oldest first) through the account up to [now]: waiting orders fill or expire,
     * positions' stops and targets close them, intraday positions are squared off at 15:15, and the last price is kept.
     */
    fun process(book: PaperBook, symbol: String, candles: List<IntradayPlan.Candle>, now: Long): Pair<PaperBook, List<PaperEvent>> {
        var b = book
        val events = mutableListOf<PaperEvent>()
        val sorted = candles.sortedBy { it.at }
        var lastOfDay: IntradayPlan.Candle? = null
        for (c in sorted) {
            val day = MarketClock.dayOf(c.at)
            val time = MarketClock.timeOf(c.at)
            // A new day: intraday positions of an earlier day still open are squared off at that day's last price (the
            // last one the account knew, when these candles do not reach back to that day).
            val previous = lastOfDay
            if (previous == null || MarketClock.dayOf(previous.at) != day) {
                b.positions.filter { it.symbol == symbol && it.product == Product.MIS && it.day.isBefore(day) }.forEach { p ->
                    val price = previous?.close ?: b.quotes[symbol]?.price ?: p.avg
                    val at = previous?.let { (it.at + CANDLE_SECONDS) * 1000 } ?: (c.at * 1000)
                    val (nb, event) = closeBy(b, p, price, at, PaperReason.SQUARE_OFF)
                    b = nb
                    events += event
                }
            }
            lastOfDay = c
            // Orders: expire past their session, cancel intraday ones at 15:15, then fill.
            for (order in b.orders.filter { it.open && it.symbol == symbol }) {
                if (c.at * 1000 < order.placedAt) continue
                var o = order
                if (!o.sessionSeen && !day.isBefore(o.day)) {
                    // An order placed during its session keeps that session; one placed after hours (AMO) waits for the
                    // next session with candles, past a holiday.
                    o = if (inSession(o.placedAt)) o.copy(sessionSeen = true) else o.copy(sessionSeen = true, day = day)
                }
                when {
                    o.sessionSeen && day.isAfter(o.day) -> {
                        b = replace(b, o.copy(status = PaperOrder.Status.EXPIRED, doneAt = c.at * 1000, reason = PaperReason.EXPIRED))
                        events += PaperEvent(PaperEvent.Kind.EXPIRED, b.orders.first { it.id == o.id })
                        continue
                    }
                    o.product == Product.MIS && day == o.day && time >= SQUARE_OFF -> {
                        b = replace(b, o.copy(status = PaperOrder.Status.CANCELLED, doneAt = c.at * 1000, reason = PaperReason.MIS_CLOSED))
                        events += PaperEvent(PaperEvent.Kind.CANCELLED, b.orders.first { it.id == o.id })
                        continue
                    }
                    day.isBefore(o.day) -> continue
                }
                val (tried, price) = tryFill(o, c)
                if (price == null) {
                    if (tried != order) b = replace(b, tried)
                    continue
                }
                b = replace(b, tried)
                val (nb, event) = fill(b, tried, price, c.at * 1000, c.at + CANDLE_SECONDS, null)
                b = nb
                events += event
            }
            // Positions: square-off, then stop and target.
            for (p in b.positions.filter { it.symbol == symbol }) {
                if (p.product == Product.MIS && day == p.day && time >= SQUARE_OFF) {
                    val (nb, event) = closeBy(b, p, c.open, c.at * 1000, PaperReason.SQUARE_OFF)
                    b = nb
                    events += event
                    continue
                }
                if (c.at < p.watchFrom) continue
                val long = p.qty > 0
                val stop = p.stop
                val target = p.target
                val stopHit = stop != null && (if (long) c.low <= stop else c.high >= stop)
                val targetHit = target != null && (if (long) c.high >= target else c.low <= target)
                when {
                    stopHit -> {
                        val at = if (long) min(stop!!, c.open) else max(stop!!, c.open)
                        val (nb, event) = closeBy(b, p, at, c.at * 1000, PaperReason.STOP)
                        b = nb
                        events += event
                    }
                    targetHit -> {
                        val at = if (long) max(target!!, c.open) else min(target!!, c.open)
                        val (nb, event) = closeBy(b, p, at, c.at * 1000, PaperReason.TARGET)
                        b = nb
                        events += event
                    }
                    else -> b = b.copy(positions = b.positions.map { if (it == p) it.copy(watchFrom = max(it.watchFrom, c.at)) else it })
                }
            }
        }
        sorted.lastOrNull()?.let { last -> b = b.copy(quotes = b.quotes + (symbol to PaperQuote(last.close, last.at))) }
        // The clock: a session over by [now] ends its orders, and an intraday position left from an earlier day closes.
        val clock = MarketClock.now(now)
        for (order in b.orders.filter { it.open && it.symbol == symbol }) {
            val started = order.sessionSeen || inSession(order.placedAt)
            val over = started && (clock.toLocalDate().isAfter(order.day) || (clock.toLocalDate() == order.day && clock.toLocalTime() >= MarketClock.CLOSE))
            if (over) {
                b = replace(b, order.copy(status = PaperOrder.Status.EXPIRED, doneAt = now, reason = PaperReason.EXPIRED))
                events += PaperEvent(PaperEvent.Kind.EXPIRED, b.orders.first { it.id == order.id })
            }
        }
        val last = sorted.lastOrNull()
        if (last != null) {
            val lastDay = MarketClock.dayOf(last.at)
            val pastClose = clock.toLocalDate().isAfter(lastDay) || clock.toLocalTime() >= MarketClock.CLOSE
            b.positions.filter { it.symbol == symbol && it.product == Product.MIS && !it.day.isAfter(lastDay) && pastClose }.forEach { p ->
                val (nb, event) = closeBy(b, p, last.close, now, PaperReason.SQUARE_OFF)
                b = nb
                events += event
            }
        }
        return trim(b) to events
    }

    /** True when [order] would open (or add to) a position rather than only close one. */
    private fun opens(book: PaperBook, order: PaperOrder): Boolean = split(position(book, order.symbol, order.product), order.side, order.qty).second > 0

    private fun replace(book: PaperBook, order: PaperOrder): PaperBook = book.copy(orders = book.orders.map { if (it.id == order.id) order else it })

    /** Writes the account's worth for [now]'s day. */
    fun mark(book: PaperBook, now: Long): PaperBook {
        val day = MarketClock.now(now).toLocalDate()
        val worth = worth(book)
        val kept = book.equity.filter { it.first != day } + (day to worth)
        return book.copy(equity = kept.sortedBy { it.first }.takeLast(KEEP_EQUITY_DAYS))
    }

    /** Old finished orders and trades beyond what is kept go. */
    private fun trim(book: PaperBook): PaperBook {
        if (book.orders.size <= KEEP_ORDERS && book.trades.size <= KEEP_TRADES) return book
        val open = book.orders.filter { it.open }
        val done = book.orders.filterNot { it.open }.take(KEEP_ORDERS - open.size.coerceAtMost(KEEP_ORDERS))
        return book.copy(orders = (open + done).sortedByDescending { it.id }, trades = book.trades.take(KEEP_TRADES))
    }

    /** The symbols the account needs prices for: waiting orders and open positions. */
    fun watched(book: PaperBook): Map<String, String> =
        (book.orders.filter { it.open }.map { it.symbol to it.ticker } + book.positions.map { it.symbol to it.ticker }).toMap()

    /** The oldest moment [symbol]'s candles must reach back to: its oldest waiting order or watched position. */
    fun needSince(book: PaperBook, symbol: String): Long? {
        val orders = book.orders.filter { it.open && it.symbol == symbol }.map { it.placedAt / 1000 }
        val positions = book.positions.filter { it.symbol == symbol }.map { it.watchFrom }
        return (orders + positions).minOrNull()
    }

    /** How the closed trades went. */
    data class Stats(
        val trades: Int,
        val wins: Int,
        val losses: Int,
        val net: Double,
        val gross: Double,
        val charges: Double,
        val avgWin: Double,
        val avgLoss: Double,
        /** Money won over money lost; null with no loss. */
        val profitFactor: Double?,
        val best: PaperTrade?,
        val worst: PaperTrade?,
    ) {
        val winRate: Double get() = if (trades == 0) 0.0 else wins * 100.0 / trades
    }

    fun stats(trades: List<PaperTrade>): Stats {
        val wins = trades.filter { it.net > 0.0 }
        val losses = trades.filter { it.net <= 0.0 }
        val won = wins.sumOf { it.net }
        val lost = -losses.sumOf { it.net }
        return Stats(
            trades = trades.size,
            wins = wins.size,
            losses = losses.size,
            net = trades.sumOf { it.net },
            gross = trades.sumOf { it.gross },
            charges = trades.sumOf { it.charges },
            avgWin = if (wins.isEmpty()) 0.0 else won / wins.size,
            avgLoss = if (losses.isEmpty()) 0.0 else -lost / losses.size,
            profitFactor = if (lost > 0.0) won / lost else null,
            best = trades.maxByOrNull { it.net },
            worst = trades.minByOrNull { it.net },
        )
    }

    /** The biggest fall from a high in the daily worth, in percent. */
    fun maxDrawdown(equity: List<Pair<LocalDate, Double>>): Double {
        var peak = 0.0
        var worst = 0.0
        for ((_, value) in equity) {
            peak = max(peak, value)
            if (peak > 0.0) worst = max(worst, (peak - value) / peak * 100.0)
        }
        return worst
    }

    /** Closed trades of [day] (by when they closed), net of charges. */
    fun realizedOn(book: PaperBook, day: LocalDate): Double =
        book.trades.filter { MarketClock.now(it.closedAt).toLocalDate() == day }.sumOf { it.net }

    fun encode(book: PaperBook): String = JSONObject().apply {
        put("v", 1)
        put("start", book.start)
        put("cash", book.cash)
        put("at", book.startedAt)
        put("charges", book.chargesPaid)
        put("next", book.nextId)
        put("orders", JSONArray().apply { book.orders.forEach { put(encodeOrder(it)) } })
        put("positions", JSONArray().apply { book.positions.forEach { put(encodePosition(it)) } })
        put("trades", JSONArray().apply { book.trades.forEach { put(encodeTrade(it)) } })
        put("quotes", JSONObject().apply { book.quotes.forEach { (symbol, q) -> put(symbol, JSONArray().put(q.price).put(q.at)) } })
        put("equity", JSONArray().apply { book.equity.forEach { (day, value) -> put(JSONArray().put(day.toString()).put(value)) } })
    }.toString()

    /** The account in [text]; null when there is none or it cannot be read. */
    fun decode(text: String?): PaperBook? {
        val o = runCatching { JSONObject(text ?: return null) }.getOrNull() ?: return null
        return runCatching {
            PaperBook(
                start = o.getDouble("start"),
                cash = o.getDouble("cash"),
                startedAt = o.getLong("at"),
                chargesPaid = o.optDouble("charges", 0.0),
                nextId = o.optLong("next", 1L),
                orders = o.optJSONArray("orders").objects().mapNotNull { runCatching { decodeOrder(it) }.getOrNull() },
                positions = o.optJSONArray("positions").objects().mapNotNull { runCatching { decodePosition(it) }.getOrNull() },
                trades = o.optJSONArray("trades").objects().mapNotNull { runCatching { decodeTrade(it) }.getOrNull() },
                quotes = o.optJSONObject("quotes")?.let { q ->
                    q.keys().asSequence().mapNotNull { key ->
                        runCatching { q.getJSONArray(key).let { key to PaperQuote(it.getDouble(0), it.getLong(1)) } }.getOrNull()
                    }.toMap()
                }.orEmpty(),
                equity = o.optJSONArray("equity")?.let { a ->
                    (0 until a.length()).mapNotNull { i -> runCatching { a.getJSONArray(i).let { LocalDate.parse(it.getString(0)) to it.getDouble(1) } }.getOrNull() }
                }.orEmpty(),
            )
        }.getOrNull()
    }

    private fun JSONArray?.objects(): List<JSONObject> = if (this == null) emptyList() else (0 until length()).mapNotNull { optJSONObject(it) }

    private fun JSONObject.putOpt(name: String, value: Any?): JSONObject = if (value == null) this else put(name, value)

    private fun JSONObject.double(name: String): Double? = if (has(name)) getDouble(name) else null

    private fun encodeOrder(o: PaperOrder) = JSONObject()
        .put("id", o.id).put("s", o.symbol).put("tk", o.ticker).put("bse", o.bse).put("side", o.side.name).put("p", o.product.name)
        .put("type", o.type.name).put("q", o.qty).putOpt("lp", o.price).putOpt("tr", o.trigger).putOpt("sl", o.stop).putOpt("t", o.target)
        .put("src", o.source.name).put("at", o.placedAt).put("d", o.day.toString()).put("st", o.status.name).put("trg", o.triggered)
        .put("seen", o.sessionSeen).putOpt("done", o.doneAt).putOpt("fp", o.fillPrice).put("ch", o.charges).putOpt("r", o.reason?.name)

    private fun decodeOrder(o: JSONObject) = PaperOrder(
        id = o.getLong("id"),
        symbol = o.getString("s"),
        ticker = o.getString("tk"),
        bse = o.optBoolean("bse"),
        side = Side.valueOf(o.getString("side")),
        product = Product.valueOf(o.getString("p")),
        type = PaperOrder.Type.valueOf(o.getString("type")),
        qty = o.getInt("q"),
        price = o.double("lp"),
        trigger = o.double("tr"),
        stop = o.double("sl"),
        target = o.double("t"),
        source = runCatching { PaperSource.valueOf(o.getString("src")) }.getOrDefault(PaperSource.MANUAL),
        placedAt = o.getLong("at"),
        day = LocalDate.parse(o.getString("d")),
        status = PaperOrder.Status.valueOf(o.getString("st")),
        triggered = o.optBoolean("trg"),
        sessionSeen = o.optBoolean("seen"),
        doneAt = if (o.has("done")) o.getLong("done") else null,
        fillPrice = o.double("fp"),
        charges = o.optDouble("ch", 0.0),
        reason = if (o.has("r")) PaperReason.valueOf(o.getString("r")) else null,
    )

    private fun encodePosition(p: PaperPosition) = JSONObject()
        .put("s", p.symbol).put("tk", p.ticker).put("bse", p.bse).put("p", p.product.name).put("q", p.qty).put("avg", p.avg)
        .put("m", p.margin).put("ec", p.entryCharges).putOpt("sl", p.stop).putOpt("t", p.target).put("at", p.openedAt)
        .put("d", p.day.toString()).put("wf", p.watchFrom).put("src", p.source.name)

    private fun decodePosition(o: JSONObject) = PaperPosition(
        symbol = o.getString("s"),
        ticker = o.getString("tk"),
        bse = o.optBoolean("bse"),
        product = Product.valueOf(o.getString("p")),
        qty = o.getInt("q"),
        avg = o.getDouble("avg"),
        margin = o.optDouble("m", 0.0),
        entryCharges = o.optDouble("ec", 0.0),
        stop = o.double("sl"),
        target = o.double("t"),
        openedAt = o.getLong("at"),
        day = LocalDate.parse(o.getString("d")),
        watchFrom = o.optLong("wf"),
        source = runCatching { PaperSource.valueOf(o.getString("src")) }.getOrDefault(PaperSource.MANUAL),
    )

    private fun encodeTrade(t: PaperTrade) = JSONObject()
        .put("id", t.id).put("s", t.symbol).put("p", t.product.name).put("side", t.side.name).put("q", t.qty).put("e", t.entry)
        .put("x", t.exit).put("oa", t.openedAt).put("ca", t.closedAt).put("ch", t.charges).putOpt("r", t.reason?.name).put("src", t.source.name)

    private fun decodeTrade(o: JSONObject) = PaperTrade(
        id = o.getLong("id"),
        symbol = o.getString("s"),
        product = Product.valueOf(o.getString("p")),
        side = Side.valueOf(o.getString("side")),
        qty = o.getInt("q"),
        entry = o.getDouble("e"),
        exit = o.getDouble("x"),
        openedAt = o.getLong("oa"),
        closedAt = o.getLong("ca"),
        charges = o.optDouble("ch", 0.0),
        reason = if (o.has("r")) PaperReason.valueOf(o.getString("r")) else null,
        source = runCatching { PaperSource.valueOf(o.getString("src")) }.getOrDefault(PaperSource.MANUAL),
    )
}
