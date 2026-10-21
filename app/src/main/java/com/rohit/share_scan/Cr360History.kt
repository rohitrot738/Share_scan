package com.rohit.share_scan

import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import java.util.zip.CRC32

/**
 * One stock's R/S history, as the daily cache keeps it: quarterly results, quarterly shareholding, yearly results,
 * cash flow and debt, and a month-end close for three years. It comes from one of [SHARDS] history files
 * (cr360-h-00 ... cr360-h-31.json.gz), so the app fetches only the file of the stock that is opened. Periods are
 * short ("2606" = June 2026). Free of Android types so it can be unit tested.
 */
class Cr360History(
    val quarters: List<Quarter>,
    val holders: List<Holding>,
    val years: List<Year>,
    val chart: Chart?,
) {
    /** Sales, net profit (₹ crore) and EPS (₹) of a quarter, or of a half-year for half-yearly reporters. */
    class Quarter(val period: String, val sales: Double?, val profit: Double?, val eps: Double?)

    /** Who held the shares at a quarter's end, in % of the company. */
    class Holding(val period: String, val promoter: Double?, val fii: Double?, val dii: Double?, val public: Double?)

    /** A financial year: sales, profit, operating and free cash flow, and debt (₹ crore). */
    class Year(
        val period: String,
        val sales: Double?,
        val profit: Double?,
        val operatingCash: Double?,
        val freeCash: Double?,
        val debt: Double?,
    )

    /** Each month's last close from [firstMonth] on; null for a month without trading. */
    class Chart(val firstMonth: String, val closes: List<Double?>)

    companion object {
        /** How many history files there are; the cache (cr360/app_store.py HISTORY_SHARDS) writes the same. */
        const val SHARDS = 32

        private val MONTHS = listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")

        /** The history file holding [symbol]: CRC-32 of the symbol, as the cache computes it (history_shard). */
        fun shard(symbol: String): Int {
            val crc = CRC32()
            crc.update(symbol.toByteArray(Charsets.UTF_8))
            return (crc.value % SHARDS).toInt()
        }

        /** [symbol]'s history from a history file's JSON; null when the file is broken or does not have the stock. */
        fun parse(json: String?, symbol: String): Cr360History? {
            if (json.isNullOrBlank()) return null
            val stock = runCatching {
                val root = JSONObject(json)
                if (root.optInt("v") != 1) return null
                root.optJSONObject("stocks")?.optJSONObject(symbol)
            }.getOrNull() ?: return null
            return Cr360History(
                quarters = rows(stock.optJSONArray("q")) { Quarter(it.period(), it.num(1), it.num(2), it.num(3)) },
                holders = rows(stock.optJSONArray("s")) { Holding(it.period(), it.num(1), it.num(2), it.num(3), it.num(4)) },
                years = rows(stock.optJSONArray("y")) { Year(it.period(), it.num(1), it.num(2), it.num(3), it.num(4), it.num(5)) },
                chart = stock.optJSONObject("p")?.let { p ->
                    val closes = p.optJSONArray("c") ?: return@let null
                    Chart(p.optString("m"), List(closes.length()) { closes.num(it) })
                },
            )
        }

        /** "2606" -> "Jun 26"; the input as it is when it is not a short period. */
        fun label(period: String): String {
            val month = period.drop(2).toIntOrNull()
            return if (period.length == 4 && month != null && month in 1..12) "${MONTHS[month - 1]} ${period.take(2)}" else period
        }

        /** A year ending in March is a financial year ("2603" -> "FY26"); any other year end reads as its month. */
        fun yearLabel(period: String): String = if (period.length == 4 && period.endsWith("03")) "FY${period.take(2)}" else label(period)

        /** The month [offset] months after [firstMonth] ("2310", 3 -> "2401"). */
        fun monthAfter(firstMonth: String, offset: Int): String {
            val year = firstMonth.take(2).toIntOrNull() ?: return firstMonth
            val month = firstMonth.drop(2).toIntOrNull() ?: return firstMonth
            val index = year * 12 + (month - 1) + offset
            return String.format(Locale.US, "%02d%02d", index / 12, index % 12 + 1)
        }

        private inline fun <T> rows(array: JSONArray?, make: (JSONArray) -> T): List<T> {
            if (array == null) return emptyList()
            return (0 until array.length()).mapNotNull { i -> array.optJSONArray(i)?.takeIf { it.length() > 0 }?.let(make) }
        }

        private fun JSONArray.period(): String = optString(0)

        private fun JSONArray.num(index: Int): Double? =
            if (isNull(index)) null else optDouble(index).takeIf { it.isFinite() }
    }
}
