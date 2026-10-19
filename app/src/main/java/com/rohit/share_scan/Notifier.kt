package com.rohit.share_scan

import android.Manifest
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

/**
 * The app's notifications: live mode's finds (stocks newly STRONG or breaking out) and watchlist alerts (breakout,
 * target, stop). Tapping one opens the app; a single stock's alert opens its detail sheet.
 */
object Notifier {
    private const val CHANNEL_LIVE = "live_finds"
    private const val CHANNEL_WATCH = "watch_alerts"
    private const val ID_LIVE = 1
    /** Each stock's alerts replace its previous one. */
    private const val ID_WATCH_BASE = 1_000

    /** The intent extra a notification passes to [MainActivity]: the stock to open. */
    const val EXTRA_SYMBOL = "symbol"

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
                if (find.breakout) context.getString(R.string.notify_tag_breakout) else null,
                if (find.newStrong) context.getString(R.string.notify_tag_strong) else null,
            ).joinToString(" • ")
            context.getString(R.string.notify_live_line, find.symbol, StockFormat.score(find.score), StockFormat.price(find.price), tags)
        }
        val more = finds.size - lines.size
        val style = NotificationCompat.InboxStyle()
        lines.forEach(style::addLine)
        if (more > 0) style.setSummaryText(context.getString(R.string.notify_live_more, more))
        val single = finds.singleOrNull()?.symbol
        post(
            context,
            ID_LIVE,
            NotificationCompat.Builder(context, CHANNEL_LIVE)
                .setSmallIcon(R.drawable.ic_trending_up)
                .setContentTitle(title)
                .setContentText(finds.joinToString(", ") { it.symbol })
                .setStyle(style)
                .setContentIntent(open(context, single, ID_LIVE))
                .setAutoCancel(true),
        )
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

    private fun open(context: Context, symbol: String?, requestCode: Int): PendingIntent {
        val intent = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        if (symbol != null) intent.putExtra(EXTRA_SYMBOL, symbol)
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
}
