package com.rohit.share_scan

import org.json.JSONObject
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.GZIPInputStream
import kotlin.math.max
import kotlin.math.min

const val CRORE = 10_000_000.0

data class ScanResult(
    val symbol: String,
    val price: Double,
    val score: Double,
    val rvol: Double,
    val change5d: Double,
    /** Shares traded in the latest session. */
    val volume: Double = 0.0,
    /** Null when NSE's share-count file was unavailable or did not list the symbol. */
    val marketCapCr: Double? = null,
) {
    /** Rupee value traded in the latest session, in crore. */
    val turnoverCr: Double get() = price * volume / CRORE
}

/** Every stock one scan could score, best score first. [marketCapDate] is null when market caps are missing. */
data class ScanOutput(val results: List<ScanResult>, val marketCapDate: LocalDate?)

object NseScanner {
    private const val NSE_CSV = "https://archives.nseindia.com/content/equities/EQUITY_L.csv"
    private const val SECURITY_FILE = "https://nsearchives.nseindia.com/content/cm/NSE_CM_security_%s.csv.gz"
    private const val USER_AGENT = "ShareScan/1.0 (Android)"
    private val BYTE_ORDER_MARK = Char(0xFEFF)
    private const val BROWSER_AGENT =
        "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124 Mobile Safari/537.36"

    fun scan(onProgress: (Int, Int) -> Unit): ScanOutput {
        val symbols = loadSymbols()
        onProgress(0, symbols.size)
        val done = AtomicInteger(0)
        val pool = Executors.newFixedThreadPool(4)
        try {
            val issuedShares = pool.submit(Callable { runCatching { loadIssuedShares() }.getOrNull() })
            val tasks = symbols.map { symbol -> Callable {
                try { score(symbol) } finally { onProgress(done.incrementAndGet(), symbols.size) }
            }}
            val scored = pool.invokeAll(tasks).mapNotNull { future ->
                runCatching { future.get() }.getOrNull()
            }
            val shares = runCatching { issuedShares.get() }.getOrNull()
            val results = scored.map { row ->
                val issued = shares?.second?.get(row.symbol) ?: return@map row
                row.copy(marketCapCr = row.price * issued / CRORE)
            }
            return ScanOutput(results.sortedByDescending { it.score }, shares?.first)
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
        return ScanResult(symbol, close, score, rvol, momentum, volume)
    }

    /**
     * Issued share counts from NSE's daily security file, the same source the Python scanner uses for
     * market cap. NSE only publishes it on trading days, so recent dates are tried newest first.
     */
    private fun loadIssuedShares(): Pair<LocalDate, Map<String, Double>>? {
        val today = LocalDate.now(ZoneId.of("Asia/Kolkata"))
        val format = DateTimeFormatter.ofPattern("ddMMyyyy", Locale.US)
        for (daysBack in 0L until 10L) {
            val day = today.minusDays(daysBack)
            val shares = runCatching {
                open(SECURITY_FILE.format(day.format(format)), browser = true) { GZIPInputStream(it).use(::parseIssuedShares) }
            }.getOrNull()
            if (shares != null && shares.size >= 500) return day to shares
        }
        return null
    }

    /** Keeps live `EQ` rows only: `IssdCptl` is the number of issued shares. */
    internal fun parseIssuedShares(input: InputStream): Map<String, Double> {
        val lines = input.bufferedReader().lineSequence().iterator()
        if (!lines.hasNext()) return emptyMap()
        val header = splitCsv(lines.next().trimStart(BYTE_ORDER_MARK)).map { it.trim() }
        val symbolAt = header.indexOf("TckrSymb")
        val seriesAt = header.indexOf("SctySrs")
        val issuedAt = header.indexOf("IssdCptl")
        val deletedAt = header.indexOf("DelFlg")
        if (symbolAt < 0 || seriesAt < 0 || issuedAt < 0) return emptyMap()
        val shares = HashMap<String, Double>()
        for (line in lines) {
            val cells = splitCsv(line)
            fun cell(index: Int) = cells.getOrNull(index)?.trim().orEmpty()
            val issued = cell(issuedAt).toDoubleOrNull() ?: continue
            if (!issued.isFinite() || issued <= 0.0) continue
            if (!cell(seriesAt).equals("EQ", ignoreCase = true)) continue
            if (deletedAt >= 0 && cell(deletedAt).equals("Y", ignoreCase = true)) continue
            val symbol = cell(symbolAt).uppercase(Locale.US)
            if (symbol.isNotEmpty()) shares[symbol] = issued
        }
        return shares
    }

    /** Splits one CSV line, honouring quoted cells that contain commas or doubled quotes. */
    internal fun splitCsv(line: String): List<String> {
        val cells = ArrayList<String>()
        val cell = StringBuilder()
        var quoted = false
        var i = 0
        while (i < line.length) {
            val c = line[i]
            when {
                c == '"' && quoted && line.getOrNull(i + 1) == '"' -> { cell.append('"'); i++ }
                c == '"' -> quoted = !quoted
                c == ',' && !quoted -> { cells.add(cell.toString()); cell.setLength(0) }
                else -> cell.append(c)
            }
            i++
        }
        cells.add(cell.toString())
        return cells
    }

    private fun get(address: String): String = open(address) { it.bufferedReader().use { reader -> reader.readText() } }

    private fun <T> open(address: String, browser: Boolean = false, read: (InputStream) -> T): T {
        val connection = (URL(address).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 25_000
            if (browser) {
                // nsearchives serves its report files to browser-like requests only.
                setRequestProperty("User-Agent", BROWSER_AGENT)
                setRequestProperty("Referer", "https://www.nseindia.com/all-reports")
                setRequestProperty("Accept", "text/csv,application/gzip,application/octet-stream,*/*")
            } else {
                setRequestProperty("User-Agent", USER_AGENT)
                setRequestProperty("Accept", "application/json,text/plain,*/*")
            }
        }
        return try {
            if (connection.responseCode !in 200..299) error("HTTP ${connection.responseCode}")
            connection.inputStream.use(read)
        } finally { connection.disconnect() }
    }
}
