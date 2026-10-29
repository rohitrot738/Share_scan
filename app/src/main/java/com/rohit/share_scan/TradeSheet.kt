package com.rohit.share_scan

import java.text.NumberFormat
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * Insider trades and bulk/block deals ([Cr360History.Trade]) as the R/S card's tables ([TradeTableView]) read: the
 * columns, newest first. Free of Android types so it can be unit tested.
 */
object TradeSheet {
    /** The buy/sell column of an insider sheet and of a deals sheet. */
    const val INSIDER_SIDE = 4
    const val DEALS_SIDE = 3

    /** The columns aligned to the right: shares, price, rupees (and an insider's holding after). */
    val INSIDER_FIGURES = setOf(5, 6, 7, 9)
    val DEALS_FIGURES = setOf(4, 5, 6)

    private val indian: Locale = Locale.forLanguageTag("en-IN")
    private val DAY = DateTimeFormatter.ofPattern("d MMM yy", Locale.US)

    /** Newest first: by day, then by when NSE published the filing. */
    fun newestFirst(trades: List<Cr360History.Trade>): List<Cr360History.Trade> =
        trades.sortedWith(compareByDescending<Cr360History.Trade> { it.day }.thenByDescending { it.filed.orEmpty() })

    /**
     * One row of the sheet. An insider trade: day, NSE published, name, who, buy/sell, shares, price, rupees, how,
     * holding after. A deal: day, bulk or block, name, buy/sell, shares, price, rupees. [dash] stands for what is
     * not known.
     */
    fun cells(trade: Cr360History.Trade, deal: Boolean, buy: String, sell: String, dash: String): List<String> {
        val side = if (trade.buy) buy else sell
        val shares = trade.shares?.let(::shares) ?: dash
        val price = trade.price?.let(StockFormat::price) ?: dash
        val rupees = trade.rupees?.let(::rupees) ?: dash
        return if (deal) {
            listOf(days(trade), trade.who?.replaceFirstChar { it.uppercaseChar() } ?: dash, trade.name ?: dash, side, shares, price, rupees)
        } else {
            listOf(days(trade), trade.filed?.let(::filed) ?: dash, trade.name ?: dash, trade.who ?: dash, side, shares, price, rupees,
                trade.how ?: dash, trade.holdingAfter?.let { Cr360Format.number(it, 2) + "%" } ?: dash)
        }
    }

    /** "2026-02-13" -> "13 Feb 26"; the input as it is when it is not a date. */
    fun day(iso: String): String = runCatching { LocalDate.parse(iso).format(DAY) }.getOrDefault(iso)

    /** A trade's day, "13 Feb 26"; one over several days "13 Feb 26 – 15 Feb 26". */
    fun days(trade: Cr360History.Trade): String =
        day(trade.day) + (trade.lastDay?.let { " – " + day(it) } ?: "")

    /** "2026-02-18 19:06" -> "18 Feb 26, 19:06"; a filing without a time as its day. */
    fun filed(text: String): String {
        val time = text.drop(11).takeIf { it.length == 5 }
        return day(text.take(10)) + (time?.let { ", $it" } ?: "")
    }

    /** Rupees in Indian units: "₹85,000", "₹32.94 लाख", "₹3.29 Cr", "₹1,250 Cr". */
    fun rupees(value: Double): String {
        val v = abs(value)
        val text = when {
            v >= 1e9 -> count(v / 1e7) + " Cr"
            v >= 1e7 -> Cr360Format.number(v / 1e7, 2) + " Cr"
            v >= 1e5 -> Cr360Format.number(v / 1e5, 2) + " " + StockFormat.words.lakh
            else -> count(v)
        }
        return (if (value < 0) "−₹" else "₹") + text
    }

    /** Shares, grouped: "2,320". */
    fun shares(value: Long): String = NumberFormat.getIntegerInstance(indian).format(value)

    private fun count(value: Double): String = NumberFormat.getIntegerInstance(indian).format(value.roundToLong())
}
