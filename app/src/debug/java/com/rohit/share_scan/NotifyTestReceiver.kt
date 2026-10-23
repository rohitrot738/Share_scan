package com.rohit.share_scan

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Temporary, debug builds only: posts a watchlist alert ("watch") or a live scan's finds ("live"), or downloads the
 * v1.2.134 release in the background as an update would ("update"), for the review.
 */
class NotifyTestReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.getStringExtra("kind") == "update") {
            UpdateDownload.start(
                context,
                ReleaseInfo(134, "1.2.134", "https://github.com/rohitrot738/Share_scan/releases/download/v1.2.134/ShareScan.apk"),
            )
            return
        }
        val symbols = intent.getStringExtra("symbols")?.split(',')?.filter { it.isNotBlank() }.orEmpty()
        if (symbols.isEmpty()) return
        when (intent.getStringExtra("kind")) {
            "watch" -> Notifier.watchEvent(context, WatchAlerts.Event.Stopped(symbols.first(), 100.0, 101.0, 110.0), symbols.first())
            else -> Notifier.liveFinds(
                context,
                symbols.mapIndexed { i, symbol -> LiveAlerts.Find(symbol, 90.0 - i, 100.0, breakout = i == 0, newStrong = true, rvol = 2.5) },
            )
        }
    }
}
