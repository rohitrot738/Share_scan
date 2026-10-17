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
    /** Where it is listed: NSE's main board, NSE's SME platform (Emerge) or only on BSE. */
    val market: Market = Market.NSE,
    /** BSE's numeric scrip code, for BSE-only stocks; empty otherwise. */
    val bseCode: String = "",
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

/** Where a stock is listed; decides its Yahoo ticker, chart links and label. */
enum class Market { NSE, NSE_SME, BSE }

/** One row of an exchange's listing file; [isin] lets BSE stocks that also trade on NSE be skipped. */
data class Listing(
    val symbol: String,
    val name: String,
    val market: Market = Market.NSE,
    val isin: String = "",
    val bseCode: String = "",
)

object NseScanner {
    private const val NSE_CSV = "https://archives.nseindia.com/content/equities/EQUITY_L.csv"
    /** NSE Emerge (SME platform) listings; scanned only when the user turns them on in Settings. */
    private val SME_CSVS = listOf(
        "https://nsearchives.nseindia.com/emerge/corporates/content/SME_EQUITY_L.csv",
        "https://archives.nseindia.com/emerge/corporates/content/SME_EQUITY_L.csv",
    )
    private const val SECURITY_FILE = "https://nsearchives.nseindia.com/content/cm/NSE_CM_security_%s.csv.gz"
    /** BSE's daily equity bhavcopy: every scrip traded that day, with its ISIN; the source of BSE-only stocks. */
    private const val BSE_BHAVCOPY = "https://www.bseindia.com/download/BhavCopy/Equity/BhavCopy_BSE_CM_0_0_0_%s_F_0000.CSV"
    private val INDIA = ZoneId.of("Asia/Kolkata")
    private const val USER_AGENT = "ShareScan/1.0 (Android)"
    private val BYTE_ORDER_MARK = Char(0xFEFF)
    private const val BROWSER_AGENT =
        "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124 Mobile Safari/537.36"

