package com.rohit.share_scan

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.edit
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

/**
 * Temporary, debug builds only, for the emulator review: "probe" logs how each news source answers the app;
 * "badges" logs which stocks carry a 📰 mark; "star" puts the
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
                    "probe" -> probe()
                    "badges" -> {
                        val items = NewsRepository.market().orEmpty()
                        NewsRepository.badges().forEach { (symbol, badge) ->
                            val top = items.firstOrNull { it.symbol == symbol && it.kind == badge.kind }
                            Log.i(TAG, "badge $symbol ${badge.kind} ${badge.count} ${top?.title?.take(140)}")
                        }
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

    private fun probe() {
        val today = MarketClock.now().toLocalDate()
        val nseDay = java.time.format.DateTimeFormatter.ofPattern("dd-MM-yyyy")
        val agent = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/129.0 Mobile Safari/537.36"
        val nseRef = "https://www.nseindia.com/companies-listing/corporate-filings-announcements"
        var cookie: String? = null
        fun raw(label: String, address: String, headers: Map<String, String>) {
            runCatching {
                val c = URL(address).openConnection() as HttpURLConnection
                c.connectTimeout = 15_000
                c.readTimeout = 25_000
                c.setRequestProperty("User-Agent", agent)
                c.setRequestProperty("Accept-Language", "en-IN,en;q=0.9,hi;q=0.8")
                headers.forEach { (k, v) -> c.setRequestProperty(k, v) }
                val code = c.responseCode
                val body = (if (code < 400) c.inputStream else c.errorStream)?.use { it.readBytes() } ?: ByteArray(0)
                if (label == "nse-home") {
                    cookie = c.headerFields.entries.filter { it.key.equals("Set-Cookie", true) }.flatMap { it.value }
                        .map { it.substringBefore(';') }.joinToString("; ").ifEmpty { null }
                }
                Log.i(TAG, "probe $label: $code ${c.contentType} ${body.size}B ${String(body).take(160).replace('\n', ' ')}")
                c.disconnect()
            }.onFailure { Log.i(TAG, "probe $label: failed $it") }
        }
        val dates = "from_date=${today.minusDays(1).format(nseDay)}&to_date=${today.format(nseDay)}"
        val api = "https://www.nseindia.com/api/corporate-announcements"
        val json = mapOf("Referer" to nseRef, "Accept" to "application/json, text/plain, */*")
        raw("nse-stock", "$api?index=equities&symbol=RELIANCE&from_date=${today.minusDays(30).format(nseDay)}&to_date=${today.format(nseDay)}", json)
        raw("nse-market", "$api?index=equities&$dates", json)
        raw("nse-home", "https://www.nseindia.com/", mapOf("Accept" to "text/html"))
        Log.i(TAG, "probe cookie names: ${cookie?.split("; ")?.map { it.substringBefore('=') }}")
        raw("nse-market-cookie", "$api?index=equities&$dates", json + listOfNotNull(cookie?.let { "Cookie" to it }))
        raw(
            "bse-stock",
            "https://api.bseindia.com/BseIndiaAPI/api/AnnSubCategoryGetData/w?pageno=1&strCat=-1&strPrevDate=" +
                "${today.minusDays(30).format(java.time.format.DateTimeFormatter.BASIC_ISO_DATE)}&strScrip=500325&strSearch=P" +
                "&strToDate=${today.format(java.time.format.DateTimeFormatter.BASIC_ISO_DATE)}&strType=C&subcategory=-1",
            mapOf("Referer" to "https://www.bseindia.com/", "Origin" to "https://www.bseindia.com", "Accept" to "application/json"),
        )
        raw("google", "https://news.google.com/rss/search?q=%22Reliance+Industries%22+when%3A7d&hl=en-IN&gl=IN&ceid=IN%3Aen", mapOf("Accept" to "application/rss+xml, */*"))
        Log.i(TAG, "probe repo market: ${NewsRepository.market()?.size}")
        Log.i(TAG, "probe repo nse stock: ${NewsRepository.official("RELIANCE", Market.NSE, "", today.minusDays(30), today)?.size}")
        Log.i(TAG, "probe repo bse stock: ${NewsRepository.official("RELIANCE", Market.BSE, "500325", today.minusDays(30), today)?.size}")
        Log.i(TAG, "probe repo articles: ${NewsRepository.articles(ScanResult("RELIANCE", name = "Reliance Industries Limited", price = 0.0, score = 0.0, rvol = 0.0, change5d = 0.0))?.size}")
    }

    private companion object {
        const val TAG = "NewsTest"
    }
}
