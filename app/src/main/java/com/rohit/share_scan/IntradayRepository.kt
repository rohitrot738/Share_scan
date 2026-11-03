package com.rohit.share_scan

import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap

/**
 * The latest session's 5-minute candles from Yahoo's chart, as the scan reads its daily ones, turned into an intraday
 * call ([IntradayPlan]). Each stock's answer is kept for [FRESH_MS], so the detail sheet and the intraday list share
 * one request; requests are paced like the scan's, and a 429 pauses them all.
 */
object IntradayRepository {
    private const val USER_AGENT = "ShareScan/1.0 (Android)"
    private const val FRESH_MS = 60_000L
    private const val RATE_LIMIT_PAUSE_MS = 5_000L
    private const val HTTP_TOO_MANY_REQUESTS = 429
    private val yahoo = RateGate(burst = 20, perSecond = 6.0)
    private val kept = ConcurrentHashMap<String, Pair<Long, Answer>>()

    /** What Yahoo gave: the call, or null when its candles hold no opening range (no trading yet, or ever). */
    class Answer(val plan: IntradayPlan?)

    fun ticker(stock: ScanResult): String = when (stock.market) {
        Market.NSE, Market.NSE_SME -> "${stock.symbol}.NS"
        Market.BSE -> "${stock.bseCode.ifBlank { stock.symbol }}.BO"
    }

    /** [stock]'s intraday call; null when Yahoo did not answer, or [isStopped] turned true while waiting. */
    fun plan(stock: ScanResult, isStopped: () -> Boolean = { false }, now: Long = System.currentTimeMillis()): Answer? {
        val ticker = ticker(stock)
        kept[ticker]?.let { (at, answer) -> if (now - at < FRESH_MS) return answer }
        repeat(2) {
            if (!yahoo.acquire(isStopped)) return null
            val body = try {
                get("https://query1.finance.yahoo.com/v8/finance/chart/$ticker?range=1d&interval=5m")
            } catch (limited: HttpStatus) {
                if (limited.status != HTTP_TOO_MANY_REQUESTS) return null
                yahoo.pause(RATE_LIMIT_PAUSE_MS)
                null
            } catch (_: IOException) {
                return null
            } ?: return@repeat
            val answer = runCatching { Answer(IntradayPlan.of(candles(body))) }.getOrNull() ?: return null
            kept[ticker] = now to answer
            return answer
        }
        return null
    }

    /** The candles in a Yahoo chart answer; a candle with a missing price is left out. */
    internal fun candles(json: String): List<IntradayPlan.Candle> {
        val chart = JSONObject(json).getJSONObject("chart").optJSONArray("result")?.optJSONObject(0) ?: return emptyList()
        val stamps = chart.optJSONArray("timestamp") ?: return emptyList()
        val quote = chart.optJSONObject("indicators")?.optJSONArray("quote")?.optJSONObject(0) ?: return emptyList()
        fun value(name: String, i: Int): Double? =
            quote.optJSONArray(name)?.takeUnless { it.isNull(i) }?.optDouble(i)?.takeIf { it.isFinite() }
        return (0 until stamps.length()).mapNotNull { i ->
            val open = value("open", i) ?: return@mapNotNull null
            val high = value("high", i) ?: return@mapNotNull null
            val low = value("low", i) ?: return@mapNotNull null
            val close = value("close", i) ?: return@mapNotNull null
            IntradayPlan.Candle(stamps.optLong(i), open, high, low, close, value("volume", i) ?: 0.0)
        }
    }

    private class HttpStatus(val status: Int) : IOException("HTTP $status")

    private fun get(address: String): String {
        val connection = (URL(address).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 20_000
            setRequestProperty("User-Agent", USER_AGENT)
            setRequestProperty("Accept", "application/json,text/plain,*/*")
        }
        try {
            val status = connection.responseCode
            if (status !in 200..299) {
                connection.errorStream?.use { it.readBytes() }
                throw HttpStatus(status)
            }
            return connection.inputStream.bufferedReader().use { it.readText() }
        } catch (failure: IOException) {
            connection.disconnect()
            throw failure
        }
    }
}
