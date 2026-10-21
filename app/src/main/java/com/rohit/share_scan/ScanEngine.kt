package com.rohit.share_scan

import android.content.Context
import android.os.SystemClock
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * Runs the app's scans one at a time, whoever starts them (the user's button, or live mode in the background), and
 * owns what a finished scan leaves behind: the scan kept on disk ([ScanStore]), the rank badges, the track record
 * ([PickJournal]) and the alerts ([WatchAlerts], [LiveAlerts]). It lives as long as the app's process, so a scan
 * started on the screen finishes even when the screen goes, and one started in the background shows on the screen
 * the moment it is opened.
 */
object ScanEngine {
    enum class Trigger { USER, AUTO }

    /** The scan under way, if any. */
    data class Progress(
        val running: Boolean = false,
        val trigger: Trigger = Trigger.USER,
        /** The stock lists are still loading; nothing is scored yet. */
        val loading: Boolean = false,
        val done: Int = 0,
        val total: Int = 0,
        /** [SystemClock.elapsedRealtime] at the start. */
        val startedAt: Long = 0L,
        /** [SystemClock.elapsedRealtime] at the end; 0 while it runs. */
        val endedAt: Long = 0L,
        val stopping: Boolean = false,
        /** Counts up with every scan, so late progress from a stopped scan's threads can be told apart. */
        val runId: Int = 0,
    )

    /** How the latest run ended, when it ended without a scan to show. */
    data class Failure(val runId: Int, val message: String?, val stoppedEarly: Boolean = false)

    private val progressFlow = MutableStateFlow(Progress())
    private val latestFlow = MutableStateFlow<SavedScan?>(null)
    private val failureFlow = MutableStateFlow<Failure?>(null)
    val progress: StateFlow<Progress> = progressFlow.asStateFlow()

    /** The latest finished scan; it is read from disk on first use. */
    val latest: StateFlow<SavedScan?> = latestFlow.asStateFlow()
    val failure: StateFlow<Failure?> = failureFlow.asStateFlow()

    private val worker = Executors.newSingleThreadExecutor()
    private val loaded = AtomicBoolean(false)
    private val loadDone = CountDownLatch(1)
    @Volatile private var stopSignal = AtomicBoolean(false)
    private val lock = Any()

    /** Reads the kept scan in the background, once; the screen shows it as soon as it is in. */
    fun load(context: Context) {
        if (!loaded.compareAndSet(false, true)) return
        val app = context.applicationContext
        worker.execute {
            try {
                if (latestFlow.value == null) latestFlow.value = store(app).load()
            } finally {
                loadDone.countDown()
            }
        }
    }

    /** The kept scan, waiting (briefly) for it to be read from disk; for background work, never the main thread. */
    fun loaded(context: Context): SavedScan? {
        load(context)
        loadDone.await(LOAD_WAIT_S, TimeUnit.SECONDS)
        return latestFlow.value
    }

    /** Starts a scan unless one is under way; false when one already is. */
    fun start(context: Context, trigger: Trigger): Boolean {
        val app = context.applicationContext
        load(app)
        val runId = claim(trigger) ?: return false
        worker.execute { run(app, trigger, runId, budgetMs = 0L) }
        return true
    }

    /**
     * Live mode's scan: runs one now and waits for it, giving up on the stocks not reached within [budgetMs]. When a
     * scan is already under way it waits for that one instead. Returns the scan kept afterwards.
     */
    fun runAndWait(context: Context, budgetMs: Long): SavedScan? {
        val app = context.applicationContext
        load(app)
        val runId = claim(Trigger.AUTO)
        val finished = CountDownLatch(1)
        if (runId != null) {
            worker.execute {
                try { run(app, Trigger.AUTO, runId, budgetMs) } finally { finished.countDown() }
            }
        } else {
            worker.execute { finished.countDown() }
        }
        finished.await(budgetMs + WAIT_SLACK_MS, TimeUnit.MILLISECONDS)
        return latestFlow.value
    }

