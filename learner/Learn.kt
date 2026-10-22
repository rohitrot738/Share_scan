// The daily learner behind the app's learned setups (.github/workflows/setups-learn.yml). It is compiled together
// with the app's own scoring code (BreakoutSignals, TradePlan, SetupCondition, PlanOutcome, SetupMiner, SetupBook),
// so every plan it judges is the plan the app would have shown on that day.
//
//     kotlinc ... -d out && java -cp out:... com.rohit.share_scan.learner.LearnKt setups.json 90
//
// For every stock the app scans, it reads two years of daily candles. At each past close it scores the stock on the
// six months before (as the app's chart has them), notes the setup conditions and whether the stock rallied after, and
// draws the trade plan. Every plan is then judged under the 30,000 stops, Target 1s and time limits PlanTuner tries;
// it keeps the shape that did best, and SetupMiner keeps the setups that won under it, drops the ones that stopped winning,
// learns the mistakes (setups whose trades went the wrong way far more often than the market's) and the setups after
// which stocks rallied. Setups that did better still with a stop and target of their own get them. The result
// replaces setups.json.
package com.rohit.share_scan.learner

import com.rohit.share_scan.BreakoutSetup
import com.rohit.share_scan.BreakoutSignals
import com.rohit.share_scan.DayScore
import com.rohit.share_scan.Listing
import com.rohit.share_scan.Market
import com.rohit.share_scan.MarketClock
import com.rohit.share_scan.NseScanner
import com.rohit.share_scan.PlanOutcome
import com.rohit.share_scan.PlanShape
import com.rohit.share_scan.PlanTuner
import com.rohit.share_scan.ScanResult
import com.rohit.share_scan.SetupBook
import com.rohit.share_scan.SetupCondition
import com.rohit.share_scan.SetupMiner
import com.rohit.share_scan.TradePlan
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Collections
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

private const val NSE_CSV = "https://archives.nseindia.com/content/equities/EQUITY_L.csv"
private val SME_CSVS = listOf(
    "https://nsearchives.nseindia.com/emerge/corporates/content/SME_EQUITY_L.csv",
    "https://archives.nseindia.com/emerge/corporates/content/SME_EQUITY_L.csv",
)
private const val BSE_BHAVCOPY = "https://www.bseindia.com/download/BhavCopy/Equity/BhavCopy_BSE_CM_0_0_0_%s_F_0000.CSV"
private const val BROWSER = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/129.0 Mobile Safari/537.36"
private val INDIA: ZoneId = ZoneId.of("Asia/Kolkata")

/** Sessions the app's six-month chart holds: each past close is scored on this many sessions, as the app would. */
private const val WINDOW = 125
private const val THREADS = 6

private fun log(message: String) = println("${LocalDate.now(INDIA)} ${java.time.LocalTime.now(INDIA).withNano(0)} $message")

private fun get(url: String, referer: String? = null, timeoutMs: Int = 30_000): Pair<Int, String> {
    val c = (URL(url).openConnection() as HttpURLConnection).apply {
        connectTimeout = timeoutMs
        readTimeout = timeoutMs
        setRequestProperty("User-Agent", BROWSER)
        setRequestProperty("Accept", "*/*")
        referer?.let { setRequestProperty("Referer", it) }
    }
    return try {
        val status = c.responseCode
        status to if (status in 200..299) c.inputStream.bufferedReader().use { it.readText() } else ""
    } catch (e: Exception) {
        -1 to ""
    } finally {
        c.disconnect()
    }
}

