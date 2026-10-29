package com.rohit.share_scan

import org.json.JSONObject

/**
 * What every company makes, from the file the app carries (assets/products.json): each company's Screener profile read
 * once by cr360/products.py, since a company's products hardly change. A company the file does not have (a new
 * listing) takes the products the daily 360CR file reads for it ([Cr360Book.productsOf]). Free of Android types so it
 * can be unit tested.
 */
class ProductBook(private val entries: Map<String, List<String>>) {
    val size: Int get() = entries.size

    /** [symbol]'s products ([Products] keys), the main one first; empty when the file does not have it. */
    fun of(symbol: String): List<String> = entries[symbol].orEmpty()

    /** [symbol]'s products from this file, or else from the daily 360CR file. */
    fun of(symbol: String, cr360: Cr360Book): List<String> = of(symbol).ifEmpty { cr360.productsOf(symbol) }

    companion object {
        val EMPTY = ProductBook(emptyMap())

        /** The book in [json]; [EMPTY] for null, broken or unexpected input. Keys this version cannot name are left out. */
        fun parse(json: String?): ProductBook {
            if (json.isNullOrBlank()) return EMPTY
            return runCatching {
                val root = JSONObject(json)
                if (root.optInt("v") != 1) return EMPTY
                val stocks = root.optJSONObject("stocks") ?: return EMPTY
                val entries = HashMap<String, List<String>>(stocks.length() * 2)
                for (symbol in stocks.keys()) {
                    val list = stocks.optJSONArray(symbol) ?: continue
                    val keys = (0 until list.length()).map(list::optString).filter(Products::known)
                    if (keys.isNotEmpty()) entries[symbol] = keys
                }
                ProductBook(entries)
            }.getOrDefault(EMPTY)
        }
    }
}
