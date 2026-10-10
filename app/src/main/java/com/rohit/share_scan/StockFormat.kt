package com.rohit.share_scan

import java.text.NumberFormat
import java.util.Locale
import kotlin.math.abs

/** Display rules shared by the scan screen; kept free of Android types so it can be unit tested. */
object StockFormat {
    const val SPIKE_RVOL = 2.0

    private val indian: Locale = Locale.forLanguageTag("en-IN")

    enum class Tier { STRONG, WATCH, WEAK }

    fun tier(score: Double): Tier = when {
        score >= 65.0 -> Tier.STRONG
        score >= 52.0 -> Tier.WATCH
        else -> Tier.WEAK
    }

    fun price(value: Double): String = NumberFormat.getNumberInstance(indian).apply {
        minimumFractionDigits = 2
        maximumFractionDigits = 2
    }.format(value)

    fun count(value: Int): String = NumberFormat.getIntegerInstance(indian).format(value)

    fun score(value: Double): String = String.format(Locale.US, "%.1f", value)

    fun rvol(value: Double): String = String.format(Locale.US, "%.2f", value)

    fun change(value: Double): String =
        String.format(Locale.US, "%s %.2f%%", if (value >= 0) "▲" else "▼", abs(value))

    fun duration(millis: Long): String {
        val seconds = millis.coerceAtLeast(0) / 1000
        return String.format(Locale.US, "%02d:%02d", seconds / 60, seconds % 60)
    }

    /** TradingView spells NSE symbols such as M&M and BAJAJ-AUTO as M_M and BAJAJ_AUTO. */
    fun chartUrl(symbol: String): String =
        "https://www.tradingview.com/chart/?symbol=NSE:" + symbol.replace('&', '_').replace('-', '_')
}
