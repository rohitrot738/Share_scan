package com.rohit.share_scan

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.LocalDate
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/** A finished scan as kept on disk: what it found, when it finished and whether live mode ran it on its own. */
data class SavedScan(
    val output: ScanOutput,
    /** Epoch millis when the scan finished. */
    val finishedAt: Long,
    /** Live mode ran it in the background, not the user's button. */
    val auto: Boolean = false,
    /** Rank moves against the scan before it, as the list showed them. */
    val badges: Map<String, RankBadge> = emptyMap(),
    /** [ScanSectors.key] of the exchanges and industries scanned; empty for a scan kept before scans had one. */
    val scope: String = "",
    /**
     * Starred stocks the scan did not cover (another exchange or industry, or no chart this time), from their last
     * known rows charted again when the scan finished, so the watchlist still shows them.
     */
    val watchExtras: List<ScanResult> = emptyList(),
)

/**
 * Keeps the latest finished scan in one gzipped file, a JSON header line and one JSON line per stock, so the list is
 * back the moment the app opens and live mode's background scans reach the screen. A file that is missing, damaged
 * or from another format version reads as no scan. Free of Android types so it can be unit tested.
 */
class ScanStore(private val file: File) {
    fun save(scan: SavedScan) {
        val temp = File(file.parentFile, file.name + ".tmp")
        GZIPOutputStream(temp.outputStream().buffered()).bufferedWriter().use { out ->
            out.write(header(scan).toString())
            out.write("\n")
            for (row in scan.output.results) {
                out.write(stock(row).toString())
                out.write("\n")
            }
            for (row in scan.watchExtras) {
                out.write(stock(row).put(WATCH_EXTRA, 1).toString())
                out.write("\n")
            }
        }
        // A rename swaps the new scan in whole, so a reader never sees half of it.
        if (!temp.renameTo(file)) {
            file.delete()
            temp.renameTo(file)
        }
    }

    fun load(): SavedScan? = runCatching {
        if (!file.isFile) return null
        GZIPInputStream(file.inputStream().buffered()).bufferedReader().useLines { lines ->
            val iterator = lines.iterator()
            if (!iterator.hasNext()) return null
            val head = JSONObject(iterator.next())
            if (head.optInt("v") != VERSION) return null
            val results = ArrayList<ScanResult>()
            val extras = ArrayList<ScanResult>()
            for (line in iterator) {
                if (line.isBlank()) continue
                val json = JSONObject(line)
                if (json.optInt(WATCH_EXTRA) == 1) extras += stock(json) else results += stock(json)
            }
            SavedScan(
                output = ScanOutput(
                    results = results,
                    marketCapDate = head.optString("capDate").takeIf { it.isNotEmpty() }?.let(LocalDate::parse),
                    stopped = head.optBoolean("stopped"),
                    benchmark = head.optJSONObject("nifty")?.let(::series),
                    coverage = head.optJSONArray("cov")?.let(::coverage),
                ),
                finishedAt = head.optLong("at"),
                auto = head.optBoolean("auto"),
                badges = badges(head.optJSONObject("badges")),
                scope = head.optString("scope"),
                watchExtras = extras,
            )
        }
    }.getOrNull()

    fun clear() {
        file.delete()
    }

    private fun header(scan: SavedScan) = JSONObject().apply {
        put("v", VERSION)
        put("at", scan.finishedAt)
        put("auto", scan.auto)
        put("scope", scan.scope)
        put("stopped", scan.output.stopped)
        scan.output.marketCapDate?.let { put("capDate", it.toString()) }
        scan.output.benchmark?.let { put("nifty", series(it)) }
        scan.output.coverage?.let { c ->
            put("cov", JSONArray().put(c.listed).put(c.charted).put(c.noData).put(c.tooNew).put(c.limited).put(c.failed))
        }
        put("badges", JSONObject().apply {
            for ((symbol, badge) in scan.badges) {
                put(symbol, if (badge is RankBadge.Moved) badge.places else NEW)
            }
        })
    }

    private fun badges(json: JSONObject?): Map<String, RankBadge> {
        if (json == null) return emptyMap()
        val badges = HashMap<String, RankBadge>()
        for (symbol in json.keys()) {
            val value = json.opt(symbol)
            badges[symbol] = if (value is Number) RankBadge.Moved(value.toInt()) else RankBadge.New
        }
        return badges
    }

    private fun stock(row: ScanResult) = JSONObject().apply {
        put("s", row.symbol)
        put("n", row.name)
        put("m", row.market.name)
        if (row.bseCode.isNotEmpty()) put("b", row.bseCode)
        if (row.sector.isNotEmpty()) put("sec", row.sector)
        put("p", num(row.price))
        put("sc", num(row.score))
        put("rv", num(row.rvol))
        put("c5", num(row.change5d))
        put("vol", num(row.volume))
        row.marketCapCr?.let { put("mc", num(it)) }
        if (row.live) put("live", true)
        if (row.parts.isNotEmpty()) {
            put("parts", JSONArray().apply {
                for (part in row.parts) put(JSONArray().put(part.key).put(num(part.value)).put(num(part.weight)))
            })
        }
        row.structure?.let { put("st", structure(it)) }
        if (row.pastScores.isNotEmpty()) {
            put("past", JSONArray().apply { for (past in row.pastScores) put(JSONArray().put(past.day).put(num(past.score))) })
        }
        row.support?.let { floor ->
            put(
                "sup",
                JSONArray().put(num(floor.level)).put(floor.ageSessions).put(floor.touches)
                    .put(if (floor.unbroken) 1 else 0).put(floor.window),
            )
        }
        if (row.setupMask != 0L) put("sm", row.setupMask)
    }