/** The app's markets: NSE's main board, NSE SME and the BSE shares not on NSE (each when its list loads). */
private fun listings(today: LocalDate): List<Listing> {
    val main = NseScanner.parseListings(get(NSE_CSV).second, Market.NSE)
    check(main.size >= 1000) { "NSE equity list unavailable (${main.size} rows)" }
    val sme = SME_CSVS.asSequence().map { NseScanner.parseListings(get(it).second, Market.NSE_SME) }.firstOrNull { it.isNotEmpty() }.orEmpty()
    val format = DateTimeFormatter.ofPattern("yyyyMMdd", Locale.US)
    val bse = (0L until 8L).asSequence()
        .map { NseScanner.parseBseBhavcopy(get(BSE_BHAVCOPY.format(today.minusDays(it).format(format)), "https://www.bseindia.com/", 60_000).second) }
        .firstOrNull { it.size >= 500 }.orEmpty()
    log("lists: nse ${main.size}, sme ${sme.size}, bse ${bse.size}")
    return NseScanner.combine(main, sme, bse)
}

private class Chart(val days: LongArray, val close: DoubleArray, val high: DoubleArray, val low: DoubleArray, val volume: DoubleArray)

/** Two years of daily candles from the first ticker Yahoo has, sessions missing a value dropped; null when none. */
private fun chart(listing: Listing): Chart? {
    for (ticker in NseScanner.yahooTickers(listing)) {
        val url = "https://query1.finance.yahoo.com/v8/finance/chart/$ticker?range=2y&interval=1d"
        var body = ""
        for (attempt in 1..3) {
            val (status, text) = get(url)
            if (status == 200) {
                body = text
                break
            }
            if (status == 404) break
            Thread.sleep(if (status == 429) 20_000L * attempt else 2_000L)
        }
        if (body.isEmpty()) continue
        val parsed = runCatching { parse(body) }.getOrNull()
        if (parsed != null && parsed.close.size > WINDOW) return parsed
    }
    return null
}

private fun parse(body: String): Chart? {
    val result = JSONObject(body).getJSONObject("chart").optJSONArray("result")?.optJSONObject(0) ?: return null
    val stamps = result.optJSONArray("timestamp") ?: return null
    val quote = result.optJSONObject("indicators")?.optJSONArray("quote")?.optJSONObject(0) ?: return null
    val c = quote.optJSONArray("close") ?: return null
    val h = quote.optJSONArray("high") ?: return null
    val l = quote.optJSONArray("low") ?: return null
    val v = quote.optJSONArray("volume") ?: return null
    val days = ArrayList<Long>()
    val close = ArrayList<Double>()
    val high = ArrayList<Double>()
    val low = ArrayList<Double>()
    val volume = ArrayList<Double>()
    for (i in 0 until minOf(stamps.length(), c.length(), h.length(), l.length(), v.length())) {
        if (c.isNull(i) || h.isNull(i) || l.isNull(i) || v.isNull(i)) continue
        val cl = c.optDouble(i)
        val hi = h.optDouble(i)
        val lo = l.optDouble(i)
        val vo = v.optDouble(i)
        if (!cl.isFinite() || !hi.isFinite() || !lo.isFinite() || !vo.isFinite() || cl <= 0 || hi <= 0 || lo <= 0 || vo < 0) continue
        days += MarketClock.dayOf(stamps.optLong(i)).toEpochDay()
        close += cl
        // As BreakoutSignals.analyse keeps them: a candle's extremes always hold its close.
        high += maxOf(hi, lo, cl)
        low += minOf(hi, lo, cl)
        volume += vo
    }
    return Chart(days.toLongArray(), close.toDoubleArray(), high.toDoubleArray(), low.toDoubleArray(), volume.toDoubleArray())
}

/** A past plan: the chart and close it was drawn at, its conditions, and the app's own entry, stop and ATR. */
private class Trade(
    val chart: Chart,
    val at: Int,
    val mask: Long,
    val day: Long,
    val entry: Double,
    val breakout: Boolean,
    val stop: Double,
    val atr: Double,
) {
    fun judge(into: IntArray) = PlanTuner.judge(entry, breakout, stop, atr, chart.high, chart.low, chart.close, at, into)

    fun result(cell: Int) = PlanTuner.result(cell, entry, breakout, stop, atr, chart.high, chart.low, chart.close, at)
}

