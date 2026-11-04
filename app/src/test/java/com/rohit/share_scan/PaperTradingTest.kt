package com.rohit.share_scan

import com.rohit.share_scan.OrderTicket.Side
import com.rohit.share_scan.PaperEngine.Draft
import com.rohit.share_scan.PaperEngine.Problem
import com.rohit.share_scan.PaperOrder.Status
import com.rohit.share_scan.PaperOrder.Type
import com.rohit.share_scan.PositionSize.Product
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class PaperTradingTest {
    private val wed = LocalDate.of(2026, 11, 4)
    private val thu = wed.plusDays(1)

    private fun sec(day: LocalDate, h: Int, m: Int): Long = day.atTime(h, m).atZone(MarketClock.INDIA).toEpochSecond()
    private fun ms(day: LocalDate, h: Int, m: Int): Long = sec(day, h, m) * 1000

    private fun candle(day: LocalDate, h: Int, m: Int, open: Double, high: Double, low: Double, close: Double) =
        IntradayPlan.Candle(sec(day, h, m), open, high, low, close, 1000.0)

    private fun book(capital: Double = 1_000_000.0) = PaperEngine.newBook(capital, ms(wed, 9, 0))

    private fun draft(
        side: Side = Side.BUY,
        product: Product = Product.CNC,
        type: Type = Type.MARKET,
        qty: Int = 10,
        price: Double? = null,
        trigger: Double? = null,
        stop: Double? = null,
        target: Double? = null,
    ) = Draft("ABC", "ABC.NS", false, side, product, type, qty, price, trigger, stop, target)

    private fun place(b: PaperBook, d: Draft, ltp: Double?, now: Long): PaperBook {
        val (next, problem) = PaperEngine.place(b, d, ltp, now)
        assertNull("$problem", problem)
        return next
    }

    @Test
    fun chargesMatchTheBrokersPublishedRates() {
        val buy = PaperCharges.of(Product.MIS, Side.BUY, 100_000.0)
        val sell = PaperCharges.of(Product.MIS, Side.SELL, 110_000.0)
        assertEquals(20.0, buy.brokerage, 1e-9) // 0.03% would be ₹30: capped at ₹20
        assertEquals(0.0, buy.stt, 1e-9)
        assertEquals(3.0, buy.stamp, 1e-9)
        assertEquals(30.2226, buy.total, 1e-4)
        assertEquals(27.5, sell.stt, 1e-9)
        assertEquals(55.08486, sell.total, 1e-4)
        val dBuy = PaperCharges.of(Product.CNC, Side.BUY, 100_000.0)
        val dSell = PaperCharges.of(Product.CNC, Side.SELL, 110_000.0)
        assertEquals(0.0, dBuy.brokerage, 1e-9)
        assertEquals(118.6226, dBuy.total, 1e-4)
        assertEquals(15.34, dSell.dp, 1e-9)
        assertEquals(129.32486, dSell.total, 1e-4)
        assertEquals(3.0, PaperCharges.of(Product.MIS, Side.BUY, 10_000.0).brokerage, 1e-9) // 0.03% under ₹20
    }

    @Test
    fun aMarketOrderFillsAtOnceAtTodaysPriceAndPaysForTheShares() {
        val b = place(book(), draft(qty = 10), 1000.0, ms(wed, 10, 2))
        val order = b.orders.single()
        assertEquals(Status.FILLED, order.status)
        assertEquals(1000.0, order.fillPrice!!, 1e-9)
        val charges = PaperCharges.of(Product.CNC, Side.BUY, 10_000.0).total
        assertEquals(1_000_000.0 - 10_000.0 - charges, b.cash, 1e-6)
        val position = b.positions.single()
        assertEquals(10, position.qty)
        assertEquals(1_000_000.0 - charges, PaperEngine.worth(b.copy(quotes = mapOf("ABC" to PaperQuote(1000.0, 0)))), 1e-6)
    }

    @Test
    fun aLimitBuyBelowThePriceWaitsForALowThatReachesItAndNeverForOneBeforeIt() {
        var b = place(book(), draft(type = Type.LIMIT, price = 990.0), 1000.0, ms(wed, 10, 2))
        assertEquals(Status.PENDING, b.orders.single().status)
        // The 10:00 candle began before the order: its low of 985 does not count.
        b = PaperEngine.process(b, "ABC", listOf(candle(wed, 10, 0, 1000.0, 1001.0, 985.0, 998.0)), ms(wed, 10, 4)).first
        assertEquals(Status.PENDING, b.orders.single().status)
        val (filled, events) = PaperEngine.process(
            b,
            "ABC",
            listOf(candle(wed, 10, 0, 1000.0, 1001.0, 985.0, 998.0), candle(wed, 10, 5, 998.0, 999.0, 989.0, 995.0)),
            ms(wed, 10, 9),
        )
        assertEquals(Status.FILLED, filled.orders.single().status)
        assertEquals(990.0, filled.orders.single().fillPrice!!, 1e-9)
        assertEquals(PaperEvent.Kind.FILLED, events.single().kind)
        assertEquals(995.0, PaperEngine.price(filled, "ABC")!!, 1e-9)
        // Opened below the limit: filled at the open, the better price.
        val gap = place(book(), draft(type = Type.LIMIT, price = 990.0), 1000.0, ms(wed, 10, 2))
        val gapped = PaperEngine.process(gap, "ABC", listOf(candle(wed, 10, 5, 980.0, 984.0, 978.0, 982.0)), ms(wed, 10, 9)).first
        assertEquals(980.0, gapped.orders.single().fillPrice!!, 1e-9)
    }

    @Test
    fun aMarketableLimitFillsAtOnceAtThePrice() {
        val b = place(book(), draft(type = Type.LIMIT, price = 1010.0), 1000.0, ms(wed, 10, 2))
        assertEquals(1000.0, b.orders.single().fillPrice!!, 1e-9)
    }

    @Test
    fun stopOrdersWaitForTheirTriggerAndATriggerAlreadyPassedIsRefused() {
        val (_, crossed) = PaperEngine.place(book(), draft(type = Type.SL_M, trigger = 995.0), 1000.0, ms(wed, 10, 2))
        assertEquals(Problem.TRIGGER_CROSSED, crossed)
        var b = place(book(), draft(type = Type.SL_M, trigger = 1010.0), 1000.0, ms(wed, 10, 2))
        b = PaperEngine.process(b, "ABC", listOf(candle(wed, 10, 5, 1005.0, 1012.0, 1004.0, 1011.0)), ms(wed, 10, 9)).first
        assertEquals(1010.0, b.orders.single().fillPrice!!, 1e-9)
        // A gap past the trigger fills at the open.
        var g = place(book(), draft(type = Type.SL_M, trigger = 1010.0), 1000.0, ms(wed, 10, 2))
        g = PaperEngine.process(g, "ABC", listOf(candle(wed, 10, 5, 1020.0, 1025.0, 1018.0, 1022.0)), ms(wed, 10, 9)).first
        assertEquals(1020.0, g.orders.single().fillPrice!!, 1e-9)
    }

    @Test
    fun anSlOrderPastItsLimitWaitsAsALimitOrder() {
        var b = place(book(), draft(type = Type.SL, trigger = 1010.0, price = 1015.0), 1000.0, ms(wed, 10, 2))
        b = PaperEngine.process(b, "ABC", listOf(candle(wed, 10, 5, 1020.0, 1030.0, 1018.0, 1025.0)), ms(wed, 10, 9)).first
        val waiting = b.orders.single()
        assertEquals(Status.PENDING, waiting.status)
        assertTrue(waiting.triggered)
        b = PaperEngine.process(
            b,
            "ABC",
            listOf(candle(wed, 10, 5, 1020.0, 1030.0, 1018.0, 1025.0), candle(wed, 10, 10, 1022.0, 1023.0, 1012.0, 1014.0)),
            ms(wed, 10, 14),
        ).first
        assertEquals(1015.0, b.orders.single().fillPrice!!, 1e-9)
        val (_, bad) = PaperEngine.place(book(), draft(type = Type.SL, trigger = 1010.0, price = 1005.0), 1000.0, ms(wed, 10, 2))
        assertEquals(Problem.SL_PRICE, bad)
    }

    @Test
    fun aPositionClosesAtItsStopOrTargetAndAtTheStopWhenOneCandleReachesBoth() {
        val start = place(book(), draft(qty = 10, stop = 980.0, target = 1040.0), 1000.0, ms(wed, 10, 2))
        // The fill's own candle (10:00) is not checked; 10:05 reaches both: the stop counts.
        val (stopped, events) = PaperEngine.process(
            start,
            "ABC",
            listOf(candle(wed, 10, 0, 1000.0, 1000.0, 970.0, 990.0), candle(wed, 10, 5, 1000.0, 1045.0, 975.0, 1000.0)),
            ms(wed, 10, 9),
        )
        assertTrue(stopped.positions.isEmpty())
        assertEquals(PaperEvent.Kind.STOP, events.single().kind)
        val trade = stopped.trades.single()
        assertEquals(980.0, trade.exit, 1e-9)
        assertEquals(PaperReason.STOP, trade.reason)
        assertEquals(-200.0, trade.gross, 1e-9)
        // A gap over the target fills at the open.
        val (won, _) = PaperEngine.process(start, "ABC", listOf(candle(wed, 10, 5, 1050.0, 1055.0, 1048.0, 1052.0)), ms(wed, 10, 9))
        assertEquals(1050.0, won.trades.single().exit, 1e-9)
        assertEquals(PaperReason.TARGET, won.trades.single().reason)
        // Every rupee is accounted for: cash is the start plus the trade's net.
        assertEquals(1_000_000.0 + won.trades.single().net, won.cash, 1e-6)
    }

    @Test
    fun intradayHoldsAFifthAsMarginCanGoShortAndIsSquaredOffAt1515() {
        var b = place(book(), draft(side = Side.SELL, product = Product.MIS, qty = 100), 1000.0, ms(wed, 11, 0))
        val short = b.positions.single()
        assertEquals(-100, short.qty)
        assertEquals(20_000.0, short.margin, 1e-9)
        val sellCharges = PaperCharges.of(Product.MIS, Side.SELL, 100_000.0).total
        assertEquals(1_000_000.0 - 20_000.0 - sellCharges, b.cash, 1e-6)
        val (squared, events) = PaperEngine.process(
            b,
            "ABC",
            listOf(candle(wed, 15, 10, 980.0, 982.0, 975.0, 978.0), candle(wed, 15, 15, 970.0, 972.0, 968.0, 971.0)),
            ms(wed, 15, 16),
        )
        b = squared
        assertTrue(b.positions.isEmpty())
        assertEquals(PaperEvent.Kind.SQUARE_OFF, events.single().kind)
        val trade = b.trades.single()
        assertEquals(Side.SELL, trade.side)
        assertEquals(970.0, trade.exit, 1e-9)
        assertEquals(3000.0, trade.gross, 1e-9)
        assertEquals(1_000_000.0 + trade.net, b.cash, 1e-6)
    }

    @Test
    fun theAccountRefusesWhatABrokerWould() {
        // No new intraday position from 15:15.
        assertEquals(Problem.MIS_HOURS, PaperEngine.place(book(), draft(product = Product.MIS), 1000.0, ms(wed, 15, 16)).second)
        // No delivery sell without the shares.
        assertEquals(Problem.NO_HOLDING, PaperEngine.place(book(), draft(side = Side.SELL), 1000.0, ms(wed, 10, 0)).second)
        // Not more than the cash; intraday may take five times it.
        assertEquals(Problem.FUNDS, PaperEngine.place(book(100_000.0), draft(qty = 101), 1000.0, ms(wed, 10, 0)).second)
        assertNull(PaperEngine.place(book(100_000.0), draft(product = Product.MIS, qty = 490), 1000.0, ms(wed, 10, 0)).second)
        // A buy's stop under the price, its target over it.
        assertEquals(Problem.STOP_SIDE, PaperEngine.place(book(), draft(stop = 1001.0), 1000.0, ms(wed, 10, 0)).second)
        assertEquals(Problem.TARGET_SIDE, PaperEngine.place(book(), draft(target = 999.0), 1000.0, ms(wed, 10, 0)).second)
        // Money held for a waiting order is not free for the next one.
        val waiting = place(book(100_000.0), draft(type = Type.LIMIT, price = 900.0, qty = 100), 1000.0, ms(wed, 10, 0))
        assertEquals(Problem.FUNDS, PaperEngine.place(waiting, draft(qty = 20), 1000.0, ms(wed, 10, 1)).second)
    }

    @Test
    fun aDayOrderExpiresAtTheCloseAndAnAfterHoursOrderFillsAtTheNextOpen() {
        var b = place(book(), draft(type = Type.LIMIT, price = 900.0), 1000.0, ms(wed, 15, 28))
        val (expired, events) = PaperEngine.process(b, "ABC", listOf(candle(wed, 15, 25, 1000.0, 1001.0, 999.0, 1000.0)), ms(wed, 15, 40))
        assertEquals(Status.EXPIRED, expired.orders.single().status)
        assertEquals(PaperEvent.Kind.EXPIRED, events.single().kind)
        // Placed after the close: for Thursday, at its open.
        b = place(book(), draft(qty = 5), 1000.0, ms(wed, 20, 0))
        assertEquals(thu, b.orders.single().day)
        assertEquals(Status.PENDING, b.orders.single().status)
        b = PaperEngine.process(b, "ABC", listOf(candle(wed, 15, 25, 1000.0, 1001.0, 999.0, 1000.0), candle(thu, 9, 15, 1012.0, 1020.0, 1010.0, 1015.0)), ms(thu, 9, 21)).first
        assertEquals(Status.FILLED, b.orders.single().status)
        assertEquals(1012.0, b.orders.single().fillPrice!!, 1e-9)
    }

    @Test
    fun aWaitingIntradayOrderIsCancelledAt1515() {
        var b = place(book(), draft(product = Product.MIS, type = Type.LIMIT, price = 950.0), 1000.0, ms(wed, 14, 0))
        b = PaperEngine.process(b, "ABC", listOf(candle(wed, 15, 15, 990.0, 991.0, 989.0, 990.0)), ms(wed, 15, 16)).first
        assertEquals(Status.CANCELLED, b.orders.single().status)
        assertEquals(PaperReason.MIS_CLOSED, b.orders.single().reason)
    }

    @Test
    fun anExitOrderLeftWaitingGoesWhenTheStopClosesThePosition() {
        var b = place(book(), draft(product = Product.MIS, qty = 10, stop = 980.0), 1000.0, ms(wed, 10, 2))
        b = place(b, draft(side = Side.SELL, product = Product.MIS, type = Type.LIMIT, price = 1050.0, qty = 10), 1000.0, ms(wed, 10, 3))
        b = PaperEngine.process(b, "ABC", listOf(candle(wed, 10, 5, 990.0, 991.0, 975.0, 978.0)), ms(wed, 10, 9)).first
        assertTrue(b.positions.isEmpty())
        val exit = b.orders.first { it.type == Type.LIMIT }
        assertEquals(Status.CANCELLED, exit.status)
    }

    @Test
    fun exitClosesAtThePriceAndLevelsCanBeMoved() {
        var b = place(book(), draft(qty = 10, stop = 950.0), 1000.0, ms(wed, 10, 2))
        val position = b.positions.single()
        assertEquals(Problem.STOP_SIDE, PaperEngine.setLevels(b, position, 1010.0, null, 1005.0, ms(wed, 10, 30)).second)
        b = PaperEngine.setLevels(b, position, 990.0, 1100.0, 1005.0, ms(wed, 10, 30)).first
        assertEquals(990.0, b.positions.single().stop!!, 1e-9)
        b = PaperEngine.exit(b, b.positions.single(), 1020.0, ms(wed, 11, 0)).first
        assertTrue(b.positions.isEmpty())
        assertEquals(200.0, b.trades.single().gross, 1e-9)
    }

    @Test
    fun statsCountWinsLossesAndTheProfitFactor() {
        fun trade(entry: Double, exit: Double) =
            PaperTrade(0, "ABC", Product.CNC, Side.BUY, 10, entry, exit, 0, 0, 0.0, null, PaperSource.MANUAL)
        val stats = PaperEngine.stats(listOf(trade(100.0, 110.0), trade(100.0, 120.0), trade(100.0, 95.0)))
        assertEquals(3, stats.trades)
        assertEquals(2, stats.wins)
        assertEquals(66.67, stats.winRate, 0.01)
        assertEquals(250.0, stats.net, 1e-9)
        assertEquals(6.0, stats.profitFactor!!, 1e-9)
        assertEquals(150.0, stats.avgWin, 1e-9)
        assertEquals(-50.0, stats.avgLoss, 1e-9)
        assertEquals(20.0, PaperEngine.maxDrawdown(listOf(wed to 100.0, thu to 80.0, thu.plusDays(1) to 120.0)), 1e-9)
    }

    @Test
    fun theAccountSurvivesBeingWrittenAndReadBack() {
        var b = place(book(), draft(qty = 10, stop = 950.0, target = 1100.0), 1000.0, ms(wed, 10, 2))
        b = place(b, draft(type = Type.LIMIT, price = 900.0, qty = 3), 1000.0, ms(wed, 10, 3))
        b = PaperEngine.mark(b.copy(quotes = mapOf("ABC" to PaperQuote(1001.0, sec(wed, 10, 0)))), ms(wed, 10, 4))
        val back = PaperEngine.decode(PaperEngine.encode(b))
        assertNotNull(back)
        assertEquals(b, back)
        assertNull(PaperEngine.decode("not json"))
    }
}