    private fun stock(json: JSONObject): ScanResult = ScanResult(
        symbol = json.getString("s"),
        price = json.double("p"),
        score = json.double("sc"),
        rvol = json.double("rv"),
        change5d = json.double("c5"),
        volume = json.double("vol"),
        marketCapCr = if (json.isNull("mc")) null else json.double("mc"),
        structure = json.optJSONObject("st")?.let(::structure),
        parts = json.optJSONArray("parts")?.let { parts ->
            List(parts.length()) { i ->
                val part = parts.getJSONArray(i)
                ScorePart(part.getString(0), part.doubleAt(1), part.doubleAt(2))
            }
        }.orEmpty(),
        name = json.optString("n"),
        market = runCatching { Market.valueOf(json.optString("m")) }.getOrDefault(Market.NSE),
        bseCode = json.optString("b"),
        sector = json.optString("sec"),
        live = json.optBoolean("live"),
        pastScores = json.optJSONArray("past")?.let { past ->
            List(past.length()) { i ->
                val pair = past.getJSONArray(i)
                DayScore(pair.getLong(0), pair.doubleAt(1))
            }
        }.orEmpty(),
        setupMask = json.optLong("sm"),
        support = json.optJSONArray("sup")?.let { floor ->
            SupportFloor(
                level = floor.doubleAt(0),
                ageSessions = floor.getInt(1),
                touches = floor.getInt(2),
                unbroken = floor.getInt(3) == 1,
                window = floor.getInt(4),
            )
        },
    )

    private fun structure(s: PriceStructure) = JSONObject().apply {
        put("c", JSONArray().apply { for (close in s.closes) put(num(close)) })
        if (s.closeDays.isNotEmpty()) put("d", JSONArray().apply { for (day in s.closeDays) put(day) })
        put("h20", num(s.high20))
        put("l10", num(s.low10))
        put("atr", num(s.atr14))
        put("s20", num(s.sma20))
        s.sma50?.let { put("s50", num(it)) }
        s.high10?.let { put("h10", num(it)) }
        if (s.weekRanges.isNotEmpty()) put("wr", JSONArray().apply { for (range in s.weekRanges) put(num(range)) })
        s.rsi14?.let { put("rsi", num(it)) }
        s.macd?.let { put("macd", num(it)) }
        s.macdSignal?.let { put("macds", num(it)) }
        s.macdCrossAge?.let { put("macdx", it) }
    }

    private fun structure(json: JSONObject): PriceStructure {
        val closes = json.getJSONArray("c")
        val days = json.optJSONArray("d")
        val ranges = json.optJSONArray("wr")
        return PriceStructure(
            closes = List(closes.length()) { closes.doubleAt(it) },
            high20 = json.double("h20"),
            low10 = json.double("l10"),
            atr14 = json.double("atr"),
            sma20 = json.double("s20"),
            sma50 = if (json.has("s50")) json.double("s50") else null,
            high10 = if (json.has("h10")) json.double("h10") else null,
            closeDays = if (days == null) emptyList() else List(days.length()) { days.getLong(it) },
            weekRanges = if (ranges == null) emptyList() else List(ranges.length()) { ranges.doubleAt(it) },
            rsi14 = if (json.has("rsi")) json.double("rsi") else null,
            macd = if (json.has("macd")) json.double("macd") else null,
            macdSignal = if (json.has("macds")) json.double("macds") else null,
            macdCrossAge = if (json.has("macdx")) json.getInt("macdx") else null,
        )
    }

    /** listed, charted, no data, too new, limited, failed; null when the array is short. */
    private fun coverage(json: JSONArray): ScanCoverage? {
        if (json.length() < 6) return null
        return ScanCoverage(json.optInt(0), json.optInt(1), json.optInt(2), json.optInt(3), json.optInt(4), json.optInt(5))
    }

    private fun series(s: DailySeries) = JSONObject().apply {
        put("d", JSONArray().apply { for (day in s.days) put(day) })
        put("c", JSONArray().apply { for (close in s.closes) put(num(close)) })
    }

    private fun series(json: JSONObject): DailySeries? {
        val days = json.optJSONArray("d") ?: return null
        val closes = json.optJSONArray("c") ?: return null
        if (days.length() != closes.length()) return null
        return DailySeries(List(days.length()) { days.getLong(it) }, List(closes.length()) { closes.doubleAt(it) })
    }

    private companion object {
        const val VERSION = 1
        const val NEW = "N"

        /** Marks a line of [SavedScan.watchExtras]; the other stock lines are the scan's own. */
        const val WATCH_EXTRA = "w"

        /** JSON has no NaN or infinity; such a value is written as null and read back as NaN. */
        fun num(value: Double): Any = if (value.isFinite()) value else JSONObject.NULL

        fun JSONObject.double(key: String): Double = if (isNull(key)) Double.NaN else getDouble(key)

        fun JSONArray.doubleAt(index: Int): Double = if (isNull(index)) Double.NaN else getDouble(index)
    }
}
