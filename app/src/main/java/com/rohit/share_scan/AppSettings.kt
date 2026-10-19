package com.rohit.share_scan

import android.content.Context
import androidx.core.content.edit
import java.net.URLEncoder

/** The app's colour themes; [key] is what the settings store. */
enum class ThemeChoice(val key: String) {
    GALAXY("galaxy"),
    NEBULA("nebula"),
    AURORA("aurora"),
    MIDNIGHT("midnight"),
    SUNSET("sunset"),
    OCEAN("ocean"),
    EMERALD("emerald"),
    MARS("mars"),
    ROYAL_GOLD("royal_gold"),
    FROST("frost");

    companion object {
        fun fromKey(key: String?): ThemeChoice = entries.firstOrNull { it.key == key } ?: GALAXY
    }
}

/** Where the detail sheet's chart button opens a stock; [key] is what the settings store. */
enum class ChartSite(val key: String) {
    TRADINGVIEW("tradingview"),
    CHARTINK("chartink"),
    GOOGLE_FINANCE("google"),
    SCREENER("screener");

    /** Chartink only covers NSE, so BSE-only stocks open on TradingView instead. */
    fun forMarket(market: Market): ChartSite = if (this == CHARTINK && market == Market.BSE) TRADINGVIEW else this

    /** The page for a stock; BSE-only stocks are addressed by their BSE scrip code where the site expects one. */
    fun url(symbol: String, market: Market = Market.NSE, bseCode: String = ""): String {
        val bse = market == Market.BSE
        return when (forMarket(market)) {
            TRADINGVIEW -> StockFormat.chartUrl(symbol, if (bse) "BSE" else "NSE")
            CHARTINK -> "https://chartink.com/stocks/${encode(symbol.lowercase())}.html"
            GOOGLE_FINANCE ->
                if (bse) "https://www.google.com/finance/quote/${encode(bseCode.ifEmpty { symbol })}:BOM"
                else "https://www.google.com/finance/quote/${encode(symbol)}:NSE"
            SCREENER -> "https://www.screener.in/company/${encode(if (bse) bseCode.ifEmpty { symbol } else symbol)}/"
        }
    }

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

    /** How much glass the screens are made of; MainActivity lays it over the theme when the screen is created. */
    var glassStyle: GlassStyle
        get() = GlassStyle.fromKey(prefs.getString(Prefs.GLASS_STYLE, null))
        set(value) = prefs.edit { putString(Prefs.GLASS_STYLE, value.key) }

    /** Glass on at all; off makes every surface flat and solid but keeps [glassStyle] for when it is back on. */
    var glassEnabled: Boolean
        get() = prefs.getBoolean(Prefs.GLASS_ENABLED, true)
        set(value) = prefs.edit { putBoolean(Prefs.GLASS_ENABLED, value) }

    /** Let the sky's stars twinkle, drift and shoot; off holds it still, which saves battery. */
    var skyAnimation: Boolean
        get() = prefs.getBoolean(Prefs.SKY_ANIMATION, true)
        set(value) = prefs.edit { putBoolean(Prefs.SKY_ANIMATION, value) }

    /** Look for a newer release every time the app opens. */
    var checkUpdates: Boolean
        get() = prefs.getBoolean(Prefs.CHECK_UPDATES, true)
        set(value) = prefs.edit { putBoolean(Prefs.CHECK_UPDATES, value) }

    /** Scan NSE's main board; on by default. Settings keeps at least one of the three markets on. */
    var includeNse: Boolean
        get() = prefs.getBoolean(Prefs.INCLUDE_NSE, true)
        set(value) = prefs.edit { putBoolean(Prefs.INCLUDE_NSE, value) }

    /** Also scan NSE's SME platform (Emerge); off by default since those stocks trade thinly. */
    var includeSme: Boolean
        get() = prefs.getBoolean(Prefs.INCLUDE_SME, false)
        set(value) = prefs.edit { putBoolean(Prefs.INCLUDE_SME, value) }

    /** Also scan stocks listed only on BSE; off by default since it nearly doubles the scan. */
    var includeBse: Boolean
        get() = prefs.getBoolean(Prefs.INCLUDE_BSE, false)
        set(value) = prefs.edit { putBoolean(Prefs.INCLUDE_BSE, value) }

    /** Keep the screen awake while a scan runs. */
    var keepScreenOn: Boolean
        get() = prefs.getBoolean(Prefs.KEEP_SCREEN_ON, true)
        set(value) = prefs.edit { putBoolean(Prefs.KEEP_SCREEN_ON, value) }

    /** Live mode: while the market is open, scan on its own every [liveInterval] minutes, even with the app closed. */
    var liveScan: Boolean
        get() = prefs.getBoolean(Prefs.LIVE_SCAN, true)
        set(value) = prefs.edit { putBoolean(Prefs.LIVE_SCAN, value) }

    /** Minutes between live mode's full scans; one of [LIVE_INTERVALS]. */
    var liveInterval: Int
        get() = prefs.getInt(Prefs.LIVE_INTERVAL, DEFAULT_LIVE_INTERVAL).takeIf { it in LIVE_INTERVALS } ?: DEFAULT_LIVE_INTERVAL
        set(value) = prefs.edit { putInt(Prefs.LIVE_INTERVAL, value) }

    /** Notify when a live scan finds stocks newly STRONG or breaking out. */
    var liveAlerts: Boolean
        get() = prefs.getBoolean(Prefs.LIVE_ALERTS, true)
        set(value) = prefs.edit { putBoolean(Prefs.LIVE_ALERTS, value) }

    /** Notify on watchlisted stocks' breakouts, targets and stops. */
    var watchAlerts: Boolean
        get() = prefs.getBoolean(Prefs.WATCH_ALERTS, true)
        set(value) = prefs.edit { putBoolean(Prefs.WATCH_ALERTS, value) }

    companion object {
        /** Live mode's choices; the background check itself runs every 15 minutes, Android's shortest period. */
        val LIVE_INTERVALS = listOf(15, 30, 60)
        const val DEFAULT_LIVE_INTERVAL = 30
    }
}
