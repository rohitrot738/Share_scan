package com.rohit.share_scan

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.edit
import kotlin.concurrent.thread

/**
 * Temporary, debug builds only, for the emulator review: "badges" logs which stocks carry a 📰 mark; "star" puts the
 * given symbols on the watchlist; "digest" runs the news round-up at any hour; "alert" posts the first symbol's latest
 * big filing as a news alert.
 */
class NewsTestReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext
        val symbols = intent.getStringExtra("symbols")?.split(',')?.filter { it.isNotBlank() }.orEmpty()
        val done = goAsync()
        thread {
            try {
                when (intent.getStringExtra("kind")) {
                    "badges" -> NewsRepository.badges().forEach { (symbol, badge) ->
                        Log.i(TAG, "badge $symbol ${badge.kind} ${badge.count}")
                    }
                    "star" -> app.getSharedPreferences(Prefs.FILE, Context.MODE_PRIVATE).edit {
                        putStringSet(Prefs.WATCHLIST, symbols.toSet())
                    }
                    "digest" -> NewsAlerts.check(app, force = true)
                    "alert" -> {
                        val symbol = symbols.first()
                        val today = MarketClock.now().toLocalDate()
                        val filings = NewsRepository.official(symbol, Market.NSE, "", today.minusDays(7), today).orEmpty()
                        val item = filings.filter { it.kind.big }.maxByOrNull { it.at } ?: filings.maxByOrNull { it.at }
                        Log.i(TAG, "alert $symbol ${item?.kind} ${item?.title}")
                        if (item != null) Notifier.newsAlert(app, item, ScanEngine.peek(app, symbol)?.name.orEmpty())
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "failed", e)
            } finally {
                done.finish()
            }
        }
    }

    private companion object {
        const val TAG = "NewsTest"
    }
}
