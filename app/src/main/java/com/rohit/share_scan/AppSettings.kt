package com.rohit.share_scan

import android.content.Context
import androidx.core.content.edit
import java.net.URLEncoder

/** The app's colour themes; [key] is what the settings store. */
enum class ThemeChoice(val key: String) {
    GALAXY("galaxy"),
    NEBULA("nebula"),
    AURORA("aurora"),
    MIDNIGHT("midnight");

    companion object {
        fun fromKey(key: String?): ThemeChoice = entries.firstOrNull { it.key == key } ?: GALAXY
    }
}

/** Where the detail sheet's chart button opens a stock; [key] is what the settings store. */
enum class ChartSite(val key: String) {
    TRADINGVIEW("tradingview") {
        override fun url(symbol: String) = StockFormat.chartUrl(symbol)
    },
    CHARTINK("chartink") {
        override fun url(symbol: String) = "https://chartink.com/stocks/${encode(symbol.lowercase())}.html"
    },
    GOOGLE_FINANCE("google") {
        override fun url(symbol: String) = "https://www.google.com/finance/quote/${encode(symbol)}:NSE"
    },
    SCREENER("screener") {
        override fun url(symbol: String) = "https://www.screener.in/company/${encode(symbol)}/"
    };

    abstract fun url(symbol: String): String

    companion object {
        fun fromKey(key: String?): ChartSite = entries.firstOrNull { it.key == key } ?: TRADINGVIEW

        /** NSE symbols can hold '&' (M&M) and '-' (BAJAJ-AUTO); only the former needs escaping in a path. */
        private fun encode(symbol: String): String = URLEncoder.encode(symbol, "UTF-8")
    }
}

/** User choices from the menu's Settings and Theme sheets, stored in the shared [Prefs.FILE]. */
class AppSettings(context: Context) {
    private val prefs = context.getSharedPreferences(Prefs.FILE, Context.MODE_PRIVATE)

    var theme: ThemeChoice
        get() = ThemeChoice.fromKey(prefs.getString(Prefs.THEME, null))
        set(value) = prefs.edit { putString(Prefs.THEME, value.key) }

    var chartSite: ChartSite
        get() = ChartSite.fromKey(prefs.getString(Prefs.CHART_SITE, null))
        set(value) = prefs.edit { putString(Prefs.CHART_SITE, value.key) }

    /** Look for a newer release every time the app opens. */
    var checkUpdates: Boolean
        get() = prefs.getBoolean(Prefs.CHECK_UPDATES, true)
        set(value) = prefs.edit { putBoolean(Prefs.CHECK_UPDATES, value) }

    /** Also scan NSE's SME platform (Emerge); off by default since those stocks trade thinly. */
    var includeSme: Boolean
        get() = prefs.getBoolean(Prefs.INCLUDE_SME, false)
        set(value) = prefs.edit { putBoolean(Prefs.INCLUDE_SME, value) }

    /** Keep the screen awake while a scan runs. */
    var keepScreenOn: Boolean
        get() = prefs.getBoolean(Prefs.KEEP_SCREEN_ON, true)
        set(value) = prefs.edit { putBoolean(Prefs.KEEP_SCREEN_ON, value) }
}