private class Tally {
    val trades: MutableList<Trade> = Collections.synchronizedList(ArrayList())
    val rallies: MutableList<SetupMiner.Sample> = Collections.synchronizedList(ArrayList())
    val kinds = HashMap<BreakoutSetup.Kind, IntArray>() // n, quick wins, wide n, wide wins

    @Synchronized
    fun kind(kind: BreakoutSetup.Kind, quick: PlanOutcome.Outcome, wide: PlanOutcome.Outcome) {
        val a = kinds.getOrPut(kind) { IntArray(4) }
        if (quick.decided) {
            a[0]++
            if (quick.result == PlanOutcome.Result.WIN) a[1]++
        }
        if (wide.decided) {
            a[2]++
            if (wide.result == PlanOutcome.Result.WIN) a[3]++
        }
    }
}

/** Every past close of [listing]'s chart that the app would have drawn a plan at, judged by what came after. */
private fun judge(listing: Listing, chart: Chart, tally: Tally): Int {
    val n = chart.close.size
    val close = chart.close.toList()
    val high = chart.high.toList()
    val low = chart.low.toList()
    val volume = chart.volume.toList()
    var previous: Double? = null
    var plans = 0
    for (t in WINDOW - 1 until n - 1) {
        val from = t - (WINDOW - 1)
        val s = BreakoutSignals.analyse(close.subList(from, t + 1), high.subList(from, t + 1), low.subList(from, t + 1), volume.subList(from, t + 1))
        val before = previous
        previous = s?.score
        if (s == null || s.medianTurnoverCr < SetupBook.MIN_TURNOVER_CR) continue
        val plan = TradePlan.of(s.price, s.structure)
        val mask = SetupCondition.mask(s, plan, before)
        // Rallies are judged at every close, plan or no plan: what came before a stock went up.
        val atr = s.structure.atr14.takeIf { it.isFinite() && it > 0.0 } ?: (s.price * TradePlan.FALLBACK_ATR_FRACTION)
        val rally = PlanOutcome.rally(s.price, atr, chart.high, chart.low, t)
        if (rally.decided) {
            tally.rallies += SetupMiner.Sample(
                mask, chart.days[t], win = rally.result == PlanOutcome.Result.WIN, stopped = rally.result == PlanOutcome.Result.STOPPED,
            )
        }
        if (plan == null || plan.isExtended) continue
        tally.trades += Trade(chart, t, mask, chart.days[t], plan.entry, plan.entryKind == TradePlan.EntryKind.BREAKOUT, plan.stop, plan.atr)
        plans++
        val stock = ScanResult(
            listing.symbol, s.price, s.score, s.rvol, s.change5d,
            pastScores = before?.let { listOf(DayScore(chart.days[t] - 1, it)) }.orEmpty(),
        )
        tally.kind(BreakoutSetup.of(stock, plan), PlanOutcome.quick(plan, chart.high, chart.low, t), PlanOutcome.quick(plan, chart.high, chart.low, t, wide = true))
    }
    return plans
}

