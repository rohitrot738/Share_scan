package com.rohit.share_scan

import java.text.NumberFormat
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * Insider trades and bulk/block deals ([Cr360History.Trade]) as the detail sheet's tables show them, and as a CSV
 * file, which Excel and Google Sheets open as a sheet. Free of Android types so it can be unit tested.
 */
object TradeSheet {
    /** Rows a table shows at first, and adds with each "और दिखाएँ". */
    const val PAGE = 50

    private val indian: Locale = Locale.forLanguageTag("en-IN")
    private val DAY = DateTimeFormatter.ofPattern("d MMM yy", Locale.US)

    /** Newest first: by day, then by when NSE published the filing. */
    fun newestFirst(trades: List<Cr360History.Trade>): List<Cr360History.Trade> =
        trades.sortedWith(compareByDescending<Cr360History.Trade> { it.day }.thenByDescending { it.filed.orEmpty() })

    /** Rupees bought and rupees sold. */
    fun totals(trades: List<Cr360History.Trade>): Pair<Double, Double> =
        trades.filter { it.buy }.sumOf { it.rupees ?: 0.0 } to trades.filterNot { it.buy }.sumOf { it.rupees ?: 0.0 }

    /** "2026-02-13" -> "13 Feb 26"; the input as it is when it is not a date. */
    fun day(iso: String): String = runCatching { LocalDate.parse(iso).format(DAY) }.getOrDefault(iso)

    /** A trade's day, "13 Feb 26"; one over several days "13 Feb 26 – 15 Feb 26". */
    fun days(trade: Cr360History.Trade): String =
        day(trade.day) + (trade.lastDay?.let { " – " + day(it) } ?: "")

    /** "2026-02-18 19:06" -> "18 Feb 26, 19:06"; the input as it is when it is not a date. */
    fun filed(text: String): String {
        val time = text.drop(11).takeIf { it.length == 5 }
        return day(text.take(10)) + (time?.let { ", $it" } ?: "")
    }

    /** Rupees in Indian units: "₹85,000", "₹32.94 लाख", "₹3.29 Cr", "₹1,250 Cr". */
    fun rupees(value: Double): String {
        val v = abs(value)
        val text = when {
            v >= 1e9 -> count(v / 1e7) + " Cr"
            v >= 1e7 -> plain(v / 1e7) + " Cr"
            v >= 1e5 -> plain(v / 1e5) + " लाख"
            else -> count(v)
        }
        return (if (value < 0) "−₹" else "₹") + text
    }

    /** Shares, grouped: "2,320". */
    fun shares(value: Long): String = NumberFormat.getIntegerInstance(indian).format(value)

    /**
     * Every trade as one sheet, newest first: [header] names its twelve columns (kind, day, last day, filed, name,
     * who, buy or sell, shares, price, rupees, how, holding after %); an insider trade's kind is [insiderKind] and a
     * deal's "Bulk" or "Block". Days are ISO and numbers plain, so a spreadsheet sorts and sums them; it starts with a
     * BOM so Excel reads Hindi.
     */
    fun csv(
        header: List<String>,
        insiderKind: String,
        buy: String,
        sell: String,
        insider: List<Cr360History.Trade>,
        deals: List<Cr360History.Trade>,
    ): String {
        val out = StringBuilder("﻿")
        out.append(header.joinToString(",") { cell(it) }).append("\r\n")
        fun row(trade: Cr360History.Trade, kind: String, who: String?) {
            val cells = listOf(
                kind, trade.day, trade.lastDay, trade.filed, trade.name, who, if (trade.buy) buy else sell,
                trade.shares?.toString(), trade.price?.let { String.format(Locale.US, "%.2f", it) },
                trade.rupees?.let { String.format(Locale.US, "%.0f", it) }, trade.how,
                trade.holdingAfter?.let { Cr360Format.number(it, 2).replace("−", "-") },
            )
            out.append(cells.joinToString(",") { cell(it.orEmpty()) }).append("\r\n")
        }
        for (trade in newestFirst(insider)) row(trade, insiderKind, trade.who)
        for (trade in newestFirst(deals)) row(trade, trade.who.orEmpty().replaceFirstChar { it.uppercaseChar() }, null)
        return out.toString()
    }

    /** A CSV cell: quoted when it holds a comma, a quote or a line break, its quotes doubled. */
    private fun cell(text: String): String =
        if (text.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) "\"" + text.replace("\"", "\"\"") + "\"" else text


    private fun count(value: Double): String = NumberFormat.getIntegerInstance(indian).format(value.roundToLong())

    private fun plain(value: Double): String = Cr360Format.number(value, 2)
}
