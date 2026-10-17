package com.rohit.share_scan

import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Plain-text summary of the shown list for Intent.ACTION_SEND; free of Android types so it can be unit tested. */
object ShareText {
    /** How many stocks from the top of the shown list are shared. */
    const val COUNT = 10

    private const val SEPARATOR = " • "
    private val DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.US)

    /** "Share Scan • 17 Oct 2026 • 2 filter लागू" */
    fun header(appName: String, day: LocalDate, filters: String): String =
        listOf(appName, DATE.format(day), filters).joinToString(SEPARATOR)

    /**
     * One stock, using [template] from the string resources: %1$d position, %2$s symbol, %3$s price,
     * %4$s score and %5$s RVOL, as in "1. TECHM ₹1,790.00 • score 88.8 • RVOL 8.05×".
     */
    fun line(template: String, position: Int, stock: ScanResult): String = String.format(
        Locale.US,
        template,
        position,
        stock.symbol,
        StockFormat.price(stock.price),
        StockFormat.score(stock.score),
        StockFormat.rvol(stock.rvol),
    )

    /** [header], then the first [COUNT] of [stocks] in the order given, numbered from 1. */
    fun build(header: String, template: String, stocks: List<ScanResult>): String = buildString {
        append(header)
        stocks.take(COUNT).forEachIndexed { index, stock -> append('\n').append(line(template, index + 1, stock)) }
    }
}