fun main(args: Array<String>) {
    val file = File(args.getOrElse(0) { "setups.json" })
    val minutes = args.getOrNull(1)?.toDoubleOrNull() ?: 90.0
    val deadline = System.currentTimeMillis() + (minutes * 60_000).toLong()
    val today = LocalDate.now(INDIA)
    val previous = SetupBook.parse(if (file.isFile) file.readText() else null)
    log("previous book: ${previous.rules.size} setups, ${previous.avoid.size} mistakes, ${previous.changes.size} changes")

    val stocks = listings(today)
    val tally = Tally()
    val done = AtomicInteger()
    val judged = AtomicInteger()
    val pool = Executors.newFixedThreadPool(THREADS)
    for (listing in stocks) {
        pool.execute {
            if (System.currentTimeMillis() > deadline) return@execute
            val chart = runCatching { chart(listing) }.getOrNull()
            if (chart != null && runCatching { judge(listing, chart, tally) }.getOrDefault(0) > 0) judged.incrementAndGet()
            val count = done.incrementAndGet()
            if (count % 250 == 0) log("charts $count/${stocks.size}, stocks with plans ${judged.get()}, plans ${tally.trades.size}")
        }
    }
    pool.shutdown()
    pool.awaitTermination(minutes.toLong() + 5, TimeUnit.MINUTES)
    val trades = tally.trades.toList()
    log("stocks with plans ${judged.get()} of ${stocks.size}, plans ${trades.size}, closes judged for rallies ${tally.rallies.size}")
    check(judged.get() >= 500) { "too few stocks charted (${judged.get()}); the book stays as it was" }

    val day = today.toEpochDay()
    val holdoutFrom = SetupMiner.holdoutFrom(trades.map { it.day })

    // Every plan under every shape, then the shape for every plan, then the setups that won under it.
    val started = System.currentTimeMillis()
    val totals = totals(trades, holdoutFrom, 1) { intArrayOf(0) }.single()
    log("judged ${trades.size} plans under ${PlanTuner.CELLS} shapes in ${(System.currentTimeMillis() - started) / 1000}s")
    val ownCell = PlanTuner.cell(PlanShape.OWN)
    val ownPlan = PlanTuner.Choice(PlanShape.OWN, totals.record(ownCell, false), totals.record(ownCell, true), day)
    val marketPlan = PlanTuner.choose(totals, previous.marketPlan, PlanShape.OWN, day)
    val marketCell = PlanTuner.cell(marketPlan.shape)
    val samples = trades.parallelStream().map { trade ->
        trade.result(marketCell)?.let { r -> SetupMiner.Sample(trade.mask, trade.day, PlanTuner.won(marketCell, r), PlanTuner.stopped(r)) }
    }.toList().filterNotNull()
    val result = SetupMiner.learn(
        samples,
        previous.rules.associate { it.mask to it.since },
        day,
        previous.avoid.associate { it.mask to it.since },
        holdoutFrom,
    )
    // A setup gets a shape of its own when one did better for it, still beating the market's success rate under it.
    val rules = result.rules
    val bySetup = totals(trades, holdoutFrom, rules.size) { trade ->
        rules.indices.filter { rules[it].mask and trade.mask == rules[it].mask }.toIntArray()
    }
    val setupPlans = rules.indices.mapNotNull { i ->
        val choice = PlanTuner.choose(bySetup[i], previous.setupPlans[rules[i].mask], marketPlan.shape, day) { cell, record ->
            record.stats.rate >= totals.record(cell, false).stats.rate + SetupMiner.ADD_MARGIN
        }
        if (choice.shape == marketPlan.shape) null else rules[i].mask to choice
    }.toMap()
    val rally = SetupMiner.learn(tally.rallies.toList(), previous.rally.associate { it.mask to it.since }, day)

    val kinds = tally.kinds.mapValues { (_, a) -> SetupBook.KindStats(SetupMiner.Stats(a[0], a[1]), SetupMiner.Stats(a[2], a[3])) }
    file.writeText(
        SetupBook.encode(result, kinds, previous, day, judged.get(), samples.size, marketPlan, ownPlan, setupPlans, rally),
    )

    log("plan for every stock: ${shape(marketPlan.shape)} since ${LocalDate.ofEpochDay(marketPlan.since)}: ${plan(marketPlan)}")
    log("the app's own plan: ${plan(ownPlan)}")
    log("market: ${percent(result.train)} before the holdout, ${percent(result.holdout)} in it (from ${LocalDate.ofEpochDay(result.holdoutFrom)})")
    log("market failures: stopped ${stopped(result.train)}, too late ${late(result.train)}; in the holdout ${stopped(result.holdout)} and ${late(result.holdout)}")
    for (r in result.rules) {
        val own = setupPlans[r.mask]?.let { "; own plan ${shape(it.shape)}: ${plan(it)}" }.orEmpty()
        log("setup ${r.conditions.joinToString(" + ")}: ${percent(r.train)} of ${r.train.n}, holdout ${percent(r.holdout)} of ${r.holdout.n}$own")
    }
    for (r in result.avoid) {
        log("mistake ${r.conditions.joinToString(" + ")}: ${percent(r.all)} of ${r.all.n} won, stopped ${stopped(r.all)}, too late ${late(r.all)}; holdout ${percent(r.holdout)} of ${r.holdout.n}")
    }
    for (c in result.changes) {
        val what = if (c.added) "added" else if (c.replaced) "replaced" else "removed"
        log("$what ${if (c.avoid) "mistake" else "setup"} ${SetupCondition.of(c.mask).joinToString(" + ")}")
    }
    log("rallies (${PlanOutcome.RALLY_ATR} ATR in ${PlanOutcome.RALLY_SESSIONS} sessions): ${percent(rally.train)} before the holdout, ${percent(rally.holdout)} in it")
    for (r in rally.rules) log("rally setup ${r.conditions.joinToString(" + ")}: ${percent(r.all)} of ${r.all.n}, holdout ${percent(r.holdout)}")
    log("why up: " + rally.lift.take(8).joinToString { "${it.condition} ×${"%.2f".format(it.lift)}" })
    for (k in kinds) log("kind ${k.key}: quick ${percent(k.value.quick)} of ${k.value.quick.n}, wide ${percent(k.value.quickWide)}")
    log("why: " + result.lift.take(8).joinToString { "${it.condition} ×${"%.2f".format(it.lift)}" })
    log("why stopped: " + result.loseLift.take(8).joinToString { "${it.condition} ×${"%.2f".format(it.lift)}" })
}

