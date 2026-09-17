package com.rohit.share_scan

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.max
import kotlin.math.min

data class ScanResult(val symbol: String, val price: Double, val score: Double, val rvol: Double)

object NseScanner {
    private const val NSE_CSV = "https://archives.nseindia.com/content/equities/EQUITY_L.csv"
    private const val USER_AGENT = "ShareScan/1.0 (Android)"

    fun scan(onProgress: (Int, Int) -> Unit): List<ScanResult> {
        val symbols = loadSymbols()
        val done = AtomicInteger(0)
        val pool = Executors.newFixedThreadPool(4)
        try {
            val tasks = symbols.map { symbol -> Callable {
                try { score(symbol) } finally { onProgress(done.incrementAndGet(), symbols.size) }
            }}
            return pool.invokeAll(tasks).mapNotNull { future ->
                runCatching { future.get() }.getOrNull()
            }.sortedByDescending { it.score }.take(100)
        } finally {
            pool.shutdownNow()
        }
    }

    private fun loadSymbols(): List<String> {
        val rows = get(NSE_CSV).lineSequence().drop(1)
        return rows.mapNotNull { row ->
            val symbol = row.substringBefore(',').trim().trim('"')
            symbol.takeIf { it.matches(Regex("[A-Z0-9&-]+")) }
        }.distinct().toList()
    }

    private fun score(symbol: String): ScanResult? {
        val url = "https://query1.finance.yahoo.com/v8/finance/chart/$symbol.NS?range=3mo&interval=1d"
        val result = JSONObject(get(url)).getJSONObject("chart").optJSONArray("result")?.optJSONObject(0) ?: return null
        val quote = result.getJSONObject("indicators").getJSONArray("quote").getJSONObject(0)
        val closes = quote.getJSONArray("close")
        val volumes = quote.getJSONArray("volume")
        if (closes.length() < 21 || volumes.length() < 21) return null
        val close = closes.optDouble(closes.length() - 1, 0.0)
        val volume = volumes.optDouble(volumes.length() - 1, 0.0)
        if (close <= 0.0 || volume < 0.0) return null
        var average = 0.0
        for (i in closes.length() - 21 until closes.length() - 1) average += volumes.optDouble(i, 0.0)
        average /= 20.0
        val rvol = if (average > 0) volume / average else 0.0
        val oldClose = closes.optDouble(closes.length() - 6, close)
        val momentum = if (oldClose > 0) ((close / oldClose) - 1.0) * 100.0 else 0.0
        val liquidity = min(100.0, max(0.0, 20.0 + kotlin.math.log10(max(volume * close, 1.0)) * 4.0))
        val score = min(100.0, max(0.0, 0.45 * liquidity + 0.30 * min(100.0, rvol * 25.0) + 0.25 * (50.0 + momentum * 4.0)))
        return ScanResult(symbol, close, score, rvol)
    }

    private fun get(address: String): String {
        val connection = (URL(address).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 25_000
            setRequestProperty("User-Agent", USER_AGENT)
            setRequestProperty("Accept", "application/json,text/plain,*/*")
        }
        return try {
            if (connection.responseCode !in 200..299) error("HTTP ${connection.responseCode}")
            connection.inputStream.bufferedReader().use { it.readText() }
        } finally { connection.disconnect() }
    }
}
