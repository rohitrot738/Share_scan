package com.rohit.share_scan

import android.content.Context
import androidx.core.content.edit
import java.time.LocalDate

/**
 * Posts what [BigMoneyAlerts] plans from the evening's बड़े पैसे की चाल file: run from the background check
 * ([LiveScanWorker]) every 15 minutes, it fetches the file when due and tells each session once.
 */
object BigMoneyNotices {
    fun check(context: Context, now: java.time.ZonedDateTime = MarketClock.now()) {
        if (!AppSettings(context).bigMoneyAlerts) return
        runCatching { BigMoneyRepository.refreshIfStale(context, now) }
        val book = BigMoneyRepository.book(context)
        val prefs = context.getSharedPreferences(Prefs.FILE, Context.MODE_PRIVATE)
        val watchlist = prefs.getStringSet(Prefs.WATCHLIST, null).orEmpty()
        val seen = prefs.getString(Prefs.BIG_MONEY_SEEN, null)?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
        val previous = BigMoneyAlerts.decode(prefs.getString(Prefs.BIG_MONEY_FLAGS, null))
        val plan = BigMoneyAlerts.plan(book, watchlist, seen, previous, now) ?: return
        val local = Languages.localized(context)
        for ((symbol, signals) in plan.watch) {
            Notifier.bigMoneyWatch(context, symbol, BigMoneyBook.Signal.entries.filter { it in signals }.map { BigMoneyText.signal(local, it) })
        }
        Notifier.bigMoneyDigest(context, BigMoneyText.day(plan.session), plan.count, plan.top)
        prefs.edit {
            putString(Prefs.BIG_MONEY_SEEN, plan.session.toString())
            putString(Prefs.BIG_MONEY_FLAGS, BigMoneyAlerts.encode(plan.flags))
        }
    }
}
