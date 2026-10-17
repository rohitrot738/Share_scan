package com.rohit.share_scan

import java.util.Locale

/** The list's search box: matches a stock by its NSE symbol or its company name. */
object StockSearch {
    /** Shortest query, letters and digits only, that may match a name with its spaces and punctuation ignored. */
    private const val MIN_COMPACT = 3
    private val COMPANY_SUFFIX = Regex("""\s+(limited|ltd\.?)$""", RegexOption.IGNORE_CASE)

    /**
     * Case-insensitive, anywhere in the symbol or the name. A query of three or more letters and digits also
     * matches across spaces and punctuation, so "bajajauto" finds "Bajaj Auto Limited".
     */
    fun matches(stock: ScanResult, query: String): Boolean {
        val needle = query.trim()
        if (needle.isEmpty()) return true
        if (stock.symbol.contains(needle, ignoreCase = true) || stock.name.contains(needle, ignoreCase = true)) return true
        val compact = compact(needle)
        return compact.length >= MIN_COMPACT && (compact(stock.name).contains(compact) || compact(stock.symbol).contains(compact))
    }

    /** "Tata Consultancy Services Limited" → "Tata Consultancy Services", for cards where space is short. */
    fun shortName(name: String): String = name.trim().replace(COMPANY_SUFFIX, "")

    private fun compact(text: String): String = text.lowercase(Locale.ROOT).filter { it.isLetterOrDigit() }
}
