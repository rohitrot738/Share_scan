package com.rohit.share_scan

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.net.toUri

/**
 * The app's notifications: live mode's finds (stocks newly STRONG or breaking out), watchlist alerts (breakout,
 * target, stop), the user's scan while it runs in the background ([ScanService]) and how it ended, and an update
 * downloaded in the background ([UpdateDownload]). Tapping one opens the app; a stock's alert opens its detail sheet.
 */
object Notifier {
    private const val CHANNEL_LIVE = "live_finds"
    private const val CHANNEL_WATCH = "watch_alerts"
    private const val CHANNEL_SCAN = "scan_progress"
    private const val CHANNEL_SCAN_DONE = "scan_done"
    private const val CHANNEL_UPDATE = "update_ready"
    private const val CHANNEL_NEWS = "stock_news"
    private const val ID_LIVE = 1

    /** The running scan's notification, which [ScanService] runs in the foreground under. */
    const val ID_SCAN = 2
    private const val ID_SCAN_DONE = 3
    private const val ID_UPDATE = 4
    private const val ID_NEWS_DIGEST = 5
    /** Each stock's news alert replaces its previous one; its "पूरी ख़बर" button counts on from [NEWS_READ_BASE]. */
    private const val ID_NEWS_BASE = 70_000
    private const val NEWS_READ_BASE = 140_000
    /** Request codes of the morning round-up's per-stock buttons. */
    private const val DIGEST_ACTION_BASE = 300
    /** Each stock's alerts replace its previous one. */
    private const val ID_WATCH_BASE = 1_000
    /** Request codes of the live finds' per-stock buttons: one after another from here. */
    private const val LIVE_ACTION_BASE = 100

    /** The intent extra a notification passes to [MainActivity]: the stock to open. */
    const val EXTRA_SYMBOL = "symbol"

    /** Set by the news notifications: the stock opens on its खबरें tab. */
    const val EXTRA_NEWS = "news"

    /** The intent extra of the "update ready" notification: hand the downloaded APK to the installer. */
    const val EXTRA_INSTALL = "install_update"

