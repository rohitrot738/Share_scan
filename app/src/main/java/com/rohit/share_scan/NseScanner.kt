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

const val CRORE = 10_000_000.0

/** Keys of the score ingredients in [ScanResult.parts]. */
object ScoreKeys {
    const val LIQUIDITY = "liquidity"
    const val VOLUME = "volume"
    const val MOMENTUM = "momentum"
    const val BREAKOUT = "breakout"
    const val TREND = "trend"
}

/** One ingredient of the score: its 0–100 value and its weight in the total. */
data class ScorePart(val key: String, val value: Double, val weight: Double)

/** Price structure derived from the recent daily candles, for breakout signals and the detail screen. */
data class PriceStructure(
    /** Up to the last 60 daily closes, oldest first. */
    val closes: List<Double>,
    /** Highest high of the 20 sessions before the latest one. */
    val high20: Double,
    /** Lowest low of the latest 10 sessions. */
    val low10: Double,
    /** 14-session average true range. */
    val atr14: Double,
    val sma20: Double,
    /** Null when fewer than 50 sessions are available. */
    val sma50: Double?,
    /** Highest high of the latest 10 sessions, the top of the base that [low10] bottoms; null when not recorded. */
    val high10: Double? = null,
)

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
    /** Null until the scanner has computed it for this stock. */
    val structure: PriceStructure? = null,
    /** How [score] was built; empty when the scanner did not record it. */
    val parts: List<ScorePart> = emptyList(),
    /** Company name from NSE's listing, e.g. "Tata Consultancy Services Limited"; empty when unknown. */
    val name: String = "",
    /** Listed on NSE's SME platform (Emerge) rather than the main board. */
    val sme: Boolean = false,
) {
    /** Rupee value traded in the latest session, in crore. */
    val turnoverCr: Double get() = price * volume / CRORE

    /** Percent below the 20-day high ([PriceStructure.high20]); negative once price has broken above it. */
    val belowHigh20Pct: Double?
        get() = structure?.high20?.takeIf { it > 0.0 }?.let { (it - price) / it * 100.0 }

    /** Width of the latest 10-session base, highest high to lowest low, as a percent of its midpoint; small = tight. */
    val baseRangePct: Double?
        get() = structure?.let { s -> s.high10?.let { high -> BreakoutSignals.rangePct(high, s.low10) } }

    /** Percent above (+) or below (-) the 50-day simple moving average. */
    val vsSma50Pct: Double?
        get() = structure?.sma50?.takeIf { it > 0.0 }?.let { (price / it - 1.0) * 100.0 }
}

/** Every stock one scan could score, best score first. [marketCapDate] is null when market caps are missing. */
data class ScanOutput(val results: List<ScanResult>, val marketCapDate: LocalDate?)

/** One row of an NSE listing file. */
data class Listing(val symbol: String, val name: String, val sme: Boolean = false)

object NseScanner {
    private const val NSE_CSV = "https://archives.nseindia.com/content/equities/EQUITY_L.csv"
    /** NSE Emerge (SME platform) listings; scanned only when the user turns them on in Settings. */
    private val SME_CSVS = listOf(
        "https://nsearchives.nseindia.com/emerge/corporates/content/SME_EQUITY_L.csv",
        "https://archives.nseindia.com/emerge/corporates/content/SME_EQUITY_L.csv",
    )
    private const val SECURITY_FILE = "https://nsearchives.nseindia.com/content/cm/NSE_CM_security_%s.csv.gz"
    private const val USER_AGENT = "ShareScan/1.0 (Android)"
    private val BYTE_ORDER_MARK = Char(0xFEFF)
    private const val BROWSER_AGENT =
        "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124 Mobile Safari/537.36"

