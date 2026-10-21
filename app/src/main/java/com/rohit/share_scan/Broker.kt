package com.rohit.share_scan

import org.json.JSONObject
import java.net.URLEncoder
import java.util.Locale

/**
 * The trading apps the detail sheet opens a stock in, by their Play Store ids. Groww has a page for every stock, and
 * its app opens groww.in links; Angel One's and Zerodha's apps take no link to a stock, so the sheet copies the
 * symbol for their search and opens the app. Free of Android types so it can be unit tested.
 */
enum class Broker(val appId: String) {
    GROWW("com.nextbillion.groww"),
    ANGEL_ONE("com.msf.angelmobile"),
    ZERODHA("com.zerodha.kite3");

    companion object {
        private const val GROWW_STOCKS = "https://groww.in/stocks/"
        private const val GROWW_SEARCH = "https://groww.in/v1/api/search/v1/entity?app=false&page=0&size=10&q="

        /** Groww's page for a stock whose page id is [id]. */
        fun growwUrl(id: String): String = GROWW_STOCKS + id

        /** Groww's search for [stock]: by its NSE symbol, or by its BSE code when it is listed on BSE alone. */
        fun growwSearchUrl(stock: ScanResult): String =
            GROWW_SEARCH + URLEncoder.encode(if (stock.market == Market.BSE) stock.bseCode.ifEmpty { stock.symbol } else stock.symbol, "UTF-8")

        /**
         * The page id Groww's search answer [json] gives [stock], matched on its NSE symbol or BSE code; null when the
         * answer does not list it. Renamed companies keep their old page ids, so the search knows them where
         * [growwGuess] does not.
         */
        fun growwPage(json: String, stock: ScanResult): String? = runCatching {
            val (field, want) = if (stock.market == Market.BSE) "bse_scrip_code" to stock.bseCode else "nse_scrip_code" to stock.symbol
            val content = JSONObject(json).optJSONArray("content") ?: return null
            (0 until content.length()).asSequence().map { content.getJSONObject(it) }
                .firstOrNull { it.optString("entity_type") == "Stocks" && want.isNotEmpty() && it.optString(field) == want }
                ?.let { it.optString("search_id").ifEmpty { it.optString("id") } }
                ?.takeIf { it.isNotEmpty() }
        }.getOrNull()

        /**
         * Groww's page id read off the company's [name], for when its search does not answer: "Reliance Industries
         * Limited" is reliance-industries-ltd. Right for most stocks, not for renamed ones; null without a name.
         */
        fun growwGuess(name: String): String? = name.lowercase(Locale.ROOT)
            .replace(LIMITED, "ltd")
            .replace("'", "")
            .replace(NOT_SLUG, "-")
            .trim('-')
            .ifEmpty { null }

        private val LIMITED = Regex("""\blimited\b""")
        private val NOT_SLUG = Regex("""[^a-z0-9]+""")
    }
}
