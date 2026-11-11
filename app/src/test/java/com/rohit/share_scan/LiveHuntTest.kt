package com.rohit.share_scan

import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.lang.reflect.Modifier
import java.net.URL
import java.util.IdentityHashMap

/**
 * Temporary crash hunt (removed before merging): two real scans while NSE trades, then everything the app does with a
 * live scan off the screen — the alerts, the track record, the kept scan, the plans and the list's sorts — with every
 * number checked for NaN or infinity. Runs only with LIVE_HUNT=1.
 */
class LiveHuntTest {
    private val out = StringBuilder()
    private fun say(line: String) { println(line); out.append(line).append('\n') }

    @Test
    fun liveScanAndAfter() {
        assumeTrue(System.getenv("LIVE_HUNT") == "1")
        val sectors = runCatching { SectorBook.parse(URL(SECTORS).readText()) }.getOrDefault(SectorBook.EMPTY)
        val setups = runCatching { SetupBook.parse(URL(SETUPS).readText()) }.getOrDefault(SetupBook.EMPTY)
        say("sectors ${sectors != SectorBook.EMPTY}, setups ${setups != SetupBook.EMPTY}")

        val first = timed("scan 1") { NseScanner.scan(sectors = sectors) { _, _ -> } }
        say("scan 1: ${first.results.size} scored, ${first.unscanned.size} unscanned, live ${first.live}")
        Thread.sleep(60_000)
        val second = timed("scan 2") { NseScanner.scan(sectors = sectors) { _, _ -> } }
        say("scan 2: ${second.results.size} scored, ${second.unscanned.size} unscanned, live ${second.live}")

        for ((label, output) in listOf("scan 1" to first, "scan 2" to second)) {
            val bad = ArrayList<String>()
            output.results.forEach { row -> walk(row, row.symbol, bad, IdentityHashMap()) }
            output.unscanned.forEach { row -> walk(row, row.symbol + "(u)", bad, IdentityHashMap()) }
            say("$label: ${bad.size} non-finite numbers")
            bad.take(60).forEach { say("  $it") }
        }

        val previous = first.results.sortedByDescending { it.score }
        val ranked = second.results.sortedByDescending { it.score }
        attempt("live alerts") {
            val finds = LiveAlerts.fresh(previous, ranked, ScanFilters())
            say("  ${finds.size} finds: ${finds.take(8).joinToString { "${it.symbol} b=${it.breakout} s=${it.newStrong}" }}")
            finds.forEach { StockFormat.score(it.score); StockFormat.price(it.price); StockFormat.rvol(it.rvol) }
        }
        attempt("watch alerts on every stock") {
            val now = System.currentTimeMillis()
            val states = HashMap<String, WatchAlerts.State>()
            var events = 0
            for (pass in listOf(previous, ranked)) {
                for (stock in pass) {
                    val (state, event) = WatchAlerts.step(states[stock.symbol], stock, now, setups.shape(stock.setupMask))
                    states[stock.symbol] = state
                    if (event != null) events++
                    when (event) {
                        is WatchAlerts.Event.Target -> TradePlan.formatPercent((event.price / event.entry - 1.0) * 100.0)
                        is WatchAlerts.Event.Stopped -> TradePlan.formatPercent((event.price / event.entry - 1.0) * 100.0)
                        else -> Unit
                    }
                }
            }
            val text = WatchAlerts.encode(states)
            say("  $events events, ${WatchAlerts.decode(text).size} states kept")
        }
        attempt("track record") {
            val journal = PickJournal.decode(null).update(first).update(second)
            PickJournal.decode(journal.encode())
        }
        attempt("kept scan round trip") {
            val file = File.createTempFile("scan", ".gz")
            val store = ScanStore(file)
            store.save(SavedScan(second.copy(results = ranked), System.currentTimeMillis(), true, emptyMap(), "", emptyList()))
            val back = store.load()
            say("  saved and read ${back?.output?.results?.size} rows")
        }
        attempt("plans and figures") {
            for (stock in ranked) {
                stock.structure?.let { s ->
                    TradePlan.of(stock.price, s)
                    TradePlan.of(stock.price, s, setups.shape(stock.setupMask))
                }
                StockFormat.score(stock.score); StockFormat.price(stock.price); StockFormat.rvol(stock.rvol)
                StockFormat.change(stock.change5d); stock.change1d?.let(StockFormat::signedChange)
                stock.marketCapCr?.let(StockFormat::crore); StockFormat.shares(stock.volume)
                StockFormat.tier(stock.score)
            }
        }
        attempt("list sorts") {
            for (mode in ScanMode.values()) for (sort in SortMode.values()) for (up in listOf(false, true)) {
                val state = ScanUiState(
                    phase = ScanPhase.DONE,
                    results = ranked,
                    unscanned = second.unscanned,
                    sort = sort,
                    ascending = up,
                    scanMode = mode,
                    watchlist = ranked.take(5).map { it.symbol }.toSet(),
                )
                state.visible.size; state.listed.size; state.shown.size; state.starredStocks.size; state.volumeSpikes
            }
        }
        File("live-hunt").mkdirs()
        File("live-hunt/report.txt").writeText(out.toString())
        check(!out.contains("FAILED")) { "a step failed; see the report" }
    }

    private fun <T> timed(label: String, block: () -> T): T {
        val start = System.currentTimeMillis()
        return block().also { say("$label took ${(System.currentTimeMillis() - start) / 1000}s") }
    }

    private fun attempt(label: String, block: () -> Unit) {
        try {
            block()
            say("ok: $label")
        } catch (error: Throwable) {
            say("FAILED: $label\n" + error.stackTraceToString().lines().take(25).joinToString("\n"))
        }
    }

    /** Every Double/Float field, list element or array value reachable from [value] that is NaN or infinite. */
    private fun walk(value: Any?, path: String, bad: MutableList<String>, seen: IdentityHashMap<Any, Unit>) {
        when (value) {
            null, is String, is Boolean, is Int, is Long, is Enum<*>, is java.time.temporal.Temporal -> return
            is Double -> if (!value.isFinite()) bad += "$path = $value"
            is Float -> if (!value.isFinite()) bad += "$path = $value"
            is DoubleArray -> value.forEachIndexed { i, v -> if (!v.isFinite()) bad += "$path[$i] = $v" }
            is FloatArray -> value.forEachIndexed { i, v -> if (!v.isFinite()) bad += "$path[$i] = $v" }
            is LongArray, is IntArray -> return
            is Collection<*> -> value.forEachIndexed { i, v -> walk(v, "$path[$i]", bad, seen) }
            is Map<*, *> -> value.forEach { (k, v) -> walk(v, "$path{$k}", bad, seen) }
            else -> {
                if (seen.put(value, Unit) != null) return
                if (!value.javaClass.name.startsWith("com.rohit")) return
                var type: Class<*>? = value.javaClass
                while (type != null && type != Any::class.java) {
                    for (field in type.declaredFields) {
                        if (Modifier.isStatic(field.modifiers)) continue
                        field.isAccessible = true
                        walk(field.get(value), "$path.${field.name}", bad, seen)
                    }
                    type = type.superclass
                }
            }
        }
    }

    private companion object {
        const val SECTORS = "https://raw.githubusercontent.com/rohitrot738/Share_scan/main/data/sectors.json"
        const val SETUPS = "https://github.com/rohitrot738/Share_scan/releases/download/cr360-data/setups.json"
    }
}
