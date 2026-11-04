package com.rohit.share_scan

import android.content.Context
import androidx.core.content.edit
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

/**
 * The paper account ([PaperEngine]) kept in the app's preferences, and its prices: each refresh reads the 5-minute
 * candles of every stock with a waiting order or an open position from Yahoo and runs them through the account.
 * Changes go through one lock, so the screen and the background check ([PaperWorker]) never undo each other.
 */
object PaperStore {
    private const val BOOK = "paper_book"
    private val lock = Any()

    private fun prefs(context: Context) = context.getSharedPreferences(Prefs.FILE, Context.MODE_PRIVATE)

    /** The account; a new one with [PaperEngine.DEFAULT_CAPITAL] the first time. */
    fun load(context: Context): PaperBook = synchronized(lock) {
        PaperEngine.decode(prefs(context).getString(BOOK, null))
            ?: PaperEngine.newBook(PaperEngine.DEFAULT_CAPITAL, System.currentTimeMillis())
    }

    private fun save(context: Context, book: PaperBook) = prefs(context).edit { putString(BOOK, PaperEngine.encode(book)) }

    /** Applies [change] to the saved account and keeps what it returns. */
    fun <T> update(context: Context, change: (PaperBook) -> Pair<PaperBook, T>): T = synchronized(lock) {
        val (book, result) = change(load(context))
        save(context, book)
        result
    }

    /** Starts the account over with [capital]. */
    fun reset(context: Context, capital: Double) = synchronized(lock) {
        save(context, PaperEngine.newBook(capital, System.currentTimeMillis()))
    }

    /**
     * Reads fresh candles for every stock the account watches, and for [extra] (symbol to Yahoo ticker, for a price
     * to order at), and runs them through the account. Blocks on the network: call it off the main thread. Returns
     * what happened, and whether every stock's candles came.
     */
    fun refresh(context: Context, extra: Map<String, String> = emptyMap(), now: Long = System.currentTimeMillis()): Pair<List<PaperEvent>, Boolean> {
        val book = load(context)
        val wanted = PaperEngine.watched(book) + extra
        var complete = true
        val fetched = wanted.mapNotNull { (symbol, ticker) ->
            val since = PaperEngine.needSince(book, symbol) ?: (now / 1000)
            IntradayRepository.recent(ticker, since, now).also { if (it == null) complete = false }?.let { symbol to it }
        }
        val events = update(context) { current ->
            var b = current
            val events = mutableListOf<PaperEvent>()
            for ((symbol, candles) in fetched) {
                val (next, happened) = PaperEngine.process(b, symbol, candles, now)
                b = next
                events += happened
            }
            PaperEngine.mark(b, now) to events
        }
        if (PaperEngine.watched(load(context)).isNotEmpty()) PaperWorker.schedule(context)
        return events to complete
    }

    /** Yahoo's name for a stock of the scan. */
    fun ticker(stock: ScanResult): String = IntradayRepository.ticker(stock)
}

/**
 * The paper account's check while the app is closed: every 15 minutes on a market day from just before the open to
 * just after the close, waiting orders fill, stops and targets close positions and intraday ones are squared off at
 * 15:15, each told in a notification. It stops itself once nothing is waiting or open.
 */
class PaperWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
    override fun doWork(): Result {
        val book = PaperStore.load(applicationContext)
        if (PaperEngine.watched(book).isEmpty()) {
            WorkManager.getInstance(applicationContext).cancelUniqueWork(WORK)
            return Result.success()
        }
        val now = MarketClock.now()
        val minutes = MarketClock.minutesSinceOpen(now)
        if (!MarketClock.isWeekday(now.toLocalDate()) || minutes < -10.0 || minutes > MarketClock.SESSION_MINUTES + 30.0) return Result.success()
        val (events, _) = PaperStore.refresh(applicationContext)
        if (events.isNotEmpty()) Notifier.paper(applicationContext, events)
        return Result.success()
    }

    companion object {
        private const val WORK = "paper_trading"

        /** Starts the checks; already running, they go on as they are. */
        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<PaperWorker>(15, TimeUnit.MINUTES)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(WORK, ExistingPeriodicWorkPolicy.KEEP, request)
        }
    }
}