    fun canPost(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    /** Creates the channels, so they show in the system settings before the first alert. */
    fun createChannels(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_LIVE, context.getString(R.string.notify_channel_live), NotificationManager.IMPORTANCE_DEFAULT)
                .apply { description = context.getString(R.string.notify_channel_live_body) },
        )
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_WATCH, context.getString(R.string.notify_channel_watch), NotificationManager.IMPORTANCE_HIGH)
                .apply { description = context.getString(R.string.notify_channel_watch_body) },
        )
        // Quiet: it changes every second while the scan runs.
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_SCAN, context.getString(R.string.notify_channel_scan), NotificationManager.IMPORTANCE_LOW)
                .apply { description = context.getString(R.string.notify_channel_scan_body) },
        )
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_SCAN_DONE, context.getString(R.string.notify_channel_scan_done), NotificationManager.IMPORTANCE_DEFAULT)
                .apply { description = context.getString(R.string.notify_channel_scan_done_body) },
        )
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_UPDATE, context.getString(R.string.notify_channel_update), NotificationManager.IMPORTANCE_DEFAULT)
                .apply { description = context.getString(R.string.notify_channel_update_body) },
        )
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_NEWS, context.getString(R.string.notify_channel_news), NotificationManager.IMPORTANCE_HIGH)
                .apply { description = context.getString(R.string.notify_channel_news_body) },
        )
    }

    /** A big filing on a watchlisted stock ([NewsAlerts]): a tap opens the stock, the button the filing itself. */
    fun newsAlert(context: Context, item: NewsItem, name: String) {
        val id = ID_NEWS_BASE + (item.symbol.hashCode() and 0xFFFF)
        val read = PendingIntent.getActivity(
            context,
            NEWS_READ_BASE + (item.symbol.hashCode() and 0xFFFF),
            Intent(Intent.ACTION_VIEW, item.url.toUri()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        post(
            context,
            id,
            NotificationCompat.Builder(context, CHANNEL_NEWS)
                .setSmallIcon(R.drawable.ic_trending_up)
                .setContentTitle(context.getString(R.string.notify_news_alert, item.symbol, context.getString(NewsLabels.label(item.kind))))
                .setContentText(item.title)
                .setSubText(StockSearch.shortName(name).ifEmpty { item.source })
                .setStyle(NotificationCompat.BigTextStyle().bigText(item.title))
                .setWhen(item.at)
                .setShowWhen(true)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setContentIntent(open(context, item.symbol, id, news = true))
                .addAction(0, context.getString(R.string.notify_news_read), read)
                .setAutoCancel(true),
        )
    }

    /**
     * The morning's round-up of the watchlist's news: a line per stock (its weightiest item), most telling first. A tap
     * opens the first stock; a button for each of the first three opens that one.
     */
    fun newsDigest(context: Context, byStock: List<Pair<String, List<NewsItem>>>) {
        if (byStock.isEmpty()) return
        val style = NotificationCompat.InboxStyle()
        byStock.take(MAX_LINES).forEach { (symbol, news) ->
            val top = news.first()
            style.addLine(context.getString(R.string.notify_news_digest_line, symbol, context.getString(NewsLabels.label(top.kind)), top.title))
        }
        val more = byStock.size - MAX_LINES
        if (more > 0) style.setSummaryText(context.getString(R.string.notify_live_more, more))
        val (firstSymbol, firstNews) = byStock.first()
        val lead = firstNews.first()
        val builder = NotificationCompat.Builder(context, CHANNEL_NEWS)
            .setSmallIcon(R.drawable.ic_trending_up)
            .setContentTitle(context.getString(R.string.notify_news_digest, byStock.size))
            .setContentText(
                context.getString(R.string.notify_news_digest_line, firstSymbol, context.getString(NewsLabels.label(lead.kind)), lead.title),
            )
            .setStyle(style)
            .setContentIntent(open(context, firstSymbol, ID_NEWS_DIGEST, news = true))
            .setAutoCancel(true)
        if (byStock.size > 1) {
            byStock.take(MAX_ACTIONS).forEachIndexed { index, (symbol, _) ->
                builder.addAction(0, symbol, open(context, symbol, DIGEST_ACTION_BASE + index, news = true))
            }
        }
        post(context, ID_NEWS_DIGEST, builder)
    }

    /** One notification for everything a live scan found, best first. */
    fun liveFinds(context: Context, finds: List<LiveAlerts.Find>) {
        if (finds.isEmpty()) return
        val strong = finds.filter { it.newStrong }
        val breakouts = finds.filter { it.breakout }
        val title = when {
            strong.isNotEmpty() && breakouts.isNotEmpty() ->
                context.getString(R.string.notify_live_both, strong.size, breakouts.size)
            strong.isNotEmpty() -> context.getString(R.string.notify_live_strong, strong.size)
            else -> context.getString(R.string.notify_live_breakouts, breakouts.size)
        }
        val lines = finds.take(MAX_LINES).map { find ->
            val tags = listOfNotNull(
                if (find.breakout) breakoutTag(context, find.rvol) else null,
                if (find.newStrong) context.getString(R.string.notify_tag_strong) else null,
            ).joinToString(" • ")
            context.getString(R.string.notify_live_line, find.symbol, StockFormat.score(find.score), StockFormat.price(find.price), tags)
        }
        val more = finds.size - lines.size
        val style = NotificationCompat.InboxStyle()
        lines.forEach(style::addLine)
        if (more > 0) style.setSummaryText(context.getString(R.string.notify_live_more, more))
        // A tap opens the best find; with several, a button for each of the first few opens that one.
        val builder = NotificationCompat.Builder(context, CHANNEL_LIVE)
            .setSmallIcon(R.drawable.ic_trending_up)
            .setContentTitle(title)
            .setContentText(finds.joinToString(", ") { it.symbol })
            .setStyle(style)
            .setContentIntent(open(context, finds.first().symbol, ID_LIVE))
            .setAutoCancel(true)
        if (finds.size > 1) {
            finds.take(MAX_ACTIONS).forEachIndexed { index, find ->
                builder.addAction(0, find.symbol, open(context, find.symbol, LIVE_ACTION_BASE + index))
            }
        }
        post(context, ID_LIVE, builder)
    }

    /** A watchlisted stock's alert. */
    fun watchEvent(context: Context, event: WatchAlerts.Event, name: String) {
        val label = StockSearch.shortName(name).ifEmpty { event.symbol }
        val (title, body) = when (event) {
            is WatchAlerts.Event.Breakout -> context.getString(R.string.notify_watch_breakout, event.symbol) to
                context.getString(
                    R.string.notify_watch_breakout_body,
                    StockFormat.price(event.price),
                    StockFormat.price(event.entry),
                    StockFormat.price(event.stop),
                    event.target?.let(StockFormat::price) ?: "—",
                    breakoutTag(context, event.rvol),
                )
            is WatchAlerts.Event.Target -> context.getString(R.string.notify_watch_target, event.symbol, event.number) to
                context.getString(
                    R.string.notify_watch_target_body,
                    StockFormat.price(event.price),
                    StockFormat.price(event.level),
                    TradePlan.formatPercent((event.price / event.entry - 1.0) * 100.0),
                )
            is WatchAlerts.Event.Stopped -> context.getString(R.string.notify_watch_stop, event.symbol) to
                context.getString(
                    R.string.notify_watch_stop_body,
                    StockFormat.price(event.price),
                    StockFormat.price(event.stop),
                    TradePlan.formatPercent((event.price / event.entry - 1.0) * 100.0),
                )
        }
        val id = ID_WATCH_BASE + (event.symbol.hashCode() and 0xFFFF)
        post(
            context,
            id,
            NotificationCompat.Builder(context, CHANNEL_WATCH)
                .setSmallIcon(R.drawable.ic_star)
                .setContentTitle(title)
                .setContentText(body)
                .setSubText(label)
                .setStyle(NotificationCompat.BigTextStyle().bigText(body))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setContentIntent(open(context, event.symbol, id))
                .setAutoCancel(true),
        )
    }

    /** The ongoing notification of a scan the user started: how far it has got, and a button that stops it. */
    fun scanProgress(context: Context, progress: ScanEngine.Progress): Notification {
        val builder = NotificationCompat.Builder(context, CHANNEL_SCAN)
            .setSmallIcon(R.drawable.ic_radar)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(open(context, null, ID_SCAN))
        when {
            progress.stopping -> builder.setContentTitle(context.getString(R.string.notify_scan_stopping)).setProgress(0, 0, true)
            progress.loading || progress.total <= 0 -> builder
                .setContentTitle(context.getString(R.string.notify_scan_title))
                .setContentText(context.getString(R.string.notify_scan_loading))
                .setProgress(0, 0, true)
            else -> builder
                .setContentTitle(context.getString(R.string.notify_scan_title))
                .setContentText(
                    context.getString(
                        R.string.notify_scan_body,
                        StockFormat.count(progress.done),
                        StockFormat.count(progress.total),
                        (progress.done * 100 / progress.total).coerceIn(0, 100),
                    ),
                )
                .setProgress(progress.total, progress.done.coerceAtMost(progress.total), false)
        }
        if (!progress.stopping) {
            builder.addAction(R.drawable.ic_stop, context.getString(R.string.action_stop), ScanService.stopIntent(context))
        }
        return builder.build()
    }

    /** Shows [notification] as the running scan's, in place of the one before. */
    fun showScan(context: Context, notification: Notification) {
        if (!canPost(context)) return
        try {
            NotificationManagerCompat.from(context).notify(ID_SCAN, notification)
        } catch (denied: SecurityException) {
            // The permission was taken back between the check and the post.
        }
    }

    /**
     * How the user's scan [runId] ended, for when it ended with the app out of sight: how many stocks made the list
     * (the filters on the home screen, Top limit included) and which is first, or that it stopped or failed.
     */
    fun scanDone(context: Context, runId: Int) {
        val failure = ScanEngine.failure.value?.takeIf { it.runId == runId }
        val latest = ScanEngine.latest.value
        val (title, body) = when {
            failure != null && !failure.stoppedEarly -> context.getString(R.string.status_failed_title) to
                (failure.message?.let { context.getString(R.string.status_failed_detail, it) }
                    ?: context.getString(R.string.status_failed_body))
            failure != null || latest == null -> context.getString(R.string.notify_scan_stopped_early) to
                context.getString(R.string.notify_scan_stopped_early_body)
            else -> {
                val prefs = context.getSharedPreferences(Prefs.FILE, Context.MODE_PRIVATE)
                val filters = ScanFilters.decode(prefs.getString(Prefs.FILTERS, null))
                val inside = latest.output.results.filter(filters::accepts)
                val listed = if (filters.limit > 0) minOf(inside.size, filters.limit) else inside.size
                val top = inside.firstOrNull()
                context.getString(if (latest.output.stopped) R.string.notify_scan_stopped else R.string.notify_scan_done, listed) to
                    (top?.let { context.getString(R.string.notify_scan_top, it.symbol, StockFormat.score(it.score)) }
                        ?: context.getString(R.string.notify_scan_none))
            }
        }
        post(
            context,
            ID_SCAN_DONE,
            NotificationCompat.Builder(context, CHANNEL_SCAN_DONE)
                .setSmallIcon(R.drawable.ic_check)
                .setContentTitle(title)
                .setContentText(body)
                .setStyle(NotificationCompat.BigTextStyle().bigText(body))
                .setContentIntent(open(context, null, ID_SCAN_DONE))
                .setAutoCancel(true),
        )
    }

    /** The app is open again: the scan's result is on the screen. */
    fun cancelScanDone(context: Context) = NotificationManagerCompat.from(context).cancel(ID_SCAN_DONE)

    /** An update [versionName] finished downloading with the app out of sight; a tap hands it to the installer. */
    fun updateReady(context: Context, versionName: String) {
        val intent = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra(EXTRA_INSTALL, true)
        val tap = PendingIntent.getActivity(context, ID_UPDATE, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        post(
            context,
            ID_UPDATE,
            NotificationCompat.Builder(context, CHANNEL_UPDATE)
                .setSmallIcon(R.drawable.ic_check)
                .setContentTitle(context.getString(R.string.notify_update_ready, versionName))
                .setContentText(context.getString(R.string.notify_update_ready_body))
                .setContentIntent(tap)
                .setAutoCancel(true),
        )
    }

    /** The update is being installed, or was put off. */
    fun cancelUpdateReady(context: Context) = NotificationManagerCompat.from(context).cancel(ID_UPDATE)

    /** "breakout • volume 2.4× ✓", or the volume marked as too low when it is under [BreakoutSetup.VOLUME_RVOL]. */
    private fun breakoutTag(context: Context, rvol: Double): String = context.getString(
        if (rvol >= BreakoutSetup.VOLUME_RVOL) R.string.notify_tag_breakout_volume else R.string.notify_tag_breakout_low,
        StockFormat.rvol(rvol),
    )

    private fun open(context: Context, symbol: String?, requestCode: Int, news: Boolean = false): PendingIntent {
        val intent = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        if (symbol != null) intent.putExtra(EXTRA_SYMBOL, symbol)
        if (news) intent.putExtra(EXTRA_NEWS, true)
        return PendingIntent.getActivity(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun post(context: Context, id: Int, builder: NotificationCompat.Builder) {
        if (!canPost(context)) return
        createChannels(context)
        try {
            NotificationManagerCompat.from(context).notify(id, builder.build())
        } catch (denied: SecurityException) {
            // The permission was taken back between the check and the post.
        }
    }

    private const val MAX_LINES = 6

    /** Android shows at most three buttons on a notification. */
    private const val MAX_ACTIONS = 3
}