    /**
     * Scans the markets switched on in Settings: NSE's main board ([includeNse]), NSE's SME platform ([includeSme])
     * and BSE ([includeBse]). The NSE list is still read when its stocks are off, to give BSE shares their NSE market
     * cap; an optional list that does not load is skipped and the rest of the scan runs.
     */
    fun scan(
        includeNse: Boolean = true,
        includeSme: Boolean = false,
        includeBse: Boolean = false,
        onProgress: (Int, Int) -> Unit,
    ): ScanOutput {
        val main = if (includeNse) {
            parseListings(get(NSE_CSV), Market.NSE)
        } else {
            runCatching { parseListings(get(NSE_CSV), Market.NSE) }.getOrDefault(emptyList())
        }
        val sme = if (includeSme) loadSmeListings() else emptyList()
        val bse = if (includeBse) loadBseListings() else emptyList()
        val symbols = combine(if (includeNse) main else emptyList(), sme, bse)
        if (symbols.isEmpty()) error("कोई stock list नहीं मिली")
        val nseSymbolByIsin = main.filter { it.isin.isNotEmpty() }.associate { it.isin to it.symbol }
        val isinBySymbol = symbols.associate { it.symbol to it.isin }
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
                val key = sharesKey(row, isinBySymbol[row.symbol].orEmpty(), nseSymbolByIsin) ?: return@map row
                val issued = shares?.second?.get(key) ?: return@map row
                row.copy(marketCapCr = row.price * issued / CRORE)
            }
            return ScanOutput(results.sortedByDescending { it.score }, shares?.first)
        } finally {
            pool.shutdownNow()
        }
    }

    /**
     * One list to scan from the chosen markets. BSE adds only the shares not already in [main] or [sme], so a stock
     * listed on both exchanges is scanned once, as NSE; with NSE's main board off, BSE brings its dual-listed shares.
     */
    internal fun combine(main: List<Listing>, sme: List<Listing>, bse: List<Listing>): List<Listing> {
        val nse = main + sme
        return (nse + bseOnly(bse, nse)).distinctBy { it.symbol }
    }

    /**
     * Symbol under which NSE's security file lists [row]'s issued shares: its own for NSE and SME stocks, and for a
     * BSE share the NSE symbol of the same ISIN, so dual-listed BSE shares get a market cap and BSE-only ones none.
     */
    internal fun sharesKey(row: ScanResult, isin: String, nseSymbolByIsin: Map<String, String>): String? =
        if (row.market == Market.BSE) nseSymbolByIsin[isin] else row.symbol

    /**
     * BSE stocks that do not also trade on NSE: matched by ISIN, and by symbol so an unrelated BSE scrip never takes
     * an NSE stock's place in the watchlist or rank history.
     */
    internal fun bseOnly(bse: List<Listing>, nse: List<Listing>): List<Listing> {
        val isins = nse.mapTo(HashSet()) { it.isin }.apply { remove("") }
        val symbols = nse.mapTo(HashSet()) { it.symbol }
        return bse.filter { it.isin !in isins && it.symbol !in symbols }
    }

    /** The latest BSE bhavcopy from the last week; on holidays BSE serves a web page instead, which parses to nothing. */
    private fun loadBseListings(): List<Listing> {
        val today = LocalDate.now(INDIA)
        val format = DateTimeFormatter.ofPattern("yyyyMMdd", Locale.US)
        for (daysBack in 0L until 8L) {
            val address = BSE_BHAVCOPY.format(today.minusDays(daysBack).format(format))
            val listings = runCatching {
                parseBseBhavcopy(open(address, browser = true, referer = "https://www.bseindia.com/") {
                    it.bufferedReader().readText()
                })
            }.getOrNull()
            if (listings != null && listings.size >= 500) return listings
        }
        return emptyList()
    }

    /**
     * Shares from a BSE bhavcopy (UDiFF format). ETFs, debt and government securities are left out by their BSE
     * group (SctySrs) and, for ETFs filed under ordinary groups, by name.
     */
    internal fun parseBseBhavcopy(csv: String): List<Listing> {
        val lines = csv.trimStart(BYTE_ORDER_MARK).lineSequence().filter { it.isNotBlank() }.iterator()
        if (!lines.hasNext()) return emptyList()
        val header = splitCsv(lines.next()).map { it.trim() }
        val symbolAt = header.indexOf("TckrSymb")
        val codeAt = header.indexOf("FinInstrmId")
        val isinAt = header.indexOf("ISIN")
        val groupAt = header.indexOf("SctySrs")
        val nameAt = header.indexOf("FinInstrmNm")
        if (symbolAt < 0 || codeAt < 0 || isinAt < 0) return emptyList()
        val listings = ArrayList<Listing>()
        for (line in lines) {
            val cells = splitCsv(line)
            fun cell(index: Int) = cells.getOrNull(index)?.trim().orEmpty()
            val symbol = cell(symbolAt).uppercase(Locale.US)
            if (!symbol.matches(SYMBOL) || cell(groupAt).uppercase(Locale.US) in BSE_NON_EQUITY_GROUPS) continue
            val name = cell(nameAt)
            if (ETF_NAME.containsMatchIn(name)) continue
            listings += Listing(symbol, titleCase(name), Market.BSE, cell(isinAt), cell(codeAt))
        }
        return listings.distinctBy { it.symbol }
    }

    /** BSE groups that are not company shares: ETFs, debt and government securities. */
    private val BSE_NON_EQUITY_GROUPS = setOf("E", "F", "G", "GS", "IF", "IT", "DT")
    private val ETF_NAME = Regex("""\bETF\b|\bBEES\b""", RegexOption.IGNORE_CASE)

    /** BSE writes names in capitals ("AEGIS LOGISTICS LTD."); NSE's style reads better on the cards. */
    internal fun titleCase(name: String): String =
        if (name.any { it.isLowerCase() }) name
        else name.lowercase(Locale.ROOT).split(' ').joinToString(" ") { word -> word.replaceFirstChar { it.titlecase(Locale.ROOT) } }

    /** The SME list from whichever NSE host answers; empty when none does, so the main-board scan still runs. */
    private fun loadSmeListings(): List<Listing> {
        for (address in SME_CSVS) {
            val listings = runCatching { parseListings(open(address, browser = true) { it.bufferedReader().readText() }, Market.NSE_SME) }
                .getOrNull()
            if (!listings.isNullOrEmpty()) return listings
        }
        return emptyList()
    }

    /**
     * Symbols and company names from an NSE listing file (EQUITY_L.csv or SME_EQUITY_L.csv), whose first two
     * columns are SYMBOL and NAME OF COMPANY. Rows with a malformed symbol are skipped.
     */
    internal fun parseListings(csv: String, market: Market): List<Listing> {
        val lines = csv.trimStart(BYTE_ORDER_MARK).lineSequence().filter { it.isNotBlank() }.iterator()
        if (!lines.hasNext()) return emptyList()
        // EQUITY_L.csv says "NAME OF COMPANY", SME_EQUITY_L.csv "NAME_OF_COMPANY".
        val header = splitCsv(lines.next()).map { it.trim().uppercase(Locale.US).replace('_', ' ') }
        val symbolAt = header.indexOf("SYMBOL").takeIf { it >= 0 } ?: 0
        val nameAt = header.indexOf("NAME OF COMPANY").takeIf { it >= 0 } ?: 1
        val isinAt = header.indexOf("ISIN NUMBER")
        val listings = ArrayList<Listing>()
        for (line in lines) {
            val cells = splitCsv(line)
            val symbol = cells.getOrNull(symbolAt)?.trim().orEmpty()
            if (!symbol.matches(SYMBOL)) continue
            val isin = if (isinAt >= 0) cells.getOrNull(isinAt)?.trim().orEmpty() else ""
            listings += Listing(symbol, cells.getOrNull(nameAt)?.trim().orEmpty(), market, isin)
        }
        return listings.distinctBy { it.symbol }
    }

    private val SYMBOL = Regex("[A-Z0-9&-]+")

    /** Yahoo tickers to try for a listing, best first: BSE stocks by symbol, then by scrip code. */
    internal fun yahooTickers(listing: Listing): List<String> = when (listing.market) {
        Market.NSE, Market.NSE_SME -> listOf("${listing.symbol}.NS")
        Market.BSE -> listOfNotNull("${listing.symbol}.BO", listing.bseCode.takeIf { it.isNotEmpty() }?.let { "$it.BO" })
    }

    /** One chart request per ticker: about 62 daily sessions, enough for the 50-day average. */
    private fun score(listing: Listing): ScanResult? {
        val symbol = listing.symbol
        val signals = yahooTickers(listing).firstNotNullOfOrNull { ticker -> runCatching { signalsFor(ticker) }.getOrNull() }
            ?: return null
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
            market = listing.market,
            bseCode = listing.bseCode,
        )
    }

    /** One of Yahoo's daily arrays; JSON nulls (holidays, a session still forming) stay null for [BreakoutSignals]. */
    private fun signalsFor(ticker: String): BreakoutSignals.Signals? {
        val url = "https://query1.finance.yahoo.com/v8/finance/chart/$ticker?range=3mo&interval=1d"
        val chart = JSONObject(get(url)).getJSONObject("chart").optJSONArray("result")?.optJSONObject(0) ?: return null
        val quote = chart.optJSONObject("indicators")?.optJSONArray("quote")?.optJSONObject(0) ?: return null
        return BreakoutSignals.analyse(
            closes = quote.series("close"),
            highs = quote.series("high"),
            lows = quote.series("low"),
            volumes = quote.series("volume"),
        )
    }

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

    /**
     * Keeps live share rows: `IssdCptl` is the number of issued shares. Besides `EQ` that covers trade-for-trade
     * (`BE`, `BZ`) and SME (`SM`, `ST`, `SZ`) series; `EQ` wins when a symbol is listed in more than one.
     */
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
            val series = cell(seriesAt).uppercase(Locale.US)
            if (series !in SHARE_SERIES) continue
            if (deletedAt >= 0 && cell(deletedAt).equals("Y", ignoreCase = true)) continue
            val symbol = cell(symbolAt).uppercase(Locale.US)
            if (symbol.isEmpty() || (symbol in shares && series != "EQ")) continue
            shares[symbol] = issued
        }
        return shares
    }

    private val SHARE_SERIES = setOf("EQ", "BE", "BZ", "SM", "ST", "SZ")

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

    private fun <T> open(
        address: String,
        browser: Boolean = false,
        referer: String = "https://www.nseindia.com/all-reports",
        read: (InputStream) -> T,
    ): T {
        val connection = (URL(address).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 25_000
            if (browser) {
                // nsearchives and BSE's downloads serve report files to browser-like requests only.
                setRequestProperty("User-Agent", BROWSER_AGENT)
                setRequestProperty("Referer", referer)
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