    /** Asks the running scan to stop; it keeps the stocks scored so far. */
    fun stop() {
        val current = progressFlow.value
        if (!current.running) return
        stopSignal.set(true)
        progressFlow.update { if (it.runId == current.runId && it.running) it.copy(stopping = true) else it }
    }

    /**
     * Fresh prices for the watchlist between live mode's full scans: those stocks are charted again, put back in the
     * kept scan and checked for alerts. Does nothing while a scan runs or before the first scan.
     */
    fun refreshWatchlist(context: Context) {
        val app = context.applicationContext
        loaded(app)
        val watchlist = prefs(app).getStringSet(Prefs.WATCHLIST, null).orEmpty()
        if (watchlist.isEmpty()) return
        val done = CountDownLatch(1)
        worker.execute {
            try {
                if (progressFlow.value.running) return@execute
                val saved = latestFlow.value ?: return@execute
                val rows = saved.output.results.filter { it.symbol in watchlist }
                val extras = saved.watchExtras.filter { it.symbol in watchlist }
                if (rows.isEmpty() && extras.isEmpty()) return@execute
                val fresh = NseScanner.refresh(rows + extras).associateBy { it.symbol }
                val results = saved.output.results.map { fresh[it.symbol] ?: it }.sortedByDescending { it.score }
                val freshExtras = extras.map { fresh[it.symbol] ?: it }
                // The scan's time stays: it dates the full scan, which live mode counts its interval from.
                val updated = saved.copy(output = saved.output.copy(results = results), watchExtras = freshExtras)
                store(app).save(updated)
                latestFlow.value = updated
                watchAlerts(app, results + freshExtras)
            } catch (error: Exception) {
                // A failed refresh leaves the kept scan as it was; the next full scan brings fresh prices anyway.
            } finally {
                done.countDown()
            }
        }
        done.await(REFRESH_WAIT_MS, TimeUnit.MILLISECONDS)
    }

    /** Forgets the kept scan's rank badges, as Settings' "rank history" reset asks. */
    fun clearBadges(context: Context) {
        val app = context.applicationContext
        worker.execute {
            val saved = latestFlow.value ?: return@execute
            val updated = saved.copy(badges = emptyMap())
            runCatching { store(app).save(updated) }
            latestFlow.value = updated
        }
    }

    /** The track record as kept on disk. */
    fun journal(context: Context): PickJournal =
        PickJournal.decode(runCatching { journalFile(context.applicationContext).readText() }.getOrNull())

    /** The alert state of each watchlisted stock. */
    fun watchStates(context: Context): Map<String, WatchAlerts.State> =
        WatchAlerts.decode(prefs(context).getString(Prefs.WATCH_STATES, null))

    private fun claim(trigger: Trigger): Int? = synchronized(lock) {
        val current = progressFlow.value
        if (current.running) return null
        stopSignal = AtomicBoolean(false)
        val runId = current.runId + 1
        progressFlow.value = Progress(
            running = true,
            trigger = trigger,
            loading = true,
            startedAt = SystemClock.elapsedRealtime(),
            runId = runId,
        )
        failureFlow.value = null
        runId
    }

