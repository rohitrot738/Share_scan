package com.rohit.share_scan

import java.text.NumberFormat
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

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

    /** Rupee amounts in crore: ₹0.5 Cr, ₹850 Cr, ₹12,500 Cr, ₹1.2 लाख Cr. */
    fun crore(value: Double): String = when {
        value >= 100_000 -> "₹" + short(value / 100_000) + " लाख Cr"
        value >= 100 -> "₹" + count(value.roundToInt()) + " Cr"
        else -> "₹" + short(value) + " Cr"
    }

    /** Share counts in Indian units: 850, 12.5K, 4.2 L, 1.3 Cr. */
    fun shares(value: Double): String = when {
        value >= 10_000_000 -> short(value / 10_000_000) + " Cr"
        value >= 100_000 -> short(value / 100_000) + " L"
        value >= 1_000 -> short(value / 1_000) + "K"
        else -> value.roundToInt().toString()
    }

    fun metricValue(metric: Metric, value: Double): String = when (metric) {
        Metric.MARKET_CAP, Metric.TURNOVER -> crore(value)
        Metric.PRICE -> "₹" + count(value.roundToInt())
        Metric.VOLUME -> shares(value)
        Metric.RVOL -> short(value) + "×"
        Metric.CHANGE -> (if (value > 0) "+" else "") + short(value) + "%"
        Metric.SCORE -> short(value)
    }

    /** "₹500 Cr – ₹20,000 Cr", "≥ ₹500 Cr", "≤ 3×", or [any] when the bound is open on both sides. */
    fun range(metric: Metric, bound: Bound, any: String): String {
        val min = bound.min?.let { metricValue(metric, it) }
        val max = bound.max?.let { metricValue(metric, it) }
        return when {
            min != null && max != null -> "$min – $max"
            min != null -> "≥ $min"
            max != null -> "≤ $max"
            else -> any
        }
    }

    /** One decimal at most, without a trailing ".0". */
    private fun short(value: Double): String = String.format(Locale.US, "%.1f", value).removeSuffix(".0")

    fun duration(millis: Long): String {
        val seconds = millis.coerceAtLeast(0) / 1000
        return String.format(Locale.US, "%02d:%02d", seconds / 60, seconds % 60)
    }

    /** TradingView spells NSE symbols such as M&M and BAJAJ-AUTO as M_M and BAJAJ_AUTO. */
    fun chartUrl(symbol: String): String =
        "https://www.tradingview.com/chart/?symbol=NSE:" + symbol.replace('&', '_').replace('-', '_')
}
