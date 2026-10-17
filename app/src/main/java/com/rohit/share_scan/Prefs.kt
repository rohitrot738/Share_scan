package com.rohit.share_scan

/** Shared preference file and keys used across the app. */
object Prefs {
    const val FILE = "scan"

    /** [ScanFilters.encode] string. */
    const val FILTERS = "filters"

    /** String set of watchlisted NSE symbols. */
    const val WATCHLIST = "watchlist"

    /** Score ranks from the previous completed scan, used for NEW badges and rank changes. */
    const val LAST_RANKS = "last_ranks"

    /** Version code of a release the user chose "बाद में" for; its banner stays hidden. */
    const val UPDATE_DISMISSED = "update_dismissed"

    /** [ThemeChoice.key] of the picked theme. */
    const val THEME = "theme"

    /** [ChartSite.key] the detail sheet's chart button opens. */
    const val CHART_SITE = "chart_site"

    /** Booleans from the Settings sheet, both on by default. */
    const val CHECK_UPDATES = "check_updates"
    const val KEEP_SCREEN_ON = "keep_screen_on"
}