    private fun run(app: Context, trigger: Trigger, runId: Int, budgetMs: Long) {
        val stop = stopSignal
        val started = SystemClock.elapsedRealtime()
        val settings = AppSettings(app)
        val markets = buildSet {
            if (settings.includeNse) add(Market.NSE)
            if (settings.includeSme) add(Market.NSE_SME)
            if (settings.includeBse) add(Market.BSE)
        }
        val scope = settings.scanSectors.key(markets)
        val outcome = runCatching {
            runCatching { SectorRepository.refreshIfStale(app) }
            // The R/S file is fetched alongside: a scan never waits for it, and it is in long before the results show.
            thread(name = "Cr360Refresh", isDaemon = true) { runCatching { Cr360Repository.refreshIfStale(app) } }
            NseScanner.scan(
                includeNse = settings.includeNse,
                includeSme = settings.includeSme,
                includeBse = settings.includeBse,
                sectors = SectorRepository.book(app),
                scope = settings.scanSectors,
                isStopped = { stop.get() || (budgetMs > 0 && SystemClock.elapsedRealtime() - started > budgetMs) },
            ) { finished, total ->
                // Progress arrives from several pool threads, so keep the highest count seen; threads a stop left
                // behind can still report after their scan settled, so only this run's own progress counts.
                progressFlow.update {
                    if (it.runId != runId || !it.running) it
                    else it.copy(loading = false, done = maxOf(it.done, finished), total = total)
                }
            }
        }
        val output = outcome.getOrNull()
        try {
            if (output == null) {
                failureFlow.value = Failure(runId, outcome.exceptionOrNull()?.message)
            } else if (output.results.isEmpty()) {
                // Stopped while the lists loaded: nothing scored, the kept scan stays.
                failureFlow.value = Failure(runId, null, stoppedEarly = output.stopped)
            } else {
                finish(app, trigger, output, scope, cutByBudget = budgetMs > 0 && !stop.get() && output.stopped)
            }
        } finally {
            progressFlow.update {
                if (it.runId == runId) {
                    it.copy(running = false, stopping = false, loading = false, endedAt = SystemClock.elapsedRealtime())
                } else {
                    it
                }
            }
        }
    }

    /** Keeps a finished scan of the stocks [scope] names and acts on it. */
    private fun finish(app: Context, trigger: Trigger, scanned: ScanOutput, scope: String, cutByBudget: Boolean) {
        val kept = latestFlow.value
        // Only a scan of the same exchanges and industries is a "before" for this one; a kept scan from before scans
        // had a scope counts as one.
        val previous = kept?.takeIf { it.scope.isEmpty() || it.scope == scope }
        // Live mode out of time keeps the stocks it did not reach from the scan before, rather than losing them.
        val output = if (cutByBudget && previous != null) merge(previous.output, scanned) else scanned
        val ranked = output.results.sortedByDescending { it.score }
        val complete = !output.stopped
        val badges = if (complete) trackRanks(app, ranked, scope) else emptyMap()
        // A scan the user stopped is kept at once; its starred stocks outside it keep their last rows.
        val extras = watchExtras(app, ranked, kept, chart = complete)
        val saved = SavedScan(
            output.copy(results = ranked),
            System.currentTimeMillis(),
            trigger == Trigger.AUTO,
            badges,
            scope,
            extras,
        )
        runCatching { store(app).save(saved) }
        latestFlow.value = saved
        if (complete) {
            runCatching {
                val file = journalFile(app)
                val journal = PickJournal.decode(file.takeIf { it.isFile }?.readText()).update(saved.output)
                file.writeText(journal.encode())
            }
        }
        watchAlerts(app, ranked + extras)
        val settings = AppSettings(app)
        if (trigger == Trigger.AUTO && settings.liveAlerts && previous != null) {
            val filters = ScanFilters.decode(prefs(app).getString(Prefs.FILTERS, null))
            val finds = LiveAlerts.fresh(previous.output.results, ranked, filters)
            if (finds.isNotEmpty()) Notifier.liveFinds(app, finds)
        }
    }

    /**
     * Starred stocks [results] lack, as the [kept] scan last had them (a stock starred on another phone has no row
     * yet and is looked up on NSE), charted again when [chart] so the watchlist shows them with fresh prices. A
     * chart that does not load leaves the old row; a stock never charted is left out.
     */
    private fun watchExtras(app: Context, results: List<ScanResult>, kept: SavedScan?, chart: Boolean): List<ScanResult> {
        val watchlist = prefs(app).getStringSet(Prefs.WATCHLIST, null).orEmpty()
        val covered = results.mapTo(HashSet()) { it.symbol }
        val missing = watchlist.filter { it !in covered }
        if (missing.isEmpty()) return emptyList()
        val known = kept?.let { it.watchExtras + it.output.results }.orEmpty().associateBy { it.symbol }
        val old = missing.map { known[it] ?: ScanResult(symbol = it, price = 0.0, score = 0.0, rvol = 0.0, change5d = 0.0) }
        if (!chart) return old.filter { it.price > 0.0 }
        // Yahoo's limit can hold a chart for minutes; the scan is kept without waiting that long.
        val deadline = SystemClock.elapsedRealtime() + EXTRAS_WAIT_MS
        val fresh = runCatching { NseScanner.refresh(old, isStopped = { SystemClock.elapsedRealtime() > deadline }) }
            .getOrDefault(old)
        return fresh.filter { it.price > 0.0 }
    }