    /** Scans every main-board stock, plus the SME platform's when [includeSme] is on and its list loads. */
    fun scan(includeSme: Boolean = false, onProgress: (Int, Int) -> Unit): ScanOutput {
        val main = parseListings(get(NSE_CSV), sme = false)
        val sme = if (includeSme) loadSmeListings() else emptyList()
        val symbols = (main + sme).distinctBy { it.symbol }
        onProgress(0, symbols.size)
        val done = AtomicInteger(0)
        val pool = Executors.newFixedThreadPool(4)
        try {
            val issuedShares = pool.submit(Callable { runCatching { loadIssuedShares() }.getOrNull() })
            val tasks = symbols.map { listing -> Callable {
                try { score(listing) } finally { onProgress(done.incrementAndGet(), symbols.size) }
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

    /** The SME list from whichever NSE host answers; empty when none does, so the main-board scan still runs. */
    private fun loadSmeListings(): List<Listing> {
        for (address in SME_CSVS) {
            val listings = runCatching { parseListings(open(address, browser = true) { it.bufferedReader().readText() }, sme = true) }
                .getOrNull()
            if (!listings.isNullOrEmpty()) return listings
        }
        return emptyList()
    }

    /**
     * Symbols and company names from an NSE listing file (EQUITY_L.csv or SME_EQUITY_L.csv), whose first two
     * columns are SYMBOL and NAME OF COMPANY. Rows with a malformed symbol are skipped.
     */
    internal fun parseListings(csv: String, sme: Boolean): List<Listing> {
        val lines = csv.trimStart(BYTE_ORDER_MARK).lineSequence().filter { it.isNotBlank() }.iterator()
        if (!lines.hasNext()) return emptyList()
        // EQUITY_L.csv says "NAME OF COMPANY", SME_EQUITY_L.csv "NAME_OF_COMPANY".
        val header = splitCsv(lines.next()).map { it.trim().uppercase(Locale.US).replace('_', ' ') }
        val symbolAt = header.indexOf("SYMBOL").takeIf { it >= 0 } ?: 0
        val nameAt = header.indexOf("NAME OF COMPANY").takeIf { it >= 0 } ?: 1
        val listings = ArrayList<Listing>()
        for (line in lines) {
            val cells = splitCsv(line)
            val symbol = cells.getOrNull(symbolAt)?.trim().orEmpty()
            if (!symbol.matches(SYMBOL)) continue
            listings += Listing(symbol, cells.getOrNull(nameAt)?.trim().orEmpty(), sme)
        }
        return listings.distinctBy { it.symbol }
    }

    private val SYMBOL = Regex("[A-Z0-9&-]+")

    /** One chart request per symbol: about 62 daily sessions, enough for the 50-day average. */
    private fun score(listing: Listing): ScanResult? {
        val symbol = listing.symbol
        val url = "https://query1.finance.yahoo.com/v8/finance/chart/$symbol.NS?range=3mo&interval=1d"
        val chart = JSONObject(get(url)).getJSONObject("chart").optJSONArray("result")?.optJSONObject(0) ?: return null
        val quote = chart.optJSONObject("indicators")?.optJSONArray("quote")?.optJSONObject(0) ?: return null
        val signals = BreakoutSignals.analyse(
            closes = quote.series("close"),
            highs = quote.series("high"),
            lows = quote.series("low"),
            volumes = quote.series("volume"),
        ) ?: return null
        return ScanResult(
            symbol = symbol,
            price = signals.price,
            score = signals.score,
            rvol = signals.rvol,
            change5d = signals.change5d,
            volume = signals.volume,
            structure = signals.structure,
            parts = signals.parts,
            name = listing.name,
            sme = listing.sme,
        )
    }

    /** One of Yahoo's daily arrays; JSON nulls (holidays, a session still forming) stay null for [BreakoutSignals]. */
    private fun JSONObject.series(name: String): List<Double?> {
        val values = optJSONArray(name) ?: return emptyList()
        return List(values.length()) { i -> if (values.isNull(i)) null else values.optDouble(i, Double.NaN) }
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