private fun percent(s: SetupMiner.Stats) = "${SetupBook.percent(s)}%"

private fun stopped(s: SetupMiner.Stats) = "${Math.round(s.stopRate * 100)}%"

private fun late(s: SetupMiner.Stats) = "${Math.round((1 - s.rate - s.stopRate) * 100)}%"

private fun shape(s: PlanShape) =
    (if (s.stopAtr > 0.0) "stop ${s.stopAtr} ATR" else "the app's own stop") + ", Target 1 at ${s.targetR}R in ${s.days}x its usual sessions"

/**
 * Every shape's record over [trades], in [groups] totals: each plan is judged once and added to the totals [groupsOf]
 * names, before or in the holdout (from [holdoutFrom]). Spread over [THREADS] threads.
 */
private fun totals(trades: List<Trade>, holdoutFrom: Long, groups: Int, groupsOf: (Trade) -> IntArray): List<PlanTuner.Totals> {
    val workers = (0 until THREADS).map { w ->
        val own = List(groups) { PlanTuner.Totals() }
        val thread = Thread {
            val results = IntArray(PlanTuner.CELLS)
            for (i in w until trades.size step THREADS) {
                val trade = trades[i]
                val into = groupsOf(trade)
                if (into.isEmpty() || !trade.judge(results)) continue
                for (g in into) own[g].add(results, trade.day >= holdoutFrom)
            }
        }
        thread.start()
        thread to own
    }
    val all = List(groups) { PlanTuner.Totals() }
    for ((thread, own) in workers) {
        thread.join()
        for (g in 0 until groups) all[g].addAll(own[g])
    }
    return all
}

private fun plan(c: PlanTuner.Choice) =
    "${percent(c.all.stats)} won, ${stopped(c.all.stats)} stopped, ${"%+.2f".format(c.all.averageR)}R a plan " +
        "(holdout ${percent(c.holdout.stats)}, ${"%+.2f".format(c.holdout.averageR)}R)"