    /** [fresh]'s rows, then the rows of [old] it did not reach, best score first; stopped only when [old] was too. */
    private fun merge(old: ScanOutput, fresh: ScanOutput): ScanOutput {
        val reached = fresh.results.mapTo(HashSet()) { it.symbol }
        val results = fresh.results + old.results.filter { it.symbol !in reached }
        return fresh.copy(
            results = results,
            stopped = old.stopped,
            marketCapDate = fresh.marketCapDate ?: old.marketCapDate,
            benchmark = fresh.benchmark ?: old.benchmark,
        )
    }

    /** Steps each watchlisted stock's alerts on [results] and posts what they raise. */
    private fun watchAlerts(app: Context, results: List<ScanResult>) {
        val prefs = prefs(app)
        val watchlist = prefs.getStringSet(Prefs.WATCHLIST, null).orEmpty()
        val states = WatchAlerts.decode(prefs.getString(Prefs.WATCH_STATES, null))
        val now = System.currentTimeMillis()
        val next = HashMap<String, WatchAlerts.State>()
        val events = ArrayList<Pair<WatchAlerts.Event, String>>()
        for (stock in results) {
            if (stock.symbol !in watchlist) continue
            val (state, event) = WatchAlerts.step(states[stock.symbol], stock, now)
            next[stock.symbol] = state
            if (event != null) events += event to stock.name
        }
        // A starred stock missing from this scan keeps its state; an unstarred one is forgotten.
        for ((symbol, state) in states) if (symbol in watchlist && symbol !in next) next[symbol] = state
        prefs.edit { putString(Prefs.WATCH_STATES, WatchAlerts.encode(next)) }
        if (AppSettings(app).watchAlerts) events.forEach { (event, name) -> Notifier.watchEvent(app, event, name) }
    }

    /** Badges against the previous complete scan of the same stocks ([scope]), then remembers this scan's ranks. */
    private fun trackRanks(app: Context, results: List<ScanResult>, scope: String): Map<String, RankBadge> {
        if (results.isEmpty()) return emptyMap()
        val prefs = prefs(app)
        val symbols = results.map { it.symbol }
        val ranksScope = prefs.getString(Prefs.LAST_RANKS_SCOPE, null)
        val previous = if (ranksScope == null || ranksScope == scope) {
            RankHistory.decode(prefs.getString(Prefs.LAST_RANKS, null))
        } else {
            emptyMap()
        }
        prefs.edit {
            putString(Prefs.LAST_RANKS, RankHistory.encode(symbols))
            putString(Prefs.LAST_RANKS_SCOPE, scope)
        }
        return RankHistory.badges(symbols, previous)
    }

    private fun prefs(context: Context) = context.getSharedPreferences(Prefs.FILE, Context.MODE_PRIVATE)

    private fun store(context: Context) = ScanStore(File(context.filesDir, SCAN_FILE))

    private fun journalFile(context: Context) = File(context.filesDir, JOURNAL_FILE)

    private const val SCAN_FILE = "last_scan.gz"
    private const val JOURNAL_FILE = "track_record.json"
    private const val WAIT_SLACK_MS = 30_000L
    private const val LOAD_WAIT_S = 20L
    private const val REFRESH_WAIT_MS = 120_000L
    /** How long a finished scan waits on the charts of starred stocks it did not cover. */
    private const val EXTRAS_WAIT_MS = 30_000L
}
