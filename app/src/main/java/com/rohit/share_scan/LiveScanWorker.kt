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
 * Live mode in the background. Android runs this every 15 minutes or so, app open or not. Each run first looks for
 * the watchlist's news ([NewsAlerts], hours of its own); beyond that, outside market hours it does nothing. In market hours it runs a full scan once the latest one is [AppSettings.liveInterval] minutes old,
 * and in between it only refreshes the watchlist's prices for their alerts. The scan, its alerts and the track record
 * are [ScanEngine]'s, as for a scan the user starts.
 */
class LiveScanWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
    override fun doWork(): Result {
        val app = applicationContext
        val settings = AppSettings(app)
        // The watchlist's news first: it keeps hours of its own, morning and evening included.
        if (settings.newsAlerts) runCatching { NewsAlerts.check(app) }
        if (!settings.liveScan && !settings.watchAlerts) return Result.success()
        val now = MarketClock.now()
        if (!LiveSchedule.inHours(now)) return Result.success()
        val prefs = app.getSharedPreferences(Prefs.FILE, Context.MODE_PRIVATE)
        val today = now.toLocalDate().toEpochDay()
        if (prefs.getLong(Prefs.CLOSED_DAY, -1L) == today) return Result.success()

        val latest = ScanEngine.loaded(app)
        val due = settings.liveScan && LiveSchedule.fullScanDue(latest?.finishedAt, settings.liveInterval, System.currentTimeMillis())
        if (due) {
            val scan = ScanEngine.runAndWait(app, BUDGET_MS)
            // In market hours a scan with no price from today means the exchange is shut: a holiday.
            if (scan != null && LiveSchedule.looksClosed(now, scan.output)) prefs.edit { putLong(Prefs.CLOSED_DAY, today) }
        } else if (settings.watchAlerts) {
            ScanEngine.refreshWatchlist(app)
        }
        return Result.success()
    }

    private companion object {
        /** Android gives a background job about ten minutes; the scan stops short of that and keeps what it got. */
        const val BUDGET_MS = 8L * 60 * 1000
    }
}

/** When live mode's background work runs, and the schedule that wakes it. */
object LiveSchedule {
    private const val WORK = "live_scan"

    /** A late run after 15:30 still catches the closing prices. */
    private const val AFTER_CLOSE_MINUTES = 20.0

    /** Weekdays from the open until a little after the close. */
    fun inHours(at: java.time.ZonedDateTime): Boolean {
        val phase = MarketClock.phase(at)
        val minutes = MarketClock.minutesSinceOpen(at)
        return phase == MarketClock.Phase.OPEN ||
            (phase == MarketClock.Phase.AFTER_CLOSE && minutes < MarketClock.SESSION_MINUTES + AFTER_CLOSE_MINUTES)
    }

    /**
     * A full scan is due when there is none, or the latest finished [intervalMinutes] ago or more (a few minutes'
     * slack, since Android wakes the work only roughly every 15 minutes).
     */
    fun fullScanDue(lastFinishedAt: Long?, intervalMinutes: Int, nowMillis: Long): Boolean {
        if (lastFinishedAt == null || lastFinishedAt <= 0L) return true
        val slack = SLACK_MINUTES * 60_000L
        return nowMillis - lastFinishedAt >= intervalMinutes * 60_000L - slack
    }

    private const val SLACK_MINUTES = 4L

    /** Well into the session and still no stock has today's price: the exchange is closed today. */
    fun looksClosed(at: java.time.ZonedDateTime, output: ScanOutput): Boolean =
        MarketClock.isOpen(at) && MarketClock.minutesSinceOpen(at) > 20.0 && output.results.isNotEmpty() && !output.live

    /** Schedules the work when live scans, watchlist alerts or news alerts are on, and cancels it when all are off. */
    fun sync(context: Context) {
        val settings = AppSettings(context)
        val work = WorkManager.getInstance(context)
        if (!settings.liveScan && !settings.watchAlerts && !settings.newsAlerts) {
            work.cancelUniqueWork(WORK)
            return
        }
        val request = PeriodicWorkRequestBuilder<LiveScanWorker>(15, TimeUnit.MINUTES)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        work.enqueueUniquePeriodicWork(WORK, ExistingPeriodicWorkPolicy.UPDATE, request)
    }
}
